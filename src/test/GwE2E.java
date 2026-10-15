import jet.core.*;
import java.io.*;
import java.net.*;

public class GwE2E {
    public static void main(String[] args) throws Exception {
        CredentialStore store = new CredentialStore.FileBased(new File("/tmp/jethub-smoke-credentials.json"));
        GatewayServer gw = new GatewayServer(new ClineProvider(store), new TraeProvider(store), store);
        gw.start();
        String key = gw.localKey();
        // 1. models 走网关
        String models = get("http://127.0.0.1:8391/v1/models", key);
        System.out.println("models: " + models.substring(0, 120) + "… count=" + count(models));
        // 2. 非流式 chat（验证 data 信封归一）
        String chat = post("http://127.0.0.1:8391/v1/chat/completions", key,
                "{\"model\":\"cline/cline-free/mimo-v2.6-flash\",\"messages\":[{\"role\":\"user\",\"content\":\"回复两个字：成功\"}],\"max_tokens\":100}");
        System.out.println("chat head: " + chat.substring(0, Math.min(200, chat.length())));
        System.out.println("信封归一: " + (chat.contains("\"choices\"") && !chat.contains("\"data\":{\"choices\"") ? "PASS" : "FAIL"));
        System.out.println("GW E2E DONE");
    }
    static int count(String json) { try { return new org.json.JSONObject(json).getJSONArray("data").length(); } catch (Exception e) { return -1; } }
    static String get(String url, String key) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setReadTimeout(60000);
        c.setRequestProperty("Authorization", "Bearer " + key);
        return read(c.getInputStream());
    }
    static String post(String url, String key, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setReadTimeout(120000); c.setRequestMethod("POST"); c.setDoOutput(true);
        c.setRequestProperty("Authorization", "Bearer " + key);
        c.setRequestProperty("Content-Type", "application/json");
        try (OutputStream os = c.getOutputStream()) { os.write(body.getBytes("UTF-8")); }
        return read(c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream());
    }
    static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toString("UTF-8");
    }
}
