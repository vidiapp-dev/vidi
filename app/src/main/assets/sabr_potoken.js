/*
 * SABR GVS PO-token minter — runs ENTIRELY in-page inside the youtube.com WebView.
 *
 * Why a separate script from po_token.html: the GVS (googlevideo / SABR streaming) server only
 * accepts a Proof-of-Origin token whose BotGuard attestation was obtained SAME-ORIGIN from the
 * youtube.com page (att/get + GenerateIT fetched by the page itself). Our po_token.html flow fetches
 * the challenge out-of-page via Kotlin OkHttp (/api/jnn/v1/Create); that token is fine for the
 * Innertube /player call but the GVS server keeps the stream at protection status=2 (never 1) and
 * cuts media at ~60s. This script mirrors PipePipe's working sabr_potoken_poc.js: read the page's
 * visitorData + clientVersion, POST /youtubei/v1/att/get for the BotGuard challenge, run the VM,
 * snapshot, POST /api/jnn/v1/GenerateIT for the integrity token, then mint a token bound to whatever
 * the caller asks for — all via the page's own fetch (same-origin). The result goes back through
 * PoTokenBridge.
 *
 * Injected AFTER https://www.youtube.com/ has finished loading (same-origin required). The caller
 * sets window.__SABR_POC_IDENTIFIER (required — the value the token binds to) and, optionally,
 * window.__SABR_POC_PURPOSE ('player' | 'gvs', logging only). This script has NO opinion about
 * which identity belongs to which endpoint; see PoTokenBinding for that decision.
 *
 * ── ATTEST ONCE, MINT MANY ──────────────────────────────────────────────────────────────────────
 * This script is re-injected for every mint, but the BotGuard attestation is NOT re-run every time.
 * State lives on window.__SABR_POT_STATE and survives between injections.
 *
 * Why: LuanRT/BgUtils documents the real web player's model as attest once → keep webPoSignalOutput
 * and the integrity token → call the mint callback repeatedly ("Once minted, the PO Token is then
 * reused for the rest of the session … the player will continue using the cached token as long as
 * its respective integrity token is still valid"). We used to re-run att/get + interpreter eval +
 * snapshot + GenerateIT on EVERY mint: run 17's log shows 11 full attestations in 108 seconds, each
 * one creating a fresh BotGuard VM that was then never shut down — 11 live VMs in one page.
 *
 * That is not just wasteful. BotGuard attests the runtime ENVIRONMENT, and a VM created and
 * snapshotted 30 ms later has observed nothing of the page: no interaction, no event history, no
 * dwell. It is the weakest possible attestation, and it is the best available explanation for why
 * GVS accepts our token on some sessions and answers StreamProtectionStatus=2 on others. It also
 * retro-explains why the elapsed-time and token-age hypotheses each half-fitted and then died —
 * every one of them was a proxy for how settled the VM and page were.
 *
 * GenerateIT hands back [integrityToken, estimatedTtlSecs, mintRefreshThreshold,
 * websafeFallbackToken]. We used to read [0] and discard the rest; mintRefreshThreshold is exactly
 * the "re-attest now" signal we had been guessing at. All four are parsed and honoured below.
 */
(function () {
    'use strict';

    // YouTube ships a Trusted Types CSP that blocks new Function()/eval of the BotGuard interpreter.
    // An identity "default" policy makes Chromium route those sinks through it. (Our WebView also
    // strips CSP headers, but installing the policy is belt-and-suspenders and matches PipePipe.)
    try {
        if (window.trustedTypes && window.trustedTypes.createPolicy
                && !window.trustedTypes.defaultPolicy) {
            window.trustedTypes.createPolicy('default', {
                createHTML: function (value) { return value; },
                createScript: function (value) { return value; },
                createScriptURL: function (value) { return value; }
            });
        }
    } catch (ttError) {
        // ignore; surfaced later as an eval failure
    }

    var API_KEY = 'AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw';
    var REQUEST_KEY = 'O43z0dpjhgX20SCx4KAo';
    // Used only if GenerateIT returns neither mintRefreshThreshold nor estimatedTtlSecs. The
    // observed TTL is 43200s (12h); 10 minutes is deliberately far below that, because guessing
    // short costs one extra attestation and guessing long costs a rejected token.
    var DEFAULT_ATTESTATION_LIFETIME_SECS = 600;
    // Page-scope, survives re-injection of this script. See the header comment.
    var STATE_KEY = '__SABR_POT_STATE';

    function report(result) {
        try {
            PoTokenBridge.onSabrPoTokenResult(JSON.stringify(result));
        } catch (e) {
            try { console.log('[sabr-pot] ' + JSON.stringify(result)); } catch (ignored) { /* */ }
        }
    }

    function step(message) {
        try { console.log('[sabr-pot] ' + message); } catch (e) { /* ignore */ }
    }

    // Prefer the values the Kotlin caller injected (the SABR session's own visitorData/clientVersion
    // — guaranteed clean and bound to the same session the stream uses). Only fall back to scraping
    // the page if they're absent. Scraping was unreliable: it grabbed a 558-char garbage value whose
    // control chars made the X-Goog-Visitor-Id header invalid → fetch() threw "Failed to fetch".
    function readVisitorData() {
        var injected = window.__SABR_VISITOR_DATA;
        if (injected && typeof injected === 'string') {
            return injected;
        }
        var cfg = window.ytcfg;
        var fromCfg = cfg && typeof cfg.get === 'function' ? cfg.get('VISITOR_DATA') : null;
        if (fromCfg) {
            return fromCfg;
        }
        var html = document.documentElement.innerHTML;
        var marker = '"VISITOR_DATA":"';
        var start = html.indexOf(marker);
        if (start < 0) {
            throw new Error('Could not find visitor data');
        }
        var from = start + marker.length;
        var end = html.indexOf('"', from);
        if (end < 0) {
            throw new Error('Could not find visitor data end');
        }
        return html.slice(from, end);
    }

    /**
     * Page identity helpers come from visitor_data.js, which WebPoTokenProvider prepends to this
     * script at injection time. Shared rather than copied so this diagnostic and the Kotlin-side
     * capture can never disagree about what the page's identity is.
     */
    function visitorCandidates() {
        return typeof window.__ytVisitorCandidates === 'function'
            ? window.__ytVisitorCandidates()
            : [];
    }

    /**
     * Are these two visitorData values the SAME identity? Compares canonical forms, because one
     * side is typically still percent-encoded and the other has been decoded — see run 22, where
     * that alone produced a false MATCH=false on two values that were in fact identical.
     */
    function sameVisitor(a, b) {
        if (typeof window.__ytNormaliseVisitorData !== 'function') { return a === b; }
        return window.__ytNormaliseVisitorData(a) === window.__ytNormaliseVisitorData(b);
    }

    function readClientVersion() {
        var injected = window.__SABR_CLIENT_VERSION;
        if (injected && typeof injected === 'string') {
            return injected;
        }
        var cfg = window.ytcfg;
        var fromCfg = cfg && typeof cfg.get === 'function'
            ? cfg.get('INNERTUBE_CLIENT_VERSION') : null;
        return fromCfg || '2.20260114.01.00';
    }

    function normalizeTrustedUrl(value) {
        if (!value) {
            throw new Error('Missing interpreter url');
        }
        return value.indexOf('//') === 0 ? 'https:' + value : value;
    }

    function fetchChallenge(ctx) {
        var context = {
            client: {
                clientName: 'WEB',
                clientVersion: ctx.clientVersion,
                hl: 'en',
                gl: 'US',
                utcOffsetMinutes: 0,
                visitorData: ctx.visitorData
            }
        };
        return fetch('https://www.youtube.com/youtubei/v1/att/get?prettyPrint=false&alt=json', {
            method: 'POST',
            headers: {
                'Accept': '*/*',
                'Content-Type': 'application/json',
                'X-Goog-Visitor-Id': ctx.visitorData,
                'X-Youtube-Client-Version': ctx.clientVersion,
                'X-Youtube-Client-Name': '1'
            },
            body: JSON.stringify({
                engagementType: 'ENGAGEMENT_TYPE_UNBOUND',
                context: context
            })
        }).then(function (response) {
            return response.json().then(function (data) {
                if (!response.ok || !data.bgChallenge) {
                    throw new Error('att/get failed status=' + response.status);
                }
                return data.bgChallenge;
            });
        });
    }

    function resolveInterpreter(challenge, userAgent) {
        var embedded = challenge.interpreterJavascript
            && challenge.interpreterJavascript.privateDoNotAccessOrElseSafeScriptWrappedValue;
        if (embedded) {
            return Promise.resolve(embedded);
        }
        var url = normalizeTrustedUrl(
            (challenge.interpreterJavascript
                && challenge.interpreterJavascript
                    .privateDoNotAccessOrElseTrustedResourceUrlWrappedValue)
            || (challenge.interpreterUrl
                && challenge.interpreterUrl
                    .privateDoNotAccessOrElseTrustedResourceUrlWrappedValue));
        return fetch(url, { headers: { 'User-Agent': userAgent } }).then(function (response) {
            return response.text().then(function (js) {
                if (!response.ok || !js) {
                    throw new Error('interpreter fetch failed status=' + response.status);
                }
                return js;
            });
        });
    }

    function loadBotGuard(interpreterJavascript, program, globalName) {
        return new Promise(function (resolve, reject) {
            try {
                new Function(interpreterJavascript)();
            } catch (e) {
                reject(new Error('interpreter eval failed: ' + e.message));
                return;
            }
            var vm = window[globalName];
            if (!vm || typeof vm.a !== 'function') {
                reject(new Error('BotGuard VM missing init function'));
                return;
            }
            var timeout = setTimeout(function () {
                reject(new Error('BotGuard init timeout'));
            }, 10000);
            try {
                // BotGuard hands back FOUR functions, not one. We used to capture only the first and
                // drop the rest, which meant shutdownFunction was never called and every mint left a
                // live VM behind. Keep them all; shutdown() is called before any re-attestation.
                vm.a(program, function (asyncSnapshotFunction, shutdownFunction,
                                        passEventFunction, checkCameraFunction) {
                    clearTimeout(timeout);
                    resolve({
                        asyncSnapshotFunction: asyncSnapshotFunction,
                        shutdownFunction: shutdownFunction,
                        passEventFunction: passEventFunction,
                        checkCameraFunction: checkCameraFunction
                    });
                }, true, undefined, function () { }, [[], []]);
            } catch (e) {
                clearTimeout(timeout);
                reject(new Error('BotGuard init threw: ' + e.message));
            }
        });
    }

    /** Tear down a previous BotGuard VM before standing up a replacement. Best effort. */
    function shutdownBotGuard(state) {
        if (!state || !state.vmFunctions) {
            return;
        }
        var shutdown = state.vmFunctions.shutdownFunction;
        if (typeof shutdown !== 'function') {
            return;
        }
        try {
            shutdown();
            step('previous BotGuard VM shut down');
        } catch (e) {
            step('shutdownFunction threw (ignored): ' + ((e && e.message) || e));
        }
    }

    function snapshot(functions, webPoSignalOutput) {
        return new Promise(function (resolve, reject) {
            var timeout = setTimeout(function () {
                reject(new Error('BotGuard snapshot timeout'));
            }, 10000);
            functions.asyncSnapshotFunction(function (response) {
                clearTimeout(timeout);
                resolve(response);
            }, [undefined, undefined, webPoSignalOutput, undefined]);
        });
    }

    function fetchIntegrityToken(botGuardResponse, userAgent) {
        return fetch('https://www.youtube.com/api/jnn/v1/GenerateIT', {
            method: 'POST',
            headers: {
                'content-type': 'application/json+protobuf',
                'x-goog-api-key': API_KEY,
                'x-user-agent': 'grpc-web-javascript/0.1',
                'User-Agent': userAgent
            },
            body: JSON.stringify([REQUEST_KEY, botGuardResponse])
        }).then(function (response) {
            return response.json().then(function (data) {
                // [integrityToken, estimatedTtlSecs, mintRefreshThreshold, websafeFallbackToken].
                // We used to read [0] and throw the rest away — including mintRefreshThreshold,
                // which is the server telling us when to come back.
                var integrityToken = data[0];
                var estimatedTtlSecs = typeof data[1] === 'number' ? data[1] : 0;
                var mintRefreshThreshold = typeof data[2] === 'number' ? data[2] : 0;
                var websafeFallbackToken = typeof data[3] === 'string' ? data[3] : null;
                if (typeof integrityToken !== 'string') {
                    // A degraded attestation can come back with only the fallback. Surface that
                    // distinctly rather than as a generic failure — it means BotGuard ran but was
                    // not trusted, which is a different problem from the request failing.
                    throw new Error('GenerateIT returned no integrity token (status='
                        + response.status + ', fallback=' + (websafeFallbackToken ? 'yes' : 'no')
                        + ')');
                }
                return {
                    integrityToken: integrityToken,
                    estimatedTtlSecs: estimatedTtlSecs,
                    mintRefreshThreshold: mintRefreshThreshold,
                    websafeFallbackToken: websafeFallbackToken
                };
            });
        });
    }

    function base64ToU8(value) {
        var normalized = value.replace(/-/g, '+').replace(/_/g, '/');
        var padded = normalized + '==='.slice((normalized.length + 3) % 4);
        var binary = atob(padded);
        var bytes = new Uint8Array(binary.length);
        for (var i = 0; i < binary.length; i++) {
            bytes[i] = binary.charCodeAt(i);
        }
        return bytes;
    }

    function u8ToBase64Url(value) {
        var binary = '';
        for (var i = 0; i < value.length; i++) {
            binary += String.fromCharCode(value[i]);
        }
        return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=/g, '');
    }

    /**
     * Build the reusable mint callback from a completed attestation. Called ONCE per attestation;
     * the returned function is then kept on the state and used for every subsequent mint.
     */
    function createMinter(webPoSignalOutput, integrityToken) {
        var getMinter = webPoSignalOutput[0];
        if (typeof getMinter !== 'function') {
            return Promise.reject(new Error('Missing PO minter factory'));
        }
        return Promise.resolve(getMinter(base64ToU8(integrityToken))).then(function (mintCallback) {
            if (typeof mintCallback !== 'function') {
                throw new Error('Missing PO mint callback');
            }
            return mintCallback;
        });
    }

    /** Mint one token for [identifier] from an already-built callback. Cheap; no network, no VM. */
    function mintWith(mintCallback, identifier) {
        return Promise.resolve(mintCallback(new TextEncoder().encode(identifier)))
            .then(function (result) {
                if (!result) {
                    throw new Error('mint returned nothing');
                }
                if (!(result instanceof Uint8Array)) {
                    throw new Error('mint returned a non-Uint8Array');
                }
                return u8ToBase64Url(result);
            });
    }

    /**
     * Is the stored attestation still good enough to mint from?
     *
     * Prefers the server's own mintRefreshThreshold; falls back to estimatedTtlSecs with a margin,
     * and to a conservative default if the server told us neither. Deliberately does NOT try to be
     * clever: an expired-looking attestation just costs one re-attestation, whereas minting from a
     * stale one costs a rejected token and a dead session.
     */
    function attestationUsable(state) {
        if (!state || typeof state.mintCallback !== 'function') {
            return false;
        }
        var ageSecs = (Date.now() - state.attestedAtMs) / 1000;
        if (state.mintRefreshThreshold > 0) {
            return ageSecs < state.mintRefreshThreshold;
        }
        if (state.estimatedTtlSecs > 0) {
            // 10% safety margin so we re-attest before the server starts refusing.
            return ageSecs < state.estimatedTtlSecs * 0.9;
        }
        return ageSecs < DEFAULT_ATTESTATION_LIFETIME_SECS;
    }

    /**
     * Describe what a token is bound to, by comparing the identifier against the identity we're
     * attesting under. Derived, never assumed — the previous version of this script compared the
     * identifier against a `videoId` variable that the QuickJS caller was setting to the visitorData,
     * so every token in the log claimed to be "bound to videoId" regardless of what it really was.
     * That mislabelled every /player pot in runs 15-18.
     */
    function bindingKind(identifier, ctx) {
        // Reverted with identityChanged above, to restore the run-24 build byte for byte. This one
        // is cosmetic — it only picks the label in the log — but leaving half a change in place is
        // how you end up unable to attribute the next result.
        if (ctx && identifier === ctx.visitorData) return 'visitorData/session';
        if (/^[A-Za-z0-9_-]{11}$/.test(identifier)) return 'videoId/content';
        return 'other(' + identifier.length + 'ch)';
    }

    function run() {
        step('run start, readyState=' + document.readyState + ' origin=' + location.origin);
        // What the token is BOUND to — a videoId (content binding) or a visitorData (session
        // binding). The caller decides; this script does not care and must not assume. Which one
        // each endpoint actually wants is what PoTokenBinding.SWAP is probing.
        var identifier = window.__SABR_POC_IDENTIFIER;
        if (!identifier) {
            throw new Error('Missing __SABR_POC_IDENTIFIER');
        }
        // Which endpoint this token is for ('player' | 'gvs'), for logging only. The mint is
        // identical either way — only the identifier above differs.
        var purpose = window.__SABR_POC_PURPOSE || 'unknown';
        var ctx = {
            visitorData: readVisitorData(),
            clientVersion: readClientVersion(),
            userAgent: navigator.userAgent
        };
        step('context ok visitorLen=' + ctx.visitorData.length + ' clientVersion=' + ctx.clientVersion);
        // IDENTITY CHECK. We attest inside this page, but we override the visitorData with the
        // QuickJS Innertube session's. The page still sends its OWN cookies with att/get, so if the
        // page identity differs from the session identity, the integrity token may be bound to the
        // page while the stream is issued to the session — which would make GVS refuse the pot no
        // matter WHAT we bind the mint to (consistent with the binding probe: both were rejected).
        // NOTE: this check read ONLY ytcfg.get('VISITOR_DATA'), which is empty in this WebView, so it
        // logged "page=(none) MATCH=false" on every mint since it was added and therefore never
        // actually compared anything — the hypothesis above has never been tested. Fall back to the
        // same HTML scrape readVisitorData() uses so we can finally see the page's real identity.
        // Diagnostic only for now: the page value is NOT used for minting.
        //
        // ⚠️ THE 558-CHAR VALUE IS ALMOST CERTAINLY A BAD SCRAPE, NOT THE PAGE'S IDENTITY.
        // A real logged-out visitorData runs ~85-200 chars (run 20's /player responses returned
        // ~85). The old code took the FIRST `"VISITOR_DATA":"` in the raw HTML and read to the next
        // quote, which lands inside whatever blob happens to mention the key first. Acting on that
        // number — either by "fixing" MATCH or by adopting the value — would have been acting on a
        // measurement artifact. So enumerate every source, VALIDATE each one, and report the lot;
        // the next run tells us what the page identity actually is before anything changes.
        try {
            var candidates = visitorCandidates();
            var chosen = null;
            for (var ci = 0; ci < candidates.length; ci++) {
                if (candidates[ci].valid) { chosen = candidates[ci]; break; }
            }
            var summary = [];
            for (var cj = 0; cj < candidates.length; cj++) {
                summary.push(candidates[cj].source + '=' + candidates[cj].value.length + 'ch'
                    + (candidates[cj].valid ? '/ok' : '/bad'));
            }
            step('identity sources: ' + (summary.length ? summary.join(' ') : '(none found)'));
            var pageVisitor = chosen ? chosen.value : '';
            var pageSource = chosen ? chosen.source : '';
            step('identity: session=' + ctx.visitorData.slice(0, 16)
                + ' page=' + (pageVisitor ? pageVisitor.slice(0, 16) : '(none)')
                + ' pageLen=' + pageVisitor.length + ' via=' + (pageSource || 'nothing')
                + ' MATCH=' + (!!pageVisitor && sameVisitor(pageVisitor, ctx.visitorData)));
        } catch (idErr) {
            step('identity check failed: ' + idErr);
        }
        var existing = window[STATE_KEY];
        var forceReattest = !!window.__SABR_POC_FORCE_REATTEST;
        // The identity we attest under must match the identity we mint under. If the caller hands us
        // a different visitorData than the stored attestation was built with, that attestation is
        // not ours to reuse.
        //
        // REVERTED to the raw comparison for run 28 — this is the run-24 build, the last one that
        // played. The canonical version below is almost certainly *more correct* (558 and 556 are
        // one identity in two spellings, so it was re-attesting for no reason), but "more correct"
        // has now lost three runs in a row to "known to work", and the only way to tell whether this
        // line matters is to put it back exactly as it was:
        //
        //     var identityChanged = !!existing && !sameVisitor(existing.visitorData, ctx.visitorData);
        //
        // If run 28 plays, this line is implicated and we bisect from here. If it still opens in
        // grace, the client is exonerated and the environment is the remaining variable.
        var identityChanged = !!existing && existing.visitorData !== ctx.visitorData;

        if (!forceReattest && !identityChanged && attestationUsable(existing)) {
            var reusedAge = Math.round((Date.now() - existing.attestedAtMs) / 1000);
            step('reusing attestation (age=' + reusedAge + 's, mints=' + existing.mintCount
                + '), minting ' + purpose + ' pot bound to ' + bindingKind(identifier, ctx)
                + ' (' + identifier.slice(0, 12) + '…)');
            return mintWith(existing.mintCallback, identifier).then(function (poToken) {
                existing.mintCount++;
                report({
                    ok: true, identifier: identifier, purpose: purpose,
                    binding: bindingKind(identifier, ctx),
                    poToken: poToken, poTokenLength: poToken.length,
                    reusedAttestation: true, attestationAgeSecs: reusedAge
                });
            }, function (mintError) {
                // The stored attestation looked usable but the mint failed — the VM may have been
                // torn down by the page, or the integrity token revoked early. Drop it and fall
                // through to a full attestation rather than failing the caller.
                step('mint from stored attestation failed (' + ((mintError && mintError.message)
                    || mintError) + ') — re-attesting');
                shutdownBotGuard(window[STATE_KEY]);
                window[STATE_KEY] = null;
                window.__SABR_POC_FORCE_REATTEST = true;
                return run();
            });
        }

        step('attesting: ' + (forceReattest ? 'forced'
            : identityChanged ? 'visitorData changed'
            : existing ? 'stored attestation expired' : 'no stored attestation'));
        // One-shot: consumed here so a forced re-attest doesn't stick for every later mint.
        window.__SABR_POC_FORCE_REATTEST = false;
        // Tear the old VM down BEFORE building a new one, so we never accumulate live BotGuard
        // instances in the page (run 17 ended with 11).
        shutdownBotGuard(existing);
        window[STATE_KEY] = null;

        var webPoSignalOutput = [];
        step('fetching att/get challenge...');
        return fetchChallenge(ctx).then(function (challenge) {
            return resolveInterpreter(challenge, ctx.userAgent).then(function (interpreterJs) {
                return loadBotGuard(interpreterJs, challenge.program, challenge.globalName);
            });
        }).then(function (functions) {
            step('botguard loaded, taking snapshot...');
            return snapshot(functions, webPoSignalOutput).then(function (botGuardResponse) {
                return { functions: functions, botGuardResponse: botGuardResponse };
            });
        }).then(function (snap) {
            step('snapshot ok, calling GenerateIT...');
            return fetchIntegrityToken(snap.botGuardResponse, ctx.userAgent)
                .then(function (it) { return { functions: snap.functions, it: it }; });
        }).then(function (got) {
            var it = got.it;
            step('integrity token len=' + it.integrityToken.length
                + ' ttl=' + it.estimatedTtlSecs + 's refreshThreshold=' + it.mintRefreshThreshold
                + 's fallback=' + (it.websafeFallbackToken ? 'yes' : 'no'));
            return createMinter(webPoSignalOutput, it.integrityToken).then(function (mintCallback) {
                window[STATE_KEY] = {
                    vmFunctions: got.functions,
                    webPoSignalOutput: webPoSignalOutput,
                    mintCallback: mintCallback,
                    integrityToken: it.integrityToken,
                    estimatedTtlSecs: it.estimatedTtlSecs,
                    mintRefreshThreshold: it.mintRefreshThreshold,
                    websafeFallbackToken: it.websafeFallbackToken,
                    visitorData: ctx.visitorData,
                    attestedAtMs: Date.now(),
                    mintCount: 0
                };
                step('attestation stored, minting ' + purpose + ' pot bound to '
                    + bindingKind(identifier, ctx)
                    + ' (' + identifier.slice(0, 12) + '…)');
                return mintWith(mintCallback, identifier);
            });
        }).then(function (poToken) {
            var state = window[STATE_KEY];
            if (state) {
                state.mintCount++;
            }
            report({
                ok: true,
                identifier: identifier,
                purpose: purpose,
                binding: bindingKind(identifier, ctx),
                poToken: poToken,
                poTokenLength: poToken.length,
                reusedAttestation: false,
                attestationAgeSecs: 0
            });
        });
    }

    function reportError(e) {
        report({
            ok: false,
            error: (e && e.message) ? e.message : String(e),
            stack: e && e.stack ? String(e.stack).slice(0, 400) : ''
        });
    }

    try {
        run().catch(reportError);
    } catch (e) {
        reportError(e);
    }
})();
