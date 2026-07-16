package com.example.ittechnology.controller;

import com.example.ittechnology.service.AiProxyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.time.Duration;

/**
 * 图像生成代理 — 对接 gpt-image-2
 *
 * 前端请求：POST /api/image
 * 上游转发：POST https://ai-api.kkidc.com/v1/images/generations
 *
 * ⚠️ 图像生成最长耗时 600s，必须用 StreamingResponseBody（异步线程）
 * 响应：JSON { data: [{ b64_json: "..." }] }
 *
 * 请求体示例：
 * {
 *   "model": "gpt-image-2",
 *   "prompt": "...",
 *   "size": "1024x1024",
 *   "n": 1,
 *   "quality": "high",
 *   "response_format": "b64_json",
 *   "stream": true
 * }
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ImageController {

    private final AiProxyService proxyService;

    @PostMapping(value = "/image", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<StreamingResponseBody> generateImage(
            @RequestHeader("Authorization") String auth,
            @RequestBody String body) {

        log.info("Image generation request received");

        StreamingResponseBody stream = out ->
                proxyService.proxyPostStream(
                        "/v1/images/generations", auth, body, Duration.ofSeconds(600), out);

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header("Cache-Control", "no-cache")
                .body(stream);
    }
}
