package com.example.ittechnology.controller;

import com.example.ittechnology.service.AiProxyService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

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
            @RequestHeader(name = "X-Site", required = false, defaultValue = "cn") String site,
            @RequestBody String body,
            HttpServletResponse response) {

        log.info("Image generation request received [site={}]", site);

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
                    proxyService.proxyPostStream(site, upstreamPath, auth, upstreamBody, Duration.ofSeconds(600), out, response);

            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Cache-Control", "no-cache")
                    .body(stream);

        } catch (Exception e) {
            log.error("Failed to parse image request: {}", e.getMessage());
            // 降级到 OpenAI 格式
            StreamingResponseBody stream = out ->
                    proxyService.proxyPostStream(site, "/v1/images/generations", auth, body, Duration.ofSeconds(600), out, response);
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Cache-Control", "no-cache")
                    .body(stream);
        }
    }

    /**
     * 图像编辑（gpt-image-2，OpenAI 格式 multipart/form-data）
     *
     * 前端请求：POST /api/image/edit（multipart，file 字段 1-4 张参考图）
     * 上游转发：POST /v1/images/edits（按 X-Site 选站）
     *
     * 提示词支持多图引用，如「图1中的女生穿着图2中的黑色裙子按图3的姿势坐下」。
     * 图像编辑最长耗时 600s，必须用 StreamingResponseBody（异步线程）。
     */
    @PostMapping(value = "/image/edit")
    public ResponseEntity<StreamingResponseBody> editImage(
            @RequestHeader("Authorization") String auth,
            @RequestHeader(name = "X-Site", required = false, defaultValue = "cn") String site,
            @RequestParam("model") String model,
            @RequestParam("prompt") String prompt,
            @RequestParam(value = "n", defaultValue = "1") String n,
            @RequestParam(value = "size", defaultValue = "1024x1024") String size,
            @RequestParam(value = "response_format", defaultValue = "b64_json") String responseFormat,
            @RequestParam(value = "stream", required = false) String stream,
            @RequestPart("file") List<MultipartFile> files,
            HttpServletResponse response) throws IOException {

        log.info("Image edit request received [site={}, files={}, model={}]", site, files.size(), model);

        // 重新组装 multipart/form-data 转发上游（字段名与上游文档一致，文件字段名固定为 file）
        String boundary = "----ClrBoundary" + java.util.UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        field(bos, boundary, "model", model);
        field(bos, boundary, "prompt", prompt);
        field(bos, boundary, "n", n);
        field(bos, boundary, "size", size);
        field(bos, boundary, "response_format", responseFormat);
        if (stream != null && !stream.isEmpty()) {
            field(bos, boundary, "stream", stream);
        }
        for (MultipartFile f : files) {
            bos.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            bos.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" +
                    (f.getOriginalFilename() == null ? "image.png" : f.getOriginalFilename()) + "\"\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            bos.write(("Content-Type: " +
                    (f.getContentType() == null ? "application/octet-stream" : f.getContentType()) + "\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            bos.write(f.getBytes());
            bos.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        bos.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        byte[] multipartBody = bos.toByteArray();
        String contentType = "multipart/form-data; boundary=" + boundary;

        StreamingResponseBody respStream = out ->
                proxyService.proxyPostRaw(site, "/v1/images/edits", auth, multipartBody,
                        contentType, Duration.ofSeconds(600), out, response);

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header("Cache-Control", "no-cache")
                .body(respStream);
    }

    /** 写一个普通文本字段到 multipart 体。 */
    private void field(ByteArrayOutputStream bos, String boundary, String name, String value) throws IOException {
        bos.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        bos.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n")
                .getBytes(StandardCharsets.UTF_8));
    }

    /**
     * JSON 直发：把文本框里的完整请求 JSON 原样转发到指定上游路径。
     *
     * 前端请求：POST /api/raw?path=/v1/images/generations（body 为原始 JSON 文本）
     * 出于安全考虑，path 仅允许 /v1/ 和 /api/v3/ 前缀。
     */
    @PostMapping(value = "/raw")
    public ResponseEntity<StreamingResponseBody> raw(
            @RequestHeader("Authorization") String auth,
            @RequestHeader(name = "X-Site", required = false, defaultValue = "cn") String site,
            @RequestParam("path") String path,
            @RequestBody String body,
            HttpServletResponse response) {

        log.info("Raw request received [site={}, path={}]", site, path);

        if (path == null || !(path.startsWith("/v1/") || path.startsWith("/v1beta/"))) {
            return ResponseEntity.badRequest()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(out -> out.write(
                            "{\"error\":\"path 仅允许 /v1/ 或 /v1beta/ 前缀\"}".getBytes(StandardCharsets.UTF_8)));
        }

        StreamingResponseBody stream = out ->
                proxyService.proxyPostRaw(site, path, auth,
                        body.getBytes(StandardCharsets.UTF_8), "application/json",
                        Duration.ofSeconds(600), out, response);

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header("Cache-Control", "no-cache")
                .body(stream);
    }
}
