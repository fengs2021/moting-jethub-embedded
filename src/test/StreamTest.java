import jet.core.*;

public class StreamTest {
    public static void main(String[] args) throws Exception {
        ClineProvider cline = new ClineProvider(new CredentialStore.FileBased(
                new java.io.File("/tmp/jethub-smoke-credentials.json")));
        java.util.Map<String, String> h = cline.authHeaders(cline.ensureFresh());
        h.put("Content-Type", "application/json");
        String body = new org.json.JSONObject()
                .put("model", "cline-free/mimo-v2.6-flash")
                .put("stream", true)
                .put("messages", new org.json.JSONArray()
                        .put(new org.json.JSONObject().put("role", "user").put("content", "数到3"))
                ).toString();
        HttpFetcher.Response resp = HttpFetcher.postJson(cline.chatUrl(), h, body, 120000);
        System.out.println("HTTP " + resp.status + " content-type=" + resp.headers.get("content-type"));
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(resp.body, "UTF-8"))) {
            String line; int n = 0;
            while ((line = r.readLine()) != null && n < 14) {
                System.out.println(line.substring(0, Math.min(160, line.length())));
                n++;
            }
        }
    }
}
