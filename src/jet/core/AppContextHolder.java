package jet.core;

import android.content.Context;

/** 应用级 Context 与凭据存储的静态持有者（Boot/Activity 共用）。 */
public final class AppContextHolder {

    private static volatile Context appContext;
    private static volatile CredentialStore credentialStore;
    private static volatile AccountManager accountManager;

    private AppContextHolder() { }

    public static void init(Context context, CredentialStore store) {
        appContext = context.getApplicationContext();
        if (credentialStore == null) credentialStore = store;
    }

    /** Boot 在构造完 AccountManager 后调用。 */
    public static void initAccounts(AccountManager manager) {
        accountManager = manager;
    }

    public static Context context() {
        return appContext;
    }

    public static CredentialStore store() {
        return credentialStore;
    }

    /** 多账号管理器；未初始化时返回 null。 */
    public static AccountManager accounts() {
        return accountManager;
    }
}
