package com.example.ittechnology.controller;

import com.example.ittechnology.service.WorkflowService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 工作流引擎 — 在画布上编排「开始 / 文字对话 / 图像 / 视频 / 结束」节点，
 * 节点间连线传递输出（文本 / 图片 / 视频）作为下游节点的参考输入。
 *
 *  POST /api/workflow/start           提交工作流图，后台执行，立即返回 task_id
 *  GET  /api/workflow/status/{taskId} 轮询各节点状态与输出
 *
 * 请求体（start）：
 * {
 *   "nodes": [ { "id":"n1", "type":"start",  "label":"开始",
 *                "config": { "prompt":"一只猫在沙滩上" } },
 *              { "id":"n2", "type":"chat",   "label":"文字对话",
 *                "config": { "model":"claude-sonnet-4-6", "prompt":"请为画面写一段文案", "maxTokens": 2000 } },
 *              { "id":"n3", "type":"image",  "label":"图像生成",
 *                "config": { "model":"gpt-image-2", "prompt":"", "size":"1024x1024", "quality":"high" } },
 *              { "id":"n4", "type":"video",  "label":"视频生成",
 *                "config": { "model":"doubao-seedance-2-0-260128", "prompt":"", "ratio":"16:9",
 *                            "duration":5, "resolution":"720p", "audio":true, "watermark":false } },
 *              { "id":"n5", "type":"end",    "label":"结束", "config": {} } ],
 *   "edges": [ { "from":"n1","to":"n2" }, { "from":"n2","to":"n3" },
 *              { "from":"n2","to":"n4" }, { "from":"n3","to":"n4" },
 *              { "from":"n4","to":"n5" } ]
 * }
 *
 * 输出传递规则：
 *   - text / chat 节点输出 → 文本，拼接到下游节点的提示词「上游节点输出，供参考」
 *   - image 节点输出     → 图片（base64 data URL），作为下游 图生图 / 图生视频 / 多模态对话 的参考图
 *   - video 节点输出     → 视频 URL，作为下游 视频参考 / 多模态参考 的参考视频
 */
@Slf4j
@RestController
@RequestMapping("/api/workflow")
@RequiredArgsConstructor
public class WorkflowController {

    private final WorkflowService workflowService;

    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> start(
            @RequestHeader("Authorization") String auth,
            @RequestHeader(name = "X-Site", required = false, defaultValue = "cn") String site,
            @RequestBody JsonNode body) {

        log.info("Workflow start received [site={}, nodes={}]", site, body.path("nodes").size());
        Map<String, Object> resp = workflowService.start(auth, site, body);
        if (resp.containsKey("error")) {
            return ResponseEntity.badRequest().body(resp);
        }
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/status/{taskId}")
    public ResponseEntity<Map<String, Object>> status(@PathVariable String taskId) {
        Map<String, Object> st = workflowService.status(taskId);
        if (st == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(st);
    }
}