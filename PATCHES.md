# 补丁说明（网页文件被重新导出后需要重打）

## 为什么需要这份文档

本仓库**没有** `package.json` / `vite.config.ts` / `src/`。
根目录的 `index.html` 是那个 vite 单文件工程的**构建产物**，被直接提交进了 git；
CI（`.github/workflows/build-apk.yml`）只执行 `gradle assembleRelease`，
再通过 `android/app/build.gradle` 里的 `copyWebApp` 任务把它拷进 `assets/`，**从不重新构建它**。

所以：**只要你不从外部 Web 工程重新导出一份 `index.html` 覆盖过来，这些补丁就一直有效。**
但一旦覆盖，下面两个补丁会被静默冲掉 —— 症状是「APK 又变卡、升级后首次启动白屏时间翻倍」。
（原生侧改动都在 `android/` 里，不受影响，无需重打。）

---

## 补丁 1：Android WebView 启用重 GPU 效果降级

**位置**：`index.html` 中，`iOS 26 Safari 检测` 的 IIFE 之后、`缓存破坏机制` 之前。

**作用**：打包进 APK 时（`window.NativeBridge` 存在）给 `<html>` / `<body>` 加 `safari-ios26-fix` 类，
复用那份 CSS 里**已经存在**的 13 条降级规则：把 `backdrop-blur` 从 24/40/64px 压到 6px、
清掉 `will-change` / `translateZ`、给滚动容器加 `contain: layout style`、停掉 float/pulse 无限动画。
这些规则本来就是为「显存爆满 → 卡死/白屏」写的，与浏览器引擎无关，安卓 WebView 同样受益。

**注意**：必须同步执行、且在 React 挂载之前，否则首帧仍会按原效果渲染一次。

```html
      // ===================================================================
      // Android APK（原生壳内）重 GPU 效果降级：刻意复用上面那套规则
      // ===================================================================
      (function() {
        if (!window.NativeBridge) return;
        document.documentElement.classList.add('safari-ios26-fix');
        if (document.body) {
          document.body.classList.add('safari-ios26-fix');
        } else {
          document.addEventListener('DOMContentLoaded', function() {
            document.body.classList.add('safari-ios26-fix');
          });
        }
      })();
```

---

## 补丁 2：APK 内跳过 `app_version` 强制整页重载

**位置**：`index.html` 的「缓存破坏机制」IIFE 内，`APP_VERSION` 比较分支里。

**作用**：该机制是为 iOS Safari 无视 `<meta>` 缓存头写的。在 APK 里文件随安装包一起更新，
不存在缓存问题；而每次重新打包 `APP_VERSION` 都会变，这一下 `window.location.replace()`
会让「升级后首次启动」白白多一次整页重载。

**改法**：把重载三行包进 `if (!window.NativeBridge) { ... }`。

```js
            if (!window.NativeBridge) {
              var url = new URL(window.location.href);
              url.searchParams.set('_v', Date.now().toString());
              window.location.replace(url.toString());
              return; // 阻止后续脚本执行
            }
```

---

## 自检方法

重新覆盖 `index.html` 后，在本仓库根目录执行：

```powershell
Select-String -Path index.html -Pattern 'safari-ios26-fix' | Select-Object -First 5
Select-String -Path index.html -Pattern 'if \(!window\.NativeBridge\)' | Select-Object -First 5
```

补丁 1 应能搜到 10 处 `safari-ios26-fix`（含 CSS 里原有规则），补丁 2 应能搜到 5 处 `window.NativeBridge`
（两处补丁各 1 处，其余是页面里定义桥接的部分）。结果明显变少或为空，
即表示补丁已被覆盖，需要重打。
