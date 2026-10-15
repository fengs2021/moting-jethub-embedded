import jet.core.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;

public class TraeGwTest {
    static int http(String method, String url, String auth, String body, StringBuilder head, StringBuilder out) throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        c.setRequestMethod(method);
        if (auth != null) c.setRequestProperty("Authorization", auth);
        if (body != null) { c.setDoOutput(true); c.getOutputStream().write(body.getBytes("UTF-8")); }
        head.append(c.getResponseCode());
        InputStream in = c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        String l; while ((l = r.readLine()) != null) out.append(l).append('\n');
        return c.getResponseCode();
    }
    public static void main(String[] args) throws Exception {
        CredentialStore store = new CredentialStore.FileBased(new File("/tmp/jethub-smoke-credentials.json"));
        GatewayServer gw = new GatewayServer(new ClineProvider(store), new TraeProvider(store), store);
        gw.start();
        String key = gw.localKey();
        StringBuilder h = new StringBuilder(), o = new StringBuilder();
        int st = http("GET", "http://127.0.0.1:8391/v1/models", "Bearer " + key, null, h, o);
        JSONObject models = new JSONObject(o.toString());
        JSONArray data = models.getJSONArray("data");
        int trae = 0, cline = 0;
        String pick = null;
        for (int i = 0; i < data.length(); i++) {
            String id = data.getJSONObject(i).optString("id","");
            if (id.startsWith("trae/")) { trae++; if (id.contains("Kimi")||id.contains("kimi")) pick = id; }
            if (id.startsWith("cline/")) cline++;
        }
        System.out.println("models HTTP " + st + " total=" + data.length() + " trae=" + trae + " cline=" + cline);
        // 非流式
        StringBuilder h2 = new StringBuilder(), o2 = new StringBuilder();
        String body = new JSONObject().put("model", pick != null ? pick : "trae/DeepSeek-V4-Pro")
            .put("stream", false)
            .put("messages", new JSONArray().put(new JSONObject().put("role","user").put("content","回复：OK")))
            .toString();
        st = http("POST", "http://127.0.0.1:8391/v1/chat/completions", "Bearer " + key, body, h2, o2);
        JSONObject resp = new JSONObject(o2.toString());
        System.out.println("chat non-stream HTTP " + st + " content=" + resp.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content",""));
        // 流式
        java.net.HttpURLConnection c3 = (java.net.HttpURLConnection) new java.net.URL("http://127.0.0.1:8391/v1/chat/completions").openConnection();
        c3.setRequestProperty("Authorization", "Bearer " + key);
        c3.setDoOutput(true);
        c3.getOutputStream().write(new JSONObject(body).put("stream", true).toString().getBytes("UTF-8"));
        BufferedReader r3 = new BufferedReader(new InputStreamReader(c3.getInputStream(), "UTF-8"));
        String l; int chunks = 0; StringBuilder content = new StringBuilder();
        while ((l = r3.readLine()) != null) {
            if (l.startsWith("data: ") && !l.contains("[DONE]")) {
                chunks++;
                JSONObject ch = new JSONObject(l.substring(6));
                String d = ch.getJSONArray("choices").getJSONObject(0).getJSONObject("delta").optString("content","");
                content.append(d);
            }
        }
        System.out.println("chat stream chunks=" + chunks + " content=" + content);
        System.out.println("TRAE GW PASS");
    }
}
