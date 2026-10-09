package com.mpphone.app;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 把导出的临时文件真正落地成用户可见的文件。
 *
 * <p>Android 10（API 29）及以上：使用 MediaStore 静默写入系统「下载」目录，
 * 不需要任何存储权限，导出后文件管理器里立刻能看到。</p>
 *
 * <p>Android 8 / 9（API 26~28）：这些版本没有免权限的公共目录写入能力，
 * 退化为系统「另存为」对话框（SAF），同样不需要存储权限。</p>
 */
public class FileExporter {

    private static final int REQ_CREATE_DOCUMENT = 2001;

    private File pendingFile;
    private String pendingName;

    /** 主线程弹提示 */
    void notifyMessage(final Activity activity, final String message) {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
            } catch (Exception ignored) {
            }
        });
    }

    /**
     * 提交导出文件。注意：本方法可能在 JS 桥接线程被调用，内部会自行切换线程。
     */
    void deliver(final Activity activity, final File file, final String name, final String mime) {
        if (file == null || !file.exists()) {
            notifyMessage(activity, activity.getString(R.string.export_failed));
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 已在后台线程，直接落盘
            final String message = saveToDownloads(activity, file, name, mime);
            notifyMessage(activity, message);
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            return;
        }

        // Android 8 / 9：唤起系统「另存为」
        new Handler(Looper.getMainLooper()).post(() -> {
            pendingFile = file;
            pendingName = name;
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType((mime == null || mime.trim().isEmpty()) ? "*/*" : mime);
            intent.putExtra(Intent.EXTRA_TITLE, name);
            try {
                activity.startActivityForResult(intent, REQ_CREATE_DOCUMENT);
            } catch (Exception e) {
                pendingFile = null;
                pendingName = null;
                notifyMessage(activity, activity.getString(R.string.export_failed));
            }
        });
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private String saveToDownloads(Activity activity, File file, String name, String mime) {
        ContentResolver resolver = activity.getContentResolver();
        Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;

        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);

        Uri uri = null;
        try {
            uri = resolver.insert(collection, values);
            if (uri == null) {
                return activity.getString(R.string.export_failed);
            }
            try (InputStream in = new FileInputStream(file);
                 OutputStream out = resolver.openOutputStream(uri)) {
                if (out == null) {
                    throw new IOException("openOutputStream 返回 null");
                }
                copy(in, out);
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(uri, done, null, null);
            return activity.getString(R.string.export_saved, name);
        } catch (Exception e) {
            if (uri != null) {
                try {
                    resolver.delete(uri, null, null);
                } catch (Exception ignored) {
                }
            }
            return activity.getString(R.string.export_failed);
        }
    }

    /**
     * @return true 表示本次结果已被本类消费（调用方不要再处理）
     */
    boolean onActivityResult(Activity activity, int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode != REQ_CREATE_DOCUMENT) {
            return false;
        }

        File file = pendingFile;
        String name = pendingName;
        pendingFile = null;
        pendingName = null;

        if (file == null) {
            return true;
        }

        if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
            Uri dest = data.getData();
            try (InputStream in = new FileInputStream(file);
                 OutputStream out = activity.getContentResolver().openOutputStream(dest, "wt")) {
                if (out == null) {
                    throw new IOException("openOutputStream 返回 null");
                }
                copy(in, out);
                notifyMessage(activity, activity.getString(R.string.export_saved_other, name));
            } catch (Exception e) {
                notifyMessage(activity, activity.getString(R.string.export_failed));
            }
        } else {
            notifyMessage(activity, activity.getString(R.string.export_cancelled));
        }

        //noinspection ResultOfMethodCallIgnored
        file.delete();
        return true;
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
        out.flush();
    }
}
