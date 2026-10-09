package com.mpphone.app;

import android.app.Activity;
import android.util.Base64;
import android.webkit.JavascriptInterface;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * WebView 与原生之间的导出桥。
 *
 * <p>网页里的导出垫片会把 Blob 分片转成 base64 依次调用
 * {@link #beginSave} / {@link #appendChunk} / {@link #endSave}，
 * 原生这边先写进 cache 临时文件，最后交给 {@link FileExporter} 落地。</p>
 *
 * <p>之所以要分片：备份文件可能有几十 MB，一次性跨桥传 base64 容易 OOM。</p>
 */
public class NativeBridge {

    private final Activity activity;
    private final FileExporter exporter = new FileExporter();

    private OutputStream out;
    private File tempFile;
    private String displayName = "download.bin";
    private String mimeType = "application/octet-stream";

    NativeBridge(Activity activity) {
        this.activity = activity;
    }

    /** 开始一次导出，重置状态并打开临时文件 */
    @JavascriptInterface
    public synchronized void beginSave(String name, String mime) {
        closeQuietly();
        try {
            displayName = sanitize(name);
            String cleaned = (mime == null) ? "" : mime.trim();
            mimeType = cleaned.isEmpty() ? guessMime(displayName) : cleaned;
            tempFile = new File(activity.getCacheDir(), "maop_export.tmp");
            if (tempFile.exists() && !tempFile.delete()) {
                // 删不掉就改名另存，避免追加到旧内容后面
                tempFile = new File(activity.getCacheDir(), "maop_export_" + System.currentTimeMillis() + ".tmp");
            }
            out = new BufferedOutputStream(new FileOutputStream(tempFile), 128 * 1024);
        } catch (Exception e) {
            out = null;
            tempFile = null;
        }
    }

    /** 追加一段 base64（来自 FileReader.readAsDataURL 的逗号之后部分） */
    @JavascriptInterface
    public synchronized void appendChunk(String base64) {
        if (out == null || base64 == null || base64.isEmpty()) {
            return;
        }
        try {
            out.write(Base64.decode(base64, Base64.DEFAULT));
        } catch (Exception e) {
            closeQuietly();
        }
    }

    /** 结束并落地 */
    @JavascriptInterface
    public synchronized void endSave() {
        closeQuietly();

        final File file = tempFile;
        final String name = displayName;
        final String mime = mimeType;
        tempFile = null;

        if (file == null || !file.exists()) {
            exporter.notifyMessage(activity, activity.getString(R.string.export_failed));
            return;
        }
        exporter.deliver(activity, file, name, mime);
    }

    /** 出错时放弃本次导出 */
    @JavascriptInterface
    public synchronized void abortSave(String reason) {
        closeQuietly();
        if (tempFile != null) {
            //noinspection ResultOfMethodCallIgnored
            tempFile.delete();
        }
        tempFile = null;
    }

    private void closeQuietly() {
        if (out != null) {
            try {
                out.flush();
            } catch (Exception ignored) {
            }
            try {
                out.close();
            } catch (Exception ignored) {
            }
            out = null;
        }
    }

    private static String sanitize(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "download.bin";
        }
        // 去掉路径分隔符与非法字符，防止写到奇怪的位置
        String cleaned = name.replace('\\', '_').replace('/', '_').trim();
        cleaned = cleaned.replaceAll("[\\p{Cntrl}]", "");
        if (cleaned.isEmpty()) {
            cleaned = "download.bin";
        }
        return cleaned;
    }

    private static String guessMime(String name) {
        String lower = name == null ? "" : name.toLowerCase();
        if (lower.endsWith(".json.gz") || lower.endsWith(".gz")) {
            return "application/gzip";
        }
        if (lower.endsWith(".json")) {
            return "application/json";
        }
        if (lower.endsWith(".txt")) {
            return "text/plain";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        return "application/octet-stream";
    }
}
