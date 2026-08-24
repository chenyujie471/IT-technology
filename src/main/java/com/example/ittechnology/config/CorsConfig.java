package com.example.ittechnology.config;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;
import java.util.List;

/**
 * CORS 与请求拦截配置。
 *
 * 安全收紧说明：
 *  - 允许的跨域来源改为可配置（ai.cors-allowed-origins），留空则不做跨域放行（仅同源），
 *    不再默认对任意来源开放 `*` 且允许携带凭据（原先存在被任意站点盗用代理的风险）。
 *  - 注册 access-key 拦截器，全局校验可选的访问密钥。
 */
@Configuration
@RequiredArgsConstructor
public class CorsConfig implements WebMvcConfigurer {

    @Value("${ai.access-key:}")
    private String accessKey;

    @Value("${ai.cors-allowed-origins:}")
    private String[] allowedOrigins;

    private final AccessKeyInterceptor accessKeyInterceptor;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        List<String> origins = allowedOrigins == null
                ? List.of()
                : Arrays.stream(allowedOrigins)
                        .map(String::trim)
                        .filter(o -> !o.isEmpty())
                        .toList();

        // 统一一条映射：配置了来源则放行这些来源并携带凭据；否则保持浏览器同源策略拦截
        registry.addMapping("/api/**")
                .allowedOrigins(origins.isEmpty() ? new String[0] : origins.toArray(new String[0]))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(!origins.isEmpty())
                .maxAge(3600);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(accessKeyInterceptor)
                .addPathPatterns("/api/**");
    }
}
