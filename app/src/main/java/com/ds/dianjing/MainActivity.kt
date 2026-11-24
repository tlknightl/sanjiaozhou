package com.ds.dianjing

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebChromeClient.FileChooserParams
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.ds.dianjing.databinding.ActivityMainBinding
import com.ds.dianjing.util.UrlUtils
import com.ds.dianjing.web.WebAppBridge
import java.util.Locale

/**
 * DS电竞原生客户端主容器。
 * 服务器地址在编译期通过 BuildConfig.SERVER_URL 固化，对最终用户完全不可见且不可修改。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var pendingFileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var lastBackPressAt = 0L
    private var pageLoadFailed = false

    private val embeddedServerUrl: String = BuildConfig.SERVER_URL.trim().trimEnd('/')

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            handleFileChooserResult(result.resultCode, result.data)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        configureWindowForEdgeToEdge()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyWindowInsets()
        configureWebView()
        configurePullToRefresh()
        configureErrorView()
        configureBackNavigation()

        val restoredState = savedInstanceState?.let { binding.webView.restoreState(it) }
        if (restoredState == null) {
            loadEmbeddedSite()
        }
    }

    private fun configureWindowForEdgeToEdge() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val bottomInset = maxOf(safe.bottom, ime.bottom)
            val density = resources.displayMetrics.density

            binding.swipeRefresh.setPadding(safe.left, safe.top, safe.right, bottomInset)
            updateMargins(binding.progressBar, safe.left, safe.top, safe.right, 0)

            val errorPadding = resources.getDimensionPixelSize(R.dimen.error_padding)
            binding.errorView.setPadding(
                safe.left + errorPadding,
                safe.top + errorPadding,
                safe.right + errorPadding,
                bottomInset + errorPadding
            )

            val spinnerGap = (16 * density).toInt()
            binding.swipeRefresh.setProgressViewOffset(
                false,
                safe.top + spinnerGap,
                safe.top + spinnerGap + (64 * density).toInt()
            )
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun updateMargins(view: View, left: Int, top: Int, right: Int, bottom: Int) {
        val params = view.layoutParams as ViewGroup.MarginLayoutParams
        if (params.leftMargin == left &&
            params.topMargin == top &&
            params.rightMargin == right &&
            params.bottomMargin == bottom
        ) {
            return
        }
        params.setMargins(left, top, right, bottom)
        view.layoutParams = params
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private fun configureWebView() {
        val webView = binding.webView
        webView.setBackgroundColor(ContextCompat.getColor(this, R.color.brand_background))

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)

            allowFileAccess = false
            allowContentAccess = true
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false

            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            displayZoomControls = false

            userAgentString = userAgentString + " DSAndroid/" + BuildConfig.VERSION_NAME

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                setAlgorithmicDarkeningAllowed(false)
            }
        }

        webView.webViewClient = createWebViewClient()
        webView.webChromeClient = createWebChromeClient()
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            startDownload(url, userAgent, contentDisposition, mimeType)
        }
        webView.addJavascriptInterface(
            WebAppBridge(this),
            WebAppBridge.NATIVE_INTERFACE_NAME
        )
        installDocumentStartBridge(webView)
    }

    private fun installDocumentStartBridge(webView: WebView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            return
        }
        try {
            WebViewCompat.addDocumentStartJavaScript(
                webView,
                WebAppBridge.FACADE_SCRIPT,
                setOf("*")
            )
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Unable to register document-start JS bridge.", e)
        }
    }

    private fun createWebViewClient(): WebViewClient = object : WebViewClient() {

        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?
        ): Boolean {
            if (request?.isForMainFrame != true) return false
            val target = request.url
            return when (target.scheme?.lowercase(Locale.ROOT)) {
                "http", "https" -> {
                    if (UrlUtils.isSameSite(target.toString(), embeddedServerUrl)) {
                        false
                    } else {
                        openExternal(target)
                        true
                    }
                }
                "intent" -> {
                    openExternal(target)
                    true
                }
                "about", "data", "blob", "javascript" -> false
                "file" -> true
                else -> {
                    openExternal(target)
                    true
                }
            }
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            pageLoadFailed = false
            binding.progressBar.progress = 0
            binding.progressBar.visibility = View.VISIBLE
            hideError()
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            if (pageLoadFailed) return
            binding.progressBar.visibility = View.GONE
            binding.swipeRefresh.isRefreshing = false
            hideError()
            view?.evaluateJavascript(WebAppBridge.FACADE_SCRIPT, null)
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?
        ) {
            if (request?.isForMainFrame != true || error == null) return
            val description = error.description?.toString().orEmpty()
            if (error.errorCode == WebViewClient.ERROR_UNKNOWN &&
                (description.contains("ERR_ABORTED", ignoreCase = true) ||
                    description.contains("ERR_BLOCKED_BY_CLIENT", ignoreCase = true))
            ) {
                return
            }
            pageLoadFailed = true
            showConnectionError(error.errorCode)
        }

        override fun onReceivedHttpError(
            view: WebView?,
            request: WebResourceRequest?,
            response: WebResourceResponse?
        ) {
            if (request?.isForMainFrame != true) return
            val statusCode = response?.statusCode ?: return
            if (statusCode >= 500) {
                pageLoadFailed = true
                showError(
                    getString(R.string.error_title),
                    getString(R.string.error_http_server, statusCode)
                )
            }
        }

        override fun onReceivedSslError(
            view: WebView?,
            handler: SslErrorHandler?,
            error: SslError?
        ) {
            handler?.cancel()
            pageLoadFailed = true
            showError(
                getString(R.string.error_title),
                getString(R.string.error_ssl)
            )
        }
    }

    private fun createWebChromeClient(): WebChromeClient = object : WebChromeClient() {

        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            if (pageLoadFailed) return
            binding.progressBar.progress = newProgress
            binding.progressBar.visibility =
                if (newProgress >= 100) View.GONE else View.VISIBLE
        }

        override fun onShowFileChooser(
            webView: WebView?,
            filePathCallback: ValueCallback<Array<Uri>>?,
            fileChooserParams: FileChooserParams?
        ): Boolean {
            pendingFileChooserCallback?.onReceiveValue(null)
            pendingFileChooserCallback = null

            if (filePathCallback == null || fileChooserParams == null) {
                filePathCallback?.onReceiveValue(null)
                return false
            }

            val chooserIntent = try {
                fileChooserParams.createIntent()
            } catch (e: Exception) {
                filePathCallback.onReceiveValue(null)
                return false
            }

            if (fileChooserParams.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                chooserIntent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }

            pendingFileChooserCallback = filePathCallback
            return try {
                fileChooserLauncher.launch(
                    Intent.createChooser(chooserIntent, getString(R.string.choose_file))
                )
                true
            } catch (e: ActivityNotFoundException) {
                pendingFileChooserCallback = null
                filePathCallback.onReceiveValue(null)
                showToast(R.string.file_chooser_failed)
                false
            }
        }

        @SuppressLint("SetJavaScriptEnabled")
        override fun onCreateWindow(
            view: WebView?,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message?
        ): Boolean {
            val message = resultMsg ?: return false
            val transport = message.obj as? WebView.WebViewTransport ?: return false

            val popupWebView = WebView(this@MainActivity)
            popupWebView.settings.javaScriptEnabled = true
            popupWebView.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val target = request?.url ?: return false
                    if (UrlUtils.isSameSite(target.toString(), embeddedServerUrl)) {
                        binding.webView.loadUrl(target.toString())
                    } else {
                        openExternal(target)
                    }
                    binding.webView.post { popupWebView.destroy() }
                    return true
                }
            }

            transport.webView = popupWebView
            message.sendToTarget()
            return true
        }
    }

    private fun configurePullToRefresh() {
        binding.swipeRefresh.setColorSchemeColors(
            ContextCompat.getColor(this, R.color.accent),
            ContextCompat.getColor(this, R.color.text_primary)
        )
        binding.swipeRefresh.setProgressBackgroundColorSchemeColor(
            ContextCompat.getColor(this, R.color.brand_surface)
        )
        binding.swipeRefresh.setOnRefreshListener {
            if (binding.webView.visibility == View.VISIBLE) {
                binding.webView.reload()
            } else {
                binding.swipeRefresh.isRefreshing = false
                loadEmbeddedSite()
            }
        }
        binding.swipeRefresh.setOnChildScrollUpCallback { _, _ ->
            binding.webView.canScrollVertically(-1)
        }
    }

    private fun configureErrorView() {
        binding.retryButton.setOnClickListener { loadEmbeddedSite() }
    }

    private fun configureBackNavigation() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (binding.errorView.visibility == View.VISIBLE) {
                        doubleBackToExit()
                        return
                    }
                    binding.webView.evaluateJavascript(
                        "(function(){try{return typeof window.DSAndroidBack==='function' && window.DSAndroidBack()===true;}catch(e){return false;}})();"
                    ) { raw ->
                        if (isFinishing || isDestroyed) return@evaluateJavascript
                        if (raw?.trim() == "true") {
                            return@evaluateJavascript
                        }
                        if (binding.webView.canGoBack()) {
                            binding.webView.goBack()
                        } else {
                            doubleBackToExit()
                        }
                    }
                }
            }
        )
    }

    private fun doubleBackToExit() {
        val now = System.currentTimeMillis()
        if (now - lastBackPressAt <= DOUBLE_BACK_INTERVAL_MS) {
            finish()
            return
        }
        lastBackPressAt = now
        showToast(R.string.exit_hint)
    }

    private fun loadEmbeddedSite() {
        if (embeddedServerUrl.isBlank()) {
            showError(getString(R.string.error_title), getString(R.string.error_message))
            return
        }
        hideError()
        pageLoadFailed = false
        binding.swipeRefresh.isEnabled = true
        binding.webView.visibility = View.VISIBLE
        binding.progressBar.progress = 0
        binding.progressBar.visibility = View.VISIBLE
        binding.webView.loadUrl(embeddedServerUrl)
    }

    private fun showConnectionError(errorCode: Int) {
        val message = when (errorCode) {
            WebViewClient.ERROR_TIMEOUT -> getString(R.string.error_timeout)
            WebViewClient.ERROR_FAILED_SSL_HANDSHAKE -> getString(R.string.error_ssl)
            else -> getString(R.string.error_message)
        }
        showError(getString(R.string.error_title), message)
    }

    private fun showError(title: String, message: String) {
        binding.swipeRefresh.isRefreshing = false
        binding.progressBar.visibility = View.GONE
        binding.errorTitle.text = title
        binding.errorMessage.text = message
        binding.errorView.visibility = View.VISIBLE
        binding.webView.visibility = View.INVISIBLE
        binding.swipeRefresh.isEnabled = false
    }

    private fun hideError() {
        if (binding.errorView.visibility != View.VISIBLE) return
        binding.errorView.visibility = View.GONE
        binding.swipeRefresh.isEnabled = true
        binding.webView.visibility = View.VISIBLE
    }

    private fun handleFileChooserResult(resultCode: Int, data: Intent?) {
        val callback = pendingFileChooserCallback ?: return
        pendingFileChooserCallback = null

        if (resultCode != Activity.RESULT_OK || data == null) {
            callback.onReceiveValue(null)
            return
        }

        val clipData = data.clipData
        val uris: Array<Uri>? = if (clipData != null && clipData.itemCount > 0) {
            Array(clipData.itemCount) { index -> clipData.getItemAt(index).uri }
        } else {
            data.data?.let { arrayOf(it) }
        }
        callback.onReceiveValue(uris?.takeIf { it.isNotEmpty() })
    }

    private fun startDownload(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ) {
        val uri = Uri.parse(url)
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") {
            showToast(R.string.download_unsupported)
            return
        }

        val guessedName = URLUtil.guessFileName(url, contentDisposition, mimeType).orEmpty()
        val safeFileName = guessedName
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .take(120)
            .ifBlank { "ds_download_${System.currentTimeMillis()}" }

        val request = DownloadManager.Request(uri)
            .setTitle(safeFileName)
            .setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .setDestinationInExternalFilesDir(
                this,
                Environment.DIRECTORY_DOWNLOADS,
                safeFileName
            )

        if (!userAgent.isNullOrBlank()) {
            request.addRequestHeader("User-Agent", userAgent)
        }
        if (!mimeType.isNullOrBlank()) {
            request.setMimeType(mimeType)
        }
        val cookie = CookieManager.getInstance().getCookie(url)
        if (!cookie.isNullOrBlank()) {
            request.addRequestHeader("Cookie", cookie)
        }

        try {
            val manager = getSystemService(DownloadManager::class.java)
            if (manager == null) {
                showToast(R.string.download_failed)
                return
            }
            manager.enqueue(request)
            Toast.makeText(
                this,
                getString(R.string.download_started, safeFileName),
                Toast.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            showToast(R.string.download_failed)
        }
    }

    private fun openExternal(uri: Uri) {
        val scheme = uri.scheme
        val intent = try {
            if (scheme != null && scheme.equals("intent", ignoreCase = true)) {
                Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                    ?: throw IllegalArgumentException("Invalid intent URI")
            } else {
                Intent(Intent.ACTION_VIEW, uri)
            }
        } catch (e: Exception) {
            showToast(R.string.no_app_for_link)
            return
        }

        try {
            startActivity(intent)
        } catch (e: Exception) {
            showToast(R.string.no_app_for_link)
        }
    }

    private fun showToast(messageRes: Int) {
        Toast.makeText(this, messageRes, Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        binding.webView.onResume()
        binding.webView.resumeTimers()
    }

    override fun onPause() {
        binding.webView.onPause()
        binding.webView.pauseTimers()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (this::binding.isInitialized) {
            binding.webView.saveState(outState)
        }
    }

    override fun onDestroy() {
        pendingFileChooserCallback?.onReceiveValue(null)
        pendingFileChooserCallback = null

        if (this::binding.isInitialized && !isChangingConfigurations) {
            (binding.webView.parent as? ViewGroup)?.removeView(binding.webView)
            binding.webView.stopLoading()
            binding.webView.setDownloadListener(null)
            binding.webView.removeJavascriptInterface(WebAppBridge.NATIVE_INTERFACE_NAME)
            binding.webView.destroy()
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val DOUBLE_BACK_INTERVAL_MS = 2_000L
    }
}
