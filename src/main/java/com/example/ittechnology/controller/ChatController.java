package com.example.ittechnology.controller;

import com.example.ittechnology.service.AiProxyService;
import com.example.ittechnology.util.ImageMime;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.time.Duration;

/**
 * 对话代理 — 兼容三种上游对话格式（由 X-Chat-Api 头选择，auto 时按模型名自动判断）：
 *
 *   messages  → Anthropic   POST /v1/messages            {model, messages, max_tokens, stream}
 *   chat      → OpenAI 对话  POST /v1/chat/completions   {model, messages, max_tokens, stream}
 *   responses → OpenAI 响应  POST /v1/responses          {model, input, max_output_tokens, stream}
 *
 * 前端统一发 {model, messages, max_tokens, stream:true}，
 * responses 格式由后端重写为 input / max_output_tokens。
 * 三种格式的 SSE 均原样透传，前端按事件类型解析。
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ChatController {

    private final AiProxyService proxyService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> chat(
            @RequestHeader("Authorization") String auth,
            @RequestHeader(name = "X-Site", required = false, defaultValue = "cn") String site,
            @RequestHeader(name = "X-Chat-Api", required = false, defaultValue = "auto") String chatApi,
            @RequestBody String body,
            HttpServletResponse response) {

        String model = "";
        try {
            model = objectMapper.readTree(body).path("model").asText("");
        } catch (Exception ignored) {}

        String fmt = resolveFormat(chatApi, model);
        String path;
        String outBody = body;

        switch (fmt) {
            case "chat":
                path = "/v1/chat/completions";
                break;
            case "responses":
                path = "/v1/responses";
                outBody = toResponsesBody(body);
                break;
            default:
                path = "/v1/messages";
                fmt = "messages";
                break;
        }

        log.info("Chat request [site={}, api={}, model={}]", site, fmt, model);

        final String upstreamBody = outBody;
        // 300s：覆盖思考型模型首 token 延迟 + 长输出，避免中途被掐断
        StreamingResponseBody stream = out ->
                proxyService.proxyPostStream(site, path, auth, upstreamBody, Duration.ofSeconds(300), out, response);

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header("Cache-Control", "no-cache")
                .header("X-Accel-Buffering", "no")    // 禁止 Nginx 缓冲，确保 SSE 实时推送
                .body(stream);
    }

    /** auto：claude 系走 Anthropic，其余走 OpenAI chat/completions。 */
    private String resolveFormat(String chatApi, String model) {
        if ("messages".equalsIgnoreCase(chatApi) || "chat".equalsIgnoreCase(chatApi)
                || "responses".equalsIgnoreCase(chatApi)) {
            return chatApi.toLowerCase();
        }
        return (model != null && model.toLowerCase().contains("claude")) ? "messages" : "chat";
    }

    /**
     * 对话带附件（图片理解 / 参考文件）— 非流式。
     *
     * 前端统一 content 数组：{role, content:[{type:"text",text}|{type:"image",mime,name,data}|{type:"file",mime,name,data}]}
     * 按模型格式重写：
     *   - Gemini  → /v1beta/models/{m}:generateContent（parts 内 inlineData）
     *   - Claude  → /v1/messages（image/document source base64）
     *   - OpenAI  → /v1/chat/completions（image_url data URL；文件转为文本占位）
     *
     * 返回：{ok, text, ms}
     */
    @PostMapping(value = "/chat/attach", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ObjectNode> chatAttach(
            @RequestHeader("Authorization") String auth,
            @RequestHeader(name = "X-Site", required = false, defaultValue = "cn") String site,
            @RequestHeader(name = "X-Chat-Api", required = false, defaultValue = "auto") String chatApi,
            @RequestBody String body) {

        long t0 = System.currentTimeMillis();
        try {
            JsonNode req = objectMapper.readTree(body);
            String model = req.path("model").asText("");
            if (model.isEmpty()) throw new IllegalArgumentException("请选择模型");
            int maxTok = Math.max(64, Math.min(req.path("max_tokens").asInt(2000), 8192));
            String fmt = resolveFormat(chatApi, model);
            String lm = model.toLowerCase();

            String path;
            String upBody;
            if (lm.contains("gemini")) {
                path = "/v1beta/models/" + model + ":generateContent";
                upBody = buildGeminiAttach(req, maxTok);
            } else if (fmt.equals("messages")) {
                path = "/v1/messages";
                upBody = buildClaudeAttach(req, model, maxTok);
            } else {
                path = "/v1/chat/completions";
                upBody = buildOpenAiAttach(req, model, maxTok);
            }

            log.info("Chat attach [site={}, api={}, model={}]", site, fmt, model);
            AiProxyService.FetchResult r = proxyService.postJson(site, path, auth, upBody, Duration.ofSeconds(180));
            long ms = System.currentTimeMillis() - t0;

            ObjectNode out = objectMapper.createObjectNode();
            if (r.status() >= 400) {
                out.put("ok", false);
                out.put("status", r.status());
                out.put("ms", ms);
                out.put("error", "上游 " + r.status() + ": " + truncate(r.body(), 400));
                return ResponseEntity.status(r.status() >= 500 ? 502 : 400).body(out);
            }

            String text = extractText(r.body(), lm);
            out.put("ok", true);
            out.put("status", r.status());
            out.put("ms", ms);
            out.put("text", text);
            return ResponseEntity.ok(out);

        } catch (IllegalArgumentException e) {
            log.warn("Chat attach bad request: {}", e.getMessage());
            ObjectNode err = objectMapper.createObjectNode();
            err.put("ok", false);
            err.put("ms", System.currentTimeMillis() - t0);
            err.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(err);
        } catch (Exception e) {
            log.error("Chat attach failed: {}", e.getMessage());
            ObjectNode err = objectMapper.createObjectNode();
            err.put("ok", false);
            err.put("ms", System.currentTimeMillis() - t0);
            err.put("error", e.getMessage());
            return ResponseEntity.status(502).body(err);
        }
    }

    /** Gemini：contents[].parts = [{text}, {inlineData:{mimeType,data}}]。 */
    private String buildGeminiAttach(JsonNode req, int maxTok) throws Exception {
        ObjectNode out = objectMapper.createObjectNode();
        ArrayNode contents = objectMapper.createArrayNode();
        for (JsonNode m : req.path("messages")) {
            String role = m.path("role").asText("user");
            if (!role.equals("user") && !role.equals("model")) role = "user";
            ObjectNode c = objectMapper.createObjectNode();
            c.put("role", role);
            ArrayNode parts = objectMapper.createArrayNode();
            JsonNode content = m.path("content");
            if (content.isArray()) {
                for (JsonNode item : content) {
                    String type = item.path("type").asText("");
                    if (type.equals("text")) {
                        ObjectNode p = objectMapper.createObjectNode();
                        p.put("text", item.path("text").asText(""));
                        parts.add(p);
                    } else if (type.equals("image") || type.equals("file")) {
                        String data = item.path("data").asText("");
                        String mime = item.path("mime").asText("image/png");
                        if (data.startsWith("data:")) {
                            int comma = data.indexOf(',');
                            data = comma > 0 ? data.substring(comma + 1) : data;
                        }
                        ObjectNode inline = objectMapper.createObjectNode();
                        ObjectNode id = objectMapper.createObjectNode();
                        id.put("mimeType", ImageMime.resolve(mime, data));
                        id.put("data", data);
                        inline.set("inlineData", id);
                        parts.add(inline);
                    }
                }
            } else {
                ObjectNode p = objectMapper.createObjectNode();
                p.put("text", content.asText(""));
                parts.add(p);
            }
            c.set("parts", parts);
            contents.add(c);
        }
        out.set("contents", contents);
        ObjectNode gc = objectMapper.createObjectNode();
        gc.put("maxOutputTokens", maxTok);
        out.set("generationConfig", gc);
        return objectMapper.writeValueAsString(out);
    }

    /** Claude：content = [{type:text}, {type:image,source:{base64}}, {type:document,source:{base64}}]。 */
    private String buildClaudeAttach(JsonNode req, String model, int maxTok) throws Exception {
        ObjectNode out = objectMapper.createObjectNode();
        out.put("model", model);
        out.put("max_tokens", maxTok);
        out.put("stream", false);
        ArrayNode messages = objectMapper.createArrayNode();
        for (JsonNode m : req.path("messages")) {
            ObjectNode msg = objectMapper.createObjectNode();
            msg.put("role", m.path("role").asText("user").equals("assistant") ? "assistant" : "user");
            ArrayNode content = objectMapper.createArrayNode();
            JsonNode c = m.path("content");
            if (c.isArray()) {
                for (JsonNode item : c) {
                    String type = item.path("type").asText("");
                    String data = item.path("data").asText("");
                    if (data.startsWith("data:")) {
                        int comma = data.indexOf(',');
                        data = comma > 0 ? data.substring(comma + 1) : data;
                    }
                    String mime = item.path("mime").asText("image/png");
                    if (type.equals("image")) {
                        ObjectNode block = objectMapper.createObjectNode();
                        block.put("type", "image");
                        ObjectNode src = objectMapper.createObjectNode();
                        src.put("type", "base64");
                        src.put("media_type", ImageMime.resolve(mime, data));
                        src.put("data", data);
                        block.set("source", src);
                        content.add(block);
                    } else if (type.equals("file")) {
                        // 文档/其它文件 → document 块（PDF/文本类上游可读；其它类型尝试透传）
                        ObjectNode block = objectMapper.createObjectNode();
                        block.put("type", "document");
                        ObjectNode src = objectMapper.createObjectNode();
                        src.put("type", "base64");
                        src.put("media_type", ImageMime.resolve(mime, data));
                        src.put("data", data);
                        block.set("source", src);
                        content.add(block);
                    } else {
                        ObjectNode block = objectMapper.createObjectNode();
                        block.put("type", "text");
                        block.put("text", item.path("text").asText(""));
                        content.add(block);
                    }
                }
            } else {
                ObjectNode block = objectMapper.createObjectNode();
                block.put("type", "text");
                block.put("text", c.asText(""));
                content.add(block);
            }
            msg.set("content", content);
            messages.add(msg);
        }
        out.set("messages", messages);
        return objectMapper.writeValueAsString(out);
    }

    /** OpenAI：content = [{type:text}, {type:image_url,{data URL}}]；文件 → 文本占位提示。 */
    private String buildOpenAiAttach(JsonNode req, String model, int maxTok) throws Exception {
        ObjectNode out = objectMapper.createObjectNode();
        out.put("model", model);
        out.put("max_tokens", maxTok);
        out.put("stream", false);
        ArrayNode messages = objectMapper.createArrayNode();
        for (JsonNode m : req.path("messages")) {
            ObjectNode msg = objectMapper.createObjectNode();
            msg.put("role", m.path("role").asText("user").equals("assistant") ? "assistant" : "user");
            ArrayNode content = objectMapper.createArrayNode();
            JsonNode c = m.path("content");
            if (c.isArray()) {
                for (JsonNode item : c) {
                    String type = item.path("type").asText("");
                    if (type.equals("image")) {
                        String data = item.path("data").asText("");
                        if (!data.startsWith("data:")) {
                            String mime = ImageMime.resolve(item.path("mime").asText("image/png"), data);
                            data = "data:" + mime + ";base64," + data;
                        }
                        ObjectNode block = objectMapper.createObjectNode();
                        block.put("type", "image_url");
                        ObjectNode iu = objectMapper.createObjectNode();
                        iu.put("url", data);
                        block.set("image_url", iu);
                        content.add(block);
                    } else if (type.equals("file")) {
                        ObjectNode block = objectMapper.createObjectNode();
                        block.put("type", "text");
                        block.put("text", "[已附带文件：" + item.path("name").asText("附件") + "（当前模型不支持文件内容，仅作提示）]");
                        content.add(block);
                    } else {
                        ObjectNode block = objectMapper.createObjectNode();
                        block.put("type", "text");
                        block.put("text", item.path("text").asText(""));
                        content.add(block);
                    }
                }
            } else {
                ObjectNode block = objectMapper.createObjectNode();
                block.put("type", "text");
                block.put("text", c.asText(""));
                content.add(block);
            }
            msg.set("content", content);
            messages.add(msg);
        }
        out.set("messages", messages);
        return objectMapper.writeValueAsString(out);
    }

    /** 从三种上游响应提取文本。 */
    private String extractText(String respBody, String lm) throws Exception {
        JsonNode j = objectMapper.readTree(respBody);
        StringBuilder sb = new StringBuilder();
        if (lm.contains("gemini")) {
            for (JsonNode part : j.path("candidates").path(0).path("content").path("parts")) {
                String s = part.path("text").asText("");
                if (!s.isEmpty()) { if (sb.length() > 0) sb.append("\n"); sb.append(s); }
            }
            return sb.toString();
        }
        if (lm.contains("claude")) {
            for (JsonNode b : j.path("content")) {
                String s = b.path("text").asText("");
                if (!s.isEmpty()) { if (sb.length() > 0) sb.append("\n"); sb.append(s); }
            }
            return sb.toString();
        }
        return j.path("choices").path(0).path("message").path("content").asText("");
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** 把统一请求体 {model, messages, max_tokens} 重写为 Responses API 格式 {model, input, max_output_tokens}。 */
    private String toResponsesBody(String body) {
        try {
            JsonNode json = objectMapper.readTree(body);
            ObjectNode out = objectMapper.createObjectNode();
            out.put("model", json.path("model").asText(""));
            out.set("input", json.path("messages"));
            if (json.has("max_tokens")) {
                out.put("max_output_tokens", json.get("max_tokens").asInt());
            }
            out.put("stream", json.path("stream").asBoolean(true));
            return objectMapper.writeValueAsString(out);
        } catch (Exception e) {
            log.warn("Failed to rewrite body for responses API, forwarding as-is: {}", e.getMessage());
            return body;
        }
    }
}
