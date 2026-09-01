package com.example.ittechnology.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 模型广场数据服务。
 *
 * 从上游 new-api 的 /api/pricing 拉取「全量模型定价」数据（含官网模型广场未展示的
 * 隐藏 / 海外模型），做字段归一化后缓存返回。令牌只存在于服务端配置，不下发浏览器。
 *
 * 兼容多种上游返回结构：
 *   { "data": [ ... ] }                      ← kkhub /api/pricing 实际结构
 *   { "model_pricing": [ ... ] }
 *   根节点直接是数组
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlazaService {

    private final AiProxyService proxyService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${ai.plaza.pricing-url:https://www.kkhub.com/api/pricing}")
    private String pricingUrl;

    /** 系统访问令牌（可选）：非空时以 Authorization: Bearer <token> 请求上游。 */
    @Value("${ai.plaza.token:}")
    private String token;

    /** 结果缓存秒数。 */
    @Value("${ai.plaza.cache-seconds:300}")
    private long cacheSeconds;

    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(30);

    private volatile CacheEntry cache;

    public record CacheEntry(OffsetDateTime fetchedAt, JsonNode data) {}

    /** 获取归一化后的广场数据；refresh=true 时强制重新拉取上游。 */
    public synchronized JsonNode getPricing(boolean refresh) throws PlazaException {
        CacheEntry c = cache;
        if (!refresh && c != null && c.fetchedAt().plusSeconds(cacheSeconds).isAfter(OffsetDateTime.now())) {
            return c.data();
        }
        return fetchAndBuild();
    }

    private JsonNode fetchAndBuild() throws PlazaException {
        String auth = (token == null || token.isEmpty()) ? null : "Bearer " + token.trim();
        AiProxyService.FetchResult r = proxyService.fetch(pricingUrl, auth, FETCH_TIMEOUT);
        if (r.status() >= 400) {
            log.warn("Plaza upstream error: HTTP {} (token configured: {})", r.status(), token != null && !token.isEmpty());
            throw new PlazaException(r.status(), "上游接口返回 HTTP " + r.status()
                    + (token != null && !token.isEmpty() ? "（已携带系统令牌）" : "（未配置令牌）")
                    + ": " + truncate(r.body()));
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(r.body());
        } catch (Exception e) {
            log.error("Plaza: 上游响应不是合法 JSON: {}", e.getMessage());
            throw new PlazaException(502, "上游响应不是合法 JSON: " + e.getMessage());
        }

        // 定位模型数组，兼容多种结构
        JsonNode list = null;
        if (root.isArray()) {
            list = root;
        } else {
            for (String key : new String[]{"data", "model_pricing", "models", "list", "items"}) {
                JsonNode n = root.path(key);
                if (n.isArray()) { list = n; break; }
            }
        }
        if (list == null) {
            JsonNode success = root.path("success");
            if (!success.isMissingNode() && !success.asBoolean()) {
                throw new PlazaException(502, "上游返回失败: " + root.path("message").asText("unknown"));
            }
            throw new PlazaException(502, "未找到模型数组字段（data / model_pricing）: " + truncate(r.body()));
        }

        // 厂商表 vendor_id → 名称
        Map<Integer, String> vendors = new HashMap<>();
        JsonNode vendorsNode = root.path("vendors");
        if (vendorsNode.isArray()) {
            for (JsonNode v : vendorsNode) {
                int id = v.path("id").asInt(-1);
                if (id >= 0) vendors.put(id, v.path("name").asText(""));
            }
        }

        ObjectNode out = objectMapper.createObjectNode();
        out.put("success", true);
        out.put("source", pricingUrl);
        out.put("fetched_at", OffsetDateTime.now().toString());
        out.put("pricing_version", root.path("pricing_version").asText(""));

        ArrayNode models = objectMapper.createArrayNode();
        int displayCount = 0;
        for (JsonNode m : list) {
            if (m == null || !m.isObject()) continue;
            ObjectNode o = objectMapper.createObjectNode();
            copyString(m, o, "model_name");
            copyString(m, o, "description");
            copyString(m, o, "icon");
            copyString(m, o, "tags");
            copyString(m, o, "display_currency");
            copyString(m, o, "owner_by");
            copyString(m, o, "billing_mode");
            copyString(m, o, "billing_expr");
            copyLong(m, o, "vendor_id");
            copyLong(m, o, "content_context");
            copyLong(m, o, "display_sort");
            copyLong(m, o, "is_display");
            copyLong(m, o, "quota_type");
            copyDouble(m, o, "model_ratio");
            copyDouble(m, o, "model_price");
            copyDouble(m, o, "completion_ratio");
            copyDouble(m, o, "cache_ratio");
            copyDouble(m, o, "create_cache_ratio");
            copyDouble(m, o, "image_ratio");

            int vid = m.path("vendor_id").asInt(-1);
            o.put("vendor_name", vendors.getOrDefault(vid, ""));

            o.set("enable_groups",
                    m.path("enable_groups").isArray() ? m.path("enable_groups") : objectMapper.createArrayNode());
            o.set("supported_endpoint_types",
                    m.path("supported_endpoint_types").isArray()
                            ? m.path("supported_endpoint_types") : objectMapper.createArrayNode());

            if (m.path("is_display").asInt(0) == 1) displayCount++;
            models.add(o);
        }

        out.set("models", models);
        out.put("count", models.size());
        out.put("display_count", displayCount);

        // 透传辅助信息（分组折扣 / 分组说明 / 端点映射 / 厂商表）
        out.set("auto_groups", root.path("auto_groups").isArray() ? root.path("auto_groups") : objectMapper.createArrayNode());
        out.set("vendors", vendorsNode.isArray() ? vendorsNode : objectMapper.createArrayNode());
        out.set("group_ratio", root.path("group_ratio").isObject() ? root.path("group_ratio") : objectMapper.createObjectNode());
        out.set("usable_group", root.path("usable_group").isObject() ? root.path("usable_group") : objectMapper.createObjectNode());
        out.set("supported_endpoint", root.path("supported_endpoint").isObject() ? root.path("supported_endpoint") : objectMapper.createObjectNode());

        cache = new CacheEntry(OffsetDateTime.now(), out);
        log.info("Plaza loaded: {} models (display {}), version {}", models.size(), displayCount, out.path("pricing_version").asText());
        return out;
    }

    private void copyString(JsonNode src, ObjectNode dst, String key) {
        JsonNode v = src.path(key);
        dst.put(key, v.isTextual() ? v.asText() : "");
    }

    private void copyLong(JsonNode src, ObjectNode dst, String key) {
        JsonNode v = src.path(key);
        if (v.isNumber()) dst.put(key, v.asLong());
        else if (v.isBoolean()) dst.put(key, v.asBoolean() ? 1 : 0);
        else dst.put(key, 0);
    }

    private void copyDouble(JsonNode src, ObjectNode dst, String key) {
        JsonNode v = src.path(key);
        dst.put(key, v.isNumber() ? v.asDouble() : 0.0);
    }

    private String truncate(String s) {
        if (s == null) return "";
        return s.length() > 400 ? s.substring(0, 400) + "…" : s;
    }

    /** 广场数据拉取失败：status 为建议返回给前端的 HTTP 状态码。 */
    public static class PlazaException extends Exception {
        public final int status;
        public PlazaException(int status, String message) {
            super(message);
            this.status = status;
        }
    }
}
