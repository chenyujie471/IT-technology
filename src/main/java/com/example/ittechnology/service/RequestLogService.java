package com.example.ittechnology.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 请求历史日志（内存环形缓冲）。
 *
 * 由 AiProxyService.postJson 埋点记录所有非流式上游调用：
 *   时间 / 站点 / 路径 / 状态码 / 耗时 / 完整请求体 / 完整响应体。
 * 前端「请求日志」Tab 据此展示原始 JSON、失败筛选与 cURL 导出。
 *
 * 仅存内存、重启清空；容量 300 条，超出丢弃最旧。
 */
@Slf4j
@Service
public class RequestLogService {

    private static final int CAPACITY = 300;

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<ObjectNode> entries = new ArrayList<>();
    private final AtomicLong seq = new AtomicLong(1);

    /** 记录一次上游请求。 */
    public void record(String site, String path, String auth, String body,
                       int status, long ms, String respBody) {
        ObjectNode e = mapper.createObjectNode();
        e.put("id", seq.getAndIncrement());
        e.put("ts", LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")));
        e.put("epoch", System.currentTimeMillis());
        e.put("site", site == null ? "cn" : site);
        e.put("path", path);
        e.put("authMask", maskAuth(auth));
        e.put("status", status);
        e.put("ms", ms);
        e.put("ok", status >= 200 && status < 400);
        e.put("req", truncate(body, 8000));
        e.put("res", truncate(respBody, 12000));
        synchronized (entries) {
            entries.add(0, e);
            while (entries.size() > CAPACITY) entries.remove(entries.size() - 1);
        }
    }

    /** 查询日志。filter = all | ok | fail。最新在前。 */
    public ArrayNode list(String filter) {
        ArrayNode arr = mapper.createArrayNode();
        synchronized (entries) {
            for (ObjectNode e : entries) {
                boolean ok = e.path("ok").asBoolean();
                if ("ok".equals(filter) && !ok) continue;
                if ("fail".equals(filter) && ok) continue;
                arr.add(e);
            }
        }
        return arr;
    }

    /** 清空日志。 */
    public void clear() {
        synchronized (entries) {
            entries.clear();
        }
    }

    private String maskAuth(String auth) {
        if (auth == null || auth.isEmpty()) return "";
        String t = auth.startsWith("Bearer ") ? auth.substring(7) : auth;
        if (t.length() <= 8) return "***";
        return t.substring(0, 4) + "..." + t.substring(t.length() - 4);
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…[truncated " + (s.length() - max) + " chars]";
    }
}
