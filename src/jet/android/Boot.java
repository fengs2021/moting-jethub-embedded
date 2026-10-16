package jet.android;

import android.content.Context;

import java.io.File;

import jet.core.AccountManager;
import jet.core.AppContextHolder;
import jet.core.CredentialStore;
import jet.core.GatewayServer;

/**
 * Android 壳：从墨听 Application 的 onCreate（smali 注入一行）调用 {@link #init}。
 *
 * 职责：
 * 1. 启动 loopback 网关（127.0.0.1:8391）；
 * 2. 初始化 AppContextHolder（模型中心注册器要用）。
 *
 * 任何异常都不能让宿主崩溃 —— 全部吞掉并打 Log（同时落盘 boot.log 便于排查）。
 */
public final class Boot {

    private static volatile boolean started;
    private static volatile String lastError;
    private static volatile String trace;

    public static boolean isStarted() { return started; }

    public static String lastError() { return lastError; }

    public static String trace() { return trace; }

    public static void init(Context context) {
        if (started) return;
        synchronized (Boot.class) {
            if (started) return;
            try {
                File dir = new File(context.getFilesDir(), "jethub");
                if (!dir.exists()) dir.mkdirs();
                CredentialStore store = new CredentialStore.FileBased(new File(dir, "credentials.json"));
                AppContextHolder.init(context, store);
                // 多账号：Provider 全部建立在「活跃账号视图」上，切换账号无需重启网关
                AccountManager accounts = new AccountManager(store);
                AppContextHolder.initAccounts(accounts);
                // OpenCode 匿名通道（Bearer public）已被服务端封死（2026-10-16 实测），
                // 故不再播种默认账号；请到管理页填入真实 API Key。
                GatewayServer gw = new GatewayServer(accounts);
                gw.start();
                started = true;
                lastError = null;
                trace = "ok";
                android.util.Log.i("JetHub", "内嵌网关已启动 127.0.0.1:" + GatewayServer.port());
            } catch (Throwable t) {
                started = false;
                lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
                trace = stackOf(t);
                android.util.Log.w("JetHub", "内嵌网关启动失败", t);
            }
            try { writeLog(context); } catch (Throwable ignored) { }
        }
    }

    /** Activity 等非 Application 入口的幂等启动。 */
    public static void ensureStarted(Context context) {
        init(context);
    }

    private static String stackOf(Throwable t) {
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }

    private static void writeLog(Context context) {
        File dir = new File(context.getFilesDir(), "jethub");
        if (!dir.exists()) dir.mkdirs();
        File f = new File(dir, "boot.log");
        StringBuilder sb = new StringBuilder();
        sb.append("started=").append(started).append('\n');
        sb.append("port=").append(GatewayServer.port()).append('\n');
        sb.append("error=").append(lastError == null ? "-" : lastError).append('\n');
        sb.append("time=").append(new java.util.Date()).append('\n');
        if (trace != null) sb.append('\n').append(trace);
        try {
            java.io.FileOutputStream out = new java.io.FileOutputStream(f, false);
            out.write(sb.toString().getBytes("UTF-8"));
            out.close();
        } catch (Throwable ignored) { }
    }

    private Boot() { }
}
