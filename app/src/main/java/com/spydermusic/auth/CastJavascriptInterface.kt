package com.spydermusic.auth

import android.webkit.JavascriptInterface
import android.util.Log

/**
 * CastJavascriptInterface — JavaScript bridge injected into the YouTube Music WebView.
 *
 * Injected as window.SpyderCast via WebView.addJavascriptInterface().
 * The companion app's page-load JS calls window.SpyderCast.castToKodi(videoId, title, artist)
 * when the user taps the Cast button overlaid on a track row or Now Playing card.
 *
 * On receipt, calls [onCastRequested] which is wired to [CastReceiver] in MainActivity.
 *
 * Why a JavascriptInterface and not shouldOverrideUrlLoading?
 *   URL interception requires a navigation event; a Cast tap doesn't navigate.
 *   The JS bridge fires directly from a click handler injected by [CAST_JS] below.
 */
class CastJavascriptInterface(
    private val onCastRequested: (videoId: String, title: String, artist: String) -> Unit
) {
    companion object {
        const val INTERFACE_NAME = "SpyderCast"

        /**
         * JavaScript injected on every music.youtube.com page load.
         *
         * Strategy:
         *   1. Wait for the page to settle (1 s rAF loop).
         *   2. Find all track rows (ytmusic-responsive-list-item-renderer) and
         *      the Now Playing bar (ytmusic-player-bar).
         *   3. Inject a Cast button SVG into each, styled to match YTMusic's
         *      existing icon row.
         *   4. On click, extract videoId from the item's data-videoid attribute
         *      (set by YTMusic) or parse it from the surrounding href.
         *   5. Call window.SpyderCast.castToKodi(videoId, title, artist).
         *
         * The script is re-injected on onPageFinished so it works across
         * in-app navigation (YTMusic is a SPA — the page never fully reloads).
         */
        val CAST_JS = """
(function() {
  const CAST_BTN_CLASS = '_spyderCastBtn';
  const PLAYER_BTN_ID  = '_spyderPlayerCastBtn';

  function makeSvg(size) {
    return '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"' +
      ' width="' + size + '" height="' + size + '"' +
      ' fill="none" stroke="#FF4444" stroke-width="2.2"' +
      ' style="display:block;cursor:pointer;opacity:0.95">' +
      '<path d="M2 16.1A5 5 0 0 1 5.9 20M2 12.05A9 9 0 0 1 9.95 20' +
      'M2 8V6a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-6"/>' +
      '<line x1="2" y1="20" x2="2.01" y2="20"/></svg>';
  }

  // ── URL helpers ──────────────────────────────────────────────────────

  function onPlayerPage() {
    return location.href.indexOf('watch?v=') !== -1 ||
           location.href.indexOf('/watch?') !== -1;
  }

  function videoIdFromUrl() {
    var m = location.href.match(/[?&]v=([^&]+)/);
    return m ? m[1] : null;
  }

  // ── Meta extraction ──────────────────────────────────────────────────

  function metaFromRow(row) {
    var r = row.closest('ytmusic-responsive-list-item-renderer') ||
            row.closest('[data-context-item-id]') || row;
    var t = r.querySelector('.title, yt-formatted-string.title');
    var a = r.querySelector('.secondary-flex-columns a, .subtitle a, yt-formatted-string.subtitle a');
    return {
      title:  t ? t.textContent.trim() : '',
      artist: a ? a.textContent.trim() : ''
    };
  }

  function metaFromPlayer() {
    // Mobile YTMusic player uses these elements
    var selectors = [
      // Mobile player expanded view
      ['ytmusic-player .title',                        '.subtitle a, .byline a'],
      // Fallback: mini-player bar at bottom
      ['ytmusic-player-bar .title',                    'ytmusic-player-bar .subtitle a'],
      // Generic fallbacks
      ['[class*="player"] [class*="title"]',           '[class*="player"] [class*="artist"]'],
    ];
    for (var i = 0; i < selectors.length; i++) {
      var t = document.querySelector(selectors[i][0]);
      var a = document.querySelector(selectors[i][1]);
      if (t) return {
        title:  t.textContent.trim(),
        artist: a ? a.textContent.trim() : ''
      };
    }
    // Last resort: page title
    return {
      title:  document.title.replace(' - YouTube Music', '').trim(),
      artist: ''
    };
  }

  function doCast(videoId, title, artist) {
    if (!videoId) { console.warn('[SpyderCast] No videoId'); return; }
    if (window.SpyderCast) window.SpyderCast.castToKodi(videoId, title, artist);
  }

  // ── Row Cast buttons (track lists) ──────────────────────────────────

  function videoIdFromRow(row) {
    var el = row.closest('[data-videoid]');
    if (el) return el.dataset.videoid;
    var r = row.closest('ytmusic-responsive-list-item-renderer,[data-context-item-id]');
    if (r) {
      var id = r.getAttribute('data-videoid') || r.getAttribute('data-context-item-id');
      if (id) return id;
    }
    var a = row.querySelector('a[href*="watch?v="]');
    if (a) { var m = a.href.match(/[?&]v=([^&]+)/); if (m) return m[1]; }
    return null;
  }

  function attachRowButton(row) {
    if (row.querySelector('.' + CAST_BTN_CLASS)) return;
    var menuBtn = row.querySelector(
      'ytmusic-menu-renderer, ' +
      'tp-yt-paper-icon-button[icon="more_vert"], ' +
      'button[aria-label="More actions"]'
    );
    if (!menuBtn) return;
    var span = document.createElement('span');
    span.className = CAST_BTN_CLASS;
    span.innerHTML = makeSvg(18);
    span.style.cssText = 'display:inline-flex;align-items:center;margin-right:4px;vertical-align:middle;';
    span.addEventListener('click', function(e) {
      e.stopPropagation(); e.preventDefault();
      var m = metaFromRow(span);
      doCast(videoIdFromRow(span), m.title, m.artist);
    });
    menuBtn.parentNode.insertBefore(span, menuBtn);
  }

  // ── Player page FAB ──────────────────────────────────────────────────
  // Always fixed-position — visible whenever the URL contains watch?v=
  // so it works regardless of which DOM element wraps the player.

  function ensurePlayerFab() {
    var existing = document.getElementById(PLAYER_BTN_ID);
    if (onPlayerPage()) {
      if (!existing) {
        var btn = document.createElement('div');
        btn.id = PLAYER_BTN_ID;
        btn.innerHTML = makeSvg(30);
        btn.title = 'Cast to Kodi';
        btn.style.cssText =
          'position:fixed;bottom:84px;right:14px;z-index:99999;' +
          'background:rgba(20,20,20,0.85);border-radius:50%;' +
          'width:52px;height:52px;' +
          'display:flex;align-items:center;justify-content:center;' +
          'box-shadow:0 3px 10px rgba(0,0,0,0.6);' +
          'border:1.5px solid rgba(255,68,68,0.4);';
        btn.addEventListener('click', function(e) {
          e.stopPropagation();
          var vid = videoIdFromUrl();
          var m = metaFromPlayer();
          doCast(vid, m.title, m.artist);
        });
        document.body.appendChild(btn);
      }
    } else {
      if (existing) existing.remove();
    }
  }

  // ── Main injection ───────────────────────────────────────────────────

  function injectAll() {
    document.querySelectorAll(
      'ytmusic-responsive-list-item-renderer, [data-context-item-id]'
    ).forEach(attachRowButton);
    document.querySelectorAll('ytmusic-player-bar').forEach(attachRowButton);
    ensurePlayerFab();
  }

  setTimeout(injectAll, 1200);

  if (!window._spyderCastObserver) {
    window._spyderCastObserver = new MutationObserver(function() {
      clearTimeout(window._spyderCastTimer);
      window._spyderCastTimer = setTimeout(injectAll, 500);
    });
    window._spyderCastObserver.observe(document.body, { childList: true, subtree: true });
  }
})();
""".trimIndent()
    }

    @JavascriptInterface
    fun castToKodi(videoId: String, title: String, artist: String) {
        Log.i("SpyderCast", "castToKodi: videoId=$videoId title=$title artist=$artist")
        if (videoId.isBlank()) {
            Log.w("SpyderCast", "castToKodi called with blank videoId — ignoring")
            return
        }
        onCastRequested(videoId, title, artist)
    }
}
