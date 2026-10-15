package jet.core;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * MiniMax（Anthropic messages 形状）SSE → 标准 OpenAI 转换。
 *
 * 事件流：message_start / content_block_start(thinking|text|tool_use) /
 * content_block_delta(thinking_delta|text_delta|signature_delta|input_json_delta) /
 * content_block_stop / message_delta(stop_reason,usage) / message_stop / error。
 * ⚠️ 服务端混用两种错误信封：Anthropic {type:error,error:{message}} 与自家 {code,message}。
 */
public final class MinimaxSseConverter {

    private MinimaxSseConverter() { }

    /** 解析单帧；返回 [textDelta, reasoningDelta]（长度 2，可为空串）；流结束返回 null。 */
    public static String[] parseFrame(String data) throws MinimaxStreamError {
        JSONObject frame;
        try { frame = new JSONObject(data); } catch (Exception e) { return new String[]{"", ""}; }
        String type = frame.optString("type", "");
        if ("error".equals(type)) {
            JSONObject nested = frame.optJSONObject("error");
            String message = nested != null ? nested.optString("message", "") : "";
            if (message.isEmpty()) message = frame.optString("message", "");
            if (message.isEmpty()) message = "未知错误";
            String code = "";
            Object codeRaw = nested != null && nested.has("code") ? nested.opt("code")
                    : frame.has("code") ? frame.opt("code") : null;
            if (codeRaw != null) code = "（code=" + codeRaw + "）";
            throw new MinimaxStreamError("minimax: 流内错误：" + message + code);
        }
        if ("content_block_delta".equals(type)) {
            JSONObject d = frame.optJSONObject("delta");
            if (d == null) return new String[]{"", ""};
            String t = d.optString("type", "");
            if ("text_delta".equals(t)) return new String[]{d.optString("text", ""), ""};
            if ("thinking_delta".equals(t)) return new String[]{"", d.optString("thinking", "")};
            return new String[]{"", ""};
        }
        return new String[]{"", ""};
    }

    /** 聚合完整 SSE 文本为标准非流式响应。 */
    public static JSONObject aggregate(String model, String sse) {
        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        String finishReason = "stop";
        for (String line : sse.split("\n")) {
            line = line.trim();
            if (!line.startsWith("data:")) continue;
            String payload = line.substring(5).trim();
            if (payload.isEmpty()) continue;
            try {
                String[] delta = parseFrame(payload);
                content.append(delta[0]);
                reasoning.append(delta[1]);
                JSONObject frame = new JSONObject(payload);
                if ("message_delta".equals(frame.optString("type"))) {
                    JSONObject d = frame.optJSONObject("delta");
                    String sr = d == null ? "" : d.optString("stop_reason", "");
                    if ("max_tokens".equals(sr)) finishReason = "length";
                }
            } catch (MinimaxStreamError ignored) {
            } catch (Exception ignored) { }
        }
        JSONObject result = new JSONObject();
        try {
            result.put("id", "chatcmpl-jethub-minimax");
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
            choice.put("finish_reason", finishReason);
            choices.put(choice);
            result.put("choices", choices);
        } catch (Exception ignored) { }
        return result;
    }

    public static final class MinimaxStreamError extends Exception {
        public MinimaxStreamError(String msg) { super(msg); }
    }
}
