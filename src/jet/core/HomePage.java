package jet.core;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

/**
 * 管理页 HTML 的加载器。
 *
 * 三级来源，按可用性依次回退：
 * 1. APK 内 assets/jethub/home.html（真机唯一路径）；
 * 2. 文件系统上的开发副本（host JVM 单测用，路径由系统属性 jethub.home 指定）；
 * 3. 极简兜底页（前两者都不可用时，至少让用户看到诊断信息）。
 *
 * 结果缓存 —— 管理页 82KB，不必每次请求都读盘。
 */
public final class HomePage {

    private static final String ASSET_PATH = "jethub/home.html";
    private static volatile String cached;

    private HomePage() { }

    /** 读取管理页 HTML，永不返回 null。 */
    public static String load() {
        String hit = cached;
        if (hit != null) return hit;
        synchronized (HomePage.class) {
            if (cached != null) return cached;
            String html = fromAssets();
            if (html == null) html = fromDevFile();
            if (html == null) html = fallback();
            cached = html;
            return html;
        }
    }

    /** 强制重读（调试用）。 */
    public static void invalidate() {
        cached = null;
    }

    private static String fromAssets() {
        Context ctx = AppContextHolder.context();
        if (ctx == null) return null;
        InputStream in = null;
        try {
            AssetManager am = ctx.getAssets();
            in = am.open(ASSET_PATH);
            return readAll(in);
        } catch (Throwable ignored) {
            return null;
        } finally {
            close(in);
        }
    }

    private static String fromDevFile() {
        String path = System.getProperty("jethub.home");
        if (path == null) return null;
        File f = new File(path);
        if (!f.isFile()) return null;
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            return readAll(in);
        } catch (Throwable ignored) {
            return null;
        } finally {
            close(in);
        }
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(96 * 1024);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return new String(bos.toByteArray(), "UTF-8");
    }

    private static void close(InputStream in) {
        if (in != null) {
            try { in.close(); } catch (Throwable ignored) { }
        }
    }

    /** 兜底页：只在资源缺失时出现，用于暴露问题而不是白屏。 */
    private static String fallback() {
        return "<!DOCTYPE html><html lang=zh><head><meta charset=utf-8>"
                + "<meta name=viewport content='width=device-width,initial-scale=1'>"
                + "<title>JetHub</title></head><body style='font-family:sans-serif;padding:24px'>"
                + "<h2>JetHub 内置网关</h2>"
                + "<p>管理页资源缺失（assets/" + ASSET_PATH + "）。</p>"
                + "<p>接口仍可用：<code>/jethub/status</code>、<code>/jethub/accounts</code>、"
                + "<code>/jethub/checkin</code></p>"
                + "</body></html>";
    }
}
