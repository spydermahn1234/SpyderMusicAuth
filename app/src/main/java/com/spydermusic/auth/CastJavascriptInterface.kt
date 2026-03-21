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

  const CAST_SVG = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" ' +
    'width="18" height="18" fill="none" stroke="#FF4444" stroke-width="2.2" ' +
    'style="cursor:pointer;margin-left:6px;vertical-align:middle;flex-shrink:0;opacity:0.9" ' +
    'title="Cast to Kodi">' +
    '<path d="M2 16.1A5 5 0 0 1 5.9 20M2 12.05A9 9 0 0 1 9.95 20M2 8V6a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-6"/>' +
    '<line x1="2" y1="20" x2="2.01" y2="20"/></svg>';

  function extractVideoId(row) {
    // 1. data attribute set by YTMusic desktop on the row itself or parent
    let el = row.closest('[data-videoid]');
    if (el) return el.dataset.videoid;
    // 2. href on a watch link inside the row
    const a = row.querySelector('a[href*="watch?v="]');
    if (a) { const m = a.href.match(/[?&]v=([^&]+)/); if (m) return m[1]; }
    // 3. YTMusic stores videoId in the renderer's data attribute under various names
    const r = row.closest('ytmusic-responsive-list-item-renderer');
    if (r) {
      const id = r.getAttribute('data-videoid') || r.getAttribute('videoid');
      if (id) return id;
    }
    return null;
  }

  function extractMeta(row) {
    const r = row.closest('ytmusic-responsive-list-item-renderer') || row;
    const titleEl  = r.querySelector('.title, yt-formatted-string.title, [class*="title"]');
    const artistEl = r.querySelector('.secondary-flex-columns a, yt-formatted-string[class*="subtitle"] a');
    return {
      title:  (titleEl  && titleEl.textContent.trim())  || '',
      artist: (artistEl && artistEl.textContent.trim()) || '',
    };
  }

  function attachCastButton(row) {
    if (row.querySelector('.' + CAST_BTN_CLASS)) return;
    // Find the three-dot overflow menu button — insert the cast button right before it
    const menuBtn = row.querySelector('ytmusic-menu-renderer, .menu, tp-yt-paper-icon-button');
    if (!menuBtn) return;  // row not fully rendered yet — MutationObserver will retry

    const span = document.createElement('span');
    span.className = CAST_BTN_CLASS;
    span.innerHTML = CAST_SVG;
    span.style.cssText = 'display:inline-flex;align-items:center;';
    span.addEventListener('click', function(e) {
      e.stopPropagation();
      e.preventDefault();
      const vid = extractVideoId(span);
      if (!vid) { console.warn('[SpyderCast] No videoId on row'); return; }
      const { title, artist } = extractMeta(span);
      if (window.SpyderCast) window.SpyderCast.castToKodi(vid, title, artist);
    });

    // Insert before the three-dot menu
    menuBtn.parentNode.insertBefore(span, menuBtn);
  }

  function injectAll() {
    document.querySelectorAll('ytmusic-responsive-list-item-renderer').forEach(attachCastButton);
    // Also inject into the now-playing footer bar
    document.querySelectorAll('ytmusic-player-bar').forEach(attachCastButton);
  }

  // Run after a settle (content loads async)
  setTimeout(injectAll, 1500);

  // Re-run on every DOM change (SPA navigation replaces track lists in-place).
  // Use a short debounce to batch rapid mutations during list rendering.
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
