import jet.core.*;
import java.io.*;

public class E2E {
    public static void main(String[] args) throws Exception {
        ClineProvider cline = new ClineProvider(new CredentialStore.FileBased(
                new java.io.File("/tmp/jethub-smoke-credentials.json")));
        ClineProvider.Credential c = cline.loadCredential();
        System.out.println("loaded: refreshable=" + cline.isRefreshable(c) + " expired=" + cline.isExpired(c));
        // 1. 强制续期（验证驼峰 refresh 协议）
        ClineProvider.Credential fresh = cline.refresh();
        System.out.println("refreshed: expire=" + fresh.expireTimeMs + " hasRefresh=" + cline.isRefreshable(fresh));
        // 2. 列模型
        java.util.Map<String, String> h = cline.authHeaders(cline.loadCredential());
        HttpFetcher.Response models = HttpFetcher.get(cline.modelsUrl(), h, 30000);
        String mt = models.readText();
        System.out.println("models HTTP " + models.status + " count=" + countModels(mt));
        // 3. 非流式对话
        h = cline.authHeaders(cline.loadCredential());
        h.put("Content-Type", "application/json");
        String body = new org.json.JSONObject()
                .put("model", "mini-max-ui/minimax-m2.7")
                .put("messages", new org.json.JSONArray()
                        .put(new org.json.JSONObject().put("role", "user").put("content", "回复两个字：成功")))
                .put("max_tokens", 100)
                .toString();
        HttpFetcher.Response chat = HttpFetcher.postJson(cline.chatUrl(), h, body, 120000);
        String ct = chat.readText();
        System.out.println("chat HTTP " + chat.status);
        System.out.println("chat body head: " + ct.substring(0, Math.min(400, ct.length())));
        System.out.println("E2E DONE");
    }
    static int countModels(String json) {
        try {
            return new org.json.JSONObject(json).getJSONArray("data").length();
        } catch (Exception e) { return -1; }
    }
}
