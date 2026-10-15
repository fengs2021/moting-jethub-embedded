import jet.core.*;

public class DeviceLoginTest {
    public static void main(String[] args) throws Exception {
        ClineProvider cline = new ClineProvider(new CredentialStore.FileBased(
                new java.io.File("/tmp/jethub-smoke-credentials.json")));
        ClineProvider.DeviceAuthorization a = cline.startDeviceLogin();
        System.out.println("deviceCode.len=" + a.deviceCode.length());
        System.out.println("userCode=" + a.userCode);
        System.out.println("verificationUri=" + a.verificationUri);
        System.out.println("complete=" + a.verificationUriComplete);
        System.out.println("intervalMs=" + a.intervalMs);
        // 一次轮询：应返回 PENDING（不会真的登录）
        ClineProvider.PollResult r = cline.pollDeviceLogin(a);
        System.out.println("poll.kind=" + r.kind + " (0=PENDING)");
        System.out.println("DEVICE LOGIN PROTOCOL OK");
    }
}
