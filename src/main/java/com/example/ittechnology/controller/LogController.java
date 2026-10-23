package com.example.ittechnology.controller;

import com.example.ittechnology.service.RequestLogService;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 请求历史日志接口（数据库持久化）：
 *
 *   GET    /api/logs?filter=all|ok|fail&limit=200   — 查询最近的上游请求日志（时间倒序）
 *   GET    /api/logs/export?filter=all|ok|fail&limit=0 — 导出为 Excel(.xlsx)，limit<=0 全量
 *   DELETE /api/logs                                 — 清空日志
 *
 * 每条包含：时间、站点、路径、状态码、耗时、脱敏 Token、完整请求体与响应体。
 * 前端「请求日志」Tab 据此展示原始 JSON、失败筛选、cURL 导出与 Excel 导出。
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
            @RequestParam(name = "filter", required = false, defaultValue = "all") String filter,
            @RequestParam(name = "limit", required = false, defaultValue = "200") int limit) {
        String f = "ok".equals(filter) ? "ok" : "fail".equals(filter) ? "fail" : "all";
        return ResponseEntity.ok(requestLogService.list(f, limit));
    }

    /** 数据库日志总条数（COUNT(*)），用于界面显示"持久化条数"证明数据真实落库。 */
    @GetMapping("/count")
    public ResponseEntity<ObjectNode> count() {
        ObjectNode out = mapper.createObjectNode();
        out.put("count", requestLogService.count());
        return ResponseEntity.ok(out);
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(
            @RequestParam(name = "filter", required = false, defaultValue = "all") String filter,
            @RequestParam(name = "limit", required = false, defaultValue = "0") int limit) {
        String f = "ok".equals(filter) ? "ok" : "fail".equals(filter) ? "fail" : "all";
        byte[] data = requestLogService.exportExcel(f, limit);
        if (data.length == 0) {
            return ResponseEntity.noContent().build();
        }
        String filename = "request_logs_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".xlsx";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(data);
    }

    @DeleteMapping
    public ResponseEntity<ObjectNode> clear() {
        requestLogService.clear();
        ObjectNode out = mapper.createObjectNode();
        out.put("ok", true);
        return ResponseEntity.ok(out);
    }
}
