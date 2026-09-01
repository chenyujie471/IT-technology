package com.example.ittechnology.controller;

import com.example.ittechnology.service.ToolCallService;
import com.example.ittechnology.service.ToolCallService.ToolResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 工具调用 Function Calling 端点：
 *
 *   POST /api/chat/tools   {model, messages, max_tokens?, max_rounds?}
 *
 * 非流式：后端自动执行工具循环（最多 max_rounds 轮，默认 4），
 * 返回 {text, tool_calls:[{name, arguments, result}], truncated}。
 * 前端据此渲染工具卡片与最终文本。
 */
@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ToolController {

    private final ToolCallService toolService;
    private final ObjectMapper mapper = new ObjectMapper();

    @PostMapping("/tools")
    public ResponseEntity<ObjectNode> tools(
            @RequestHeader("Authorization") String auth,
            @RequestHeader(name = "X-Site", required = false, defaultValue = "cn") String site,
            @RequestBody String body) {
        try {
            JsonNode req = mapper.readTree(body);
            int maxRounds = Math.max(1, Math.min(req.path("max_rounds").asInt(4), 8));
            ToolResponse res = toolService.run(site, auth, req, maxRounds);

            ObjectNode out = mapper.createObjectNode();
            out.put("text", res.text());
            out.put("truncated", res.truncated());
            ArrayNode calls = mapper.createArrayNode();
            for (ToolCallService.ToolRun r : res.runs()) {
                ObjectNode c = mapper.createObjectNode();
                c.put("name", r.name());
                c.put("arguments", r.args());
                c.put("result", r.result());
                calls.add(c);
            }
            out.set("tool_calls", calls);
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            log.warn("Tool call failed: {}", e.getMessage());
            ObjectNode err = mapper.createObjectNode();
            err.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(err);
        }
    }

    /** 工具列表（前端展示可用工具） */
    @GetMapping("/tools")
    public ResponseEntity<ArrayNode> listTools() {
        return ResponseEntity.ok(toolService.openAiTools());
    }
}
