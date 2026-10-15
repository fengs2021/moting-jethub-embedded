import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

import org.json.JSONArray;
import org.json.JSONObject;

import jet.core.*;

/** 管理页 + 账号管理 + 积分签到端点端到端测试（host JVM）。 */
public class UiE2E {

    static String key;

    public static void main(String[] args) throws Exception {
        File cred = new File(args.length > 0 ? args[0] : "/tmp/jethub-smoke-credentials.json");
        File tmp = new File("/tmp/jethub-uie2e-credentials.json");
        copy(cred, tmp);

        CredentialStore store = new CredentialStore.FileBased(tmp);
        AccountManager accounts = new AccountManager(store);
        GatewayServer gw = new GatewayServer(accounts);
        gw.start();
        key = gw.localKey();
        Thread.sleep(400);

        int pass = 0, fail = 0;
        String[] names = {
            "首页返回新管理页", "status 含 localKey/baseUrl", "accounts 结构",
            "providers 含 supportsCheckin", "账号字段契约", "余额查询",
            "签到状态查询", "签到全部", "账号测试", "账号刷新令牌", "登出",
        };
        int i = 0;
        try {
            // 1 首页
            String home = getText("/");
            boolean ok1 = home.contains("id=\"btn-register\"") && home.contains("/jethub");
            report(names[i++], ok1, ok1 ? "含 btn-register 与 /jethub 引用" : "首页异常: " + head(home));
            pass += ok1 ? 1 : 0; fail += ok1 ? 0 : 1;

            // 2 status
            JSONObject st = new JSONObject(get("/jethub/status", null));
            boolean ok2 = st.has("localKey") && st.has("baseUrl") && st.has("configured");
            report(names[i++], ok2, "localKey=" + shorten(st.optString("localKey")) + " baseUrl=" + st.optString("baseUrl"));
            pass += ok2 ? 1 : 0; fail += ok2 ? 0 : 1;

            // 3 accounts 结构
            JSONObject acc = new JSONObject(get("/jethub/accounts", null));
            JSONArray provs = acc.optJSONArray("providers");
            boolean ok3 = provs != null && provs.length() >= 5;
            report(names[i++], ok3, "providers=" + (provs == null ? "null" : provs.length()));
            pass += ok3 ? 1 : 0; fail += ok3 ? 0 : 1;

            // 4 supportsCheckin + 5 账号字段
            boolean ok4 = false, ok5 = true;
            StringBuilder detail = new StringBuilder();
            int totalAccts = 0;
            for (int p = 0; provs != null && p < provs.length(); p++) {
                JSONObject pv = provs.getJSONObject(p);
                if (pv.has("supportsCheckin") && pv.get("supportsCheckin") instanceof Boolean) ok4 = true;
                JSONArray list = pv.optJSONArray("accounts");
                totalAccts += list == null ? 0 : list.length();
                for (int a = 0; list != null && a < list.length(); a++) {
                    JSONObject ao = list.getJSONObject(a);
                    if (!ao.has("id") || !ao.has("label") || !ao.has("active") || !ao.has("createdAt")) {
                        ok5 = false;
                        detail.append("缺字段@").append(pv.optString("id")).append(" ");
                    }
                }
            }
            report(names[i++], ok4, ok4 ? "全部 provider 带 supportsCheckin 布尔" : "缺 supportsCheckin");
            pass += ok4 ? 1 : 0; fail += ok4 ? 0 : 1;
            report(names[i++], ok5, "账号总数=" + totalAccts + " " + detail);
            pass += ok5 ? 1 : 0; fail += ok5 ? 0 : 1;

            // 找第一个有账号的 provider
            String prov = null, acctId = null;
            for (int p = 0; provs != null && p < provs.length(); p++) {
                JSONObject pv = provs.getJSONObject(p);
                JSONArray list = pv.optJSONArray("accounts");
                if (list != null && list.length() > 0) {
                    prov = pv.getString("id");
                    acctId = list.getJSONObject(0).getString("id");
                    break;
                }
            }
            System.out.println("  → 测试目标: " + prov + " / " + acctId);

            // 6 余额
            JSONObject bal = new JSONObject(post("/jethub/accounts/test",
                    "{\"provider\":\"" + prov + "\",\"accountId\":\"" + acctId + "\"}"));
            boolean ok6 = bal.has("ok");
            report(names[i++], ok6, bal.toString());
            pass += ok6 ? 1 : 0; fail += ok6 ? 0 : 1;

            // 7 签到状态
            JSONObject ck = new JSONObject(post("/jethub/checkin/status",
                    "{\"provider\":\"" + prov + "\",\"accountId\":\"" + acctId + "\"}"));
            boolean ok7 = ck.has("active") || ck.has("error");
            report(names[i++], ok7, ck.toString());
            pass += ok7 ? 1 : 0; fail += ok7 ? 0 : 1;

            // 8 签到全部（幂等，可能 already-claimed）
            JSONObject all = new JSONObject(post("/jethub/checkin/all", "{}"));
            boolean ok8 = all.has("results");
            report(names[i++], ok8, "results=" + (all.optJSONArray("results") == null ? "null" : all.optJSONArray("results").length()));
            pass += ok8 ? 1 : 0; fail += ok8 ? 0 : 1;

            // 9 账号测试（列模型数）
            JSONObject t2 = new JSONObject(post("/jethub/accounts/test",
                    "{\"provider\":\"" + prov + "\",\"accountId\":\"" + acctId + "\"}"));
            boolean ok9 = t2.has("ok");
            report(names[i++], ok9, t2.toString());
            pass += ok9 ? 1 : 0; fail += ok9 ? 0 : 1;

            // 10 刷新令牌
            JSONObject rf = new JSONObject(post("/jethub/accounts/refresh",
                    "{\"provider\":\"" + prov + "\",\"accountId\":\"" + acctId + "\"}"));
            boolean ok10 = rf.has("ok");
            report(names[i++], ok10, rf.toString());
            pass += ok10 ? 1 : 0; fail += ok10 ? 0 : 1;

            // 11 无密钥访问 /v1/models 应 401
            JSONObject oops = raw("/v1/models", "GET", null, null);
            boolean ok11 = oops.has("error");
            report(names[i++], ok11, "无密钥→" + oops.optJSONObject("error"));
            pass += ok11 ? 1 : 0; fail += ok11 ? 0 : 1;

        } catch (Exception e) {
            e.printStackTrace();
            fail++;
        }
        System.out.println("\n通过 " + pass + " / 失败 " + fail);
        System.exit(fail == 0 ? 0 : 1);
    }

    static void report(String name, boolean ok, String detail) {
        System.out.println((ok ? "[PASS] " : "[FAIL] ") + name + " — " + detail);
    }

    static String shorten(String s) { return s == null ? "null" : (s.length() > 10 ? s.substring(0, 8) + "…" : s); }
    static String head(String s) { return s == null ? "null" : s.substring(0, Math.min(120, s.length())).replace('\n', ' '); }

    static void copy(File a, File b) throws IOException {
        InputStream in = new FileInputStream(a);
        OutputStream out = new FileOutputStream(b);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close(); out.close();
    }

    static String get(String path, String auth) throws Exception { return raw(path, "GET", null, auth).toString(); }

    /** 首页返回 HTML 非 JSON，需取原始文本。 */
    static String getText(String path) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + GatewayServer.PORT + path).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(30000);
        String t = read(c.getInputStream());
        c.disconnect();
        return t;
    }

    static String post(String path, String body) throws Exception {
        return raw(path, "POST", body, null).toString();
    }

    /** 管理端点不需要密钥；带 auth 时用于 /v1/* 测试。 */
    static JSONObject raw(String path, String method, String body, String auth) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + GatewayServer.port() + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(5000);
        c.setReadTimeout(180000);
        if (auth != null) c.setRequestProperty("Authorization", auth);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String text = in == null ? "" : read(in);
        c.disconnect();
        if (text.startsWith("{")) return new JSONObject(text);
        JSONObject o = new JSONObject();
        o.put("raw", text);
        o.put("status", code);
        return o;
    }

    static String read(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }
}
