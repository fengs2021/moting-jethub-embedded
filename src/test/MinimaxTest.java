import jet.core.*;

public class MinimaxTest {
    public static void main(String[] args) throws Exception {
        String cred = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("/tmp/minimax-cred.json")), "UTF-8").trim();
        CredentialStore store = new CredentialStore.FileBased(new java.io.File("/tmp/jethub-minimax-test-cred.json"));
        store.set("JETHUB_MINIMAX_TOKEN", cred);
        MinimaxProvider mm = new MinimaxProvider(store);
        // 1) 目录
        org.json.JSONObject models = mm.listModels();
        System.out.println("models=" + models.optJSONArray("data").length());
        // 2) 非流式对话
        org.json.JSONObject req = new org.json.JSONObject();
        req.put("model", "minimax/MiniMax-M2.7");
        org.json.JSONArray msgs = new org.json.JSONArray();
        msgs.put(new org.json.JSONObject().put("role", "user").put("content", "回复两个字：收到"));
        req.put("messages", msgs);
        MinimaxProvider.Credential c = mm.ensureFresh();
        HttpFetcher.Response resp = mm.chatRaw(c, MinimaxProvider.toMessagesPayload(req));
        String text = resp.readText();
        System.out.println("chat status=" + resp.status);
        if (resp.status == 200) {
            org.json.JSONObject out = MinimaxSseConverter.aggregate("minimax/MiniMax-M2.7", text);
            System.out.println("content=" + out.optJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content"));
        } else {
            System.out.println("err=" + text.substring(0, Math.min(200, text.length())));
        }
    }
}
