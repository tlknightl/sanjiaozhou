plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * 内置服务器地址（编译进 APK，运行期对用户完全不可见、不可修改）。
 *
 * 读取优先级：
 *   1. Gradle 属性 `-PserverUrl=https://...`（GitHub Actions 手动输入或仓库变量）
 *   2. 环境变量 `DS_SERVER_URL`
 *   3. 下方 defaultFallbackServerUrl（修改后重新打包即可生效）
 */
val defaultFallbackServerUrl = "http://192.168.1.3:8080"

val rawServerUrl: String = (project.findProperty("serverUrl") as? String)
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?: System.getenv("DS_SERVER_URL")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    ?: defaultFallbackServerUrl

val configuredServerUrl: String = rawServerUrl
    .let { url ->
        val lower = url.lowercase()
        when {
            lower.startsWith("http://") || lower.startsWith("https://") -> url
            // 局域网 / 本机地址默认走 http，避免被自动补成 https 导致连不上
            Regex("^(localhost|127\\.0\\.0\\.1|10\\.\\d+\\.\\d+\\.\\d+|192\\.168\\.\\d+\\.\\d+|172\\.(1[6-9]|2\\d|3[01])\\.\\d+\\.\\d+)(:\\d+)?(/.*)?$")
                .matches(lower) -> "http://$url"
            // 其余（域名）默认走 https
            else -> "https://$url"
        }
    }
    .trimEnd('/')

require(configuredServerUrl.startsWith("http://") || configuredServerUrl.startsWith("https://")) {
    "Invalid SERVER_URL: $configuredServerUrl"
}

/** Escapes a plain string so it is a valid Java/Kotlin string literal. */
fun String.asBuildConfigString(): String {
    val escaped = replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
    return "\"$escaped\""
}

// Release signing is only enabled when every value below is present and the
// keystore file exists. Missing secrets must never make CI fail.
val releaseKeystorePath: String? =
    System.getenv("ANDROID_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
        ?: (project.findProperty("androidKeystorePath") as? String)?.takeIf { it.isNotBlank() }
val releaseKeystorePassword: String? =
    System.getenv("ANDROID_KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }
        ?: (project.findProperty("androidKeystorePassword") as? String)?.takeIf { it.isNotBlank() }
val releaseKeyAlias: String? =
    System.getenv("ANDROID_KEY_ALIAS")?.takeIf { it.isNotBlank() }
        ?: (project.findProperty("androidKeyAlias") as? String)?.takeIf { it.isNotBlank() }
val releaseKeyPassword: String? =
    System.getenv("ANDROID_KEY_PASSWORD")?.takeIf { it.isNotBlank() }
        ?: (project.findProperty("androidKeyPassword") as? String)?.takeIf { it.isNotBlank() }

val releaseKeystoreFile = releaseKeystorePath?.let { file(it) }
val hasReleaseSigning =
    releaseKeystoreFile?.exists() == true &&
        releaseKeystorePassword != null &&
        releaseKeyAlias != null &&
        releaseKeyPassword != null

android {
    namespace = "com.ds.dianjing"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ds.dianjing"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        vectorDrawables {
            useSupportLibrary = true
        }

        // 编译期固化服务器地址，App 运行期不可见、不可修改
        buildConfigField(
            "String",
            "SERVER_URL",
            configuredServerUrl.asBuildConfigString()
        )
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    val releaseSigningConfig = signingConfigs.findByName("release")

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (releaseSigningConfig != null) {
                signingConfig = releaseSigningConfig
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
}

logger.lifecycle("========================================================")
logger.lifecycle(" [DSDianjing] 内置服务器地址 = $configuredServerUrl")
logger.lifecycle(" [DSDianjing] 打包后 App 启动即加载该地址，用户不可见/不可修改")
logger.lifecycle("========================================================")
if (!hasReleaseSigning) {
    logger.lifecycle(
        "Release signing is not configured; only debug APKs are signed. " +
            "Set ANDROID_KEYSTORE_PATH and the three signing secrets to sign releases."
    )
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.webkit:webkit:1.11.0")
}
