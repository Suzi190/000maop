# MpPhone

把自己打包的单文件网页应用（`index.html`，标题 `MpPhone`）变成**可安装的 Android APK**，
由 GitHub Actions 自动构建、自动发布，不需要在本地安装 Android Studio。

---

## 一、它是怎么工作的

```
仓库根目录 index.html            ← 唯一数据源，改这个文件就够了
        │
        │ 构建时由 Gradle 的 copyWebApp 任务复制
        ▼
android/app/src/main/assets/index.html
        │
        │ WebViewAssetLoader 以安全源加载
        ▼
https://appassets.androidplatform.net/assets/index.html
        │
        ▼
      MpPhone.apk
```

**为什么不用 `file://` 加载？**
`file://` 页面在 Android WebView 里属于「opaque origin」，
IndexedDB、`localStorage`、`CompressionStream` 等能力会被限制甚至直接用不了。
换成 `https://appassets.androidplatform.net`（由 `WebViewAssetLoader` 提供的虚拟域名，
数据仍然全部来自 APK 内部，完全离线）之后，网页应用和跑在服务器上时几乎无差别。

---

## 二、安装 APK

构建完成后（Actions 跑完大约 3~6 分钟）：

1. 打开仓库的 **Actions** 页面，点最新一次成功的构建，下载 `MpPhone-apk` 产物；**或者**
2. 直接打开 **Releases** 页面下载 `MpPhone.apk`；**或者**
3. 手机浏览器直接访问固定直链：

```
https://github.com/<你的用户名>/<仓库名>/releases/latest/download/MpPhone.apk
```

> 手机上首次安装需要允许「安装未知来源应用」。
> 覆盖安装时**用户数据不会丢**（见第六节「签名」）。

---

## 三、本地改动后怎么触发构建

只要推送到 `main` 分支、并且改动落在以下任一位置，就会自动重新构建：

- `index.html`
- `android/**`
- `.github/workflows/build-apk.yml`

也可以在 Actions 页面点 **Run workflow** 手动触发（`workflow_dispatch`）。

---

## 四、已实现的定制

| 需求 | 实现 |
| --- | --- |
| 沉浸式全屏，没有灰色状态栏 | `WindowInsetsControllerCompat.hide(systemBars())` + 透明状态栏/导航栏 + `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`（从边缘上滑可临时唤出）+ `LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES`（刘海屏铺满）+ viewport 加 `viewport-fit=cover` |
| 导出数据可用 | 见第五节，接管 `blob:` 下载并写入系统「下载」目录 |
| 导入数据可用 | 原生实现 `onShowFileChooser`（`WebChromeClient`），`<input type="file">` 能正常唤起系统文件选择器 |
| 键盘不遮挡输入框 | `android:windowSoftInputMode="adjustResize"` |
| 返回键 | 网页有历史就 `goBack()`，否则退出应用 |
| 扫一扫 | 声明 `CAMERA` 权限；`onPermissionRequest` 收到 `VIDEO_CAPTURE` 时先申请系统权限，用户同意后再 `grant()`（WebView 不会代你申请系统权限，直接 `grant()` 会静默失败） |
| 访问在线接口 | `INTERNET` 权限；`usesCleartextTraffic=true` 且 `MIXED_CONTENT_ALWAYS_ALLOW`，自建 http 中转 / `localhost:8000` MCP 也能用 |
| 外链 | 站内链接留在 WebView，`tel:` / `mailto:` / 其它 http(s) 外链交给系统浏览器 |

---

## 五、导出 / 导入是怎么修好的

### 问题

网页版导出用的是「触屏设备 → `navigator.share({files})`，否则 `<a download>` + `blob:` URL」。
这两条路在 Android WebView 里**都不生效**：

- WebView 没有实现 Web Share API；
- WebView 不会为 `blob:` URL 触发 `DownloadListener`，所以 `<a download>.click()` 是一个**静默空操作**。

### 解决

在 `index.html` 的 `head` 里加了一小段**垫片脚本**（约 110 行，见 `index.html` 第 134 行起）：

1. 包装 `URL.createObjectURL`，把 `blob URL → Blob` 记下来
   （应用 100ms 后就会 `revokeObjectURL`，不提前记就取不到数据了）；
2. 包装 `HTMLAnchorElement.prototype.click`，遇到 `download` 属性 + `blob:` URL 时，
   把 Blob 按 512 KB 分片读成 base64，依次调用原生桥
   `NativeBridge.beginSave / appendChunk / endSave`；
3. 让 `navigator.canShare` 返回 `false`，保证导出逻辑一定走到上面这条被接管的路径。

原生侧（`NativeBridge.java` → `FileExporter.java`）：

- **Android 10+**：用 `MediaStore` 静默写入系统**「下载」**目录（`IS_PENDING` 两阶段写入，
  写完才可见，不会出现半截文件），**不需要任何存储权限**；
- **Android 8 / 9**：这些版本没有免权限的公共目录写入能力，自动降级为系统「另存为」对话框（SAF），同样零权限。

导出完成会弹一个 Toast：`已保存到「下载」：xxx.json.gz`。

> ⚠️ 垫片脚本开头就检查 `window.NativeBridge` 是否存在。
> 不存在（普通浏览器、iOS Safari、微信内置浏览器、直接双击打开 HTML）时**直接 return**，
> 不注册任何行为，网页版导出逻辑完全不受影响。

### 导入

网页里的 `<input type="file">` 由 `MainActivity` 的 `onShowFileChooser` 处理：
用 `FileChooserParams.createIntent()` + `CATEGORY_OPENABLE` 唤起系统文件选择器，
结果用 `parseResult()` 回填 —— 选择 `.json` / `.json.gz` 后可正常解析。

---

## 六、签名（很重要）

`android/app/build.gradle` 里 release 构建复用了 **debug 签名**：

```groovy
buildTypes {
    release {
        signingConfig signingConfigs.debug
    }
}
```

CI 里用 `actions/cache` 缓存了 `~/.android/debug.keystore`：

```yaml
- uses: actions/cache@v6
  with:
    path: ~/.android/debug.keystore
    key: maop-debug-keystore-v1
```

命中缓存就直接复用；未命中才用 `keytool` 现生成一个并为本次运行保存。

**这样做的意义**：Android 只在「签名一致」时才允许覆盖安装。
如果每次构建都用一个新的随机 debug keystore，用户就得先卸载旧版才能装新版，**本地数据会一起被清掉**。
缓存之后签名稳定，可以直接覆盖升级，IndexedDB / localStorage 里的数据都保留。

> ⚠️ **这个机制有两个已知缺口**：
>
> 1. GitHub 的缓存**超过 7 天没有被访问就会被自动清理**；
> 2. `actions/cache` 的保存步骤是 `post-if: success()` —— **构建失败的那一次不会保存缓存**。
>
> 缓存一旦丢失，下一次构建就会生成新的 key，那个 APK **无法覆盖安装**，
> 必须卸载旧版才能装，**本地 IndexedDB / localStorage 数据会一起消失**。
>
> 因此：**每次升级前，先用应用内的「导出」存一份数据**。想彻底根治，见下面的「换成自己的固定签名」。

如果想换成自己的签名，在 `android/app/build.gradle` 里加：

```groovy
signingConfigs {
    release {
        storeFile file('../keystore/release.jks')
        storePassword System.getenv('KEYSTORE_PASSWORD')
        keyAlias System.getenv('KEY_ALIAS')
        keyPassword System.getenv('KEY_PASSWORD')
    }
}
```

（记得在 `buildTypes.release` 里把 `signingConfig signingConfigs.debug` 换成 `signingConfigs.release`。）

具体做法：

```bash
# 1) 本地生成一次，务必长期保管好 —— 丢了就再也无法给同一应用签名升级
keytool -genkeypair -v -keystore release.jks \
  -alias mpphone -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=MpPhone, O=MpPhone, C=CN"

# 2) 转成 base64 文本（Windows PowerShell，会复制到剪贴板）
[Convert]::ToBase64String([IO.File]::ReadAllBytes("release.jks")) | Set-Clipboard
```

3) 到仓库 **Settings → Secrets and variables → Actions** 新建 secret
   （建议 `KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`）；
4) 改 `build-apk.yml`，在构建前把 `KEYSTORE_BASE64` 解码还原成 `android/keystore/release.jks`。

> 第 4 步目前**还没有做** —— 现在的 workflow 只有「缓存 debug 签名」这一条路。
> 在你完成上面改造之前，请把「导出备份」当作升级前的固定动作。

---

## 七、换应用名 / 换包名 / 换图标

| 想改什么 | 改哪里 |
| --- | --- |
| 桌面上显示的名字 | `android/app/src/main/res/values/strings.xml` 里的 `app_name` |
| 包名 | `android/app/build.gradle` 的 `namespace` + `applicationId`，以及 `java/com/mpphone/app/` 目录名 |
| 图标 | `android/app/src/main/res/drawable/ic_launcher_foreground.xml`（纯矢量，猫爪）与 `values/colors.xml` 里的 `ic_launcher_background` |
| 版本号 | `android/app/build.gradle` 的 `versionCode` / `versionName` |

---

## 八、目录结构

```
.
├── index.html                                  # 单文件网页应用（唯一数据源）
├── .github/workflows/build-apk.yml             # CI：构建 + 发布 APK
├── .gitignore
├── README.md
└── android/
    ├── settings.gradle
    ├── build.gradle                            # AGP 8.5.2
    ├── gradle.properties
    └── app/
        ├── build.gradle                        # compileSdk 34 / minSdk 26 / Java 17
        └── src/main/
            ├── AndroidManifest.xml
            ├── java/com/mpphone/app/
            │   ├── MainActivity.java           # WebView 配置、沉浸式、文件选择、返回键
            │   ├── NativeBridge.java           # JS ⇄ 原生 的分片导出桥
            │   └── FileExporter.java           # MediaStore / SAF 落地
            └── res/
                ├── layout/activity_main.xml
                ├── values/{strings,colors,themes}.xml
                ├── drawable/ic_launcher_foreground.xml
                └── mipmap-anydpi-v26/ic_launcher{,_round}.xml
```

> `android/app/src/main/assets/index.html` 是构建时自动生成的副本，已在 `.gitignore` 里忽略，**不要手工改它**。

---

## 九、常见问题

### 1. AI 接口报 CORS / 请求失败

页面源从 `https://你的域名`（或 `file://`）变成了 `https://appassets.androidplatform.net`。
如果中转服务只把「你原来的网页域名」写进了白名单，就会被 CORS 拦掉；
用通配符 `Access-Control-Allow-Origin: *` 的服务（OpenAI / Anthropic / Gemini 官方接口）不受影响。

排查方法：手机连电脑，电脑 Chrome 打开 `chrome://inspect`，就能看到 WebView 的 Console 和 Network。
（调试开关在 `MainActivity.setupWebView()` 里，`WebView.setWebContentsDebuggingEnabled(true)`。）

### 2. 全屏后底部 UI 被手势条挡住

`index.html` 里用了多处 `env(safe-area-inset-bottom)`，配合 `viewport-fit=cover` 会自动留出安全距离。
如果某个页面还是贴到了最底下，可以把 `MainActivity.applyImmersiveMode()` 改成只隐藏状态栏：

```java
controller.hide(WindowInsetsCompat.Type.statusBars());
```

### 3. 构建失败 / 想看日志

Actions → 对应 run → 「构建 APK」这一步的日志。
`gradle assembleRelease` 带了 `--stacktrace`，错误信息很直白。

如果卡在下载依赖，多半是网络问题，重新跑一次即可（`gradle/actions/setup-gradle` 会缓存）。

### 4. Release 步骤报 `Resource not accessible by integration`

仓库没给 Actions 写权限。到
**Settings → Actions → General → Workflow permissions** 选 **Read and write permissions** 保存即可。
这一步已经设了 `continue-on-error: true`，即使失败也**不影响 APK 产物**（在 Actions 页面的 Artifacts 里下载）。

### 5. 想临时改网页内容，但不想等 CI

改完 `index.html` 推送 `main` 即可，CI 大约 3~6 分钟出包。
本地想先验证网页版：任何静态服务器（`npx serve .`、VS Code Live Server）打开都能跑，
只是没有原生导出桥，导出会回退成网页版原有行为。
