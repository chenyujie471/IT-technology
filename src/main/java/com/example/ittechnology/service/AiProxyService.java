package com.example.ittechnology.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Slf4j
@Service
public class AiProxyService {

    @Value("${ai.upstream:https://ai-api.kkidc.com}")
    private String upstream;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    /**
     * 代理 POST 请求，将上游响应流直接 pipe 到 out。
     * 用于：Chat SSE 流式输出、图像生成（大 base64 响应）、视频任务提交。
     */
    public void proxyPostStream(String path, String auth, String body,
                                Duration timeout, OutputStream out) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(upstream + path))
                .header("Authorization", auth)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .timeout(timeout)
                .build();

        try {
            HttpResponse<InputStream> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            log.debug("Upstream [POST {}] → {}", path, status);
            try (InputStream in = response.body()) {
                if (status >= 400) {
                    // 上游返回错误：将错误 JSON 包装为 SSE data 事件，前端 SSE 循环可直接解析
                    String errorBody = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    String sseEvent = "data: " + errorBody + "\n\n";
                    out.write(sseEvent.getBytes(StandardCharsets.UTF_8));
                } else {
                    in.transferTo(out);
                }
            }
            out.flush();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Request interrupted: {}", path);
            writeError(out, "Request interrupted");
        } catch (IOException e) {
            log.error("Proxy POST error [{}]: {}", path, e.getMessage());
            writeError(out, e.getMessage());
        }
    }

    /**
     * 代理 GET 请求，返回上游真实状态码 + 响应体。
     * 用于：Token 验证（透传真实 HTTP 状态码，不自己猜错误）。
     */
    public ResponseEntity<String> proxyGetResponse(String path, String auth, Duration timeout) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(upstream + path))
                .header("Authorization", auth)
                .GET()
                .timeout(timeout)
                .build();

        try {
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            log.debug("Upstream [GET {}] → {}", path, response.statusCode());
            return ResponseEntity.status(response.statusCode())
                    .body(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(500).body("{\"error\":\"Request interrupted\"}");
        } catch (IOException e) {
            log.error("Proxy GET error [{}]: {}", path, e.getMessage());
            return ResponseEntity.status(502).body("{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}");
        }
    }

    /**
     * 代理 GET 请求，返回响应体字符串。
     * 用于：轮询视频任务状态。
     */
    public String proxyGet(String path, String auth, Duration timeout) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(upstream + path))
                .header("Authorization", auth)
                .GET()
                .timeout(timeout)
                .build();

        try {
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            log.debug("Upstream [GET {}] → {}", path, response.statusCode());
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "{\"error\":\"Request interrupted\"}";
        } catch (IOException e) {
            log.error("Proxy GET error [{}]: {}", path, e.getMessage());
            return "{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}";
        }
    }

    private void writeError(OutputStream out, String message) {
        try {
            String err = "{\"error\":\"" + message.replace("\"", "'") + "\"}";
            out.write(err.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException ignored) {}
    }
}
