package com.example.ittechnology.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 会话管理：内存中的会话存储，支持创建 / 列表 / 详情 / 改名 / 删除 / 清空 /
 * 追加消息 / 上下文压缩（用对话模型把旧消息压缩为摘要，控制上下文窗口）。
 *
 * 会话仅保存在内存（进程内），重启即清空；前端页面刷新不丢（走同一后端）。
 */
@Slf4j
@Service
public class ChatSessionService {

    private final AiProxyService proxy;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicLong seq = new AtomicLong(0);
    private final ConcurrentHashMap<String, ChatSession> sessions = new ConcurrentHashMap<>();

    /** 超过该条数时建议压缩上下文 */
    public static final int COMPRESS_AFTER = 12;
    /** 压缩后保留的最近消息条数 */
    private static final int KEEP_RECENT = 6;

    public ChatSessionService(AiProxyService proxy) {
        this.proxy = proxy;
    }

    public static final class ChatSession {
        final String id;
        volatile String title;
        volatile String model;
        final long createdAt = System.currentTimeMillis();
        volatile long updatedAt = createdAt;
        final List<ObjectNode> messages = new ArrayList<>(); // {role, content}

        ChatSession(String id, String title, String model) {
            this.id = id;
            this.title = title;
            this.model = model;
        }
    }

    // ================= 基础操作 =================

    public ChatSession create(String title, String model) {
        String id = "s" + (System.currentTimeMillis() % 100000) + "-" + seq.incrementAndGet();
        ChatSession s = new ChatSession(id, (title == null || title.isBlank()) ? "新会话" : title, model == null ? "" : model);
        sessions.put(id, s);
        return s;
    }

    public ChatSession get(String id) {
        return sessions.get(id);
    }

    public List<ChatSession> list() {
        List<ChatSession> all = new ArrayList<>(sessions.values());
        all.sort((a, b) -> Long.compare(b.updatedAt, a.updatedAt)); // 最近更新在前
        return all;
    }

    public boolean delete(String id) {
        return sessions.remove(id) != null;
    }

    public void rename(String id, String title) {
        ChatSession s = sessions.get(id);
        if (s != null && title != null && !title.isBlank()) {
            s.title = title;
            s.updatedAt = System.currentTimeMillis();
        }
    }

    public void setModel(String id, String model) {
        ChatSession s = sessions.get(id);
        if (s != null && model != null && !model.isBlank()) {
            s.model = model;
        }
    }

    /** 追加一条消息；内容为空则跳过。 */
    public void append(String id, String role, String content) {
        ChatSession s = sessions.get(id);
        if (s == null) return;
        if (role == null || content == null || content.isEmpty()) return;
        ObjectNode m = mapper.createObjectNode();
        m.put("role", "user".equals(role) ? "user" : "assistant");
        m.put("content", content);
        synchronized (s) {
            s.messages.add(m);
            s.updatedAt = System.currentTimeMillis();
        }
    }

    public void clear(String id) {
        ChatSession s = sessions.get(id);
        if (s == null) return;
        synchronized (s) {
            s.messages.clear();
            s.updatedAt = System.currentTimeMillis();
        }
    }

    /** 返回会话消息的只读副本（用于工作流节点延续上下文）。 */
    public List<ObjectNode> messages(String id) {
        ChatSession s = sessions.get(id);
        if (s == null) return null;
        synchronized (s) {
            return new ArrayList<>(s.messages);
        }
    }

    /** 转成 JSON（用于接口返回）。 */
    public ObjectNode toJson(ChatSession s) {
        ObjectNode o = mapper.createObjectNode();
        o.put("id", s.id);
        o.put("title", s.title);
        o.put("model", s.model);
        o.put("created_at", s.createdAt);
        o.put("updated_at", s.updatedAt);
        ArrayNode arr = mapper.createArrayNode();
        synchronized (s) {
            s.messages.forEach(arr::add);
        }
        o.set("messages", arr);
        return o;
    }

    // ================= 上下文压缩 =================

    /**
     * 用对话模型把历史消息压缩成一段摘要（作为一条 system 消息），
     * 只保留最近 KEEP_RECENT 条原始消息，从而控制上下文窗口。
     * 返回压缩后的会话 JSON。
     */
    public ObjectNode summarize(String id, String site, String auth, String model) throws Exception {
        ChatSession s = sessions.get(id);
        if (s == null) return null;
        List<ObjectNode> snapshot;
        synchronized (s) {
            snapshot = new ArrayList<>(s.messages);
        }
        if (snapshot.size() <= COMPRESS_AFTER) return toJson(s);

        String useModel = (model != null && !model.isBlank()) ? model : (s.model.isBlank() ? "claude-sonnet-4-6" : s.model);
        int keep = Math.min(KEEP_RECENT, snapshot.size());

        // 需要压缩的旧消息
        StringBuilder transcript = new StringBuilder();
        for (int i = 0; i < snapshot.size() - keep; i++) {
            ObjectNode m = snapshot.get(i);
            transcript.append(m.path("role").asText()).append(": ").append(m.path("content").asText()).append("\n\n");
        }
        String summaryPrompt =
                "你是会话压缩助手。请把下面这段对话历史压缩成一份简洁的中文要点摘要，" +
                "保留关键事实、结论、用户偏好与未完成事项，控制在 300 字以内，直接输出摘要正文：\n\n" +
                transcript;

        ObjectNode body = mapper.createObjectNode();
        body.put("model", useModel);
        ArrayNode msgs = mapper.createArrayNode();
        ObjectNode sys = mapper.createObjectNode();
        sys.put("role", "user");
        sys.put("content", summaryPrompt);
        msgs.add(sys);
        body.set("messages", msgs);
        body.put("max_tokens", 600);
        body.put("stream", false);

        String summary;
        String lm = useModel.toLowerCase();
        if (lm.contains("claude")) {
            body.set("messages", msgs);
            String p = "/v1/messages";
            var r = proxy.postJson(site, p, auth, body.toString(), Duration.ofSeconds(120));
            if (r.status() >= 400) throw new IllegalStateException("上游 " + r.status() + ": " + r.body());
            JsonNode j = mapper.readTree(r.body());
            summary = j.path("content").isArray() ? extractText(j.path("content")) : j.path("content").asText("");
        } else {
            String p = "/v1/chat/completions";
            var r = proxy.postJson(site, p, auth, body.toString(), Duration.ofSeconds(120));
            if (r.status() >= 400) throw new IllegalStateException("上游 " + r.status() + ": " + r.body());
            JsonNode j = mapper.readTree(r.body());
            summary = j.path("choices").path(0).path("message").path("content").asText("");
        }
        if (summary.isBlank()) return toJson(s);

        // 重建消息：system 摘要 + 最近消息
        synchronized (s) {
            s.messages.clear();
            ObjectNode sysMsg = mapper.createObjectNode();
            sysMsg.put("role", "system");
            sysMsg.put("content", "【历史对话摘要】" + summary);
            s.messages.add(sysMsg);
            for (int i = snapshot.size() - keep; i < snapshot.size(); i++) {
                s.messages.add(snapshot.get(i));
            }
            s.updatedAt = System.currentTimeMillis();
        }
        return toJson(s);
    }

    private String extractText(JsonNode contentArr) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode b : contentArr) {
            String t = b.path("text").asText("");
            if (!t.isEmpty()) { if (sb.length() > 0) sb.append("\n"); sb.append(t); }
        }
        return sb.toString();
    }
}
