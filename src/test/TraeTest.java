import jet.core.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;

public class TraeTest {
    public static void main(String[] args) throws Exception {
        CredentialStore store = new CredentialStore.FileBased(new File("/tmp/jethub-smoke-credentials.json"));
        TraeProvider trae = new TraeProvider(store);
        TraeProvider.Credential c = trae.loadCredential();
        System.out.println("loaded, expired=" + trae.isExpired(c));
        // 1. 模型目录
        JSONObject models = trae.listModels();
        JSONArray data = models.getJSONArray("data");
        System.out.println("models: " + data.length());
        String pick = null;
        for (int i = 0; i < data.length(); i++) {
            String id = data.getJSONObject(i).optString("id", "");
            if (id.toLowerCase().contains("kimi") || id.toLowerCase().contains("deepseek")) { pick = id; break; }
        }
        if (pick == null && data.length() > 0) pick = data.getJSONObject(0).optString("id", "");
        System.out.println("pick = " + pick);
        // 2. 非流式对话（经网关转换聚合）
        JSONObject req = new JSONObject();
        req.put("model", pick);
        req.put("stream", false);
        req.put("messages", new JSONArray().put(new JSONObject().put("role","user").put("content","只回复两个字：收到")));
        HttpFetcher.Response resp = trae.chatRaw(trae.ensureFresh(), trae.toSoloBody(req, pick));
        System.out.println("chat HTTP " + resp.status);
        if (resp.status != 200) { System.out.println(resp.readText().substring(0, Math.min(300, 1))); return; }
        String out = TraeSseConverter.aggregate(pick, resp.body);
        System.out.println("aggregated: " + out.substring(0, Math.min(400, out.length())));
    }
}
