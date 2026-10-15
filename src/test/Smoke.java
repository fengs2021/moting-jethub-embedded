import jet.core.*;
import java.io.*;
import java.net.*;

public class Smoke {
    public static void main(String[] args) throws Exception {
        CredentialStore store = new CredentialStore.FileBased(
                new File("/tmp/jethub-smoke-empty.json"));
        ClineProvider cline = new ClineProvider(store);
        GatewayServer gw = new GatewayServer(cline, new TraeProvider(store), store);
        gw.start();
        String key = gw.localKey();
        check("401 无密钥", httpGet("http://127.0.0.1:8391/v1/models", null).startsWith("HTTP/1.1 401"));
        check("401 错密钥", httpGet("http://127.0.0.1:8391/v1/models", "Bearer wrong").startsWith("HTTP/1.1 401"));
        check("status configured=false", httpGet("http://127.0.0.1:8391/jethub/status", "Bearer " + key).contains("\"configured\":false"));
        String models = httpGet("http://127.0.0.1:8391/v1/models", "Bearer " + key);
        // 未登录时目录应为 200 兜底表（cline free 表）或 502，不能挂死/空指针
        check("未登录拉模型有兜底", models.startsWith("HTTP/1.1 200") || models.startsWith("HTTP/1.1 502"));
        // 无效 JSON body → 502/400 而不是挂死
        check("chat 坏 body", httpPost("http://127.0.0.1:8391/v1/chat/completions", "Bearer " + key, "not json") != null);
        System.out.println("SMOKE PASS");
    }
    static String httpGet(String url, String auth) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(5000); c.setReadTimeout(30000);
        if (auth != null) c.setRequestProperty("Authorization", auth);
        int status = c.getResponseCode();
        String body = read(status >= 400 ? c.getErrorStream() : c.getInputStream());
        return "HTTP/1.1 " + status + " " + c.getResponseMessage() + "\n" + body;
    }
    static String httpPost(String url, String auth, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(5000); c.setReadTimeout(30000);
        c.setRequestMethod("POST"); c.setDoOutput(true);
        if (auth != null) c.setRequestProperty("Authorization", auth);
        try (OutputStream os = c.getOutputStream()) { os.write(body.getBytes("UTF-8")); }
        int status = c.getResponseCode();
        return "HTTP/1.1 " + status;
    }
    static String read(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toString("UTF-8");
    }
    static void check(String name, boolean ok) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) System.exit(1);
    }
}
