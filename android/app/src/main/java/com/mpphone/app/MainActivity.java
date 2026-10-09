package com.mpphone.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewClientCompat;

/**
 * 单 Activity 的 WebView 外壳。
 *
 * <p>关键点：页面不是用 file:// 加载，而是通过 {@link WebViewAssetLoader} 走
 * https://appassets.androidplatform.net/assets/index.html —— 这是一个安全源，
 * IndexedDB / localStorage / CompressionStream / crypto 等能力都能正常工作，
 * 同时因为资源来自本地，完全离线可用。</p>
 */
public class MainActivity extends AppCompatActivity {

    private static final String APP_HOST = "appassets.androidplatform.net";
    private static final String APP_URL = "https://" + APP_HOST + "/assets/index.html";

    private static final int REQ_FILE_CHOOSER = 1001;
    private static final int REQ_CAMERA_PERMISSION = 1002;

    private ViewGroup rootView;
    private WebView webView;
    private FileExporter fileExporter;
    private ValueCallback<Uri[]> pendingFileChooser;
    private PermissionRequest pendingPermissionRequest;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ── 沉浸式全屏的关键一步 ───────────────────────────────────────
        // 让 DecorView 不再把系统栏的 inset 吃成自己的 padding。
        // 只调 hide(systemBars()) 是不够的：窗口仍会按系统栏内缩布局，
        // 上方那条缝里露出的就是主题的 windowBackground —— 也就是「顶部白条」。
        // 必须在 setContentView 之前调用。
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        setContentView(R.layout.activity_main);

        rootView = findViewById(R.id.root);
        fileExporter = new FileExporter();

        // 切角模式 / 系统栏颜色，只在这里设一次
        applyEdgeToEdgeSetup();

        webView = findViewById(R.id.webview);
        setupWebView();

        // decor 在 onCreate 阶段尚未 attach，此时 hide() 会被 Android 11+ 忽略，
        // 于是启动瞬间能瞥到状态栏。等首帧之后再补一次。
        getWindow().getDecorView().post(this::hideSystemBars);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView != null && webView.canGoBack()) {
                    webView.goBack();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

        if (savedInstanceState == null) {
            webView.loadUrl(APP_URL);
        } else {
            webView.restoreState(savedInstanceState);
        }
    }

    // ────────────────────────────────────────────────────────────────
    // WebView 初始化
    // ────────────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(false);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setTextZoom(100);
        // 允许 https 页面访问 http 接口（自建中转 / localhost MCP 等）
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        // 提前栅格化屏幕外的内容，长列表滚动更跟手
        settings.setOffscreenPreRaster(true);

        // 用不透明的应用底色，而不是 TRANSPARENT：
        // 透明 WebView 会强制合成器与下层做混合，是首帧/重绘时「白闪」的来源。
        // 这个颜色取自页面 body 渐变的起点，加载期间几乎看不出反差。
        webView.setBackgroundColor(ContextCompat.getColor(this, R.color.window_background));
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setFitsSystemWindows(false);
        // 方便用 chrome://inspect 调试，不影响正常使用
        WebView.setWebContentsDebuggingEnabled(true);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        final WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .setDomain(APP_HOST)
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        webView.addJavascriptInterface(new NativeBridge(this), "NativeBridge");

        webView.setWebViewClient(new WebViewClientCompat() {

            @Nullable
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return openExternallyIfNeeded(request.getUrl());
            }

            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return openExternallyIfNeeded(Uri.parse(url));
            }

            /**
             * 渲染进程崩溃时 WebView 会永久留白，而且**不会自愈**，
             * 用户看到的就是「动不动白屏」。最常见的诱因是 GPU 显存被大量
             * backdrop-filter / will-change 图层耗尽而被系统杀掉。
             * 必须由宿主销毁这个实例并重建一个新的。
             *
             * @return true 表示事件已由宿主消费，WebView 不再自行处理。
             */
            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                recreateWebView();
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (pendingFileChooser != null) {
                    pendingFileChooser.onReceiveValue(null);
                }
                pendingFileChooser = callback;
                try {
                    Intent intent = params.createIntent();
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    startActivityForResult(
                            Intent.createChooser(intent, getString(R.string.choose_file)),
                            REQ_FILE_CHOOSER);
                    return true;
                } catch (Exception e) {
                    pendingFileChooser = null;
                    return false;
                }
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                // 扫一扫等场景需要摄像头。WebView 不会自己申请系统权限：
                // 应用没拿到 CAMERA 权限时 grant() 会静默失败，所以这里先补齐系统权限。
                for (String resource : request.getResources()) {
                    if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)
                            && ContextCompat.checkSelfPermission(MainActivity.this,
                                    Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                        pendingPermissionRequest = request;
                        ActivityCompat.requestPermissions(MainActivity.this,
                                new String[]{Manifest.permission.CAMERA}, REQ_CAMERA_PERMISSION);
                        return;
                    }
                }
                grantResources(request);
            }
        });
    }

    /**
     * 站内链接留在 WebView 内；blob / data 等内部协议同样保留；
     * 其余（tel:、mailto:、外链、微信等）交给系统处理。
     */
    private boolean openExternallyIfNeeded(Uri uri) {
        if (uri == null) {
            return false;
        }
        String scheme = uri.getScheme();
        if (scheme == null) {
            return false;
        }
        scheme = scheme.toLowerCase();

        if ("blob".equals(scheme) || "data".equals(scheme)
                || "about".equals(scheme) || "javascript".equals(scheme)) {
            return false;
        }
        if (("http".equals(scheme) || "https".equals(scheme)) && APP_HOST.equals(uri.getHost())) {
            return false;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception ignored) {
        }
        return true;
    }

    // ────────────────────────────────────────────────────────────────
    // 摄像头权限（扫一扫）
    // ────────────────────────────────────────────────────────────────

    private void grantResources(PermissionRequest request) {
        try {
            request.grant(request.getResources());
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        if (requestCode == REQ_CAMERA_PERMISSION) {
            PermissionRequest request = pendingPermissionRequest;
            pendingPermissionRequest = null;
            if (request == null) {
                return;
            }
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (granted) {
                grantResources(request);
            } else {
                try {
                    request.deny();
                } catch (Exception ignored) {
                }
            }
            return;
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    // ────────────────────────────────────────────────────────────────
    // 沉浸式全屏
    // ────────────────────────────────────────────────────────────────

    private void applyEdgeToEdgeSetup() {
        // 刘海屏：允许内容铺到挖孔区域。
        // SHORT_EDGES 已覆盖竖屏顶部 / 横屏两侧的挖孔，
        // 不需要用 ALWAYS 把内容进一步压到刘海下面去。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams attrs = getWindow().getAttributes();
            attrs.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(attrs);
        }

        // 系统栏完全透明。
        // 注意：Android 15 起这两个调用已是 no-op，全屏真正依赖的是
        // onCreate 里的 setDecorFitsSystemWindows(false) + 下面的 hideSystemBars()。
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
    }

    /**
     * 隐藏系统栏。幂等，可重复调用。
     */
    private void hideSystemBars() {
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.hide(WindowInsetsCompat.Type.systemBars());
        // 从屏幕边缘上滑时临时唤出系统栏，松手自动隐藏
        controller.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }

    /**
     * 渲染进程崩溃后重建 WebView。
     *
     * <p>崩溃过的 WebView 实例无法复用，必须先脱离视图树并 destroy，
     * 再新建一个挂回根布局，否则用户会一直盯着白屏。</p>
     */
    private void recreateWebView() {
        if (rootView == null) {
            return;
        }
        if (webView != null) {
            webView.removeJavascriptInterface("NativeBridge");
            rootView.removeView(webView);
            webView.destroy();
            webView = null;
        }

        webView = new WebView(this);
        webView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // 插到最底层，与 activity_main.xml 中的层级保持一致
        rootView.addView(webView, 0);
        setupWebView();
        webView.loadUrl(APP_URL);

        hideSystemBars();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            // 与 onPause() 里的 webView.onPause() 配对。
            // 少了这一句，从后台回来 WebView 会一直停留在暂停/节流状态，
            // 表现就是又卡又容易白屏。
            webView.onResume();
        }
        hideSystemBars();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            // 只做幂等的 hide，不再 setAttributes()：
            // 每次焦点变化都改窗口属性会触发一次 relayout，本身就在掉帧。
            hideSystemBars();
        }
    }

    // ────────────────────────────────────────────────────────────────
    // 结果回调 & 生命周期
    // ────────────────────────────────────────────────────────────────

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode == REQ_FILE_CHOOSER) {
            if (pendingFileChooser != null) {
                pendingFileChooser.onReceiveValue(
                        WebChromeClient.FileChooserParams.parseResult(resultCode, data));
                pendingFileChooser = null;
            }
            return;
        }
        if (fileExporter != null
                && fileExporter.onActivityResult(this, requestCode, resultCode, data)) {
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (webView != null) {
            webView.saveState(outState);
        }
    }

    @Override
    protected void onPause() {
        if (webView != null) {
            webView.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.removeJavascriptInterface("NativeBridge");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
