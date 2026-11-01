package com.amko.oldgram

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.amko.oldgram.ui.theme.OldgramTheme
import java.io.ByteArrayInputStream

private const val TAG_REQ = "OldgramREQ"
private const val TAG_NET = "OldgramNET"

private const val BLOCK_PART = "/api/v1/discover/web/explore_grid/"

private const val EMPTY_GRID =
    "{\"sectional_items\":[],\"rank_token\":\"\",\"auto_load_more_enabled\":false,\"more_available\":false,\"next_max_id\":null,\"status\":\"ok\"}"

private const val NET_HOOK_JS = """
(function() {
    if (window.__oldgramNet) { return; }
    window.__oldgramNet = true;
    function caughtUp() {
        return document.body && (document.body.innerText || '').toLowerCase().indexOf('caught up') !== -1;
    }
    function shouldBlock(url, body) {
        if (String(url).indexOf('/graphql/query') === -1) { return false; }
        if (typeof body !== 'string') { return false; }
        var name = '';
        try { name = new URLSearchParams(body).get('fb_api_req_friendly_name') || ''; } catch (e) {}
        if (name.indexOf('PolarisFeedRootPaginationCachedQuery') === -1) { return false; }
        return caughtUp();
    }
    var of = window.fetch;
    window.fetch = function(input, opts) {
        try {
            var u = typeof input === 'string' ? input : input.url;
            if (shouldBlock(u, opts && opts.body)) {
                OldgramBridge.log('BLOCKED fetch feed pagination');
                return Promise.reject(new TypeError('blocked'));
            }
        } catch (e) {}
        return of.apply(this, arguments);
    };
    var xo = XMLHttpRequest.prototype.open;
    var xs = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.open = function(m, u) {
        this.__ogu = u;
        return xo.apply(this, arguments);
    };
    XMLHttpRequest.prototype.send = function(b) {
        try {
            if (shouldBlock(this.__ogu, b)) {
                OldgramBridge.log('BLOCKED xhr feed pagination');
                this.abort();
                return;
            }
        } catch (e) {}
        return xs.apply(this, arguments);
    };
})();
"""

private const val FIX_JS = """
(function() {
    var h = window.innerHeight + 'px';
    function apply() {
        var b = document.body;
        if (!b) { return; }
        document.documentElement.style.setProperty('height', h, 'important');
        b.style.setProperty('height', h, 'important');
        var n = document.querySelector('.wbloks_88');
        while (n && n !== b) {
            n.style.setProperty('height', h, 'important');
            n.style.setProperty('min-height', h, 'important');
            n = n.parentElement;
        }
    }
    if (window.__oldgramTimer) { clearInterval(window.__oldgramTimer); }
    var t = 0;
    window.__oldgramTimer = setInterval(function() {
        apply();
        t++;
        if (t > 120) { clearInterval(window.__oldgramTimer); }
    }, 250);
    apply();
})();
"""

private const val HIDE_REELS_JS = """
(function() {
    if (window.__oldgramHide) { return; }
    window.__oldgramHide = true;
    function hideReels() {
        var links = document.querySelectorAll('a[href*="/reels"], a[href*="/reel/"]');
        for (var i = 0; i < links.length; i++) {
            var a = links[i];
            var r = a.getBoundingClientRect();
            if (r.height === 0 || r.top < window.innerHeight * 0.75) { continue; }
            var item = a;
            while (item.parentElement && item.parentElement.children.length < 4 && item.parentElement !== document.body) {
                item = item.parentElement;
            }
            item.style.setProperty('display', 'none', 'important');
        }
    }
    hideReels();
    var pending = false;
    new MutationObserver(function() {
        if (pending) { return; }
        pending = true;
        setTimeout(function() { pending = false; hideReels(); }, 100);
    }).observe(document.documentElement, { childList: true, subtree: true });
})();
"""

private const val HIDE_EXPLORE_JS = """
(function() {
    if (window.__oldgramExplore) { return; }
    window.__oldgramExplore = true;
    function hideGrid() {
        if (location.pathname.indexOf('/explore') !== 0) { return; }
        var links = document.querySelectorAll('a[href*="/p/"], a[href*="/reel/"]');
        for (var i = 0; i < links.length; i++) {
            var a = links[i];
            var r = a.getBoundingClientRect();
            if (r.top > window.innerHeight * 0.75 && r.height > 0 && r.width < 80) { continue; }
            var item = a;
            while (item.parentElement &&
                   item.parentElement !== document.body &&
                   !item.parentElement.querySelector('input') &&
                   !item.parentElement.querySelector('a[href*="/direct/"]')) {
                item = item.parentElement;
            }
            item.style.setProperty('display', 'none', 'important');
        }
    }
    hideGrid();
    var pending = false;
    new MutationObserver(function() {
        if (pending) { return; }
        pending = true;
        setTimeout(function() { pending = false; hideGrid(); }, 100);
    }).observe(document.documentElement, { childList: true, subtree: true });
})();
"""

private const val HIDE_LOADER_JS = """
(function() {
    if (window.__oldgramLoader) { return; }
    window.__oldgramLoader = true;
    function hideLoader() {
        var p = location.pathname;
        var onExplore = (p === '/explore' || p === '/explore/');
        var onHome = (p === '/' || p === '');
        var caught = onHome && document.body &&
            (document.body.innerText || '').toLowerCase().indexOf('caught up') !== -1;
        if (!onExplore && !caught) { return; }
        var els = document.querySelectorAll('[role="progressbar"], [aria-label*="oading"]');
        for (var i = 0; i < els.length; i++) {
            els[i].style.setProperty('display', 'none', 'important');
            var par = els[i].parentElement;
            if (par && par.children.length === 1 && !par.querySelector('input')) {
                par.style.setProperty('display', 'none', 'important');
            }
        }
    }
    hideLoader();
    var pending = false;
    new MutationObserver(function() {
        if (pending) { return; }
        pending = true;
        setTimeout(function() { pending = false; hideLoader(); }, 50);
    }).observe(document.documentElement, { childList: true, subtree: true });
})();
"""

private const val HIDE_ADS_JS = """
(function() {
    if (window.__oldgramAds) { return; }
    window.__oldgramAds = true;
    var labels = [
        'ad', 'ads', 'sponsored', 'reklama', 'oglas', 'sponzorirano', 'sponzorisano',
        'anzeige', 'gesponsert', 'publicité', 'sponsorisé', 'anuncio', 'patrocinado',
        'annuncio', 'sponsorizzato', 'advertentie', 'gesponsord', 'annons', 'sponsrad',
        'reklam', 'sponsorlu'
    ];
    function hideAds() {
        if (location.pathname !== '/' && location.pathname !== '') { return; }
        var spans = document.querySelectorAll('span');
        for (var i = 0; i < spans.length; i++) {
            var s = spans[i];
            if (s.dataset.ogChecked) { continue; }
            if (s.children.length > 0) { continue; }
            var txt = (s.textContent || '').trim().toLowerCase();
            if (txt.length === 0 || txt.length > 20) { continue; }
            s.dataset.ogChecked = '1';
            if (labels.indexOf(txt) === -1) { continue; }
            var target = s.closest('article');
            if (!target) {
                target = s;
                while (target.parentElement &&
                       target.parentElement !== document.body &&
                       target.getBoundingClientRect().height < 400) {
                    target = target.parentElement;
                }
            }
            target.style.setProperty('display', 'none', 'important');
        }
    }
    hideAds();
    var pending = false;
    new MutationObserver(function() {
        if (pending) { return; }
        pending = true;
        setTimeout(function() { pending = false; hideAds(); }, 100);
    }).observe(document.documentElement, { childList: true, subtree: true });
})();
"""

private const val HIDE_SUGGESTED_JS = """
(function() {
    if (window.__oldgramSugg) { return; }
    window.__oldgramSugg = true;
    var heads = [
        'suggested posts', 'suggested for you', 'predložene objave', 'predloženi postovi',
        'preporučene objave', 'preporučeni postovi', 'vorgeschlagene beiträge',
        'publicaciones sugeridas', 'publicações sugeridas', 'publications suggérées',
        'post suggeriti'
    ];
    function hasNav(n) {
        return n.querySelector('a[href="/explore/"], a[href="/direct/inbox/"]') !== null;
    }
    function hideSuggested() {
        if (location.pathname !== '/' && location.pathname !== '') { return; }
        var els = document.querySelectorAll('span, div, h1, h2, h3');
        for (var i = 0; i < els.length; i++) {
            var el = els[i];
            if (el.children.length > 0) { continue; }
            var txt = (el.textContent || '').trim().toLowerCase();
            if (txt.length === 0 || txt.length > 30) { continue; }
            if (heads.indexOf(txt) === -1) { continue; }
            el.style.setProperty('display', 'none', 'important');
            var node = el;
            var depth = 0;
            while (node.parentElement && node.parentElement !== document.body && depth < 12) {
                var sib = node.nextElementSibling;
                while (sib) {
                    if (!hasNav(sib) && getComputedStyle(sib).position !== 'fixed') {
                        sib.style.setProperty('display', 'none', 'important');
                    }
                    sib = sib.nextElementSibling;
                }
                var parentText = (node.parentElement.innerText || '').toLowerCase();
                if (parentText.indexOf('caught up') !== -1) { break; }
                node = node.parentElement;
                depth++;
            }
        }
    }
    hideSuggested();
    var pending = false;
    new MutationObserver(function() {
        if (pending) { return; }
        pending = true;
        setTimeout(function() { pending = false; hideSuggested(); }, 100);
    }).observe(document.documentElement, { childList: true, subtree: true });
})();
"""

class NetBridge {
    @JavascriptInterface
    fun log(message: String) {
        Log.e(TAG_NET, message)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OldgramTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    InstagramWebView(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    )
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onStop() {
        super.onStop()
        CookieManager.getInstance().flush()
    }
}

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun InstagramWebView(modifier: Modifier = Modifier) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }

    BackHandler(enabled = canGoBack) {
        webView?.goBack()
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                if (Build.VERSION.SDK_INT >= 33) {
                    settings.isAlgorithmicDarkeningAllowed = false
                }
                settings.userAgentString = settings.userAgentString
                    .replace("; wv)", ")")
                    .replace(Regex("Version/\\d+\\.\\d+ "), "")
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                addJavascriptInterface(NetBridge(), "OldgramBridge")
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        val scheme = request?.url?.scheme
                        return scheme != "http" && scheme != "https"
                    }

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val url = request?.url?.toString() ?: return null
                        if (url.contains(BLOCK_PART)) {
                            Log.e(TAG_REQ, "BLOCKED $url")
                            return WebResourceResponse(
                                "application/json",
                                "UTF-8",
                                200,
                                "OK",
                                mapOf("Cache-Control" to "no-store"),
                                ByteArrayInputStream(EMPTY_GRID.toByteArray())
                            )
                        }
                        return null
                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        view?.evaluateJavascript(NET_HOOK_JS, null)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        CookieManager.getInstance().flush()
                        view?.evaluateJavascript(NET_HOOK_JS, null)
                        view?.evaluateJavascript(FIX_JS, null)
                        view?.evaluateJavascript(HIDE_REELS_JS, null)
                        view?.evaluateJavascript(HIDE_EXPLORE_JS, null)
                        view?.evaluateJavascript(HIDE_LOADER_JS, null)
                        view?.evaluateJavascript(HIDE_ADS_JS, null)
                        view?.evaluateJavascript(HIDE_SUGGESTED_JS, null)
                        view?.postDelayed({
                            view.evaluateJavascript(NET_HOOK_JS, null)
                            view.evaluateJavascript(FIX_JS, null)
                            view.evaluateJavascript(HIDE_REELS_JS, null)
                            view.evaluateJavascript(HIDE_EXPLORE_JS, null)
                            view.evaluateJavascript(HIDE_LOADER_JS, null)
                            view.evaluateJavascript(HIDE_ADS_JS, null)
                            view.evaluateJavascript(HIDE_SUGGESTED_JS, null)
                        }, 1500)
                    }

                    override fun doUpdateVisitedHistory(
                        view: WebView?,
                        url: String?,
                        isReload: Boolean
                    ) {
                        canGoBack = view?.canGoBack() == true
                        CookieManager.getInstance().flush()
                    }
                }
                loadUrl("https://www.instagram.com")
                webView = this
            }
        }
    )
}