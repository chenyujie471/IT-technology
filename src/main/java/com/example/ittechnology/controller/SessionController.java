package com.example.ittechnology.controller;

import com.example.ittechnology.service.ChatSessionService;
import com.example.ittechnology.service.ChatSessionService.ChatSession;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 会话管理接口：创建 / 列表 / 详情 / 改名 / 删除 / 清空 / 追加消息 / 上下文压缩。
 * 前端对话页用它持久化会话，刷新不丢；工作流对话节点也可绑定会话延续上下文。
 */
@Slf4j
@RestController
@RequestMapping("/api/sessions")
@RequiredArgsConstructor
public class SessionController {

    private final ChatSessionService sessions;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 创建会话 {title?, model?} → {id,...} */
    @PostMapping
    public ResponseEntity<ObjectNode> create(@RequestHeader("Authorization") String auth,
                                             @RequestBody(required = false) String body) {
        String title = "", model = "";
        try {
            JsonNode j = mapper.readTree(body == null ? "{}" : body);
            title = j.path("title").asText("");
            model = j.path("model").asText("");
        } catch (Exception ignored) {}
        ChatSession s = sessions.create(title, model);
        return ResponseEntity.ok(sessions.toJson(s));
    }

    /** 会话列表（最近更新在前），不含消息体 */
    @GetMapping
    public ResponseEntity<List<ObjectNode>> list(@RequestHeader("Authorization") String auth) {
        List<ObjectNode> out = new ArrayList<>();
        for (ChatSession s : sessions.list()) {
            ObjectNode o = sessions.toJson(s);
            o.remove("messages");
            out.add(o);
        }
        return ResponseEntity.ok(out);
    }

    /** 会话详情（含消息） */
    @GetMapping("/{id}")
    public ResponseEntity<ObjectNode> get(@RequestHeader("Authorization") String auth, @PathVariable String id) {
        ChatSession s = sessions.get(id);
        if (s == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(sessions.toJson(s));
    }

    /** 改名 {title} */
    @PatchMapping("/{id}")
    public ResponseEntity<ObjectNode> rename(@RequestHeader("Authorization") String auth,
                                             @PathVariable String id,
                                             @RequestBody(required = false) String body) {
        ChatSession s = sessions.get(id);
        if (s == null) return ResponseEntity.notFound().build();
        try {
            JsonNode j = mapper.readTree(body == null ? "{}" : body);
            if (j.has("title")) sessions.rename(id, j.path("title").asText(""));
            if (j.has("model")) sessions.setModel(id, j.path("model").asText(""));
        } catch (Exception ignored) {}
        return ResponseEntity.ok(sessions.toJson(s));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@RequestHeader("Authorization") String auth, @PathVariable String id) {
        if (!sessions.delete(id)) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /** 追加消息 {role, content} */
    @PostMapping("/{id}/messages")
    public ResponseEntity<ObjectNode> append(@RequestHeader("Authorization") String auth,
                                             @PathVariable String id,
                                             @RequestBody(required = false) String body) {
        ChatSession s = sessions.get(id);
        if (s == null) return ResponseEntity.notFound().build();
        try {
            JsonNode j = mapper.readTree(body == null ? "{}" : body);
            sessions.append(id, j.path("role").asText("user"), j.path("content").asText(""));
        } catch (Exception ignored) {}
        return ResponseEntity.ok(sessions.toJson(s));
    }

    /** 清空消息 */
    @DeleteMapping("/{id}/messages")
    public ResponseEntity<ObjectNode> clear(@RequestHeader("Authorization") String auth, @PathVariable String id) {
        ChatSession s = sessions.get(id);
        if (s == null) return ResponseEntity.notFound().build();
        sessions.clear(id);
        return ResponseEntity.ok(sessions.toJson(s));
    }

    /** 上下文压缩（调用对话模型把旧消息压成摘要） */
    @PostMapping("/{id}/summarize")
    public ResponseEntity<ObjectNode> summarize(@RequestHeader("Authorization") String auth,
                                                @RequestHeader(name = "X-Site", required = false, defaultValue = "cn") String site,
                                                @PathVariable String id,
                                                @RequestBody(required = false) String body) {
        if (sessions.get(id) == null) return ResponseEntity.notFound().build();
        String model = "";
        try {
            JsonNode j = mapper.readTree(body == null ? "{}" : body);
            model = j.path("model").asText("");
        } catch (Exception ignored) {}
        try {
            return ResponseEntity.ok(sessions.summarize(id, site, auth, model));
        } catch (Exception e) {
            log.warn("Session summarize failed: {}", e.getMessage());
            ObjectNode err = mapper.createObjectNode();
            err.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(err);
        }
    }
}
