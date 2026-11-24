# Keep the minimal JavaScript bridge methods if minification is enabled later.
-keepattributes *Annotation*
-keepclassmembers class com.ds.dianjing.web.WebAppBridge {
    @android.webkit.JavascriptInterface <methods>;
}
