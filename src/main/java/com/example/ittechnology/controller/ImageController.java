package com.example.ittechnology.controller;

import com.example.ittechnology.service.AiProxyService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.time.Duration;

/**
 * 图像生成代理 — 兼容 OpenAI 和 Gemini 格式
 *
 * 前端请求：POST /api/image
 * 上游转发：
 *   - OpenAI 格式 (gpt-image-2): POST /v1/images/generations
 *   - Gemini 格式 (gemini-*-image*): POST /v1beta/models/{model}:generateContent
 *
 * ⚠️ 图像生成最长耗时 600s，必须用 StreamingResponseBody（异步线程）
 *
 * OpenAI 请求体示例：
 * {
 *   "model": "gpt-image-2",
 *   "prompt": "...",
 *   "size": "1024x1024",
 *   "quality": "high",
 *   "response_format": "b64_json",
 *   "stream": true
 * }
 *
 * Gemini 请求体示例（前端已转换）：
 * {
 *   "model": "gemini-3.1-flash-image",
 *   "generationConfig": {
 *     "responseModalities": ["IMAGE"],
 *     "imageConfig": { "aspectRatio": "9:16", "imageSize": "2K" }
 *   },
 *   "contents": [
 *     { "role": "user", "parts": [{ "text": "..." }] }
 *   ]
 * }
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ImageController {

    private final AiProxyService proxyService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostMapping(value = "/image", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<StreamingResponseBody> generateImage(
            @RequestHeader("Authorization") String auth,
            @RequestBody String body) {

        log.info("Image generation request received");

        try {
            JsonNode json = objectMapper.readTree(body);
            String model = json.has("model") ? json.get("model").asText() : "";

            // 检测是否为 Gemini 图像生成模型
            boolean isGemini = model.toLowerCase().contains("gemini") &&
                             (model.toLowerCase().contains("image") ||
                              model.toLowerCase().contains("flash-preview"));

            String upstreamPath;
            String upstreamBody;

            if (isGemini) {
                // Gemini 格式：提取 model 字段，路由到 /v1beta/models/{model}:generateContent
                upstreamPath = "/v1beta/models/" + model + ":generateContent";
                // 移除前端传来的 model 字段（Gemini API 不需要在 body 中）
                var geminiBody = objectMapper.createObjectNode();
                if (json.has("generationConfig")) {
                    geminiBody.set("generationConfig", json.get("generationConfig"));
                }
                if (json.has("contents")) {
                    geminiBody.set("contents", json.get("contents"));
                }
                upstreamBody = objectMapper.writeValueAsString(geminiBody);
                log.info("Routing to Gemini: {} {}", upstreamPath, model);
            } else {
                // OpenAI 格式：使用原始 body
                upstreamPath = "/v1/images/generations";
                upstreamBody = body;
                log.info("Routing to OpenAI: {} {}", upstreamPath, model);
            }

            StreamingResponseBody stream = out ->
                    proxyService.proxyPostStream(upstreamPath, auth, upstreamBody, Duration.ofSeconds(600), out);

            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Cache-Control", "no-cache")
                    .body(stream);

        } catch (Exception e) {
            log.error("Failed to parse image request: {}", e.getMessage());
            // 降级到 OpenAI 格式
            StreamingResponseBody stream = out ->
                    proxyService.proxyPostStream("/v1/images/generations", auth, body, Duration.ofSeconds(600), out);
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Cache-Control", "no-cache")
                    .body(stream);
        }
    }
}
