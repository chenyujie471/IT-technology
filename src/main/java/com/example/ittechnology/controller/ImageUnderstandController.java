package com.example.ittechnology.controller;

import com.example.ittechnology.service.AiProxyService;
import com.example.ittechnology.util.ImageMime;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;

/**
 * 图片理解（多模态视觉）— 上传图片让视觉模型描述 / OCR / 分析。
 *
 * 接口：POST /api/image/understand
 * 请求体：
 * {
 *   "model": "gemini-2.5-pro" | "claude-sonnet-4-6" | "gpt-4o-mini",
 *   "prompt": "请描述这张图片",
 *   "image_b64": "<base64 或 data:image/png;base64,...>",   // 必填
 *   "mime": "image/png",                                    // 可选，默认 image/png
 *   "max_tokens": 1024                                      // 可选
 * }
 *
 * 按模型类型路由：
 *   - Gemini → POST /v1beta/models/{model}:generateContent（inlineData 图片）
 *   - Claude → POST /v1/messages（content[].source base64 图片）
 *   - OpenAI → POST /v1/chat/completions（image_url data URL 图片）
 *
 * 返回：{ok, text, ms, status}
 */
@Slf4j
@RestController
@RequestMapping("/api/image")
@RequiredArgsConstructor
public class ImageUnderstandController {

    private final AiProxyService proxyService;
    private final ObjectMapper mapper = new ObjectMapper();

    @PostMapping(value = "/understand", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ObjectNode> understand(
            @RequestHeader("Authorization") String auth,
            @RequestHeader(name = "X-Site", required = false, defaultValue = "cn") String site,
            @RequestBody String body) {

        long t0 = System.currentTimeMillis();
        try {
            JsonNode req = mapper.readTree(body);
            String model = req.path("model").asText("");
            if (model.isEmpty()) throw new IllegalArgumentException("请选择模型");

            // 图片 base64：兼容纯 base64 与 data URL 前缀
            String imgB64 = req.path("image_b64").asText("").trim();
            if (imgB64.isEmpty()) throw new IllegalArgumentException("请上传参考图片");
            String mime = req.path("mime").asText("");
            if (imgB64.startsWith("data:")) {
                int comma = imgB64.indexOf(',');
                if (comma > 0) {
                    String head = imgB64.substring(5, comma);   // data:image/png;base64
                    if (mime.isEmpty() || !mime.contains("/")) {
                        int semi = head.indexOf(';');
                        String maybe = semi > 0 ? head.substring(0, semi) : head;
                        if (maybe.contains("/")) mime = maybe;
                    }
                    imgB64 = imgB64.substring(comma + 1);
                }
            }
            if (imgB64.isEmpty()) throw new IllegalArgumentException("图片数据为空");
            // 按内容魔数纠正 MIME：声明 image/jpeg 但实际是 webp 时上游会 400 拒收
            mime = ImageMime.resolve(mime, imgB64);

            String prompt = req.path("prompt").asText("").trim();
            if (prompt.isEmpty()) prompt = "请详细描述这张图片的内容";
            int maxTok = Math.max(64, Math.min(req.path("max_tokens").asInt(1024), 8192));

            String lm = model.toLowerCase();
            String path;
            String upstreamBody;

            if (lm.contains("gemini")) {
                // Gemini：inlineData 图片
                path = "/v1beta/models/" + model + ":generateContent";
                ObjectNode gBody = mapper.createObjectNode();
                ArrayNode contents = mapper.createArrayNode();
                ObjectNode user = mapper.createObjectNode();
                user.put("role", "user");
                ArrayNode parts = mapper.createArrayNode();
                ObjectNode txt = mapper.createObjectNode();
                txt.put("text", prompt);
                parts.add(txt);
                ObjectNode inline = mapper.createObjectNode();
                ObjectNode inlineData = mapper.createObjectNode();
                inlineData.put("mimeType", mime);
                inlineData.put("data", imgB64);
                inline.set("inlineData", inlineData);
                parts.add(inline);
                user.set("parts", parts);
                contents.add(user);
                gBody.set("contents", contents);
                ObjectNode gc = mapper.createObjectNode();
                gc.put("maxOutputTokens", maxTok);
                gBody.set("generationConfig", gc);
                upstreamBody = gBody.toString();
            } else if (lm.contains("claude")) {
                // Claude：content[].source base64
                path = "/v1/messages";
                ObjectNode cBody = mapper.createObjectNode();
                cBody.put("model", model);
                cBody.put("max_tokens", maxTok);
                ArrayNode content = mapper.createArrayNode();
                ObjectNode img = mapper.createObjectNode();
                img.put("type", "image");
                ObjectNode source = mapper.createObjectNode();
                source.put("type", "base64");
                source.put("media_type", mime);
                source.put("data", imgB64);
                img.set("source", source);
                content.add(img);
                ObjectNode txt = mapper.createObjectNode();
                txt.put("type", "text");
                txt.put("text", prompt);
                content.add(txt);
                ArrayNode messages = mapper.createArrayNode();
                ObjectNode user = mapper.createObjectNode();
                user.put("role", "user");
                user.set("content", content);
                messages.add(user);
                cBody.set("messages", messages);
                upstreamBody = cBody.toString();
            } else {
                // OpenAI：image_url data URL
                path = "/v1/chat/completions";
                ObjectNode oBody = mapper.createObjectNode();
                oBody.put("model", model);
                oBody.put("max_tokens", maxTok);
                ArrayNode messages = mapper.createArrayNode();
                ObjectNode user = mapper.createObjectNode();
                user.put("role", "user");
                ArrayNode content = mapper.createArrayNode();
                ObjectNode img = mapper.createObjectNode();
                img.put("type", "image_url");
                ObjectNode iu = mapper.createObjectNode();
                iu.put("url", "data:" + mime + ";base64," + imgB64);
                img.set("image_url", iu);
                content.add(img);
                ObjectNode txt = mapper.createObjectNode();
                txt.put("type", "text");
                txt.put("text", prompt);
                content.add(txt);
                user.set("content", content);
                messages.add(user);
                oBody.set("messages", messages);
                upstreamBody = oBody.toString();
            }

            log.info("Image understand [site={}, model={}, mime={}]", site, model, mime);
            AiProxyService.FetchResult r = proxyService.postJson(site, path, auth, upstreamBody, Duration.ofSeconds(180));
            long ms = System.currentTimeMillis() - t0;

            ObjectNode out = mapper.createObjectNode();
            if (r.status() >= 400) {
                out.put("ok", false);
                out.put("status", r.status());
                out.put("ms", ms);
                out.put("error", "上游 " + r.status() + ": " + truncate(r.body(), 500));
                return ResponseEntity.status(r.status() >= 500 ? 502 : 400).body(out);
            }

            String text = extractText(r.body(), lm);
            out.put("ok", true);
            out.put("status", r.status());
            out.put("ms", ms);
            out.put("text", text);
            return ResponseEntity.ok(out);

        } catch (IllegalArgumentException e) {
            log.warn("Image understand bad request: {}", e.getMessage());
            ObjectNode err = mapper.createObjectNode();
            err.put("ok", false);
            err.put("ms", System.currentTimeMillis() - t0);
            err.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(err);
        } catch (Exception e) {
            log.error("Image understand failed: {}", e.getMessage());
            ObjectNode err = mapper.createObjectNode();
            err.put("ok", false);
            err.put("ms", System.currentTimeMillis() - t0);
            err.put("error", e.getMessage());
            return ResponseEntity.status(502).body(err);
        }
    }

    /** 从三种上游响应格式中提取文本。 */
    private String extractText(String respBody, String lm) throws Exception {
        JsonNode j = mapper.readTree(respBody);
        if (lm.contains("gemini")) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : j.path("candidates").path(0).path("content").path("parts")) {
                String s = part.path("text").asText("");
                if (!s.isEmpty()) { if (sb.length() > 0) sb.append("\n"); sb.append(s); }
            }
            return sb.toString();
        }
        if (lm.contains("claude")) {
            StringBuilder sb = new StringBuilder();
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
}
