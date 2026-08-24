package com.example.ittechnology.controller;

import com.example.ittechnology.service.AiProxyService;
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

        log.info("Chat request [api={}, model={}]", fmt, model);

        final String upstreamBody = outBody;
        StreamingResponseBody stream = out ->
                proxyService.proxyPostStream(null, path, auth, upstreamBody, Duration.ofSeconds(120), out, response);

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
