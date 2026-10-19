# yt-android JS runtime

Bundles `youtubei.js` (and, in later milestones, `bgutils-js` + `googlevideo`) into a single self-contained script that runs inside the QuickJS engine embedded in the Android app.

## Build

```bash
cd js-runtime
npm install
npm run build
```

Output is written to `../app/src/main/assets/youtube-runtime.js`. Re-run after any change here, then rebuild the Android app.

## How it plugs in

- **Bundle entry**: `src/index.js` — installs polyfills, creates an `Innertube` session, and exposes async methods on `globalThis.__yt`.
- **fetch**: polyfilled in `src/polyfills.js`, delegates to Kotlin via `globalThis.__nativeFetch(JSON.stringify({method, url, headers, body}))`.
- **console**: routed to logcat via `globalThis.__nativeLog(level, msg)`.
- **Kotlin entry point**: `QuickJsRuntime.callAsync("search", "...")` evaluates `await __yt.search("...")` and JSON-stringifies the result.

## Milestone 1 surface

| JS                  | Kotlin caller                              | Returns                |
|---------------------|--------------------------------------------|------------------------|
| `__yt.search(q)`    | `YoutubeBridge.search(q)`                  | `{ videos: [...] }`    |

## Watch mode

```bash
npm run watch
```

Rebuilds on save. Re-launch the app after each rebuild — QuickJS only loads the bundle once at process start.
