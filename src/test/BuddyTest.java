import jet.core.BuddyProvider;
import jet.core.CredentialStore;
import jet.core.HttpFetcher;
import org.json.JSONArray;
import org.json.JSONObject;

/** Buddy/WorkBuddy 真实凭据端到端：模型目录 + 流式对话（两家均强制 stream）。 */
public class BuddyTest {
    public static void main(String[] args) throws Exception {
        CredentialStore store = new CredentialStore.FileBased(new java.io.File("/tmp/jethub-smoke-credentials.json"));
        int fail = 0;
        for (BuddyProvider.Variant v : new BuddyProvider.Variant[]{
                BuddyProvider.Variant.workbuddy()}) {
            BuddyProvider p = new BuddyProvider(store, v);
            String tag = v.productCode;
            String firstModel;
            try {
                JSONObject models = p.listModels();
                JSONArray data = models.getJSONArray("data");
                System.out.println("[" + tag + "] models: " + data.length());
                if (data.length() == 0) throw new IllegalStateException("empty models");
                firstModel = data.getJSONObject(0).getString("id").split("/", 2)[1];
                for (int i = 0; i < Math.min(3, data.length()); i++)
                    System.out.println("  " + data.getJSONObject(i).getString("id"));
            } catch (Exception e) {
                fail++;
                System.out.println("[" + tag + "] models FAIL: " + e.getMessage());
                continue;
            }
            try {
                JSONObject body = new JSONObject();
                body.put("model", firstModel);
                body.put("stream", true);
                JSONArray msgs = new JSONArray();
                if ("workbuddy".equals(v.productCode))
                    msgs.put(new JSONObject().put("role", "system").put("content", "You are a helpful assistant."));
                msgs.put(new JSONObject().put("role", "user").put("content", "只回复两个字：收到"));
                body.put("messages", msgs);
                body.put("max_tokens", 512);
                System.out.println("SEND=" + body); HttpFetcher.Response resp = p.chatRaw(p.ensureFresh(), body);
                if (resp.status != 200) {
                    String t = resp.readText();
                    throw new IllegalStateException("chat HTTP " + resp.status + " " +
                            t.substring(0, Math.min(200, t.length())));
                }
                String content = parseSseContent(resp);
                System.out.println("[" + tag + "] chat stream content=\"" + content + "\"");
                if (content.isEmpty()) throw new IllegalStateException("empty content");
            } catch (Exception e) {
                fail++;
                System.out.println("[" + tag + "] chat FAIL: " + e);
            }
        }
        System.out.println(fail == 0 ? "BUDDY TEST PASS" : "BUDDY TEST FAIL(" + fail + ")");
        if (fail > 0) System.exit(1);
    }

    /** 从 SSE 流收集 content 增量（标准 OpenAI chunk）。 */
    static String parseSseContent(HttpFetcher.Response resp) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(resp.conn.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                String payload = line.substring(5).trim();
                if (payload.equals("[DONE]") || payload.isEmpty()) continue;
                try {
                    JSONObject chunk = new JSONObject(payload);
                    JSONArray choices = chunk.optJSONArray("choices");
                    if (choices == null || choices.length() == 0) continue;
                    JSONObject delta = choices.getJSONObject(0).optJSONObject("delta");
                    if (delta != null) sb.append(delta.optString("content", ""));
                } catch (Exception ignored) { }
            }
        }
        return sb.toString();
    }
}
