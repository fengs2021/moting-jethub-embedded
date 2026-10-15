package jet.core;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** 极简 HTTP 客户端（java.net 栈，Android 与 host JVM 通用）。 */
public final class HttpFetcher {

    /** 一次 HTTP 响应。body 流由调用方决定是否读取/关闭。 */
    public static final class Response {
        public final int status;
        public final Map<String, java.util.List<String>> headers;
        public final InputStream body;
        public final HttpURLConnection conn;

        Response(int status, Map<String, java.util.List<String>> headers,
                 InputStream body, HttpURLConnection conn) {
            this.status = status;
            this.headers = headers;
            this.body = body;
            this.conn = conn;
        }

        /** 读整个 body 为字符串（非流式场景用）。 */
        public String readText() throws IOException {
            try (InputStream in = body) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            } finally {
                conn.disconnect();
            }
        }

        public void close() {
            try { if (body != null) body.close(); } catch (IOException ignored) { }
            conn.disconnect();
        }
    }

    public static Response fetch(String method, String urlStr, Map<String, String> headers,
                                 byte[] body, int timeoutMs) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        conn.setRequestMethod(method);
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                conn.setRequestProperty(e.getKey(), e.getValue());
            }
        }
        boolean hasBody = body != null && body.length > 0;
        conn.setDoOutput(hasBody);
        if (hasBody) {
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
            }
        }
        int status = conn.getResponseCode();
        InputStream raw = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        InputStream stream = raw != null ? raw : new ByteArrayInputStream(new byte[0]);
        return new Response(status, conn.getHeaderFields(), stream, conn);
    }

    public static Response get(String url, Map<String, String> headers, int timeoutMs) throws IOException {
        return fetch("GET", url, headers, null, timeoutMs);
    }

    public static Response postJson(String url, Map<String, String> headers, String jsonBody, int timeoutMs)
            throws IOException {
        return fetch("POST", url, headers, jsonBody.getBytes(StandardCharsets.UTF_8), timeoutMs);
    }

    private HttpFetcher() { }
}
