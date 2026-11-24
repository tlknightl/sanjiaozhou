package com.ds.dianjing.web

import android.webkit.JavascriptInterface
import android.widget.Toast
import com.ds.dianjing.BuildConfig
import com.ds.dianjing.MainActivity

/**
 * 极简原生 JS Bridge：仅向内置站点暴露版本号、原生提示与退出能力，
 * 不暴露任何服务器地址读取或修改接口。
 *
 *   window.DSNative.version
 *   window.DSNative.getVersion()
 *   window.DSNative.toast(message)
 *   window.DSNative.exitApp()
 */
class WebAppBridge(
    private val activity: MainActivity
) {

    @JavascriptInterface
    fun getVersion(): String = BuildConfig.VERSION_NAME

    @JavascriptInterface
    fun toast(message: String?) {
        val text = message?.trim().orEmpty()
        if (text.isEmpty()) return
        val safeText = if (text.length > 200) text.substring(0, 200) else text
        activity.runOnUiThread {
            Toast.makeText(activity.applicationContext, safeText, Toast.LENGTH_SHORT).show()
        }
    }

    @JavascriptInterface
    fun exitApp() {
        activity.runOnUiThread { activity.finish() }
    }

    companion object {
        const val NATIVE_INTERFACE_NAME = "__DSNativeBridge"

        private val escapedVersion: String =
            BuildConfig.VERSION_NAME.replace("\\", "\\\\").replace("'", "\\'")

        val FACADE_SCRIPT: String = """
            (function () {
                if (!window.__DSNativeBridge) { return; }
                var bridge = window.__DSNativeBridge;
                window.DSNative = {
                    version: '$escapedVersion',
                    getVersion: function () { return bridge.getVersion(); },
                    toast: function (message) {
                        bridge.toast(message == null ? '' : String(message));
                    },
                    exitApp: function () { bridge.exitApp(); }
                };
            })();
        """.trimIndent()
    }
}
