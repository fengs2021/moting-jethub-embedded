package jet.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Trae SOLO 自定义 SSE → OpenAI 形状转换器。
 *
 * SOLO 事件（solosse.go）：
 *   event:output        data:{"response":"<增量>","reasoning_content":"<思考增量>"}
 *   event:token_usage   data:{"prompt_tokens":21,...}
 *   event:done          data:{"finish_reason":"stop"}
 *   event:error         data:{"code":4008,"message":"quota exceeded"}
 */
public final class TraeSseConverter {

    private TraeSseConverter() { }

    /** 一条已转换的输出事件。kind: content | reasoning | done | error | skip */
    public static final class Event {
        public final String kind;
        public final String text;
        public final String finishReason;
        public final long promptTokens;
        public final long completionTokens;

        private Event(String kind, String text, String finishReason, long pt, long ct) {
            this.kind = kind; this.text = text; this.finishReason = finishReason;
            this.promptTokens = pt; this.completionTokens = ct;
        }

        static Event content(String t) { return new Event("content", t, null, 0, 0); }
        static Event reasoning(String t) { return new Event("reasoning", t, null, 0, 0); }
        static Event done(String fr, long pt, long ct) { return new Event("done", null, fr, pt, ct); }
        static Event error(String t) { return new Event("error", t, null, 0, 0); }
        static Event skip() { return new Event("skip", null, null, 0, 0); }
    }

    /** 解析一行 data: 载荷（配合当前 event 名）。 */
    public static Event parse(String eventName, String data) {
        if (data == null || data.isEmpty()) return Event.skip();
        JSONObject o;
        try { o = new JSONObject(data); } catch (Exception e) { return Event.skip(); }
        if ("output".equals(eventName)) {
            String delta = o.optString("response", "");
            String reasoning = o.optString("reasoning_content", "");
            if (!reasoning.isEmpty()) return Event.reasoning(reasoning);
            if (!delta.isEmpty()) return Event.content(delta);
            return Event.skip();
        }
        if ("done".equals(eventName)) {
            return Event.done(o.optString("finish_reason", "stop"), 0, 0);
        }
        if ("token_usage".equals(eventName)) {
            return Event.skip(); // usage 在聚合非流式时另行统计
        }
        if ("error".equals(eventName)) {
            return Event.error("code=" + o.optInt("code", 0) + " " + o.optString("message", ""));
        }
        return Event.skip();
    }

    /** 把 Trae SSE 流聚合成非流式标准 OpenAI 响应 JSON。 */
    public static String aggregate(String model, InputStream in) throws Exception {
        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        String finish = "stop";
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        String currentEvent = "";
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) { currentEvent = ""; continue; }
            if (line.startsWith("event:")) { currentEvent = line.substring(6).trim(); continue; }
            if (line.startsWith("data:")) {
                Event e = parse(currentEvent, line.substring(5).trim());
                if ("content".equals(e.kind)) content.append(e.text);
                else if ("reasoning".equals(e.kind)) reasoning.append(e.text);
                else if ("done".equals(e.kind)) finish = e.finishReason == null ? "stop" : e.finishReason;
                else if ("error".equals(e.kind)) throw new IllegalStateException("trae: " + e.text);
            }
        }
        return buildNonStream(model, content.toString(), reasoning.toString(), finish);
    }

    /** 把 Trae SSE 流实时转写成标准 OpenAI SSE 流（逐块回调 writer）。 */
    public interface SseWriter { void write(String s) throws Exception; }

    public static void streamToOpenAi(String model, String chunkId, InputStream in, SseWriter w) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        String currentEvent = "";
        StringBuilder contentBuf = new StringBuilder();
        StringBuilder reasoningBuf = new StringBuilder();
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) { currentEvent = ""; continue; }
            if (line.startsWith("event:")) { currentEvent = line.substring(6).trim(); continue; }
            if (line.startsWith("data:")) {
                String data = line.substring(5).trim();
                if ("[DONE]".equals(data)) break;
                Event e = parse(currentEvent, data);
                if ("content".equals(e.kind)) {
                    contentBuf.append(e.text);
                    w.write(sseChunk(model, chunkId, e.text, null, contentBuf, reasoningBuf));
                } else if ("reasoning".equals(e.kind)) {
                    reasoningBuf.append(e.text);
                    w.write(sseChunk(model, chunkId, null, e.text, contentBuf, reasoningBuf));
                } else if ("done".equals(e.kind)) {
                    w.write(finalChunk(model, chunkId, e.finishReason == null ? "stop" : e.finishReason));
                    w.write("data: [DONE]\n\n");
                } else if ("error".equals(e.kind)) {
                    throw new IllegalStateException("trae: " + e.text);
                }
            }
        }
    }

    private static String sseChunk(String model, String chunkId, String content, String reasoning,
                                   StringBuilder contentBuf, StringBuilder reasoningBuf) throws org.json.JSONException {
        JSONObject delta = new JSONObject();
        if (reasoning != null) delta.put("reasoning_content", reasoning);
        if (content != null) delta.put("content", content);
        JSONObject o = new JSONObject();
        o.put("id", chunkId);
        o.put("object", "chat.completion.chunk");
        o.put("created", System.currentTimeMillis() / 1000);
        o.put("model", model);
        JSONArray choices = new JSONArray();
        JSONObject choice = new JSONObject();
        choice.put("index", 0);
        choice.put("delta", delta);
        choice.put("finish_reason", JSONObject.NULL);
        choices.put(choice);
        o.put("choices", choices);
        return "data: " + o + "\n\n";
    }

    private static String finalChunk(String model, String chunkId, String finish) throws org.json.JSONException {
        JSONObject o = new JSONObject();
        o.put("id", chunkId);
        o.put("object", "chat.completion.chunk");
        o.put("created", System.currentTimeMillis() / 1000);
        o.put("model", model);
        JSONArray choices = new JSONArray();
        JSONObject choice = new JSONObject();
        choice.put("index", 0);
        choice.put("delta", new JSONObject());
        choice.put("finish_reason", finish);
        choices.put(choice);
        o.put("choices", choices);
        return "data: " + o + "\n\n";
    }

    private static String buildNonStream(String model, String content, String reasoning, String finish)
            throws org.json.JSONException {
        JSONObject message = new JSONObject();
        message.put("role", "assistant");
        if (!reasoning.isEmpty()) message.put("reasoning_content", reasoning);
        message.put("content", content);
        JSONObject choice = new JSONObject();
        choice.put("index", 0);
        choice.put("message", message);
        choice.put("finish_reason", finish);
        JSONObject o = new JSONObject();
        o.put("id", "chatcmpl-trae-" + Long.toHexString(System.currentTimeMillis()));
        o.put("object", "chat.completion");
        o.put("created", System.currentTimeMillis() / 1000);
        o.put("model", model);
        JSONArray choices = new JSONArray();
        choices.put(choice);
        o.put("choices", choices);
        o.put("usage", new JSONObject().put("prompt_tokens", 0).put("completion_tokens", 0).put("total_tokens", 0));
        return o.toString();
    }
}
