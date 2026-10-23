package com.example.ittechnology.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 请求历史日志（数据库持久化）。
 *
 * 由 AiProxyService.postJson 埋点记录所有非流式上游调用：
 *   时间 / 站点 / 路径 / 状态码 / 耗时 / 完整请求体 / 完整响应体。
 *
 * 存储：默认 H2 文件模式（./data/reqlog），可切 MySQL（application-mysql.yaml）。
 * 前端「请求日志」Tab 据此展示原始 JSON、失败筛选、cURL 导出与 Excel 导出。
 *
 * 记录不分页上限：查询接口返回最近 limit 条（默认 200），Excel 导出支持全量或筛选。
 */
@Slf4j
@Service
public class RequestLogService {

    private final ObjectMapper mapper = new ObjectMapper();
    private final JdbcTemplate jdbc;

    public RequestLogService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 启动时尝试创建索引（H2 与 MySQL 语法有差异，失败仅告警）。 */
    @PostConstruct
    public void initIndexes() {
        try {
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_request_logs_epoch ON request_logs (epoch)");
        } catch (Exception e) {
            try {
                jdbc.execute("CREATE INDEX idx_request_logs_epoch ON request_logs (epoch)");
            } catch (Exception ignored) { /* 已存在或权限不足，忽略 */ }
        }
        try {
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_request_logs_status ON request_logs (status)");
        } catch (Exception e) {
            try {
                jdbc.execute("CREATE INDEX idx_request_logs_status ON request_logs (status)");
            } catch (Exception ignored) { /* 忽略 */ }
        }
    }

    /** 记录一次上游请求（落库，失败仅告警不影响主流程）。 */
    public void record(String site, String path, String auth, String body,
                       int status, long ms, String respBody) {
        try {
            jdbc.update(
                    "INSERT INTO request_logs (ts, epoch, site, path, auth_mask, status, ms, ok, req, res) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")),
                    System.currentTimeMillis(),
                    site == null ? "cn" : site,
                    path,
                    maskAuth(auth),
                    status,
                    ms,
                    status >= 200 && status < 400,
                    truncate(body, 8000),
                    truncate(respBody, 12000));
        } catch (Exception e) {
            log.warn("Failed to persist request log: {}", e.getMessage());
        }
    }

    /**
     * 查询日志（数据库，按时间倒序）。
     * filter = all | ok | fail；limit 为返回条数上限（默认 200）。
     */
    public ArrayNode list(String filter, int limit) {
        int lim = Math.max(1, Math.min(limit <= 0 ? 200 : limit, 5000));
        String where = "ok".equals(filter) ? "WHERE ok = TRUE"
                : "fail".equals(filter) ? "WHERE ok = FALSE" : "";
        String sql = "SELECT id, ts, epoch, site, path, auth_mask AS authMask, status, ms, ok, req, res " +
                "FROM request_logs " + where + " ORDER BY epoch DESC, id DESC LIMIT ?";
        List<Map<String, Object>> rows = jdbc.queryForList(sql, lim);
        ArrayNode arr = mapper.createArrayNode();
        for (Map<String, Object> r : rows) {
            ObjectNode e = mapper.createObjectNode();
            e.put("id", ((Number) r.get("id")).longValue());
            e.put("ts", String.valueOf(r.get("ts")));
            e.put("epoch", ((Number) r.get("epoch")).longValue());
            e.put("site", String.valueOf(r.get("site")));
            e.put("path", String.valueOf(r.get("path")));
            e.put("authMask", String.valueOf(r.get("authMask")));
            e.put("status", ((Number) r.get("status")).intValue());
            e.put("ms", ((Number) r.get("ms")).longValue());
            e.put("ok", Boolean.TRUE.equals(r.get("ok")));
            e.put("req", r.get("req") == null ? "" : String.valueOf(r.get("req")));
            e.put("res", r.get("res") == null ? "" : String.valueOf(r.get("res")));
            arr.add(e);
        }
        return arr;
    }

    /** 清空日志。 */
    public void clear() {
        try {
            jdbc.update("DELETE FROM request_logs");
        } catch (Exception e) {
            log.warn("Failed to clear request logs: {}", e.getMessage());
        }
    }

    /** 当前库中日志总条数。 */
    public long count() {
        try {
            Long c = jdbc.queryForObject("SELECT COUNT(*) FROM request_logs", Long.class);
            return c == null ? 0 : c;
        } catch (Exception e) {
            return 0;
        }
    }

    /** 导出为 Excel（.xlsx）。filter = all | ok | fail；limit<=0 表示全部。 */
    public byte[] exportExcel(String filter, int limit) {
        String where = "ok".equals(filter) ? "WHERE ok = TRUE"
                : "fail".equals(filter) ? "WHERE ok = FALSE" : "";
        String sql = "SELECT id, ts, epoch, site, path, auth_mask AS authMask, status, ms, ok, req, res " +
                "FROM request_logs " + where + " ORDER BY epoch DESC, id DESC";
        if (limit > 0) {
            sql += " LIMIT " + Math.min(limit, 20000);
        }
        List<Map<String, Object>> rows = jdbc.queryForList(sql);

        try (Workbook wb = new XSSFWorkbook();
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("请求日志");
            CellStyle head = wb.createCellStyle();
            Font headFont = wb.createFont();
            headFont.setBold(true);
            head.setFont(headFont);
            head.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            head.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            String[] cols = {"ID", "时间", "时间戳", "站点", "路径", "Token(脱敏)", "状态码", "耗时ms", "成功", "请求体", "响应体"};
            Row hr = sheet.createRow(0);
            for (int i = 0; i < cols.length; i++) {
                Cell c = hr.createCell(i);
                c.setCellValue(cols[i]);
                c.setCellStyle(head);
            }
            int ri = 1;
            for (Map<String, Object> r : rows) {
                Row row = sheet.createRow(ri++);
                row.createCell(0).setCellValue(((Number) r.get("id")).doubleValue());
                row.createCell(1).setCellValue(String.valueOf(r.get("ts")));
                row.createCell(2).setCellValue(((Number) r.get("epoch")).longValue());
                row.createCell(3).setCellValue(String.valueOf(r.get("site")));
                row.createCell(4).setCellValue(String.valueOf(r.get("path")));
                row.createCell(5).setCellValue(String.valueOf(r.get("authMask")));
                row.createCell(6).setCellValue(((Number) r.get("status")).intValue());
                row.createCell(7).setCellValue(((Number) r.get("ms")).longValue());
                row.createCell(8).setCellValue(Boolean.TRUE.equals(r.get("ok")) ? "✓" : "✗");
                row.createCell(9).setCellValue(r.get("req") == null ? "" : String.valueOf(r.get("req")));
                row.createCell(10).setCellValue(r.get("res") == null ? "" : String.valueOf(r.get("res")));
            }
            // 列宽（请求体/响应体放宽）
            sheet.setColumnWidth(0, 6 * 256);
            sheet.setColumnWidth(1, 10 * 256);
            sheet.setColumnWidth(2, 16 * 256);
            sheet.setColumnWidth(3, 6 * 256);
            sheet.setColumnWidth(4, 34 * 256);
            sheet.setColumnWidth(5, 16 * 256);
            sheet.setColumnWidth(6, 8 * 256);
            sheet.setColumnWidth(7, 9 * 256);
            sheet.setColumnWidth(8, 6 * 256);
            sheet.setColumnWidth(9, 60 * 256);
            sheet.setColumnWidth(10, 60 * 256);
            wb.write(bos);
            return bos.toByteArray();
        } catch (Exception e) {
            log.error("Excel export failed", e);
            return new byte[0];
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
