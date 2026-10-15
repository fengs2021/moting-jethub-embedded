import jet.core.*;
import java.io.*;
import java.net.*;

public class GwFull {
    static String base() { return "http://127.0.0.1:" + GatewayServer.port(); }
    static String get(String u, String key) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(10000); c.setReadTimeout(180000);
        if (key != null) c.setRequestProperty("Authorization", "Bearer " + key);
        BufferedReader r = new BufferedReader(new InputStreamReader(c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream(), "UTF-8"));
        StringBuilder sb = new StringBuilder(); String l;
        while ((l = r.readLine()) != null) sb.append(l);
        return c.getResponseCode() + "|" + sb;
    }
    static String post(String u, String key, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setRequestMethod("POST"); c.setDoOutput(true);
        c.setConnectTimeout(10000); c.setReadTimeout(180000);
        if (key != null) c.setRequestProperty("Authorization", "Bearer " + key);
        c.setRequestProperty("Content-Type", "application/json");
        c.getOutputStream().write(body.getBytes("UTF-8"));
        BufferedReader r = new BufferedReader(new InputStreamReader(c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream(), "UTF-8"));
        StringBuilder sb = new StringBuilder(); String l;
        while ((l = r.readLine()) != null) sb.append(l);
        return c.getResponseCode() + "|" + sb;
    }
    public static void main(String[] args) throws Exception {
        CredentialStore store = new CredentialStore.FileBased(new File("/tmp/jethub-smoke-credentials.json"));
        GatewayServer gw = new GatewayServer(new ClineProvider(store), new TraeProvider(store),
            new BuddyProvider(store, BuddyProvider.Variant.buddy()),
            new BuddyProvider(store, BuddyProvider.Variant.workbuddy()), store);
        gw.start();
        Thread.sleep(500);
        String key = gw.localKey();
        String models = get(base() + "/v1/models", key);
        String bodyPart = models.substring(models.indexOf('|') + 1);
        int count = bodyPart.split("\"id\"").length - 1;
        System.out.println("models http=" + models.substring(0, models.indexOf('|')) + " count=" + count);
        // 每家供应商抽一个模型做真实对话
        String[][] probes = {
            {"trae/kimi-k2.5", "trae"},
            {"buddy/auto", "buddy"},
            {"workbuddy/default-model", "workbuddy"},
        };
        for (String[] p : probes) {
            try {
                String resp = post(base() + "/v1/chat/completions", key,
                    "{\"model\":\"" + p[0] + "\",\"messages\":[{\"role\":\"user\",\"content\":\"只回复两个字：收到\"}],\"max_tokens\":300}");
                String code = resp.substring(0, resp.indexOf('|'));
                String content = resp.contains("\"content\":\"") ? resp.substring(resp.indexOf("\"content\":\"") + 10, resp.indexOf("\"content\":\"") + 30) : "(no content)";
                System.out.println("chat " + p[0] + " http=" + code + " content~" + content);
            } catch (Exception e) {
                System.out.println("chat " + p[0] + " ERROR " + e);
            }
        }
        gw.stop();
        System.out.println("FULL DONE");
    }
}
