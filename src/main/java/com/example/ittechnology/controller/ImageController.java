package com.example.ittechnology.controller;

import com.example.ittechnology.service.AiProxyService;
import com.example.ittechnology.util.ImageMime;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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

    /**
     * 图像并发批量生成（服务端控制并发，逐张 SSE 输出）。
     *
     * 前端请求：POST /api/image/batch
     * body: { "concurrency": 10, "request": { ...与 /api/image 相同的请求体... } }
     *   - concurrency：0-100，0 或 1 等效单张；上限 100。
     *   - request：OpenAI 格式或 Gemini 格式均可（复用 /api/image 的解析规则）。
     *
     * 响应：SSE 文本流（Content-Type: text/event-stream），哪张先完成就先推送，前端可逐张上屏：
     *   data: {"type":"img","index":0,"ok":true,"mime":"image/png","b64":"...","rev":"...","ms":12345}
     *   data: {"type":"img","index":3,"ok":false,"status":429,"error":"rate limit","ms":900}
     *   data: {"type":"summary","total":10,"ok":8,"fail":2,"totalMs":45000}
     *
     * 实现要点：
     *   - 固定线程池（大小 = 并发数）+ ExecutorCompletionService：完成顺序即输出顺序，
     *     服务端同一时刻内存中最多持有 并发数 张图的 base64（取到即写走）。
     *   - 每张任务最长 600s（与单张一致）；整批墙钟时间 ≈ 最慢的一张，受
     *     spring.mvc.async.request-timeout=660s 覆盖。
     *   - 并发任务日志走 postJsonStreamCollect：成功只入库首部 4KB 摘要，避免日志表爆炸。
     */
    @PostMapping(value = "/image/batch", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> batchGenerate(
            @RequestHeader("Authorization") String auth,
            @RequestHeader(name = "X-Site", required = false, defaultValue = "cn") String site,
            @RequestBody String body) {

        final int concurrency;
        final Route route;
        try {
            JsonNode json = objectMapper.readTree(body);
            int c = json.path("concurrency").asInt(1);
            concurrency = Math.max(1, Math.min(100, c));
            JsonNode reqNode = json.path("request");
            if (reqNode.isMissingNode() || reqNode.isNull() || !reqNode.isObject()) {
                // 兼容直接传图像请求体（不带 request 包裹）的调用方
                reqNode = json;
            }
            route = routeImage(reqNode);
        } catch (Exception e) {
            log.warn("Failed to parse batch request: {}", e.getMessage());
            String err = "{\"error\":\"" + escJson(e.getMessage()) + "\"}";
            StreamingResponseBody errStream = out -> {
                out.write(err.getBytes(StandardCharsets.UTF_8));
                out.flush();
            };
            return ResponseEntity.badRequest()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(errStream);
        }

        log.info("Image batch request [site={}, concurrency={}, gemini={}, path={}]",
                site, concurrency, route.gemini(), route.path());

        StreamingResponseBody stream = out -> {
            ExecutorService pool = Executors.newFixedThreadPool(concurrency);
            ExecutorCompletionService<BatchItem> ecs = new ExecutorCompletionService<>(pool);
            long t0 = System.currentTimeMillis();
            int ok = 0, fail = 0;
            try {
                for (int i = 0; i < concurrency; i++) {
                    final int idx = i;
                    ecs.submit(() -> runOne(idx, site, auth, route));
                }
                for (int i = 0; i < concurrency; i++) {
                    Future<BatchItem> f = ecs.take();
                    BatchItem item;
                    try {
                        item = f.get();
                    } catch (ExecutionException ee) {
                        item = BatchItem.error(i, 0, ee.getCause() != null
                                ? String.valueOf(ee.getCause().getMessage()) : "task failed", 0);
                    }
                    if (item.ok()) ok++; else fail++;
                    writeSse(out, item.toJson(objectMapper).toString());
                }
            } catch (Exception e) {
                // 客户端断开（StreamingResponseBody 被打断）等：停止推送，任务仍在池中跑完
                log.warn("Batch stream interrupted after {} done: {}", ok + fail, e.getMessage());
            } finally {
                pool.shutdown();
                ObjectNode summary = objectMapper.createObjectNode();
                summary.put("type", "summary");
                summary.put("total", concurrency);
                summary.put("ok", ok);
                summary.put("fail", fail);
                summary.put("totalMs", System.currentTimeMillis() - t0);
                try {
                    writeSse(out, summary.toString());
                } catch (IOException ignored) {}
            }
        };

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header("Cache-Control", "no-cache")
                .body(stream);
    }

    /** 路由结果：上游路径 + 转发体 + 是否 Gemini 格式。 */
    private record Route(String path, String body, boolean gemini) {}

    /**
     * 解析图像请求 JSON，路由到 OpenAI /v1/images/generations 或 Gemini generateContent。
     * OpenAI 转发体去掉 stream 字段：批量模式按非流式完整 JSON 收集，确保响应是纯 JSON。
     */
    private Route routeImage(JsonNode json) throws IOException {
        String model = json.has("model") ? json.get("model").asText() : "";
        boolean isGemini = model.toLowerCase().contains("gemini") &&
                         (model.toLowerCase().contains("image") ||
                          model.toLowerCase().contains("flash-preview"));
        if (isGemini) {
            String path = "/v1beta/models/" + model + ":generateContent";
            ObjectNode g = objectMapper.createObjectNode();
            if (json.has("generationConfig")) g.set("generationConfig", json.get("generationConfig"));
            if (json.has("contents")) g.set("contents", json.get("contents"));
            return new Route(path, objectMapper.writeValueAsString(g), true);
        }
        ObjectNode o = objectMapper.createObjectNode();
        json.fields().forEachRemaining(e -> {
            if (!"stream".equals(e.getKey())) o.set(e.getKey(), e.getValue());
        });
        return new Route("/v1/images/generations", objectMapper.writeValueAsString(o), false);
    }

    /** 单个并发任务：转发上游、收集完整响应、解析图片，返回 BatchItem（内部捕获一切异常，永不抛出）。 */
    private BatchItem runOne(int index, String site, String auth, Route route) {
        long t0 = System.currentTimeMillis();
        try {
            AiProxyService.StreamCollectResult r = proxyService.postJsonStreamCollect(
                    site, route.path(), auth, route.body(), Duration.ofSeconds(600));
            long ms = System.currentTimeMillis() - t0;
            if (r.status() >= 400) {
                return BatchItem.error(index, r.status(), extractError(r.body()), ms);
            }
            JsonNode j = objectMapper.readTree(r.body());
            if (route.gemini()) {
                // Gemini 格式：candidates[0].content.parts[*].inlineData
                JsonNode parts = j.path("candidates").path(0).path("content").path("parts");
                JsonNode inline = null;
                for (JsonNode p : parts) {
                    if (p.has("inlineData")) { inline = p.path("inlineData"); break; }
                }
                if (inline == null) {
                    return BatchItem.error(index, r.status(), "响应中没有图片数据: " + extractError(r.body()), ms);
                }
                String b64 = inline.path("data").asText("");
                String mime = inline.path("mimeType").asText("");
                if (b64.isEmpty()) {
                    return BatchItem.error(index, r.status(), "响应中没有图片数据: " + extractError(r.body()), ms);
                }
                if (mime.isEmpty() || !mime.contains("/")) {
                    mime = ImageMime.detect(b64);
                    if (mime == null) mime = "image/png";
                }
                return BatchItem.ok(index, b64, mime, "", ms);
            }
            // OpenAI 格式：data[0].b64_json
            JsonNode d0 = j.path("data").path(0);
            String b64 = d0.path("b64_json").asText("");
            if (b64.isEmpty()) {
                return BatchItem.error(index, r.status(), "响应中没有图片数据: " + extractError(r.body()), ms);
            }
            String mime = ImageMime.detect(b64);
            if (mime == null) mime = "image/png";
            return BatchItem.ok(index, b64, mime, d0.path("revised_prompt").asText(""), ms);
        } catch (Exception e) {
            log.warn("Batch task #{} failed: {}", index, e.getMessage());
            return BatchItem.error(index, 0, e.getMessage(), System.currentTimeMillis() - t0);
        }
    }

    /** 单个并发结果（SSE 事件载体）。 */
    private record BatchItem(int index, boolean ok, int status, String b64, String mime,
                             String rev, String error, long ms) {
        static BatchItem ok(int index, String b64, String mime, String rev, long ms) {
            return new BatchItem(index, true, 200, b64, mime, rev, null, ms);
        }
        static BatchItem error(int index, int status, String error, long ms) {
            return new BatchItem(index, false, status, null, null, null, error, ms);
        }
        ObjectNode toJson(ObjectMapper mapper) {
            ObjectNode n = mapper.createObjectNode();
            n.put("type", "img");
            n.put("index", index);
            n.put("ok", ok);
            n.put("status", status);
            n.put("ms", ms);
            if (ok) {
                n.put("mime", mime);
                n.put("b64", b64);
                if (rev != null && !rev.isEmpty()) n.put("rev", rev);
            } else {
                n.put("error", error == null ? "" : error);
            }
            return n;
        }
    }

    /** 写一条 SSE data 事件并 flush。 */
    private void writeSse(OutputStream out, String json) throws IOException {
        out.write(("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** 从上游错误 JSON 中提取可读信息（兼容 OpenAI / Anthropic / 网关格式）。 */
    private String extractError(String raw) {
        if (raw == null || raw.isEmpty()) return "上游返回空响应";
        String clip = raw.length() > 400 ? raw.substring(0, 400) + "…" : raw;
        try {
            JsonNode j = objectMapper.readTree(raw);
            JsonNode e = j.path("error");
            if (e.isObject()) {
                if (e.has("message")) return e.get("message").asText("");
                if (e.has("msg")) return e.get("msg").asText("");
                return e.toString();
            }
            if (e.isTextual()) return e.asText();
            if (j.has("message")) return j.get("message").asText("");
            if (j.has("msg")) return j.get("msg").asText("");
            return clip;
        } catch (Exception ex) {
            return clip;
        }
    }

    /** 把字符串嵌入 JSON 双引号字符串（转义反斜杠/引号/换行）。 */
    private String escJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", " ").replace("\n", " ");
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
