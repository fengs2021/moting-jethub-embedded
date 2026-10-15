import java.io.*;
import java.net.*;
public class GwE2EHelper {
    public static int count(String json) { try { return new org.json.JSONObject(json).getJSONArray("data").length(); } catch (Exception e) { return -1; } }
    public static String get(String url, String key) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setReadTimeout(150000);
        c.setRequestProperty("Authorization", "Bearer " + key);
        return read(c.getInputStream());
    }
    public static String post(String url, String key, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setReadTimeout(120000); c.setRequestMethod("POST"); c.setDoOutput(true);
        c.setRequestProperty("Authorization", "Bearer " + key);
        c.setRequestProperty("Content-Type", "application/json");
        try (OutputStream os = c.getOutputStream()) { os.write(body.getBytes("UTF-8")); }
        return read(c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream());
    }
    static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toString("UTF-8");
    }
}
