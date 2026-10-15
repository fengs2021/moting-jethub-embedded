import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import org.json.*;
import jet.core.*;

/** 余额与签到的真实端到端（含领取）。 */
public class BalCkTest {
    static int port;
    public static void main(String[] a) throws Exception {
        File tmp = new File("/tmp/jethub-balck-credentials.json");
        copy(new File("/tmp/jethub-smoke-credentials.json"), tmp);
        CredentialStore st = new CredentialStore.FileBased(tmp);
        AccountManager am = new AccountManager(st);
        GatewayServer gw = new GatewayServer(am);
        port = Integer.getInteger("jethub.port", 18391);
        gw.start();
        Thread.sleep(300);

        JSONObject acc = am.accountsJson(false);
        JSONArray provs = acc.getJSONArray("providers");
        for (int i = 0; i < provs.length(); i++) {
            JSONObject p = provs.getJSONObject(i);
            JSONArray l = p.optJSONArray("accounts");
            if (l == null || l.length() == 0) continue;
            String pid = p.getString("id");
            String aid = l.getJSONObject(0).getString("id");
            System.out.println("\n=== " + pid + " / " + aid + " ===");
            JSONObject b = am.fetchBalance(pid, aid);
            System.out.println("余额: " + b);
            if (p.optBoolean("supportsCheckin")) {
                JSONObject c = am.fetchCheckin(pid, aid);
                System.out.println("签到状态: " + c);
                JSONObject cl = am.checkin(pid, aid);
                System.out.println("签到领取: " + cl);
            }
        }
    }
    static void copy(File a, File b) throws IOException {
        InputStream in=new FileInputStream(a); OutputStream o=new FileOutputStream(b);
        byte[] buf=new byte[8192]; int n; while((n=in.read(buf))>0) o.write(buf,0,n);
        in.close(); o.close();
    }
}
