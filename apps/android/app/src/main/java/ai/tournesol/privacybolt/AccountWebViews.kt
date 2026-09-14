package ai.tournesol.privacybolt

import android.webkit.WebView

/** Main-thread registry. Destroy account pages before deleting their shared cookie
 * store: finishing an Activity only schedules onDestroy and can otherwise race login. */
internal object AccountWebViews {
    private val views = mutableSetOf<WebView>()

    fun register(view: WebView) { views.add(view) }

    fun close(view: WebView) {
        if (!views.remove(view)) return
        view.stopLoading()
        view.removeJavascriptInterface("ppAndroid")
        view.webChromeClient = null
        view.webViewClient = android.webkit.WebViewClient()
        (view.parent as? android.view.ViewGroup)?.removeView(view)
        view.clearHistory()
        view.clearCache(true)
        view.destroy()
    }

    fun closeAll() { views.toList().forEach(::close) }
}
