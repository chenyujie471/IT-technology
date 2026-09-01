package com.example.ittechnology.controller;

import com.example.ittechnology.service.RequestLogService;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 请求历史日志接口：
 *
 *   GET    /api/logs?filter=all|ok|fail   — 查询最近的上游请求日志（内存环形，最新在前）
 *   DELETE /api/logs                      — 清空日志
 *
 * 每条包含：时间、站点、路径、状态码、耗时、脱敏 Token、完整请求体与响应体。
 * 前端「请求日志」Tab 据此展示原始 JSON 并可导出 cURL。
 */
@Slf4j
@RestController
@RequestMapping("/api/logs")
@RequiredArgsConstructor
public class LogController {

    private final RequestLogService requestLogService;
    private final ObjectMapper mapper = new ObjectMapper();

    @GetMapping
    public ResponseEntity<ArrayNode> list(
            @RequestParam(name = "filter", required = false, defaultValue = "all") String filter) {
        String f = "ok".equals(filter) ? "ok" : "fail".equals(filter) ? "fail" : "all";
        return ResponseEntity.ok(requestLogService.list(f));
    }

    @DeleteMapping
    public ResponseEntity<ObjectNode> clear() {
        requestLogService.clear();
        ObjectNode out = mapper.createObjectNode();
        out.put("ok", true);
        return ResponseEntity.ok(out);
    }
}
