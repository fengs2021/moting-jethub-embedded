package jet.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * 多账号存储：每个供应商一个「账号桶」（bucket）。
 *
 * 设计要点（勿凭直觉改动）：
 * - 桶内每个账号 = {id, label, createdAt, payload}，payload 是供应商各自的凭据 JSON；
 * - activeId 指向当前使用的账号，聊天/列模型只走它；
 * - 账号 id 由 {@link Identity#identityOf} 从凭据里抽出的稳定身份串算出（sha256 前 16 位），
 *   所以同一账号重复登录是**覆盖**而不是新增，避免攒出一堆重复项；
 * - 兼容旧版单账号存储：桶为空且旧 ref 有值时自动迁移成 1 个账号。
 */
public final class AccountStore {

    /** 供应商提供：从凭据里抽出稳定身份与展示名。 */
    public interface Identity {
        /** 稳定身份串（如邮箱 / uid / 手机号）；返回 null 时退化为 payload 哈希。 */
        String identityOf(JSONObject payload);

        /** 展示名（如邮箱 / 昵称）；返回 null 时退化为「账号 N」。 */
        String labelOf(JSONObject payload);
    }

    public static final class Account {
        public String id;
        public String label;
        public long createdAt;
        public JSONObject payload;

        public JSONObject toJson() throws org.json.JSONException {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("label", label == null ? "" : label);
            o.put("createdAt", createdAt);
            o.put("payload", payload == null ? new JSONObject() : payload);
            return o;
        }

        public static Account fromJson(JSONObject o) {
            Account a = new Account();
            a.id = o.optString("id", "");
            a.label = o.optString("label", "");
            a.createdAt = o.optLong("createdAt", 0L);
            a.payload = o.optJSONObject("payload");
            if (a.payload == null) a.payload = new JSONObject();
            return a;
        }
    }

    public static final class Bucket {
        public final List<Account> accounts = new ArrayList<>();
        public String activeId;

        public Account byId(String id) {
            if (id == null) return null;
            for (Account a : accounts) if (id.equals(a.id)) return a;
            return null;
        }

        /** 当前活跃账号；activeId 失效时退化为第一个。 */
        public Account active() {
            Account a = byId(activeId);
            if (a != null) return a;
            return accounts.isEmpty() ? null : accounts.get(0);
        }
    }

    private final CredentialStore store;
    private final String ref;
    private final String legacyRef;
    private final Identity identity;
    private boolean migrated;

    public AccountStore(CredentialStore store, String ref, String legacyRef, Identity identity) {
        this.store = store;
        this.ref = ref;
        this.legacyRef = legacyRef;
        this.identity = identity;
    }

    // ── 读写 ──

    public synchronized Bucket load() {
        Bucket b = new Bucket();
        String raw = store.get(ref);
        if (raw != null && !raw.trim().isEmpty()) {
            try {
                JSONObject o = new JSONObject(raw);
                JSONArray arr = o.optJSONArray("accounts");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject item = arr.optJSONObject(i);
                        if (item != null) b.accounts.add(Account.fromJson(item));
                    }
                }
                b.activeId = o.optString("activeId", null);
                if (b.activeId != null && b.activeId.isEmpty()) b.activeId = null;
            } catch (Exception e) {
                // 损坏即视为空桶；下次 save 会重建
            }
        }
        if (b.accounts.isEmpty()) migrateLegacy(b);
        return b;
    }

    public synchronized void save(Bucket b) {
        try {
            JSONArray arr = new JSONArray();
            for (Account a : b.accounts) arr.put(a.toJson());
            JSONObject o = new JSONObject();
            o.put("accounts", arr);
            o.put("activeId", b.activeId == null ? "" : b.activeId);
            store.set(ref, o.toString());
        } catch (org.json.JSONException e) {
            throw new IllegalStateException("账号桶序列化失败", e);
        }
    }

    /** 旧版单账号 ref → 桶内 1 个账号（只做一次）。 */
    private void migrateLegacy(Bucket b) {
        if (migrated || legacyRef == null) return;
        migrated = true;
        String legacy = store.get(legacyRef);
        if (legacy == null || legacy.trim().isEmpty()) return;
        try {
            JSONObject payload = new JSONObject(legacy);
            Account a = new Account();
            a.payload = payload;
            a.id = newId(payload);
            a.label = labelFor(payload, 1);
            a.createdAt = System.currentTimeMillis();
            b.accounts.add(a);
            b.activeId = a.id;
            save(b);
        } catch (Exception e) {
            // 旧数据不可解析：保持空桶，用户重新登录即可
        }
    }

    // ── 增删改 ──

    /** 新增或覆盖（同一身份只留一份）。返回落库后的账号。 */
    public synchronized Account upsert(JSONObject payload, String labelOverride) {
        Bucket b = load();
        String id = newId(payload);
        Account existing = b.byId(id);
        if (existing != null) {
            existing.payload = payload;
            if (labelOverride != null && !labelOverride.trim().isEmpty()) existing.label = labelOverride.trim();
            else if (existing.label == null || existing.label.trim().isEmpty()) {
                existing.label = labelFor(payload, b.accounts.size());
            }
            b.activeId = existing.id;
            save(b);
            return existing;
        }
        Account a = new Account();
        a.payload = payload;
        a.id = id;
        a.label = (labelOverride != null && !labelOverride.trim().isEmpty())
                ? labelOverride.trim() : labelFor(payload, b.accounts.size() + 1);
        a.createdAt = System.currentTimeMillis();
        b.accounts.add(a);
        b.activeId = a.id;
        save(b);
        return a;
    }

    /** 更新已有账号的 payload（续期回写用）；账号不存在则忽略。 */
    public synchronized void updatePayload(String accountId, JSONObject payload) {
        Bucket b = load();
        Account a = b.byId(accountId);
        if (a == null) return;
        a.payload = payload;
        save(b);
    }

    public synchronized boolean remove(String accountId) {
        Bucket b = load();
        Account a = b.byId(accountId);
        if (a == null) return false;
        b.accounts.remove(a);
        if (accountId.equals(b.activeId)) {
            b.activeId = b.accounts.isEmpty() ? null : b.accounts.get(0).id;
        }
        save(b);
        return true;
    }

    public synchronized boolean activate(String accountId) {
        Bucket b = load();
        if (b.byId(accountId) == null) return false;
        b.activeId = accountId;
        save(b);
        return true;
    }

    public synchronized boolean hasAny() {
        return !load().accounts.isEmpty();
    }

    // ── id / label ──

    private String newId(JSONObject payload) {
        String ident = null;
        try {
            ident = identity == null ? null : identity.identityOf(payload);
        } catch (Exception ignored) {
        }
        String basis = (ident == null || ident.trim().isEmpty())
                ? String.valueOf(payload == null ? "" : payload.toString()) : ident.trim();
        return sha256Hex(basis).substring(0, 16);
    }

    private String labelFor(JSONObject payload, int ordinal) {
        String l = null;
        try {
            l = identity == null ? null : identity.labelOf(payload);
        } catch (Exception ignored) {
        }
        if (l != null && !l.trim().isEmpty()) return l.trim();
        return "账号 " + ordinal;
    }

    static String sha256Hex(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(text.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte x : d) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(text.hashCode());
        }
    }
}
