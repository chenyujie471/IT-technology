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
 * 文字对话代理 — 对接 Claude (Anthropic Messages API)
 *
 * 前端请求：POST /api/chat
 * 上游转发：POST https://ai-api.kkidc.com/v1/messages
 *
 * 支持模型：claude-haiku-4-5-20251001 / claude-sonnet-4-6 / claude-opus-4-6 / claude-opus-4-7
 * 响应格式：SSE 流式（前端请求体中 "stream": true）或完整 JSON（"stream": false）
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ChatController {

    private final AiProxyService proxyService;

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> chat(
            @RequestHeader("Authorization") String auth,
            @RequestBody String body) {

        log.info("Chat request, auth prefix: {}", auth.substring(0, Math.min(12, auth.length())));

        StreamingResponseBody stream = out ->
                proxyService.proxyPostStream("/v1/messages", auth, body, Duration.ofSeconds(120), out);

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header("Cache-Control", "no-cache")
                .header("X-Accel-Buffering", "no")    // 禁止 Nginx 缓冲，确保 SSE 实时推送
                .body(stream);
    }
}
