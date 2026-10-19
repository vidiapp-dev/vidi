# SABR reference source (PipePipe, GPLv3)

Verbatim copy of the SABR implementation from
`Priveetee/PipePipeExtractor@44c84c3` (`main`, 2026-07-23),
package `org.schabi.newpipe.extractor.services.youtube.sabr`.

## Refresh log

**2026-08-02** — refreshed from `3aafd5a` (PR #69 branch, not reachable from `main`) to `44c84c3`.
The old pin was far enough behind to be actively misleading: several "we're byte-identical to the
reference, so it can't be X" conclusions in `FIXES_AND_ANALYSIS_2026-07-30.md` were resting on it.
The refresh brought 11 new files and large rewrites (`YoutubeSabrSession` ~1536 diff lines,
`YoutubeSabrRequestBuilder` ~84).

`main` already contains the fixes that looked like unmerged branches:
`avoid SABR OOM from segment buffer growth (#77)`, `expose SABR init metadata (#78)`,
`respect active SABR tracks on cold start (#80)`, and `stop SABR UMP reads when interrupted`.
Cherry-picking `fix/2577-sabr-segment-oom`, `fix/2575-sabr-audio-only-cold-start` and
`fix/2577-sabr-init-metadata` onto `main` conflicts precisely because their content is already
there — those branches are stale, sitting 63-88 commits behind. **Take `main`; don't cherry-pick.**

To refresh again:

```sh
git clone --filter=blob:none https://github.com/Priveetee/PipePipeExtractor
cp PipePipeExtractor/extractor/src/main/java/org/schabi/newpipe/extractor/services/youtube/sabr/*.java \
   sabr-reference/
```

**Not compiled** — this folder is outside the Kotlin/Java source set. It's the
reference we port from into `app/src/main/java/...` incrementally. Source is
**GPLv3**; this project therefore adopts GPLv3 for the ported SABR code.

Porting boundary (what we replace rather than copy):
- `YoutubeSabrProbe` (NewPipe Downloader + player-JS + nanojson) → our OkHttp +
  QuickJS/youtubei.js info layer.
- `SabrPoTokenProvider` impl → backed by our `WebPoTokenProvider` + `SabrColdStartPoToken`.
- `ExtractionException`/`Localization`/`ContentCountry` deps → trimmed or shimmed.
