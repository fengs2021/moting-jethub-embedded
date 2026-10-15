import jet.core.*;
import org.json.*;
import java.io.File;
import java.util.*;

// 验证 ModelCenterRegistrar 写进墨听的 displayName 确实带倍率
public class DnTest {
  public static void main(String[] a) throws Exception {
    CredentialStore store = new CredentialStore.FileBased(new File("/tmp/dn-creds.json"));
    store.set("JETHUB_LOCAL_KEY", "testkey");
    AccountManager am = new AccountManager(store);
    String smoke = new String(java.nio.file.Files.readAllBytes(new File("/tmp/jethub-smoke-credentials.json").toPath()), "UTF-8");
    JSONObject sm = new JSONObject(smoke);
    Iterator<String> it = sm.keys();
    while (it.hasNext()) { String k = it.next(); store.set(k, sm.get(k).toString()); }

    GatewayServer gw = new GatewayServer(am);
    gw.start();
    try {
      JSONArray data = gw.catalogJson().getJSONArray("data");
      // 复刻 ModelCenterRegistrar:92 的取值
      int withRate = 0; List<String> samples = new ArrayList<>();
      for (int i = 0; i < data.length(); i++) {
        JSONObject m = data.getJSONObject(i);
        String upstream = m.optString("id");
        String dn = m.optString("name", m.optString("id", upstream));
        if (dn.contains(" · ")) { withRate++; if (samples.size() < 8) samples.add(dn); }
      }
      System.out.println("catalogJson 条目数: " + data.length());
      System.out.println("displayName 带倍率/标注: " + withRate);
      System.out.println("样例:");
      for (String s : samples) System.out.println("  " + s);
      System.out.println(withRate > 100 ? "DN-RATE PASS" : "DN-RATE FAIL");
    } finally { gw.stop(); }
  }
}
