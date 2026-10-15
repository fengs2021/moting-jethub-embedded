import jet.core.*;
import org.json.*;
import java.io.File;
import java.util.*;

// 走真实 HTTP 路径核验前端实际会打的三个请求
public class HttpCredits {
  public static void main(String[] a) throws Exception {
    CredentialStore store = new CredentialStore.FileBased(new File("/tmp/hc-creds.json"));
    store.set("JETHUB_LOCAL_KEY", "testkey");
    AccountManager am = new AccountManager(store);
    String smoke = new String(java.nio.file.Files.readAllBytes(new File("/tmp/jethub-smoke-credentials.json").toPath()), "UTF-8");
    JSONObject sm = new JSONObject(smoke);
    Iterator<String> it = sm.keys();
    while (it.hasNext()) { String k = it.next(); store.set(k, sm.get(k).toString()); }

    GatewayServer gw = new GatewayServer(am);
    gw.start();
    int p = GatewayServer.port();
    String base = "http://127.0.0.1:" + p + "/jethub";
    Map<String,String> h = new HashMap<>();
    try {
      // 1. 心跳路径（不带 credits）
      JSONObject plain = new JSONObject(HttpFetcher.get(base + "/accounts", h, 30000).readText());
      boolean plainHasBal = false;
      JSONArray ps = plain.getJSONArray("providers");
      for (int i = 0; i < ps.length(); i++) {
        JSONArray as = ps.getJSONObject(i).optJSONArray("accounts");
        if (as == null) continue;
        for (int j = 0; j < as.length(); j++) if (as.getJSONObject(j).has("balance")) plainHasBal = true;
      }
      System.out.println("① /accounts 无 credits=1 → 含 balance 字段: " + plainHasBal + "（应为 false）");

      // 2. 补查路径
      JSONObject withC = new JSONObject(HttpFetcher.get(base + "/accounts?credits=1", h, 60000).readText());
      int withBal = 0, withCk = 0;
      JSONArray ps2 = withC.getJSONArray("providers");
      for (int i = 0; i < ps2.length(); i++) {
        JSONArray as = ps2.getJSONObject(i).optJSONArray("accounts");
        if (as == null) continue;
        for (int j = 0; j < as.length(); j++) {
          JSONObject ac = as.getJSONObject(j);
          if (ac.has("balance")) withBal++;
          if (ac.has("checkin")) withCk++;
        }
      }
      System.out.println("② /accounts?credits=1 → 带 balance 账号 " + withBal + " 个，带 checkin " + withCk + " 个（应 > 0）");

      // 3. 单账号强制查询
      String prov = null, acct = null;
      for (int i = 0; i < ps2.length() && prov == null; i++) {
        JSONArray as = ps2.getJSONObject(i).optJSONArray("accounts");
        if (as != null && as.length() > 0) { prov = ps2.getJSONObject(i).optString("id"); acct = as.getJSONObject(0).optString("id"); }
      }
      JSONObject body = new JSONObject().put("provider", prov).put("accountId", acct);
      JSONObject one = new JSONObject(HttpFetcher.postJson(base + "/accounts/credits", h, body.toString(), 60000).readText());
      System.out.println("③ POST /accounts/credits [" + prov + "] ok=" + one.opt("ok")
        + " 有balance=" + one.has("balance") + " 有checkin=" + one.has("checkin"));

      // 4. 模型管理端点
      JSONObject mg = new JSONObject(HttpFetcher.get(base + "/models", h, 60000).readText());
      JSONArray gs = mg.getJSONArray("groups");
      System.out.println("④ GET /models → 分组 " + gs.length() + " 个");
      JSONObject setBody = new JSONObject().put("provider", prov)
        .put("modelId", gs.getJSONObject(0).getJSONArray("models").getJSONObject(0).optString("id")).put("enabled", false);
      JSONObject sr = new JSONObject(HttpFetcher.postJson(base + "/models/set", h, setBody.toString(), 30000).readText());
      System.out.println("⑤ POST /models/set → ok=" + sr.opt("ok") + " disabled=" + sr.optJSONArray("disabled"));
      JSONObject rr = new JSONObject(HttpFetcher.postJson(base + "/models/reset", h,
        new JSONObject().put("provider", prov).toString(), 30000).readText());
      System.out.println("⑥ POST /models/reset → ok=" + rr.opt("ok") + " disabled=" + rr.optJSONArray("disabled"));

      boolean pass = !plainHasBal && withBal > 0 && one.optBoolean("ok") && gs.length() >= 3
        && sr.optBoolean("ok") && rr.optBoolean("ok");
      System.out.println(pass ? "HTTP-CREDITS PASS" : "HTTP-CREDITS FAIL");
    } finally { gw.stop(); }
  }
}
