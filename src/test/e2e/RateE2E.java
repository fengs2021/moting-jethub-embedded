import jet.core.*;
import org.json.*;
import java.io.File;
import java.util.*;

public class RateE2E {
  public static void main(String[] a) throws Exception {
    CredentialStore store = new CredentialStore.FileBased(new File("/tmp/rate-e2e-creds.json"));
    store.set("JETHUB_LOCAL_KEY", "testkey");
    AccountManager am = new AccountManager(store);
    // 从冒烟凭据导入
    String smoke = new String(java.nio.file.Files.readAllBytes(new File("/tmp/jethub-smoke-credentials.json").toPath()), "UTF-8");
    JSONObject sm = new JSONObject(smoke);
    Iterator<String> it = sm.keys();
    while (it.hasNext()) { String k = it.next(); store.set(k, sm.get(k).toString()); }

    GatewayServer gw = new GatewayServer(am);
    gw.start();
    int port = GatewayServer.port();
    try {
      Map<String,String> h = new HashMap<>();
      h.put("Authorization", "Bearer testkey");
      HttpFetcher.Response r = HttpFetcher.get("http://127.0.0.1:" + port + "/jethub/models", h, 60000);
      JSONObject body = new JSONObject(r.readText());
      JSONArray groups = body.getJSONArray("groups");
      System.out.println("分组数: " + groups.length());
      int withRate = 0, total = 0;
      for (int i = 0; i < groups.length(); i++) {
        JSONObject g = groups.getJSONObject(i);
        JSONArray ms = g.getJSONArray("models");
        int wr = 0;
        for (int j = 0; j < ms.length(); j++) {
          String nm = ms.getJSONObject(j).optString("name","");
          if (nm.contains(" · ")) wr++;
        }
        total += ms.length(); withRate += wr;
        System.out.println(String.format("  %-12s %-16s 共%3d 带倍率%3d  例: %s",
          g.optString("provider"), g.optString("label"), ms.length(), wr,
          ms.length()>0 ? ms.getJSONObject(0).optString("name") : "-"));
      }
      System.out.println("合计: " + total + " 个模型, 带倍率/标注 " + withRate);
    } finally { gw.stop(); }
  }
}
