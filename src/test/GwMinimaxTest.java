import jet.core.*;
import java.io.*;

public class GwMinimaxTest {
    public static void main(String[] args) throws Exception {
        // 把 minimax 凭据并入烟测凭据库
        CredentialStore store = new CredentialStore.FileBased(new File("/tmp/jethub-smoke-credentials.json"));
        String mm = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("/tmp/jethub-minimax-acc2.json")), "UTF-8").trim();
        org.json.JSONObject wrapper = new org.json.JSONObject(mm);
        if (wrapper.has("JETHUB_MINIMAX_TOKEN")) mm = wrapper.getString("JETHUB_MINIMAX_TOKEN");
        store.set("JETHUB_MINIMAX_TOKEN", mm);
        GatewayServer gw = new GatewayServer(new ClineProvider(store), new TraeProvider(store), store);
        gw.start();
        String key = gw.localKey();
        String models = GwE2EHelper.get("http://127.0.0.1:8391/v1/models", key);
        System.out.println("merged count=" + GwE2EHelper.count(models));
        // minimax 非流式
        String chat = GwE2EHelper.post("http://127.0.0.1:8391/v1/chat/completions", key,
                "{\"model\":\"minimax/MiniMax-M2.7\",\"messages\":[{\"role\":\"user\",\"content\":\"回复两个字：收到\"}]}");
        System.out.println("non-stream: " + chat.substring(0, Math.min(160, chat.length())));
        // minimax 流式
        String stream = GwE2EHelper.post("http://127.0.0.1:8391/v1/chat/completions", key,
                "{\"model\":\"minimax/MiniMax-M2.7\",\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"回复两个字：成功\"}]}");
        System.out.println("stream chunks=" + countChunks(stream) + " hasDONE=" + stream.contains("[DONE]"));
        System.out.println("GW MINIMAX DONE");
    }
    static int countChunks(String sse) { int n = 0; for (String l : sse.split("\n")) if (l.startsWith("data:") && l.contains("chat.completion.chunk")) n++; return n; }
}
