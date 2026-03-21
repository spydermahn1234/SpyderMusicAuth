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

  const makeSvg = function(size) {
    return '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" ' +
      'width="' + size + '" height="' + size + '" fill="none" stroke="#FF4444" stroke-width="2.2" ' +
      'style="cursor:pointer;flex-shrink:0;opacity:0.9;display:block" title="Cast to Kodi">' +
      '<path d="M2 16.1A5 5 0 0 1 5.9 20M2 12.05A9 9 0 0 1 9.95 20M2 8V6a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-6"/>' +
      '<line x1="2" y1="20" x2="2.01" y2="20"/></svg>';
  };

  function videoIdFromRow(row) {
    var el = row.closest('[data-videoid]');
    if (el) return el.dataset.videoid;
    var a = row.querySelector('a[href*="watch?v="]');
    if (a) { var m = a.href.match(/[?&]v=([^&]+)/); if (m) return m[1]; }
    var r = row.closest('ytmusic-responsive-list-item-renderer');
    if (r) { var id = r.getAttribute('data-videoid') || r.getAttribute('videoid'); if (id) return id; }
    return null;
  }

  function videoIdFromUrl() {
    var m = location.href.match(/[?&]v=([^&]+)/);
    return m ? m[1] : null;
  }

  function metaFromRow(row) {
    var r = row.closest('ytmusic-responsive-list-item-renderer') || row;
    var t = r.querySelector('.title, yt-formatted-string.title, [class*="title"]');
    var a = r.querySelector('.secondary-flex-columns a, yt-formatted-string[class*="subtitle"] a');
    return { title: t ? t.textContent.trim() : '', artist: a ? a.textContent.trim() : '' };
  }

  function metaFromPlayer() {
    var titleEl  = document.querySelector('ytmusic-player-page .title, ytmusic-player-page yt-formatted-string.title');
    var artistEl = document.querySelector('ytmusic-player-page .subtitle a, ytmusic-player-page yt-formatted-string.byline a');
    return {
      title:  titleEl  ? titleEl.textContent.trim()  : document.title.replace(' - YouTube Music',''),
      artist: artistEl ? artistEl.textContent.trim() : ''
    };
  }

  function doCast(videoId, title, artist) {
    if (!videoId) { console.warn('[SpyderCast] No videoId'); return; }
    if (window.SpyderCast) window.SpyderCast.castToKodi(videoId, title, artist);
  }

  function attachRowButton(row) {
    if (row.querySelector('.' + CAST_BTN_CLASS)) return;
    var menuBtn = row.querySelector('ytmusic-menu-renderer, tp-yt-paper-icon-button[icon="more_vert"]');
    if (!menuBtn) return;
    var span = document.createElement('span');
    span.className = CAST_BTN_CLASS;
    span.innerHTML = makeSvg(18);
    span.style.cssText = 'display:inline-flex;align-items:center;margin-right:4px;';
    span.addEventListener('click', function(e) {
      e.stopPropagation(); e.preventDefault();
      doCast(videoIdFromRow(span), metaFromRow(span).title, metaFromRow(span).artist);
    });
    menuBtn.parentNode.insertBefore(span, menuBtn);
  }

  function attachPlayerButton() {
    if (document.getElementById(PLAYER_BTN_ID)) return;
    var btn = document.createElement('div');
    btn.id = PLAYER_BTN_ID;
    btn.innerHTML = makeSvg(28);
    btn.style.cssText = 'position:fixed;bottom:80px;right:16px;z-index:9999;' +
      'background:rgba(0,0,0,0.6);border-radius:50%;padding:10px;' +
      'display:flex;align-items:center;justify-content:center;' +
      'box-shadow:0 2px 8px rgba(0,0,0,0.5);';
    btn.addEventListener('click', function(e) {
      e.stopPropagation();
      var m = metaFromPlayer();
      doCast(videoIdFromUrl(), m.title, m.artist);
    });
    document.body.appendChild(btn);
  }

  function removePlayerButton() {
    var btn = document.getElementById(PLAYER_BTN_ID);
    if (btn) btn.remove();
  }

  function injectAll() {
    document.querySelectorAll('ytmusic-responsive-list-item-renderer').forEach(attachRowButton);
    document.querySelectorAll('ytmusic-player-bar').forEach(attachRowButton);
    if (document.querySelector('ytmusic-player-page')) {
      attachPlayerButton();
    } else {
      removePlayerButton();
    }
  }

  setTimeout(injectAll, 1500);

  if (!window._spyderCastObserver) {
    window._spyderCastObserver = new MutationObserver(function() {
      clearTimeout(window._spyderCastTimer);
      window._spyderCastTimer = setTimeout(injectAll, 600);
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
