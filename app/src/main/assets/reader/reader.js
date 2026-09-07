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

  var themes = {
    light: { body: { background: '#ffffff', color: '#1b1b1f' }, a: { color: '#5b4bcf' } },
    sepia: { body: { background: '#f4ecd8', color: '#3b2f22' }, a: { color: '#7a5230' } },
    dark: { body: { background: '#121212', color: '#d6d6d6' }, a: { color: '#b0a4ff' } }
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

  window.reader = {
    open: function (url, cfi, theme, fontPct, font, linePct) {
      book = ePub(url);
      rendition = book.renderTo('viewer', {
        width: '100%', height: '100%', flow: 'paginated', spread: 'none', allowScriptedContent: false
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
      first.catch(fail);
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
      document.body.style.background = themes[name].body.background;
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
