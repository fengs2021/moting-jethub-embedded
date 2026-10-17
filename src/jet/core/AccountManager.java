package jet.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多账号管理器：把「每供应商单凭据」的 Provider 升成「每供应商多账号」。
 *
 * <h3>核心机制：AccountView</h3>
 * 各家 Provider 只认 {@link CredentialStore} 接口（get/set 一个 ref）。
 * 本类为每个账号造一个「桶视图」：{@code get(账号级ref)} 读该账号的 payload，
 * {@code set(...)} 按身份 upsert 回桶，其余 ref（设备指纹、本地密钥）透传全局存储。
 *
 * <p>于是：<b>要按某个账号干活，就用它的视图造一个临时 Provider</b> ——
 * Provider 代码一行不用改，且天然并发安全（各视图互不干扰）。
 * 不采用「临时把 payload 写进全局 ref 再调、调完恢复」的方案：
 * 网关是多线程的，那样会让并发的对话与签到互相踩凭据。
 *
 * <h3>登录写哪个账号</h3>
 * 登录流程用「活跃账号视图」的 Provider；凭据落库时走 upsert，
 * 按身份去重 ⇒ 新账号自动新增，老账号续期自动覆盖。
 */
public final class AccountManager {

    /** 供应商 id 与 UI 的 PROVIDERS 常量一致。 */
    public static final String P_CLINE = "cline";
    public static final String P_TRAE = "trae";
    public static final String P_BUDDY = "buddy";
    public static final String P_WORKBUDDY = "workbuddy";
    public static final String P_MINIMAX = "minimax";
    public static final String P_OPENCODE = "opencode";
    public static final String P_CODEARTS = "codearts";

    /** 供应商元数据（顺序即界面顺序）。 */
    public static final class Meta {
        public final String id, name, note;
        public final boolean checkin;

        Meta(String id, String name, String note, boolean checkin) {
            this.id = id; this.name = name; this.note = note; this.checkin = checkin;
        }
    }

    public static final List<Meta> PROVIDERS = new ArrayList<>();
    static {
        PROVIDERS.add(new Meta(P_CLINE, "Cline", "设备码登录", false));
        PROVIDERS.add(new Meta(P_TRAE, "Trae", "短信 / 扫码", true));
        PROVIDERS.add(new Meta(P_BUDDY, "CodeBuddy", "浏览器授权", true));
        // ⚠️ WorkBuddy 国际版**没有签到接口**（jethub provider-credits.ts 的
        // claimUnavailableReason 明写：www.workbuddy.ai 后端不存在 claim 端点）。
        // 其状态端点虽与国内版共用、但恒返回 active:false；若仍标记可签到，界面会
        // 显示一个必然失败的签到按钮（实测返回「签到活动未开启或已过期」并落到
        // already-claimed 语义，对用户是误导）。故此处与 jethub 保持一致：不可签到。
        PROVIDERS.add(new Meta(P_WORKBUDDY, "WorkBuddy", "浏览器授权", false));
        PROVIDERS.add(new Meta(P_MINIMAX, "MiniMax", "设备码登录", true));
        // ⚠️ OpenCode 无签到活动也无余额接口，checkin 必须 false，否则界面会出现
        // 一个永远失败的签到按钮。它的登录形态也与其余五家不同：不是设备码/扫码，
        // 而是**直接填 API key**（匿名通道已被服务端封死，必须真实 key）。
        PROVIDERS.add(new Meta(P_OPENCODE, "OpenCode", "API Key", false));
        // ⚠️ CodeArts 的签到只在**积分计费账户**上生效（Token 计费账户不在活动
        // 范围内）。仍标 checkin=true：账户类型差异由 CreditsService 判定成
        // inactive 并在界面如实说明，比直接不显示按钮更好 —— 用户至少知道
        // 「这个平台有签到，只是我这类账户不参与」。
        // 登录形态也与其余家不同：浏览器 OAuth（PKCE + DPoP，本地回调），
        // 凭据 8 小时过期，靠 refresh_token 静默续期。
        PROVIDERS.add(new Meta(P_CODEARTS, "CodeArts", "浏览器授权", true));
    }

    /** 余额/签到缓存有效期：避免每次开页面都打 5 家上游。 */
    private static final long CACHE_TTL_MS = 60_000;

    private final CredentialStore global;
    private final Map<String, AccountStore> buckets = new LinkedHashMap<>();
    private final Map<String, long[]> cacheAt = new LinkedHashMap<>();
    private final Map<String, JSONObject> balanceCache = new LinkedHashMap<>();
    private final Map<String, JSONObject> checkinCache = new LinkedHashMap<>();

    public AccountManager(CredentialStore global) {
        this.global = global;
        buckets.put(P_CLINE, new AccountStore(global, "JETHUB_CLINE_ACCOUNTS",
                ClineProvider.CREDENTIAL_REF, new ClineIdentity()));
        buckets.put(P_TRAE, new AccountStore(global, "JETHUB_TRAE_ACCOUNTS",
                TraeProvider.CREDENTIAL_REF, new TraeIdentity()));
        buckets.put(P_BUDDY, new AccountStore(global, "JETHUB_BUDDY_ACCOUNTS",
                BuddyProvider.Variant.buddy().credentialRef, new BuddyIdentity()));
        buckets.put(P_WORKBUDDY, new AccountStore(global, "JETHUB_WORKBUDDY_ACCOUNTS",
                BuddyProvider.Variant.workbuddy().credentialRef, new BuddyIdentity()));
        buckets.put(P_MINIMAX, new AccountStore(global, "JETHUB_MINIMAX_ACCOUNTS",
                MinimaxProvider.CREDENTIAL_REF, new MinimaxIdentity()));
        buckets.put(P_OPENCODE, new AccountStore(global, "JETHUB_OPENCODE_ACCOUNTS",
                OpencodeProvider.CREDENTIAL_REF, new OpencodeIdentity()));
        buckets.put(P_CODEARTS, new AccountStore(global, "JETHUB_CODEARTS_ACCOUNTS",
                CodeartsProvider.CREDENTIAL_REF, new CodeartsIdentity()));
    }

    public CredentialStore globalStore() { return global; }

    public AccountStore bucket(String providerId) { return buckets.get(providerId); }

    /** 账号级 ref：只有它走桶，其余（设备指纹/本地密钥）透传全局。 */
    private String accountRefOf(String providerId) {
        if (P_CLINE.equals(providerId)) return ClineProvider.CREDENTIAL_REF;
        if (P_TRAE.equals(providerId)) return TraeProvider.CREDENTIAL_REF;
        if (P_BUDDY.equals(providerId)) return BuddyProvider.Variant.buddy().credentialRef;
        if (P_WORKBUDDY.equals(providerId)) return BuddyProvider.Variant.workbuddy().credentialRef;
        if (P_MINIMAX.equals(providerId)) return MinimaxProvider.CREDENTIAL_REF;
        if (P_OPENCODE.equals(providerId)) return OpencodeProvider.CREDENTIAL_REF;
        if (P_CODEARTS.equals(providerId)) return CodeartsProvider.CREDENTIAL_REF;
        return null;
    }

    // ══════════════════════════════════════════════════════════════
    //  AccountView —— 单账号的存储视图
    // ══════════════════════════════════════════════════════════════

    /**
     * 某个账号（或「活跃账号」）的存储视图。
     *
     * @param accountId null 表示活跃账号；读不到时 get 返回 null（等价于未登录）
     */
    public CredentialStore view(final String providerId, final String accountId) {
        return new CredentialStore() {
            @Override public String get(String ref) {
                if (!isAccountRef(providerId, ref)) return global.get(ref);
                AccountStore b = buckets.get(providerId);
                if (b == null) return null;
                AccountStore.Account a = accountId == null ? b.load().active() : b.load().byId(accountId);
                return a == null ? null : a.payload.toString();
            }

            @Override public void set(String ref, String value) {
                if (!isAccountRef(providerId, ref)) { global.set(ref, value); return; }
                AccountStore b = buckets.get(providerId);
                if (b == null) return;
                try {
                    JSONObject payload = new JSONObject(value);
                    if (accountId != null) {
                        // 明确指定账号：原地更新（续期回写），不存在则新增
                        if (b.load().byId(accountId) != null) b.updatePayload(accountId, payload);
                        else b.upsert(payload, null);
                    } else {
                        b.upsert(payload, null);
                    }
                } catch (Exception ignored) { }
            }

            @Override public void remove(String ref) {
                if (!isAccountRef(providerId, ref)) { global.remove(ref); return; }
                if (accountId != null) buckets.get(providerId).remove(accountId);
            }
        };
    }

    private boolean isAccountRef(String providerId, String ref) {
        String acctRef = accountRefOf(providerId);
        return acctRef != null && acctRef.equals(ref);
    }

    // ══════════════════════════════════════════════════════════════
    //  Provider 工厂（按账号）
    // ══════════════════════════════════════════════════════════════

    public ClineProvider cline(String accountId) { return new ClineProvider(view(P_CLINE, accountId)); }

    public TraeProvider trae(String accountId) { return new TraeProvider(view(P_TRAE, accountId)); }

    public BuddyProvider buddy(String accountId) {
        return new BuddyProvider(view(P_BUDDY, accountId), BuddyProvider.Variant.buddy());
    }

    public BuddyProvider workbuddy(String accountId) {
        return new BuddyProvider(view(P_WORKBUDDY, accountId), BuddyProvider.Variant.workbuddy());
    }

    public MinimaxProvider minimax(String accountId) { return new MinimaxProvider(view(P_MINIMAX, accountId)); }

    public OpencodeProvider opencode(String accountId) {
        return new OpencodeProvider(view(P_OPENCODE, accountId));
    }

    public CodeartsProvider codearts(String accountId) {
        return new CodeartsProvider(view(P_CODEARTS, accountId));
    }

    /** 按活跃账号造 Provider（聊天/列模型走它）。 */
    public Object providerFor(String providerId) {
        if (P_CLINE.equals(providerId)) return cline(null);
        if (P_TRAE.equals(providerId)) return trae(null);
        if (P_BUDDY.equals(providerId)) return buddy(null);
        if (P_WORKBUDDY.equals(providerId)) return workbuddy(null);
        if (P_MINIMAX.equals(providerId)) return minimax(null);
        if (P_OPENCODE.equals(providerId)) return opencode(null);
        if (P_CODEARTS.equals(providerId)) return codearts(null);
        return null;
    }

    // ══════════════════════════════════════════════════════════════
    //  /jethub/accounts —— 账号总览
    // ══════════════════════════════════════════════════════════════

    /**
     * 账号总览 JSON（供管理页渲染）。
     *
     * @param withCredits 是否附带余额/签到（带 TTL 缓存；页面首屏可传 false 加快）
     */
    public JSONObject accountsJson(boolean withCredits) {
        JSONObject root = new JSONObject();
        JSONArray provArr = new JSONArray();
        try {
            for (Meta m : PROVIDERS) {
                JSONObject p = new JSONObject();
                p.put("id", m.id);
                p.put("name", m.name);
                p.put("note", m.note);
                p.put("supportsCheckin", m.checkin);
                AccountStore b = buckets.get(m.id);
                JSONArray acctArr = new JSONArray();
                if (b != null) {
                    AccountStore.Bucket bk = b.load();
                    for (AccountStore.Account a : bk.accounts) {
                        JSONObject ao = new JSONObject();
                        ao.put("id", a.id);
                        ao.put("label", a.label);
                        ao.put("active", a.id.equals(bk.activeId)
                                || (bk.activeId == null && a == bk.active()));
                        ao.put("createdAt", a.createdAt);
                        JSONObject payload = a.payload;
                        String email = payload.optString("email", "");
                        if (!email.isEmpty()) ao.put("email", email);
                        Long exp = payload.optLong("expire_time", 0L);
                        if (exp > 0) {
                            ao.put("expiresAt", exp);
                            ao.put("expired", System.currentTimeMillis() >= exp);
                        } else {
                            ao.put("expired", false);
                        }
                        if (withCredits) {
                            JSONObject bal = cachedBalance(m.id, a.id);
                            if (bal != null) ao.put("balance", bal);
                            if (m.checkin) {
                                JSONObject ck = cachedCheckin(m.id, a.id);
                                if (ck != null) ao.put("checkin", ck);
                            }
                        }
                        acctArr.put(ao);
                    }
                }
                p.put("accounts", acctArr);
                provArr.put(p);
            }
            root.put("providers", provArr);
        } catch (Exception ignored) { }
        return root;
    }

    private JSONObject cachedBalance(String providerId, String accountId) {
        String key = providerId + "/" + accountId;
        long now = System.currentTimeMillis();
        long[] at = cacheAt.get(key + ":bal");
        if (at != null && now - at[0] < CACHE_TTL_MS) return balanceCache.get(key + ":bal");
        JSONObject v = fetchBalance(providerId, accountId);
        balanceCache.put(key + ":bal", v);
        cacheAt.put(key + ":bal", new long[]{now});
        return v;
    }

    private JSONObject cachedCheckin(String providerId, String accountId) {
        String key = providerId + "/" + accountId + ":ck";
        long now = System.currentTimeMillis();
        long[] at = cacheAt.get(key);
        if (at != null && now - at[0] < CACHE_TTL_MS) return checkinCache.get(key);
        JSONObject v = fetchCheckin(providerId, accountId);
        checkinCache.put(key, v);
        cacheAt.put(key, new long[]{now});
        return v;
    }

    /** 使某账号的余额/签到缓存失效（签到成功后调用）。 */
    private void invalidate(String providerId, String accountId) {
        cacheAt.remove(providerId + "/" + accountId + ":bal");
        cacheAt.remove(providerId + "/" + accountId + ":ck");
    }

    /** 使某家全部账号的缓存失效（新增/删除凭据后调用，避免 UI 拿到旧列表）。 */
    public void invalidate(String providerId) {
        cacheAt.keySet().removeIf(k -> k.startsWith(providerId + "/"));
        balanceCache.keySet().removeIf(k -> k.startsWith(providerId + "/"));
        checkinCache.keySet().removeIf(k -> k.startsWith(providerId + "/"));
    }

    // ══════════════════════════════════════════════════════════════
    //  余额 / 签到
    // ══════════════════════════════════════════════════════════════

    /** 查余额；失败返回带 error 的对象（不返回 null，UI 要显示原因）。 */
    public JSONObject fetchBalance(String providerId, String accountId) {
        try {
            if (P_CLINE.equals(providerId)) {
                ClineProvider p = cline(accountId);
                ClineProvider.Credential c = p.loadCredential();
                if (c == null) return CreditsService.Balance.failed("未登录").toJson();
                p.ensureFresh();
                c = p.loadCredential();
                return CreditsService.clineBalance(p, c.toJson()).toJson();
            }
            if (P_TRAE.equals(providerId)) {
                TraeProvider p = trae(accountId);
                TraeProvider.Credential c = p.loadCredential();
                if (c == null) return CreditsService.Balance.failed("未登录").toJson();
                c = p.ensureFresh();
                if (c == null) c = p.loadCredential();
                if (c == null) return CreditsService.Balance.failed("凭据已失效，请重新登录").toJson();
                return CreditsService.traeBalance(c.accessToken, c.uid).toJson();
            }
            if (P_BUDDY.equals(providerId) || P_WORKBUDDY.equals(providerId)) {
                BuddyProvider p = P_BUDDY.equals(providerId) ? buddy(accountId) : workbuddy(accountId);
                BuddyProvider.Credential c = p.loadCredential();
                if (c == null) return CreditsService.Balance.failed("未登录").toJson();
                c = p.ensureFresh();
                if (c == null) c = p.loadCredential();
                if (c == null) return CreditsService.Balance.failed("凭据已失效，请重新登录").toJson();
                String userId = payloadString(providerId, accountId, "user_id");
                return CreditsService.buddyBalance(variantOf(providerId), c.accessToken,
                        c.enterpriseId, userId).toJson();
            }
            if (P_MINIMAX.equals(providerId)) {
                MinimaxProvider p = minimax(accountId);
                MinimaxProvider.Credential c = p.loadCredential();
                if (c == null) return CreditsService.Balance.failed("未登录").toJson();
                c = p.ensureFresh();
                if (c == null) c = p.loadCredential();
                if (c == null) return CreditsService.Balance.failed("凭据已失效，请重新登录").toJson();
                return CreditsService.minimaxBalance(c.accessToken).toJson();
            }
            if (P_CODEARTS.equals(providerId)) {
                CodeartsProvider p = codearts(accountId);
                if (p.loadCredential() == null) {
                    return CreditsService.Balance.failed("未登录").toJson();
                }
                p.ensureFresh();
                CodeartsProvider.Credential c = p.loadCredential();
                if (c == null) {
                    return CreditsService.Balance.failed("凭据已失效，请重新登录").toJson();
                }
                return CreditsService.codeartsBalance(c).toJson();
            }
            return CreditsService.Balance.failed("该平台不支持余额查询").toJson();
        } catch (Exception e) {
            return CreditsService.Balance.failed(shortError(e)).toJson();
        }
    }

    /** 查签到状态。 */
    public JSONObject fetchCheckin(String providerId, String accountId) {
        try {
            if (P_TRAE.equals(providerId)) {
                TraeProvider p = trae(accountId);
                TraeProvider.Credential c = p.loadCredential();
                if (c == null) return new CreditsService.Checkin().toJson();
                c = p.ensureFresh();
                if (c == null) c = p.loadCredential();
                if (c == null) return new CreditsService.Checkin().toJson();
                return CreditsService.traeCheckinStatus(c.accessToken, c.uid).toJson();
            }
            if (P_BUDDY.equals(providerId) || P_WORKBUDDY.equals(providerId)) {
                BuddyProvider p = P_BUDDY.equals(providerId) ? buddy(accountId) : workbuddy(accountId);
                BuddyProvider.Credential c = p.loadCredential();
                if (c == null) return new CreditsService.Checkin().toJson();
                c = p.ensureFresh();
                if (c == null) c = p.loadCredential();
                if (c == null) return new CreditsService.Checkin().toJson();
                String userId = payloadString(providerId, accountId, "user_id");
                return CreditsService.buddyCheckinStatus(variantOf(providerId), c.accessToken,
                        c.enterpriseId, userId).toJson();
            }
            if (P_MINIMAX.equals(providerId)) {
                MinimaxProvider p = minimax(accountId);
                MinimaxProvider.Credential c = p.loadCredential();
                if (c == null) return new CreditsService.Checkin().toJson();
                c = p.ensureFresh();
                if (c == null) c = p.loadCredential();
                if (c == null) return new CreditsService.Checkin().toJson();
                return CreditsService.minimaxCheckinStatus(c.accessToken, timezoneId()).toJson();
            }
            if (P_CODEARTS.equals(providerId)) {
                CodeartsProvider p = codearts(accountId);
                if (p.loadCredential() == null) return new CreditsService.Checkin().toJson();
                p.ensureFresh();
                CodeartsProvider.Credential c = p.loadCredential();
                if (c == null) return new CreditsService.Checkin().toJson();
                return CreditsService.codeartsCheckinStatus(c).toJson();
            }
            return new CreditsService.Checkin().toJson();
        } catch (Exception e) {
            CreditsService.Checkin st = new CreditsService.Checkin();
            st.error = shortError(e);
            return st.toJson();
        }
    }

    /** 单账号签到。 */
    public JSONObject checkin(String providerId, String accountId) {
        try {
            CreditsService.Claim c;
            if (P_TRAE.equals(providerId)) {
                TraeProvider p = trae(accountId);
                TraeProvider.Credential cr = p.loadCredential();
                if (cr == null) return CreditsService.Claim.of(CreditsService.KIND_FAILED, "未登录").toJson();
                cr = p.ensureFresh();
                if (cr == null) cr = p.loadCredential();
                if (cr == null) {
                    return CreditsService.Claim.of(CreditsService.KIND_FAILED, "凭据已失效，请重新登录").toJson();
                }
                c = CreditsService.traeClaim(cr.accessToken, cr.uid);
            } else if (P_BUDDY.equals(providerId) || P_WORKBUDDY.equals(providerId)) {
                BuddyProvider p = P_BUDDY.equals(providerId) ? buddy(accountId) : workbuddy(accountId);
                BuddyProvider.Credential cr = p.loadCredential();
                if (cr == null) return CreditsService.Claim.of(CreditsService.KIND_FAILED, "未登录").toJson();
                cr = p.ensureFresh();
                if (cr == null) cr = p.loadCredential();
                if (cr == null) {
                    return CreditsService.Claim.of(CreditsService.KIND_FAILED, "凭据已失效，请重新登录").toJson();
                }
                String userId = payloadString(providerId, accountId, "user_id");
                c = CreditsService.buddyClaim(variantOf(providerId), cr.accessToken, cr.enterpriseId, userId);
            } else if (P_MINIMAX.equals(providerId)) {
                MinimaxProvider p = minimax(accountId);
                MinimaxProvider.Credential cr = p.loadCredential();
                if (cr == null) return CreditsService.Claim.of(CreditsService.KIND_FAILED, "未登录").toJson();
                cr = p.ensureFresh();
                if (cr == null) cr = p.loadCredential();
                if (cr == null) {
                    return CreditsService.Claim.of(CreditsService.KIND_FAILED, "凭据已失效，请重新登录").toJson();
                }
                c = CreditsService.minimaxClaim(cr.accessToken, timezoneId());
            } else if (P_CODEARTS.equals(providerId)) {
                CodeartsProvider p = codearts(accountId);
                if (p.loadCredential() == null) {
                    return CreditsService.Claim.of(CreditsService.KIND_FAILED, "未登录").toJson();
                }
                p.ensureFresh();
                CodeartsProvider.Credential cr = p.loadCredential();
                if (cr == null) {
                    return CreditsService.Claim.of(CreditsService.KIND_FAILED, "凭据已失效，请重新登录").toJson();
                }
                c = CreditsService.codeartsClaim(cr);
            } else {
                return CreditsService.Claim.of(CreditsService.KIND_INACTIVE, "该平台没有签到活动").toJson();
            }
            invalidate(providerId, accountId);
            return c.toJson();
        } catch (Exception e) {
            return CreditsService.Claim.of(CreditsService.KIND_FAILED, shortError(e)).toJson();
        }
    }

    /**
     * 一键签到：遍历所有支持签到的平台的**所有**账号。
     *
     * 单账号失败不中断其余账号（这是本方法的硬约束，见 CreditsService 的设计约定）。
     */
    public JSONObject checkinAll() {
        JSONArray results = new JSONArray();
        for (Meta m : PROVIDERS) {
            if (!m.checkin) continue;
            AccountStore b = buckets.get(m.id);
            if (b == null) continue;
            for (AccountStore.Account a : b.load().accounts) {
                JSONObject r = checkin(m.id, a.id);
                try {
                    r.put("provider", m.id);
                    r.put("accountId", a.id);
                } catch (Exception ignored) { }
                results.put(r);
            }
        }
        JSONObject out = new JSONObject();
        try { out.put("results", results); } catch (Exception ignored) { }
        return out;
    }

    /** 按 id 找平台元信息；未知平台返回 null。 */
    public static Meta metaOf(String providerId) {
        for (Meta m : PROVIDERS) if (m.id.equals(providerId)) return m;
        return null;
    }

    // ══════════════════════════════════════════════════════════════
    //  账号动作：refresh / test / activate / remove / credits
    // ══════════════════════════════════════════════════════════════

    public JSONObject action(String name, String providerId, String accountId) {
        JSONObject out = new JSONObject();
        AccountStore b = buckets.get(providerId);
        if (b == null) {
            try { out.put("ok", false).put("message", "未知平台：" + providerId); } catch (Exception ignored) { }
            return out;
        }
        if (accountId != null && b.load().byId(accountId) == null) {
            try { out.put("ok", false).put("message", "账号不存在或已删除"); } catch (Exception ignored) { }
            return out;
        }
        try {
            if ("activate".equals(name)) {
                boolean ok = b.activate(accountId);
                out.put("ok", ok);
                if (!ok) out.put("message", "账号不存在");
                return out;
            }
            if ("remove".equals(name)) {
                boolean ok = b.remove(accountId);
                invalidate(providerId, accountId);
                out.put("ok", ok);
                if (!ok) out.put("message", "账号不存在");
                return out;
            }
            if ("refresh".equals(name)) {
                // 各家都有 ensureFresh()：未过期直接返回，过期则续期并回写
                Object p = providerForAccount(providerId, accountId);
                String label = labelOf(providerId, accountId);
                refreshOne(p, providerId);
                out.put("ok", true);
                out.put("label", label);
                invalidate(providerId, accountId);
                return out;
            }
            if ("test".equals(name)) {
                Object p = providerForAccount(providerId, accountId);
                int count = testOne(p, providerId);
                out.put("ok", count >= 0);
                out.put("models", count);
                if (count < 0) out.put("message", "无法列出模型（凭据可能已失效）");
                return out;
            }
            if ("credits".equals(name)) {
                // 手动查询：先清缓存再打上游，保证拿到的是此刻的真实数值
                invalidate(providerId, accountId);
                JSONObject bal = fetchBalance(providerId, accountId);
                out.put("ok", bal != null && !bal.has("error"));
                if (bal != null) out.put("balance", bal);
                Meta meta = metaOf(providerId);
                if (meta != null && meta.checkin) {
                    JSONObject ck = fetchCheckin(providerId, accountId);
                    if (ck != null) out.put("checkin", ck);
                }
                return out;
            }
            out.put("ok", false);
            out.put("message", "未知操作：" + name);
            return out;
        } catch (Exception e) {
            try { out.put("ok", false).put("message", shortError(e)); } catch (Exception ignored) { }
            return out;
        }
    }

    private Object providerForAccount(String providerId, String accountId) {
        if (P_CLINE.equals(providerId)) return cline(accountId);
        if (P_TRAE.equals(providerId)) return trae(accountId);
        if (P_BUDDY.equals(providerId)) return buddy(accountId);
        if (P_WORKBUDDY.equals(providerId)) return workbuddy(accountId);
        if (P_MINIMAX.equals(providerId)) return minimax(accountId);
        if (P_OPENCODE.equals(providerId)) return opencode(accountId);
        if (P_CODEARTS.equals(providerId)) return codearts(accountId);
        return null;
    }

    private void refreshOne(Object p, String providerId) throws Exception {
        if (p instanceof ClineProvider) ((ClineProvider) p).ensureFresh();
        else if (p instanceof TraeProvider) ((TraeProvider) p).ensureFresh();
        else if (p instanceof BuddyProvider) ((BuddyProvider) p).ensureFresh();
        else if (p instanceof MinimaxProvider) ((MinimaxProvider) p).ensureFresh();
        // OpenCode 没有续期概念（key 是长期凭据）：走到这里只是校验凭据仍可读；
        // 真正判断「key 还能不能用」由 test / credits 动作打一次上游完成。
        else if (p instanceof OpencodeProvider) ((OpencodeProvider) p).ensureFresh();
        // CodeArts：ensureFresh 会按 expiresAt 判过期并走 DPoP 续期，
        // refresh_token 失效时抛 RefreshTokenExpiredException（在此处冒出即为终态）。
        else if (p instanceof CodeartsProvider) ((CodeartsProvider) p).ensureFresh();
    }

    /** 返回可用模型数；-1 表示失败。 */
    private int testOne(Object p, String providerId) {
        try {
            JSONObject models;
            if (p instanceof ClineProvider) {
                ClineProvider cp = (ClineProvider) p;
                cp.ensureFresh();
                HttpFetcher.Response r = HttpFetcher.get(cp.modelsUrl(),
                        cp.authHeaders(cp.loadCredential()), 30_000);
                models = new JSONObject(r.readText());
            } else if (p instanceof TraeProvider) {
                ((TraeProvider) p).ensureFresh();
                models = ((TraeProvider) p).listModels();
            } else if (p instanceof BuddyProvider) {
                ((BuddyProvider) p).ensureFresh();
                models = ((BuddyProvider) p).listModels();
            } else if (p instanceof MinimaxProvider) {
                ((MinimaxProvider) p).ensureFresh();
                models = ((MinimaxProvider) p).listModels();
            } else if (p instanceof OpencodeProvider) {
                ((OpencodeProvider) p).ensureFresh();
                models = ((OpencodeProvider) p).listModels();
            } else if (p instanceof CodeartsProvider) {
                ((CodeartsProvider) p).ensureFresh();
                models = ((CodeartsProvider) p).listModels();
            } else {
                return -1;
            }
            return countModels(models);
        } catch (Exception e) {
            return -1;
        }
    }

    /** 各家 listModels 返回形状不同，统一数一下条目数。 */
    private int countModels(JSONObject o) {
        if (o == null) return -1;
        JSONArray arr = o.optJSONArray("data");
        if (arr != null) return arr.length();
        arr = o.optJSONArray("models");
        if (arr != null) return arr.length();
        // trae: 通道 -> 模型数组 的对象表
        int total = 0;
        java.util.Iterator<String> it = o.keys();
        while (it.hasNext()) {
            String k = it.next();
            JSONArray a = o.optJSONArray(k);
            if (a != null) total += a.length();
        }
        return total > 0 ? total : -1;
    }

    private String labelOf(String providerId, String accountId) {
        AccountStore b = buckets.get(providerId);
        if (b == null || accountId == null) return "";
        AccountStore.Account a = b.load().byId(accountId);
        return a == null ? "" : a.label;
    }

    // ══════════════════════════════════════════════════════════════
    //  工具
    // ══════════════════════════════════════════════════════════════

    private BuddyProvider.Variant variantOf(String providerId) {
        return P_WORKBUDDY.equals(providerId)
                ? BuddyProvider.Variant.workbuddy() : BuddyProvider.Variant.buddy();
    }

    /** 从桶里读某个 payload 字段（如 Buddy 的 user_id）。 */
    private String payloadString(String providerId, String accountId, String key) {
        AccountStore b = buckets.get(providerId);
        if (b == null) return "";
        AccountStore.Account a = accountId == null ? b.load().active() : b.load().byId(accountId);
        return a == null ? "" : a.payload.optString(key, "");
    }

    /**
     * 签到用 IANA 时区名。
     *
     * ⚠️ 不能直接用 TimeZone.getDefault().getID()：部分 Android 设备返回
     * "GMT+08:00" 这类非 IANA 名，MiniMax 服务端会拒（invalid timezone_id）。
     */
    public static String timezoneId() {
        try {
            java.util.TimeZone tz = java.util.TimeZone.getDefault();
            String id = tz.getID();
            if (id != null && id.indexOf('/') > 0 && !id.startsWith("GMT")) return id;
            int offset = tz.getOffset(System.currentTimeMillis());
            int hours = offset / 3_600_000;
            if (hours == 8) return "Asia/Shanghai";
            if (hours == 9) return "Asia/Tokyo";
            if (hours == 0) return "UTC";
            if (hours == -5) return "America/New_York";
            if (hours == -8) return "America/Los_Angeles";
            return "UTC";
        } catch (Exception e) {
            return "Asia/Shanghai";
        }
    }

    static String shortError(Exception e) {
        String m = e.getMessage();
        if (m == null || m.trim().isEmpty()) return e.getClass().getSimpleName();
        return m.length() > 160 ? m.substring(0, 160) : m;
    }

    // ══════════════════════════════════════════════════════════════
    //  身份抽取
    // ══════════════════════════════════════════════════════════════

    static final class ClineIdentity implements AccountStore.Identity {
        @Override public String identityOf(JSONObject p) {
            String v = p.optString("account_id", "");
            if (!v.isEmpty()) return "cline:" + v;
            String e = p.optString("email", "");
            return e.isEmpty() ? null : "cline:" + e;
        }
        @Override public String labelOf(JSONObject p) {
            String d = p.optString("display_name", "");
            if (!d.isEmpty()) return d;
            String e = p.optString("email", "");
            return e.isEmpty() ? null : e;
        }
    }

    static final class TraeIdentity implements AccountStore.Identity {
        @Override public String identityOf(JSONObject p) {
            String v = p.optString("uid", "");
            if (!v.isEmpty()) return "trae:" + v;
            String e = p.optString("email", "");
            if (!e.isEmpty()) return "trae:" + e;
            String ph = p.optString("phone", "");
            return ph.isEmpty() ? null : "trae:" + ph;
        }
        @Override public String labelOf(JSONObject p) {
            String n = p.optString("nickname", "");
            if (!n.isEmpty()) return n;
            String ph = p.optString("phone", "");
            if (!ph.isEmpty()) return ph;
            String e = p.optString("email", "");
            return e.isEmpty() ? null : e;
        }
    }

    static final class BuddyIdentity implements AccountStore.Identity {
        @Override public String identityOf(JSONObject p) {
            String uid = p.optString("user_id", "");
            String ent = p.optString("enterprise_id", "");
            if (!uid.isEmpty()) return "buddy:" + ent + ":" + uid;
            String e = p.optString("email", "");
            if (!e.isEmpty()) return "buddy:" + ent + ":" + e;
            String n = p.optString("nickname", "");
            return n.isEmpty() ? null : "buddy:" + ent + ":" + n;
        }
        @Override public String labelOf(JSONObject p) {
            String n = p.optString("nickname", "");
            if (!n.isEmpty()) return n;
            String e = p.optString("email", "");
            return e.isEmpty() ? null : e;
        }
    }

    static final class MinimaxIdentity implements AccountStore.Identity {
        /**
         * ⚠️ **不能用 access_token 前缀当身份**。
         *
         * MiniMax 的 access_token 是 `mmoat_` 开头、**不是 JWT**（无 sub 可解），
         * 且每次续期都会换新值。拿它做身份会导致**每续期一次就多出一个账号**：
         * 桶里堆满同一个账号的历史凭据，界面上看起来像登录了好几个账号。
         *
         * 昵称能拿到时用它（可区分不同账号）；拿不到时退化成一个**稳定**常量，
         * 语义是「本机这台 MiniMax 账号」—— 重新登录是覆盖而不是追加。
         */
        @Override public String identityOf(JSONObject p) {
            String n = p.optString("nickname", "");
            if (!n.isEmpty()) return "minimax:" + n;
            String id = p.optString("account_id", "");
            if (!id.isEmpty()) return "minimax:" + id;
            return "minimax:default";
        }
        @Override public String labelOf(JSONObject p) {
            String n = p.optString("nickname", "");
            return n.isEmpty() ? "MiniMax 账号" : n;
        }
    }

    /** OpenCode 身份抽取。 */
    static final class OpencodeIdentity implements AccountStore.Identity {
        /**
         * ⚠️ **匿名槽的身份必须与账号槽区分开**。
         *
         * <p>所有匿名槽的 {@code api_key} 都是字面量 {@code public}；若直接拿
         * apiKey 当身份，多加几个匿名槽（各自配不同代理 = 不同出口 IP = 不同配额桶）
         * 会互相覆盖成一个账号，多开代理的意义全部消失。
         * 故匿名槽退化用 nickname 区分，没有再退到固定常量（此时重新添加是覆盖）。
         *
         * <p>账号槽（真 {@code sk-…} key）用 apiKey 本身：它天然一账号一身份，
         * 且不像 access_token 会续期换值。
         */
        @Override public String identityOf(JSONObject p) {
            String key = p.optString("api_key", "");
            if (key.isEmpty()) return null;
            if (OpencodeProvider.ANONYMOUS_KEY.equals(key)) {
                String n = p.optString("nickname", "");
                return n.isEmpty() ? "opencode:anonymous" : "opencode:anonymous:" + n;
            }
            return "opencode:" + key;
        }

        @Override public String labelOf(JSONObject p) {
            String key = p.optString("api_key", "");
            if (OpencodeProvider.ANONYMOUS_KEY.equals(key)) {
                String n = p.optString("nickname", "");
                // ⚠️ 匿名通道已被服务端封死：标签必须标出「已失效」，
                //    否则用户看到一条正常标签的账号却条条失败，会以为是自己网络问题。
                return n.isEmpty() ? "匿名通道（已失效）" : "匿名通道（已失效）· " + n;
            }
            String n = p.optString("nickname", "");
            if (!n.isEmpty()) return n;
            // key 本身是秘密，标签里只留可辨认的前后少量字符
            if (key.length() > 12) return "API Key " + key.substring(0, 6) + "…" + key.substring(key.length() - 4);
            return "API Key";
        }
    }

    /** CodeArts 身份抽取。 */
    static final class CodeartsIdentity implements AccountStore.Identity {
        /**
         * ⚠️ **绝不能用 AK / SecurityToken 当身份**。
         *
         * <p>CodeArts 的凭据是 STS 临时凭据：每 8 小时过期一次，每次 DPoP 续期
         * 都会换一整套 {@code access_key_id} / {@code secret_access_key} /
         * {@code security_token}。拿 AK 或 securityToken 做身份，每次续期都会
         * 攒出一个「新账号」，桶里迅速堆满同一账号的历史凭据。
         *
         * <p>故优先用 {@code user_id}（STS 响应与 ticket 响应都给，且不随续期变），
         * 退到 {@code domain_id}，再退到 {@code user_name}；都拿不到时退化成一个
         * 稳定常量 —— 语义是「本机这个 CodeArts 账号」，重新登录是覆盖而非追加。
         */
        @Override public String identityOf(JSONObject p) {
            String uid = p.optString("user_id", "");
            if (!uid.isEmpty()) return "codearts:" + uid;
            String did = p.optString("domain_id", "");
            if (!did.isEmpty()) return "codearts:" + did;
            String un = p.optString("user_name", "");
            if (!un.isEmpty()) return "codearts:" + un;
            String n = p.optString("nickname", "");
            return n.isEmpty() ? "codearts:default" : "codearts:" + n;
        }

        @Override public String labelOf(JSONObject p) {
            String n = p.optString("nickname", "");
            if (!n.isEmpty()) return n;
            String un = p.optString("user_name", "");
            if (!un.isEmpty()) return un;
            // AK 本身是凭据，不进标签。退到 user_id 前缀，至少能区分同机多账号。
            String uid = p.optString("user_id", "");
            if (!uid.isEmpty()) return "CodeArts 账号 " + (uid.length() > 8 ? uid.substring(0, 8) : uid);
            return "CodeArts 账号";
        }
    }
}
