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
  if (window._spyderCastInjected) return;
  window._spyderCastInjected = true;

  const CAST_SVG = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"
    width="20" height="20" fill="none" stroke="#FF4444" stroke-width="2"
    style="cursor:pointer;margin-left:8px;vertical-align:middle;flex-shrink:0"
    title="Cast to Kodi">
    <path d="M2 16.1A5 5 0 0 1 5.9 20M2 12.05A9 9 0 0 1 9.95 20M2 8V6a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-6"/>
    <line x1="2" y1="20" x2="2.01" y2="20"/>
  </svg>`;

  function extractVideoId(el) {
    // Try data-videoid attribute first (most reliable)
    let vid = el.closest('[data-videoid]')?.dataset?.videoid;
    if (vid) return vid;
    // Fallback: parse from any nearby <a> href containing watch?v=
    const a = el.closest('ytmusic-responsive-list-item-renderer,ytmusic-player-bar')
               ?.querySelector('a[href*="watch?v="]');
    if (a) {
      const m = a.href.match(/[?&]v=([^&]+)/);
      if (m) return m[1];
    }
    return null;
  }

  function extractMeta(container) {
    const titleEl  = container.querySelector('.title, .song-title, [class*="title"]');
    const artistEl = container.querySelector('.secondary-flex-columns a, [class*="artist"]');
    return {
      title:  titleEl?.textContent?.trim()  || '',
      artist: artistEl?.textContent?.trim() || '',
    };
  }

  function attachCastButton(container) {
    if (container.querySelector('._spyderCastBtn')) return; // already attached
    const wrapper = document.createElement('span');
    wrapper.className = '_spyderCastBtn';
    wrapper.innerHTML = CAST_SVG;
    wrapper.addEventListener('click', function(e) {
      e.stopPropagation();
      e.preventDefault();
      const vid = extractVideoId(container);
      if (!vid) { console.warn('[SpyderCast] Could not extract videoId'); return; }
      const { title, artist } = extractMeta(container);
      if (window.SpyderCast) {
        window.SpyderCast.castToKodi(vid, title, artist);
      }
    });
    // Insert into the action/menu row so it sits next to the existing icons
    const actionRow = container.querySelector('.menu, [class*="action"], [class*="button-row"]');
    if (actionRow) {
      actionRow.appendChild(wrapper);
    } else {
      container.appendChild(wrapper);
    }
  }

  function injectButtons() {
    document.querySelectorAll(
      'ytmusic-responsive-list-item-renderer, ytmusic-player-bar'
    ).forEach(attachCastButton);
  }

  // Initial injection after a short settle
  setTimeout(injectButtons, 1200);

  // Re-inject on DOM mutations (SPA navigation updates the track list in place)
  const observer = new MutationObserver(function() {
    clearTimeout(window._spyderCastDebounce);
    window._spyderCastDebounce = setTimeout(injectButtons, 400);
  });
  observer.observe(document.body, { childList: true, subtree: true });
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
