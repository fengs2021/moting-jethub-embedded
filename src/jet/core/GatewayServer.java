package jet.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 内嵌 OpenAI 兼容网关：墨听把它当一个普通 baseUrl（http://127.0.0.1:PORT/v1）。
 *
 * 路由：
 *   GET  /v1/models                — 模型目录（供应商前缀 id，如 cline/deepseek-v4.1-flash）
 *   POST /v1/chat/completions      — 对话（stream / 非 stream），透传 SSE
 *   GET  /jethub/status            — 登录状态（供调试页用）
 *   POST /jethub/login/start       — 发起 Cline 设备码登录 → {verification_uri, user_code}
 *   POST /jethub/login/poll        — 轮询登录 → {status}
 *   POST /jethub/logout
 *
 * 鉴权：固定本地密钥（写入凭据存储 JETHUB_LOCAL_KEY，首次启动生成），
 * 墨听侧账号的 apiKey 填它。Authorization: Bearer <key>。
 */
public final class GatewayServer {

    public static final int PORT = 8391;

    /**
     * 实际监听端口：真机恒为 {@link #PORT}。
     * host JVM 单测可用 {@code -Djethub.port=N} 覆盖（避免与本机其它进程抢 8391）。
     */
    public static int port() {
        String p = System.getProperty("jethub.port");
        if (p != null) {
            try {
                int v = Integer.parseInt(p.trim());
                if (v > 0 && v < 65536) return v;
            } catch (NumberFormatException ignored) { }
        }
        return PORT;
    }

    /** 管理页与 /jethub/status 显示的版本号。 */
    public static final String VERSION = "2.0";

    /**
     * 这些 Provider 一律建立在「活跃账号视图」上（{@link AccountManager#view} 传 null）。
     * 视图每次 get 都现读桶，所以用户切换账号、续期回写之后**无需重建 Provider**，
     * 对话与目录自动跟随当前活跃账号。
     */
    private volatile ClineProvider cline;
    private volatile TraeProvider trae;
    private volatile BuddyProvider buddy;
    private volatile BuddyProvider workbuddy;
    private volatile MinimaxProvider minimax;
    private volatile OpencodeProvider opencode;
    private volatile CodeartsProvider codearts;
    private final CredentialStore store;
    /** 多账号管理（账号总览 / 签到 / 切号）；旧构造器下也会建一个仅用于端点。 */
    private final AccountManager accounts;
    /** 模型开关表（每供应商一份「已关闭模型」黑名单）。 */
    private final ModelRegistry models;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private ServerSocket serverSocket;
    private volatile boolean running;

    /** 设备码登录会话（单会话即可）。 */
    private volatile ClineProvider.DeviceAuthorization loginSession;
    private volatile MinimaxProvider.DeviceAuthorization mmLoginSession;
    private volatile BuddyProvider.LoginSession buddyLoginSession;
    private volatile BuddyProvider.LoginSession workbuddyLoginSession;
    /** CodeArts 登录会话（回调由 Provider 自建的本地服务器接收，这里只保存状态供 UI 查询）。 */
    private volatile CodeartsProvider.LoginSession codeartsLoginSession;

    private static volatile GatewayServer INSTANCE;

    /** 最近一次构造的网关实例（供模型中心注册器取目录）。 */
    public static GatewayServer active() { return INSTANCE; }

    /**
     * 生产构造器：全部 Provider 建立在「活跃账号视图」上。
     *
     * 这是多账号能生效的关键 —— Provider 只认 CredentialStore 接口，
     * 视图把「账号桶里的当前账号」伪装成它就是那个单账号存储。
     * 于是对话/列模型/续期回写全部自动跟随用户在管理页选定的账号，
     * 切换账号无需重启网关、无需重建 Provider。
     */
    public GatewayServer(AccountManager accounts) {
        INSTANCE = this;
        this.accounts = accounts;
        this.store = accounts.globalStore();
        this.models = new ModelRegistry(this.store);
        this.cline = accounts.cline(null);
        this.trae = accounts.trae(null);
        this.buddy = accounts.buddy(null);
        this.workbuddy = accounts.workbuddy(null);
        this.minimax = accounts.minimax(null);
        this.opencode = accounts.opencode(null);
        this.codearts = accounts.codearts(null);
    }

    /** 兼容旧调用（测试用）：Provider 由外部给定，管理端点仍可用。 */
    public GatewayServer(ClineProvider cline, TraeProvider trae, CredentialStore store) {
        this(cline, trae, new BuddyProvider(store, BuddyProvider.Variant.buddy()),
                new BuddyProvider(store, BuddyProvider.Variant.workbuddy()), store);
    }

    /** 兼容旧调用（测试用）。 */
    public GatewayServer(ClineProvider cline, TraeProvider trae, BuddyProvider buddy,
                         BuddyProvider workbuddy, CredentialStore store) {
        INSTANCE = this;
        this.cline = cline;
        this.trae = trae;
        this.buddy = buddy;
        this.workbuddy = workbuddy;
        this.minimax = new MinimaxProvider(store);
        this.store = store;
        this.accounts = new AccountManager(store);
        this.models = new ModelRegistry(store);
    }

    /** 多账号管理器（管理页端点用）。 */
    public AccountManager accountManager() { return accounts; }

    /** 模型开关表。 */
    public ModelRegistry modelRegistry() { return models; }

    /**
     * /jethub/status 的响应体（管理页顶栏用）。
     *
     * 注意 started 恒为 true —— 能收到这个响应就说明网关已经在监听了；
     * 真正的启动失败用户看到的是页面根本打不开（前端会显示「未连接网关」）。
     */
    JSONObject statusJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("started", true);
            o.put("version", VERSION);
            o.put("localKey", localKey());
            o.put("baseUrl", "http://127.0.0.1:" + port() + "/v1");
            // 顶栏「已登录 xxx」取当前活跃账号的展示名（任一供应商）
            String who = "";
            for (AccountManager.Meta m : AccountManager.PROVIDERS) {
                AccountStore b = accounts.bucket(m.id);
                if (b == null) continue;
                AccountStore.Bucket bk = b.load();
                AccountStore.Account a = bk.active();
                if (a != null && !a.label.isEmpty()) {
                    who = a.label + "（" + m.name + "）";
                    break;
                }
            }
            o.put("displayName", who);
            o.put("configured", !who.isEmpty());
        } catch (Exception ignored) { }
        return o;
    }

    /** 空串归一成 null（前端可能回传 accountId: ""）。 */
    private static String nullable(String s) {
        return s == null || s.trim().isEmpty() ? null : s;
    }

    /** 本地网关密钥（首次生成并持久化）。 */
    public synchronized String localKey() {
        String key = store.get("JETHUB_LOCAL_KEY");
        if (key == null || key.isEmpty()) {
            byte[] buf = new byte[24];
            new java.security.SecureRandom().nextBytes(buf);
            StringBuilder sb = new StringBuilder();
            for (byte b : buf) sb.append(String.format("%02x", b));
            key = sb.toString();
            store.set("JETHUB_LOCAL_KEY", key);
        }
        return key;
    }

    public synchronized void start() throws IOException {
        if (running) return;
        // 必须显式绑 IPv4 回环：getLoopbackAddress() 在部分 Android 上返回 ::1，
        // 那样只监听 IPv6，浏览器访问 http://127.0.0.1:PORT 会连不上。
        serverSocket = new ServerSocket(port(), 64, java.net.InetAddress.getByName("127.0.0.1"));
        running = true;
        Thread acceptor = new Thread(() -> {
            while (running) {
                try {
                    Socket sock = serverSocket.accept();
                    pool.execute(() -> handle(sock));
                } catch (IOException e) {
                    if (running) { /* 继续接受 */ }
                }
            }
        }, "jethub-gw-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    public synchronized void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) { }
    }

    // ── HTTP 解析与分发 ──

    private void handle(Socket sock) {
        try {
            sock.setSoTimeout(600_000); // 长对话 SSE
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    sock.getInputStream(), StandardCharsets.ISO_8859_1));
            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.isEmpty()) { sock.close(); return; }
            String[] parts = requestLine.split(" ");
            String method = parts[0];
            String path = parts.length > 1 ? parts[1] : "/";
            String query = null;
            int qIdx = path.indexOf('?');
            if (qIdx >= 0) {
                query = path.substring(qIdx + 1);
                path = path.substring(0, qIdx);
            }

            java.util.Map<String, String> headers = new java.util.HashMap<>();
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                int idx = line.indexOf(':');
                if (idx > 0) {
                    headers.put(line.substring(0, idx).trim().toLowerCase(), line.substring(idx + 1).trim());
                }
            }
            int contentLength = 0;
            String cl = headers.get("content-length");
            if (cl != null) { try { contentLength = Integer.parseInt(cl); } catch (NumberFormatException ignored) { } }
            char[] bodyChars = new char[contentLength];
            if (contentLength > 0) {
                int off = 0;
                while (off < contentLength) {
                    int n = reader.read(bodyChars, off, contentLength - off);
                    if (n < 0) break;
                    off += n;
                }
            }
            String body = new String(bodyChars, 0, contentLength);

            OutputStream out = new BufferedOutputStream(sock.getOutputStream());
            route(method, path, query, headers, body, out);
            out.flush();
            sock.close();
        } catch (Exception e) { e.printStackTrace();
            try { sock.close(); } catch (IOException ignored) { }
        }
    }

    private void route(String method, String path, String query, java.util.Map<String, String> headers,
                       String body, OutputStream out) throws IOException {
        try {
            // 本地登录管理端点不做 Bearer 校验（loopback only）
            if ("/".equals(path) || "/index.html".equals(path)) {
                handleHome(out);
                return;
            }
            if (path.startsWith("/jethub/")) {
                handleAdmin(method, path, body, out, query);
                return;
            }
            String auth = headers.get("authorization");
            String expect = "Bearer " + localKey();
            if (auth == null || !auth.equals(expect)) {
                sendJson(out, 401, new JSONObject()
                        .put("error", new JSONObject()
                                .put("message", "Missing or invalid API key")
                                .put("type", "authentication_error")
                                .put("code", "invalid_api_key")).toString());
                return;
            }
            if ("/v1/models".equals(path) && "GET".equals(method)) {
                handleModels(out);
            } else if ("/v1/chat/completions".equals(path) && "POST".equals(method)) {
                handleChat(body, out);
            } else {
                sendJson(out, 404, new JSONObject().put("error", "not found").toString());
            }
        } catch (Exception e) {
            try {
                JSONObject err = new JSONObject();
                err.put("error", new JSONObject().put("message", String.valueOf(e.getMessage())));
                sendJson(out, 502, err.toString());
            } catch (Exception ignored) { }
        }
    }

    private void handleAdmin(String method, String path, String body, OutputStream out, String query) throws IOException {
        try {
            if ("/jethub/status".equals(path)) {
                sendJson(out, 200, statusJson().toString());
            } else if ("/jethub/accounts".equals(path)) {
                // withCredits=true 会逐账号打上游查余额/签到（带 60s 缓存）
                boolean credits = query != null && query.contains("credits=1");
                sendJson(out, 200, accounts.accountsJson(credits).toString());
            } else if (path.startsWith("/jethub/accounts/") && "POST".equals(method)) {
                String action = path.substring("/jethub/accounts/".length());
                JSONObject req = body == null || body.trim().isEmpty() ? new JSONObject() : new JSONObject(body);
                sendJson(out, 200, accounts.action(action,
                        req.optString("provider", ""), nullable(req.optString("accountId", ""))).toString());
            } else if ("/jethub/checkin/status".equals(path) && "POST".equals(method)) {
                JSONObject req = body == null || body.trim().isEmpty() ? new JSONObject() : new JSONObject(body);
                sendJson(out, 200, accounts.fetchCheckin(req.optString("provider", ""),
                        nullable(req.optString("accountId", ""))).toString());
            } else if ("/jethub/checkin".equals(path) && "POST".equals(method)) {
                JSONObject req = body == null || body.trim().isEmpty() ? new JSONObject() : new JSONObject(body);
                sendJson(out, 200, accounts.checkin(req.optString("provider", ""),
                        nullable(req.optString("accountId", ""))).toString());
            } else if ("/jethub/checkin/all".equals(path) && "POST".equals(method)) {
                sendJson(out, 200, accounts.checkinAll().toString());
            } else if ("/jethub/trae".equals(path)) {
                TraeProvider.Credential t = trae.loadCredential();
                JSONObject o = new JSONObject();
                o.put("configured", t != null);
                if (t != null) {
                    o.put("expired", trae.isExpired(t));
                    if (t.nickname != null && !t.nickname.isEmpty()) o.put("displayName", t.nickname);
                    if (t.email != null && !t.email.isEmpty()) o.put("email", t.email);
                }
                sendJson(out, 200, o.toString());
            } else if ("/jethub/mm".equals(path)) {
                MinimaxProvider.Credential mc = minimax.loadCredential();
                JSONObject o = new JSONObject();
                o.put("configured", mc != null);
                if (mc != null) {
                    o.put("expired", minimax.isExpired(mc));
                }
                sendJson(out, 200, o.toString());
            } else if ("/jethub/login/start".equals(path) && "POST".equals(method)) {
                ClineProvider.DeviceAuthorization a = cline.startDeviceLogin();
                loginSession = a;
                startAutoPoll(a);
                sendJson(out, 200, new JSONObject()
                        .put("verification_uri", a.verificationUri)
                        .put("verification_uri_complete",
                                a.verificationUriComplete.isEmpty() ? a.verificationUri : a.verificationUriComplete)
                        .put("user_code", a.userCode)
                        .toString());
            } else if ("/jethub/login/poll".equals(path) && "POST".equals(method)) {
                ClineProvider.DeviceAuthorization a = loginSession;
                if (a == null) {
                    sendJson(out, 400, new JSONObject().put("error", "no login session").toString());
                    return;
                }
                if (System.currentTimeMillis() > a.expiresAtMs) {
                    loginSession = null;
                    sendJson(out, 200, new JSONObject().put("status", "expired").toString());
                    return;
                }
                ClineProvider.PollResult r = cline.pollDeviceLogin(a);
                String status = r.kind == ClineProvider.PollResult.DONE ? "done"
                        : r.kind == ClineProvider.PollResult.PENDING ? "pending"
                        : r.kind == ClineProvider.PollResult.SLOW_DOWN ? "slow_down"
                        : r.kind == ClineProvider.PollResult.EXPIRED ? "expired" : "denied";
                if (r.kind == ClineProvider.PollResult.DONE) loginSession = null;
                sendJson(out, 200, new JSONObject().put("status", status).toString());
            } else if ("/jethub/mm/login/start".equals(path) && "POST".equals(method)) {
                MinimaxProvider.DeviceAuthorization a = minimax.startDeviceLogin();
                mmLoginSession = a;
                startMinimaxAutoPoll(a);
                sendJson(out, 200, new JSONObject()
                        .put("verification_uri", a.verificationUri)
                        .put("verification_uri_complete", a.verificationUriComplete)
                        .put("user_code", a.userCode)
                        .toString());
            } else if ("/jethub/mm/login/poll".equals(path) && "POST".equals(method)) {
                MinimaxProvider.DeviceAuthorization a = mmLoginSession;
                if (a == null) {
                    sendJson(out, 400, new JSONObject().put("error", "no login session").toString());
                    return;
                }
                if (System.currentTimeMillis() > a.expiresAtMs) {
                    mmLoginSession = null;
                    sendJson(out, 200, new JSONObject().put("status", "expired").toString());
                    return;
                }
                MinimaxProvider.PollResult r = minimax.pollDeviceLogin(a);
                String status = r.kind == MinimaxProvider.PollResult.Kind.DONE ? "done"
                        : r.kind == MinimaxProvider.PollResult.Kind.PENDING ? "pending"
                        : r.kind == MinimaxProvider.PollResult.Kind.SLOW_DOWN ? "slow_down"
                        : r.kind == MinimaxProvider.PollResult.Kind.EXPIRED ? "expired"
                        : r.kind == MinimaxProvider.PollResult.Kind.DENIED ? "denied" : "error";
                if (r.kind == MinimaxProvider.PollResult.Kind.DONE) mmLoginSession = null;
                JSONObject jo = new JSONObject().put("status", status);
                if (r.message != null && !r.message.isEmpty()) jo.put("message", r.message);
                sendJson(out, 200, jo.toString());
            } else if ("/jethub/buddy".equals(path)) {
                BuddyProvider.Credential bc = buddy.loadCredential();
                sendJson(out, 200, new JSONObject()
                        .put("configured", bc != null)
                        .put("expired", bc != null && buddy.isExpired(bc)).toString());
            } else if ("/jethub/workbuddy".equals(path)) {
                BuddyProvider.Credential wc = workbuddy.loadCredential();
                sendJson(out, 200, new JSONObject()
                        .put("configured", wc != null)
                        .put("expired", wc != null && workbuddy.isExpired(wc)).toString());
            } else if ("/jethub/buddy/login/start".equals(path) && "POST".equals(method)
                    || "/jethub/workbuddy/login/start".equals(path) && "POST".equals(method)) {
                boolean isWb = path.startsWith("/jethub/workbuddy");
                BuddyProvider bp = isWb ? workbuddy : buddy;
                BuddyProvider.LoginSession s = bp.startLogin();
                if (isWb) workbuddyLoginSession = s; else buddyLoginSession = s;
                startBuddyAutoPoll(bp, s, isWb);
                sendJson(out, 200, new JSONObject()
                        .put("auth_url", s.authUrl).put("state", s.state).toString());
            } else if ("/jethub/trae/login/start".equals(path) && "POST".equals(method)) {
                TraeProvider.Credential device = trae.deviceIdentity();
                String loginUrl = trae.buildLoginUrl(device, "http://127.0.0.1:" + port() + "/jethub/trae/callback");
                sendJson(out, 200, new JSONObject().put("auth_url", loginUrl).toString());
            } else if ("/jethub/trae/callback".equals(path)) {
                try {
                    String name = trae.handleLoginCallback(query != null ? query : "");
                    byte[] page = ("<html><meta charset=utf-8><body><h3>Trae 登录成功：" + name
                            + "</h3>可关闭此页。</body></html>").getBytes(StandardCharsets.UTF_8);
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: "
                            + page.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                    out.write(page);
                    out.flush();
                } catch (Exception e) {
                    sendJson(out, 502, new JSONObject().put("error", String.valueOf(e.getMessage())).toString());
                }
            } else if ("/jethub/codearts/login/start".equals(path) && "POST".equals(method)) {
                // CodeArts 走 IAM OAuth（PKCE + DPoP），由 Provider 自己起本地回调
                // 服务器；这里只把 auth_url 交给前端去开浏览器。
                CodeartsProvider.LoginSession s = codearts.startLogin();
                codeartsLoginSession = s;
                sendJson(out, 200, new JSONObject()
                        .put("auth_url", s.loginUrl)
                        .put("expires_in", Math.max(0, (s.expiresAtMs - System.currentTimeMillis()) / 1000))
                        .toString());
            } else if ("/jethub/codearts/login/poll".equals(path) && "POST".equals(method)) {
                CodeartsProvider.LoginSession s = codeartsLoginSession;
                if (s == null) {
                    sendJson(out, 400, errorJson("没有进行中的 CodeArts 登录"));
                    return;
                }
                if ("done".equals(s.status)) {
                    // 凭据已在 Provider 内写完账号桶，这里只回报状态；
                    // 清空会话避免重复轮询时重复 upsert。
                    codeartsLoginSession = null;
                    accounts.invalidate(AccountManager.P_CODEARTS);
                    sendJson(out, 200, new JSONObject().put("status", "done").toString());
                    return;
                }
                if ("error".equals(s.status)) {
                    String msg = s.message;
                    codeartsLoginSession = null;
                    sendJson(out, 200, new JSONObject().put("status", "error")
                            .put("message", msg == null ? "" : msg).toString());
                    return;
                }
                if (System.currentTimeMillis() > s.expiresAtMs) {
                    codeartsLoginSession = null;
                    CodeartsProvider.closeLogin(s);
                    sendJson(out, 200, new JSONObject().put("status", "expired").toString());
                    return;
                }
                sendJson(out, 200, new JSONObject().put("status", "pending").toString());
            } else if ("/jethub/codearts".equals(path)) {
                CodeartsProvider.Credential cc = codearts.loadCredential();
                JSONObject jo = new JSONObject()
                        .put("configured", cc != null)
                        .put("expired", cc != null && codearts.isExpired(cc))
                        .put("refreshable", cc != null && cc.isRefreshable());
                if (cc != null) {
                    JSONObject b = new JSONObject();
                    b.put("user_name", cc.userName == null ? "" : cc.userName);
                    b.put("nickname", cc.nickname == null ? "" : cc.nickname);
                    b.put("expires_at", cc.expiresAt == null ? "" : cc.expiresAt);
                    jo.put("credential", b);
                }
                sendJson(out, 200, jo.toString());
            } else if ("/jethub/models".equals(path)) {
                sendJson(out, 200, modelsJson(query).toString());
            } else if ("/jethub/opencode/key".equals(path) && "POST".equals(method)) {
                // OpenCode 没有设备码 / 扫码流程，凭据就是一条 API Key。
                // ⚠️ 匿名通道（Bearer public）已被服务端封死（2026-10-16 实测），
                //    故这里不再接受空 key —— 必须给出真实 Key，否则用户会以为配好了。
                JSONObject req = body == null || body.trim().isEmpty() ? new JSONObject() : new JSONObject(body);
                String key = req.optString("api_key", "").trim();
                String nickname = req.optString("nickname", "").trim();
                if (key.isEmpty()) {
                    sendJson(out, 400, errorJson("请填写 OpenCode API Key（免费档位模型同样需要 Key）"));
                } else {
                    AccountStore b = accounts.bucket(AccountManager.P_OPENCODE);
                    if (b == null) {
                        sendJson(out, 500, errorJson("opencode 账号桶不可用"));
                    } else {
                        // 先写入凭据再回列模型，这样 UI 能立刻拿到该 key 可见的目录
                        JSONObject p = new JSONObject();
                        p.put("api_key", key);
                        p.put("nickname", nickname);
                        p.put("generation", 0);
                        AccountStore.Account saved = b.upsert(p, null);
                        accounts.invalidate(AccountManager.P_OPENCODE);
                        JSONObject ok = new JSONObject();
                        ok.put("ok", true);
                        ok.put("id", saved == null ? "" : saved.id);
                        ok.put("label", saved == null ? "" : saved.label);
                        sendJson(out, 200, ok.toString());
                    }
                }
            } else if (path.startsWith("/jethub/models/") && "POST".equals(method)) {
                String action = path.substring("/jethub/models/".length());
                JSONObject req = body == null || body.trim().isEmpty() ? new JSONObject() : new JSONObject(body);
                sendJson(out, 200, modelsAction(action, req).toString());
            } else if ("/jethub/logout".equals(path) && "POST".equals(method)) {
                // 登出当前活跃的 Cline 账号（管理页那个按钮只针对 Cline）
                AccountStore cb = accounts.bucket(AccountManager.P_CLINE);
                AccountStore.Account ca = cb == null ? null : cb.load().active();
                if (ca != null) cb.remove(ca.id);
                sendJson(out, 200, new JSONObject().put("status", "ok").toString());
            } else {
                sendJson(out, 404, new JSONObject().put("error", "not found").toString());
            }
        } catch (Exception e) {
            try {
                sendJson(out, 502, new JSONObject().put("error", String.valueOf(e.getMessage())).toString());
            } catch (Exception ignored) { }
        }
    }

    /**
     * 模型清单（管理页用）：**不套黑名单**，每项带 enabled 标记。
     *
     * <p>为什么设置页要显示已关闭的模型：用户关掉一个模型之后还得能再打开它，
     * 若这里也过滤掉，被关的模型就从界面上永远消失了。这与 jethub 的
     * {@code model.list} 语义一致（注释明写「设置页不套黑名单」）。
     *
     * @param query 支持 {@code provider=xxx} 只看一家
     */
    JSONObject modelsJson(String query) throws Exception {
        String only = null;
        if (query != null) {
            for (String kv : query.split("&")) {
                int eq = kv.indexOf('=');
                if (eq > 0 && "provider".equals(kv.substring(0, eq))) {
                    only = java.net.URLDecoder.decode(kv.substring(eq + 1), "UTF-8");
                }
            }
        }
        JSONObject out = new JSONObject();
        JSONArray groups = new JSONArray();
        java.util.Map<String, JSONArray> byProvider = new java.util.LinkedHashMap<>();
        JSONArray all = catalogAll();
        for (int i = 0; i < all.length(); i++) {
            JSONObject m = all.optJSONObject(i);
            if (m == null) continue;
            String id = m.optString("id", "");
            if (id.isEmpty()) continue;
            String provider = m.optString("owned_by", providerOf(id));
            if (only != null && !only.isEmpty() && !only.equals(provider)) continue;
            JSONArray arr = byProvider.get(provider);
            if (arr == null) { arr = new JSONArray(); byProvider.put(provider, arr); }
            JSONObject item = new JSONObject();
            item.put("id", id);
            item.put("name", m.optString("name", id));
            item.put("enabled", !models.isDisabled(provider, id));
            arr.put(item);
        }
        for (java.util.Map.Entry<String, JSONArray> e : byProvider.entrySet()) {
            JSONObject g = new JSONObject();
            g.put("provider", e.getKey());
            g.put("label", providerLabel(e.getKey()));
            JSONArray arr = e.getValue();
            int on = 0;
            for (int i = 0; i < arr.length(); i++) {
                if (arr.optJSONObject(i) != null && arr.optJSONObject(i).optBoolean("enabled", true)) on++;
            }
            g.put("count", arr.length());
            g.put("enabledCount", on);
            g.put("models", arr);
            groups.put(g);
        }
        out.put("groups", groups);
        return out;
    }

    /** 模型管理动作：set / set-many / reset。 */
    JSONObject modelsAction(String action, JSONObject req) throws Exception {
        JSONObject out = new JSONObject();
        String provider = req.optString("provider", "");
        if (provider.isEmpty()) {
            out.put("ok", false);
            out.put("message", "缺少 provider");
            return out;
        }
        boolean enabled = req.optBoolean("enabled", true);
        if ("set".equals(action)) {
            String id = req.optString("modelId", "");
            if (id.isEmpty()) {
                out.put("ok", false).put("message", "缺少 modelId");
                return out;
            }
            models.setDisabled(provider, id, !enabled);
            out.put("ok", true);
        } else if ("set-many".equals(action)) {
            JSONArray ids = req.optJSONArray("modelIds");
            java.util.List<String> list = new java.util.ArrayList<>();
            if (ids != null) for (int i = 0; i < ids.length(); i++) list.add(ids.optString(i, ""));
            models.setDisabledMany(provider, list, !enabled);
            out.put("ok", true);
            out.put("count", list.size());
        } else if ("reset".equals(action)) {
            models.clear(provider);
            out.put("ok", true);
        } else {
            out.put("ok", false);
            out.put("message", "未知操作：" + action);
            return out;
        }
        out.put("disabled", new JSONArray(models.disabled(provider)));
        return out;
    }

    /** 供应商 id → 展示名（沿用账号页的元数据）。 */
    static String providerLabel(String providerId) {
        AccountManager.Meta m = AccountManager.metaOf(providerId);
        return m == null ? providerId : m.name;
    }

    /**
     * 登录自动轮询：/jethub/login/start 之后后台线程按 interval 轮询直到完成/过期。
     * 用户只需在浏览器打开授权链接，无需再手动触发 poll。
     */
    private void startAutoPoll(ClineProvider.DeviceAuthorization a) {
        Thread t = new Thread(() -> {
            long interval = a.intervalMs > 0 ? a.intervalMs : 5000;
            while (loginSession == a && System.currentTimeMillis() < a.expiresAtMs) {
                try {
                    Thread.sleep(interval);
                } catch (InterruptedException e) {
                    return;
                }
                if (loginSession != a) return; // 已被新会话取代或已登出
                try {
                    ClineProvider.PollResult r = cline.pollDeviceLogin(a);
                    if (r.kind == ClineProvider.PollResult.DONE) {
                        loginSession = null;
                        return;
                    }
                    if (r.kind == ClineProvider.PollResult.SLOW_DOWN) {
                        interval += 1000;
                    } else if (r.kind == ClineProvider.PollResult.EXPIRED
                            || r.kind == ClineProvider.PollResult.DENIED) {
                        loginSession = null;
                        return;
                    }
                } catch (Exception ignored) { }
            }
            if (loginSession == a) loginSession = null;
        }, "jethub-login-poll");
        t.setDaemon(true);
        t.start();
    }

    /** Buddy/WorkBuddy 浏览器授权自动轮询：1s 间隔直到 done/过期/出错。 */
    private void startBuddyAutoPoll(BuddyProvider bp, BuddyProvider.LoginSession s, boolean isWb) {
        Thread t = new Thread(() -> {
            while (System.currentTimeMillis() < s.expiresAtMs) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }
                try {
                    BuddyProvider.LoginPollResult r = bp.pollLogin(s);
                    if (r.kind == BuddyProvider.LoginPollResult.Kind.DONE) return;
                    if (r.kind == BuddyProvider.LoginPollResult.Kind.ERROR) return;
                } catch (Exception ignored) { }
            }
        }, isWb ? "jethub-workbuddy-login-poll" : "jethub-buddy-login-poll");
        t.setDaemon(true);
        t.start();
    }

    /** MiniMax 设备码登录自动轮询（interval 缺省 5s，slow_down +5s）。 */
    private void startMinimaxAutoPoll(MinimaxProvider.DeviceAuthorization a) {
        Thread t = new Thread(() -> {
            long interval = a.intervalMs > 0 ? a.intervalMs : 5000;
            while (mmLoginSession == a && System.currentTimeMillis() < a.expiresAtMs) {
                try {
                    Thread.sleep(interval);
                } catch (InterruptedException e) {
                    return;
                }
                if (mmLoginSession != a) return;
                try {
                    MinimaxProvider.PollResult r = minimax.pollDeviceLogin(a);
                    if (r.kind == MinimaxProvider.PollResult.Kind.DONE) {
                        mmLoginSession = null;
                        return;
                    }
                    if (r.kind == MinimaxProvider.PollResult.Kind.SLOW_DOWN) {
                        interval += 5000;
                    } else if (r.kind == MinimaxProvider.PollResult.Kind.EXPIRED
                            || r.kind == MinimaxProvider.PollResult.Kind.DENIED) {
                        mmLoginSession = null;
                        return;
                    }
                } catch (Exception ignored) { }
            }
            if (mmLoginSession == a) mmLoginSession = null;
        }, "jethub-mm-login-poll");
        t.setDaemon(true);
        t.start();
    }

    /** 手机浏览器直接打开 http://127.0.0.1:8391/ 的管理页（资源见 HomePage）。 */
    private void handleHome(OutputStream out) throws IOException {
        String html = HomePage.load();
        byte[] payload = html.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n"
                + "Cache-Control: no-store\r\n"
                + "Content-Length: " + payload.length + "\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(payload);
        out.flush();
    }

    /** 旧版内置页（保留在源码里备查，不再对外提供）。 */
    @SuppressWarnings("unused")
    private String legacyHomeHtml() {
        String html = "<!DOCTYPE html><html lang=zh><head><meta charset=utf-8>"
                + "<meta name=viewport content='width=device-width,initial-scale=1'>"
                + "<title>JetHub 内置网关</title>"
                + "<style>body{font-family:sans-serif;max-width:640px;margin:24px auto;padding:0 16px;color:#222}"
                + "button{padding:10px 18px;font-size:16px;margin:8px 0}"
                + "code{background:#f2f2f2;padding:2px 6px;word-break:break-all;display:block;margin:4px 0}"
                + "a{word-break:break-all}</style></head><body>"
                + "<h2>JetHub 内置网关</h2><div id=s>加载中…</div>"
                + "<button onclick=login()>① 登录 Cline</button>"
                + "<div id=login></div>"
                + "<button onclick=mmlogin()>② 登录 MiniMax Code</button>"
                + "<div id=mm></div>"
                + "<button onclick=bdlogin('buddy')>③ 登录 CodeBuddy（腾讯）</button>"
                + "<button onclick=bdlogin('workbuddy')>④ 登录 WorkBuddy</button>"
                + "<div id=bd></div>"
                + "<button onclick=traelogin()>⑤ 登录 Trae（短信/扫码）</button>"
                + "<div id=trae></div>"
                + "<h3>⑥ 在墨听 AI 模型中心添加中转账号</h3>"
                + "<p>Base URL 与 API Key 如下（也可粘贴到自定义中转）：</p>"
                + "<code id=key>加载中…</code>"
                + "<script>"
                + "function j(r){return r.json()}"
                + "function refresh(){fetch('/jethub/status').then(j).then(s=>{"
                + "document.getElementById('s').textContent=s.configured?('已登录：'+(s.displayName||s.email||'Cline')):'未登录';"
                + "document.getElementById('key').textContent=s.baseUrl+'  |  Key: '+s.localKey;})}"
                + "function login(){fetch('/jethub/login/start',{method:'POST'}).then(j).then(s=>{"
                + "document.getElementById('login').innerHTML='打开授权页并输入 <b>'+s.user_code+'</b>：<br><a href=\"'+s.verification_uri_complete+'\">'+s.verification_uri_complete+'</a>';"
                + "let n=0;const t=setInterval(()=>{fetch('/jethub/status').then(j).then(st=>{"
                + "if(st.configured){clearInterval(t);document.getElementById('s').textContent='已登录：'+(st.displayName||st.email);"
                + "document.getElementById('login').innerHTML='<b>登录成功</b>';}else if(++n>120){clearInterval(t);}})},3000);})}"
                + "refresh();"
                + "function mmlogin(){fetch('/jethub/mm/login/start',{method:'POST'}).then(j).then(s=>{"
                + "document.getElementById('mm').innerHTML='打开授权页并输入 <b>'+s.user_code+'</b>：<br><a href=\"'+s.verification_uri_complete+'\">'+s.verification_uri_complete+'</a>';"
                + "let n=0;const t=setInterval(()=>{fetch('/jethub/mm').then(j).then(st=>{"
                + "if(st.configured){clearInterval(t);document.getElementById('mm').innerHTML='<b>MiniMax 登录成功</b>';}else if(++n>120){clearInterval(t);}})},5000);})}"
                + "function bdlogin(name){fetch('/jethub/'+name+'/login/start',{method:'POST'}).then(j).then(s=>{"
                + "document.getElementById('bd').innerHTML='在浏览器打开授权页完成 <b>'+name+'</b> 登录：<br><a href=\"'+s.auth_url+'\">'+s.auth_url+'</a><br>（完成后自动写入凭据，约 1-3 秒后刷新本页）';"
                + "let n=0;const t=setInterval(()=>{fetch('/jethub/'+name).then(j).then(st=>{"
                + "if(st.configured){clearInterval(t);document.getElementById('bd').innerHTML='<b>'+name+' 登录成功</b>';}else if(++n>300){clearInterval(t);}})},1000);})}"
                + "function traelogin(){fetch('/jethub/trae/login/start',{method:'POST'}).then(j).then(s=>{"
                + "document.getElementById('trae').innerHTML='在浏览器打开 Trae 授权页完成登录（短信/扫码均可）：<br><a href=\"'+s.auth_url+'\">'+s.auth_url+'</a><br>授权后自动跳回本页完成凭据写入。';})}"
                + "</script></body></html>";
        return html;
    }

    private void handleModels(OutputStream out) throws Exception {
        JSONObject result = new JSONObject();
        result.put("object", "list");
        result.put("data", catalogData());
        sendJson(out, 200, result.toString());
    }

    /**
     * 聚合全部供应商目录（含兜底），任何一段失败跳过。
     *
     * <p>返回的是**可用目录**：已在模型开关表里关闭的条目被剔除。墨听用它列模型，
     * 用户关掉某个模型后它就真的从模型中心消失。管理页要显示全部（含已关闭），
     * 用 {@link #catalogAll()}。
     */
    JSONArray catalogData() throws Exception {
        JSONArray all = catalogAll();
        JSONArray outData = new JSONArray();
        for (int i = 0; i < all.length(); i++) {
            JSONObject m = all.optJSONObject(i);
            if (m == null) continue;
            String id = m.optString("id", "");
            String provider = m.optString("owned_by", providerOf(id));
            if (models.isDisabled(provider, id)) continue;
            outData.put(m);
        }
        return outData;
    }

    /** 从 {@code provider/model} 形态的 id 里取供应商段。 */
    static String providerOf(String id) {
        if (id == null) return "";
        int slash = id.indexOf('/');
        return slash > 0 ? id.substring(0, slash) : "";
    }

    /** 不套黑名单的全量目录（管理页的模型清单用，要能显示并重新打开已关闭的模型）。 */
    JSONArray catalogAll() throws Exception {
        JSONArray outData = new JSONArray();
        // Trae 模型（trae/<config_name>），失败不阻塞其他家
        try {
            JSONObject traeModels = trae.listModels();
            JSONArray td = traeModels.optJSONArray("data");
            if (td != null) for (int i = 0; i < td.length(); i++) {
                JSONObject m = td.optJSONObject(i);
                if (m != null) {
                    m.put("id", "trae/" + m.optString("id", ""));
                    outData.put(m);
                }
            }
        } catch (IllegalStateException e) {
            // 未登录/凭据失效：跳过 trae 段
        }
        // Buddy / WorkBuddy 模型，失败各自跳过
        appendBuddyModels(outData, buddy);
        appendBuddyModels(outData, workbuddy);
        // MiniMax 模型，失败跳过
        try {
            JSONObject mm = minimax.listModels();
            JSONArray data = mm.optJSONArray("data");
            if (data != null) for (int i = 0; i < data.length(); i++) {
                JSONObject m = data.optJSONObject(i);
                if (m != null) outData.put(m);
            }
        } catch (Exception e) {
            // 未登录 minimax：跳过
        }
        // OpenCode 模型。⚠️ 它的 listModels 内部已经把远端目录按可达性过滤过
        // （远端 /v1/models 返回 84+ 个含 claude-*/gpt-* 等我们未实现协议的模型）。
        // ⚠️ 匿名通道已被服务端封死（2026-10-16 实测），故配置了有效 API Key
        // 才会列出模型；未配置时这里会跳过（不再有「零配置免登录可用」这条路）。
        // provider 为 null 只发生在不传 AccountManager 的旧测试构造器下。
        if (opencode != null) {
            try {
                JSONObject oc = opencode.listModels();
                JSONArray od = oc.optJSONArray("data");
                if (od != null) for (int i = 0; i < od.length(); i++) {
                    JSONObject m = od.optJSONObject(i);
                    if (m != null) outData.put(m);
                }
            } catch (Exception e) {
                // 未配置或上游不可用：跳过（Provider 内部已回落兜底表，走到这里说明连兜底都失败）
            }
        }
        // CodeArts 模型。⚠️ 它的 listModels 会先判凭据是否存在/可续期，再合并
        // 两个远端目录（benefit 用 opengw gateway/config，常规用 snap /v1/model/builtin）。
        // 未登录或 SecurityToken 无法续期时抛异常，这里跳过即可 —— 账号没配好
        // 不该让整个模型目录为空。
        // provider 为 null 只发生在不传 AccountManager 的旧测试构造器下。
        if (codearts != null) {
            try {
                JSONObject ca = codearts.listModels();
                JSONArray cd = ca.optJSONArray("data");
                if (cd != null) for (int i = 0; i < cd.length(); i++) {
                    JSONObject m = cd.optJSONObject(i);
                    if (m != null) outData.put(m);
                }
            } catch (Exception e) {
                // 未登录/凭据已失效：跳过
            }
        }
        // Cline 模型，同样失败跳过（任何一段都不阻塞整体目录）
        try {
            ClineProvider.Credential c = cline.ensureFresh();
            java.util.Map<String, String> headers = cline.authHeaders(c);
            HttpFetcher.Response resp = HttpFetcher.get(cline.modelsUrl(), headers, ClineProvider.HTTP_TIMEOUT_MS);
            String text = resp.readText();
            if (resp.status == 200) {
                JSONObject upstream = new JSONObject(text);
                JSONArray data = upstream.optJSONArray("data");
                if (data != null) {
                    for (int i = 0; i < data.length(); i++) {
                        JSONObject m = data.optJSONObject(i);
                        if (m == null) continue;
                        String id = m.optString("id", "");
                        if (id.isEmpty()) continue;
                        JSONObject nm = new JSONObject();
                        nm.put("id", "cline/" + id);
                        nm.put("object", "model");
                        nm.put("owned_by", "cline");
                        String label = m.optString("name", "");
                        if (label.isEmpty()) {
                            int slash = id.lastIndexOf('/');
                            label = slash >= 0 ? id.substring(slash + 1) : id;
                        }
                        // Cline 用 id 形态标免费（:free 结尾 / cline-free/ 前缀），
                        // 或上游显式 isFree。拼进 name 才能让用户在模型中心一眼看出哪个不花钱。
                        String withFree = ModelRate.clineDisplayName(label, ModelRate.clineIsFree(id, m));
                        nm.put("name", withFree == null ? label : withFree);
                        if (m.has("created")) nm.put("created", m.optLong("created"));
                        outData.put(nm);
                    }
                }
            }
        } catch (Exception e) {
            // 未登录 Cline / 上游失败：跳过（TokenExpiredException 是受检异常）
        }
        // 免费模型兜底表（远端 recommended-models 与 /models 不含 cline-free/*）
        String[][] fallback = {
                {"stealth/space-bunny-alpha", "Space Bunny Alpha"},
                {"cline-free/mimo-v2.6-flash", "MiMo-V2.6-Flash"},
                {"cline-free/muse-spark-1.3-contributor", "Muse Spark 1.3 Contributor"},
        };
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < outData.length(); i++) seen.add(outData.getJSONObject(i).getString("id"));
        for (String[] f : fallback) {
            String id = "cline/" + f[0];
            if (seen.contains(id)) continue;
            JSONObject nm = new JSONObject();
            nm.put("id", id);
            nm.put("object", "model");
            nm.put("owned_by", "cline");
            // 兜底表原来只写 id、不写 name —— 墨听会退化成显示 canonical 生成的
            // 展示名（一串 model_xxxx 哈希），用户完全认不出是哪个模型。
            String withFree = ModelRate.clineDisplayName(f[1], ModelRate.clineIsFree(f[0], null));
            nm.put("name", withFree == null ? f[1] : withFree);
            outData.put(nm);
        }
        return outData;
    }

    /** 供模型中心注册器使用：标准 OpenAI 目录 JSON。 */
    public JSONObject catalogJson() throws Exception {
        JSONObject r = new JSONObject();
        r.put("object", "list");
        r.put("data", catalogData());
        return r;
    }

    /** 追加一家的模型目录（带前缀），任何失败静默跳过。 */
    private void appendBuddyModels(JSONArray outData, BuddyProvider bp) {
        try {
            JSONObject models = bp.listModels();
            JSONArray d = models.optJSONArray("data");
            if (d == null) return;
            for (int i = 0; i < d.length(); i++) {
                JSONObject m = d.optJSONObject(i);
                if (m != null) outData.put(m);
            }
        } catch (Exception e) {
            // 未登录/目录为空：跳过该段
        }
    }

    private void handleChat(String body, OutputStream out) throws Exception {
        JSONObject req = new JSONObject(body);
        String model = req.optString("model", "");
        if (model.startsWith("trae/")) {
            handleTraeChat(req, model.substring("trae/".length()), out);
            return;
        }
        if (model.startsWith("buddy/") || model.startsWith("workbuddy/")) {
            handleBuddyChat(req, model, out);
            return;
        }
        if (model.startsWith("minimax/")) {
            handleMinimaxChat(req, model.substring("minimax/".length()), out);
            return;
        }
        if (model.startsWith("opencode/")) {
            handleOpencodeChat(req, model.substring("opencode/".length()), out);
            return;
        }
        if (model.startsWith("codearts/")) {
            handleCodeartsChat(req, model.substring("codearts/".length()), out);
            return;
        }
        // 剥掉 cline/ 前缀
        String upstreamModel = model.startsWith("cline/") ? model.substring("cline/".length()) : model;
        req.put("model", upstreamModel);
        boolean stream = req.optBoolean("stream", false);

        ClineProvider.Credential c = cline.ensureFresh();
        java.util.Map<String, String> headers = cline.authHeaders(c);
        headers.put("Content-Type", "application/json");
        headers.remove("Accept");

        HttpFetcher.Response resp = HttpFetcher.fetch("POST", cline.chatUrl(), headers,
                req.toString().getBytes(StandardCharsets.UTF_8), 600_000);
        if (resp.status != 200) {
            String err = resp.readText();
            sendJson(out, resp.status >= 400 ? resp.status : 502, err);
            return;
        }
        if (stream) {
            // SSE 逐块透传（墨听按 OpenAI 流式解析）
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n"
                    + "Cache-Control: no-cache\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            try (InputStream in = resp.body) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    out.flush();
                }
            } finally {
                resp.close();
            }
        } else {
            String text = resp.readText();
            // Cline 非流式响应包了 {"data":{...}} 信封，归一成标准 OpenAI 形状
            try {
                JSONObject o = new JSONObject(text);
                if (o.has("data") && o.optJSONObject("data") != null && o.optJSONObject("data").has("choices")) {
                    text = o.getJSONObject("data").toString();
                }
            } catch (Exception ignored) { }
            sendJson(out, 200, text);
        }
    }

    /** Trae 对话：SOLO body → 自定义 SSE，转成标准 OpenAI（流式/非流式）返回。 */
    private void handleTraeChat(JSONObject req, String model, OutputStream out) throws Exception {
        boolean stream = req.optBoolean("stream", false);
        TraeProvider.Credential c = trae.ensureFresh();
        JSONObject soloBody = trae.toSoloBody(req, model);
        HttpFetcher.Response resp = trae.chatRaw(c, soloBody);
        if (resp.status != 200) {
            String err = resp.readText();
            sendJson(out, resp.status >= 400 ? resp.status : 502, err);
            return;
        }
        String chunkId = "chatcmpl-trae-" + Long.toHexString(System.currentTimeMillis());
        if (stream) {
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n"
                    + "Cache-Control: no-cache\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            final OutputStream fout = out;
            try {
                TraeSseConverter.streamToOpenAi(model, chunkId, resp.body, s -> {
                    fout.write(s.getBytes(StandardCharsets.UTF_8));
                    fout.flush();
                });
            } finally {
                resp.close();
            }
        } else {
            String aggregated;
            try {
                aggregated = TraeSseConverter.aggregate(model, resp.body);
            } finally {
                resp.close();
            }
            sendJson(out, 200, aggregated);
        }
    }

    /** Buddy/WorkBuddy 对话：标准 OpenAI 请求直发（流式透传 / 非流式剥 {data:...} 信封）。 */
    /** MiniMax：OpenAI 请求 → Anthropic messages，SSE → OpenAI chunk/聚合。 */
    private void handleMinimaxChat(JSONObject req, String model, OutputStream out) throws Exception {
        boolean stream = req.optBoolean("stream", false);
        MinimaxProvider.Credential c = minimax.ensureFresh();
        JSONObject payload = MinimaxProvider.toMessagesPayload(req);
        payload.put("model", model);
        HttpFetcher.Response resp = minimax.chatRaw(c, payload);
        if (resp.status != 200) {
            String err = resp.readText();
            sendJson(out, resp.status >= 400 ? resp.status : 502, err);
            return;
        }
        if (!stream) {
            String text = resp.readText();
            resp.close();
            sendJson(out, 200, MinimaxSseConverter.aggregate(model, text).toString());
            return;
        }
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n"
                + "Cache-Control: no-cache\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
        String chunkId = "chatcmpl-jethub-minimax";
        boolean first = true;
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(resp.body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if (data.isEmpty() || "[DONE]".equals(data)) continue;
                String[] delta;
                try {
                    delta = MinimaxSseConverter.parseFrame(data);
                } catch (MinimaxSseConverter.MinimaxStreamError e) {
                    // 流内错误：以 OpenAI 错误事件形式下发后终止
                    JSONObject eo = new JSONObject();
                    eo.put("error", new JSONObject().put("message", e.getMessage()));
                    out.write(("data: " + eo + "\n\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    break;
                }
                if (delta[0].isEmpty() && delta[1].isEmpty()) continue;
                JSONObject chunk = openAiChunk(chunkId, model, first, delta[0], delta[1]);
                first = false;
                out.write(("data: " + chunk + "\n\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } finally {
            out.write("data: [DONE]\n\n".getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            resp.close();
        }
    }

    /**
     * OpenCode Zen 对话转发。
     *
     * <p>与其余五家最大的不同：它是<b>标准 OpenAI 兼容</b>，响应就是标准 OpenAI SSE，
     * 故流式可原样透传、非流式由 {@link OpencodeProvider#aggregate} 收口，
     * 不需要新建 SseConverter。
     *
     * <p>但有两处必须由 Provider 侧保证，缺一则匿名通道一律 403：
     * ① 五个 {@code x-opencode-*} 指纹头；② body 的 FreeTier 形状门禁
     * （{@code stream:true} + {@code tools} 含 {@code bash}/{@code read}）。
     * 两者都在 {@link OpencodeProvider#buildPayload} 与
     * {@link OpencodeProvider#headers} 里处理，这里只负责转发与错误文案。
     */
    private void handleOpencodeChat(JSONObject req, String model, OutputStream out) throws Exception {
        boolean stream = req.optBoolean("stream", false);
        OpencodeProvider.Credential c;
        try {
            c = opencode.ensureFresh();
        } catch (Exception e) {
            sendJson(out, 400, errorJson("未配置 OpenCode 账号：" + e.getMessage()));
            return;
        }
        JSONObject payload = OpencodeProvider.buildPayload(req, model);
        String sessionId = opencode.sessionId();

        HttpFetcher.Response resp = opencode.chatRaw(c, payload, sessionId);
        if (resp.status != 200) {
            // ⚠️ 必须先读体再判断：Zen 的额度错误语义（FreeUsageLimitError 等）在响应体里，
            // 而 body 只能读一次。
            String err = resp.readText();
            resp.close();
            OpencodeProvider.ErrorInfo info = OpencodeProvider.classifyError(
                    resp.status, err, firstHeader(resp, "retry-after"));
            sendJson(out, resp.status >= 400 ? resp.status : 502,
                    errorJson(opencodeErrorMessage(info, c)));
            return;
        }
        if (!stream) {
            String text = resp.readText();
            resp.close();
            sendJson(out, 200, OpencodeProvider.aggregate(model, text).toString());
            return;
        }
        // 流式：上游已是标准 OpenAI SSE，逐块透传（墨听按 OpenAI 流式解析）
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n"
                + "Cache-Control: no-cache\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
        try (InputStream in = resp.body) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                out.flush();
            }
        } finally {
            resp.close();
        }
    }

    /**
     * 把语义化错误翻成「用户下一步该做什么」。
     *
     * <p>⚠️ 这里的措辞直接决定用户会不会做无用功：额度用尽必须说「等窗口恢复 / 换账号」，
     * 余额不足必须说「去充值」，说成「服务端故障，请重试」会让用户无限重试一个
     * 永远不会成功的请求。
     */
    private static String opencodeErrorMessage(OpencodeProvider.ErrorInfo info, OpencodeProvider.Credential c) {
        String who = c.isAnonymous() ? "匿名通道" : "当前账号";
        String detail = info.detail == null || info.detail.isEmpty() ? "" : "：" + info.detail;
        if (OpencodeProvider.KIND_FREE_USAGE_LIMIT.equals(info.kind)) {
            return "OpenCode 免费额度今日已用尽（" + who + "）" + detail
                    + " —— 明日恢复，或换一个 API Key 继续。";
        }
        if (OpencodeProvider.KIND_GO_USAGE_LIMIT.equals(info.kind)) {
            return "OpenCode 当日额度已用尽（" + who + "）" + detail + " —— 请等待额度恢复。";
        }
        if (OpencodeProvider.KIND_RATE_LIMIT.equals(info.kind)) {
            String wait = info.retryAfterMs == null ? "" : "（约 " + (info.retryAfterMs / 1000) + " 秒后可重试）";
            return "OpenCode 请求过于频繁" + wait + detail;
        }
        if (OpencodeProvider.KIND_QUOTA.equals(info.kind)) {
            return "OpenCode 账号余额不足" + detail + " —— 请到 opencode.ai 充值后再试。";
        }
        if (OpencodeProvider.KIND_AUTH.equals(info.kind)) {
            return "OpenCode 认证失败（" + who + "）" + detail
                    + " —— 该模型可能需要付费 Key，或 Key 已失效。";
        }
        if (OpencodeProvider.KIND_FREE_TIER.equals(info.kind)) {
            // 匿名通道已被服务端封死（2026-10-16 实测，权威参考实现同样如此）。
            // 这是用户的配置缺失，不是本网关的兼容性问题 —— 必须直接告诉他去填 Key，
            // 说成「本网关 bug 请反馈」会让他白等一个不会来的修复。
            return "OpenCode 匿名通道已被服务端关闭（FreeTier）" + detail
                    + " —— 请到 opencode.ai 申请 API Key 填入本卡片后再试。";
        }
        if (OpencodeProvider.KIND_TRANSPORT.equals(info.kind)) {
            return "无法连接 OpenCode" + detail + " —— 请检查网络或代理。";
        }
        return "OpenCode 服务异常" + detail;
    }

    /**
     * CodeArts（华为云）对话转发。
     *
     * <p>与其余六家最不同的三处，都必须在这里照顾到：
     * <ol>
     *   <li><b>请求要现签</b>：华为云 SDK-HMAC-SHA256 用 AK/SK/SecurityToken 逐请求
     *       签，SecurityToken 只有几小时有效期，靠 DPoP 刷新续期 —— 签名与续期都在
     *       {@link CodeartsProvider} 内，这里只负责转发与错误文案。</li>
     *   <li><b>错误可能是 HTTP 200</b>：排队/限流/额度耗尽常常以 200 + SSE
     *       {@code error_code} 下发。只看状态码会把错误当正常流，界面永远停在
     *       「运行中」且零输出 —— 这正是上游报障「CodeArts Agent 没反应」的根因。</li>
     *   <li><b>benefit 头要能回退</b>：benefit 模型必须带参与签名的 {@code maas_type}
     *       头；账号里没有 benefit 包时后端以 200 + {@code InferHub.4004.200} 拒绝，
     *       去掉该头重试一次即可走普通通道（只试一次，避免死循环）。</li>
     * </ol>
     */
    private void handleCodeartsChat(JSONObject req, String model, OutputStream out) throws Exception {
        boolean stream = req.optBoolean("stream", false);
        try {
            codearts.ensureFresh();
        } catch (Exception e) {
            sendJson(out, 400, errorJson("未配置 CodeArts 账号，或凭据已过期且无法续期："
                    + CodeartsProvider.shortErr(e) + " —— 请到管理页重新登录。"));
            return;
        }
        // 会话 id 在整个会话里必须稳定：它既是后端前缀缓存的键，也是并发
        // 会话的计数单位（并发上限报错 TM.00001041 按会话计）。
        String sessionId = CodeartsProvider.sessionIdFor(req);
        boolean wantedBenefit = CodeartsProvider.isBenefitModel(model);

        for (int attempt = 0; attempt < 2; attempt++) {
            boolean useBenefit = wantedBenefit && attempt == 0;
            HttpFetcher.Response resp = codearts.chatRaw(req, model, sessionId, useBenefit);
            if (resp.status != 200) {
                String err = resp.readText();
                resp.close();
                CodeartsProvider.ErrorInfo info = CodeartsProvider.classifyError(resp.status, err, null);
                sendJson(out, resp.status >= 400 ? resp.status : 502,
                        errorJson(codeartsErrorMessage(info, model)));
                return;
            }
            if (!stream) {
                String text = resp.readText();
                resp.close();
                if (attempt == 0 && CodeartsProvider.isBenefitNotFound(text)) {
                    continue;   // 账号无 benefit 包：去掉 maas_type 头再试一次
                }
                try {
                    sendJson(out, 200, CodeartsProvider.aggregateSse(model, text).toString());
                } catch (CodeartsProvider.QuotaExhaustedException e) {
                    sendJson(out, 429, errorJson(e.getMessage()));
                } catch (CodeartsProvider.StreamError e) {
                    sendJson(out, 502, errorJson(codeartsStreamMessage(e.code, e.getMessage(), model)));
                }
                return;
            }
            // 流式：先窥探到第一帧再决定是「200 流」还是「错误」——
            // 一旦写出 200 响应头就不能再改成错误状态码了。
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(resp.body, StandardCharsets.UTF_8));
            java.util.List<String> buffered = new java.util.ArrayList<>();
            String firstPayload = null;
            String line;
            while ((line = reader.readLine()) != null) {
                buffered.add(line);
                String t = line.trim();
                if (!t.startsWith("data:")) continue;
                String payload = t.substring(5).trim();
                if (payload.isEmpty() || "[DONE]".equals(payload)) continue;
                firstPayload = payload;
                break;
            }
            if (firstPayload == null) {
                resp.close();
                sendJson(out, 502, errorJson("CodeArts 上游未返回任何数据 —— 请稍后重试，"
                        + "或检查该模型当前是否可用。"));
                return;
            }
            String errCode = CodeartsProvider.sseErrorCode(firstPayload);
            if (errCode != null) {
                String errText = CodeartsProvider.sseErrorText(firstPayload);
                resp.close();
                if (attempt == 0 && CodeartsProvider.isBenefitNotFound(errText)) {
                    continue;   // 账号无 benefit 包：去掉 maas_type 头再试一次
                }
                if (CodeartsProvider.isQuotaExhaustedCode(errText, firstPayload)) {
                    // 额度按自然日结算：重试结果相同，必须如实报出解禁时间，
                    // 否则用户会一直等一个不会来的结果。
                    sendJson(out, 429, errorJson(
                            CodeartsProvider.quotaExhaustedMessage(model, System.currentTimeMillis())));
                } else {
                    sendJson(out, 502, errorJson(codeartsStreamMessage(errCode, errText, model)));
                }
                return;
            }
            // 确认是正常流：写出 200 与缓冲的内容，之后逐行透传。
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n"
                    + "Cache-Control: no-cache\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            boolean done = false;
            try {
                for (String l : buffered) {
                    String t = l.trim();
                    if (t.startsWith("data:") && "[DONE]".equals(t.substring(5).trim())) done = true;
                    out.write((l + "\n").getBytes(StandardCharsets.UTF_8));
                }
                out.flush();
                while (!done && (line = reader.readLine()) != null) {
                    String t = line.trim();
                    if (t.startsWith("data:")) {
                        String payload = t.substring(5).trim();
                        if ("[DONE]".equals(payload)) done = true;
                        else if (!payload.isEmpty()) {
                            String code = CodeartsProvider.sseErrorCode(payload);
                            if (code != null) {
                                // 流内错误：以 OpenAI 错误事件下发后终止。
                                // 不能静默丢弃 —— 那是「零输出卡住」的老毛病。
                                String errText = CodeartsProvider.sseErrorText(payload);
                                JSONObject eo = new JSONObject();
                                eo.put("error", new JSONObject().put("message",
                                        CodeartsProvider.isQuotaExhaustedCode(errText, payload)
                                                ? CodeartsProvider.quotaExhaustedMessage(
                                                        model, System.currentTimeMillis())
                                                : codeartsStreamMessage(code, errText, model)));
                                out.write(("data: " + eo + "\n\n").getBytes(StandardCharsets.UTF_8));
                                out.flush();
                                break;
                            }
                        }
                    }
                    out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            } finally {
                if (!done) {
                    out.write("data: [DONE]\n\n".getBytes(StandardCharsets.ISO_8859_1));
                    out.flush();
                }
                resp.close();
            }
            return;
        }
    }

    /**
     * CodeArts 错误 → 告诉用户下一步该做什么。
     *
     * <p>措辞决定用户会不会做无用功：额度耗尽要让他等重置或换模型/换号，
     * 排队要让他等（这是正常的并发限流，不是故障），凭据失效要让他重新登录。
     * 一律说成「服务端异常请重试」会让用户对着一个必然失败的结果反复点。
     */
    private static String codeartsErrorMessage(CodeartsProvider.ErrorInfo info, String model) {
        String detail = info.detail == null || info.detail.isEmpty() ? "" : "：" + info.detail;
        if (CodeartsProvider.KIND_QUOTA.equals(info.kind)) {
            return CodeartsProvider.quotaExhaustedMessage(model, System.currentTimeMillis()) + detail;
        }
        if (CodeartsProvider.KIND_QUEUE.equals(info.kind)) {
            return "CodeArts 当前并发已满（" + model + "）" + detail
                    + " —— 后端会排队放行，稍后重试即可；频繁重试不会加快。";
        }
        if (CodeartsProvider.KIND_AUTH.equals(info.kind)) {
            return "CodeArts 凭据已失效" + detail + " —— 请到管理页重新登录 CodeArts。";
        }
        if (CodeartsProvider.KIND_RATE_LIMIT.equals(info.kind)) {
            return "CodeArts 请求过于频繁（" + model + "）" + detail + " —— 请稍后重试。";
        }
        if (CodeartsProvider.KIND_CONTEXT_WINDOW.equals(info.kind)) {
            return "对话超出 " + model + " 的上下文长度" + detail + " —— 请新建对话或减少内容。";
        }
        if (CodeartsProvider.KIND_INVALID_REQUEST.equals(info.kind)) {
            return "CodeArts 拒绝了该请求" + detail + " —— 多为模型名或参数不被支持。";
        }
        if (CodeartsProvider.KIND_TRANSPORT.equals(info.kind)) {
            return "无法连接 CodeArts" + detail + " —— 请检查网络。";
        }
        return "CodeArts 服务异常" + detail;
    }

    /** 流内错误码 → 用户可读文案。 */
    private static String codeartsStreamMessage(String code, String detail, String model) {
        String d = detail == null || detail.isEmpty() ? "" : "：" + detail;
        if (CodeartsProvider.isQueueErrorCode(code)) {
            return "CodeArts 当前并发已满（" + model + "）" + d
                    + " —— 后端会排队放行，稍后重试即可。";
        }
        if (code != null && (code.contains("APIG.0602") || code.toLowerCase().contains("token"))) {
            return "CodeArts 凭据已失效" + d + " —— 请到管理页重新登录 CodeArts。";
        }
        return "CodeArts 返回错误（" + code + "）" + d;
    }

    /** 取响应头首个值（HttpFetcher 的头名已由调用方保证大小写，这里再兜一层）。 */
    private static String firstHeader(HttpFetcher.Response resp, String name) {
        try {
            for (Map.Entry<String, java.util.List<String>> e : resp.headers.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)
                        && e.getValue() != null && !e.getValue().isEmpty()) {
                    return e.getValue().get(0);
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    /** 标准 OpenAI 错误信封。 */
    private static String errorJson(String message) {
        JSONObject err = new JSONObject();
        JSONObject inner = new JSONObject();
        try {
            inner.put("message", message);
            inner.put("type", "upstream_error");
            err.put("error", inner);
        } catch (Exception ignored) { }
        return err.toString();
    }

    private JSONObject openAiChunk(String id, String model, boolean first, String content, String reasoning)
            throws org.json.JSONException {
        JSONObject chunk = new JSONObject();
        chunk.put("id", id);
        chunk.put("object", "chat.completion.chunk");
        chunk.put("model", model);
        JSONArray choices = new JSONArray();
        JSONObject choice = new JSONObject();
        choice.put("index", 0);
        JSONObject delta = new JSONObject();
        if (first) delta.put("role", "assistant");
        if (!content.isEmpty()) delta.put("content", content);
        if (!reasoning.isEmpty()) delta.put("reasoning_content", reasoning);
        choice.put("delta", delta);
        choices.put(choice);
        chunk.put("choices", choices);
        return chunk;
    }

    private void handleBuddyChat(JSONObject req, String fullModel, OutputStream out) throws Exception {
        BuddyProvider bp = fullModel.startsWith("workbuddy/") ? workbuddy : buddy;
        String model = fullModel.substring(fullModel.indexOf('/') + 1);
        req.put("model", model);
        // WorkBuddy 网关硬性要求 messages[0] 是 system，否则 400/11128（伪装成安全策略拦截）。
        if ("workbuddy".equals(bp.variantProductCode())) {
            JSONArray msgs = req.optJSONArray("messages");
            if (msgs != null && msgs.length() > 0) {
                JSONObject first = msgs.optJSONObject(0);
                if (first == null || !"system".equals(first.optString("role"))) {
                    JSONArray nm = new JSONArray();
                    nm.put(new JSONObject().put("role", "system").put("content", "You are a helpful assistant."));
                    for (int i = 0; i < msgs.length(); i++) nm.put(msgs.get(i));
                    req.put("messages", nm);
                }
            }
        }
        boolean stream = req.optBoolean("stream", false);
        // Buddy/WorkBuddy 上游硬性要求 stream:true（否则 400/11101）。
        // 客户端要非流式时，网关内部强制流式再聚合为完整 JSON。
        req.put("stream", true);
        BuddyProvider.Credential c = bp.ensureFresh();
        HttpFetcher.Response resp = bp.chatRaw(c, req);
        if (resp.status != 200) {
            String err = resp.readText();
            sendJson(out, resp.status >= 400 ? resp.status : 502, err);
            return;
        }
        if (stream) {
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n"
                    + "Cache-Control: no-cache\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            try (InputStream in = resp.body) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    out.flush();
                }
            } finally {
                resp.close();
            }
        } else {
            // 上游是 SSE（强制流式），聚合为标准非流式响应
            String text = resp.readText();
            resp.close();
            sendJson(out, 200, aggregateBuddySse(model, text).toString());
        }
    }

    /** 聚合 Buddy/WorkBuddy 的 OpenAI SSE 为标准非流式响应。 */
    private static JSONObject aggregateBuddySse(String model, String sse) {
        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        String finishReason = null;
        for (String line : sse.split("\n")) {
            line = line.trim();
            if (!line.startsWith("data:")) continue;
            String payload = line.substring(5).trim();
            if (payload.isEmpty() || "[DONE]".equals(payload)) continue;
            try {
                JSONObject chunk = new JSONObject(payload);
                JSONArray choices = chunk.optJSONArray("choices");
                if (choices == null || choices.length() == 0) continue;
                JSONObject c0 = choices.getJSONObject(0);
                JSONObject delta = c0.optJSONObject("delta");
                if (delta != null) {
                    content.append(delta.optString("content", ""));
                    reasoning.append(delta.optString("reasoning_content", ""));
                }
                if (c0.has("finish_reason") && !c0.isNull("finish_reason")) {
                    finishReason = c0.optString("finish_reason");
                }
            } catch (Exception ignored) { }
        }
        JSONObject result = new JSONObject();
        try {
            result.put("id", "chatcmpl-jethub-buddy");
            result.put("object", "chat.completion");
            result.put("model", model);
            JSONObject message = new JSONObject();
            message.put("role", "assistant");
            message.put("content", content.toString());
            if (reasoning.length() > 0) message.put("reasoning_content", reasoning.toString());
            JSONArray choices = new JSONArray();
            JSONObject choice = new JSONObject();
            choice.put("index", 0);
            choice.put("message", message);
            choice.put("finish_reason", finishReason == null ? "stop" : finishReason);
            choices.put(choice);
            result.put("choices", choices);
        } catch (Exception ignored) { }
        return result;
    }

    private void sendJson(OutputStream out, int status, String json) throws IOException {
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + status + " " + reason(status) + "\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: " + payload.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(payload);
        out.flush();
    }

    private static String reason(int status) {
        switch (status) {
            case 200: return "OK";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 404: return "Not Found";
            case 502: return "Bad Gateway";
            default: return "Error";
        }
    }
}
