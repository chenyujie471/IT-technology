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
}
