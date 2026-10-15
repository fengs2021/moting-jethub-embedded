package jet.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 凭据存储：按 ref 名存取字符串值。
 * Android 侧落到 context.filesDir；host 测试落到临时目录。
 */
public interface CredentialStore {
    String get(String ref);
    void set(String ref, String value);
    void remove(String ref);

    /** 基于目录的 JSON 简单实现（单文件 map，原子写）。 */
    final class FileBased implements CredentialStore {
        private final File file;
        private final Map<String, String> cache = new HashMap<>();

        public FileBased(File file) {
            this.file = file;
            load();
        }

        private synchronized void load() {
            cache.clear();
            if (!file.exists()) return;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    new FileInputStream(file), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                char[] buf = new char[8192];
                int n;
                while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
                org.json.JSONObject obj = new org.json.JSONObject(sb.toString());
                java.util.Iterator<String> it = obj.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    cache.put(k, obj.getString(k));
                }
            } catch (Exception e) {
                // 损坏即视为空存储；下次 set 会重建
            }
        }

        private synchronized void save() {
            File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
            try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
                w.write(new org.json.JSONObject(cache).toString());
            } catch (IOException e) {
                return;
            }
            if (!tmp.renameTo(file)) {
                file.delete();
                tmp.renameTo(file);
            }
        }

        @Override public synchronized String get(String ref) { return cache.get(ref); }
        @Override public synchronized void set(String ref, String value) {
            cache.put(ref, value);
            save();
        }
        @Override public synchronized void remove(String ref) {
            cache.remove(ref);
            save();
        }
    }
}
