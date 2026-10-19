package org.schabi.newpipe.extractor.services.youtube.sabr;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonParserException;

import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.downloader.StreamingResponse;
import org.schabi.newpipe.extractor.localization.Localization;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * App-specific replacement for PipePipe's {@code YoutubeSabrProbe}.
 *
 * <p>This is the SABR I/O boundary. PipePipe's original built the Innertube player request itself
 * (NewPipe Downloader + player JS + nanojson) and posted media requests through NewPipe's
 * Downloader. We keep PipePipe's <em>request-building and response-decoding</em> (verbatim
 * {@link YoutubeSabrRequestBuilder} / {@link SabrResponseDecoder}) but:
 *
 * <ul>
 *   <li>The media POST goes through OkHttp instead of NewPipe's Downloader. The query parameters
 *       ({@code alr=yes}, {@code cpn}, {@code rn}) and headers are reproduced exactly from the
 *       original so the wire request is identical.</li>
 *   <li>{@link YoutubeSabrInfo} is built from our QuickJS/youtubei.js player response via
 *       {@link #buildInfo}, called by the Kotlin bridge — not re-fetched here.</li>
 *   <li>{@link #fetchSabrInfo} (used only on a server-requested reload) delegates to a pluggable
 *       {@link SabrInfoProvider} the Kotlin bridge installs. If none is installed, a reload fails
 *       and the player's existing self-heal takes over.</li>
 * </ul>
 *
 * <p>{@link YoutubeSabrSession} is ported verbatim and calls the package-private media-response and
 * {@code fetchSabrInfo} methods below; their signatures match the originals exactly.
 */
public final class YoutubeSabrProbe {

    /**
     * Supplies a fresh {@link YoutubeSabrInfo} for a server-requested player reload (URLs / ustreamer
     * config expired mid-watch). Backed by the Kotlin bridge's QuickJS/youtubei.js info layer.
     */
    public interface SabrInfoProvider {
        @Nonnull
        YoutubeSabrInfo fetch(@Nonnull String videoId,
                              @Nonnull YoutubeSabrClientProfile profile,
                              @Nonnull Localization localization,
                              @Nonnull ContentCountry contentCountry)
                throws IOException, ExtractionException;
    }

    @Nullable
    private static volatile SabrInfoProvider infoProvider;
    @Nullable
    private static volatile OkHttpClient httpClient;

    private YoutubeSabrProbe() {
    }

    /** Install the reload info provider (Kotlin bridge). */
    public static void setSabrInfoProvider(@Nullable final SabrInfoProvider provider) {
        infoProvider = provider;
    }

    /** Share the app's OkHttp stack (the one proven to get 206s from googlevideo). */
    public static void setHttpClient(@Nullable final OkHttpClient client) {
        httpClient = client;
    }

    @Nonnull
    private static OkHttpClient client() {
        OkHttpClient local = httpClient;
        if (local == null) {
            synchronized (YoutubeSabrProbe.class) {
                local = httpClient;
                if (local == null) {
                    local = new OkHttpClient.Builder()
                            .connectTimeout(15, TimeUnit.SECONDS)
                            .readTimeout(30, TimeUnit.SECONDS)
                            .writeTimeout(30, TimeUnit.SECONDS)
                            .build();
                    httpClient = local;
                }
            }
        }
        return local;
    }

    /**
     * Build a {@link YoutubeSabrInfo} from data the Kotlin bridge already extracted from the
     * youtubei.js player response. {@code adaptiveFormatsJson} is the raw JSON text of
     * {@code streamingData.adaptiveFormats}.
     */
    @Nonnull
    public static YoutubeSabrInfo buildInfo(@Nonnull final YoutubeSabrClientProfile profile,
                                            @Nonnull final String videoId,
                                            @Nonnull final String cpn,
                                            @Nonnull final String clientVersion,
                                            @Nullable final String visitorData,
                                            @Nullable final String serverAbrStreamingUrl,
                                            @Nullable final String ustreamerConfig,
                                            @Nullable final String adaptiveFormatsJson)
            throws SabrProtocolException {
        final JsonArray formats;
        try {
            formats = adaptiveFormatsJson == null || adaptiveFormatsJson.isEmpty()
                    ? new JsonArray()
                    : JsonParser.array().from(adaptiveFormatsJson);
        } catch (final JsonParserException e) {
            throw new SabrProtocolException("Could not parse adaptiveFormats JSON", e);
        }
        return new YoutubeSabrInfo(profile, videoId, cpn, clientVersion, visitorData,
                serverAbrStreamingUrl, ustreamerConfig,
                YoutubeSabrFormat.fromAdaptiveFormats(formats));
    }

    /**
     * Generate a locally-computed cold-start PO token (no server round-trip). The Kotlin bridge
     * seeds this as the session's initial token so the very first SABR request already carries one;
     * a real WebView/BotGuard token is only minted if the server answers status=3. Public bridge to
     * the package-private {@link SabrColdStartPoToken}.
     */
    @Nonnull
    public static byte[] coldStartPoToken(@Nonnull final String identifier, final int clientState)
            throws SabrProtocolException {
        return SabrColdStartPoToken.generate(identifier, clientState);
    }

    @Nonnull
    static YoutubeSabrInfo fetchSabrInfo(@Nonnull final String videoId,
                                         @Nonnull final YoutubeSabrClientProfile profile,
                                         @Nonnull final Localization localization,
                                         @Nonnull final ContentCountry contentCountry)
            throws IOException, ExtractionException {
        final SabrInfoProvider provider = infoProvider;
        if (provider == null) {
            throw new SabrProtocolException("SABR player-response reload is not supported "
                    + "(no SabrInfoProvider configured)");
        }
        return provider.fetch(videoId, profile, localization, contentCountry);
    }

    /**
     * The media POST. Signature mirrors upstream so {@link YoutubeSabrSession} can call it
     * unmodified; the transport underneath is our OkHttp stack instead of NewPipe's
     * {@code Downloader.postStreaming} (see the porting boundary in {@code sabr-reference/README}).
     */
    @Nonnull
    static YoutubeSabrProbeResult postMediaRequest(
            @Nonnull final YoutubeSabrInfo info,
            @Nonnull final byte[] requestBody,
            final int requestNumber,
            @Nullable final String serverAbrStreamingUrlOverride,
            @Nullable final SabrStreamingResponseReader.StoppableSegmentConsumer segmentConsumer,
            @Nullable final SabrStreamingResponseReader.SegmentConsumer segmentStartConsumer,
            @Nullable final File segmentSpoolDirectory,
            @Nonnull final Localization localization,
            @Nonnull final SabrMediaProtocol mediaProtocol)
            throws IOException, ExtractionException {
        final String serverAbrStreamingUrl = serverAbrStreamingUrlOverride == null
                || serverAbrStreamingUrlOverride.isEmpty()
                ? info.getServerAbrStreamingUrl()
                : serverAbrStreamingUrlOverride;
        if (serverAbrStreamingUrl == null || serverAbrStreamingUrl.isEmpty()) {
            throw new SabrProtocolException("Missing serverAbrStreamingUrl");
        }

        final String url = withSabrSessionParameters(serverAbrStreamingUrl, info.getCpn(),
                requestNumber);
        final Request.Builder builder = new Request.Builder().url(url);
        for (final Map.Entry<String, List<String>> entry : buildSabrHeaders(info).entrySet()) {
            for (final String value : entry.getValue()) {
                builder.addHeader(entry.getKey(), value);
            }
        }
        // Null media type: the Content-Type (when present) comes from the header map above, matching
        // the original exactly (web profiles intentionally send no Content-Type).
        builder.post(RequestBody.create(requestBody, (MediaType) null));

        // Timing wrappers around the caller's consumers: the session uses firstSegmentElapsedMs to
        // tell "server is slow" apart from "server sent nothing", which the old single-number
        // result could not express.
        final long requestStartNs = System.nanoTime();
        final long[] firstSegmentElapsedMs = {-1};
        final SabrStreamingResponseReader.StoppableSegmentConsumer timedConsumer =
                segmentConsumer == null ? null : segment -> {
                    if (firstSegmentElapsedMs[0] < 0) {
                        firstSegmentElapsedMs[0] = elapsedMs(requestStartNs);
                    }
                    return segmentConsumer.accept(segment);
                };
        final SabrStreamingResponseReader.SegmentConsumer timedStartConsumer =
                segmentStartConsumer == null ? null : segment -> {
                    if (firstSegmentElapsedMs[0] < 0) {
                        firstSegmentElapsedMs[0] = elapsedMs(requestStartNs);
                    }
                    segmentStartConsumer.accept(segment);
                };

        try (Response response = client().newCall(builder.build()).execute()) {
            final String contentType = response.header("Content-Type");
            // Content-type check from the header (no body read), so we don't consume the body before
            // streaming it. Error responses are small → read whole for the diagnostic snippet.
            if (contentType == null
                    || !contentType.toLowerCase().contains("application/vnd.yt-ump")) {
                final byte[] errBody = response.body() == null
                        ? new byte[0]
                        : response.body().bytes();
                final String snippet = errBody.length == 0
                        ? ""
                        : new String(errBody, 0, Math.min(errBody.length, 200),
                                java.nio.charset.StandardCharsets.UTF_8);
                throw new SabrProtocolException("Expected UMP response, got content type: "
                        + contentType + ", status=" + response.code() + ", body=" + snippet);
            }
            // GC fix: stream the UMP body so the (multi-MB) MEDIA payloads are never all held at once.
            // Peak transient drops from the whole response body to a single in-flight segment.
            // SabrStreamingResponseReader assembles segments on the fly; only small control parts are
            // retained. (Ported from PipePipeExtractor PR #69, adapted to our OkHttp probe.)
            final CountingInputStream body = new CountingInputStream(response.body() == null
                    ? new java.io.ByteArrayInputStream(new byte[0])
                    : response.body().byteStream());
            final SabrStreamingResponseReader.Result streamed;
            try {
                streamed = SabrStreamingResponseReader.readUntil(body, timedConsumer,
                        timedStartConsumer, segmentSpoolDirectory, mediaProtocol);
            } catch (final IOException | ExtractionException e) {
                throw e;
            } catch (final Throwable t) {
                // A malformed response must surface as a handled protocol error, not a silent
                // RuntimeException that kills the pump thread.
                throw new SabrProtocolException("SABR streaming read failed: " + t, t);
            }
            if (YoutubeSabrSession.DIAG) {
                android.util.Log.i("YoutubeSabrProbe", "SABR POST rn=" + (requestNumber + 1)
                        + " -> HTTP " + response.code() + " ct=" + contentType
                        + " segs=" + streamed.getSegments().size());
            }
            return new YoutubeSabrProbeResult(info, streamed.getDecodedResponse(),
                    streamed.getSegments(), streamed.getSegmentCount(), response.code(),
                    contentType, body.getCount(), streamed.getMediaPayloadBytes(),
                    streamed.getMediaPartPayloadBytes(), streamed.getControlPayloadBytes(),
                    streamed.getTotalPayloadBytes(), streamed.getMaxPartBytes(),
                    streamed.getMaxMediaPartPayloadBytes(), streamed.getMaxSegmentBytes(),
                    elapsedMs(requestStartNs), firstSegmentElapsedMs[0]);
        }
    }

    /**
     * Range GET over the shared OkHttp stack, exposed as a {@link StreamingResponse} so
     * {@link YoutubeSabrSession#fetchInitializationData} can use it in place of NewPipe's
     * {@code Downloader.getStreaming}. Only the download path uses this today.
     */
    @Nonnull
    static StreamingResponse getStreaming(@Nonnull final String url,
                                          @Nonnull final Map<String, List<String>> headers,
                                          final long timeoutMs) throws IOException {
        final Request.Builder builder = new Request.Builder().url(url);
        for (final Map.Entry<String, List<String>> entry : headers.entrySet()) {
            for (final String value : entry.getValue()) {
                builder.addHeader(entry.getKey(), value);
            }
        }
        OkHttpClient http = client();
        if (timeoutMs > 0) {
            http = http.newBuilder()
                    .callTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .build();
        }
        final Response response = http.newCall(builder.get().build()).execute();
        final java.io.InputStream in = response.body() == null
                ? new java.io.ByteArrayInputStream(new byte[0])
                : response.body().byteStream();
        return new StreamingResponse(response.code(), response.headers().toMultimap(), in);
    }

    private static long elapsedMs(final long startNs) {
        return Math.max(0, (System.nanoTime() - startNs) / 1_000_000L);
    }

    /** Counts bytes off the wire so the result can report the real response size. */
    private static final class CountingInputStream extends java.io.FilterInputStream {
        private long count;

        private CountingInputStream(@Nonnull final java.io.InputStream input) {
            super(input);
        }

        @Override
        public int read() throws IOException {
            final int value = super.read();
            if (value >= 0) {
                count++;
            }
            return value;
        }

        @Override
        public int read(@Nonnull final byte[] buffer, final int offset, final int length)
                throws IOException {
            final int read = super.read(buffer, offset, length);
            if (read > 0) {
                count += read;
            }
            return read;
        }

        private long getCount() {
            return count;
        }
    }


    @Nonnull
    private static Map<String, List<String>> buildSabrHeaders(@Nonnull final YoutubeSabrInfo info) {
        final Map<String, List<String>> headers = new HashMap<>();
        headers.put("Content-Type", Collections.singletonList("application/x-protobuf"));
        headers.put("Accept", Collections.singletonList("application/vnd.yt-ump"));
        headers.put("Accept-Encoding", Collections.singletonList("identity"));
        if (info.getProfile().getUserAgent() != null) {
            headers.put("User-Agent", Collections.singletonList(info.getProfile().getUserAgent()));
        }
        if (!isWebSabrProfile(info.getProfile())
                && info.getVisitorData() != null && !info.getVisitorData().isEmpty()) {
            headers.put("X-Goog-Visitor-Id", Collections.singletonList(info.getVisitorData()));
        }
        if (isWebSabrProfile(info.getProfile())) {
            headers.remove("Content-Type");
            headers.remove("Accept-Encoding");
            headers.put("Accept", Collections.singletonList("*/*"));
            headers.put("Accept-Language", Collections.singletonList("en-US,en;q=0.9"));
            headers.put("Origin", Collections.singletonList("https://www.youtube.com"));
            headers.put("Referer", Collections.singletonList("https://www.youtube.com/"));
        }
        return headers;
    }

    private static boolean isWebSabrProfile(@Nonnull final YoutubeSabrClientProfile profile) {
        // SAFARI_WEB was in the old pinned reference and is gone from upstream `44c84c3` — the enum
        // is now WEB / MWEB / WEB_EMBEDDED / ANDROID / ANDROID_VR / IOS, and the web-family ones all
        // carry the `webLike` flag, so isWebLike() already covers what that arm was for.
        return profile.isWebLike() || profile == YoutubeSabrClientProfile.WEB;
    }

    @Nonnull
    private static String withSabrSessionParameters(@Nonnull final String url,
                                                    @Nonnull final String cpn,
                                                    final int requestNumber) {
        String result = appendQueryParameterIfMissing(url, "alr", "yes");
        result = appendQueryParameterIfMissing(result, "cpn", cpn);
        return setQueryParameter(result, "rn", String.valueOf(requestNumber + 1));
    }

    @Nonnull
    private static String appendQueryParameterIfMissing(@Nonnull final String url,
                                                        @Nonnull final String name,
                                                        @Nonnull final String value) {
        if (url.contains("?" + name + "=") || url.contains("&" + name + "=")) {
            return url;
        }
        return appendQueryParameter(url, name, value);
    }

    @Nonnull
    private static String appendQueryParameter(@Nonnull final String url,
                                               @Nonnull final String name,
                                               @Nonnull final String value) {
        final String separator = url.contains("?") ? "&" : "?";
        return url + separator + name + "=" + value;
    }

    @Nonnull
    private static String setQueryParameter(@Nonnull final String url,
                                            @Nonnull final String name,
                                            @Nonnull final String value) {
        final int fragmentIndex = url.indexOf('#');
        final String baseUrl = fragmentIndex < 0 ? url : url.substring(0, fragmentIndex);
        final String fragment = fragmentIndex < 0 ? "" : url.substring(fragmentIndex);
        final int queryIndex = baseUrl.indexOf('?');
        final String path = queryIndex < 0 ? baseUrl : baseUrl.substring(0, queryIndex);
        final String query = queryIndex < 0 ? "" : baseUrl.substring(queryIndex + 1);

        final StringBuilder result = new StringBuilder(path).append('?');
        boolean wroteParameter = false;
        for (final String parameter : query.split("&", -1)) {
            if (parameter.isEmpty()) {
                continue;
            }
            final int equalsIndex = parameter.indexOf('=');
            final String parameterName = equalsIndex < 0
                    ? parameter
                    : parameter.substring(0, equalsIndex);
            if (parameterName.equals(name)) {
                continue;
            }
            if (wroteParameter) {
                result.append('&');
            }
            result.append(parameter);
            wroteParameter = true;
        }
        if (wroteParameter) {
            result.append('&');
        }
        return result.append(name).append('=').append(value).append(fragment).toString();
    }
}
