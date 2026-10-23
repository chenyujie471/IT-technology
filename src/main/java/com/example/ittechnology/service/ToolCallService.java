package com.example.ittechnology.service;

import com.example.ittechnology.service.AiProxyService.FetchResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工具调用 Function Calling：
 *
 *  1. 对话请求带上 tools 定义，非流式调用上游；
 *  2. 若上游返回 tool_use / tool_calls，逐一执行内置工具；
 *  3. 把工具结果回传给上游，继续循环，直到模型输出最终文本（或达到最大轮数）。
 *
 * 同时支持 OpenAI chat/completions 与 Anthropic messages 两种工具协议。
 */
@Slf4j
@Service
public class ToolCallService {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AiProxyService proxy;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public ToolCallService(AiProxyService proxy) {
        this.proxy = proxy;
    }

    /** 工具执行结果 */
    public record ToolRun(String name, String args, String result) {}

    /** 最终响应 */
    public record ToolResponse(String text, List<ToolRun> runs, boolean truncated) {}

    // ================= 工具定义（OpenAI schema） =================

    private ObjectNode toolDef(String name, String desc, ObjectNode props, List<String> required) {
        ObjectNode t = mapper.createObjectNode();
        t.put("type", "function");
        ObjectNode fn = mapper.createObjectNode();
        fn.put("name", name);
        fn.put("description", desc);
        fn.set("parameters", props);
        t.set("function", fn);
        return t;
    }

    private ObjectNode strProp(String desc) {
        ObjectNode p = mapper.createObjectNode();
        p.put("type", "string");
        p.put("description", desc);
        return p;
    }

    private ObjectNode numProp(String desc) {
        ObjectNode p = mapper.createObjectNode();
        p.put("type", "number");
        p.put("description", desc);
        return p;
    }

    /** 所有可用工具（OpenAI 格式）。 */
    public ArrayNode openAiTools() {
        ArrayNode arr = mapper.createArrayNode();

        ObjectNode calcProps = mapper.createObjectNode();
        calcProps.set("expression", strProp("数学表达式，如 (2 + 3) * 4 或 sqrt(16)，支持 + - * / ( ) 与 sqrt、abs、round"));
        arr.add(toolDef("calculator", "计算数学表达式的结果", calcProps, List.of("expression")));

        ObjectNode timeProps = mapper.createObjectNode();
        timeProps.set("timezone", strProp("可选，时区名称，如 Asia/Shanghai、UTC，默认本机时区"));
        arr.add(toolDef("get_current_time", "获取当前日期与时间", timeProps, List.of()));

        ObjectNode randProps = mapper.createObjectNode();
        randProps.set("min", numProp("最小值（含），默认 1"));
        randProps.set("max", numProp("最大值（含），默认 100"));
        arr.add(toolDef("get_random_number", "生成一个随机整数", randProps, List.of()));

        ObjectNode urlProps = mapper.createObjectNode();
        urlProps.set("url", strProp("要抓取的网页地址（http/https）"));
        arr.add(toolDef("fetch_url", "抓取指定网页的文本内容（前 2000 字符）", urlProps, List.of("url")));

        ObjectNode infoProps = mapper.createObjectNode();
        arr.add(toolDef("get_platform_info", "获取 AI Studio 平台的运行信息（服务版本、引擎、模型支持等）", infoProps, List.of()));

        ObjectNode noteProps = mapper.createObjectNode();
        noteProps.set("content", strProp("要记录的内容"));
        arr.add(toolDef("notepad", "把一段内容记录下来并返回记录确认（可用于让模型先整理再保存）", noteProps, List.of("content")));

        return arr;
    }

    /** Anthropic 格式的工具定义。 */
    public ArrayNode anthropicTools() {
        return toAnthropic(openAiTools());
    }

    /** 把 OpenAI 格式工具定义数组转换为 Anthropic 格式。 */
    public ArrayNode toAnthropic(ArrayNode oai) {
        ArrayNode arr = mapper.createArrayNode();
        for (JsonNode t : oai) {
            ObjectNode at = mapper.createObjectNode();
            at.put("name", t.path("function").path("name").asText());
            at.put("description", t.path("function").path("description").asText());
            // 注意：parameters 缺失（如 computer_use 类工具）时 path() 返回 MissingNode，
            // 不能直接强转 ObjectNode，必须先判断类型再兜底。
            JsonNode params = t.path("function").path("parameters");
            ObjectNode inputSchema = params != null && params.isObject()
                    ? (ObjectNode) params.deepCopy()
                    : mapper.createObjectNode();
            inputSchema.put("type", "object");
            at.set("input_schema", inputSchema);
            arr.add(at);
        }
        return arr;
    }

    // ================= 主入口 =================

    /**
     * 带工具的执行循环。
     * body: {model, messages:[{role,content}], max_tokens, tools?, system?, max_rounds?}
     *   - tools: 可选，OpenAI 格式的工具定义数组；缺省使用内置工具
     *   - system: 可选，系统提示词
     */
    public ToolResponse run(String site, String auth, JsonNode req, int maxRounds) throws Exception {
        String model = req.path("model").asText("");
        int maxTok = req.path("max_tokens").asInt(2000);
        ArrayNode messages = (ArrayNode) req.path("messages").deepCopy();
        String systemPrompt = req.path("system").asText("").trim();
        String lm = model.toLowerCase();
        boolean anthropic = lm.contains("claude");
        boolean gemini = lm.contains("gemini");
        if (gemini) {
            throw new IllegalStateException("Gemini 暂不支持工具调用，请使用 claude 或 OpenAI 系模型");
        }

        // 自定义 tools（OpenAI 格式）或内置
        ArrayNode oaiTools = req.has("tools") && req.path("tools").isArray()
                ? (ArrayNode) req.path("tools").deepCopy()
                : openAiTools();
        ArrayNode anTools = toAnthropic(oaiTools);

        List<ToolRun> runs = new ArrayList<>();
        String path = anthropic ? "/v1/messages" : "/v1/chat/completions";
        String toolMsg;
        if (anthropic) {
            ObjectNode b = mapper.createObjectNode();
            b.put("model", model);
            b.put("max_tokens", maxTok);
            b.put("stream", false);
            if (!systemPrompt.isEmpty()) b.put("system", systemPrompt);
            b.set("messages", messages);
            b.set("tools", anTools);
            toolMsg = b.toString();
        } else {
            ObjectNode b = mapper.createObjectNode();
            b.put("model", model);
            b.put("max_tokens", maxTok);
            b.put("stream", false);
            if (!systemPrompt.isEmpty()) b.put("system", systemPrompt);
            b.set("messages", messages);
            b.set("tools", oaiTools);
            toolMsg = b.toString();
        }

        for (int round = 0; round < maxRounds; round++) {
            FetchResult r = proxy.postJson(site, path, auth, toolMsg, Duration.ofSeconds(120));
            if (r.status() >= 400) {
                throw new IllegalStateException("上游 " + r.status() + ": " + truncate(r.body(), 300));
            }
            JsonNode resp = mapper.readTree(r.body());

            // 提取本轮工具调用
            List<ObjectNode> calls = new ArrayList<>();   // {name, arguments, id}
            if (anthropic) {
                for (JsonNode block : resp.path("content")) {
                    if ("tool_use".equals(block.path("type").asText())) {
                        calls.add(callOf(block.path("name").asText(),
                                block.path("input").toString(),
                                block.path("id").asText()));
                    }
                }
            } else {
                JsonNode msg = resp.path("choices").path(0).path("message");
                for (JsonNode tc : msg.path("tool_calls")) {
                    calls.add(callOf(tc.path("function").path("name").asText(),
                            tc.path("function").path("arguments").asText(),
                            tc.path("id").asText()));
                }
            }

            if (calls.isEmpty()) {
                // 无工具调用 → 最终文本
                String text = anthropic
                        ? extractClaudeText(resp)
                        : resp.path("choices").path(0).path("message").path("content").asText("");
                return new ToolResponse(text, runs, false);
            }

            // 执行本轮工具，构造回传消息
            if (anthropic) {
                // assistant content（原样，含 tool_use）+ user 携带 tool_result
                ObjectNode asst = mapper.createObjectNode();
                asst.put("role", "assistant");
                asst.set("content", resp.path("content").deepCopy());
                messages.add(asst);
                ArrayNode toolResults = mapper.createArrayNode();
                for (ObjectNode c : calls) {
                    String result = execute(c.path("name").asText(), c.path("arguments").asText());
                    runs.add(new ToolRun(c.path("name").asText(), c.path("arguments").asText(), truncate(result, 500)));
                    ObjectNode tr = mapper.createObjectNode();
                    tr.put("type", "tool_result");
                    tr.put("tool_use_id", c.path("id").asText());
                    tr.put("content", result);
                    toolResults.add(tr);
                }
                ObjectNode user = mapper.createObjectNode();
                user.put("role", "user");
                user.set("content", toolResults);
                messages.add(user);
            } else {
                ObjectNode asst = mapper.createObjectNode();
                asst.put("role", "assistant");
                asst.put("content", "");
                ArrayNode tcArr = mapper.createArrayNode();
                for (ObjectNode c : calls) {
                    ObjectNode tcn = mapper.createObjectNode();
                    tcn.put("id", c.path("id").asText());
                    tcn.put("type", "function");
                    ObjectNode f = mapper.createObjectNode();
                    f.put("name", c.path("name").asText());
                    f.put("arguments", c.path("arguments").asText());
                    tcn.set("function", f);
                    tcArr.add(tcn);
                }
                asst.set("tool_calls", tcArr);
                messages.add(asst);
                for (ObjectNode c : calls) {
                    String result = execute(c.path("name").asText(), c.path("arguments").asText());
                    runs.add(new ToolRun(c.path("name").asText(), c.path("arguments").asText(), truncate(result, 500)));
                    ObjectNode tm = mapper.createObjectNode();
                    tm.put("role", "tool");
                    tm.put("tool_call_id", c.path("id").asText());
                    tm.put("content", result);
                    messages.add(tm);
                }
            }

            // 重发请求（带上工具回传）
            if (anthropic) {
                ObjectNode b = mapper.createObjectNode();
                b.put("model", model);
                b.put("max_tokens", maxTok);
                b.put("stream", false);
                if (!systemPrompt.isEmpty()) b.put("system", systemPrompt);
                b.set("messages", messages);
                b.set("tools", anTools);
                toolMsg = b.toString();
            } else {
                ObjectNode b = mapper.createObjectNode();
                b.put("model", model);
                b.put("max_tokens", maxTok);
                b.put("stream", false);
                if (!systemPrompt.isEmpty()) b.put("system", systemPrompt);
                b.set("messages", messages);
                b.set("tools", oaiTools);
                toolMsg = b.toString();
            }
        }

        // 达到最大轮数仍未收尾 → 强制再问一次并返回其文本
        FetchResult r = proxy.postJson(site, path, auth, toolMsg, Duration.ofSeconds(120));
        JsonNode resp = mapper.readTree(r.body());
        String text = anthropic
                ? extractClaudeText(resp)
                : resp.path("choices").path(0).path("message").path("content").asText("");
        return new ToolResponse(text, runs, true);
    }

    private ObjectNode callOf(String name, String args, String id) {
        ObjectNode c = mapper.createObjectNode();
        c.put("name", name);
        c.put("arguments", args);
        c.put("id", id == null || id.isEmpty() ? "call_" + Math.abs(new Random().nextInt()) : id);
        return c;
    }

    private String extractClaudeText(JsonNode resp) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode block : resp.path("content")) {
            if ("text".equals(block.path("type").asText())) {
                String t = block.path("text").asText("");
                if (sb.length() > 0 && !t.isEmpty()) sb.append("\n");
                sb.append(t);
            }
        }
        return sb.toString();
    }

    // ================= 工具实现 =================

    /** 解析参数 JSON（失败返回空节点），然后执行对应工具。 */
    String execute(String name, String argsJson) {
        JsonNode a;
        try {
            a = mapper.readTree(argsJson == null || argsJson.isBlank() ? "{}" : argsJson);
        } catch (Exception e) {
            a = mapper.createObjectNode();
        }
        try {
            switch (name) {
                case "calculator":          return calc(a.path("expression").asText(""));
                case "get_current_time":    return currentTime(a.path("timezone").asText(""));
                case "get_random_number":   return randomNum(a.path("min").asDouble(1), a.path("max").asDouble(100));
                case "fetch_url":           return fetchUrl(a.path("url").asText(""));
                case "get_platform_info":   return platformInfo();
                case "notepad":             return notepad(a.path("content").asText(""));
                default:                    return "未知工具: " + name;
            }
        } catch (Exception e) {
            return "工具执行失败: " + e.getMessage();
        }
    }

    // ---- calculator：轻量表达式计算 ----
    private static final Pattern CALC_TOKEN = Pattern.compile("\\s*(\\d+\\.?\\d*|[+\\-*/()]|[a-zA-Z]+)");
    private String calc(String expr) {
        if (expr == null || expr.isBlank()) return "错误：缺少表达式";
        List<String> toks = new ArrayList<>();
        Matcher m = CALC_TOKEN.matcher(expr);
        int pos = 0;
        while (m.find()) {
            if (m.start() != pos) return "错误：无法解析的表达式（仅支持数字与 + - * / ( ) 和 sqrt/abs/round）";
            toks.add(m.group(1));
            pos = m.end();
        }
        if (pos != expr.length()) return "错误：无法解析的表达式";
        double[] idx = {0};
        try {
            double v = parseExpr(toks, idx);
            if (idx[0] != toks.size()) return "错误：多余的字符";
            return String.format(Locale.ROOT, "%.10g", v);
        } catch (Exception e) {
            return "计算错误: " + e.getMessage();
        }
    }

    private double parseExpr(List<String> t, double[] i) {
        double v = parseTerm(t, i);
        while (i[0] < t.size()) {
            String op = t.get((int) i[0]);
            if (op.equals("+")) { i[0]++; v += parseTerm(t, i); }
            else if (op.equals("-")) { i[0]++; v -= parseTerm(t, i); }
            else break;
        }
        return v;
    }

    private double parseTerm(List<String> t, double[] i) {
        double v = parseFactor(t, i);
        while (i[0] < t.size()) {
            String op = t.get((int) i[0]);
            if (op.equals("*")) { i[0]++; v *= parseFactor(t, i); }
            else if (op.equals("/")) { i[0]++; double d = parseFactor(t, i); if (d == 0) throw new ArithmeticException("除零"); v /= d; }
            else break;
        }
        return v;
    }

    private double parseFactor(List<String> t, double[] i) {
        if (i[0] >= t.size()) throw new RuntimeException("表达式不完整");
        String tok = t.get((int) i[0]);
        if (tok.equals("(")) {
            i[0]++;
            double v = parseExpr(t, i);
            if (i[0] >= t.size() || !t.get((int) i[0]).equals(")")) throw new RuntimeException("缺少右括号");
            i[0]++;
            return v;
        }
        if (tok.matches("[a-zA-Z]+")) {
            String fn = tok.toLowerCase();
            i[0]++;
            double arg = parseFactor(t, i);
            switch (fn) {
                case "sqrt": return Math.sqrt(arg);
                case "abs":  return Math.abs(arg);
                case "round": return Math.round(arg);
                default: throw new RuntimeException("未知函数 " + fn);
            }
        }
        try {
            i[0]++;
            return Double.parseDouble(tok);
        } catch (NumberFormatException e) {
            throw new RuntimeException("非法数字 " + tok);
        }
    }

    private String currentTime(String tz) {
        LocalDateTime now = LocalDateTime.now();
        String formatted = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String zone = (tz == null || tz.isBlank()) ? "本机时区" : tz;
        return "当前时间：" + formatted + "（" + zone + "）";
    }

    private String randomNum(double min, double max) {
        int lo = (int) Math.ceil(min), hi = (int) Math.floor(max);
        if (lo > hi) return "错误：min 不能大于 max";
        int v = lo + new Random().nextInt(hi - lo + 1);
        return "随机整数：" + v + "（范围 " + lo + " ~ " + hi + "）";
    }

    private String fetchUrl(String url) {
        if (url == null || url.isBlank()) return "错误：缺少 url";
        if (!(url.startsWith("http://") || url.startsWith("https://"))) return "错误：仅支持 http/https 地址";
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "AI-Studio-Tool/1.0")
                    .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            String body = resp.body() == null ? "" : resp.body().replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
            return "HTTP " + resp.statusCode() + "\n内容：\n" + truncate(body, 1800);
        } catch (Exception e) {
            return "抓取失败: " + e.getMessage();
        }
    }

    private String platformInfo() {
        return "AI Studio 多模态快速 AI 聚合测试平台\n"
                + "引擎：Spring Boot（Java 17）+ 原生前端\n"
                + "能力：文本对话 / 文生图 / 文生视频 / 无限画布工作流（条件分支·循环·变量·会话绑定）/ 多会话管理 / 工具调用\n"
                + "上游：kkidc 国内站与 aiide 国际站，支持 Anthropic messages、OpenAI chat/completions、Gemini 三种协议";
    }

    private String notepad(String content) {
        if (content == null || content.isBlank()) return "错误：没有可记录的内容";
        return "📝 已记录 " + content.length() + " 字符：" + truncate(content, 200);
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
