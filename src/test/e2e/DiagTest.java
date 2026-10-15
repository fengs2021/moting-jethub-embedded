import java.io.File;
import org.json.*;
import jet.core.*;

public class DiagTest {
    public static void main(String[] a) throws Exception {
        CredentialStore st = new CredentialStore.FileBased(new File("/tmp/diag-cred.json"));
        AccountManager am = new AccountManager(st);
        JSONObject acc = am.accountsJson(false);
        JSONArray provs = acc.getJSONArray("providers");
        String pid = null, aid = null;
        for (int i = 0; i < provs.length(); i++) {
            JSONObject p = provs.getJSONObject(i);
            JSONArray l = p.optJSONArray("accounts");
            if (l != null && l.length() > 0) { pid = p.getString("id"); aid = l.getJSONObject(0).getString("id"); break; }
        }
        System.out.println("provider=" + pid + " accountId=" + aid);
        // 1) 视图能读到凭据吗
        CredentialStore v = am.view(pid, aid);
        String raw = v.get(ClineProvider.CREDENTIAL_REF);
        System.out.println("视图读凭据: " + (raw == null ? "null" : raw.substring(0, Math.min(80, raw.length()))));
        // 2) 构造 provider 并直连
        ClineProvider cp = am.cline(aid);
        ClineProvider.Credential c = cp.loadCredential();
        System.out.println("loadCredential: " + (c == null ? "null" : c.accountId + " exp=" + c.expireTimeMs + " 过期=" + cp.isExpired(c)));
        try {
            cp.ensureFresh();
            ClineProvider.Credential c2 = cp.loadCredential();
            System.out.println("ensureFresh 后 exp=" + (c2 == null ? "null" : c2.expireTimeMs));
            HttpFetcher.Response r = HttpFetcher.get(cp.modelsUrl(), cp.authHeaders(c2), 30000);
            System.out.println("models HTTP " + r.status);
            String body = r.readText();
            System.out.println("body前150: " + body.substring(0, Math.min(150, body.length())));
        } catch (Throwable t) {
            System.out.println("异常: " + t);
            t.printStackTrace(System.out);
        }
        // 3) 走 action 路径
        System.out.println("action/test → " + am.action("test", pid, aid));
    }
}
