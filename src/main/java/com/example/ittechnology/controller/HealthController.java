package com.example.ittechnology.controller;

import com.example.ittechnology.service.AiProxyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 系统状态接口
 *
 * GET  /api/health  — 服务存活检查（无需 Token）
 * POST /api/verify  — 验证 Token 是否有效
 * GET  /api/models  — 获取可用模型列表（代理 /v1/models）
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class HealthController {

    private final AiProxyService proxyService;

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "service", "AI Multimodal Proxy"
        ));
    }

    /**
     * 验证 Token 是否有效。
     * 改用 GET /v1/models 而非 POST /v1/messages，原因：
     * - /v1/messages 只有 Claude 分组的 Token 有权限，图像/视频分组会 401
     * - /v1/models 是轻量只读接口，所有分组的 Token 均可访问
     * 直接透传上游 HTTP 状态码，不依赖字符串判断。
     */
    @PostMapping("/verify")
    public ResponseEntity<String> verify(
            @RequestHeader("Authorization") String auth,
            @RequestHeader(name = "X-Site", required = false, defaultValue = "cn") String site) {

        log.info("Token verification via /v1/models [site={}]", site);
        ResponseEntity<String> resp =
                proxyService.proxyGetResponse(site, "/v1/models", auth, Duration.ofSeconds(30));
        // 响应头带上实际请求的上游地址，便于前端区分 Token 是在哪个站验证的
        // （两站错误格式不同：国内站 kk_api_error / 国际站 new_api_error，用户容易混淆）
        return ResponseEntity.status(resp.getStatusCode())
                .headers(h -> h.addAll(resp.getHeaders()))
                .header("X-Upstream", proxyService.resolveBase(site))
                .body(resp.getBody());
    }

    /**
     * 获取所有可用模型列表，代理上游 /v1/models（OpenAI 兼容格式）。
     * 前端根据返回结果动态填充对话/图像/视频的模型下拉框。
     * 可通过 X-Site: intl 从国际站拉取模型列表。
     */
    @GetMapping("/models")
    public ResponseEntity<String> models(
            @RequestHeader("Authorization") String auth,
            @RequestHeader(name = "X-Site", required = false, defaultValue = "cn") String site,
            HttpServletResponse response) {

        log.info("Fetching model list [site={}]", site);
        AtomicInteger status = new AtomicInteger(200);
        String result = proxyService.proxyGet(site, "/v1/models", auth, Duration.ofSeconds(30), status);
        response.setHeader("X-Upstream-Status", String.valueOf(status.get()));
        return ResponseEntity.ok()
                .header("Content-Type", "application/json")
                .body(result);
    }
}
