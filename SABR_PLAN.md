# Native Kotlin SABR streaming — implementation plan

## Why
YouTube is migrating to **SABR** (Server-side Adaptive BitRate, framed in **UMP**). The
legacy GET-with-byte-range `videoplayback` path we relied on (iOS client "direct"
URLs) is being deprecated/cut mid-session — symptom: plays for a while, then *every*
range request 403s and the player falls into an infinite buffer/restart loop. This
reproduces on unrelated residential networks (FTTH + 5G), so it is **not** an IP block;
it's the protocol going away. Confirmed by yt-dlp issues #12482/#13968/#15689 (web SABR-only)
and #15782/#15712 (iOS/android direct formats now 403). Fix = speak SABR ourselves.

Decision: **native Kotlin port** (no JS in the streaming hot path), feeding ExoPlayer.

## Reference
- LuanRT/googlevideo (TS) — the protocol implementation we're porting. Protos vendored
  under `app/src/main/proto/` (33 files, proto2). Key reference files in that repo:
  `src/core/Ump.ts` (UMP frame reader), `src/core/SabrStream.ts` /
  `src/utils/SabrStreamingAdapter.ts` (the state machine), `src/utils/SabrUmpProcessor.ts`.
- LuanRT/kira and LuanRT/yt-sabr-shaka-demo — end-to-end examples.

## Wire-format facts (from the vendored protos)
- **UMP framing**: a stream of parts, each `[partType: varint][partSize: varint][payload bytes]`.
  Part type IDs are in `video_streaming/ump_part_id.proto` (`UMPPartId`). The ones we handle:
  - `MEDIA_HEADER = 20` → `MediaHeader` (header_id, itag, lmt, is_init_seg, sequence_number,
    start_ms, duration_ms, content_length, format_id…). Maps a following MEDIA blob to a (format, segment).
  - `MEDIA = 21` → `[header_id: varint][raw media bytes]` (fMP4 bytes for that segment).
  - `MEDIA_END = 22` → `[header_id: varint]` finalizes a segment.
  - `FORMAT_INITIALIZATION_METADATA = 42` → `FormatInitializationMetadata` (format_id, mime_type,
    init_range, index_range, end_segment_number, duration_units/timescale). Per-format init.
  - `NEXT_REQUEST_POLICY = 35` → backoff + **playback_cookie** (must echo back next request).
  - `SABR_REDIRECT = 43` → new streaming URL (switch to it, replay request).
  - `SABR_ERROR = 44`, `STREAM_PROTECTION_STATUS = 58` (attestation/pot needed),
    `SABR_CONTEXT_UPDATE = 57` + `SABR_CONTEXT_SENDING_POLICY = 59` (ads),
    `RELOAD_PLAYER_RESPONSE = 46` (re-fetch player), `SNACKBAR_MESSAGE = 67`.
- **Request**: POST to `serverAbrStreamingUrl` (from player response streamingData) with body =
  `VideoPlaybackAbrRequest` (proto) and `&rn=` incrementing. Body fields
  (`video_streaming/video_playback_abr_request.proto`):
  - `client_abr_state` (1): set `player_time_ms` (28), `enabled_track_types_bitfield` (40)
    (0 = audio+video), media_capabilities, drc_enabled etc.
  - `selected_format_ids` (2): the formats we're currently playing (`misc.FormatId{itag,last_modified,xtags}`).
  - `buffered_ranges` (3): per-format `BufferedRange` of what we already have (drives what the server sends next).
  - `video_playback_ustreamer_config` (5): opaque bytes from player response (we already capture this as `StreamingMeta.videoPlaybackUstreamerConfig`).
  - `preferred_audio_format_ids` (16) / `preferred_video_format_ids` (17).
  - `streamer_context` (19): `StreamerContext{ client_info, po_token (2), playback_cookie (3), sabr_contexts (5) }`.
- **PO token**: required, goes in `streamer_context.po_token` (bytes). We already mint one via the WebView (`WebPoTokenProvider`). NOTE: SABR needs the **content/streaming-bound** pot for the visitorData/session — verify ours is accepted; `STREAM_PROTECTION_STATUS` tells us if attestation fails.

## Architecture (ExoPlayer integration)
ExoPlayer drives format/seek selection; we feed it segment bytes from a SABR session.

```
ExoPlayer (DASH MediaSource over a synthetic manifest)
        │  requests init+media segments via a custom DataSource
        ▼
SabrDataSource (Kotlin, DataSource)            ── exo asks for sabr://<itag>/<segIdx> (or byte ranges)
        │
        ▼
SabrClient (Kotlin)                            ── owns the SABR POST/UMP loop + per-format segment cache
        │  OkHttp POST serverAbrStreamingUrl (protobuf body) → UMP response
        ▼
googlevideo (UMP parts → MediaHeader/MEDIA/MEDIA_END → fMP4 segments)
```

Two manifest options to let ExoPlayer address segments:
1. **Synthetic DASH `SegmentTemplate` (number-based)** built from `FormatInitializationMetadata`
   (segment count from `end_segment_number`, duration from `duration_units/timescale`). ExoPlayer
   requests `init` + segment N; `SabrDataSource` returns those bytes from `SabrClient`
   (issuing SABR requests as needed, keyed on segment→time). Seeking = ExoPlayer asks for a
   different N → SABR request at that time. **Preferred** (clean seek semantics).
2. SABR-aware custom `MediaSource` (more work, fewer layers). Defer.

## Phased build order (each phase ends in an Android-Studio Gradle build + on-device test)
- **Phase 0 — protobuf infra (DONE, pending your Gradle sync to verify):**
  vendored protos + `com.google.protobuf` plugin 0.9.4 + `protobuf-javalite:3.25.5` in
  `app/build.gradle.kts`. ✅ verify: Gradle sync generates classes under
  `app/build/generated/source/proto/.../video_streaming/*` and `misc/*`.
- **Phase 1 — UMP + protobuf primitives:** `UmpReader` (varint, part iterator). Unit-decode a
  captured UMP response. Pure Kotlin, no ExoPlayer.
- **Phase 2 — SabrClient:** build `VideoPlaybackAbrRequest`, POST via OkHttp (iOS/ANDROID
  client context + our pot + ustreamerConfig), parse UMP, assemble per-format fMP4 segments,
  handle `SABR_REDIRECT` / `NEXT_REQUEST_POLICY` (playback_cookie echo) / `STREAM_PROTECTION_STATUS`.
  Verify by logging successfully fetched init+first media segment (HTTP 200, bytes > 0).
- **Phase 3 — ExoPlayer glue:** build synthetic DASH (SegmentTemplate) from
  `FormatInitializationMetadata`; `SabrDataSource(+Factory)` resolving `sabr://` to SabrClient bytes;
  `DashMediaSource`. Replace the current DASH-from-iOS path in `PlayerScreen`/`getVideoInfo`.
- **Phase 4 — seeking + ABR + hardening:** map ExoPlayer segment requests to SABR by time; let
  ExoPlayer pick quality (or keep our cap); retry/backoff; remove `ChunkedDataSource` `&range=` hack.

## What we already have to reuse
- `QuickJsRuntime` + youtubei.js → player response gives `serverAbrStreamingUrl`,
  `videoPlaybackUstreamerConfig`, adaptive format list (itag/lmt/xtags), visitorData.
  (Expose these via `getVideoInfo`/`StreamingMeta` — `serverAbrStreamingUrl` +
  `videoPlaybackUstreamerConfig` fields already exist in `VideoInfoModels.kt`.)
- `WebPoTokenProvider` → PO token bytes for `streamer_context.po_token`.
- OkHttp stack + the iOS/ANDROID client context values already used in `index.js`.

## Environment caveat for this workspace
The agent sandbox here **cannot run Gradle** (no JAVA_HOME on PATH; loopback socket blocked →
"Unable to establish loopback connection"). All build/verup must happen in Android Studio.
JBR is at `C:\Program Files\Android\Android Studio\jbr` (Java 21) if running gradlew manually
from a normal terminal: `set JAVA_HOME=...jbr` then `gradlew :app:generateDebugProto`.
