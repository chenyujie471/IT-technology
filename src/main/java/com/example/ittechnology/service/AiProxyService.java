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
     * 规范化 Authorization 请求头，避免因 Token 粘贴格式导致上游 401 Invalid token：
     *   - 纯 Token（无 scheme）→ 自动补 "Bearer "
     *   - 已带 scheme（Bearer/Basic/Token/Digest）→ 保留一层
     *   - 重复 scheme（如 "Bearer Bearer sk-xxx"，用户在输入框连同前缀一起粘贴时常见）→ 折叠为一层
     */
    private static String normalizeAuth(String auth) {
        String a = auth == null ? "" : auth.trim();
        if (a.isEmpty()) return "";
        String[] parts = a.split("\\s+");
        int i = 0;
        String scheme = null;
        while (i < parts.length && parts[i].matches("(?i)Bearer|Basic|Token|Digest")) {
            if (scheme == null) scheme = parts[i];
            i++;   // 跳过重复的 scheme
        }
        StringBuilder rest = new StringBuilder();
        for (int j = i; j < parts.length; j++) {
            if (rest.length() > 0) rest.append(' ');
            rest.append(parts[j]);
        }
        if (rest.length() == 0) return scheme == null ? "" : scheme;
        return (scheme == null ? "Bearer" : scheme) + " " + rest;
    }

    /**
     * 代理 POST 请求，将上游响应流直接 pipe 到 out。
     * 用于：Chat SSE 流式输出、图像生成（大 base64 响应）、视频任务提交。
     *
     * 通过 resp 透传上游真实的 HTTP 状态码到 X-Upstream-Status 响应头，
     * 让前端能据此准确区分成功 / 4xx / 5xx，而不是仅靠 body 内容猜测。
     *
     * 落库日志：请求体 + 状态码 + 耗时 + 响应体首部 4KB 摘要（完整 SSE/base64 过大不入库）。
     */
    public void proxyPostStream(String site, String path, String auth, String body,
                                Duration timeout, OutputStream out, HttpServletResponse resp) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(resolveBase(site) + path))
                .header("Authorization", normalizeAuth(auth))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .timeout(timeout)
                .build();

        long t0 = System.currentTimeMillis();
        final int LOG_CAP = 4096;
        byte[] logBuf = new byte[LOG_CAP];
        int logLen = 0;
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
                    out.flush();
                    requestLog.record(site, path, auth, body, status, System.currentTimeMillis() - t0, errorBody);
                } else {
                    // 逐块读取 + 每块 flush：确保 SSE 增量实时到达浏览器，
                    // 而不是攒满 Tomcat 缓冲区才一次性发送（否则表现为“几段字一起蹦”）
                    byte[] chunk = new byte[2048];
                    int n;
                    while ((n = in.read(chunk)) != -1) {
                        out.write(chunk, 0, n);
                        out.flush();
                        if (logLen < LOG_CAP) {
                            int take = Math.min(n, LOG_CAP - logLen);
                            System.arraycopy(chunk, 0, logBuf, logLen, take);
                            logLen += take;
                        }
                    }
                    String respLog = logLen == 0 ? null
                            : new String(logBuf, 0, logLen, StandardCharsets.UTF_8)
                              + (logLen >= LOG_CAP ? "\n…[stream truncated]" : "");
                    requestLog.record(site, path, auth, body, status, System.currentTimeMillis() - t0, respLog);
                }
            }
            out.flush();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Request interrupted: {}", path);
            setStatusHeader(resp, 500);
            writeError(out, "Request interrupted");
            requestLog.record(site, path, auth, body, 500, System.currentTimeMillis() - t0, "{\"error\":\"Request interrupted\"}");
        } catch (IOException e) {
            log.error("Proxy POST error [{}]: {}", path, e.getMessage());
            setStatusHeader(resp, 502);
            writeError(out, e.getMessage());
            requestLog.record(site, path, auth, body, 502, System.currentTimeMillis() - t0, "{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}");
        }
    }

    /**
     * 代理 POST 请求（任意 Content-Type / 原始字节体），将上游响应流直接 pipe 到 out。
     * 用于：图像编辑（multipart/form-data）、JSON 直发等。
     * 透传上游真实状态码到 X-Upstream-Status 头。
     *
     * 落库日志：请求体为二进制摘要（类型+字节数，不存原始字节）+ 状态码 + 耗时 + 响应首部 4KB。
     */
    public void proxyPostRaw(String site, String path, String auth, byte[] body, String contentType,
                             Duration timeout, OutputStream out, HttpServletResponse resp) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(resolveBase(site) + path))
                .header("Authorization", normalizeAuth(auth))
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .timeout(timeout)
                .build();
        String bodyLog = "<" + contentType + " 二进制 " + (body == null ? 0 : body.length) + " bytes>";
        pipe(request, path, site, auth, bodyLog, out, resp);
    }

    /**
     * 发送请求并把响应流 pipe 到 out（错误包装为 JSON 写出）。透传上游状态码。
     * 同时记录请求日志（reqBodyLog 为待入库的请求体文本；流式响应只存首部 4KB 摘要）。
     */
    private void pipe(HttpRequest request, String path, String site, String auth, String reqBodyLog,
                      OutputStream out, HttpServletResponse resp) {
        long t0 = System.currentTimeMillis();
        final int LOG_CAP = 4096;
        byte[] logBuf = new byte[LOG_CAP];
        int logLen = 0;
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
                    requestLog.record(site, path, auth, reqBodyLog, status, System.currentTimeMillis() - t0, errorBody);
                } else {
                    byte[] chunk = new byte[2048];
                    int n;
                    while ((n = in.read(chunk)) != -1) {
                        out.write(chunk, 0, n);
                        out.flush();
                        if (logLen < LOG_CAP) {
                            int take = Math.min(n, LOG_CAP - logLen);
                            System.arraycopy(chunk, 0, logBuf, logLen, take);
                            logLen += take;
                        }
                    }
                    String respLog = logLen == 0 ? null
                            : new String(logBuf, 0, logLen, StandardCharsets.UTF_8)
                              + (logLen >= LOG_CAP ? "\n…[stream truncated]" : "");
                    requestLog.record(site, path, auth, reqBodyLog, status, System.currentTimeMillis() - t0, respLog);
                }
            }
            out.flush();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Request interrupted: {}", path);
            setStatusHeader(resp, 500);
            writeError(out, "Request interrupted");
            requestLog.record(site, path, auth, reqBodyLog, 500, System.currentTimeMillis() - t0, "{\"error\":\"Request interrupted\"}");
        } catch (IOException e) {
            log.error("Proxy POST error [{}]: {}", path, e.getMessage());
            setStatusHeader(resp, 502);
            writeError(out, e.getMessage());
            requestLog.record(site, path, auth, reqBodyLog, 502, System.currentTimeMillis() - t0, "{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}");
        }
    }

    /**
     * 代理 GET 请求，返回上游真实状态码 + 响应体。
     * 用于：Token 验证（透传真实 HTTP 状态码，不自己猜错误）。
     */
    public ResponseEntity<String> proxyGetResponse(String site, String path, String auth, Duration timeout) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(resolveBase(site) + path))
                .header("Authorization", normalizeAuth(auth))
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
                .header("Authorization", normalizeAuth(auth))
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
        return postJsonWithHeaders(site, path, auth, null, body, timeout);
    }

    /**
     * 代理 POST 请求（任意 Content-Type / 原始字节体）并返回完整响应体字符串（非流式）。
     * 用于：图像编辑（multipart/form-data）、二进制直发等需要拿到完整 JSON 响应的场景。
     * 返回上游真实状态码 + 响应体；落库日志：请求体为二进制摘要（类型+字节数）。
     */
    public FetchResult postRawBytes(String site, String path, String auth, byte[] body, String contentType,
                                    Duration timeout) {
        long t0 = System.currentTimeMillis();
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(resolveBase(site) + path))
                .header("Authorization", normalizeAuth(auth))
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .timeout(timeout);
        HttpRequest request = builder.build();
        String bodyLog = "<" + contentType + " 二进制 " + (body == null ? 0 : body.length) + " bytes>";

        try {
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            log.debug("Upstream [POST {}] → {}", path, status);
            FetchResult r = new FetchResult(status, response.body());
            requestLog.record(site, path, auth, bodyLog, status, System.currentTimeMillis() - t0, r.body());
            return r;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            requestLog.record(site, path, auth, bodyLog, 500, System.currentTimeMillis() - t0, "{\"error\":\"Request interrupted\"}");
            return new FetchResult(500, "{\"error\":\"Request interrupted\"}");
        } catch (IOException e) {
            log.error("Proxy POST raw error [{}]: {}", path, e.getMessage());
            requestLog.record(site, path, auth, bodyLog, 502, System.currentTimeMillis() - t0, "{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}");
            return new FetchResult(502, "{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}");
        }
    }

    /**
     * 代理 POST 请求并返回完整响应体字符串（非流式），支持附加自定义请求头
     * （如 anthropic-beta、x-api-key 等，用于功能开关 / 权限验证类测试）。
     * extraHeaders 为 null 或空时与 postJson 行为一致。
     */
    public FetchResult postJsonWithHeaders(String site, String path, String auth,
                                           java.util.Map<String, String> extraHeaders,
                                           String body, Duration timeout) {
        long t0 = System.currentTimeMillis();
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(resolveBase(site) + path))
                .header("Authorization", normalizeAuth(auth))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .timeout(timeout);
        if (extraHeaders != null) {
            extraHeaders.forEach((k, v) -> {
                if (k != null && !k.isBlank() && v != null) {
                    builder.header(k.trim(), v);
                }
            });
        }
        HttpRequest request = builder.build();

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

    /**
     * 流式收集结果：上游 HTTP 状态码 + 累积的原始响应体 + 首字节耗时 + 总耗时。
     * 用于性能基准测试：需要拿到流式响应的原始行来做 TTFT / token 数 / TPS 统计。
     */
    public record StreamCollectResult(int status, String body, long firstByteMs, long totalMs) {}

    /**
     * 流式 POST 请求，逐块读取并累积原始响应体（不做任何解析）。
     * 记录首字节到达耗时（firstByteMs）与总耗时（totalMs），用于性能基准统计。
     */
    public StreamCollectResult postJsonStreamCollect(String site, String path, String auth, String body,
                                                     Duration timeout) {
        String base = resolveBase(site);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(base + path))
                .header("Authorization", normalizeAuth(auth))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .timeout(timeout)
                .build();

        long t0 = System.currentTimeMillis();
        try {
            HttpResponse<InputStream> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            long firstByte = -1;
            StringBuilder sb = new StringBuilder();
            try (InputStream in = response.body()) {
                byte[] chunk = new byte[8192];
                int n;
                while ((n = in.read(chunk)) != -1) {
                    if (firstByte < 0) firstByte = System.currentTimeMillis() - t0;
                    sb.append(new String(chunk, 0, n, StandardCharsets.UTF_8));
                }
            }
            long total = System.currentTimeMillis() - t0;
            if (status >= 400) {
                requestLog.record(site, path, auth, body, status, total, sb.toString());
            } else {
                // 成功：存响应首部 4KB 摘要（完整 SSE 过大不入库）
                String head = sb.length() > 4096
                        ? sb.substring(0, 4096) + "\n…[stream truncated]"
                        : sb.toString();
                requestLog.record(site, path, auth, body, status, total, head.isEmpty() ? null : head);
            }
            return new StreamCollectResult(status, sb.toString(), firstByte, total);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            long total = System.currentTimeMillis() - t0;
            requestLog.record(site, path, auth, body, 500, total, "{\"error\":\"Request interrupted\"}");
            return new StreamCollectResult(500, "{\"error\":\"Request interrupted\"}", -1, total);
        } catch (IOException e) {
            long total = System.currentTimeMillis() - t0;
            log.error("Stream collect error [{}]: {}", path, e.getMessage());
            requestLog.record(site, path, auth, body, 502, total, "{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}");
            return new StreamCollectResult(502, "{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}", -1, total);
        }
    }

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
