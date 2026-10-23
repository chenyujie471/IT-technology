package com.example.ittechnology.service;

import com.example.ittechnology.service.AiProxyService.FetchResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工作流执行引擎。
 *
 * 负责：解析画布图（节点 + 连线）→ 拓扑排序 → 逐个执行节点 →
 * 把上游节点输出（文本 / 图片 / 视频）作为下游节点的参考输入 →
 * 所有节点完成后汇总结果。
 *
 * 执行是异步的：start() 立即返回 task_id，后台线程逐步执行；
 * 前端通过 /api/workflow/status/{taskId} 轮询每个节点的状态与输出。
 *
 * 节点类型：
 *   start → 输出文本（工作流入口提示词）
 *   chat  → 调用上游对话接口；支持把上游文本拼进提示词、上游图片作为多模态参考
 *   image → 调用上游图像生成；支持图生图（上游图片作为参考）
 *   video → 调用上游视频生成；支持图生视频 / 视频参考，提交后内部轮询到完成
 *   end   → 汇总所有节点输出，生成工作流总结
 */
@Slf4j
@Service
public class WorkflowService {

    private final AiProxyService proxy;
    private final ChatSessionService chatSessions;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final AtomicLong seq = new AtomicLong(0);
    private final ConcurrentHashMap<String, WfTask> tasks = new ConcurrentHashMap<>();

    private static final int VIDEO_POLL_MAX = 200;   // 200 × 3s ≈ 10 分钟
    private static final Duration CHAT_TIMEOUT = Duration.ofSeconds(180);
    private static final Duration IMAGE_TIMEOUT = Duration.ofSeconds(600);
    private static final Duration TASK_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(30);

    public WorkflowService(AiProxyService proxy, ChatSessionService chatSessions) {
        this.proxy = proxy;
        this.chatSessions = chatSessions;
    }

    // ================= 任务模型 =================

    /** 一个后台执行中的工作流任务。 */
    public static final class WfTask {
        final String taskId;
        final String site;
        final String auth;
        final JsonNode graph;
        final long startedAt = System.currentTimeMillis();
        volatile String status = "RUNNING";      // RUNNING / DONE / FAILED
        volatile String error;
        final Map<String, String> nodeStatus = new ConcurrentHashMap<>(); // PENDING/RUNNING/DONE/FAILED
        final Map<String, JsonNode> outputs = new ConcurrentHashMap<>();  // nodeId -> 输出 JSON

        WfTask(String taskId, String site, String auth, JsonNode graph) {
            this.taskId = taskId;
            this.site = site;
            this.auth = auth;
            this.graph = graph;
        }
    }

    // ================= 对外接口 =================

    public Map<String, Object> start(String auth, String site, JsonNode body) {
        Map<String, Object> resp = new LinkedHashMap<>();
        JsonNode nodes = body.path("nodes");
        if (!nodes.isArray() || nodes.isEmpty()) {
            resp.put("error", "nodes 不能为空");
            return resp;
        }
        // 基本校验
        Map<String, JsonNode> nodeMap = new HashMap<>();
        for (JsonNode n : nodes) {
            String id = n.path("id").asText("");
            if (id.isEmpty() || nodeMap.containsKey(id)) {
                resp.put("error", "节点 id 缺失或重复");
                return resp;
            }
            nodeMap.put(id, n);
        }
        for (JsonNode e : body.path("edges")) {
            String f = e.path("from").asText("");
            String t = e.path("to").asText("");
            if (!nodeMap.containsKey(f) || !nodeMap.containsKey(t) || f.equals(t)) {
                resp.put("error", "连线引用了不存在的节点或自环: " + f + " -> " + t);
                return resp;
            }
        }

        String taskId = "wf-" + ThreadLocalRandom.current().nextLong(1000, 9999)
                + "-" + System.currentTimeMillis();
        WfTask task = new WfTask(taskId, site, auth, body);
        for (JsonNode n : nodes) {
            task.nodeStatus.put(n.path("id").asText(), "PENDING");
        }
        tasks.put(taskId, task);
        executor.submit(() -> execute(task));
        resp.put("task_id", taskId);
        return resp;
    }

    public Map<String, Object> status(String taskId) {
        WfTask t = tasks.get(taskId);
        if (t == null) return null;
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("task_id", taskId);
        r.put("status", t.status);
        r.put("error", t.error);
        r.put("elapsed", (System.currentTimeMillis() - t.startedAt) / 1000);

        List<Map<String, Object>> nodes = new ArrayList<>();
        for (JsonNode nd : t.graph.path("nodes")) {
            String id = nd.path("id").asText();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("label", nd.path("label").asText(id));
            m.put("type", nd.path("type").asText(""));
            m.put("status", t.nodeStatus.getOrDefault(id, "PENDING"));
            nodes.add(m);
        }
        r.put("nodes", nodes);
        r.put("outputs", t.outputs);
        return r;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    // ================= 图执行 =================

    private void execute(WfTask task) {
        try {
            JsonNode nodes = task.graph.path("nodes");
            Map<String, JsonNode> nodeMap = new LinkedHashMap<>();
            for (JsonNode n : nodes) nodeMap.put(n.path("id").asText(), n);

            // 邻接表（入边 / 出边，带端口）
            Map<String, List<EdgeRef>> inEdges = new HashMap<>();
            Map<String, List<EdgeRef>> outEdges = new HashMap<>();
            nodeMap.keySet().forEach(id -> {
                inEdges.put(id, new ArrayList<>());
                outEdges.put(id, new ArrayList<>());
            });
            for (JsonNode e : task.graph.path("edges")) {
                String f = e.path("from").asText();
                String t = e.path("to").asText();
                if (!nodeMap.containsKey(f) || !nodeMap.containsKey(t)) continue;
                EdgeRef er = new EdgeRef(f, e.path("fromPort").asText("out"), t, e.path("toPort").asText("in"));
                inEdges.get(t).add(er);
                outEdges.get(f).add(er);
            }

            // Kahn 拓扑排序（检测环）
            Map<String, Integer> indeg = new HashMap<>();
            nodeMap.keySet().forEach(id -> indeg.put(id, inEdges.get(id).size()));
            Deque<String> q = new ArrayDeque<>();
            nodeMap.keySet().forEach(id -> { if (indeg.get(id) == 0) q.add(id); });
            List<String> order = new ArrayList<>();
            while (!q.isEmpty()) {
                String id = q.poll();
                order.add(id);
                for (EdgeRef er : outEdges.get(id)) {
                    int d = indeg.get(er.to) - 1;
                    indeg.put(er.to, d);
                    if (d == 0) q.add(er.to);
                }
            }
            if (order.size() != nodeMap.size()) {
                throw new IllegalStateException("工作流存在循环连线，无法执行");
            }

            Map<String, JsonNode> nodeOutputs = new HashMap<>();
            Map<String, Object> locals = new HashMap<>();
            for (String id : order) {
                if ("FAILED".equals(task.status)) break;
                JsonNode nd = nodeMap.get(id);

                // 决定是否执行：所有上游已结算，且存在至少一条“激活”的入边
                List<EdgeRef> ins = inEdges.get(id);
                boolean run = false;
                if (ins.isEmpty()) {
                    run = true; // 入口节点
                } else {
                    boolean allSettled = true;
                    for (EdgeRef er : ins) {
                        String ps = task.nodeStatus.get(er.from);
                        if (ps == null || "PENDING".equals(ps) || "RUNNING".equals(ps)) { allSettled = false; break; }
                    }
                    if (allSettled) {
                        for (EdgeRef er : ins) {
                            JsonNode po = nodeOutputs.get(er.from);
                            if (po != null && isEdgeActive(er, po)) { run = true; break; }
                        }
                    }
                }
                if (!run) {
                    task.nodeStatus.put(id, "SKIPPED");
                    log.info("Workflow node {} ({}) skipped", id, nd.path("type").asText());
                    continue;
                }

                task.nodeStatus.put(id, "RUNNING");
                try {
                    List<JsonNode> ups = new ArrayList<>();
                    for (EdgeRef er : ins) {
                        JsonNode po = nodeOutputs.get(er.from);
                        if (po != null && isEdgeActive(er, po)) ups.add(po);
                    }
                    JsonNode out = executeNode(task, nd, ups, nodeOutputs, nodeMap, locals);
                    nodeOutputs.put(id, out);
                    task.outputs.put(id, out);
                    task.nodeStatus.put(id, "DONE");
                    log.info("Workflow node {} ({}) done", id, nd.path("type").asText());
                } catch (Exception ex) {
                    log.warn("Workflow node {} failed: {}", id, ex.getMessage());
                    task.nodeStatus.put(id, "FAILED");
                    task.error = "节点「" + nd.path("label").asText(id) + "」失败: " + ex.getMessage();
                    task.status = "FAILED";
                    return;
                }
            }
            if (!"FAILED".equals(task.status)) {
                task.status = "DONE";
            }
        } catch (Exception ex) {
            log.error("Workflow execution error: {}", ex.getMessage());
            task.status = "FAILED";
            task.error = ex.getMessage();
        }
    }

    /** 一条入边是否“激活”：源节点有输出，且若是路由节点则其输出端口命中该边。 */
    private boolean isEdgeActive(EdgeRef er, JsonNode fromOutput) {
        if ("route".equals(fromOutput.path("type").asText(""))) {
            return fromOutput.path("route").path(er.fromPort).asBoolean(false);
        }
        return true;
    }

    private JsonNode executeNode(WfTask task, JsonNode nd, List<JsonNode> ups,
                                 Map<String, JsonNode> nodeOutputs, Map<String, JsonNode> nodeMap,
                                 Map<String, Object> locals) throws Exception {
        JsonNode cfg = nd.path("config");
        String type = nd.path("type").asText("chat");
        switch (type) {
            case "start":
            case "text":   // 文本节点：像 start 一样输出文本，可放在工作流中间承接上游
                ObjectNode so = mapper.createObjectNode();
                so.put("type", "text");
                so.put("text", expandVars(cfg.path("prompt").asText(""), nodeOutputs, locals));
                return so;
            case "chat":
                return runChat(task, cfg, ups, nodeOutputs, locals);
            case "image":
                return runImage(task, cfg, ups, nodeOutputs, locals);
            case "video":
                return runVideo(task, cfg, ups, nodeOutputs, locals);
            case "if":
                return runIf(task, cfg, ups, nodeOutputs, locals);
            case "switch":
                return runSwitch(task, cfg, ups, nodeOutputs, locals);
            case "loop":
                return runLoop(task, cfg, ups, nodeOutputs, locals);
            case "merge":
                return runMerge(cfg, ups);
            case "end":
                return runEnd(nodeOutputs, nodeMap);
            default:
                throw new IllegalStateException("未知节点类型: " + type);
        }
    }

    // ================= 上下文收集 =================

    /** 从上游输出中收集：文本 / 图片(data URL) / 视频 URL（兼容 text/chat/result/image/video/route/merge/loop）。 */
    private void collect(List<JsonNode> ups, List<String> texts, List<String> imgs, List<String> vids) {
        for (JsonNode o : ups) {
            if (o == null) continue;
            if (texts != null) {
                String t = o.path("text").asText("");
                if (!t.isBlank()) texts.add(t);
            }
            if (imgs != null && o.path("images").isArray()) {
                for (JsonNode u : o.path("images")) {
                    String s = u.asText("");
                    if (!s.isEmpty()) imgs.add(s);
                }
            }
            if (vids != null) {
                String v = o.path("videoUrl").asText("");
                if (!v.isEmpty()) vids.add(v);
                if (o.path("videos").isArray()) {
                    for (JsonNode u : o.path("videos")) {
                        String s = u.asText("");
                        if (!s.isEmpty()) vids.add(s);
                    }
                }
            }
        }
    }

    /** 把上游文本拼进提示词。 */
    private String composePrompt(String prompt, String context) {
        StringBuilder sb = new StringBuilder();
        if (prompt != null && !prompt.isBlank()) sb.append(prompt);
        if (context != null && !context.isBlank()) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append("【上游节点输出，供参考】\n").append(context);
        }
        if (sb.length() == 0) sb.append("请结合你的能力继续处理。");
        return sb.toString();
    }

    private String joinTexts(List<String> texts) {
        if (texts.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(texts.get(0));
        for (int i = 1; i < texts.size(); i++) sb.append("\n\n").append(texts.get(i));
        return sb.toString();
    }

    // ================= Chat 节点 =================

    private JsonNode runChat(WfTask task, JsonNode cfg, List<JsonNode> ups,
                             Map<String, JsonNode> nodeOutputs, Map<String, Object> locals) throws Exception {
        String model = cfg.path("model").asText("");
        if (model.isEmpty()) throw new IllegalStateException("未指定对话模型");
        String prompt = expandVars(cfg.path("prompt").asText(""), nodeOutputs, locals);
        int maxTok = cfg.path("maxTokens").asInt(2000);

        List<String> texts = new ArrayList<>();
        List<String> imgs = new ArrayList<>();
        collect(ups, texts, imgs, null);
        String content = composePrompt(prompt, joinTexts(texts));

        // 会话绑定：载入历史上下文，并把本次问答写回会话
        String sessionId = cfg.path("sessionId").asText("");
        List<ObjectNode> history = new ArrayList<>();
        if (!sessionId.isEmpty()) {
            List<ObjectNode> ms = chatSessions.messages(sessionId);
            if (ms != null) history = ms;
        }

        String text = chatCall(task, model, content, imgs, maxTok, history);

        if (!sessionId.isEmpty() && chatSessions.get(sessionId) != null) {
            chatSessions.append(sessionId, "user", content);
            chatSessions.append(sessionId, "assistant", text);
        }

        ObjectNode o = mapper.createObjectNode();
        o.put("type", "chat");
        o.put("text", text);
        o.put("model", model);
        return o;
    }

    /** 单次对话调用（chat 节点与 loop 节点复用）。history 为可选历史消息。 */
    private String chatCall(WfTask task, String model, String content, List<String> imgs, int maxTok,
                            List<ObjectNode> history) throws Exception {
        String fmt;
        String path;
        String bodyStr;
        String lm = model.toLowerCase();
        if (lm.contains("gemini")) {
            fmt = "gemini";
            path = "/v1beta/models/" + model + ":generateContent";
            bodyStr = buildGeminiChatBody(content, imgs, maxTok, history).toString();
        } else {
            fmt = lm.contains("claude") ? "messages" : "chat";
            path = fmt.equals("messages") ? "/v1/messages" : "/v1/chat/completions";
            bodyStr = buildChatBody(fmt, model, content, imgs, maxTok, history).toString();
        }

        FetchResult r = proxy.postJson(task.site, path, task.auth, bodyStr, CHAT_TIMEOUT);
        if (r.status() >= 400) {
            throw new IllegalStateException("上游 " + r.status() + ": " + shortErr(r.body()));
        }
        JsonNode j = mapper.readTree(r.body());
        return extractChatText(fmt, j);
    }

    private ObjectNode buildChatBody(String fmt, String model, String content,
                                     List<String> imgs, int maxTok, List<ObjectNode> history) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", maxTok);
        body.put("stream", false);
        ArrayNode messages = mapper.createArrayNode();
        // 会话历史（system 角色在 Anthropic 下转成 user 前缀，避免非法角色）
        if (history != null) {
            for (ObjectNode h : history) {
                String role = h.path("role").asText("user");
                String htext = h.path("content").asText("");
                if (htext.isEmpty()) continue;
                if ("messages".equals(fmt) && "system".equals(role)) {
                    role = "user";
                    htext = "【系统设定】" + htext;
                }
                ObjectNode hm = mapper.createObjectNode();
                hm.put("role", role);
                hm.put("content", htext);
                messages.add(hm);
            }
        }
        ObjectNode user = mapper.createObjectNode();
        user.put("role", "user");
        if (imgs.isEmpty()) {
            user.put("content", content);
        } else {
            ArrayNode contentArr = mapper.createArrayNode();
            if (content != null && !content.isBlank()) {
                ObjectNode t = mapper.createObjectNode();
                t.put("type", "text");
                t.put("text", content);
                contentArr.add(t);
            }
            for (String dataUrl : imgs) {
                if (fmt.equals("messages")) {
                    // Anthropic: { type:"image", source:{ type:"base64", media_type, data } }
                    String[] parts = splitDataUrl(dataUrl);
                    ObjectNode im = mapper.createObjectNode();
                    im.put("type", "image");
                    ObjectNode src = mapper.createObjectNode();
                    src.put("type", "base64");
                    src.put("media_type", parts[0]);
                    src.put("data", parts[1]);
                    im.set("source", src);
                    contentArr.add(im);
                } else {
                    // OpenAI: { type:"image_url", image_url:{ url: dataUrl } }
                    ObjectNode im = mapper.createObjectNode();
                    im.put("type", "image_url");
                    ObjectNode iu = mapper.createObjectNode();
                    iu.put("url", dataUrl);
                    im.set("image_url", iu);
                    contentArr.add(im);
                }
            }
            user.set("content", contentArr);
        }
        messages.add(user);
        body.set("messages", messages);
        return body;
    }

    private ObjectNode buildGeminiChatBody(String content, List<String> imgs, int maxTok, List<ObjectNode> history) {
        ObjectNode body = mapper.createObjectNode();
        ArrayNode contents = mapper.createArrayNode();
        // 会话历史（Gemini 角色：user/model，system 归为 user）
        if (history != null) {
            for (ObjectNode h : history) {
                String role = h.path("role").asText("user");
                String htext = h.path("content").asText("");
                if (htext.isEmpty()) continue;
                ObjectNode hm = mapper.createObjectNode();
                hm.put("role", "model".equals(role) ? "model" : "user");
                ArrayNode parts = mapper.createArrayNode();
                ObjectNode t = mapper.createObjectNode();
                t.put("text", htext);
                parts.add(t);
                hm.set("parts", parts);
                contents.add(hm);
            }
        }
        ObjectNode user = mapper.createObjectNode();
        user.put("role", "user");
        ArrayNode parts = mapper.createArrayNode();
        if (content != null && !content.isBlank()) {
            ObjectNode t = mapper.createObjectNode();
            t.put("text", content);
            parts.add(t);
        }
        for (String dataUrl : imgs) {
            String[] parts2 = splitDataUrl(dataUrl);
            ObjectNode im = mapper.createObjectNode();
            ObjectNode inline = mapper.createObjectNode();
            inline.put("mimeType", parts2[0]);
            inline.put("data", parts2[1]);
            im.set("inlineData", inline);
            parts.add(im);
        }
        user.set("parts", parts);
        contents.add(user);
        body.set("contents", contents);
        body.put("maxOutputTokens", maxTok);
        return body;
    }

    private String extractChatText(String fmt, JsonNode j) {
        if ("gemini".equals(fmt)) {
            StringBuilder sb = new StringBuilder();
            JsonNode parts = j.path("candidates").path(0).path("content").path("parts");
            if (parts.isArray()) {
                for (JsonNode p : parts) {
                    String t = p.path("text").asText("");
                    if (!t.isEmpty()) { if (sb.length() > 0) sb.append("\n"); sb.append(t); }
                }
            }
            return sb.toString().isEmpty() ? (j.path("text").asText("")) : sb.toString();
        }
        if ("messages".equals(fmt)) {
            JsonNode content = j.path("content");
            if (content.isArray()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode b : content) {
                    String t = b.path("text").asText("");
                    if (!t.isEmpty()) { if (sb.length() > 0) sb.append("\n"); sb.append(t); }
                }
                if (sb.length() > 0) return sb.toString();
            }
            return j.path("content").asText("").isEmpty() ? j.path("text").asText("") : j.path("content").asText("");
        }
        // chat / chat/completions
        return j.path("choices").path(0).path("message").path("content").asText("");
    }

    // ================= Image 节点 =================

    private JsonNode runImage(WfTask task, JsonNode cfg, List<JsonNode> ups,
                              Map<String, JsonNode> nodeOutputs, Map<String, Object> locals) throws Exception {
        String model = cfg.path("model").asText("");
        if (model.isEmpty()) throw new IllegalStateException("未指定图像模型");
        String prompt = expandVars(cfg.path("prompt").asText(""), nodeOutputs, locals);
        String size = cfg.path("size").asText("1024x1024");
        String quality = cfg.path("quality").asText("high");

        List<String> texts = new ArrayList<>();
        List<String> imgs = new ArrayList<>();
        collect(ups, texts, imgs, null);
        String finalPrompt = composePrompt(prompt, joinTexts(texts));

        String lm = model.toLowerCase();
        boolean isGemini = lm.contains("gemini")
                && (lm.contains("image") || lm.contains("flash-preview"));

        String dataUrl;
        String revised = "";
        if (isGemini) {
            String path = "/v1beta/models/" + model + ":generateContent";
            ObjectNode body = mapper.createObjectNode();
            ArrayNode contents = mapper.createArrayNode();
            ObjectNode user = mapper.createObjectNode();
            user.put("role", "user");
            ArrayNode parts = mapper.createArrayNode();
            ObjectNode t = mapper.createObjectNode();
            t.put("text", finalPrompt);
            parts.add(t);
            for (String du : imgs) {
                String[] p = splitDataUrl(du);
                ObjectNode im = mapper.createObjectNode();
                ObjectNode inline = mapper.createObjectNode();
                inline.put("mimeType", p[0]);
                inline.put("data", p[1]);
                im.set("inlineData", inline);
                parts.add(im);
            }
            user.set("parts", parts);
            contents.add(user);
            body.set("contents", contents);
            ObjectNode gen = mapper.createObjectNode();
            ArrayNode mods = mapper.createArrayNode();
            mods.add("IMAGE");
            gen.set("responseModalities", mods);
            ObjectNode imgCfg = mapper.createObjectNode();
            imgCfg.put("aspectRatio", aspectRatio(size));
            imgCfg.put("imageSize", size.contains("3840") || size.contains("2048") ? "2K" : "1K");
            gen.set("imageConfig", imgCfg);
            body.set("generationConfig", gen);

            FetchResult r = proxy.postJson(task.site, path, task.auth, body.toString(), IMAGE_TIMEOUT);
            if (r.status() >= 400) throw new IllegalStateException("上游 " + r.status() + ": " + shortErr(r.body()));
            JsonNode j = mapper.readTree(r.body());
            JsonNode inline = j.path("candidates").path(0).path("content").path("parts").path(0).path("inlineData");
            if (inline.isMissingNode() || inline.path("data").asText("").isEmpty()) {
                throw new IllegalStateException("图像响应缺少 inlineData: " + shortErr(r.body()));
            }
            String mime = inline.path("mimeType").asText("image/png");
            dataUrl = "data:" + mime + ";base64," + inline.path("data").asText();
        } else {
            String path = "/v1/images/generations";
            ObjectNode body = mapper.createObjectNode();
            body.put("model", model);
            body.put("prompt", finalPrompt);
            body.put("size", size);
            body.put("n", 1);
            body.put("quality", quality);
            body.put("response_format", "b64_json");
            body.put("stream", false);
            if (!imgs.isEmpty()) {
                ArrayNode arr = mapper.createArrayNode();
                imgs.forEach(arr::add);
                body.set("images", arr);
                if (imgs.size() == 1) body.put("image", imgs.get(0));
            }

            FetchResult r = proxy.postJson(task.site, path, task.auth, body.toString(), IMAGE_TIMEOUT);
            if (r.status() >= 400) throw new IllegalStateException("上游 " + r.status() + ": " + shortErr(r.body()));
            JsonNode j = mapper.readTree(r.body());
            String b64 = j.path("data").path(0).path("b64_json").asText("");
            if (b64.isEmpty()) throw new IllegalStateException("图像响应缺少 b64_json: " + shortErr(r.body()));
            dataUrl = "data:" + detectMime(b64) + ";base64," + b64;
            revised = j.path("data").path(0).path("revised_prompt").asText("");
        }

        ObjectNode o = mapper.createObjectNode();
        o.put("type", "image");
        ArrayNode images = mapper.createArrayNode();
        images.add(dataUrl);
        o.set("images", images);
        if (!revised.isEmpty()) o.put("revised_prompt", revised);
        o.put("model", model);
        o.put("size", size);
        o.put("quality", quality);
        return o;
    }

    private String aspectRatio(String size) {
        switch (size) {
            case "1536x1024": return "3:2";
            case "1024x1536": return "2:3";
            case "2048x1152": return "16:9";
            case "3840x2160": return "16:9";
            case "2160x3840": return "9:16";
            default: return "1:1";
        }
    }

    // ================= Video 节点 =================

    private JsonNode runVideo(WfTask task, JsonNode cfg, List<JsonNode> ups,
                              Map<String, JsonNode> nodeOutputs, Map<String, Object> locals) throws Exception {
        String model = cfg.path("model").asText("");
        if (model.isEmpty()) throw new IllegalStateException("未指定视频模型");
        String prompt = expandVars(cfg.path("prompt").asText(""), nodeOutputs, locals);
        boolean intl = "intl".equalsIgnoreCase(task.site);

        List<String> texts = new ArrayList<>();
        List<String> imgs = new ArrayList<>();
        List<String> vids = new ArrayList<>();
        collect(ups, texts, imgs, vids);
        String finalPrompt = composePrompt(prompt, joinTexts(texts));

        String submitPath = intl ? "/v1/video/generations" : "/api/v3/contents/generations/tasks";
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("prompt", finalPrompt);
        if (intl) {
            // 国际站：官方模板格式 metadata.reference_images/videos/audios 数组
            ObjectNode meta = mapper.createObjectNode();
            ArrayNode refImages = mapper.createArrayNode();
            imgs.forEach(refImages::add);
            ArrayNode refVideos = mapper.createArrayNode();
            vids.forEach(refVideos::add);
            ArrayNode refAudios = mapper.createArrayNode();
            meta.set("reference_images", refImages);
            meta.set("reference_videos", refVideos);
            meta.set("reference_audios", refAudios);
            meta.put("generate_audio", cfg.path("audio").asBoolean(true));
            meta.put("ratio", cfg.path("ratio").asText("16:9"));
            meta.put("duration", cfg.path("duration").asInt(5));
            meta.put("resolution", cfg.path("resolution").asText("720p"));
            meta.put("watermark", cfg.path("watermark").asBoolean(false));
            body.set("metadata", meta);
        } else {
            body.put("generate_audio", cfg.path("audio").asBoolean(true));
            body.put("ratio", cfg.path("ratio").asText("16:9"));
            body.put("duration", cfg.path("duration").asInt(5));
            body.put("resolution", cfg.path("resolution").asText("720p"));
            body.put("watermark", cfg.path("watermark").asBoolean(false));
            if (!imgs.isEmpty()) {
                ArrayNode arr = mapper.createArrayNode();
                imgs.forEach(arr::add);
                body.set("images", arr);
                if (imgs.size() == 1) body.put("image", imgs.get(0));
            }
            if (!vids.isEmpty()) body.put("video_url", vids.get(0));
        }

        FetchResult r = proxy.postJson(task.site, submitPath, task.auth, body.toString(), TASK_TIMEOUT);
        if (r.status() >= 400) throw new IllegalStateException("上游 " + r.status() + ": " + shortErr(r.body()));
        JsonNode j = mapper.readTree(r.body());
        String taskId = j.path("task_id").asText("");
        if (taskId.isEmpty()) throw new IllegalStateException("视频提交响应缺少 task_id: " + shortErr(r.body()));

        // 轮询到完成（前台任务通常 1~3 分钟）
        String base = intl ? "/v1/video/generations/" : "/api/v3/contents/generations/tasks/";
        String videoUrl = "";
        String failReason = "";
        for (int i = 0; i < VIDEO_POLL_MAX; i++) {
            Thread.sleep(3000);
            String poll = proxy.proxyGet(task.site, base + taskId, task.auth, POLL_TIMEOUT, null);
            JsonNode pj;
            try { pj = mapper.readTree(poll); } catch (Exception ex) { continue; }
            String st = pj.path("data").path("status").asText("UNKNOWN");
            if ("SUCCESS".equals(st)) {
                videoUrl = pj.path("data").path("data").path("content").path("video_url").asText("");
                break;
            } else if ("FAILURE".equals(st)) {
                failReason = pj.path("data").path("fail_reason").asText("未知原因");
                throw new IllegalStateException("视频生成失败: " + failReason);
            }
        }
        if (videoUrl.isEmpty()) throw new IllegalStateException("视频生成超时或未返回视频链接");

        ObjectNode o = mapper.createObjectNode();
        o.put("type", "video");
        o.put("videoUrl", videoUrl);
        o.put("taskId", taskId);
        o.put("model", model);
        return o;
    }

    // ================= 条件 / 循环 / 汇合节点 =================

    /** if 条件路由：评估条件，透传上游内容，激活 then/else 之一。 */
    private JsonNode runIf(WfTask task, JsonNode cfg, List<JsonNode> ups,
                           Map<String, JsonNode> nodeOutputs, Map<String, Object> locals) {
        String cond = expandVars(cfg.path("cond").asText(""), nodeOutputs, locals);
        List<String> texts = new ArrayList<>();
        List<String> imgs = new ArrayList<>();
        List<String> vids = new ArrayList<>();
        collect(ups, texts, imgs, vids);
        String joined = joinTexts(texts);
        boolean yes = Expr.eval(cond, buildVars(nodeOutputs, locals, joined));
        ObjectNode o = mapper.createObjectNode();
        o.put("type", "route");
        o.put("text", joined);
        if (!imgs.isEmpty()) { ArrayNode a = mapper.createArrayNode(); imgs.forEach(a::add); o.set("images", a); }
        if (!vids.isEmpty()) { ArrayNode a = mapper.createArrayNode(); vids.forEach(a::add); o.set("videos", a); }
        ObjectNode route = mapper.createObjectNode();
        route.put("then", yes);
        route.put("else", !yes);
        o.set("route", route);
        return o;
    }

    /** switch 多分支路由：顺序匹配 cases，命中分支激活，否则 default。 */
    private JsonNode runSwitch(WfTask task, JsonNode cfg, List<JsonNode> ups,
                               Map<String, JsonNode> nodeOutputs, Map<String, Object> locals) {
        List<String> texts = new ArrayList<>();
        List<String> imgs = new ArrayList<>();
        List<String> vids = new ArrayList<>();
        collect(ups, texts, imgs, vids);
        String joined = joinTexts(texts);
        Map<String, Object> vars = buildVars(nodeOutputs, locals, joined);
        Map<String, Boolean> route = new LinkedHashMap<>();
        boolean matched = false;
        JsonNode cases = cfg.path("cases");
        if (cases.isArray()) {
            for (JsonNode cs : cases) {
                String label = cs.path("label").asText("case");
                String expr = expandVars(cs.path("expr").asText(""), nodeOutputs, locals);
                boolean hit = !expr.isBlank() && Expr.eval(expr, vars);
                route.put(label, hit);
                if (hit) matched = true;
            }
        }
        route.put("default", !matched);
        ObjectNode o = mapper.createObjectNode();
        o.put("type", "route");
        o.put("text", joined);
        if (!imgs.isEmpty()) { ArrayNode a = mapper.createArrayNode(); imgs.forEach(a::add); o.set("images", a); }
        if (!vids.isEmpty()) { ArrayNode a = mapper.createArrayNode(); vids.forEach(a::add); o.set("videos", a); }
        o.set("route", mapper.valueToTree(route));
        return o;
    }

    /** loop 循环节点：按行迭代或固定次数，每次调用对话模型，收集结果数组。 */
    private JsonNode runLoop(WfTask task, JsonNode cfg, List<JsonNode> ups,
                             Map<String, JsonNode> nodeOutputs, Map<String, Object> locals) throws Exception {
        String model = cfg.path("model").asText("");
        if (model.isEmpty()) throw new IllegalStateException("循环节点未指定对话模型");
        String mode = cfg.path("mode").asText("lines");
        String promptTpl = cfg.path("prompt").asText("");
        int maxTok = cfg.path("maxTokens").asInt(1200);
        List<String> texts = new ArrayList<>();
        List<String> imgs = new ArrayList<>();
        collect(ups, texts, imgs, null);
        String base = joinTexts(texts);

        List<String> items = new ArrayList<>();
        if ("lines".equals(mode)) {
            for (String line : base.split("\\R")) {
                if (!line.isBlank()) items.add(line.trim());
            }
            if (items.isEmpty()) items.add(base);
        } else {
            int n = Math.min(Math.max(cfg.path("count").asInt(3), 1), 50);
            for (int i = 0; i < n; i++) items.add("");
        }
        if (items.isEmpty()) items.add("");

        ArrayNode results = mapper.createArrayNode();
        StringBuilder sb = new StringBuilder();
        int idx = 0;
        for (String item : items) {
            Map<String, Object> loc = new HashMap<>(locals);
            loc.put("i", idx + 1);
            loc.put("item", item);
            loc.put("count", items.size());
            String prompt = expandVars(promptTpl, nodeOutputs, loc);
            String content = composePrompt(prompt, item.isEmpty() ? base : item);
            String text = chatCall(task, model, content, new ArrayList<>(), maxTok, null);
            ObjectNode it = mapper.createObjectNode();
            it.put("type", "chat");
            it.put("text", text);
            it.put("model", model);
            results.add(it);
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(text);
            idx++;
        }
        ObjectNode o = mapper.createObjectNode();
        o.put("type", "loop");
        o.put("text", sb.toString());
        o.put("count", results.size());
        o.set("items", results);
        return o;
    }

    /** merge 汇合节点：把多个上游输出合并为一个（文本 + 资源），供下游统一使用。 */
    private JsonNode runMerge(JsonNode cfg, List<JsonNode> ups) {
        String sep = cfg.path("separator").asText("\n\n");
        List<String> texts = new ArrayList<>();
        List<String> imgs = new ArrayList<>();
        List<String> vids = new ArrayList<>();
        collect(ups, texts, imgs, vids);
        ObjectNode o = mapper.createObjectNode();
        o.put("type", "merge");
        o.put("text", String.join(sep, texts));
        if (!imgs.isEmpty()) { ArrayNode a = mapper.createArrayNode(); imgs.forEach(a::add); o.set("images", a); }
        if (!vids.isEmpty()) { ArrayNode a = mapper.createArrayNode(); vids.forEach(a::add); o.set("videos", a); }
        ArrayNode items = mapper.createArrayNode();
        ups.forEach(items::add);
        o.set("items", items);
        return o;
    }

    // ================= End 节点 =================

    private JsonNode runEnd(Map<String, JsonNode> nodeOutputs, Map<String, JsonNode> nodeMap) {
        ObjectNode o = mapper.createObjectNode();
        o.put("type", "result");
        StringBuilder sb = new StringBuilder();
        ArrayNode items = mapper.createArrayNode();
        int count = 0;
        for (Map.Entry<String, JsonNode> en : nodeOutputs.entrySet()) {
            String id = en.getKey();
            JsonNode v = en.getValue();
            String vtype = v.path("type").asText("");
            if ("result".equals(vtype)) continue; // 跳过其他 end 节点输出
            count++;
            String label = nodeMap.containsKey(id) ? nodeMap.get(id).path("label").asText(id) : id;
            String preview = switch (vtype) {
                case "text", "chat", "route", "merge", "loop" -> v.path("text").asText("").length() > 160
                        ? v.path("text").asText("").substring(0, 160) + "…" : v.path("text").asText("");
                case "image" -> "图像 ×" + v.path("images").size();
                case "video" -> v.path("videoUrl").asText("");
                default -> "";
            };
            if (sb.length() > 0) sb.append("\n");
            sb.append("■ ").append(label).append("：").append(preview);
            ObjectNode item = mapper.createObjectNode();
            item.put("id", id);
            item.put("label", label);
            item.set("output", v);
            items.add(item);
        }
        o.put("summary", sb.toString());
        o.put("count", count);
        o.set("items", items);
        return o;
    }

    // ================= 工具 =================

    /** 一条带端口的连线（路由节点区分 then/else/case 输出端口）。 */
    private record EdgeRef(String from, String fromPort, String to, String toPort) {}

    /** 变量引用：{{名字}} 或 {{ 名字 }}；名字可为节点 id、item/i/count 等局部变量。 */
    private static final Pattern VAR_PATTERN = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.\\-]+)\\s*}}");

    /** 把字符串里的 {{变量}} 展开为对应输出文本；未知变量保留原样。 */
    private String expandVars(String s, Map<String, JsonNode> nodeOutputs, Map<String, Object> locals) {
        if (s == null || s.isEmpty()) return s;
        Matcher m = VAR_PATTERN.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            String val = null;
            if (locals != null && locals.containsKey(name)) val = String.valueOf(locals.get(name));
            else if (nodeOutputs != null && nodeOutputs.containsKey(name)) val = varString(nodeOutputs.get(name));
            m.appendReplacement(sb, Matcher.quoteReplacement(val == null ? m.group(0) : val));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 节点输出 → 便于拼进提示词的字符串（文本优先，其次图片 data URL / 视频 URL）。 */
    private String varString(JsonNode out) {
        if (out == null) return "";
        String t = out.path("text").asText("");
        if (!t.isEmpty()) return t;
        String type = out.path("type").asText("");
        if ("image".equals(type) || "route".equals(type) || "merge".equals(type)) {
            JsonNode im = out.path("images");
            if (im.isArray() && im.size() > 0) return im.get(0).asText("");
        }
        if ("video".equals(type)) return out.path("videoUrl").asText("");
        return out.toString();
    }

    /** 为条件表达式构建变量表：text=本节点承接文本 + 各节点 id 输出 + 局部变量。 */
    private Map<String, Object> buildVars(Map<String, JsonNode> nodeOutputs, Map<String, Object> locals, String text) {
        Map<String, Object> vars = new HashMap<>();
        if (locals != null) vars.putAll(locals);
        vars.put("text", text == null ? "" : text);
        if (nodeOutputs != null) {
            for (Map.Entry<String, JsonNode> en : nodeOutputs.entrySet()) {
                vars.put(en.getKey(), varString(en.getValue()));
            }
        }
        return vars;
    }

    /**
     * 极简安全表达式求值器（工作流条件判断用，不执行任意代码）。
     * 支持：数值/字符串字面量、变量、括号、比较 == != &gt; &lt; &gt;= &lt;=、
     * 算术 + - * / %、逻辑 and or not !、
     * 函数 len int round contains startsWith endsWith matches notEmpty trim toUpper toLower abs。
     */
    public static final class Expr {
        private static final class Tok {
            final String s; final char c; final int k; // 0 数字 1 字符串 2 标识符/关键字 3 运算符 4 ( 5 ) 6 , 7 结束
            Tok(String s, char c, int k) { this.s = s; this.c = c; this.k = k; }
        }
        private static final class P {
            final List<Tok> ts = new ArrayList<>();
            final Map<String, Object> vars;
            int i = 0;
            P(String src, Map<String, Object> vars) { this.vars = vars; lex(src); ts.add(new Tok("", (char) 0, 7)); }
            void lex(String src) {
                int n = src.length(), j = 0;
                while (j < n) {
                    char ch = src.charAt(j);
                    if (Character.isWhitespace(ch)) { j++; continue; }
                    if (ch == '(') { ts.add(new Tok("", '(', 4)); j++; continue; }
                    if (ch == ')') { ts.add(new Tok("", ')', 5)); j++; continue; }
                    if (ch == ',') { ts.add(new Tok("", ',', 6)); j++; continue; }
                    if (ch == '=' && j + 1 < n && src.charAt(j + 1) == '=') { ts.add(new Tok("==", '=', 3)); j += 2; continue; }
                    if (ch == '!' && j + 1 < n && src.charAt(j + 1) == '=') { ts.add(new Tok("!=", '=', 3)); j += 2; continue; }
                    if (ch == '>' && j + 1 < n && src.charAt(j + 1) == '=') { ts.add(new Tok(">=", '=', 3)); j += 2; continue; }
                    if (ch == '<' && j + 1 < n && src.charAt(j + 1) == '=') { ts.add(new Tok("<=", '=', 3)); j += 2; continue; }
                    if (ch == '!' || ch == '>' || ch == '<' || ch == '+' || ch == '-' || ch == '*' || ch == '/' || ch == '%') {
                        ts.add(new Tok(String.valueOf(ch), ch, 3)); j++; continue;
                    }
                    if (ch == '"' || ch == '\'') {
                        char q = ch; StringBuilder sb = new StringBuilder(); j++;
                        while (j < n && src.charAt(j) != q) { sb.append(src.charAt(j)); j++; }
                        j++;
                        ts.add(new Tok(sb.toString(), 's', 1)); continue;
                    }
                    if (Character.isDigit(ch) || ch == '.') {
                        int s0 = j;
                        while (j < n && (Character.isDigit(src.charAt(j)) || src.charAt(j) == '.')) j++;
                        ts.add(new Tok(src.substring(s0, j), 'n', 0)); continue;
                    }
                    if (Character.isLetter(ch) || ch == '_') {
                        int s0 = j;
                        while (j < n && (Character.isLetterOrDigit(src.charAt(j)) || src.charAt(j) == '_' || src.charAt(j) == '.')) j++;
                        ts.add(new Tok(src.substring(s0, j), 'i', 2)); continue;
                    }
                    j++;
                }
            }
            Tok peek() { return ts.get(i); }
            Tok next() { return ts.get(i++); }
            Object evalExpr() { return evalOr(); }
            Object evalOr() {
                Object a = evalAnd();
                while ("or".equals(peek().s) || "||".equals(peek().s)) { next(); a = truth(a) || truth(evalAnd()); }
                return a;
            }
            Object evalAnd() {
                Object a = evalCmp();
                while ("and".equals(peek().s) || "&&".equals(peek().s)) { next(); a = truth(a) && truth(evalCmp()); }
                return a;
            }
            Object evalCmp() {
                Object a = evalAdd();
                Tok op = peek();
                if (op.k == 3 && (op.s.equals("==") || op.s.equals("!=") || op.s.equals(">") || op.s.equals("<") || op.s.equals(">=") || op.s.equals("<="))) {
                    next();
                    return compare(op.s, a, evalAdd());
                }
                return a;
            }
            Object evalAdd() {
                Object a = evalMul();
                while (peek().k == 3 && (peek().c == '+' || peek().c == '-')) {
                    char op = next().c;
                    Object b = evalMul();
                    if (a instanceof String || b instanceof String) a = String.valueOf(a) + b;
                    else a = num(a) + (op == '+' ? num(b) : -num(b));
                }
                return a;
            }
            Object evalMul() {
                Object a = evalUnary();
                while (peek().k == 3 && (peek().c == '*' || peek().c == '/' || peek().c == '%')) {
                    char op = next().c;
                    double y = num(evalUnary());
                    double x = num(a);
                    a = op == '*' ? x * y : (op == '/' ? (y == 0 ? 0 : x / y) : (y == 0 ? 0 : x % y));
                }
                return a;
            }
            Object evalUnary() {
                if (peek().k == 3 && peek().c == '!') { next(); return !truth(evalUnary()); }
                if (peek().k == 3 && peek().c == '-') { next(); return -num(evalUnary()); }
                return evalPrimary();
            }
            Object evalPrimary() {
                Tok t = peek();
                if (t.k == 0) { next(); return Double.parseDouble(t.s); }
                if (t.k == 1) { next(); return t.s; }
                if (t.k == 2) {
                    next();
                    if (peek().k == 4) {
                        next();
                        List<Object> args = new ArrayList<>();
                        if (peek().k != 5) {
                            args.add(evalExpr());
                            while (peek().k == 6) { next(); args.add(evalExpr()); }
                        }
                        next();
                        return call(t.s, args);
                    }
                    Object v = vars.get(t.s);
                    return v == null ? "" : v;
                }
                if (t.k == 4) { next(); Object v = evalExpr(); if (peek().k == 5) next(); return v; }
                next();
                return "";
            }
        }

        public static boolean eval(String expr, Map<String, Object> vars) {
            try {
                if (expr == null || expr.isBlank()) return false;
                P p = new P(expr, vars == null ? new HashMap<>() : vars);
                return truth(p.evalExpr());
            } catch (Exception e) {
                return false;
            }
        }

        private static boolean truth(Object v) {
            if (v == null) return false;
            if (v instanceof Boolean b) return b;
            if (v instanceof Number n) return n.doubleValue() != 0;
            String s = String.valueOf(v).trim().toLowerCase();
            if (s.isEmpty()) return false;
            if (s.equals("true") || s.equals("yes") || s.equals("1")) return true;
            if (s.equals("false") || s.equals("no") || s.equals("0")) return false;
            return true;
        }

        private static double num(Object v) {
            if (v instanceof Number n) return n.doubleValue();
            String s = String.valueOf(v).trim();
            try { return Double.parseDouble(s); } catch (Exception e) { return 0; }
        }

        private static boolean isNumericStr(Object v) {
            if (v instanceof Number) return true;
            if (v instanceof String s) { try { Double.parseDouble(s.trim()); return true; } catch (Exception e) { /* not numeric */ } }
            return false;
        }

        private static boolean compare(String op, Object a, Object b) {
            boolean bothNum = isNumericStr(a) && isNumericStr(b);
            if (op.equals("==")) return bothNum ? num(a) == num(b) : String.valueOf(a).equals(String.valueOf(b));
            if (op.equals("!=")) return bothNum ? num(a) != num(b) : !String.valueOf(a).equals(String.valueOf(b));
            double x = num(a), y = num(b);
            switch (op) {
                case ">": return x > y;
                case "<": return x < y;
                case ">=": return x >= y;
                case "<=": return x <= y;
                default: return false;
            }
        }

        private static Object call(String name, List<Object> args) {
            switch (name.toLowerCase()) {
                case "len": return args.size() > 0 ? (double) String.valueOf(args.get(0)).length() : 0.0;
                case "int": return args.size() > 0 ? (double) (long) num(args.get(0)) : 0.0;
                case "round": return args.size() > 0 ? (double) Math.round(num(args.get(0))) : 0.0;
                case "abs": return args.size() > 0 ? Math.abs(num(args.get(0))) : 0.0;
                case "contains": return args.size() > 1 && String.valueOf(args.get(0)).contains(String.valueOf(args.get(1)));
                case "startswith": return args.size() > 1 && String.valueOf(args.get(0)).startsWith(String.valueOf(args.get(1)));
                case "endswith": return args.size() > 1 && String.valueOf(args.get(0)).endsWith(String.valueOf(args.get(1)));
                case "matches": return args.size() > 1 && String.valueOf(args.get(0)).matches(String.valueOf(args.get(1)));
                case "notempty": return args.size() > 0 && !String.valueOf(args.get(0)).trim().isEmpty();
                case "trim": return args.size() > 0 ? String.valueOf(args.get(0)).trim() : "";
                case "toupper": return args.size() > 0 ? String.valueOf(args.get(0)).toUpperCase() : "";
                case "tolower": return args.size() > 0 ? String.valueOf(args.get(0)).toLowerCase() : "";
                case "not": return args.size() > 0 && !truth(args.get(0));
                default: return "";
            }
        }
    }

    /** "data:image/png;base64,xxx" → [mime, base64] */
    private String[] splitDataUrl(String dataUrl) {
        int comma = dataUrl.indexOf(",");
        if (comma > 0 && dataUrl.startsWith("data:")) {
            String meta = dataUrl.substring(5, comma);
            String mime = meta.contains(";") ? meta.substring(0, meta.indexOf(';')) : "image/png";
            return new String[]{mime, dataUrl.substring(comma + 1)};
        }
        return new String[]{"image/png", dataUrl};
    }

    private String detectMime(String b64) {
        if (b64.startsWith("iVBORw0")) return "image/png";
        if (b64.startsWith("/9j/")) return "image/jpeg";
        if (b64.startsWith("UklGR")) return "image/webp";
        return "image/png";
    }

    private String shortErr(String raw) {
        try {
            JsonNode j = mapper.readTree(raw);
            if (j.path("error").path("message").isTextual()) return j.path("error").path("message").asText();
            if (j.path("error").path("msg").isTextual()) return j.path("error").path("msg").asText();
            if (j.path("message").isTextual()) return j.path("message").asText();
            if (j.path("msg").isTextual()) return j.path("msg").asText();
            return raw.length() > 200 ? raw.substring(0, 200) : raw;
        } catch (Exception e) {
            return raw.length() > 200 ? raw.substring(0, 200) : raw;
        }
    }
}