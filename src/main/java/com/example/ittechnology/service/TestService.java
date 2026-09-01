package com.example.ittechnology.service;

import com.example.ittechnology.service.AiProxyService.FetchResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试验证服务：供售后快速验证模型行为。
 *
 *  - chatOnce     单次非流式对话（兼容 Anthropic / OpenAI / Gemini）
 *  - runBatch     多提示词批量执行（可并发），统计成功率与耗时
 *  - runAb        同一提示词发给多个模型并行对比
 */
@Slf4j
@Service
public class TestService {

    private static final Duration TIMEOUT = Duration.ofSeconds(180);

    private final AiProxyService proxy;
    private final ObjectMapper mapper = new ObjectMapper();

    public TestService(AiProxyService proxy) {
        this.proxy = proxy;
    }

    /** 单条结果 */
    private record OneResult(int index, String label, boolean ok, String text, String error, long ms) {}

    // ================= 批量执行 =================

    /**
     * 批量压测。
     * body: {model, prompts:[...], max_tokens?, concurrency?, temperature?, top_p?, presence_penalty?, frequency_penalty?, seed?}
     */
    public ObjectNode runBatch(String site, String auth, JsonNode req) throws Exception {
        String model = req.path("model").asText("");
        int maxTok = req.path("max_tokens").asInt(1500);
        int concurrency = Math.max(1, Math.min(req.path("concurrency").asInt(3), 10));
        List<String> prompts = new ArrayList<>();
        for (JsonNode p : req.path("prompts")) {
            String t = p.asText("").trim();
            if (!t.isEmpty()) prompts.add(t);
        }
        if (model.isEmpty()) throw new IllegalStateException("请选择模型");
        if (prompts.isEmpty()) throw new IllegalStateException("至少提供一条提示词");

        ExecutorService pool = Executors.newFixedThreadPool(Math.min(concurrency, prompts.size()));
        try {
            List<Callable<OneResult>> tasks = new ArrayList<>();
            for (int i = 0; i < prompts.size(); i++) {
                final int idx = i;
                final String prompt = prompts.get(i);
                tasks.add(() -> {
                    long t0 = System.currentTimeMillis();
                    try {
                        String text = chatOnce(site, auth, model, prompt, maxTok, req);
                        return new OneResult(idx, prompt, true, text, null, System.currentTimeMillis() - t0);
                    } catch (Exception e) {
                        return new OneResult(idx, prompt, false, null, e.getMessage(), System.currentTimeMillis() - t0);
                    }
                });
            }
            List<Future<OneResult>> futures = pool.invokeAll(tasks);

            ObjectNode out = mapper.createObjectNode();
            ArrayNode results = mapper.createArrayNode();
            int ok = 0, fail = 0;
            long sum = 0, max = 0;
            for (Future<OneResult> f : futures) {
                OneResult r = f.get();
                if (r.ok()) ok++; else fail++;
                sum += r.ms();
                if (r.ms() > max) max = r.ms();
                ObjectNode o = mapper.createObjectNode();
                o.put("index", r.index());
                o.put("prompt", r.label());
                o.put("ok", r.ok());
                o.put("text", r.text() == null ? "" : r.text());
                o.put("error", r.error() == null ? "" : r.error());
                o.put("ms", r.ms());
                results.add(o);
            }
            out.put("model", model);
            out.put("total", prompts.size());
            out.put("ok", ok);
            out.put("fail", fail);
            out.put("avgMs", prompts.isEmpty() ? 0 : sum / prompts.size());
            out.put("maxMs", max);
            out.set("results", results);
            pool.shutdown();
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    // ================= A/B 对比 =================

    /**
     * 同一提示词并行发往多个模型。
     * body: {prompt, models:[...], max_tokens?, temperature?, ...}
     */
    public ObjectNode runAb(String site, String auth, JsonNode req) throws Exception {
        String prompt = req.path("prompt").asText("").trim();
        int maxTok = req.path("max_tokens").asInt(1500);
        List<String> models = new ArrayList<>();
        for (JsonNode m : req.path("models")) {
            String s = m.asText("").trim();
            if (!s.isEmpty()) models.add(s);
        }
        if (prompt.isEmpty()) throw new IllegalStateException("请输入测试提示词");
        if (models.isEmpty()) throw new IllegalStateException("请至少选择一个模型");

        ExecutorService pool = Executors.newFixedThreadPool(Math.min(models.size(), 6));
        try {
            List<Callable<OneResult>> tasks = new ArrayList<>();
            for (String model : models) {
                tasks.add(() -> {
                    long t0 = System.currentTimeMillis();
                    try {
                        String text = chatOnce(site, auth, model, prompt, maxTok, req);
                        return new OneResult(0, model, true, text, null, System.currentTimeMillis() - t0);
                    } catch (Exception e) {
                        return new OneResult(0, model, false, null, e.getMessage(), System.currentTimeMillis() - t0);
                    }
                });
            }
            List<Future<OneResult>> futures = pool.invokeAll(tasks);

            ObjectNode out = mapper.createObjectNode();
            out.put("prompt", prompt);
            ArrayNode results = mapper.createArrayNode();
            for (Future<OneResult> f : futures) {
                OneResult r = f.get();
                ObjectNode o = mapper.createObjectNode();
                o.put("model", r.label());
                o.put("ok", r.ok());
                o.put("text", r.text() == null ? "" : r.text());
                o.put("error", r.error() == null ? "" : r.error());
                o.put("ms", r.ms());
                results.add(o);
            }
            out.set("results", results);
            pool.shutdown();
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    // ================= 单次非流式对话 =================

    /** 单次调用，返回文本。prompt 作为单条 user 消息发送。 */
    String chatOnce(String site, String auth, String model, String prompt, int maxTok, JsonNode params) throws Exception {
        String lm = model.toLowerCase();
        if (lm.contains("gemini")) {
            ObjectNode body = mapper.createObjectNode();
            ArrayNode contents = mapper.createArrayNode();
            ObjectNode user = mapper.createObjectNode();
            user.put("role", "user");
            ArrayNode parts = mapper.createArrayNode();
            ObjectNode t = mapper.createObjectNode();
            t.put("text", prompt);
            parts.add(t);
            user.set("parts", parts);
            contents.add(user);
            body.set("contents", contents);
            ObjectNode gc = mapper.createObjectNode();
            gc.put("maxOutputTokens", maxTok);
            if (params != null && params.has("temperature")) gc.put("temperature", params.get("temperature").asDouble());
            if (params != null && params.has("top_p")) gc.put("topP", params.get("top_p").asDouble());
            body.set("generationConfig", gc);
            String p = "/v1beta/models/" + model + ":generateContent";
            FetchResult r = proxy.postJson(site, p, auth, body.toString(), TIMEOUT);
            if (r.status() >= 400) throw new IllegalStateException("上游 " + r.status() + ": " + truncate(r.body(), 300));
            JsonNode j = mapper.readTree(r.body());
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : j.path("candidates").path(0).path("content").path("parts")) {
                String s = part.path("text").asText("");
                if (!s.isEmpty()) { if (sb.length() > 0) sb.append("\n"); sb.append(s); }
            }
            return sb.toString();
        }

        boolean anthropic = lm.contains("claude");
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", maxTok);
        body.put("stream", false);
        ArrayNode messages = mapper.createArrayNode();
        ObjectNode user = mapper.createObjectNode();
        user.put("role", "user");
        user.put("content", prompt);
        messages.add(user);
        body.set("messages", messages);
        // 参数（OpenAI 全支持；Anthropic 只取 temperature/top_p）
        if (params != null) {
            if (params.has("temperature")) body.put("temperature", params.get("temperature").asDouble());
            if (params.has("top_p") && params.get("top_p").asDouble() > 0) body.put("top_p", params.get("top_p").asDouble());
            if (!anthropic) {
                if (params.has("presence_penalty") && params.get("presence_penalty").asDouble() != 0)
                    body.put("presence_penalty", params.get("presence_penalty").asDouble());
                if (params.has("frequency_penalty") && params.get("frequency_penalty").asDouble() != 0)
                    body.put("frequency_penalty", params.get("frequency_penalty").asDouble());
                if (params.has("seed") && params.get("seed").canConvertToLong())
                    body.put("seed", params.get("seed").asLong());
            }
        }

        String path = anthropic ? "/v1/messages" : "/v1/chat/completions";
        FetchResult r = proxy.postJson(site, path, auth, body.toString(), TIMEOUT);
        if (r.status() >= 400) throw new IllegalStateException("上游 " + r.status() + ": " + truncate(r.body(), 300));
        JsonNode j = mapper.readTree(r.body());
        if (anthropic) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode b : j.path("content")) {
                String s = b.path("text").asText("");
                if (!s.isEmpty()) { if (sb.length() > 0) sb.append("\n"); sb.append(s); }
            }
            return sb.toString();
        }
        return j.path("choices").path(0).path("message").path("content").asText("");
    }

    /**
     * Claude 上下文缓存命中率测试（参考官方 prompt caching 语义）。
     *
     * body: {model, systemDoc, repeat?, questions?, max_tokens?, delay?, ttl?}
     *
     * 原理：
     *  - system 内容块数组 + cache_control: ephemeral 打断点（缓存前缀=请求开头到断点）；
     *  - 第 1 次请求 → usage.cache_creation_input_tokens > 0（写入缓存）；
     *  - 之后前缀完全一致 → usage.cache_read_input_tokens > 0（命中缓存）；
     *  - 每轮变化的问题放在 messages 末尾，不破坏 system 前缀。
     *
     * 命中率 = cache_read / (input_tokens + cache_read + cache_creation)
     * （Anthropic 的 input_tokens 不含缓存部分；OpenAI 的 prompt_tokens 则包含，公式不同）
     */
    public ObjectNode runCacheTest(String site, String auth, JsonNode req) throws Exception {
        String model = req.path("model").asText("");
        String systemDoc = req.path("systemDoc").asText("");
        int repeat = Math.max(1, Math.min(req.path("repeat").asInt(3), 20));
        int maxTok = Math.max(1, Math.min(req.path("max_tokens").asInt(16), 2048));
        double delaySec = Math.max(0, Math.min(req.path("delay").asDouble(2), 120));
        String ttl = req.path("ttl").asText("").trim();
        List<String> questions = new ArrayList<>();
        for (JsonNode q : req.path("questions")) {
            String s = q.asText("").trim();
            if (!s.isEmpty()) questions.add(s);
        }
        if (model.isEmpty()) throw new IllegalStateException("请选择模型");
        if (systemDoc.isBlank()) throw new IllegalStateException("请输入固定前缀文档（systemDoc）");
        // 没有提供问题则按轮次自动生成
        int rounds = questions.isEmpty() ? Math.max(2, Math.min(req.path("rounds").asInt(10), 30)) : questions.size();
        if (questions.isEmpty()) {
            for (int i = 1; i <= rounds; i++) questions.add("这是第 " + i + " 次提问，请只回复:OK" + i);
        } else {
            rounds = questions.size();
        }

        // system 前缀：文档×repeat + 系统指令，整段打 cache_control 断点
        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < repeat; i++) {
            if (i > 0) prefix.append("\n\n");
            prefix.append(systemDoc);
        }
        prefix.append("\n\n你是一个专业的AI编程助手，请始终使用中文回答。");
        String systemText = prefix.toString();

        ObjectNode cc = mapper.createObjectNode();
        cc.put("type", "ephemeral");
        if (!ttl.isEmpty()) cc.put("ttl", ttl);
        ObjectNode sysBlock = mapper.createObjectNode();
        sysBlock.put("type", "text");
        sysBlock.put("text", systemText);
        sysBlock.set("cache_control", cc);
        ArrayNode systemArr = mapper.createArrayNode();
        systemArr.add(sysBlock);

        // 逐轮请求（串行，间隔 delay）
        ArrayNode results = mapper.createArrayNode();
        long totalInput = 0, totalRead = 0, totalWrite = 0;
        int readReqs = 0, writeReqs = 0, okCount = 0;
        List<Double> latencies = new ArrayList<>();
        for (int i = 0; i < rounds; i++) {
            long t0 = System.currentTimeMillis();
            ObjectNode o = mapper.createObjectNode();
            o.put("round", i + 1);
            o.put("question", questions.get(i));
            try {
                ObjectNode body = mapper.createObjectNode();
                body.put("model", model);
                body.put("max_tokens", maxTok);
                body.put("stream", false);
                body.set("system", systemArr);
                ArrayNode messages = mapper.createArrayNode();
                ObjectNode user = mapper.createObjectNode();
                user.put("role", "user");
                user.put("content", questions.get(i));
                messages.add(user);
                body.set("messages", messages);

                FetchResult r = proxy.postJson(site, "/v1/messages", auth, body.toString(), TIMEOUT);
                if (r.status() >= 400) throw new IllegalStateException("上游 " + r.status() + ": " + truncate(r.body(), 300));
                JsonNode j = mapper.readTree(r.body());
                if (j.has("error") || "error".equals(j.path("type").asText())) {
                    throw new IllegalStateException(j.path("error").toString());
                }
                JsonNode u = j.path("usage");
                int input = u.path("input_tokens").asInt(0);
                int read = u.path("cache_read_input_tokens").asInt(0);
                int write = u.path("cache_creation_input_tokens").asInt(0);
                int output = u.path("output_tokens").asInt(0);
                int total = input + read + write;
                double hitRate = total > 0 ? (double) read / total : 0.0;

                o.put("ok", true);
                o.put("input_tokens", input);
                o.put("cached_read", read);
                o.put("cached_write", write);
                o.put("completion_tokens", output);
                o.put("total_input", total);
                o.put("hit_rate", Math.round(hitRate * 10000) / 100.0);
                if (read > 0) readReqs++;
                if (write > 0) writeReqs++;
                totalInput += total;
                totalRead += read;
                totalWrite += write;
                okCount++;
            } catch (Exception e) {
                o.put("ok", false);
                o.put("error", e.getMessage());
            }
            double latency = (System.currentTimeMillis() - t0) / 1000.0;
            o.put("latency", Math.round(latency * 100) / 100.0);
            if (o.path("ok").asBoolean(false)) latencies.add(latency);
            results.add(o);

            if (i < rounds - 1 && delaySec > 0) {
                try { Thread.sleep((long) (delaySec * 1000)); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); break; }
            }
        }

        // 汇总
        ObjectNode out = mapper.createObjectNode();
        out.put("model", model);
        out.put("rounds", rounds);
        out.put("ok_count", okCount);
        out.put("fail_count", rounds - okCount);
        out.put("write_requests", writeReqs);
        out.put("read_requests", readReqs);
        out.put("total_read", totalRead);
        out.put("total_write", totalWrite);
        out.put("overall_hit_rate", totalInput > 0 ? Math.round(totalRead * 10000.0 / totalInput) / 100.0 : 0.0);
        if (okCount > 0) {
            double avg = latencies.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double mn = latencies.stream().mapToDouble(Double::doubleValue).min().orElse(0);
            double mx = latencies.stream().mapToDouble(Double::doubleValue).max().orElse(0);
            out.put("avg_latency", Math.round(avg * 100) / 100.0);
            out.put("min_latency", Math.round(mn * 100) / 100.0);
            out.put("max_latency", Math.round(mx * 100) / 100.0);
            // 稳态（排除第 1 次）
            double steadyTotal = 0, steadyRead = 0;
            int cnt = 0;
            for (JsonNode r : results) {
                if (!r.path("ok").asBoolean(false) || r.path("round").asInt(0) == 1) continue;
                steadyTotal += r.path("total_input").asDouble(0);
                steadyRead += r.path("cached_read").asDouble(0);
                cnt++;
            }
            if (cnt > 0) out.put("steady_hit_rate", steadyTotal > 0 ? Math.round(steadyRead * 10000.0 / steadyTotal) / 100.0 : 0.0);
            else out.put("steady_hit_rate", 0.0);
        } else {
            out.put("avg_latency", 0);
            out.put("min_latency", 0);
            out.put("max_latency", 0);
            out.put("steady_hit_rate", 0.0);
        }
        // 前缀 token 粗估（无 tiktoken：ASCII≈4字符/token，中文≈1字/token）
        int ascii = 0, cjk = 0;
        for (int k = 0; k < systemText.length(); k++) {
            if (systemText.charAt(k) < 128) ascii++; else cjk++;
        }
        long estTokens = Math.round(ascii / 4.0 + cjk * 1.0);
        out.put("prefix_chars", systemText.length());
        out.put("prefix_tokens_est", estTokens);
        out.put("prefix_min_ok", estTokens >= 1024);
        out.set("results", results);
        return out;
    }

    // ================= 全模型巡检 =================

    /**
     * 全模型巡检：对当前 Token 下的全部模型发一条短提示，统计可用/失败/耗时。
     * body: {prompt?, max_tokens?, concurrency?}
     * 站点由请求头 X-Site 决定（cn/intl），前端可分别巡检后做站点对比。
     */
    public ObjectNode runScan(String site, String auth, JsonNode req) throws Exception {
        String prompt = req.path("prompt").asText("").trim();
        if (prompt.isEmpty()) prompt = "请只回复两个字：正常";
        final String scanPrompt = prompt;
        int maxTok = Math.max(4, Math.min(req.path("max_tokens").asInt(16), 128));
        int concurrency = Math.max(1, Math.min(req.path("concurrency").asInt(5), 10));

        // 1) 拉取模型列表（OpenAI 兼容 /v1/models）
        AtomicInteger st = new AtomicInteger(200);
        String modelsJson = proxy.proxyGet(site, "/v1/models", auth, Duration.ofSeconds(30), st);
        if (st.get() >= 400) {
            throw new IllegalStateException("获取模型列表失败 " + st.get() + ": " + truncate(modelsJson, 300));
        }
        JsonNode modelsNode = mapper.readTree(modelsJson);
        List<String> models = new ArrayList<>();
        for (JsonNode m : modelsNode.path("data")) {
            String id = m.path("id").asText("");
            if (!id.isEmpty()) models.add(id);
        }
        if (models.isEmpty()) throw new IllegalStateException("当前 Token 下没有任何可用模型");

        // 2) 并发巡检
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(concurrency, models.size()));
        try {
            List<Callable<OneResult>> tasks = new ArrayList<>();
            for (String model : models) {
                tasks.add(() -> {
                    long t0 = System.currentTimeMillis();
                    try {
                        chatOnce(site, auth, model, scanPrompt, maxTok, null);
                        return new OneResult(0, model, true, null, null, System.currentTimeMillis() - t0);
                    } catch (Exception e) {
                        return new OneResult(0, model, false, null, e.getMessage(), System.currentTimeMillis() - t0);
                    }
                });
            }
            List<Future<OneResult>> futures = pool.invokeAll(tasks);

            ObjectNode out = mapper.createObjectNode();
            out.put("site", site);
            out.put("total", models.size());
            out.put("prompt", prompt);
            ArrayNode results = mapper.createArrayNode();
            int ok = 0, fail = 0;
            long sum = 0, min = Long.MAX_VALUE, max = 0;
            for (Future<OneResult> f : futures) {
                OneResult r = f.get();
                if (r.ok()) ok++; else fail++;
                sum += r.ms();
                if (r.ms() < min) min = r.ms();
                if (r.ms() > max) max = r.ms();
                ObjectNode o = mapper.createObjectNode();
                o.put("model", r.label());
                o.put("ok", r.ok());
                o.put("ms", r.ms());
                if (r.error() != null) o.put("error", truncate(r.error(), 200));
                results.add(o);
            }
            out.put("ok", ok);
            out.put("fail", fail);
            out.put("avgMs", models.isEmpty() ? 0 : sum / models.size());
            out.put("minMs", min == Long.MAX_VALUE ? 0 : min);
            out.put("maxMs", max);
            out.set("results", results);
            pool.shutdown();
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    // ================= 结构化输出验证 =================

    /**
     * 结构化输出验证：请求模型输出 JSON，并用（可选的）JSON Schema 做轻量校验。
     * body: {model, prompt, schema?, max_tokens?}
     *   - schema 支持子集：type / required / properties / items / enum / minLength / maxLength / minimum / maximum
     *   - 若未提供 schema，仅验证"是否为合法 JSON"并展示解析后的结构。
     */
    public ObjectNode runJsonTest(String site, String auth, JsonNode req) throws Exception {
        String model = req.path("model").asText("");
        String prompt = req.path("prompt").asText("").trim();
        int maxTok = Math.max(8, Math.min(req.path("max_tokens").asInt(1024), 4096));
        String schemaStr = req.path("schema").asText("").trim();
        if (model.isEmpty()) throw new IllegalStateException("请选择模型");
        if (prompt.isEmpty()) throw new IllegalStateException("请输入测试提示词");

        // 要求模型只输出 JSON
        String instruct = prompt + "\n\n请只输出一个合法的 JSON，不要包含任何解释、Markdown 代码块或多余文字。";

        ObjectNode out = mapper.createObjectNode();
        out.put("model", model);
        long t0 = System.currentTimeMillis();
        String text;
        try {
            text = chatOnce(site, auth, model, instruct, maxTok, req);
        } catch (Exception e) {
            out.put("ok", false);
            out.put("error", e.getMessage());
            out.put("ms", System.currentTimeMillis() - t0);
            return out;
        }
        out.put("ms", System.currentTimeMillis() - t0);

        // 剥离可能的代码块围栏
        String clean = text.trim();
        if (clean.startsWith("```")) {
            clean = clean.replaceFirst("^```(?:json)?\\s*", "");
            int end = clean.lastIndexOf("```");
            if (end > 0) clean = clean.substring(0, end);
            clean = clean.trim();
        }

        try {
            JsonNode parsed = mapper.readTree(clean);
            out.put("ok", true);
            out.put("json", mapper.writerWithDefaultPrettyPrinter().writeValueAsString(parsed));
            // schema 校验
            ArrayNode errors = mapper.createArrayNode();
            if (!schemaStr.isEmpty()) {
                try {
                    JsonNode schema = mapper.readTree(schemaStr);
                    validateAgainst(schema, parsed, errors, "$");
                } catch (Exception e) {
                    errors.add("Schema 解析失败: " + e.getMessage());
                }
            }
            out.put("schema_valid", errors.isEmpty());
            out.set("schema_errors", errors);
        } catch (Exception e) {
            out.put("ok", false);
            out.put("error", "输出不是合法 JSON: " + e.getMessage());
            out.put("raw", truncate(text, 1000));
        }
        return out;
    }

    /** 轻量 JSON Schema 校验（子集）。 */
    private void validateAgainst(JsonNode schema, JsonNode value, ArrayNode errors, String path) {
        String type = schema.path("type").asText("");
        if (!type.isEmpty() && !typeMatches(type, value)) {
            errors.add(path + " 期望类型 " + type + "，实际 " + (value == null ? "null" : value.getNodeType().name().toLowerCase()));
        }
        if (value == null || value.isNull()) return;

        if (value.isObject()) {
            JsonNode required = schema.path("required");
            if (required.isArray()) {
                for (JsonNode rn : required) {
                    String key = rn.asText("");
                    if (!value.has(key)) errors.add(path + "." + key + " 缺少必填字段");
                }
            }
            JsonNode props = schema.path("properties");
            if (props.isObject()) {
                props.fields().forEachRemaining(e -> {
                    if (value.has(e.getKey())) {
                        validateAgainst(e.getValue(), value.get(e.getKey()), errors, path + "." + e.getKey());
                    }
                });
            }
        } else if (value.isArray()) {
            JsonNode items = schema.path("items");
            if (items.isObject()) {
                int i = 0;
                for (JsonNode item : value) {
                    validateAgainst(items, item, errors, path + "[" + i + "]");
                    i++;
                }
            }
        } else if (value.isTextual()) {
            String s = value.asText();
            if (schema.has("minLength") && s.length() < schema.path("minLength").asInt()) errors.add(path + " 长度小于 minLength " + schema.path("minLength").asInt());
            if (schema.has("maxLength") && s.length() > schema.path("maxLength").asInt()) errors.add(path + " 长度大于 maxLength " + schema.path("maxLength").asInt());
        } else if (value.isNumber()) {
            double d = value.asDouble();
            if (schema.has("minimum") && d < schema.path("minimum").asDouble()) errors.add(path + " 小于 minimum " + schema.path("minimum").asDouble());
            if (schema.has("maximum") && d > schema.path("maximum").asDouble()) errors.add(path + " 大于 maximum " + schema.path("maximum").asDouble());
        }
        JsonNode enums = schema.path("enum");
        if (enums.isArray()) {
            boolean found = false;
            for (JsonNode e : enums) if (e.equals(value)) { found = true; break; }
            if (!found) errors.add(path + " 不在 enum 允许值内");
        }
    }

    private boolean typeMatches(String type, JsonNode v) {
        if (v == null || v.isNull()) return "null".equals(type);
        return switch (type) {
            case "object" -> v.isObject();
            case "array" -> v.isArray();
            case "string" -> v.isTextual();
            case "number" -> v.isNumber();
            case "integer" -> v.isInt() || v.isLong();
            case "boolean" -> v.isBoolean();
            case "null" -> v.isNull();
            default -> true;
        };
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
