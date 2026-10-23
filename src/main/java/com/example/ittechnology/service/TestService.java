package com.example.ittechnology.service;

import com.example.ittechnology.service.AiProxyService.FetchResult;
import com.example.ittechnology.util.ImageMime;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

    // ================= 性能基准测试 =================

    /** 单模型性能结果 */
    private record PerfResult(String model, boolean ok, String error,
                              long ttftMs, int tokens, long totalMs, double tps,
                              long firstByteMs, String text) {}

    /**
     * 性能基准：同一提示词流式发往多个模型，统计：
     *   - TTFT    首 token 到达耗时（毫秒）
     *   - tokens  生成 token 数（chunk 累计）
     *   - TPS     token / 秒 = tokens / ((totalMs - ttftMs) / 1000)
     *   - totalMs 总耗时
     *   - firstByteMs 首字节到达耗时（更保守的“首响应”指标）
     *
     * body: {prompt, models:[...], max_tokens?, temperature?}
     */
    public ObjectNode runPerf(String site, String auth, JsonNode req) throws Exception {
        String prompt = req.path("prompt").asText("").trim();
        int maxTok = Math.max(64, Math.min(req.path("max_tokens").asInt(800), 8192));
        double temp = req.path("temperature").asDouble(0.7);
        List<String> models = new ArrayList<>();
        for (JsonNode m : req.path("models")) {
            String s = m.asText("").trim();
            if (!s.isEmpty()) models.add(s);
        }
        if (prompt.isEmpty()) throw new IllegalStateException("请输入测试提示词");
        if (models.isEmpty()) throw new IllegalStateException("请至少选择一个模型");

        ExecutorService pool = Executors.newFixedThreadPool(Math.min(models.size(), 6));
        try {
            List<Callable<PerfResult>> tasks = new ArrayList<>();
            for (String model : models) {
                tasks.add(() -> perfOnce(site, auth, model, prompt, maxTok, temp, req));
            }
            List<Future<PerfResult>> futures = pool.invokeAll(tasks);

            List<PerfResult> results = new ArrayList<>();
            for (Future<PerfResult> f : futures) results.add(f.get());

            // 排序：耗时短的在前
            results.sort((a, b) -> Long.compare(a.totalMs(), b.totalMs()));

            ObjectNode out = mapper.createObjectNode();
            out.put("prompt", prompt);
            ArrayNode arr = mapper.createArrayNode();
            for (PerfResult r : results) {
                ObjectNode o = mapper.createObjectNode();
                o.put("model", r.model());
                o.put("ok", r.ok());
                o.put("error", r.error() == null ? "" : r.error());
                o.put("ttft_ms", r.ttftMs());
                o.put("first_byte_ms", r.firstByteMs());
                o.put("tokens", r.tokens());
                o.put("total_ms", r.totalMs());
                o.put("tps", Math.round(r.tps() * 10.0) / 10.0);
                o.put("text", r.text() == null ? "" : r.text());
                arr.add(o);
            }
            out.set("results", arr);
            pool.shutdown();
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 单模型流式调用 + 统计。
     * 解析 OpenAI chat/completions 与 Anthropic messages 两种 SSE 格式，
     * 以及 Gemini generateContent（非流式回退为一次性统计）。
     */
    private PerfResult perfOnce(String site, String auth, String model, String prompt,
                                int maxTok, double temp, JsonNode params) {
        String lm = model.toLowerCase();
        long t0 = System.currentTimeMillis();
        try {
            if (lm.contains("gemini")) {
                // Gemini：generateContent 不支持 count 型 SSE（减少复杂路径），
                // 用流式 generateContent 解析 streamGenerateContent 也行，但中继兼容性
                // 起见这里走标准 generateContent？——不，为了 TTFT 用 stream=1 形式。
                // 中继站 gemini 流式路径：/v1beta/models/{m}:streamGenerateContent?alt=sse
                // 但兼容性未知，稳妥起见走非流式 generateContent。
                Container<String> text = new Container<>("");
                FetchResult r = proxy.postJson(site, "/v1beta/models/" + model + ":generateContent", auth,
                        buildGeminiBody(prompt, maxTok, temp).toString(), TIMEOUT);
                long total = System.currentTimeMillis() - t0;
                if (r.status() >= 400) return new PerfResult(model, false, "上游 " + r.status() + ": " + truncate(r.body(), 200), 0, 0, total, 0, 0, "");
                JsonNode j = mapper.readTree(r.body());
                StringBuilder sb = new StringBuilder();
                for (JsonNode part : j.path("candidates").path(0).path("content").path("parts")) {
                    String s = part.path("text").asText("");
                    if (!s.isEmpty()) { if (sb.length() > 0) sb.append("\n"); sb.append(s); }
                }
                int tokens = (int) Math.max(1, sb.length() / 4.0);
                double tps = total > 0 ? tokens * 1000.0 / total : 0;
                return new PerfResult(model, true, "", 0, tokens, total, tps, 0, sb.toString());
            }

            // Anthropic / OpenAI：统一走各自原生路径
            boolean anthropic = lm.contains("claude");
            String path = anthropic ? "/v1/messages" : "/v1/chat/completions";
            ObjectNode body = mapper.createObjectNode();
            body.put("model", model);
            body.put("max_tokens", maxTok);
            body.put("stream", true);
            if (temp >= 0) body.put("temperature", temp);
            ArrayNode messages = mapper.createArrayNode();
            ObjectNode user = mapper.createObjectNode();
            user.put("role", "user");
            user.put("content", prompt);
            messages.add(user);
            body.set("messages", messages);

            AiProxyService.StreamCollectResult r =
                    proxy.postJsonStreamCollect(site, path, auth, body.toString(), TIMEOUT);
            long total = r.totalMs();
            if (r.status() >= 400) {
                return new PerfResult(model, false, "上游 " + r.status() + ": " + truncate(r.body(), 200), 0, 0, total, 0, r.firstByteMs(), "");
            }

            // 解析原始 SSE 行统计 TTFT / tokens
            Measurement m = measureSse(r.body(), anthropic);
            double tps = (m.tokens > 0 && total > m.ttftMs)
                    ? m.tokens * 1000.0 / (double) (total - m.ttftMs)
                    : 0;
            return new PerfResult(model, true, "", m.ttftMs, m.tokens, total, tps, r.firstByteMs(), m.text);
        } catch (Exception e) {
            long total = System.currentTimeMillis() - t0;
            log.warn("Perf run failed [{}]: {}", model, e.getMessage());
            return new PerfResult(model, false, e.getMessage(), 0, 0, total, 0, 0, "");
        }
    }

    /** 简易容器，绕过 lambda 捕获限制。 */
    private static final class Container<T> { T value; Container(T v) { this.value = v; } }

    // ================= 模型能力体检 =================

    /** 单项任务定义：key、标题、提示词、校验函数（输入模型输出 → 是否通过）。 */
    private record CapTask(String key, String label, String prompt, java.util.function.Function<String, Boolean> check) {}

    /** 单模型 × 单任务结果 */
    private record CapItem(String model, String key, boolean ok, long ms, String text, String err) {}

    /** 标准能力任务集（4 项：数学推理 / 代码生成 / 翻译 / JSON 结构化）。 */
    private List<CapTask> capTasks() {
        List<CapTask> tasks = new ArrayList<>();
        tasks.add(new CapTask("math", "数学推理", "计算：一箱苹果 36 个，分给 4 个人，每人得到几个？只回答数字。",
                s -> s != null && s.matches("(?s).*\\d.*")));
        tasks.add(new CapTask("code", "代码生成", "用 Python 写一个判断整数是否为质数的函数 is_prime(n)，只输出代码。",
                s -> s != null && (s.contains("def ") || s.contains("import "))));
        tasks.add(new CapTask("translate", "翻译", "把这句话翻译成中文：The quick brown fox jumps over the lazy dog.",
                s -> s != null && s.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN)));
        tasks.add(new CapTask("json", "JSON 结构化", "输出一个 JSON，包含 name 和 age 两个字段，例如 {\"name\":\"张三\",\"age\":25}。只输出 JSON，不要多余文字。",
                s -> {
                    if (s == null || s.isEmpty()) return false;
                    String t = s.trim().replaceAll("^```(json)?\\s*|\\s*```$", "");
                    try {
                        com.fasterxml.jackson.databind.JsonNode j = mapper.readTree(t);
                        return j.isObject() && j.has("name") && j.has("age");
                    } catch (Exception e) { return false; }
                }));
        return tasks;
    }

    /**
     * 模型能力体检：对一组模型并发跑标准任务集，输出能力对比表。
     * body: {models:[...], max_tokens?, temperature?}
     * 返回：{tasks:[{key,label}], results:[{model, rows:[{key,ok,ms,text,err}]}]}
     */
    public ObjectNode runCapability(String site, String auth, JsonNode req) throws Exception {
        int maxTok = Math.max(64, Math.min(req.path("max_tokens").asInt(1024), 8192));
        double temp = req.path("temperature").asDouble(0.2);
        List<String> models = new ArrayList<>();
        for (JsonNode m : req.path("models")) {
            String s = m.asText("").trim();
            if (!s.isEmpty()) models.add(s);
        }
        if (models.isEmpty()) throw new IllegalStateException("请至少选择一个模型");
        List<CapTask> tasks = capTasks();

        ExecutorService pool = Executors.newFixedThreadPool(Math.min(models.size(), 6));
        try {
            List<Callable<List<CapItem>>> jobs = new ArrayList<>();
            for (String model : models) {
                jobs.add(() -> {
                    List<CapItem> rows = new ArrayList<>();
                    for (CapTask t : tasks) {
                        long t0 = System.currentTimeMillis();
                        try {
                            String text = chatOnce(site, auth, model, t.prompt(), maxTok, null);
                            long ms = System.currentTimeMillis() - t0;
                            boolean ok = t.check().apply(text);
                            rows.add(new CapItem(model, t.key(), ok, ms, truncate(text == null ? "" : text, 200), ""));
                        } catch (Exception e) {
                            long ms = System.currentTimeMillis() - t0;
                            rows.add(new CapItem(model, t.key(), false, ms, "", truncate(e.getMessage(), 200)));
                        }
                    }
                    return rows;
                });
            }
            List<Future<List<CapItem>>> futures = pool.invokeAll(jobs);

            ObjectNode out = mapper.createObjectNode();
            ArrayNode taskArr = mapper.createArrayNode();
            for (CapTask t : tasks) {
                ObjectNode tn = mapper.createObjectNode();
                tn.put("key", t.key());
                tn.put("label", t.label());
                taskArr.add(tn);
            }
            out.set("tasks", taskArr);

            ArrayNode resArr = mapper.createArrayNode();
            for (Future<List<CapItem>> f : futures) {
                List<CapItem> rows = f.get();
                if (rows.isEmpty()) continue;
                ObjectNode mn = mapper.createObjectNode();
                mn.put("model", rows.get(0).model());
                ArrayNode rowArr = mapper.createArrayNode();
                for (CapItem r : rows) {
                    ObjectNode rn = mapper.createObjectNode();
                    rn.put("key", r.key());
                    rn.put("ok", r.ok());
                    rn.put("ms", r.ms());
                    rn.put("text", r.text());
                    rn.put("err", r.err());
                    rowArr.add(rn);
                }
                mn.set("rows", rowArr);
                resArr.add(mn);
            }
            out.set("results", resArr);
            pool.shutdown();
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    /** 组装 Gemini 请求体（与 chatOnce 一致）。 */
    private ObjectNode buildGeminiBody(String prompt, int maxTok, double temp) {
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
        if (temp >= 0) gc.put("temperature", temp);
        body.set("generationConfig", gc);
        return body;
    }

    /** SSE 解析统计：返回 TTFT、token 数、拼接文本。 */
    private Measurement measureSse(String raw, boolean anthropic) {
        long ttft = 0;
        int tokens = 0;
        StringBuilder text = new StringBuilder();
        long firstTokenAt = -1;
        long prevTokenAt = System.currentTimeMillis();
        long base = System.currentTimeMillis();   // 近似：解析从发送后并行开始
        // 更精确做法：用 firstByte 做基准，但 TTFT 与 chunk 到达时间相关；
        // 这里以解析起点为基准，chunk 间间隔即生成节奏。
        String[] lines = raw.split("\\r?\\n");
        for (String line : lines) {
            if (!line.startsWith("data:")) continue;
            String data = line.substring(5).trim();
            if (data.isEmpty() || data.equals("[DONE]")) continue;
            try {
                JsonNode ev = mapper.readTree(data);
                String delta = null;
                if (anthropic) {
                    // Anthropic SSE: {type: content_block_delta, delta: {type: text_delta, text}}
                    if (ev.path("type").asText("").equals("content_block_delta")) {
                        delta = ev.path("delta").path("text").asText("");
                        if (delta.isEmpty()) delta = ev.path("delta").path("thinking").asText("");
                    }
                } else {
                    // OpenAI SSE: {choices:[{delta:{content}}]} 或 {choices:[{delta:{reasoning_content}}]}
                    JsonNode ch = ev.path("choices").path(0);
                    delta = ch.path("delta").path("content").asText("");
                    if (delta.isEmpty()) delta = ch.path("delta").path("reasoning_content").asText("");
                }
                if (delta == null || delta.isEmpty()) continue;
                if (firstTokenAt < 0) {
                    firstTokenAt = System.currentTimeMillis() - base;
                    ttft = System.currentTimeMillis() - base;
                }
                tokens += estTokens(delta);
                text.append(delta);
                prevTokenAt = System.currentTimeMillis();
            } catch (Exception ignored) {}
        }
        return new Measurement(ttft, tokens, text.toString().trim());
    }

    private record Measurement(long ttftMs, int tokens, String text) {}

    /** 粗估 token 数：CJK 每字符约 0.95，ASCII 每 4 字符约 1。 */
    private int estTokens(String s) {
        int ascii = 0, cjk = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 128) ascii++; else cjk++;
        }
        return Math.max(1, ascii / 4 + (int) Math.ceil(cjk * 0.95));
    }

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
        // 新版 OpenAI 模型（gpt-5 家族 / o 系列推理模型）不再支持 presence_penalty / frequency_penalty / seed；
        // o 系列连 top_p 也不支持，只支持 temperature（部分仅支持 =1）。
        boolean openAiNext = lm.contains("gpt-5") || lm.contains("gpt-5.6") || lm.contains("-luna")
                || lm.matches("o[0-9].*");
        boolean oReasoner = lm.matches("o[0-9].*");
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
        // 参数（OpenAI 全支持；Anthropic 只取 temperature，新版 Claude 已弃用 top_p/top_k；
        // gpt-5/o 系列不支持 penalty，o 系列不支持 top_p）
        if (params != null) {
            if (params.has("temperature")) body.put("temperature", params.get("temperature").asDouble());
            if (!anthropic && !oReasoner && params.has("top_p") && params.get("top_p").asDouble() > 0) body.put("top_p", params.get("top_p").asDouble());
            if (!anthropic && !openAiNext) {
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
     * 上下文缓存命中率测试（Anthropic / OpenAI / Gemini 三种协议 × 隐式/显式两种方式）。
     *
     * body: {model, protocol, systemDoc, repeat?, questions?, max_tokens?, delay?, ttl?}
     *   protocol: messages[-implicit|-explicit] | chat[-implicit|-explicit] | gemini[-implicit|-explicit]
     *     隐式（implicit）：靠请求前缀（开头 token）完全一致，由上游自动命中缓存；
     *     显式（explicit）：Anthropic=cache_control 断点；Gemini=创建 cachedContents 再引用；
     *     OpenAI 无官方显式缓存 API，chat-explicit 与 chat-implicit 请求体一致（观察中继站行为）。
     *
     * 命中率 = 命中 tokens / (本次未缓存输入 + 命中 tokens)。
     */
    public ObjectNode runCacheTest(String site, String auth, JsonNode req) throws Exception {
        String model = req.path("model").asText("");
        String protocol = req.path("protocol").asText("messages-explicit");
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

        // ---- 协议解析：fmt(messages|chat|gemini) × mode(implicit|explicit) ----
        String rawProto = protocol.toLowerCase();
        String fmt;              // messages | chat | gemini
        boolean explicit;        // true=显式 false=隐式
        switch (rawProto) {
            case "messages":
            case "messages-explicit": fmt = "messages"; explicit = true; break;
            case "messages-implicit": fmt = "messages"; explicit = false; break;
            case "chat":
            case "chat-implicit": fmt = "chat"; explicit = false; break;
            case "chat-explicit": fmt = "chat"; explicit = true; break;
            case "gemini":
            case "gemini-implicit": fmt = "gemini"; explicit = false; break;
            case "gemini-explicit": fmt = "gemini"; explicit = true; break;
            default: fmt = "messages"; explicit = true;
        }
        String protoLabel = fmt + (explicit ? "-explicit" : "-implicit");
        boolean chat = "chat".equals(fmt);
        boolean gemini = "gemini".equals(fmt);

        // system 前缀：文档×repeat + 系统指令
        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < repeat; i++) {
            if (i > 0) prefix.append("\n\n");
            prefix.append(systemDoc);
        }
        prefix.append("\n\n你是一个专业的AI编程助手，请始终使用中文回答。");
        String systemText = prefix.toString();

        // 显式方式：Anthropic 用 cache_control 断点（system 数组），Gemini 创建 cachedContents
        ObjectNode cc = mapper.createObjectNode();
        cc.put("type", "ephemeral");
        if (!ttl.isEmpty()) cc.put("ttl", ttl);
        ObjectNode sysBlock = mapper.createObjectNode();
        sysBlock.put("type", "text");
        sysBlock.put("text", systemText);
        sysBlock.set("cache_control", cc);
        ArrayNode systemArr = mapper.createArrayNode();
        systemArr.add(sysBlock);

        String path = gemini
                ? "/v1beta/models/" + model + ":generateContent"
                : (chat ? "/v1/chat/completions" : "/v1/messages");

        // Gemini 显式：先创建 cachedContents 缓存对象
        String cacheName = null;
        if (gemini && explicit) {
            ObjectNode ccBody = mapper.createObjectNode();
            ccBody.put("model", model);
            ArrayNode ccContents = mapper.createArrayNode();
            ObjectNode ccMsg = mapper.createObjectNode();
            ccMsg.put("role", "user");
            ArrayNode ccParts = mapper.createArrayNode();
            ObjectNode ccT = mapper.createObjectNode();
            ccT.put("text", systemText);
            ccParts.add(ccT);
            ccMsg.set("parts", ccParts);
            ccContents.add(ccMsg);
            ccBody.set("contents", ccContents);
            ccBody.put("ttl", ttl.isEmpty() ? "3600s" : ttl);

            FetchResult cr = proxy.postJson(site, "/v1beta/cachedContents", auth, ccBody.toString(), Duration.ofSeconds(60));
            if (cr.status() >= 400) {
                throw new IllegalStateException("创建 cachedContents 失败（上游 " + cr.status()
                        + "，中继站可能不支持显式缓存接口）: " + truncate(cr.body(), 300));
            }
            JsonNode ccj = mapper.readTree(cr.body());
            cacheName = ccj.path("name").asText("");
            if (cacheName.isEmpty()) {
                throw new IllegalStateException("创建缓存响应缺少 name: " + truncate(cr.body(), 300));
            }
        }

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
                ArrayNode messages = mapper.createArrayNode();
                if (gemini) {
                    // generateContent 不接收 model/max_tokens/stream 字段，全部移除。
                    body.remove("model");
                    body.remove("max_tokens");
                    body.remove("stream");
                    ArrayNode contents = mapper.createArrayNode();
                    if (explicit && cacheName != null) {
                        // 显式：contents 只放变化的问题，前缀由 cachedContent 引用提供
                        ObjectNode q = mapper.createObjectNode();
                        q.put("role", "user");
                        ArrayNode qParts = mapper.createArrayNode();
                        ObjectNode qT = mapper.createObjectNode();
                        qT.put("text", questions.get(i));
                        qParts.add(qT);
                        q.set("parts", qParts);
                        contents.add(q);
                        body.put("cachedContent", cacheName);
                    } else {
                        // 隐式：固定前缀作为第一条 user 消息 + 变化的问题作为第二条
                        ObjectNode pre = mapper.createObjectNode();
                        pre.put("role", "user");
                        ArrayNode preParts = mapper.createArrayNode();
                        ObjectNode preT = mapper.createObjectNode();
                        preT.put("text", systemText);
                        preParts.add(preT);
                        pre.set("parts", preParts);
                        contents.add(pre);
                        ObjectNode q = mapper.createObjectNode();
                        q.put("role", "user");
                        ArrayNode qParts = mapper.createArrayNode();
                        ObjectNode qT = mapper.createObjectNode();
                        qT.put("text", questions.get(i));
                        qParts.add(qT);
                        q.set("parts", qParts);
                        contents.add(q);
                    }
                    body.set("contents", contents);
                    ObjectNode gc = mapper.createObjectNode();
                    gc.put("maxOutputTokens", maxTok);
                    body.set("generationConfig", gc);
                } else if (chat) {
                    // OpenAI：system 角色承载固定前缀（自动前缀缓存）；问题放 messages 末尾
                    // 官方无显式缓存 API，显式/隐式请求体一致（仅标注用途）
                    ObjectNode sys = mapper.createObjectNode();
                    sys.put("role", "system");
                    sys.put("content", systemText);
                    messages.add(sys);
                    ObjectNode user = mapper.createObjectNode();
                    user.put("role", "user");
                    user.put("content", questions.get(i));
                    messages.add(user);
                    body.set("messages", messages);
                } else {
                    // Anthropic：显式=system 数组带 cache_control 断点；隐式=system 纯文本（观察中继站自动前缀缓存）
                    if (explicit) {
                        body.set("system", systemArr);
                    } else {
                        body.put("system", systemText);
                    }
                    ObjectNode user = mapper.createObjectNode();
                    user.put("role", "user");
                    user.put("content", questions.get(i));
                    messages.add(user);
                    body.set("messages", messages);
                }

                FetchResult r = proxy.postJson(site, path, auth, body.toString(), TIMEOUT);
                if (r.status() >= 400) throw new IllegalStateException("上游 " + r.status() + ": " + truncate(r.body(), 300));
                JsonNode j = mapper.readTree(r.body());
                if (j.has("error") || "error".equals(j.path("type").asText())) {
                    throw new IllegalStateException(j.path("error").toString());
                }
                JsonNode u = j.path("usage");
                int input, read, write, output, total;
                double hitRate;
                if (gemini) {
                    // Gemini：usageMetadata.promptTokenCount=本次未命中输入；cachedContentTokenCount=命中；
                    // candidatesTokenCount=输出。首次（未命中）整段前缀视为写入（仅用于"写入请求"标记），
                    // 总输入 = 未缓存输入 + 命中，避免与写入重复计数。
                    JsonNode um = j.path("usageMetadata");
                    if (um.isMissingNode() || !um.isObject()) um = u;
                    int prompt = um.path("promptTokenCount").asInt(u.path("prompt_tokens").asInt(0));
                    int cached = um.path("cachedContentTokenCount").asInt(0);
                    if (cached == 0) cached = u.path("prompt_tokens_details").path("cached_tokens").asInt(0);
                    input = prompt;
                    read = cached;
                    write = (cached == 0) ? prompt : 0;
                    output = um.path("candidatesTokenCount").asInt(u.path("completion_tokens").asInt(0));
                    total = input + read;
                    hitRate = total > 0 ? (double) read / total : 0.0;
                } else if (chat) {
                    // OpenAI：prompt_tokens 含全部；cached_tokens=命中；未命中部分视为写入
                    int prompt = u.path("prompt_tokens").asInt(0);
                    int cached = u.path("prompt_tokens_details").path("cached_tokens").asInt(0);
                    if (cached == 0) cached = u.path("prompt_cache_hit_tokens").asInt(0);
                    int miss = u.path("prompt_cache_miss_tokens").asInt(0);
                    input = prompt;
                    read = cached;
                    write = miss > 0 ? miss : Math.max(0, prompt - cached);
                    output = u.path("completion_tokens").asInt(0);
                    total = Math.max(1, prompt);
                    hitRate = (double) read / total;
                } else {
                    input = u.path("input_tokens").asInt(0);
                    read = u.path("cache_read_input_tokens").asInt(0);
                    write = u.path("cache_creation_input_tokens").asInt(0);
                    output = u.path("output_tokens").asInt(0);
                    total = input + read + write;
                    hitRate = total > 0 ? (double) read / total : 0.0;
                }

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
        out.put("protocol", protoLabel);
        if (cacheName != null) out.put("cache_name", cacheName);
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

    /**
     * Gemini 显式上下文缓存测试（cachedContents API）——兼容入口。
     *
     * body: {model, systemDoc, repeat?, questions?, max_tokens?, ttl?}
     *
     * 已并入统一的 runCacheTest（protocol=gemini-explicit 分支），此处仅做转发，
     * 保持 /api/test/cache-explicit 端点可用。
     */
    public ObjectNode runExplicitCacheTest(String site, String auth, JsonNode req) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        if (req != null && req.isObject()) {
            req.fields().forEachRemaining(e -> body.set(e.getKey(), e.getValue()));
        }
        body.put("protocol", "gemini-explicit");
        return runCacheTest(site, auth, body);
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

    // ================= 原始请求测试（Apifox 式） =================

    /**
     * 原始请求测试：用户直接粘贴 JSON 请求体，自由指定站点/路径/方法/认证/超时，
     * 后端原样转发上游并返回原始响应。用于快速验证某个请求是否可用。
     *
     * body: {
     *   site?,        // cn | intl（缺省 cn）
     *   method?,      // POST | GET（缺省 POST）
     *   path,         // 必填，如 /v1/messages、/v1/chat/completions、/v1/models
     *   auth?,        // 可选；缺省使用请求头 Authorization
     *   body?,        // 请求体原始 JSON 字符串（POST 用；为空发 {}）
     *   timeout?      // 秒，缺省 120，上限 600
     * }
     * 返回：{status, ok, ms, headers?, body(原始响应，截断到 100KB), truncated}
     */
    public ObjectNode runRaw(String site, String auth, JsonNode req) throws Exception {
        String s = req.path("site").asText(site);
        String method = req.path("method").asText("POST").toUpperCase();
        String path = req.path("path").asText("").trim();
        if (!path.startsWith("/")) path = "/" + path;
        if (path.isEmpty() || path.equals("/")) throw new IllegalStateException("请填写上游路径，如 /v1/messages");
        String useAuth = req.path("auth").asText("").trim();
        if (useAuth.isEmpty()) useAuth = auth;
        if (useAuth.isEmpty()) throw new IllegalStateException("缺少认证：请填 Authorization 或使用当前 Token");
        // 规范化：body 里的 auth 可能是纯 Token（无 Bearer 前缀），
        // 直接作 Authorization 头会导致上游 Invalid token，这里自动补上认证 scheme。
        useAuth = normalizeAuth(useAuth);
        String reqBody = req.path("body").asText("").trim();
        int timeoutSec = Math.max(5, Math.min(req.path("timeout").asInt(120), 600));

        ObjectNode out = mapper.createObjectNode();
        out.put("site", s);
        out.put("method", method);
        out.put("path", path);

        long t0 = System.currentTimeMillis();
        if ("GET".equalsIgnoreCase(method)) {
            AtomicInteger st = new AtomicInteger(0);
            String resp = proxy.proxyGet(s, path, useAuth, Duration.ofSeconds(timeoutSec), st);
            out.put("status", st.get());
            out.put("ok", st.get() >= 200 && st.get() < 400);
            out.put("ms", System.currentTimeMillis() - t0);
            putBody(out, resp);
            return out;
        }

        // POST：原样转发请求体
        if (reqBody.isEmpty()) reqBody = "{}";
        // 校验请求体必须是合法 JSON（便于提前发现格式错误）
        try {
            mapper.readTree(reqBody);
        } catch (Exception e) {
            out.put("status", 400);
            out.put("ok", false);
            out.put("ms", System.currentTimeMillis() - t0);
            out.put("error", "请求体不是合法 JSON: " + e.getMessage());
            return out;
        }
        // 自定义请求头：req.headers 可以是 JSON 对象 {name:value} 或 JSON 字符串（如 {"anthropic-beta":"..."}）
        Map<String, String> extraHeaders = new java.util.HashMap<>();
        JsonNode hd = req.get("headers");
        if (hd != null) {
            if (hd.isObject()) {
                hd.fields().forEachRemaining(e -> {
                    String v = e.getValue().isValueNode() ? e.getValue().asText("") : "";
                    if (!v.isEmpty()) extraHeaders.put(e.getKey(), v);
                });
            } else if (hd.isTextual()) {
                String txt = hd.asText("").trim();
                if (!txt.isEmpty()) {
                    try {
                        JsonNode jo = mapper.readTree(txt);
                        if (jo.isObject()) {
                            jo.fields().forEachRemaining(e -> {
                                String v = e.getValue().isValueNode() ? e.getValue().asText("") : "";
                                if (!v.isEmpty()) extraHeaders.put(e.getKey(), v);
                            });
                        }
                    } catch (Exception ignore) {
                        // 不是 JSON 对象，尝试按 "Key: Value" 每行解析
                        for (String line : txt.split("\\r?\\n")) {
                            String l = line.trim();
                            if (l.isEmpty()) continue;
                            int colon = l.indexOf(':');
                            if (colon > 0) {
                                String k = l.substring(0, colon).trim();
                                String v = l.substring(colon + 1).trim();
                                if (!k.isEmpty() && !v.isEmpty()) extraHeaders.put(k, v);
                            }
                        }
                    }
                }
            }
        }
        out.put("headers", extraHeaders.isEmpty() ? "" : extraHeaders.toString());
        FetchResult r = proxy.postJsonWithHeaders(s, path, useAuth, extraHeaders.isEmpty() ? null : extraHeaders,
                reqBody, Duration.ofSeconds(timeoutSec));
        out.put("status", r.status());
        out.put("ok", r.status() >= 200 && r.status() < 400);
        out.put("ms", System.currentTimeMillis() - t0);
        putBody(out, r.body());
        return out;
    }

    /** 原始响应体塞入结果，超过 100KB 截断。 */
    private void putBody(ObjectNode out, String body) {
        final int MAX = 100 * 1024;
        if (body == null) { out.put("body", ""); out.put("truncated", false); return; }
        if (body.length() > MAX) {
            out.put("body", body.substring(0, MAX));
            out.put("truncated", true);
        } else {
            out.put("body", body);
            out.put("truncated", false);
        }
    }

    /** 规范化 Authorization：纯 Token 自动补 "Bearer "，已有 scheme 则原样返回。 */
    private String normalizeAuth(String auth) {
        String a = auth == null ? "" : auth.trim();
        if (a.isEmpty()) return a;
        // 已带认证 scheme（Bearer/Basic/Token/Digest 等）则不动
        if (a.matches("(?i)^(Bearer|Basic|Token|Digest)\\s+.*")) return a;
        return "Bearer " + a;
    }

    // ================= 图像蒙版（mask）测试 =================

    /**
     * 图像蒙版测试：验证上游 /v1/images/edits 的 mask（蒙版）编辑能力。
     *
     * 两种模式（按请求体自动识别）：
     *   1) edits  默认：{model, image_b64, mask_b64, prompt, mime?, mask_mime?,
     *             size?, quality?, output_format?, n?}
     *             由后端组装 multipart/form-data（image[]=原图、mask=蒙版，与上游 curl 模板一致）
     *             转发到 /v1/images/edits，返回编辑结果图 base64。
     *   2) direct 直接直发：请求体本身就是上游请求体（Anthropic /v1/messages 格式，
     *             image 块内带 mask 字段），原样转发，适合从 Apifox 复制的完整 JSON。
     *
     * 返回：{ok, status, ms, mode, path, model, mask_found, image_blocks, mask_blocks,
     *        text, images:[{media_type,data}], raw, truncated}
     */
    public ObjectNode runImageMask(String site, String auth, JsonNode req, String pathHeader, int timeoutSec) throws Exception {
        int timeout = Math.max(30, Math.min(timeoutSec <= 0 ? 300 : timeoutSec, 600));

        String mode;
        String path;
        if (req.has("messages") && req.path("messages").isArray()) {
            // direct：请求体本身就是上游请求体（Anthropic /v1/messages，image 块带 mask）
            path = "/v1/messages";
            if (pathHeader != null && !pathHeader.isBlank()) {
                path = pathHeader.trim().startsWith("/") ? pathHeader.trim() : "/" + pathHeader.trim();
            }
            return runImageMaskDirect(site, auth, req, path, timeout);
        }

        // edits：便捷字段组装 multipart /v1/images/edits
        mode = "edits";
        path = "/v1/images/edits";
        if (pathHeader != null && !pathHeader.isBlank()) {
            path = pathHeader.trim().startsWith("/") ? pathHeader.trim() : "/" + pathHeader.trim();
        }

        String model = req.path("model").asText("").trim();
        String imageB64 = req.path("image_b64").asText("").trim();
        String maskB64 = req.path("mask_b64").asText("").trim();
        String prompt = req.path("prompt").asText("").trim();
        String mime = req.path("mime").asText("image/png");
        String maskMime = req.path("mask_mime").asText("image/png");
        String size = req.path("size").asText("1024x1024").trim();
        String quality = req.path("quality").asText("high").trim();
        String outputFormat = req.path("output_format").asText("png").trim();
        int n = Math.max(1, Math.min(req.path("n").asInt(1), 4));

        if (model.isEmpty()) throw new IllegalStateException("请选择模型 model");
        if (imageB64.isEmpty()) throw new IllegalStateException("请提供原图 image_b64");
        if (maskB64.isEmpty()) throw new IllegalStateException("请提供蒙版 mask_b64");
        if (prompt.isEmpty()) throw new IllegalStateException("请输入编辑提示词 prompt");
        // 兼容 data URL 前缀
        if (imageB64.startsWith("data:")) { int c = imageB64.indexOf(','); if (c > 0) imageB64 = imageB64.substring(c + 1); }
        if (maskB64.startsWith("data:")) { int c = maskB64.indexOf(','); if (c > 0) maskB64 = maskB64.substring(c + 1); }
        // 按内容魔数纠正 MIME（声明 image/jpeg 实际 webp 时上游会 400）
        mime = ImageMime.resolve(mime, imageB64);
        maskMime = ImageMime.resolve(maskMime, maskB64);

        // 组装 multipart/form-data（字段名与上游文档/curl 模板一致：image[] 为文件字段）
        String boundary = "----ClrMaskBoundary" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        field(bos, boundary, "model", model);
        field(bos, boundary, "prompt", prompt);
        field(bos, boundary, "n", String.valueOf(n));
        field(bos, boundary, "size", size);
        field(bos, boundary, "quality", quality);
        field(bos, boundary, "output_format", outputFormat);
        filePart(bos, boundary, "image[]", imageB64, mime);
        filePart(bos, boundary, "mask", maskB64, maskMime);
        bos.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        byte[] multipartBody = bos.toByteArray();
        String contentType = "multipart/form-data; boundary=" + boundary;

        ObjectNode out = mapper.createObjectNode();
        out.put("mode", mode);
        out.put("path", path);
        out.put("model", model);
        out.put("mask_found", true);
        out.put("image_blocks", 1);
        out.put("mask_blocks", 1);
        out.put("request_summary", "image[](" + mime + "," + imageB64.length() + " chars) + mask(" + maskMime + "," + maskB64.length() + " chars)");

        long t0 = System.currentTimeMillis();
        FetchResult r = proxy.postRawBytes(site, path, auth, multipartBody, contentType, Duration.ofSeconds(timeout));
        long ms = System.currentTimeMillis() - t0;
        out.put("status", r.status());
        out.put("ok", r.status() >= 200 && r.status() < 400);
        out.put("ms", ms);

        if (r.status() >= 400) {
            out.put("error", "上游 " + r.status() + ": " + truncate(r.body(), 1500));
            putRaw(out, r.body());
            return out;
        }

        // 解析 OpenAI /v1/images/edits 响应：data[].b64_json
        try {
            JsonNode j = mapper.readTree(r.body());
            ArrayNode images = mapper.createArrayNode();
            for (JsonNode d : j.path("data")) {
                String b64 = d.path("b64_json").asText("");
                if (b64.isEmpty()) continue;
                ObjectNode img = mapper.createObjectNode();
                String realMime = ImageMime.detect(b64);
                img.put("media_type", realMime == null ? "image/png" : realMime);
                img.put("data", b64);
                images.add(img);
            }
            out.set("images", images);
            out.put("text", j.path("data").path(0).path("revised_prompt").asText(""));
        } catch (Exception e) {
            out.put("text", "");
        }

        putRaw(out, r.body());
        return out;
    }

    /** direct 模式：Anthropic /v1/messages 请求体原样转发，解析 content 里的 text / image 块。 */
    private ObjectNode runImageMaskDirect(String site, String auth, JsonNode req, String path, int timeout) throws Exception {
        String upstreamBody = req.toString();

        // 分析请求体里的 image / mask 块结构（测试工具回显用）
        int imageBlocks = 0, maskBlocks = 0;
        for (JsonNode m : req.path("messages")) {
            JsonNode content = m.path("content");
            if (!content.isArray()) continue;
            for (JsonNode b : content) {
                if ("image".equals(b.path("type").asText(""))) {
                    imageBlocks++;
                    if (b.has("mask") && b.get("mask").isObject()) maskBlocks++;
                }
            }
        }
        boolean maskFound = maskBlocks > 0;

        ObjectNode out = mapper.createObjectNode();
        out.put("mode", "direct");
        out.put("path", path);
        out.put("model", req.path("model").asText(""));
        out.put("mask_found", maskFound);
        out.put("image_blocks", imageBlocks);
        out.put("mask_blocks", maskBlocks);
        out.put("request_body", truncate(upstreamBody, 4096));   // 回显实际发送的请求体（截断）

        long t0 = System.currentTimeMillis();
        FetchResult r = proxy.postJson(site, path, auth, upstreamBody, Duration.ofSeconds(timeout));
        long ms = System.currentTimeMillis() - t0;
        out.put("status", r.status());
        out.put("ok", r.status() >= 200 && r.status() < 400);
        out.put("ms", ms);

        if (r.status() >= 400) {
            out.put("error", "上游 " + r.status() + ": " + truncate(r.body(), 1500));
            putRaw(out, r.body());
            return out;
        }

        // 解析响应：提取 text 块与 image 块（编辑结果图 base64 完整返回，便于前端预览）
        try {
            JsonNode j = mapper.readTree(r.body());
            StringBuilder text = new StringBuilder();
            ArrayNode images = mapper.createArrayNode();
            for (JsonNode b : j.path("content")) {
                String type = b.path("type").asText("");
                if ("text".equals(type)) {
                    String s = b.path("text").asText("");
                    if (!s.isEmpty()) { if (text.length() > 0) text.append("\n"); text.append(s); }
                } else if ("image".equals(type)) {
                    ObjectNode img = mapper.createObjectNode();
                    img.put("media_type", b.path("source").path("media_type").asText("image/png"));
                    img.put("data", b.path("source").path("data").asText(""));
                    images.add(img);
                }
            }
            out.put("text", text.toString());
            out.set("images", images);
        } catch (Exception e) {
            out.put("text", "");
        }

        putRaw(out, r.body());
        return out;
    }

    // ================= 视频生成 JSON 直发（火山豆包 /v1/video/generations） =================

    /**
     * 视频生成 JSON 直发：请求体原样转发到 /v1/video/generations（X-Site 选站），
     * 支持两种火山豆包格式：
     *   A) metadata 格式：{model, prompt, metadata:{reference_images, reference_videos, reference_audios, generate_audio, ratio, duration, watermark}}
     *   B) content 格式：{model, content:[{type:"text"|"image_url"|"audio_url"|"video_url", role, ...}], ...}
     * 素材引用：reference_images / content 里的 url 支持公网 URL 或 asset:// + 素材ID，原样透传。
     * 响应解析 task_id / status / progress，供前端轮询 /api/video/poll-v1/{taskId}。
     */
    public ObjectNode runVideoGenerate(String site, String auth, JsonNode req, String pathHeader, int timeoutSec) throws Exception {
        int timeout = Math.max(30, Math.min(timeoutSec <= 0 ? 120 : timeoutSec, 600));
        String path = "/v1/video/generations";
        if (pathHeader != null && !pathHeader.isBlank()) {
            path = pathHeader.trim().startsWith("/") ? pathHeader.trim() : "/" + pathHeader.trim();
        }
        String model = req.path("model").asText("").trim();
        if (model.isEmpty()) throw new IllegalStateException("请选择模型 model");
        String upstreamBody = req.toString();

        ObjectNode out = mapper.createObjectNode();
        out.put("mode", "video");
        out.put("path", path);
        out.put("model", model);
        out.put("request_summary", truncate(upstreamBody, 2048));

        // 素材引用统计：metadata.reference_* 与 content 数组两种格式都统计
        int refImages = 0, refVideos = 0, refAudios = 0, assetRefs = 0, urlRefs = 0;
        JsonNode meta = req.path("metadata");
        if (meta.isObject()) {
            refImages += meta.path("reference_images").size();
            refVideos += meta.path("reference_videos").size();
            refAudios += meta.path("reference_audios").size();
            for (JsonNode u : meta.path("reference_images")) {
                if (u.isTextual() && u.asText().startsWith("asset://")) assetRefs++; else if (u.isTextual()) urlRefs++;
            }
            for (JsonNode u : meta.path("reference_videos")) {
                if (u.isTextual() && u.asText().startsWith("asset://")) assetRefs++; else if (u.isTextual()) urlRefs++;
            }
            for (JsonNode u : meta.path("reference_audios")) {
                if (u.isTextual() && u.asText().startsWith("asset://")) assetRefs++; else if (u.isTextual()) urlRefs++;
            }
        }
        for (JsonNode b : req.path("content")) {
            String type = b.path("type").asText("");
            String role = b.path("role").asText("");
            String url = "";
            if ("image_url".equals(type)) {
                refImages++;
                url = b.path("image_url").path("url").asText("");
            } else if ("video_url".equals(type)) {
                refVideos++;
                url = b.path("video_url").path("url").asText("");
            } else if ("audio_url".equals(type)) {
                refAudios++;
                url = b.path("audio_url").path("url").asText("");
            }
            if (url.startsWith("asset://")) assetRefs++;
            else if (url.startsWith("http")) urlRefs++;
            if (!role.isEmpty()) out.put("last_role", role);
        }
        out.put("ref_images", refImages);
        out.put("ref_videos", refVideos);
        out.put("ref_audios", refAudios);
        out.put("asset_refs", assetRefs);
        out.put("url_refs", urlRefs);

        long t0 = System.currentTimeMillis();
        FetchResult r = proxy.postJson(site, path, auth, upstreamBody, Duration.ofSeconds(timeout));
        long ms = System.currentTimeMillis() - t0;
        out.put("status", r.status());
        out.put("ok", r.status() >= 200 && r.status() < 400);
        out.put("ms", ms);

        if (r.status() >= 400) {
            out.put("error", "上游 " + r.status() + ": " + truncate(r.body(), 1500));
            putRaw(out, r.body());
            return out;
        }

        // 解析响应：提取 task_id / status / progress（cgt-xxx）
        try {
            JsonNode j = mapper.readTree(r.body());
            String taskId = j.path("task_id").asText("");
            if (taskId.isEmpty()) taskId = j.path("id").asText("");
            out.put("task_id", taskId);
            out.put("task_status", j.path("status").asText(""));
            out.put("progress", j.path("progress").asText("0%"));
        } catch (Exception e) {
            out.put("task_id", "");
        }

        putRaw(out, r.body());
        return out;
    }

    // ================= 视频理解（模型视频输入能力测试） =================

    /**
     * 视频理解测试：验证模型（如 kimi-k3）是否支持把视频作为输入。
     *
     * 两种用法：
     *  1) 直接直发：请求体已含 messages 数组 → 原样转发（可直接粘贴官方格式请求体）
     *  2) 便捷构建：请求体给 model + prompt + video_url（或 video_b64）→ 后端组装 OpenAI 内容块：
     *     {"model":"kimi-k3","stream":false,"max_tokens":1024,
     *      "messages":[{"role":"user","content":[
     *         {"type":"video_url","video_url":{"url":"ms://xxxx-xxxx"}},
     *         {"type":"text","text":"总结这个视频"}]}]}
     *
     * video_url 支持任意 scheme：ms:// 素材引用、https:// 公网地址、data:video/mp4;base64,...
     * 响应文本自动兼容 OpenAI(choices[0].message.content) / Anthropic(content[]) / Gemini(candidates[])。
     *
     * 可选请求头：X-Test-Path（默认 /v1/chat/completions）、X-Test-Timeout（默认 120，上限 600）
     */
    public ObjectNode runVideoUnderstand(String site, String auth, JsonNode req, String pathHeader, int timeoutSec) throws Exception {
        int timeout = Math.max(30, Math.min(timeoutSec <= 0 ? 120 : timeoutSec, 600));
        String path = "/v1/chat/completions";
        if (pathHeader != null && !pathHeader.isBlank()) {
            path = pathHeader.trim().startsWith("/") ? pathHeader.trim() : "/" + pathHeader.trim();
        }

        boolean direct = req.path("messages").isArray();
        ObjectNode upstream;
        if (direct) {
            upstream = req.isObject() ? ((ObjectNode) req).deepCopy() : mapper.createObjectNode();
        } else {
            String model = req.path("model").asText("").trim();
            if (model.isEmpty()) throw new IllegalStateException("请选择模型 model（不在列表内的模型 ID 可直接输入）");
            String prompt = req.path("prompt").asText("").trim();
            if (prompt.isEmpty()) prompt = "总结这个视频";
            String url = req.path("video_url").asText("").trim();
            if (url.isEmpty()) {
                String b64 = req.path("video_b64").asText("").trim();
                if (!b64.isEmpty()) {
                    if (b64.startsWith("data:")) {
                        url = b64;
                    } else {
                        String mime = req.path("mime").asText("video/mp4").trim();
                        url = "data:" + mime + ";base64," + b64;
                    }
                }
            }
            if (url.isEmpty()) {
                throw new IllegalStateException("请提供 video_url（ms://素材ID 或 https://公网地址）或 video_b64（本地视频 base64）");
            }

            upstream = mapper.createObjectNode();
            upstream.put("model", model);
            upstream.put("stream", false);
            upstream.put("max_tokens", Math.max(1, Math.min(req.path("max_tokens").asInt(1024), 32768)));
            ArrayNode messages = mapper.createArrayNode();
            ObjectNode user = mapper.createObjectNode();
            user.put("role", "user");
            ArrayNode content = mapper.createArrayNode();
            ObjectNode vb = mapper.createObjectNode();
            vb.put("type", "video_url");
            vb.putObject("video_url").put("url", url);
            content.add(vb);
            ObjectNode tb = mapper.createObjectNode();
            tb.put("type", "text");
            tb.put("text", prompt);
            content.add(tb);
            user.set("content", content);
            messages.add(user);
            upstream.set("messages", messages);
            // 可选参数透传（fps / temperature 等，按上游支持情况生效）
            for (String k : new String[]{"temperature", "top_p", "fps", "video_fps", "detail"}) {
                if (req.has(k) && !req.get(k).isNull()) upstream.set(k, req.get(k));
            }
        }
        // 本模块一次性拿完整结果，强制非流式（避免流式响应无法解析）
        upstream.put("stream", false);

        String upstreamBody = upstream.toString();

        ObjectNode out = mapper.createObjectNode();
        out.put("mode", direct ? "direct" : "build");
        out.put("path", path);
        out.put("model", upstream.path("model").asText(""));
        out.put("request_summary", truncate(upstreamBody, 2048));

        // 内容块诊断：统计 video_url / image_url / text 块数量与引用 scheme（确认素材引用是否真的透传上去）
        int vBlocks = 0, iBlocks = 0, tBlocks = 0, msRefs = 0, httpRefs = 0, dataRefs = 0;
        ArrayNode previews = mapper.createArrayNode();
        for (JsonNode msg : upstream.path("messages")) {
            for (JsonNode b : msg.path("content")) {
                String type = b.path("type").asText("");
                String url;
                if ("video_url".equals(type)) {
                    vBlocks++;
                    url = b.path("video_url").path("url").asText("");
                } else if ("image_url".equals(type)) {
                    iBlocks++;
                    url = b.path("image_url").path("url").asText("");
                } else if ("text".equals(type)) {
                    tBlocks++;
                    continue;
                } else {
                    continue;
                }
                if (url.startsWith("ms://")) msRefs++;
                else if (url.startsWith("data:")) dataRefs++;
                else if (url.startsWith("http")) httpRefs++;
                if (!url.isEmpty()) previews.add(truncate(url, 120));
            }
        }
        out.put("video_blocks", vBlocks);
        out.put("image_blocks", iBlocks);
        out.put("text_blocks", tBlocks);
        out.put("ms_refs", msRefs);
        out.put("http_refs", httpRefs);
        out.put("data_refs", dataRefs);
        out.set("refs_preview", previews);

        long t0 = System.currentTimeMillis();
        FetchResult r = proxy.postJson(site, path, auth, upstreamBody, Duration.ofSeconds(timeout));
        long ms = System.currentTimeMillis() - t0;
        out.put("status", r.status());
        out.put("ok", r.status() >= 200 && r.status() < 400);
        out.put("ms", ms);

        if (r.status() >= 400) {
            out.put("error", "上游 " + r.status() + ": " + truncate(r.body(), 1500));
            out.put("hint", "若上游提示内容类型不支持 / 参数错误，说明当前模型或网关不支持视频输入（video_url 内容块）");
            putRaw(out, r.body());
            return out;
        }

        try {
            JsonNode j = mapper.readTree(r.body());
            String text = extractReplyText(j);
            out.put("text", text);
            out.put("text_len", text.length());
            String finish = j.path("choices").path(0).path("finish_reason").asText("");
            if (finish.isEmpty()) finish = j.path("stop_reason").asText("");
            out.put("finish_reason", finish);
            JsonNode usage = j.path("usage");
            if (usage.isObject()) out.set("usage", usage);
            if (text.isEmpty()) {
                out.put("hint", "上游返回成功但未解析到文本，请展开原始响应确认（可能是流式响应，或模型不支持视频输入而返回空内容）");
            }
        } catch (Exception e) {
            out.put("text", "");
            out.put("hint", "响应解析失败：" + e.getMessage());
        }

        putRaw(out, r.body());
        return out;
    }

    /** 从 OpenAI / Anthropic / Gemini 三种响应结构中提取助手回复文本。 */
    private String extractReplyText(JsonNode j) {
        // OpenAI 兼容：choices[0].message.content（字符串或内容块数组）
        JsonNode c = j.path("choices").path(0).path("message").path("content");
        if (c.isTextual() && !c.asText().isEmpty()) return c.asText();
        if (c.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode b : c) {
                String s = b.isTextual() ? b.asText("") : b.path("text").asText("");
                if (!s.isEmpty()) { if (sb.length() > 0) sb.append("\n"); sb.append(s); }
            }
            if (sb.length() > 0) return sb.toString();
        }
        // Anthropic：content[]
        StringBuilder sb = new StringBuilder();
        for (JsonNode b : j.path("content")) {
            String s = b.path("text").asText("");
            if (!s.isEmpty()) { if (sb.length() > 0) sb.append("\n"); sb.append(s); }
        }
        if (sb.length() > 0) return sb.toString();
        // Gemini：candidates[0].content.parts[].text
        for (JsonNode part : j.path("candidates").path(0).path("content").path("parts")) {
            String s = part.path("text").asText("");
            if (!s.isEmpty()) { if (sb.length() > 0) sb.append("\n"); sb.append(s); }
        }
        return sb.toString();
    }

    /** 原始响应体塞入结果，超过 200KB 截断（完整 base64 已放入 images 数组）。 */
    private void putRaw(ObjectNode out, String body) {
        final int RAW_CAP = 200 * 1024;
        if (body == null) { out.put("raw", ""); out.put("truncated", false); return; }
        if (body.length() > RAW_CAP) {
            out.put("raw", body.substring(0, RAW_CAP));
            out.put("truncated", true);
        } else {
            out.put("raw", body);
            out.put("truncated", false);
        }
    }

    /** 写一个普通文本字段到 multipart 体。 */
    private void field(ByteArrayOutputStream bos, String boundary, String name, String value) throws Exception {
        bos.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        bos.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n")
                .getBytes(StandardCharsets.UTF_8));
    }

    /** 写一个文件字段到 multipart 体（base64 解码后写入）。 */
    private void filePart(ByteArrayOutputStream bos, String boundary, String name,
                          String base64, String mime) throws Exception {
        String filename = name.replace("[]", "") + "." + mime.substring(mime.indexOf('/') + 1);
        if (!mime.matches("(?i)^image/(png|jpe?g|webp|gif|bmp)$")) filename = name.replace("[]", "") + ".png";
        bos.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        bos.write(("Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + filename + "\"\r\n")
                .getBytes(StandardCharsets.UTF_8));
        bos.write(("Content-Type: " + mime + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        bos.write(Base64.getDecoder().decode(base64));
        bos.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }
}
