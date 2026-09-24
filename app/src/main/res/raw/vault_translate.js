/*
 * In-page translation for the private browser.
 *
 * This is how Chrome and Brave translate a page: the address, cookies and login session stay the
 * site's own, and only the text on screen is swapped. The Android side owns every network call
 * (PageTranslator.kt), so nothing here reaches out on its own - this file collects text, asks for
 * a translation and writes the answer back where it came from.
 *
 * Injected with evaluateJavascript(), so it runs in the page's own JS context. It is injected
 * again on every start; the guard below keeps the first copy and its memory of the page.
 */
(function () {
    if (window.__vaultTr) { return; }

    /* Text inside these is either not text a reader needs or not ours to rewrite. */
    var SKIP_TAGS = {
        SCRIPT: 1, STYLE: 1, NOSCRIPT: 1, TEXTAREA: 1, CODE: 1, PRE: 1,
        SVG: 1, CANVAS: 1, TITLE: 1, IFRAME: 1
    };
    /* Anything with a letter in it: Latin, Greek, Cyrillic, Hebrew, Arabic, Indic, Thai, CJK. */
    var HAS_LETTER = /[A-Za-z\u00C0-\u02FF\u0370-\u1FFF\u2E80-\uD7FF\uF900-\uFAFF]/;
    var MAX_ITEMS = 30;      /* strings per request */
    var MAX_CHARS = 1500;    /* characters per request, so one body stays small */
    var MAX_FAILS = 3;       /* give up instead of grinding through a dead connection */
    var EMPTY_RETRIES = 3;   /* pages that paint their text late get a few more looks */
    var RETRY_MS = 1200;

    var target = null;             /* language in use; null means the page shows its own text */
    var records = new WeakMap();   /* text node -> {original, translated} */
    var owned = [];                /* every node we rewrote, so restore can walk back */
    var queued = new WeakSet();    /* nodes already waiting for a translation */
    var waiting = [];              /* {node, text} not sent yet */
    var inflight = {};             /* request id -> the items that request carried */
    var pendingId = 0;             /* request on its way; 0 when idle */
    var nextId = 1;
    var fails = 0;
    var count = 0;                 /* strings actually replaced in this run */
    var retries = 0;
    var reported = false;
    var observer = null;
    var retryTimer = 0;

    function bridge() { return window.__vaultTrBridge; }

    /* Text travels as HTML, so a "<" on the page cannot be read as a tag. The service hands the
       entities back decoded, which is exactly what textContent wants. */
    function escapeHtml(text) {
        return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    }

    /* The service trims what it was sent, so the spaces around a fragment are put back by hand.
       Without them, two fragments sharing a line ("Baca" and "selengkapnya") would run together,
       and an empty answer would silently delete the words instead of leaving them alone. */
    function keepSpacing(original, translated) {
        var body = translated.replace(/^\s+|\s+$/g, '');
        if (!body) { return ''; }
        return original.match(/^\s*/)[0] + body + original.match(/\s*$/)[0];
    }

    function isCandidate(node) {
        var text = node.nodeValue;
        if (!text || !HAS_LETTER.test(text)) { return false; }
        var parent = node.parentNode;
        if (!parent || parent.nodeType !== 1 || SKIP_TAGS[parent.tagName]) { return false; }
        if (parent.isContentEditable) { return false; }
        if (parent.closest && parent.closest('.notranslate,[translate="no"],[contenteditable="true"]')) {
            return false;
        }
        /* Already carrying our own translation? Leave it be; if the page has rewritten it since,
           the new text is what gets translated next. */
        var seen = records.get(node);
        return !(seen && node.nodeValue === seen.translated);
    }

    function offer(node) {
        if (queued.has(node)) { return; }
        queued.add(node);
        waiting.push({ node: node, text: node.nodeValue });
    }

    function collect(root) {
        if (!root) { return; }
        var doc = root.ownerDocument || document;
        var walker = doc.createTreeWalker(root, NodeFilter.SHOW_TEXT, null, false);
        var node = walker.nextNode();
        while (node) {
            if (isCandidate(node)) { offer(node); }
            node = walker.nextNode();
        }
    }

    function collectFrames() {
        var frames = document.querySelectorAll('iframe,frame');
        for (var i = 0; i < frames.length; i++) {
            try {
                var doc = frames[i].contentDocument;
                if (doc && doc.body) { collect(doc.body); }
            } catch (e) { /* another origin: not ours to touch */ }
        }
    }

    function scanNow() {
        collect(document.body || document.documentElement);
        collectFrames();
        pump();
    }

    function pump() {
        if (target === null || pendingId) { return; }
        if (!waiting.length) { settle(); return; }
        var items = [];
        var chars = 0;
        while (waiting.length && items.length < MAX_ITEMS) {
            var next = waiting[0];
            if (items.length && chars + next.text.length > MAX_CHARS) { break; }
            chars += next.text.length;
            items.push(waiting.shift());
        }
        var texts = [];
        for (var i = 0; i < items.length; i++) { texts.push(escapeHtml(items[i].text)); }
        var id = nextId++;
        inflight[id] = items;
        pendingId = id;
        try {
            bridge().request(JSON.stringify({ id: id, tl: target, q: texts }));
        } catch (e) {
            delete inflight[id];
            pendingId = 0;
            fail(items);
        }
    }

    /* A batch that came back empty-handed is put back where it was taken from, in order, and
       tried again after a pause. Giving up is reported: a page that is never told the translation
       failed would leave the reader watching a bar that never finishes. */
    function fail(items) {
        fails++;
        if (items) {
            for (var i = items.length - 1; i >= 0; i--) {
                if (items[i].node && items[i].node.parentNode) { waiting.unshift(items[i]); }
            }
        }
        if (fails >= MAX_FAILS) {
            waiting = [];
            queued = new WeakSet();
            stopWatch();
            report('failed');
            return;
        }
        if (!pendingId && waiting.length) {
            clearTimeout(retryTimer);
            retryTimer = setTimeout(function () {
                retryTimer = 0;
                pump();
            }, RETRY_MS);
        }
    }

    /* Everything that was waiting has been dealt with. Says so once per run, and gives a page
       that has not painted yet a few more chances before calling itself empty. */
    function settle() {
        if (target === null || reported) { return; }
        if (pendingId || waiting.length) { return; }
        if (count > 0) { report('done'); return; }
        if (retries < EMPTY_RETRIES) {
            retries++;
            clearTimeout(retryTimer);
            retryTimer = setTimeout(function () {
                retryTimer = 0;
                if (target === null || reported) { return; }
                scanNow();
            }, RETRY_MS);
            return;
        }
        report('empty');
    }

    function report(state) {
        if (reported) { return; }
        reported = true;
        clearTimeout(retryTimer);
        retryTimer = 0;
        try { bridge().report(state, count); } catch (e) { /* the app is gone; nothing to say */ }
    }

    function watch() {
        if (observer || !window.MutationObserver) { return; }
        observer = new MutationObserver(function (changes) {
            if (target === null) { return; }
            var touched = false;
            for (var i = 0; i < changes.length; i++) {
                var change = changes[i];
                if (change.type === 'characterData') {
                    /* Our own writes also land here and are filtered out by isCandidate(). */
                    if (isCandidate(change.target)) { offer(change.target); touched = true; }
                    continue;
                }
                var added = change.addedNodes;
                for (var j = 0; j < added.length; j++) {
                    var node = added[j];
                    if (node.nodeType === 3) {
                        if (isCandidate(node)) { offer(node); touched = true; }
                    } else if (node.nodeType === 1) {
                        collect(node);
                        touched = true;
                    }
                }
            }
            if (touched) { pump(); }
        });
        observer.observe(document.documentElement || document, {
            childList: true, subtree: true, characterData: true
        });
    }

    function stopWatch() {
        if (observer) {
            observer.disconnect();
            observer = null;
        }
        clearTimeout(retryTimer);
        retryTimer = 0;
    }

    window.__vaultTr = {
        /* Starts (or restarts) a run for [language]. */
        start: function (language) {
            if (target !== null && target !== language) { window.__vaultTr.restore(); }
            target = language;
            count = 0;
            fails = 0;
            retries = 0;
            reported = false;
            waiting = [];
            queued = new WeakSet();
            watch();
            scanNow();
            return true;
        },

        /* Called by the app with the answer for request [id], or null when it could not be had. */
        apply: function (id, translations) {
            var items = inflight[id];
            delete inflight[id];
            if (pendingId === id) { pendingId = 0; }
            if (target === null || !items) { return; }
            if (!translations || translations.length !== items.length) { fail(items); return; }
            fails = 0;
            for (var i = 0; i < items.length; i++) {
                var item = items[i];
                var node = item.node;
                var text = translations[i];
                queued.delete(node);
                if (!node || !node.parentNode || typeof text !== 'string' || !text) { continue; }
                /* The page rewrote this text while the answer was on its way: that text wins. */
                if (node.nodeValue !== item.text) { continue; }
                var written = keepSpacing(item.text, text);
                if (!written.replace(/\s/g, '')) { continue; }
                node.nodeValue = written;
                records.set(node, { original: item.text, translated: written });
                owned.push(node);
                count++;
            }
            pump();
        },

        /* Puts every word back the way the site wrote it. */
        restore: function () {
            target = null;
            stopWatch();
            waiting = [];
            queued = new WeakSet();
            inflight = {};
            pendingId = 0;
            for (var i = 0; i < owned.length; i++) {
                var node = owned[i];
                var seen = records.get(node);
                if (seen && node.parentNode && node.nodeValue === seen.translated) {
                    node.nodeValue = seen.original;
                }
                records.delete(node);
            }
            owned = [];
            count = 0;
            fails = 0;
            retries = 0;
            reported = true;
            return true;
        }
    };
})();
