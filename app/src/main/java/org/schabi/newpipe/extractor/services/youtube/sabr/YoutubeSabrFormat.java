package org.schabi.newpipe.extractor.services.youtube.sabr;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Ported from PipePipe (GPLv3) — see {@code sabr-reference/}, refreshed from upstream {@code 44c84c3}.
 *
 * <p><b>Porting boundary.</b> Upstream's copy also carries a signature-cipher / {@code n}-parameter
 * deobfuscation subsystem ({@code fromAdaptiveFormats(videoId, …)}, {@code collectDecodeParameters},
 * {@code resolveInitializationUrls}, {@code extractNParameter}, and {@code StreamingUrlParts}'s
 * cipher branch) built on {@code YoutubeJavaScriptPlayerManager} / {@code YoutubeApiDecoder}. We do
 * that work in QuickJS/youtubei.js before the DTO ever reaches this class — the adaptive formats
 * handed to {@link #fromAdaptiveFormats} already carry deciphered URLs (see the
 * {@code abrUrl n-decipher} log line). So those methods, the NewPipe imports they need, and the
 * four cipher-only fields ({@code initializationUrlTemplate}, {@code obfuscatedSignature},
 * {@code signatureParameter}, {@code obfuscatedNParameter}) are deliberately absent here.
 *
 * <p>Everything else is kept as upstream has it — including the init-range metadata from "expose
 * SABR init metadata" (#78), which {@link SabrFormatInitializationMetadata} and
 * {@link YoutubeSabrSession} now depend on.
 */
public final class YoutubeSabrFormat implements Serializable {
    private static final long serialVersionUID = 1L;

    private final int itag;
    private final long lastModified;
    @Nullable
    private final String xtags;
    @Nullable
    private final String mimeType;
    @Nullable
    private final String audioTrackId;
    @Nullable
    private final String audioTrackDisplayName;
    private final boolean audioIsDefault;
    @Nullable
    private final String qualityLabel;
    @Nullable
    private final String audioQuality;
    private final boolean drc;
    private final int width;
    private final int height;
    private final int bitrate;
    private final long contentLength;
    private final long approxDurationMs;
    // Final here, unlike upstream: only the dropped deobfuscation path mutated it post-construction.
    @Nullable
    private final String initializationUrl;
    private final long initRangeStart;
    private final long initRangeEnd;

    private YoutubeSabrFormat(final int itag,
                              final long lastModified,
                              @Nullable final String xtags,
                              @Nullable final String mimeType,
                              @Nullable final String audioTrackId,
                              @Nullable final String audioTrackDisplayName,
                              final boolean audioIsDefault,
                              @Nullable final String qualityLabel,
                              @Nullable final String audioQuality,
                              final boolean drc,
                              final int width,
                              final int height,
                              final int bitrate,
                              final long contentLength,
                              final long approxDurationMs,
                              @Nullable final String initializationUrl,
                              final long initRangeStart,
                              final long initRangeEnd) {
        this.itag = itag;
        this.lastModified = lastModified;
        this.xtags = xtags;
        this.mimeType = mimeType;
        this.audioTrackId = audioTrackId;
        this.audioTrackDisplayName = audioTrackDisplayName;
        this.audioIsDefault = audioIsDefault;
        this.qualityLabel = qualityLabel;
        this.audioQuality = audioQuality;
        this.drc = drc;
        this.width = width;
        this.height = height;
        this.bitrate = bitrate;
        this.contentLength = contentLength;
        this.approxDurationMs = approxDurationMs;
        this.initializationUrl = initializationUrl;
        this.initRangeStart = initRangeStart;
        this.initRangeEnd = initRangeEnd;
    }

    @Nonnull
    static List<YoutubeSabrFormat> fromAdaptiveFormats(@Nullable final JsonArray formats) {
        final List<YoutubeSabrFormat> result = new ArrayList<>();
        if (formats == null) {
            return result;
        }
        for (int i = 0; i < formats.size(); i++) {
            final JsonObject format = formats.getObject(i);
            if (format != null && format.has("itag")) {
                result.add(fromJson(format));
            }
        }
        return result;
    }

    @Nonnull
    private static YoutubeSabrFormat fromJson(@Nonnull final JsonObject format) {
        final JsonObject audioTrack = format.getObject("audioTrack");
        // initRange/indexRange (upstream #78): the init segment spans
        // initRange.start .. max(initRange.end, indexRange.end) — the index box sits directly after
        // the init box and both are needed before the first media segment can be parsed.
        final JsonObject initRange = format.getObject("initRange");
        final JsonObject indexRange = format.getObject("indexRange");
        final long initRangeStart = initRange == null ? -1 : parseLong(initRange.get("start"));
        long initRangeEnd = initRange == null ? -1 : parseLong(initRange.get("end"));
        if (indexRange != null) {
            final long indexRangeEnd = parseLong(indexRange.get("end"));
            if (indexRangeEnd > initRangeEnd) {
                initRangeEnd = indexRangeEnd;
            }
        }
        // Plain `url` only — see the porting-boundary note above. A signatureCipher arriving here
        // would mean the JS layer failed to decipher, which is a bug to fix there rather than paper
        // over by re-implementing the cipher in Java.
        final String url = format.getString("url");
        return new YoutubeSabrFormat(
                format.getInt("itag"),
                parseLong(format.get("lastModified")),
                format.getString("xtags"),
                format.getString("mimeType"),
                audioTrack == null ? null : audioTrack.getString("id"),
                audioTrack == null ? null : audioTrack.getString("displayName"),
                audioTrack != null && audioTrack.getBoolean("audioIsDefault", false),
                format.getString("qualityLabel"),
                format.getString("audioQuality"),
                format.getBoolean("isDrc", false),
                format.getInt("width", -1),
                format.getInt("height", -1),
                format.getInt("bitrate", -1),
                parseLong(format.get("contentLength")),
                parseLong(format.get("approxDurationMs")),
                url == null || url.isEmpty() ? null : url,
                initRangeStart, initRangeEnd);
    }

    private static long parseLong(@Nullable final Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            try {
                return Long.parseLong((String) value);
            } catch (final NumberFormatException ignored) {
                return -1;
            }
        }
        return -1;
    }

    public boolean isAudio() {
        return mimeType != null && mimeType.contains("audio");
    }

    public boolean isVideo() {
        return mimeType != null && mimeType.contains("video");
    }

    public int getItag() {
        return itag;
    }

    public long getLastModified() {
        return lastModified;
    }

    @Nullable
    public String getXtags() {
        return xtags;
    }

    @Nullable
    public String getMimeType() {
        return mimeType;
    }

    @Nullable
    public String getAudioTrackId() {
        return audioTrackId;
    }

    @Nullable
    public String getAudioTrackDisplayName() {
        return audioTrackDisplayName;
    }

    public boolean isAudioDefault() {
        return audioIsDefault;
    }

    /**
     * True when this is the source/original-language audio track (not an auto-dub). YouTube marks
     * it in the localized display name, e.g. "French (original)". Mirrors the detection used for
     * non-SABR streams.
     */
    public boolean isOriginalAudio() {
        return audioTrackDisplayName != null
                && (audioTrackDisplayName.contains("original")
                    || audioTrackDisplayName.contains("yokuqala"));
    }

    @Nullable
    public String getQualityLabel() {
        return qualityLabel;
    }

    @Nullable
    public String getAudioQuality() {
        return audioQuality;
    }

    public boolean isDrc() {
        return drc;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public int getBitrate() {
        return bitrate;
    }

    public long getContentLength() {
        return contentLength;
    }

    public long getApproxDurationMs() {
        return approxDurationMs;
    }

    @Nullable
    public String getInitializationUrl() {
        return initializationUrl;
    }

    public long getInitRangeStart() {
        return initRangeStart;
    }

    public long getInitRangeEnd() {
        return initRangeEnd;
    }
}
