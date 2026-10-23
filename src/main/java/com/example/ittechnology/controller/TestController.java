package com.example.ittechnology.controller;

import com.example.ittechnology.service.TestService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 测试验证接口：
 *
 *   POST /api/test/batch   {model, prompts:[...], max_tokens?, concurrency?, temperature?, top_p?, presence_penalty?, frequency_penalty?, seed?}
 *   POST /api/test/ab      {prompt, models:[...], max_tokens?, temperature?, ...}
 *
 * 均为非流式，返回结构化 JSON，供前端渲染统计与逐条结果。
 */
@Slf4j
@RestController
@RequestMapping("/api/test")
@RequiredArgsConstructor
public class TestController {

    private final TestService testService;
    private final ObjectMapper mapper = new ObjectMapper();

    @PostMapping("/batch")
    public ResponseEntity<ObjectNode> batch(
            @RequestHeader("Authorization") String auth,
            @RequestHeader(name = "X-Site", required = false, defaultValue = "cn") String site,
            @RequestBody String body) {
        try {
            JsonNode req = mapper.readTree(body);
            return ResponseEntity.ok(testService.runBatch(site, auth, req));
        } catch (Exception e) {
            log.warn("Batch test failed: {}", e.getMessage());
            ObjectNode err = mapper.createObjectNode();
            err.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(err);
        }
    }

    @PostMapping("/ab")
    public ResponseEntity<ObjectNode> ab(
            @RequestHeader("Authorization") String auth,
            @RequestHeader(name = "X-Site", required = false, defaultValue = "cn") String site,
            @RequestBody String body) {
        try {
            JsonNode req = mapper.readTree(body);
            return ResponseEntity.ok(testService.runAb(site, auth, req));
        } catch (Exception e) {
            log.warn("A/B test failed: {}", e.getMessage());
            ObjectNode err = mapper.createObjectNode();
            err.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(err);
        }
    }

    /** 缓存命中率测试：{model, prompt, rounds?, max_tokens?}，同一提示词连续调用并统计缓存命中。 */
    @PostMapping("/cache")
    public ResponseEntity<ObjectNode> cache(
            @RequestHeader("Authorization") String auth,
            @RequestHeader(name = "X-Site", required = false, defaultValue = "cn") String site,
            @RequestBody String body) {
        try {
            JsonNode req = mapper.readTree(body);
            return ResponseEntity.ok(testService.runCacheTest(site, auth, req));
        } catch (Exception e) {
            log.warn("Cache test failed: {}", e.getMessage());
            ObjectNode err = mapper.createObjectNode();
            err.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(err);
        }
    }

    /** Gemini 显式缓存测试：{model, systemDoc, repeat?, questions?, max_tokens?, ttl?}，先创建 cachedContents 再逐轮引用。 */
    @PostMapping("/cache-explicit")
    public ResponseEntity<ObjectNode> cacheExplicit(
            @RequestHeader("Authorization") String auth,
            @RequestHeader(name = "X-Site", required = false, defaultValue = "cn") String site,
            @RequestBody String body) {
        try {
            JsonNode req = mapper.readTree(body);
            return ResponseEntity.ok(testService.runExplicitCacheTest(site, auth, req));
        } catch (Exception e) {
            log.warn("Explicit cache test failed: {}", e.getMessage());
            ObjectNode err = mapper.createObjectNode();
            err.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(err);
        }
    }

    /** 全模型巡检：对当前 Token 下全部模型发短提示，统计可用/失败/耗时。 */
    @PostMapping("/scan")
    public ResponseEntity<ObjectNode> scan(
            @RequestHeader("Authorization") String auth,
            @RequestHeader(name = "X-Site", required = false, defaultValue = "cn") String site,
            @RequestBody String body) {
        try {
            JsonNode req = mapper.readTree(body);
            return ResponseEntity.ok(testService.runScan(site, auth, req));
        } catch (Exception e) {
            log.warn("Model scan failed: {}", e.getMessage());
            ObjectNode err = mapper.createObjectNode();
            err.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(err);
        }
    }

    /** 结构化输出验证：{model, prompt, schema?}，校验模型输出是否符合 JSON Schema。 */
    @PostMapping("/jsontest")
    public ResponseEntity<ObjectNode> jsontest(
            @RequestHeader("Authorization") String auth,
            @RequestHeader(name = "X-Site", required = false, defaultValue = "cn") String site,
            @RequestBody String body) {
        try {
            JsonNode req = mapper.readTree(body);
            return ResponseEntity.ok(testService.runJsonTest(site, auth, req));
        } catch (Exception e) {
            log.warn("JSON test failed: {}", e.getMessage());
            ObjectNode err = mapper.createObjectNode();
            err.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(err);
        }
    }

    /** 原始请求测试（Apifox 式）：粘贴 JSON 请求体自由打上游，{site?, method?, path, auth?, body?, timeout?}。 */
    @PostMapping("/raw")
    public ResponseEntity<ObjectNode> raw(
            @RequestHeader(name = "Authorization", required = false) String auth,
            @RequestHeader(name = "X-Site", required = false, defaultValue = "cn") String site,
            @RequestBody String body) {
        try {
            JsonNode req = mapper.readTree(body);
            return ResponseEntity.ok(testService.runRaw(site, auth == null ? "" : auth, req));
        } catch (Exception e) {
            log.warn("Raw test failed: {}", e.getMessage());
            ObjectNode err = mapper.createObjectNode();
            err.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(err);
        }
    }

    @PostMapping("/perf")
    public ResponseEntity<ObjectNode> perf(
            @RequestHeader(name = "Authorization", required = false) String auth,
            @RequestHeader(name = "X-Site", required = false, defaultValue = "cn") String site,
            @RequestBody String body) {
        try {
            JsonNode req = mapper.readTree(body);
            return ResponseEntity.ok(testService.runPerf(site, auth == null ? "" : auth, req));
        } catch (Exception e) {
            log.warn("Perf test failed: {}", e.getMessage());
            ObjectNode err = mapper.createObjectNode();
            err.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(err);
        }
    }

    /** 模型能力体检：对一组模型并发跑标准任务集（数学/代码/翻译/JSON），输出能力对比表。 */
    @PostMapping("/capability")
    public ResponseEntity<ObjectNode> capability(
            @RequestHeader(name = "Authorization", required = false) String auth,
            @RequestHeader(name = "X-Site", required = false, defaultValue = "cn") String site,
            @RequestBody String body) {
        try {
            JsonNode req = mapper.readTree(body);
            return ResponseEntity.ok(testService.runCapability(site, auth == null ? "" : auth, req));
        } catch (Exception e) {
            log.warn("Capability test failed: {}", e.getMessage());
            ObjectNode err = mapper.createObjectNode();
            err.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(err);
        }
    }

    /**
     * 视频生成 JSON 直发（火山豆包 /v1/video/generations）：
     * 请求体原样转发，支持 metadata 格式（reference_images/videos/audios 数组）与
     * content 数组格式；素材引用支持公网 URL 或 asset:// + 素材ID。
     *
     * 请求体示例（metadata 格式）：
     * {
     *   "model": "seed-2-vison",
     *   "prompt": "...",
     *   "metadata": {
     *     "reference_images": ["asset://asset-xxx", "https://..."],
     *     "reference_videos": ["https://..."],
     *     "reference_audios": ["asset://asset-yyy"],
     *     "generate_audio": true, "ratio": "16:9", "duration": 5, "watermark": false
     *   }
     * }
     *
     * 可选请求头：
     *   X-Test-Path     上游路径，默认 /v1/video/generations
     *   X-Test-Timeout  超时秒数，默认 120，上限 600
     * 返回 task_id（cgt-xxx），前端用 GET /api/video/poll-v1/{taskId} 轮询进度。
     */
    @PostMapping("/video-generate")
    public ResponseEntity<ObjectNode> videoGenerate(
            @RequestHeader(name = "Authorization", required = false) String auth,
            @RequestHeader(name = "X-Site", required = false, defaultValue = "cn") String site,
            @RequestHeader(name = "X-Test-Path", required = false, defaultValue = "") String testPath,
            @RequestHeader(name = "X-Test-Timeout", required = false, defaultValue = "120") int timeoutSec,
            @RequestBody String body) {
        try {
            JsonNode req = mapper.readTree(body);
            return ResponseEntity.ok(testService.runVideoGenerate(site, auth == null ? "" : auth, req, testPath, timeoutSec));
        } catch (Exception e) {
            log.warn("Video generate test failed: {}", e.getMessage());
            ObjectNode err = mapper.createObjectNode();
            err.put("ok", false);
            err.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(err);
        }
    }

    /**
     * 视频理解测试（验证模型是否支持视频输入，如 kimi-k3）：
     *
     * 用法一 · 直接直发（请求体带 messages 数组，原样转发）：
     * {
     *   "model": "kimi-k3",
     *   "messages": [ { "role": "user", "content": [
     *       { "type": "video_url", "video_url": { "url": "ms://xxxx-xxxx" } },
     *       { "type": "text", "text": "总结这个视频" } ] } ]
     * }
     *
     * 用法二 · 便捷构建（后端组装上面的 OpenAI 内容块）：
     * { "model": "kimi-k3", "prompt": "总结这个视频",
     *   "video_url": "ms://xxxx-xxxx",          // 或 "video_b64": "<base64>", "mime": "video/mp4"
     *   "max_tokens": 1024 }
     *
     * video_url 支持 ms:// 素材引用 / https:// 公网地址 / data:video/...;base64,
     * 两个用法均强制 stream=false，返回 text（已兼容 OpenAI/Anthropic/Gemini 响应结构）+ raw。
     *
     * 可选请求头：
     *   X-Test-Path     上游路径，默认 /v1/chat/completions
     *   X-Test-Timeout  超时秒数，默认 120，上限 600
     */
    @PostMapping("/video-understand")
    public ResponseEntity<ObjectNode> videoUnderstand(
            @RequestHeader(name = "Authorization", required = false) String auth,
            @RequestHeader(name = "X-Site", required = false, defaultValue = "cn") String site,
            @RequestHeader(name = "X-Test-Path", required = false, defaultValue = "") String testPath,
            @RequestHeader(name = "X-Test-Timeout", required = false, defaultValue = "120") int timeoutSec,
            @RequestBody String body) {
        try {
            JsonNode req = mapper.readTree(body);
            return ResponseEntity.ok(testService.runVideoUnderstand(site, auth == null ? "" : auth, req, testPath, timeoutSec));
        } catch (Exception e) {
            log.warn("Video understand test failed: {}", e.getMessage());
            ObjectNode err = mapper.createObjectNode();
            err.put("ok", false);
            err.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(err);
        }
    }

    /**
     * 图像蒙版测试（OpenAI /v1/images/edits multipart 格式，curl 模板）：
     * 请求体 {model, image_b64, mask_b64, prompt, mime?, mask_mime?, size?, quality?, output_format?, n?}
     * 后端自动组装 multipart/form-data（image[]=原图、mask=蒙版）转发上游，返回编辑结果图 base64。
     *
     * 若请求体带 messages 数组则视为直接直发（Anthropic /v1/messages，image 块带 mask），原样转发。
     *
     * 可选请求头：
     *   X-Test-Path     上游路径，默认 /v1/images/edits
     *   X-Test-Timeout  超时秒数，默认 300，上限 600（图像编辑较慢）
     */
    @PostMapping("/image-mask")
    public ResponseEntity<ObjectNode> imageMask(
            @RequestHeader(name = "Authorization", required = false) String auth,
            @RequestHeader(name = "X-Site", required = false, defaultValue = "cn") String site,
            @RequestHeader(name = "X-Test-Path", required = false, defaultValue = "") String testPath,
            @RequestHeader(name = "X-Test-Timeout", required = false, defaultValue = "300") int timeoutSec,
            @RequestBody String body) {
        try {
            JsonNode req = mapper.readTree(body);
            return ResponseEntity.ok(testService.runImageMask(site, auth == null ? "" : auth, req, testPath, timeoutSec));
        } catch (Exception e) {
            log.warn("Image mask test failed: {}", e.getMessage());
            ObjectNode err = mapper.createObjectNode();
            err.put("ok", false);
            err.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(err);
        }
    }
}
