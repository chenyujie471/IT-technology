package com.example.ittechnology.controller;

import com.example.ittechnology.service.PlazaService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模型广场接口。
 *
 * GET /api/plaza            — 返回全量模型定价数据（含官网广场未展示的隐藏/海外模型），
 *                             首次请求拉取上游并按 ai.plaza.cache-seconds 缓存。
 * GET /api/plaza?refresh=1  — 强制刷新上游数据。
 *
 * 上游令牌在服务端读取（ai.plaza.token），不依赖浏览器端 Token，客户无需登录即可浏览。
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class PlazaController {

    private final PlazaService plazaService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @GetMapping("/plaza")
    public ResponseEntity<JsonNode> plaza(
            @RequestParam(name = "refresh", defaultValue = "false") boolean refresh) {
        try {
            JsonNode data = plazaService.getPricing(refresh);
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(data);
        } catch (PlazaService.PlazaException e) {
            log.warn("Plaza error: {}", e.getMessage());
            ObjectNode err = objectMapper.createObjectNode();
            err.put("success", false);
            err.put("error", e.getMessage());
            return ResponseEntity.status(e.status).body(err);
        } catch (Exception e) {
            log.error("Plaza unexpected error", e);
            ObjectNode err = objectMapper.createObjectNode();
            err.put("success", false);
            err.put("error", "内部错误: " + e.getMessage());
            return ResponseEntity.status(500).body(err);
        }
    }
}
