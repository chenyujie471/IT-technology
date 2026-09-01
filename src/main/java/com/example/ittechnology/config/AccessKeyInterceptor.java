package com.example.ittechnology.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 可选的访问密钥校验。
 *
 * 当配置 ai.access-key（非空）时，所有请求必须在 X-Access-Key 请求头带上相同密钥，
 * 否则返回 403。留空则放行（默认开放，适配内网/自测场景）。
 *
 * /api/health 无需校验，用于健康探活。
 */
@Slf4j
@Component
public class AccessKeyInterceptor implements HandlerInterceptor {

    @Value("${ai.access-key:}")
    private String accessKey;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        // 未配置密钥 → 直接放行
        if (accessKey == null || accessKey.isEmpty()) {
            return true;
        }
        // 健康检查不校验，方便探活
        if (request.getRequestURI().equals("/api/health")) {
            return true;
        }
        String provided = request.getHeader("X-Access-Key");
        if (accessKey.equals(provided)) {
            return true;
        }
        log.warn("Access key rejected: {} {}", request.getMethod(), request.getRequestURI());
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"Invalid or missing X-Access-Key header\"}");
        return false;
    }
}
