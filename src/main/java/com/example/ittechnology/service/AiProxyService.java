package com.example.ittechnology.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import org.springframework.http.ResponseEntity;

import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;

@Slf4j
@Service
public class AiProxyService {

    /** 国内站上游 */
    @Value("${ai.upstream:https://ai-api.kkidc.com}")
    private String upstream;

    /** 国际站上游 */
    @Value("${ai.upstream-intl:https://www.aiide.com}")
    private String upstreamIntl;

    private final RequestLogService requestLog;

    public AiProxyService(RequestLogService requestLog) {
        this.requestLog = requestLog;
    }

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    /**
     * 根据站点标识解析上游基地址。
     * site = "intl" → 国际站（https://www.aiide.com），其余 → 国内站。
     */
    public String resolveBase(String site) {
        return "intl".equalsIgnoreCase(site) ? upstreamIntl : upstream;
    }

    /**
     * 代理 POST 请求，将上游响应流直接 pipe 到 out。
     * 用于：Chat SSE 流式输出、图像生成（大 base64 响应）、视频任务提交。
     *
     * 通过 resp 透传上游真实的 HTTP 状态码到 X-Upstream-Status 响应头，
     * 让前端能据此准确区分成功 / 4xx / 5xx，而不是仅靠 body 内容猜测。
     */
    public void proxyPostStream(String site, String path, String auth, String body,
                                Duration timeout, OutputStream out, HttpServletResponse resp) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(resolveBase(site) + path))
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
            setStatusHeader(resp, status);
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
            setStatusHeader(resp, 500);
            writeError(out, "Request interrupted");
        } catch (IOException e) {
            log.error("Proxy POST error [{}]: {}", path, e.getMessage());
            setStatusHeader(resp, 502);
            writeError(out, e.getMessage());
        }
    }

    /**
     * 代理 POST 请求（任意 Content-Type / 原始字节体），将上游响应流直接 pipe 到 out。
     * 用于：图像编辑（multipart/form-data）、JSON 直发等。
     * 透传上游真实状态码到 X-Upstream-Status 头。
     */
    public void proxyPostRaw(String site, String path, String auth, byte[] body, String contentType,
                             Duration timeout, OutputStream out, HttpServletResponse resp) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(resolveBase(site) + path))
                .header("Authorization", auth)
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .timeout(timeout)
                .build();
        pipe(request, path, out, resp);
    }

    /** 发送请求并把响应流 pipe 到 out（错误包装为 JSON 写出）。透传上游状态码。 */
    private void pipe(HttpRequest request, String path, OutputStream out, HttpServletResponse resp) {
        try {
            HttpResponse<InputStream> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            log.debug("Upstream [POST {}] → {}", path, status);
            setStatusHeader(resp, status);
            try (InputStream in = response.body()) {
                if (status >= 400) {
                    String errorBody = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    out.write(errorBody.getBytes(StandardCharsets.UTF_8));
                } else {
                    in.transferTo(out);
                }
            }
            out.flush();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Request interrupted: {}", path);
            setStatusHeader(resp, 500);
            writeError(out, "Request interrupted");
        } catch (IOException e) {
            log.error("Proxy POST error [{}]: {}", path, e.getMessage());
            setStatusHeader(resp, 502);
            writeError(out, e.getMessage());
        }
    }

    /**
     * 代理 GET 请求，返回上游真实状态码 + 响应体。
     * 用于：Token 验证（透传真实 HTTP 状态码，不自己猜错误）。
     */
    public ResponseEntity<String> proxyGetResponse(String site, String path, String auth, Duration timeout) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(resolveBase(site) + path))
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
     * 代理 GET 请求，返回响应体字符串，并通过 statusHolder 带出上游真实状态码。
     * 用于：轮询视频任务状态、模型列表。
     */
    public String proxyGet(String site, String path, String auth, Duration timeout,
                           AtomicInteger statusHolder) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(resolveBase(site) + path))
                .header("Authorization", auth)
                .GET()
                .timeout(timeout)
                .build();

        try {
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            log.debug("Upstream [GET {}] → {}", path, status);
            if (statusHolder != null) statusHolder.set(status);
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (statusHolder != null) statusHolder.set(500);
            return "{\"error\":\"Request interrupted\"}";
        } catch (IOException e) {
            log.error("Proxy GET error [{}]: {}", path, e.getMessage());
            if (statusHolder != null) statusHolder.set(502);
            return "{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}";
        }
    }

    /**
     * 代理 POST 请求并返回完整响应体字符串（非流式）。
     * 用于工作流引擎等需要拿到完整 JSON 响应的场景（对话 / 图像 / 视频提交）。
     * 返回上游真实状态码 + 响应体。
     */
    public FetchResult postJson(String site, String path, String auth, String body, Duration timeout) {
        long t0 = System.currentTimeMillis();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(resolveBase(site) + path))
                .header("Authorization", auth)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .timeout(timeout)
                .build();

        try {
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            log.debug("Upstream [POST {}] → {}", path, status);
            FetchResult r = new FetchResult(status, response.body());
            requestLog.record(site, path, auth, body, status, System.currentTimeMillis() - t0, r.body());
            return r;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            requestLog.record(site, path, auth, body, 500, System.currentTimeMillis() - t0, "{\"error\":\"Request interrupted\"}");
            return new FetchResult(500, "{\"error\":\"Request interrupted\"}");
        } catch (IOException e) {
            log.error("Proxy POST error [{}]: {}", path, e.getMessage());
            requestLog.record(site, path, auth, body, 502, System.currentTimeMillis() - t0, "{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}");
            return new FetchResult(502, "{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}");
        }
    }

    /**
     * 通用 GET 抓取任意 URL（用于模型广场等外部数据源），自动解压 gzip 响应。
     * auth 非空时以 Authorization 头传递；返回上游真实状态码 + 响应体。
     */
    public FetchResult fetch(String url, String auth, Duration timeout) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .timeout(timeout);
        if (auth != null && !auth.isEmpty()) {
            builder.header("Authorization", auth);
        }
        HttpRequest request = builder.build();

        try {
            HttpResponse<InputStream> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            String encoding = response.headers().firstValue("Content-Encoding").orElse("");
            byte[] bytes;
            try (InputStream in = response.body()) {
                if ("gzip".equalsIgnoreCase(encoding)) {
                    try (GZIPInputStream gz = new GZIPInputStream(in)) {
                        bytes = gz.readAllBytes();
                    }
                } else {
                    bytes = in.readAllBytes();
                }
            }
            log.debug("Upstream [GET {}] → {}", url, status);
            return new FetchResult(status, new String(bytes, StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new FetchResult(500, "{\"error\":\"Request interrupted\"}");
        } catch (IOException e) {
            log.error("Fetch error [{}]: {}", url, e.getMessage());
            return new FetchResult(502, "{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}");
        }
    }

    /** GET 抓取结果：上游 HTTP 状态码 + 响应体。 */
    public record FetchResult(int status, String body) {}

    /** 把上游状态码写入 X-Upstream-Status 响应头（若 resp 非空且响应未提交）。 */
    private void setStatusHeader(HttpServletResponse resp, int status) {
        if (resp == null) return;
        try {
            resp.setHeader("X-Upstream-Status", String.valueOf(status));
        } catch (IllegalStateException ignored) {
            // 响应已提交，忽略
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
