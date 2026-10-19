/*
 * Page visitorData extraction + validation, shared by two callers inside the youtube.com WebView:
 *
 *   1. sabr_potoken.js  — for the identity diagnostic printed on every mint.
 *   2. WebPoTokenProvider.capturePageVisitorData() — to hand the page's real identity to the
 *      QuickJS Innertube session (see SabrSessionIdentity).
 *
 * It lives in its own asset ON PURPOSE. Both callers need the same answer, and a copy-paste of the
 * validator into the Kotlin-side probe would be a transcription that could silently drift from the
 * one the diagnostic uses — which is exactly the class of bug that made the 558-char reading
 * untrustworthy in the first place.
 *
 * Installs two globals: __ytLooksLikeVisitorData(value) and __ytVisitorCandidates().
 */
(function () {
    'use strict';

    /**
     * Does [value] actually look like a visitorData, rather than whatever string happened to sit
     * next to the key we grepped for?
     *
     * A visitorData is a base64 (often percent-encoded, often URL-safe alphabet) protobuf whose
     * field 1 is the 11-character visitor id — so the first two bytes are always 0x0A 0x0B. Cheap
     * and specific.
     *
     * NOTE (run 21): the page's genuine value is ~558 chars and DOES pass this. Length is not a
     * signal — a real visitorData carries an experiments/config blob that the ~85-char values in
     * /player responses do not. An earlier guess that "558 chars means bad scrape" was wrong.
     */
    function looksLikeVisitorData(value) {
        if (!value || typeof value !== 'string') {
            return false;
        }
        var s = value;
        if (s.indexOf('%') >= 0) {
            try { s = decodeURIComponent(s); } catch (e) { /* leave as-is */ }
        }
        s = s.replace(/\\u003d/g, '=').replace(/-/g, '+').replace(/_/g, '/');
        while (s.length % 4 !== 0) { s += '='; }
        var bytes;
        try { bytes = atob(s); } catch (e) { return false; }
        return bytes.length > 12 && bytes.charCodeAt(0) === 0x0A && bytes.charCodeAt(1) === 11;
    }

    /**
     * Every place this page might be carrying its visitorData, with a verdict on each. Enumerating
     * rather than first-match-wins because the first match is not reliably the right one.
     *
     * Run 21 observed only `html#0` producing anything at all — ytcfg.get, ytcfg.data_ and
     * yt.config_ were all empty in this WebView — so the HTML scrape is not a fallback here, it is
     * the only source. The others stay in case a future WebView populates them.
     */
    function visitorCandidates() {
        var out = [];
        function add(source, value) {
            if (value && typeof value === 'string') {
                out.push({ source: source, value: value, valid: looksLikeVisitorData(value) });
            }
        }
        var cfg = window.ytcfg;
        if (cfg) {
            if (typeof cfg.get === 'function') {
                try { add('ytcfg.get', cfg.get('VISITOR_DATA')); } catch (e) { /* ignore */ }
            }
            if (cfg.data_) { add('ytcfg.data_', cfg.data_.VISITOR_DATA); }
        }
        if (window.yt && window.yt.config_) { add('yt.config_', window.yt.config_.VISITOR_DATA); }
        var html = document.documentElement.innerHTML;
        var marker = '"VISITOR_DATA":"';
        var idx = html.indexOf(marker);
        var n = 0;
        while (idx >= 0 && n < 4) {
            var from = idx + marker.length;
            var end = html.indexOf('"', from);
            if (end > from) { add('html#' + n, html.slice(from, end)); }
            idx = html.indexOf(marker, from);
            n++;
        }
        return out;
    }

    /**
     * Canonical form for COMPARING two visitorData values.
     *
     * Run 22: the page value and the session value were the same identity, but the diagnostic said
     * MATCH=false — because the session's had been percent-decoded on the way in (558 -> 556 chars,
     * the "==" padding) while the page's was still raw. A raw string comparison therefore reported a
     * mismatch that did not exist. Normalise both sides before comparing.
     */
    function normaliseForCompare(value) {
        if (!value || typeof value !== 'string') { return ''; }
        var s = value;
        if (s.indexOf('%') >= 0) {
            try { s = decodeURIComponent(s); } catch (e) { /* leave as-is */ }
        }
        return s;
    }

    /** First VALID candidate, or '' if the page has none. */
    function bestVisitorData() {
        var all = visitorCandidates();
        for (var i = 0; i < all.length; i++) {
            if (all[i].valid) { return all[i].value; }
        }
        return '';
    }

    window.__ytLooksLikeVisitorData = looksLikeVisitorData;
    window.__ytNormaliseVisitorData = normaliseForCompare;
    window.__ytVisitorCandidates = visitorCandidates;
    window.__ytBestVisitorData = bestVisitorData;
})();
