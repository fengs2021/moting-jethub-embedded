import jet.core.*;

public class Chat2 {
    public static void main(String[] args) throws Exception {
        ClineProvider cline = new ClineProvider(new CredentialStore.FileBased(
                new java.io.File("/tmp/jethub-smoke-credentials.json")));
        java.util.Map<String, String> h = cline.authHeaders(cline.ensureFresh());
        h.put("Content-Type", "application/json");
        String model = args.length > 0 ? args[0] : "cline-free/mimo-v2.6-flash";
        String body = new org.json.JSONObject()
                .put("model", model)
                .put("messages", new org.json.JSONArray()
                        .put(new org.json.JSONObject().put("role", "user").put("content", "回复两个字：成功")))
                .put("max_tokens", 200)
                .toString();
        HttpFetcher.Response chat = HttpFetcher.postJson(cline.chatUrl(), h, body, 120000);
        String ct = chat.readText();
        System.out.println("model=" + model + " HTTP " + chat.status);
        System.out.println(ct.substring(0, Math.min(500, ct.length())));
    }
}
