# DS电竞 · 三角洲护航 Android 客户端

这是一个**独立的 Android 原生客户端仓库**，用 Kotlin + WebView 壳加载你自己部署的「DS电竞 · 三角洲护航」服务端站点，并补齐原生级体验：

- **内置锁定服务器地址**：在编译期通过 `DS_SERVER_URL`（或 `app/build.gradle.kts` 默认值）固化进 APK，对最终用户**完全不可见、不可修改**，打开 App 直达业务首页
- 品牌启动页、顶部细进度条、下拉刷新、原生离线错误页（仅提供重新加载，不泄露服务器地址）
- 返回键优先关闭网页弹层/后退，否则双击退出
- edge-to-edge + 刘海/手势条安全区适配
- 文件选择、下载交给系统 `DownloadManager`、外链/`tel:`/`mailto:`/`weixin://` 交给系统
- 极简 JS Bridge：`window.DSNative`（仅提供版本号、Toast 与退出能力）
- WebView localStorage / Cookie 持久化，重启 App 保持登录
- 完整 GitHub Actions：自动出 debug APK；配置签名后自动出**可安装的已签名 release APK**；打 `v*` tag 自动建 Release

前端 H5 代码和 `server.py` **不在这个仓库里**。客户端只加载服务器地址上的站点，不会把网页打包进 APK。

---

## 技术栈与版本

| 项目 | 值 |
| --- | --- |
| 语言 | Kotlin 1.9.24 |
| 构建脚本 | Gradle Kotlin DSL（`build.gradle.kts`） |
| Android Gradle Plugin | 8.5.2（要求 Gradle 8.7 / JDK 17） |
| compileSdk / targetSdk | 34 |
| minSdk | 24（Android 7.0） |
| applicationId / namespace | `com.ds.dianjing` |
| UI | Android Views + Material Components（**不使用 Compose**） |
| 单模块 | `app` |

---

## 目录结构

```text
.
├── .github/
│   └── workflows/
│       └── build-apk.yml              # GitHub Actions 自动打包/签名/发布
├── app/
│   ├── build.gradle.kts               # 模块配置、默认服务器地址、条件签名
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml        # INTERNET 权限、明文 HTTP、启动 Activity
│       ├── java/com/ds/dianjing/
│       │   ├── MainActivity.kt        # WebView 壳、内置地址加载、进度、离线页、下载、返回键、Insets
│       │   ├── util/
│       │   │   └── UrlUtils.kt        # 同站域名判断
│       │   └── web/
│       │       └── WebAppBridge.kt    # @JavascriptInterface + window.DSNative 门面
│       └── res/
│           ├── drawable/              # 矢量品牌 logo、启动背景
│           ├── layout/                # 主界面（WebView + 进度条 + 离线页）
│           ├── values/                # strings / colors / themes / dimens
│           └── xml/
│               └── network_security_config.xml  # 允许内网明文 HTTP
├── build.gradle.kts                   # 插件版本
├── settings.gradle.kts                # 仓库、模块声明
├── gradle.properties                  # AndroidX / JVM 参数
├── .gitignore
└── README.md
```

### 为什么没有 `gradlew` / `gradle-wrapper.jar`？

按项目约定，本仓库**不提交任何二进制文件**（`gradle-wrapper.jar`、`.jks`、`.apk` 都不放）。CI 使用 `gradle/actions/setup-gradle` 安装的 Gradle 8.7，直接执行 `gradle` 命令。  
如果你本地想用 wrapper，可以自己生成（生成的 jar 默认已被 `.gitignore` 忽略）：

```powershell
Set-Location "F:\DS电竞小程序\android"
gradle wrapper --gradle-version 8.7
.\gradlew.bat :app:assembleDebug
```

---

## 一、把本目录推送到新建 GitHub 仓库

> 注意：**只把 `android` 目录作为仓库根目录推送**。不要 `git add` 父目录，否则会把 `server.py`、前端资源等一起带进去。

```powershell
Set-Location "F:\DS电竞小程序\android"

git init
git add .
git commit -m "feat: Android WebView client and APK workflow"
git branch -M main

# 在 GitHub 新建一个空仓库后，把下面的地址换成你的仓库地址
git remote add origin https://github.com/<你的账号>/<仓库名>.git
git push -u origin main
```

如果仓库已存在 remote，用 `git remote set-url origin <地址>` 替换即可。  
推完后打开仓库的 **Actions** 页，工作流会自动运行，并在运行记录的 **Artifacts** 里提供 `ds-dianjing-apk`。

---

## 二、GitHub Actions 配置

工作流文件：`.github/workflows/build-apk.yml`

### 触发方式

- `push` 到 `main` / `master`
- 任意 `pull_request`
- 手动 `workflow_dispatch`
- 推送 `v*` 标签（例如 `v1.0.0`）

### 产物逻辑

1. 永远构建 **debug APK**（不需要任何 secrets，首次运行也能成功）。
2. 如果下面 4 个签名 secret **全部存在**，额外构建 **已签名 release APK**。
3. 缺少任意一个签名 secret：不构建 release，**工作流不会失败**，只上传 debug。
4. 推送 `v*` tag 时，用 `softprops/action-gh-release@v2` 创建 GitHub Release，并把本次所有 APK 挂上去。
5. `actions/upload-artifact@v4` 上传 `**/*.apk`，artifact 名：`ds-dianjing-apk`。
6. `gradle/actions/setup-gradle@v4` 自带 Gradle 依赖缓存；工作流 `permissions: contents: write` 用于创建 Release。

### 必须配置的 Secrets（在仓库 Settings → Secrets and variables → Actions → Secrets）

| Secret | 说明 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | `.jks` / `.keystore` 文件的 Base64 单行字符串 |
| `ANDROID_KEYSTORE_PASSWORD` | keystore 密码 |
| `ANDROID_KEY_ALIAS` | key alias |
| `ANDROID_KEY_PASSWORD` | key 密码 |

四个都配置后，CI 会把 `ANDROID_KEYSTORE_BASE64` 解码到 `$RUNNER_TEMP/keystore.jks`，再通过环境变量传给 Gradle；Gradle 只有在 keystore 文件存在且密码齐全时才创建 `signingConfigs.release`。

### 必须配置的 Variables（Settings → Secrets and variables → Actions → Variables）

| Variable | 说明 |
| --- | --- |
| `DS_SERVER_URL` | **内置进 APK 的服务器地址**，例如 `https://ds.example.com` 或 `http://192.168.1.10:8080` |

注意：

- 这个地址会**编译进 APK（`BuildConfig.SERVER_URL`）**，App 内没有任何修改入口，用户看不到也改不了。
- 不配置时会回退到 [`app/build.gradle.kts`](app/build.gradle.kts) 里的 `defaultFallbackServerUrl`（默认 `http://192.168.1.3:8080`，适合本机联调）。
- 工作流用 `-PserverUrl="${{ vars.DS_SERVER_URL }}"` 传入；手动触发时也可以直接用 `server_url` 输入框覆盖。
- **换服务器地址 = 重新打一次 APK**，用户无需任何操作。

### 生成签名 keystore（本地执行，不要提交）

```powershell
keytool -genkeypair -v `
  -keystore "F:\keys\ds-release.jks" `
  -alias ds `
  -keyalg RSA `
  -keysize 2048 `
  -validity 10000
```

生成后把 keystore 转成 Base64 并复制到剪贴板：

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("F:\keys\ds-release.jks")) | Set-Clipboard
```

把剪贴板内容粘贴到 GitHub Secret `ANDROID_KEYSTORE_BASE64`。  
`ANDROID_KEY_ALIAS` 填上面的 `ds`，两个密码填生成时输入的密码。

> 务必把 `ds-release.jks` 和密码备份好。正式发布的后续版本必须使用**同一个签名**，否则用户无法覆盖安装。

### 打 tag 发布 Release

```powershell
git tag v1.0.0
git push origin v1.0.0
```

Actions 会再次构建，并自动创建 `v1.0.0` Release，附上 debug APK；如果签名 secrets 齐全，同时附上已签名 release APK。  
**正式分发请使用已签名的 release APK；未签名 release 不能安装。**

---

## 三、本地构建

### 环境要求

- JDK 17
- Android SDK Platform 34 / Build-Tools（安装 Android Studio 后在 SDK Manager 中安装）
- Gradle 8.7（或自己生成 wrapper）
- 设置 SDK 路径：在仓库根目录创建 `local.properties`（该文件已被忽略）：

```properties
sdk.dir=C\:\\Users\\你的用户名\\AppData\\Local\\Android\\Sdk
```

### 构建 debug

```powershell
Set-Location "F:\DS电竞小程序\android"
gradle :app:assembleDebug
```

APK 输出：`app\build\outputs\apk\debug\app-debug.apk`

### 构建 release（本地签名）

```powershell
$env:ANDROID_KEYSTORE_PATH = "F:\keys\ds-release.jks"
$env:ANDROID_KEYSTORE_PASSWORD = "你的keystore密码"
$env:ANDROID_KEY_ALIAS = "ds"
$env:ANDROID_KEY_PASSWORD = "你的key密码"

gradle :app:assembleRelease
```

也可以用 Gradle 项目属性：

```powershell
gradle :app:assembleRelease `
  -PandroidKeystorePath="F:\keys\ds-release.jks" `
  -PandroidKeystorePassword="..." `
  -PandroidKeyAlias="ds" `
  -PandroidKeyPassword="..."
```

未配置签名时 `assembleRelease` 只会生成未签名 APK，不能安装；CI 不会在缺少 secrets 时构建 release。

### 配置内置服务器地址（本地构建）

优先级：`-PserverUrl` → 环境变量 `DS_SERVER_URL` → `build.gradle.kts` 里的 `defaultFallbackServerUrl`。

```powershell
gradle :app:assembleDebug -PserverUrl="https://ds.example.com"
```

或：

```powershell
$env:DS_SERVER_URL = "http://192.168.1.10:8080"
gradle :app:assembleDebug
```

这个值被编译进 `BuildConfig.SERVER_URL`，App 启动时直接加载，**运行期不可见也不可修改**。
如果没写协议（`http://`/`https://`），构建脚本会自动补上 `https://`。

---

## 四、客户端行为说明

### 服务器地址（编译期固定）

- 地址来自 `BuildConfig.SERVER_URL`，构建时确定，运行期无法修改。
- App 内**没有**配置界面、没有设置齿轮、没有「切换服务器」入口。
- 网络异常时只显示「网络连接异常 + 重新加载」，不暴露任何服务器信息。
- 需要换地址 → 修改仓库变量 `DS_SERVER_URL` 后重新打包。

### 登录态持久化

- WebView 开启 `domStorageEnabled`，所以 localStorage（token）会保留。
- Cookie、IndexedDB 同样由 WebView 数据目录持久化。
- 清除 App 数据或卸载重装会退出登录，这是正常现象。

### 错误页

网络断开、DNS 失败、连接超时、TLS 校验失败、HTTP 5xx 等主文档错误会显示**原生错误页**，提供「重试」和「修改服务器地址」，不会停留在 WebView 默认错误页。  
TLS 证书错误**不会**被忽略或绕过。

### 外链、下载与特殊协议

- 同站 `http/https` 链接在 WebView 内打开。
- 非本站域名用系统浏览器（`Intent.ACTION_VIEW`）打开。
- `tel:`、`mailto:`、`weixin://`、`alipay://`、`market://`、`intent://` 等交给系统对应 App。
- `Content-Disposition` 下载或 WebView 无法处理的下载交给 `DownloadManager`，文件保存在 App 专属外部目录（`Android/data/com.ds.dianjing/files/Download`），无需存储权限。可从系统下载通知打开。
- `target="_blank"` / `window.open` 通过 `onCreateWindow` 处理：同站地址回主 WebView，外站交给系统。

### JS Bridge

原生接口对象注册为 `window.__DSNativeBridge`，页面加载完成后注入 `window.DSNative`：

```javascript
window.DSNative.version              // "1.0.0"
window.DSNative.getVersion()
window.DSNative.getServerUrl()
window.DSNative.openSettings()
window.DSNative.toast("提示")
window.DSNative.exitApp()
```

安全说明：只暴露了上述几个方法，没有文件操作、反射或任意 Intent 能力；WebView 禁止访问本地文件，并且外站页面不会在主 WebView 中加载。仍然建议只加载你自己信任的 H5 站点，不要把它当成通用浏览器使用。

### Android 9+ 明文 HTTP

自托管内网服务器经常是 `http://`，因此：

- Manifest 设置 `android:usesCleartextTraffic="true"`；
- `res/xml/network_security_config.xml` 允许明文流量。

**生产环境建议使用 HTTPS**（配合有效证书）。如果服务器只有 HTTP，请只在受信任的内网使用，并注意个人信息和登录 token 的传输安全。

### 权限

只申请：

- `android.permission.INTERNET`

没有存储、相机、定位、通知等无关权限。文件选择通过系统 SAF，不需要读取存储权限。下载使用 App 专属目录，也不需要存储权限。

---

## 五、常见问题（FAQ）

### 1. APK 安装失败 / 提示签名冲突

- 如果手机上已有同 `applicationId`（`com.ds.dianjing`）但不同签名的版本，先卸载旧版再安装。
- debug 和 release 使用不同签名，不能互相覆盖安装。
- 确认系统允许「安装未知来源应用」。
- 本项目 minSdk 24，Android 7.0 以下无法安装。

### 2. App 连不上服务器

按顺序检查：

1. 手机浏览器能否打开同一个地址？打不开说明是网络/服务问题，不是 App 问题。
2. 服务端是否监听 `0.0.0.0` 而不是仅 `127.0.0.1`。
3. 电脑防火墙是否放行端口。
4. 手机和服务器是否在同一局域网；手机里 **不能用 `localhost` 指代电脑**，请填电脑的局域网 IP，例如 `http://192.168.1.10:8080`。
5. 如果是 HTTPS 自签名证书，App 会因证书校验失败显示错误页。请安装受信任证书或改用 HTTP/HTTPS 正式证书。
6. 公网部署且用了域名，确认 DNS 已生效。

### 3. Android 9+ 提示 cleartext not permitted

本项目已经设置：

- `android:usesCleartextTraffic="true"`
- `network_security_config.xml` 允许明文 HTTP

如果仍然失败，确认你安装的是本项目新构建的 APK，而不是旧包；生产环境建议直接改用 HTTPS。

### 4. 没有生成 release APK

这是设计行为：只有 `ANDROID_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD` 四个 secret 都存在时，工作流才会执行 `assembleRelease`。缺任何一个都会跳过 release 且不会报错。  
到 Actions 运行日志里查看 `Check signing secrets` 步骤的 `enabled` 输出。

### 5. 打了 tag 但 Release 里的 APK 是 debug 的

原因通常是签名 secrets 未配置完整。debug APK 可以测试，但**不要作为正式发布包**分发。补齐 secrets 后重新打一个新的 `v*` tag（或手动重跑工作流）。

### 6. 下载的文件找不到

文件在 App 专属目录：

```text
Android/data/com.ds.dianjing/files/Download/
```

在 Android 11+ 上部分文件管理器不能直接浏览 `Android/data`，请从系统下载完成通知点开，或用电脑 USB 查看。若需要保存到公共下载目录，可在后续版本中增加「存储权限/MediaStore」逻辑，本项目按最小权限原则未申请。

### 7. 重启 App 后登录失效

- 本客户端开启了 localStorage 与 Cookie，正常情况下重启保留登录。
- 清除 App 数据、卸载重装、换服务器地址会导致登录态丢失。
- 服务端 token 过期或 Cookie 域变化也会导致重新登录。

### 8. 打开 App 显示「网络连接异常」

说明打包时内置的地址访问不到。按顺序检查：

1. 服务器是否在运行（浏览器能否打开该地址）
2. `DS_SERVER_URL` 是否写错（少写协议、端口错、多写路径）
3. 内网地址（`192.168.x.x`）只在同一 WiFi 下有效，出门测试请用公网/隧道地址
4. 用的是 `http://` 还是 `https://`，服务器实际支持哪种

### 9. 构建时提示缺少 SDK / `sdk.dir`

在 `local.properties` 配置本地 Android SDK 路径，或用 Android Studio 打开项目让它自动生成。CI 使用 GitHub 托管 runner 的 Android SDK，并会通过 `sdkmanager` 确保 API 34 / Build-Tools 34.0.0 已安装。

### 10. WebView 页面白屏 / 功能异常

- 在系统设置里更新「Android System WebView」或 Chrome。
- WebView 版本过旧会影响 JS、文件选择和某些 H5 能力。
- 不要在主 WebView 加载来路不明的外部页面；外链已交给系统浏览器。

---

## 六、安全与发布建议

- 正式分发前，务必配置签名 secrets 并使用已签名 release APK。
- 服务器建议启用 HTTPS，避免 token 在明文 HTTP 中传输。
- 保管好 keystore 和密码；丢失后将无法对已发布 App 做覆盖升级。
- 本客户端是 H5 的展示壳，真正的接口鉴权和业务安全仍由 `server.py` / 服务端负责。
- 不要把 `android/` 父目录中的 `server.py`、`data/`、前端资源一起推送到这个客户端仓库。
