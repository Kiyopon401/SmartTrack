package com.example.smarttrackapp.utils

import android.util.Log
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.smarttrackapp.models.Vehicle
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import org.json.JSONObject

object MapUtils {

    private val initializedWebViews = mutableSetOf<WebView>()
    private var lastMapUpdateTime = 0L
    private const val MAP_UPDATE_INTERVAL = 4000L // 4 seconds
    private const val MAX_RETRY_ATTEMPTS = 3
    private const val INITIAL_RETRY_DELAY_MS = 1500L

    data class MapReloadConfig(val htmlFile: String, val fromAssets: Boolean, val rawResId: Int?)

    /**
     * Initialize a WebView with given map HTML and settings.
     * If vehicle != null, centers on vehicle location and shows marker popup.
     * If vehicle == null, it just loads the map ready for other drawing (like trip paths).
     *
     * @param onMapErrorCallback Optional callback for repeated map load failures.
     * @param onMapLoadingCallback Optional callback for map loading state (true = loading, false = loaded).
     */
    fun initializeMap(
        webView: WebView,
        vehicle: Vehicle? = null,
        lat: Double = 0.0,
        lng: Double = 0.0,
        htmlFile: String = "",
        fromAssets: Boolean = false,
        rawResId: Int? = null,
        onMapReadyCallback: (() -> Unit)? = null,
        onMapErrorCallback: (() -> Unit)? = null,
        onMapLoadingCallback: ((Boolean) -> Unit)? = null
    ) {
        var retryAttempts = 0
        var retryDelay = INITIAL_RETRY_DELAY_MS
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowContentAccess = true
            allowFileAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        // Store reload config in the WebView tag
        webView.setTag(MapReloadConfig(htmlFile, fromAssets, rawResId))

        // Attach the retry interface ONCE
        webView.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun reloadMap() {
                Handler(Looper.getMainLooper()).post {
                    val config = webView.getTag() as? MapReloadConfig
                    if (config != null) {
                        if (config.fromAssets && config.htmlFile.isNotEmpty()) {
                            webView.loadUrl("file:///android_asset/${config.htmlFile}")
                        } else if (config.rawResId != null) {
                            val inputStream = webView.context.resources.openRawResource(config.rawResId)
                            val htmlString = inputStream.bufferedReader().use { it.readText() }
                            webView.loadDataWithBaseURL(
                                "file:///android_res/raw/",
                                htmlString,
                                "text/html",
                                "utf-8",
                                null
                            )
                        }
                    }
                }
            }
        }, "AndroidRetry")

        if (!initializedWebViews.contains(webView)) {
            webView.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    onMapLoadingCallback?.invoke(true)
                }
                override fun onPageFinished(view: WebView, url: String?) {
                    super.onPageFinished(view, url)
                    retryAttempts = 0
                    retryDelay = INITIAL_RETRY_DELAY_MS
                    onMapLoadingCallback?.invoke(false)
                    vehicle?.let {
                        updateMapLocation(webView, lat, lng, it.nickname)
                    }
                    onMapReadyCallback?.invoke()
                }

                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    if (request?.url?.path?.endsWith("/favicon.ico") == true) {
                        return WebResourceResponse("image/x-icon", null, ByteArrayInputStream(ByteArray(0)))
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    Log.e("WebViewError", "Error loading page: ${error?.description} (code: ${error?.errorCode}) on URL: ${request?.url}")
                    view?.let { clearInitialized(it) }
                    if (retryAttempts < MAX_RETRY_ATTEMPTS) {
                        Handler(Looper.getMainLooper()).postDelayed({
                            retryAttempts++
                            retryDelay *= 2
                            val config = view?.getTag() as? MapReloadConfig
                            val baseUrl = if (config?.fromAssets == true) "file:///android_asset/" else "file:///android_res/raw/"
                            if (config != null) {
                                if (config.fromAssets && config.htmlFile.isNotEmpty()) {
                                    view?.loadUrl("file:///android_asset/${config.htmlFile}")
                                } else if (config.rawResId != null) {
                                    val inputStream = view?.context?.resources?.openRawResource(config.rawResId)
                                    val htmlString = inputStream?.bufferedReader()?.use { it.readText() }
                                    if (htmlString != null) {
                                        view?.loadDataWithBaseURL(
                                            baseUrl,
                                            htmlString,
                                            "text/html",
                                            "utf-8",
                                            null
                                        )
                                    }
                                }
                            }
                        }, retryDelay)
                    } else {
                        onMapLoadingCallback?.invoke(false)
                        onMapErrorCallback?.invoke()
                    view?.post {
                            val config = view.getTag() as? MapReloadConfig
                            val baseUrl = if (config?.fromAssets == true) "file:///android_asset/" else "file:///android_res/raw/"
                            val retryHtml = """
                                <html><body style='text-align:center;'>
                                <h2>Map failed to load after multiple attempts.</h2>
                                <p>Please check your connection or try again later.</p>
                                <button onclick=\"AndroidRetry.reloadMap()\" style='padding:10px 20px;font-size:16px;'>Retry</button>
                                </body></html>
                            """
                            view.loadDataWithBaseURL(
                                baseUrl,
                                retryHtml,
                            "text/html",
                                "utf-8",
                                null
                        )
                        }
                    }
                }
            }

            webView.addJavascriptInterface(object {
                @android.webkit.JavascriptInterface
                fun setGeofenceCenter(lat: Double, lng: Double) {
                    Log.d("Geofence", "New center from map: $lat, $lng")
                }
            }, "AndroidGeofence")

            when {
                fromAssets && htmlFile.isNotEmpty() -> {
                    webView.loadUrl("file:///android_asset/$htmlFile")
                }
                rawResId != null -> {
                    val inputStream = webView.context.resources.openRawResource(rawResId)
                    val htmlString = inputStream.bufferedReader().use { it.readText() }
                    webView.loadDataWithBaseURL(
                        "file:///android_res/raw/",
                        htmlString,
                        "text/html",
                        "utf-8",
                        null
                    )
                }
                else -> {
                    Log.e("MapUtils", "No valid HTML source provided")
                }
            }

            initializedWebViews.add(webView)
        }
    }

    /**
     * Updates vehicle marker position and popup on the map, and moves the camera to follow.
     */
    fun updateMapLocation(webView: WebView, lat: Double, lng: Double, vehicleName: String, isInside: Boolean = true) {
        val safeVehicleName = JSONObject.quote(vehicleName)
        val js = """
            if (typeof updateMarkerPosition === 'function') {
                updateMarkerPosition($lat, $lng, ${isInside.toString()});
                marker.bindPopup($safeVehicleName).openPopup();
                if (typeof map !== 'undefined' && typeof map.setView === 'function') {
                    map.setView([$lat, $lng], map.getZoom());
                }
            }
        """.trimIndent()

        webView.evaluateJavascript(js) { value ->
            Log.d("MapUtils", "Updated map with geofence status and camera: $value")
        }
    }

    /**
     * Draw a trip breadcrumb path (polyline) on the map.
     * Coordinates as List of Pair(lat, lng).
     */
    fun drawTripPath(webView: WebView, coordinates: List<Pair<Double, Double>>) {
        // Prepare JS array of [lat, lng] points
        val jsArray = coordinates.joinToString(",") { "[${it.first}, ${it.second}]" }
        val js = """
            if (typeof drawPath === 'function') {
                drawPath([$jsArray]);
            }
        """.trimIndent()

        webView.post {
            webView.evaluateJavascript(js, null)
        }
    }

    fun clearInitialized(webView: WebView) {
        initializedWebViews.remove(webView)
    }

    fun drawGeofenceCircle(webView: WebView, lat: Double, lng: Double, radius: Double, color: String = "green") {
        webView.post {
            webView.evaluateJavascript("drawGeofenceCircle($lat, $lng, '$color');", null)
        }
    }

    fun removeGeofenceCircle(webView: WebView) {
        webView.post { webView.evaluateJavascript("clearGeofence();", null) }
    }

    fun loadHtmlFromRaw(webView: WebView, rawResourceId: Int) {
        val inputStream = webView.context.resources.openRawResource(rawResourceId)
        val htmlString = inputStream.bufferedReader().use { it.readText() }

        webView.loadDataWithBaseURL(
            "file:///android_res/raw/",
            htmlString,
            "text/html",
            "utf-8",
            null
        )
    }
    fun updateMapLocationDebounced(
        webView: WebView,
        lat: Double,
        lng: Double,
        vehicleName: String,
        isInside: Boolean = true
    ) {
        val now = System.currentTimeMillis()
        if (now - lastMapUpdateTime < MAP_UPDATE_INTERVAL) {
            return
        }
        lastMapUpdateTime = now

        updateMapLocation(webView, lat, lng, vehicleName, isInside)
    }
}
