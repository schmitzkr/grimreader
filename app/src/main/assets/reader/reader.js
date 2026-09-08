/*
 * Glue between the app and epub.js. The app calls window.reader.*; the page
 * reports back through window.Android (a JavascriptInterface).
 *
 * Progress is a CFI plus a 0–100 percentage. epub.js only knows a real
 * percentage once book.locations.generate() has finished, which a short
 * session never reaches, so until then the percentage is the spine item's
 * position plus the page within it: coarse, but monotonic and never zero
 * past the first page, which is what the server's READING threshold needs.
 */
(function () {
  var book = null, rendition = null, locationsReady = false, spineCount = 1, toc = [], lastLoc = null;

  /*
   * Each theme's CSS is registered under a selector scoped to that theme's
   * own class (rendition.themes.select() toggles this class on <body>, and
   * already did before this change) rather than the bare 'body'/'a' tags.
   * epub.js's Themes manager creates one <style> node per theme the first
   * time it is selected and never removes or reorders it -- with unscoped
   * selectors every theme's rules stay "in effect" for the whole document
   * simultaneously, and which one visually wins is decided by <style> tag
   * order in <head>, not by which theme is actually selected. Cycling
   * through every theme once left the page frozen on whichever was
   * selected last, no matter what was tapped afterward (confirmed on
   * device, no JS error either side: nothing here throws, it's a silent
   * cascade-order bug, not a runtime one). Scoping each rule to its own
   * theme class makes a stale, out-of-order <style> node harmless: its
   * selectors simply stop matching once class is removed from <body>.
   */
  var themes = {
    light: { 'body.light': { background: '#ffffff', color: '#1b1b1f' }, '.light a': { color: '#5b4bcf' } },
    sepia: { 'body.sepia': { background: '#f4ecd8', color: '#3b2f22' }, '.sepia a': { color: '#7a5230' } },
    dark: { 'body.dark': { background: '#121212', color: '#d6d6d6' }, '.dark a': { color: '#b0a4ff' } },
    black: { 'body.black': { background: '#000000', color: '#cfcfcf' }, '.black a': { color: '#a89bff' } },
    forest: { 'body.forest': { background: '#1b2a1e', color: '#dbe8db' }, '.forest a': { color: '#8fd19e' } }
  };

  function report(name) {
    try {
      var a = window.Android;
      if (a && typeof a[name] === 'function') a[name].apply(a, Array.prototype.slice.call(arguments, 1));
    } catch (e) { /* the bridge is gone; nothing to do */ }
  }

  function flatten(items, depth, out) {
    (items || []).forEach(function (i) {
      out.push({ label: String(i.label || '').trim(), href: String(i.href || ''), depth: depth });
      flatten(i.subitems, depth + 1, out);
    });
    return out;
  }

  function percentageFor(loc) {
    var cfi = loc.start.cfi;
    if (locationsReady) {
      var p = book.locations.percentageFromCfi(cfi);
      if (typeof p === 'number' && !isNaN(p)) return Math.round(p * 1000) / 10;
    }
    var idx = loc.start.index || 0;
    var d = loc.start.displayed;
    var within = (d && d.total) ? Math.max(0, d.page - 1) / d.total : 0;
    return Math.round(((idx + within) / spineCount) * 1000) / 10;
  }

  function chapterFor(loc) {
    var href = loc.start.href || '';
    var base = href.split('#')[0];
    var found = '';
    toc.forEach(function (t) {
      var th = t.href.split('#')[0];
      if (th && (th === base || base.slice(-th.length) === th || th.slice(-base.length) === base)) found = found || t.label;
    });
    return found;
  }

  function relocated(loc) {
    lastLoc = loc;
    report('onRelocated', loc.start.cfi, percentageFor(loc), chapterFor(loc), !!loc.atEnd);
  }

  function attachGestures(contents) {
    var doc = contents.document;
    var startX = null, startY = null, startT = 0;
    doc.addEventListener('touchstart', function (e) {
      var t = e.changedTouches[0];
      startX = t.clientX; startY = t.clientY; startT = Date.now();
    }, { passive: true });
    doc.addEventListener('touchend', function (e) {
      if (startX == null) return;
      var t = e.changedTouches[0];
      var dx = t.clientX - startX, dy = t.clientY - startY;
      var quick = Date.now() - startT < 500;
      if (Math.abs(dx) > 60 && Math.abs(dx) > Math.abs(dy) * 1.5) {
        if (dx < 0) rendition.next(); else rendition.prev();
      } else if (quick && Math.abs(dx) < 12 && Math.abs(dy) < 12) {
        var w = contents.window.innerWidth || doc.documentElement.clientWidth || 1;
        var x = t.clientX / w;
        if (x < 0.3) rendition.prev();
        else if (x > 0.7) rendition.next();
        else report('onTap');
      }
      startX = null;
    }, { passive: true });
  }

  function fail(e) { report('onError', String((e && e.message) || e)); }

  /*
   * Diagnostic only, logged via console.log -> onConsoleMessage -> Logcat:
   * relocated() firing proves epub.js believes navigation succeeded, but
   * that says nothing about whether its rendered iframe actually landed in
   * the DOM at a nonzero size with real content inside it. This answers
   * that directly instead of guessing from the outside.
   */
  function logViewerState(label) {
    try {
      var viewer = document.getElementById('viewer');
      var rect = viewer.getBoundingClientRect();
      var info = label + ': viewer children=' + viewer.children.length + ' rect=' + rect.width + 'x' + rect.height;
      var iframe = viewer.querySelector('iframe');
      if (!iframe) {
        info += ' no-iframe-found';
      } else {
        var irect = iframe.getBoundingClientRect();
        info += ' iframe rect=' + irect.width + 'x' + irect.height + ' attrs(w,h)=' + iframe.width + ',' + iframe.height;
        try {
          var idoc = iframe.contentDocument;
          info += idoc
            ? (' bodyChildren=' + (idoc.body ? idoc.body.children.length : 'no-body') + ' bodyTextLen=' + (idoc.body ? idoc.body.textContent.length : 0))
            : ' contentDocument=null';
        } catch (e) {
          info += ' contentDocument access threw: ' + ((e && e.message) || e);
        }
      }
      console.log(info);
    } catch (e) {
      console.log('logViewerState threw: ' + ((e && e.message) || e));
    }
  }

  /* Two columns once the page is tablet-wide (a landscape tablet, a desktop window); one on phones. */
  function spreadFor(widthPx) { return widthPx >= 840 ? 'auto' : 'none'; }

  window.reader = {
    /*
     * width/height (CSS px) are passed in explicitly by Kotlin, measured from
     * Compose's own layout rather than read from window.innerWidth/innerHeight
     * here: on-device testing found #viewer/the rendered iframe getting a
     * real width but an exact zero height on first layout in this WebView,
     * and confirmed the DOM 'resize' event never fires in it even when the
     * hosting Android View's on-screen size genuinely changes (rotation) --
     * so neither percentage/vh-based CSS sizing nor a JS resize listener can
     * be trusted here. '100%' is kept as a fallback only for a caller that
     * doesn't have a measured size yet.
     */
    open: function (url, cfi, theme, fontPct, font, linePct, width, height) {
      /* Everything up to the two promise chains below runs synchronously; an
       * exception anywhere in here used to abort open() with nothing ever
       * reported to Android (onReady/onError are both wired up inside this
       * same call), leaving a permanently blank #viewer and no diagnostic. */
      try {
        book = ePub(url);
        rendition = book.renderTo('viewer', {
          width: width || '100%', height: height || '100%', flow: 'paginated',
          spread: spreadFor(width || window.innerWidth), allowScriptedContent: false
        });
        /* Defensive fallback only -- confirmed dead in the WebView this was
         * tested in (no log line ever appears here even on a real device
         * rotation that genuinely changed the on-screen size). resize() is
         * called explicitly from Kotlin instead; see window.reader.resize(). */
        window.addEventListener('resize', function () {
          if (!rendition) return;
          rendition.resize();
          rendition.spread(spreadFor(window.innerWidth));
          logViewerState('after-resize-event');
        });
        Object.keys(themes).forEach(function (k) { rendition.themes.register(k, themes[k]); });
        this.setTheme(theme || 'light');
        this.setFontSize(fontPct || 100);
        this.setFont(font || 'book');
        this.setLineHeight(linePct || 100);
        rendition.on('relocated', relocated);
        rendition.hooks.content.register(attachGestures);
        book.ready.then(function () {
          spineCount = (book.spine && book.spine.spineItems && book.spine.spineItems.length) || 1;
          toc = flatten(book.navigation && book.navigation.toc, 0, []);
          report('onReady', JSON.stringify(toc));
          return book.locations.generate(1024);
        }).then(function () {
          locationsReady = true;
          if (lastLoc) relocated(lastLoc);
        }).catch(fail);
        var first = cfi ? rendition.display(cfi).catch(function () { return rendition.display(); }) : rendition.display();
        first.then(function () {
          logViewerState('after-display');
        }).catch(fail);
      } catch (e) {
        fail(e);
      }
    },
    /* Called from Kotlin whenever Compose reports a new measured size for
     * the WebView (e.g. a rotation) -- the primary resize mechanism now,
     * since the DOM 'resize' event doesn't fire in this host. width/height
     * are CSS px, same units open() takes. */
    resize: function (width, height) {
      if (!rendition) return;
      rendition.resize(width, height);
      rendition.spread(spreadFor(width));
      logViewerState('after-explicit-resize');
    },
    next: function () { if (rendition) rendition.next(); },
    prev: function () { if (rendition) rendition.prev(); },
    display: function (target) { if (rendition) rendition.display(target).catch(fail); },
    goToPercentage: function (p) {
      if (!book || !rendition) return;
      var f = Math.max(0, Math.min(1, p / 100));
      if (locationsReady) {
        var cfi = book.locations.cfiFromPercentage(f);
        if (cfi) { rendition.display(cfi).catch(fail); return; }
      }
      var idx = Math.min(spineCount - 1, Math.floor(f * spineCount));
      var item = book.spine.get(idx);
      if (item) rendition.display(item.href).catch(fail);
    },
    setTheme: function (name) {
      if (!themes[name]) name = 'light';
      if (rendition) rendition.themes.select(name);
      // The outer WebView document's own background, fully covered by
      // #viewer per index.html -- functionally inert, kept only so it
      // never shows through during a resize/layout flash.
      document.body.style.background = themes[name]['body.' + name].background;
    },
    setFontSize: function (pct) { if (rendition) rendition.themes.fontSize(pct + '%'); },
    /* 'book' keeps the publisher's fonts; the others override every element. */
    setFont: function (name) {
      if (!rendition) return;
      var families = {
        book: 'inherit',
        serif: 'Georgia, "Times New Roman", "Noto Serif", serif',
        sans: 'system-ui, Roboto, "Noto Sans", sans-serif'
      };
      rendition.themes.override('font-family', families[name] || 'inherit', name !== 'book');
    },
    /* 100 keeps the book's own spacing; otherwise a multiplier on the font size. */
    setLineHeight: function (pct) {
      if (!rendition) return;
      rendition.themes.override('line-height', pct === 100 ? 'inherit' : (pct / 100).toFixed(2), pct !== 100);
    }
  };
})();
