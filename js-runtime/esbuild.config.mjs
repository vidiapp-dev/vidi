// Bundles src/index.js into a single self-contained script that runs inside
// QuickJS. Output is written directly into the Android assets folder so a
// subsequent `gradle assembleDebug` picks it up.
//
// Run:  npm run build
import esbuild from 'esbuild';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const outFile = path.resolve(__dirname, '../app/src/main/assets/youtube-runtime.js');

const watch = process.argv.includes('--watch');

/** @type {import('esbuild').BuildOptions} */
const options = {
  entryPoints: [path.resolve(__dirname, 'src/index.js')],
  bundle: true,
  outfile: outFile,
  format: 'iife',
  globalName: '__ytBundle',
  platform: 'neutral',
  target: ['es2020'],
  mainFields: ['browser', 'module', 'main'],
  conditions: ['browser', 'default'],
  // Inline anything that tries to import node built-ins.
  // youtubei.js works in browser mode, but a few transitive deps poke at node:* — alias them to empty shims.
  alias: {
    'node:stream/web': path.resolve(__dirname, 'src/shims/empty.js'),
    'node:buffer': path.resolve(__dirname, 'src/shims/empty.js'),
    'node:util': path.resolve(__dirname, 'src/shims/empty.js'),
  },
  // QuickJS has no eval-based sourcemap support; skip sourcemaps to keep bundle small.
  sourcemap: false,
  // Keep readable output for debugging; switch to true for production.
  minify: false,
  // 'silent', NOT the default 'info'. esbuild writes its build summary to STDERR, and Gradle runs
  // this through an Exec task — so on Windows, `.\gradlew … 2>&1` turns every one of those lines
  // into a PowerShell ErrorRecord and the build reports `NativeCommandError` on a completely
  // successful build. Training yourself to ignore that is how a real failure gets missed. The
  // summary esbuild would have printed is reproduced below on stdout, in ASCII (its ⚠/⚡ arrive as
  // mojibake through a legacy Windows console codepage anyway).
  //
  // This does NOT weaken failure reporting: esbuild.build() still rejects on error, which is caught
  // below and exits non-zero, and Exec fails the Gradle build on a non-zero exit.
  logLevel: 'silent',
  legalComments: 'none',
  define: {
    'process.env.NODE_ENV': '"production"',
    'global': 'globalThis',
  },
};

/** Reproduce the parts of esbuild's own summary that are worth having, on stdout, in ASCII. */
function report(result) {
  for (const w of result.warnings ?? []) {
    const at = w.location ? ` (${w.location.file}:${w.location.line})` : '';
    console.log(`[esbuild] warning: ${w.text}${at}`);
  }
  const kb = (fs.statSync(outFile).size / 1024).toFixed(0);
  console.log(`[esbuild] wrote ${outFile} (${kb} KB)`);
}

try {
  if (watch) {
    const ctx = await esbuild.context(options);
    await ctx.watch();
    console.log(`[esbuild] watching -> ${outFile}`);
  } else {
    report(await esbuild.build(options));
  }
} catch (err) {
  // A genuine failure: this one belongs on stderr, and must not leave a stale bundle looking fresh.
  for (const e of err?.errors ?? []) {
    const at = e.location ? ` (${e.location.file}:${e.location.line}:${e.location.column})` : '';
    console.error(`[esbuild] error: ${e.text}${at}`);
  }
  if (!err?.errors?.length) console.error(`[esbuild] ${(err && err.message) || err}`);
  process.exit(1);
}
