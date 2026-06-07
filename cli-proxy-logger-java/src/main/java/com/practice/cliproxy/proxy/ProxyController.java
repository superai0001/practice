package com.practice.cliproxy.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.practice.cliproxy.config.ProxyProperties;
import com.practice.cliproxy.filters.FilterEngine;
import com.practice.cliproxy.model.Exchange;
import com.practice.cliproxy.model.NormalizedResponse;
import com.practice.cliproxy.outbound.OutboundProxy;
import com.practice.cliproxy.parser.ParserFactory;
import com.practice.cliproxy.parser.StreamAggregator;
import com.practice.cliproxy.parser.WireParser;
import com.practice.cliproxy.recorder.ExchangeRecorder;
import com.practice.cliproxy.resilience.BreakerRegistry;
import com.practice.cliproxy.resilience.Provider;
import com.practice.cliproxy.resilience.Providers;
import com.practice.cliproxy.resilience.Rectifier;
import com.practice.cliproxy.sse.SseParser;
import com.practice.cliproxy.transform.ToolNameTransformer;
import com.practice.cliproxy.translator.Translator;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 反向代理核心：捕获 /v1/** 的全部请求，转发到真实上游，并在「先把上游字节
 * 原样回写给 CLI」之后，把同一份内容喂给对应 wire 的解析器，重建文本与工具调用。
 *
 * 说明：转发时刻意不带 Accept-Encoding，交给 HttpURLConnection 自行协商 gzip 并
 * 透明解压，这样我们读到/转发出去的都是 identity 字节，解析也无需再处理压缩。
 */
@RestController
public class ProxyController {

    // 「逐跳（hop-by-hop）」头只对单个传输连接有意义（RFC 7230 6.1），代理不能
    // 原样转发。这里还顺带去掉 host/content-length（为新连接重新计算）、
    // transfer-encoding（由 HttpURLConnection 重新分帧）、以及 accept-encoding
    // ——不带它就让 HttpURLConnection 自行协商 gzip 并透明解压，读到的即 identity。
    // 用 HashSet + Arrays.asList 构造（兼容 JDK 8；Java 9 的 Set.of 在 8 上不可用）。
    private static final Set<String> HOP_BY_HOP = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade", "host", "content-length", "accept-encoding")));

    // 协议翻译模式下，drop 掉这些 Anthropic 专属/会冲突的请求头（鉴权另行翻译）。
    private static final Set<String> COMPAT_DROP = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "x-api-key", "anthropic-version", "anthropic-beta",
            "anthropic-dangerous-direct-browser-access", "content-type")));

    private final ProxyProperties props;
    private final UpstreamResolver resolver;
    private final ParserFactory parsers;
    private final ExchangeRecorder recorder;
    private final Translator translator;
    private final ObjectMapper mapper;

    // 弹性：整个代理共享一个熔断器注册表，使某 provider 的失败状态在多次请求间保留。
    private final BreakerRegistry breakers;
    // 解析后的供应商池（懒加载，首次用到时按当前配置解析一次）。
    private volatile Map<String, List<Provider>> providerPools;

    // 扩展三件套：过滤器 / 工具名映射 / 出站代理（懒加载，按当前配置解析一次）。
    private volatile List<FilterEngine.Filter> filtersCache;
    private volatile Map<String, String> toolNameMapCache;
    private volatile boolean outboundResolved;
    private volatile OutboundProxy outboundCache;

    public ProxyController(ProxyProperties props, UpstreamResolver resolver, ParserFactory parsers,
                           ExchangeRecorder recorder, Translator translator, ObjectMapper mapper) {
        this.props = props;
        this.resolver = resolver;
        this.parsers = parsers;
        this.recorder = recorder;
        this.translator = translator;
        this.mapper = mapper;
        this.breakers = new BreakerRegistry(props.resolveBreakerFailures(),
                props.resolveBreakerCooldownMs(), props.resolveBreakerHalfOpenMax());
    }

    /** 暴露熔断器注册表（给 UI / 测试做内省，对应 Node 的 proxy.breakers）。 */
    public BreakerRegistry getBreakers() {
        return breakers;
    }

    private Map<String, List<Provider>> pools() {
        Map<String, List<Provider>> p = providerPools;
        if (p == null) {
            synchronized (this) {
                p = providerPools;
                if (p == null) {
                    p = Providers.parse(props.resolveProvidersRaw(), mapper);
                    providerPools = p;
                }
            }
        }
        return p;
    }

    /** 解析后的过滤器列表（懒加载）。无配置返回空列表。 */
    private List<FilterEngine.Filter> filters() {
        List<FilterEngine.Filter> f = filtersCache;
        if (f == null) {
            synchronized (this) {
                f = filtersCache;
                if (f == null) {
                    f = FilterEngine.parseFilters(props.resolveFiltersRaw(), mapper);
                    filtersCache = f;
                }
            }
        }
        return f;
    }

    /** 工具名映射（内置表 + 用户覆盖，懒加载）。 */
    private Map<String, String> toolNameMap() {
        Map<String, String> m = toolNameMapCache;
        if (m == null) {
            synchronized (this) {
                m = toolNameMapCache;
                if (m == null) {
                    m = ToolNameTransformer.buildToolNameMap(props.resolveToolNameMapRaw(), mapper);
                    toolNameMapCache = m;
                }
            }
        }
        return m;
    }

    /** 出站代理（懒加载）。未配置返回 null（直连）。 */
    private OutboundProxy outbound() {
        if (!outboundResolved) {
            synchronized (this) {
                if (!outboundResolved) {
                    com.practice.cliproxy.kernel.Kernel.Options kopts =
                            new com.practice.cliproxy.kernel.Kernel.Options();
                    kopts.kernel = props.resolveProxyKernel();
                    kopts.configPath = props.resolveProxyKernelConfig();
                    kopts.xrayBin = props.resolveXrayBin();
                    kopts.singboxBin = props.resolveSingBoxBin();
                    kopts.socksPort = props.resolveProxyKernelSocksPort();
                    OutboundProxy ob = OutboundProxy.create(props.resolveUpstreamProxyUrl(), kopts);
                    if (ob != null) {
                        // 进程退出时清理内核子进程（与 Node src/index.js 的退出钩子一致）。
                        Runtime.getRuntime().addShutdownHook(new Thread(ob::stop));
                    }
                    outboundCache = ob;
                    outboundResolved = true;
                }
            }
        }
        return outboundCache;
    }

    /** 暴露出站代理描述（给 /api/config 内省）。 */
    public OutboundProxy getOutbound() {
        return outbound();
    }

    /** 暴露解析后的过滤器（给 /api/config 内省）。 */
    public List<FilterEngine.Filter> getFilters() {
        return filters();
    }

    /** 工具名映射大小（给 /api/config 内省）。 */
    public int getToolNameMapSize() {
        return toolNameMap().size();
    }

    /**
     * 当前生效配置的只读快照（给 Web UI 展示）。绝不含密钥（provider apiKey 只暴露
     * hasKey 布尔）。镜像 Node ui-server.js 的 configSummary。
     */
    public Map<String, Object> configSummary() {
        Map<String, Object> out = new LinkedHashMap<>();

        Map<String, Object> compat = new LinkedHashMap<>();
        compat.put("anthropicTo", props.isAnthropicToChat() ? "chat" : null);
        out.put("compat", compat);

        Map<String, List<Provider>> pools = pools();
        Map<String, Object> providers = new LinkedHashMap<>();
        providers.put("anthropic", summarizeProviders(pools.get("anthropic")));
        providers.put("openai", summarizeProviders(pools.get("openai")));
        out.put("providers", providers);

        Map<String, Object> breaker = new LinkedHashMap<>();
        breaker.put("enabled", props.isBreakerEnabled()
                || (pools.get("anthropic") != null && !pools.get("anthropic").isEmpty())
                || (pools.get("openai") != null && !pools.get("openai").isEmpty()));
        breaker.put("failureThreshold", props.getBreakerFailures());
        breaker.put("cooldownMs", props.getBreakerCooldownMs());
        out.put("breaker", breaker);

        Map<String, Object> rectifier = new LinkedHashMap<>();
        rectifier.put("enabled", props.isRectifyEnabled());
        rectifier.put("signature", props.isRectifySignatureEnabled());
        rectifier.put("budget", props.isRectifyBudgetEnabled());
        out.put("rectifier", rectifier);

        Map<String, Object> toolName = new LinkedHashMap<>();
        boolean tnEnabled = props.isToolNameEnabled();
        toolName.put("enabled", tnEnabled);
        toolName.put("request", props.isToolNameRequestEnabled());
        toolName.put("response", props.isToolNameResponseEnabled());
        toolName.put("repairInput", props.isToolNameRepairInputEnabled());
        toolName.put("mapSize", toolNameMap().size());
        out.put("toolName", toolName);

        out.put("filters", FilterEngine.summarizeFilters(filters()));

        Map<String, Object> outbound = new LinkedHashMap<>();
        OutboundProxy ob = outbound();
        if (ob != null) {
            outbound.put("enabled", true);
            outbound.put("describe", ob.describe());
        } else {
            outbound.put("enabled", false);
        }
        out.put("outbound", outbound);

        return out;
    }

    private static List<Map<String, Object>> summarizeProviders(List<Provider> pool) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (pool != null) {
            for (Provider p : pool) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", p.id);
                m.put("baseUrl", p.baseUrl);
                m.put("hasKey", p.apiKey != null && !p.apiKey.isEmpty());
                out.add(m);
            }
        }
        return out;
    }

    // 打开到上游的连接：配置了出站代理走代理，否则直连。
    private HttpURLConnection openConnection(String url) throws IOException {
        OutboundProxy ob = outbound();
        URL u = new URL(url);
        if (ob != null) {
            return (HttpURLConnection) u.openConnection(ob.proxy());
        }
        return (HttpURLConnection) u.openConnection();
    }

    /**
     * opt-in 出站请求改写（过滤器 + 工具名规范化）。原地修改 outHeaders（请求头过滤），
     * 返回可能更新过的请求体字节（当 body 过滤/工具名改写改动了它时）。命中的内容记录
     * 到 exchange.mutation（供 UI/日志可见）。无任何配置时返回原始字节、不动 headers。
     */
    private byte[] applyOutboundMutation(String wire, String providerId,
                                         Map<String, String> outHeaders, byte[] bodyBytes, Exchange ex) {
        List<FilterEngine.Filter> filters = filters();
        boolean toolNameOn = props.isToolNameEnabled();
        boolean toolNameReq = toolNameOn && props.isToolNameRequestEnabled() && "anthropic".equals(wire);
        boolean hasFilters = filters != null && !filters.isEmpty();
        if (!hasFilters && !toolNameReq) {
            return bodyBytes;
        }
        boolean needBody = toolNameReq
                || (hasFilters && filters.stream().anyMatch(f -> "body".equals(f.domain)));
        ObjectNode bodyObj = null;
        if (needBody && bodyBytes.length > 0) {
            JsonNode parsed = treeOrNull(new String(bodyBytes, StandardCharsets.UTF_8));
            if (parsed != null && parsed.isObject()) {
                bodyObj = (ObjectNode) parsed;
            }
        }

        boolean changed = false;
        Map<String, Object> meta = new LinkedHashMap<>();
        if (hasFilters) {
            FilterEngine.Result res = FilterEngine.applyFilters(filters, providerId, outHeaders, bodyObj, mapper);
            if (!res.applied.isEmpty()) {
                meta.put("filters", res.applied);
            }
            if (res.bodyChanged) {
                changed = true;
            }
        }
        if (toolNameReq && bodyObj != null) {
            int n = ToolNameTransformer.normalizeRequestToolNames(bodyObj, toolNameMap());
            if (n > 0) {
                meta.put("toolNamesRewritten", n);
                changed = true;
            }
        }
        if (ex != null && !meta.isEmpty()) {
            if (ex.mutation == null) {
                ex.mutation = new LinkedHashMap<>();
            }
            ex.mutation.putAll(meta);
        }
        if (changed && bodyObj != null) {
            try {
                return mapper.writeValueAsBytes(bodyObj);
            } catch (Exception e) {
                return bodyBytes;
            }
        }
        return bodyBytes;
    }

    /**
     * 是否要改写 Anthropic「响应」（工具名 / input 修复）？这会牺牲字节级保真换取客户端
     * 兼容，所以只在对 Anthropic 流量显式开启时发生。
     */
    private boolean responseRewriteActive(String wire) {
        return props.isToolNameEnabled() && "anthropic".equals(wire)
                && (props.isToolNameResponseEnabled() || props.isToolNameRepairInputEnabled());
    }

    /**
     * 改写并转发 Anthropic 上游响应给客户端（替代逐字 tee），随后记录 exchange。
     * 同时处理 SSE 与普通 JSON。因为改了 body，丢掉 content-encoding/length 发 identity。
     * 上游连接因为不带 Accept-Encoding，已是 identity 字节，无需再解压。
     */
    private void forwardRewrittenResponse(HttpURLConnection conn, Exchange ex, HttpServletResponse resp,
                                          long started, int status) throws Exception {
        ex.resStatus = status;
        resp.setStatus(status);
        WireParser parser = parsers.get("anthropic");
        String contentType = conn.getContentType();
        boolean sse = contentType != null && contentType.contains("text/event-stream");

        // 输出响应头：除了我们会重算的分帧/编码头，其余原样保留。
        Map<String, String> outHeaders = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : conn.getHeaderFields().entrySet()) {
            String name = e.getKey();
            if (name == null) {
                continue;
            }
            String lk = name.toLowerCase(Locale.ROOT);
            if (HOP_BY_HOP.contains(lk) || lk.equals("content-encoding") || lk.equals("content-length")) {
                continue;
            }
            String value = String.join(",", e.getValue());
            resp.setHeader(name, value);
            outHeaders.put(name, value);
        }
        ex.resHeaders = outHeaders;

        boolean tnResponse = props.isToolNameResponseEnabled();
        boolean tnRepair = props.isToolNameRepairInputEnabled();
        Map<String, String> tnMap = toolNameMap();

        InputStream upstream = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (upstream == null) {
            upstream = new ByteArrayInputStream(new byte[0]);
        }
        OutputStream clientOut = resp.getOutputStream();

        if (sse) {
            StreamAggregator agg = parser.newAggregator();
            SseParser sseParser = new SseParser(agg::feed);
            StringBuilder lineBuf = new StringBuilder();
            byte[] buf = new byte[8192];
            int n;
            try {
                while ((n = upstream.read(buf)) != -1) {
                    String text = new String(buf, 0, n, StandardCharsets.UTF_8);
                    sseParser.push(text); // 落盘副本
                    lineBuf.append(text);
                    int idx;
                    while ((idx = indexOfNewline(lineBuf)) >= 0) {
                        String line = lineBuf.substring(0, idx);
                        lineBuf.delete(0, idx + 1);
                        clientOut.write((rewriteSseLine(line, tnResponse, tnMap) + "\n").getBytes(StandardCharsets.UTF_8));
                        clientOut.flush();
                    }
                }
                if (lineBuf.length() > 0) {
                    clientOut.write((rewriteSseLine(lineBuf.toString(), tnResponse, tnMap) + "\n").getBytes(StandardCharsets.UTF_8));
                    clientOut.flush();
                }
                sseParser.flush();
            } catch (Exception e) {
                ex.error = "upstream stream error: " + e.getMessage();
            } finally {
                upstream.close();
                conn.disconnect();
            }
            try {
                ex.response = agg.result();
            } catch (Exception e) {
                NormalizedResponse r = new NormalizedResponse();
                r.parseError = e.getMessage();
                ex.response = r;
            }
        } else {
            byte[] raw = readAll(upstream);
            upstream.close();
            conn.disconnect();
            String bodyText = new String(raw, StandardCharsets.UTF_8);
            JsonNode obj = treeOrNull(bodyText);
            byte[] outBuf;
            if (obj != null && obj.isObject()) {
                int rewrites = 0;
                if (tnResponse) {
                    rewrites += ToolNameTransformer.rewriteResponseToolNames(obj, tnMap);
                }
                if (tnRepair) {
                    rewrites += ToolNameTransformer.repairToolUseInput(obj, mapper);
                }
                if (rewrites > 0) {
                    if (ex.mutation == null) {
                        ex.mutation = new LinkedHashMap<>();
                    }
                    ex.mutation.put("responseRewrites", rewrites);
                }
                outBuf = mapper.writeValueAsBytes(obj);
            } else {
                outBuf = raw;
            }
            resp.setHeader("Content-Length", String.valueOf(outBuf.length));
            if (outBuf.length > 0) {
                clientOut.write(outBuf);
            }
            clientOut.flush();
            try {
                ex.response = parser.parseResponse(obj != null ? new String(outBuf, StandardCharsets.UTF_8) : bodyText);
            } catch (Exception e) {
                NormalizedResponse r = new NormalizedResponse();
                r.parseError = e.getMessage();
                ex.response = r;
            }
        }
        ex.durationMs = System.currentTimeMillis() - started;
        recorder.record(ex);
    }

    private static int indexOfNewline(StringBuilder sb) {
        for (int i = 0; i < sb.length(); i++) {
            if (sb.charAt(i) == '\n') {
                return i;
            }
        }
        return -1;
    }

    // 改写单行 SSE 文本：只有携带 tool_use content_block_start 的 data: 行会被改写；
    // 其它（event:、id:、注释、空行）原样透传，保留事件分帧。
    private String rewriteSseLine(String line, boolean tnResponse, Map<String, String> tnMap) {
        if (!tnResponse || !line.startsWith("data:")) {
            return line;
        }
        String jsonStr = line.substring(5).trim();
        if (jsonStr.isEmpty() || "[DONE]".equals(jsonStr)) {
            return line;
        }
        JsonNode data = treeOrNull(jsonStr);
        if (data == null) {
            return line;
        }
        if (ToolNameTransformer.rewriteStreamEventToolName(data, tnMap)) {
            try {
                return "data: " + mapper.writeValueAsString(data);
            } catch (Exception e) {
                return line;
            }
        }
        return line;
    }

    /**
     * 是否对该 wire 走「弹性路径」（provider 池 / 熔断 / 整流任一启用）。返回 false
     * 时走原来的透明路径，默认行为字节级不变。
     */
    private boolean isResilient(String wire) {
        List<Provider> pool = pools().get(Providers.wireToGroup(wire));
        if (pool != null && !pool.isEmpty()) {
            return true;
        }
        if (props.isBreakerEnabled()) {
            return true;
        }
        return props.isRectifyEnabled() && "anthropic".equals(wire);
    }

    @RequestMapping("/v1/**")
    public void proxy(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        long started = System.currentTimeMillis();
        // 步骤 1：缓冲请求体。CLI 的请求体是一段完整 JSON，整体读入最简单。
        byte[] reqBody = readAll(req.getInputStream());

        // 步骤 2：按路径选真实上游与 wire 格式。路径即可判断来源：
        //   /v1/messages == Claude Code（Anthropic）；
        //   /v1/responses 或 /v1/chat/completions == Codex。
        String path = req.getRequestURI();
        String query = req.getQueryString();
        UpstreamResolver.Upstream up = resolver.resolve(path, req.getHeader("x-api-key"), req.getHeader("anthropic-version"));

        // 兼容模式：ANTHROPIC_COMPAT=chat 且客户端说 Anthropic Messages 时，交给
        // 「翻译」路径（Anthropic -> OpenAI Chat），而非透明直通。这是唯一会同时
        // 改写请求与响应的分支。
        if (props.isAnthropicToChat() && "anthropic".equals(up.wire) && path.startsWith("/v1/messages")) {
            handleAnthropicToChat(req, resp, reqBody, started);
            return;
        }

        // 弹性透明路径：provider 故障转移 + 熔断 + （Anthropic）thinking 整流。
        // 仅在 opt-in 时进入；否则保持下面原来的透明路径，行为字节级不变。
        if (isResilient(up.wire)) {
            handleResilient(req, resp, reqBody, started, up.wire, path, query);
            return;
        }

        WireParser parser = parsers.get(up.wire);

        Exchange ex = new Exchange();
        ex.id = UUID.randomUUID().toString();
        ex.ts = Instant.ofEpochMilli(started).toString();
        ex.wire = up.wire;
        ex.method = req.getMethod();
        ex.url = up.baseUrl + path + (query != null ? "?" + query : "");
        ex.reqHeaders = collectRequestHeaders(req);
        ex.requestBodyRaw = truncate(reqBody);
        ex.request = parser.parseRequest(new String(reqBody, StandardCharsets.UTF_8));

        // 步骤 3+4：建立到上游的连接，并原样转发客户端请求头（含真实的
        // Authorization / x-api-key），让上游看到与原始 CLI 完全一致的请求——
        // 这也是为何上游针对 CLI 特有请求头的放行逻辑透过代理依然成立。
        // opt-in：发送前先做过滤器 + 工具名规范化改写（不开启则原样、字节不变），
        // 出站连接可选地走外部代理（openConnection）。
        Map<String, String> outHeaders = buildForwardHeaders(req);
        byte[] sendBody = applyOutboundMutation(up.wire, null, outHeaders, reqBody, ex);
        HttpURLConnection conn = openConnection(ex.url);
        conn.setInstanceFollowRedirects(false);
        conn.setRequestMethod(req.getMethod());
        writeHeaders(conn, outHeaders);
        if (sendBody.length > 0) {
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(sendBody.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(sendBody);
            }
        }

        int status;
        try {
            status = conn.getResponseCode();
        } catch (Exception e) {
            ex.error = "upstream request error: " + e.getMessage();
            ex.durationMs = System.currentTimeMillis() - started;
            recorder.record(ex);
            resp.setStatus(502);
            resp.setContentType("application/json");
            resp.getWriter().write("{\"error\":{\"type\":\"proxy_error\",\"message\":\"" + e.getMessage() + "\"}}");
            return;
        }

        // opt-in：改写 Anthropic 响应（工具名 / input 修复）替代逐字 tee。
        if (responseRewriteActive(up.wire)) {
            forwardRewrittenResponse(conn, ex, resp, started, status);
            return;
        }

        // 步骤 5：把上游状态码与响应头回写给客户端（去掉逐跳头）。
        ex.resStatus = status;
        resp.setStatus(status);
        ex.resHeaders = copyResponseHeaders(conn, resp);

        // 步骤 6 准备：SSE 流式响应走增量解析器 + 按 wire 的聚合器重建文本与
        // 工具调用；非流式则缓冲整段字节，最后一次性解析。
        String contentType = conn.getContentType();
        boolean sse = contentType != null && contentType.contains("text/event-stream");

        InputStream upstream = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (upstream == null) {
            // 空流（兼容 JDK 8；InputStream.nullInputStream() 是 Java 11 才有）。
            upstream = new ByteArrayInputStream(new byte[0]);
        }

        StreamAggregator agg = sse ? parser.newAggregator() : null;
        SseParser sseParser = sse ? new SseParser(agg::feed) : null;
        ByteArrayOutputStream copy = sse ? null : new ByteArrayOutputStream();

        // 步骤 6：双路转发循环。每读到一块上游数据，先把「原始字节」回写给
        // CLI（保真优先：即便解析抛错也不影响 CLI），再把同一份喂给 SSE 解析器
        // 或缓冲区。
        OutputStream clientOut = resp.getOutputStream();
        byte[] buf = new byte[8192];
        int n;
        try {
            while ((n = upstream.read(buf)) != -1) {
                clientOut.write(buf, 0, n);   // 保真：先原样回写给 CLI
                clientOut.flush();            // flush 让 SSE 实时到达
                if (sse) {
                    sseParser.push(new String(buf, 0, n, StandardCharsets.UTF_8));
                } else if (copy.size() < props.getMaxBodyBytes()) {
                    copy.write(buf, 0, n);
                }
            }
        } catch (Exception e) {
            ex.error = "upstream stream error: " + e.getMessage();
        } finally {
            upstream.close();
        }

        // 步骤 7：收尾——得到归一化响应并记录这条 Exchange。
        try {
            if (sse) {
                sseParser.flush();   // 冲出缓冲区里残留的最后一个事件
                ex.response = agg.result();
            } else {
                // ByteArrayOutputStream.toString(Charset) 是 Java 10+；用 toByteArray + new String 兼容 JDK 8。
                ex.response = parser.parseResponse(new String(copy.toByteArray(), StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            NormalizedResponse r = new NormalizedResponse();
            r.parseError = e.getMessage();
            ex.response = r;
        }
        ex.durationMs = System.currentTimeMillis() - started;
        recorder.record(ex);
    }

    // =================================================================
    // 弹性透明路径：provider 故障转移 + 熔断 + （Anthropic）thinking 整流。
    // 仅当 isResilient() 为 true 时进入；默认透明路径 proxy() 原样保留，所以非弹性
    // 流量与之前字节级一致。
    //
    // 每个客户端请求按顺序遍历候选 provider，对每个：
    //   - 熔断 OPEN（冷却未到）则跳过；
    //   - 发请求；2xx 则提交（把字节流回客户端）并结束；
    //   - 连接错误或「故障转移状态」（默认 429/5xx）则记失败并试下一个；
    //   - 可整流的 Anthropic 错误则改写请求体、对同一 provider 重试一次（属客户端
    //     兼容修复，不计入熔断失败）；
    //   - 其它错误（401/403/400…）原样提交给客户端。
    // 故障转移只能在「把 2xx 流给客户端之前」发生。
    // =================================================================
    private void handleResilient(HttpServletRequest req, HttpServletResponse resp, byte[] reqBody, long started,
                                 String wire, String path, String query) throws Exception {
        WireParser parser = parsers.get(wire);
        String fallbackBaseUrl = "anthropic".equals(wire) ? props.getAnthropicUpstream() : props.getOpenaiUpstream();
        List<Provider> candidates = Providers.resolveCandidates(pools(), wire, fallbackBaseUrl);
        String group = Providers.wireToGroup(wire);
        List<Provider> pool = pools().get(group);
        boolean poolConfigured = pool != null && !pool.isEmpty();
        boolean useBreaker = props.isBreakerEnabled() || poolConfigured;
        Set<Integer> failoverStatuses = props.resolveFailoverStatuses();
        boolean rectifyOn = props.isRectifyEnabled() && "anthropic".equals(wire);
        boolean sigOn = props.isRectifySignatureEnabled();
        boolean budgetOn = props.isRectifyBudgetEnabled();
        JsonNode anthObj = "anthropic".equals(wire) ? treeOrEmpty(new String(reqBody, StandardCharsets.UTF_8)) : null;

        int lastStatus = -1;
        byte[] lastBody = null;
        Map<String, String> lastHeaders = null;
        int attemptNo = 0;
        boolean attemptedAny = false;

        for (int i = 0; i < candidates.size(); i++) {
            Provider cand = candidates.get(i);
            boolean isLast = i == candidates.size() - 1;
            BreakerRegistry.Permit permit = new BreakerRegistry.Permit(true, false);
            if (useBreaker) {
                permit = breakers.canRequest(cand.id);
                if (!permit.allowed) {
                    continue;
                }
            }
            attemptedAny = true;

            byte[] bodyBytes = reqBody;
            String rectifiedKind = null;
            boolean rectifyTried = false;

            while (true) {
                attemptNo++;
                String url = cand.baseUrl + path + (query != null ? "?" + query : "");
                Exchange ex = newResilientExchange(req, wire, url, bodyBytes, started);
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("providerId", cand.id);
                meta.put("attempt", attemptNo);
                if (rectifiedKind != null) {
                    meta.put("rectified", rectifiedKind);
                }
                ex.resilience = meta;

                // opt-in：发送前做过滤器 + 工具名规范化（按本 provider 作用域），
                // 出站连接可选地走外部代理。
                Map<String, String> outHeaders = buildResilientHeaders(req, wire, cand);
                byte[] sendBody = applyOutboundMutation(wire, cand.id, outHeaders, bodyBytes, ex);
                HttpURLConnection conn = openConnection(url);
                conn.setInstanceFollowRedirects(false);
                conn.setRequestMethod(req.getMethod());
                writeHeaders(conn, outHeaders);
                if (sendBody.length > 0) {
                    conn.setDoOutput(true);
                    conn.setFixedLengthStreamingMode(sendBody.length);
                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(sendBody);
                    }
                }

                int status;
                try {
                    status = conn.getResponseCode();
                } catch (Exception e) {
                    ex.error = "upstream request error: " + e.getMessage();
                    ex.durationMs = System.currentTimeMillis() - started;
                    meta.put("failedOver", !isLast);
                    recorder.record(ex);
                    if (useBreaker) {
                        breakers.recordFailure(cand.id, permit.halfOpen);
                    }
                    conn.disconnect();
                    break; // 下一个候选
                }

                if (status >= 200 && status < 300) {
                    // opt-in：改写 Anthropic 响应（工具名 / input 修复）替代逐字 tee。
                    if (responseRewriteActive(wire)) {
                        forwardRewrittenResponse(conn, ex, resp, started, status);
                    } else {
                        commitResilientStream(conn, parser, ex, resp, started, status);
                    }
                    if (useBreaker) {
                        breakers.recordSuccess(cand.id, permit.halfOpen);
                    }
                    return;
                }

                // 非 2xx：完整缓冲（已解压的）错误体。
                InputStream errStream = conn.getErrorStream();
                if (errStream == null) {
                    errStream = new ByteArrayInputStream(new byte[0]);
                }
                byte[] raw = readAll(errStream);
                errStream.close();
                String text = new String(raw, StandardCharsets.UTF_8);
                JsonNode obj = treeOrNull(text);
                Map<String, String> headers = collectResponseHeaders(conn);
                conn.disconnect();
                ex.resStatus = status;
                ex.resHeaders = headers;
                ex.error = errorMessage(obj, status);
                try {
                    ex.response = parser.parseResponse(text);
                } catch (Exception e) {
                    NormalizedResponse r = new NormalizedResponse();
                    r.parseError = e.getMessage();
                    ex.response = r;
                }

                // (a) 可整流的 Anthropic 错误 -> 改写 + 对同一 provider 重试一次。
                if (rectifyOn && !rectifyTried) {
                    String kind = Rectifier.detect(status, obj, sigOn, budgetOn);
                    if (kind != null) {
                        rectifyTried = true;
                        rectifiedKind = kind;
                        meta.put("rectifyTriggered", kind);
                        ex.durationMs = System.currentTimeMillis() - started;
                        recorder.record(ex); // 记录整流前失败（不动熔断）
                        bodyBytes = mapper.writeValueAsBytes(Rectifier.apply(kind, anthObj, mapper));
                        continue; // 重试同一候选（permit 仍持有）
                    }
                }

                // (b) 可故障转移且还有下一个 provider -> 试下一个。
                if (failoverStatuses.contains(status) && !isLast) {
                    meta.put("failedOver", true);
                    ex.durationMs = System.currentTimeMillis() - started;
                    recorder.record(ex);
                    if (useBreaker) {
                        breakers.recordFailure(cand.id, permit.halfOpen);
                    }
                    lastStatus = status;
                    lastBody = raw;
                    lastHeaders = headers;
                    break;
                }

                // (c) 终止错误 -> 原样回写客户端。
                resp.setStatus(status);
                for (Map.Entry<String, String> h : headers.entrySet()) {
                    resp.setHeader(h.getKey(), h.getValue());
                }
                resp.setHeader("Content-Length", String.valueOf(raw.length));
                if (raw.length > 0) {
                    resp.getOutputStream().write(raw);
                }
                resp.flushBuffer(); // 提交响应，防止测试期 ErrorPageFilter 把 4xx/5xx 响应体清掉
                ex.durationMs = System.currentTimeMillis() - started;
                recorder.record(ex);
                if (useBreaker) {
                    if (failoverStatuses.contains(status)) {
                        breakers.recordFailure(cand.id, permit.halfOpen);
                    } else {
                        breakers.recordNeutral(cand.id, permit.halfOpen);
                    }
                }
                return;
            }
        }

        // 候选耗尽：回放最后一次缓冲的错误，否则返回 502。
        if (lastStatus != -1) {
            resp.setStatus(lastStatus);
            if (lastHeaders != null) {
                for (Map.Entry<String, String> h : lastHeaders.entrySet()) {
                    resp.setHeader(h.getKey(), h.getValue());
                }
            }
            byte[] body = lastBody != null ? lastBody : new byte[0];
            resp.setHeader("Content-Length", String.valueOf(body.length));
            if (body.length > 0) {
                resp.getOutputStream().write(body);
            }
            resp.flushBuffer();
        } else {
            String msg = attemptedAny ? "all upstream providers failed" : "all providers unavailable (circuit open)";
            String payload = "anthropic".equals(wire)
                    ? "{\"type\":\"error\",\"error\":{\"type\":\"proxy_error\",\"message\":\"" + msg + "\"}}"
                    : "{\"error\":{\"type\":\"proxy_error\",\"message\":\"" + msg + "\"}}";
            byte[] out = payload.getBytes(StandardCharsets.UTF_8);
            resp.setStatus(502);
            resp.setContentType("application/json; charset=utf-8");
            resp.setHeader("Content-Length", String.valueOf(out.length));
            resp.getOutputStream().write(out);
            resp.flushBuffer();
        }
    }

    // 2xx：把上游流回写给客户端，同时把解码副本喂给 parser（与透明路径的步骤 5-7 一致）。
    private void commitResilientStream(HttpURLConnection conn, WireParser parser, Exchange ex,
                                       HttpServletResponse resp, long started, int status) throws Exception {
        ex.resStatus = status;
        resp.setStatus(status);
        ex.resHeaders = copyResponseHeaders(conn, resp);
        String contentType = conn.getContentType();
        boolean sse = contentType != null && contentType.contains("text/event-stream");
        InputStream upstream = conn.getInputStream();
        if (upstream == null) {
            upstream = new ByteArrayInputStream(new byte[0]);
        }
        StreamAggregator agg = sse ? parser.newAggregator() : null;
        SseParser sseParser = sse ? new SseParser(agg::feed) : null;
        ByteArrayOutputStream copy = sse ? null : new ByteArrayOutputStream();
        OutputStream clientOut = resp.getOutputStream();
        byte[] buf = new byte[8192];
        int n;
        try {
            while ((n = upstream.read(buf)) != -1) {
                clientOut.write(buf, 0, n);
                clientOut.flush();
                if (sse) {
                    sseParser.push(new String(buf, 0, n, StandardCharsets.UTF_8));
                } else if (copy.size() < props.getMaxBodyBytes()) {
                    copy.write(buf, 0, n);
                }
            }
        } catch (Exception e) {
            ex.error = "upstream stream error: " + e.getMessage();
        } finally {
            upstream.close();
            conn.disconnect();
        }
        try {
            if (sse) {
                sseParser.flush();
                ex.response = agg.result();
            } else {
                ex.response = parser.parseResponse(new String(copy.toByteArray(), StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            NormalizedResponse r = new NormalizedResponse();
            r.parseError = e.getMessage();
            ex.response = r;
        }
        ex.durationMs = System.currentTimeMillis() - started;
        recorder.record(ex);
    }

    private Exchange newResilientExchange(HttpServletRequest req, String wire, String url, byte[] bodyBytes, long started) {
        WireParser parser = parsers.get(wire);
        Exchange ex = new Exchange();
        ex.id = UUID.randomUUID().toString();
        ex.ts = Instant.ofEpochMilli(started).toString();
        ex.wire = wire;
        ex.method = req.getMethod();
        ex.url = url;
        ex.reqHeaders = collectRequestHeaders(req);
        ex.requestBodyRaw = truncate(bodyBytes);
        try {
            ex.request = parser.parseRequest(new String(bodyBytes, StandardCharsets.UTF_8));
        } catch (Exception e) {
            ex.request = null;
        }
        return ex;
    }

    // 转发请求头到候选 provider：去掉逐跳头；当候选自带 apiKey 时，丢掉客户端的鉴权头
    // 并换上该 provider 的凭证（Anthropic 用 x-api-key，OpenAI 用 Authorization: Bearer）。
    private void forwardResilientHeaders(HttpServletRequest req, HttpURLConnection conn, String wire, Provider cand) {
        boolean override = cand.apiKey != null;
        Enumeration<String> names = req.getHeaderNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            String lk = name.toLowerCase(Locale.ROOT);
            if (HOP_BY_HOP.contains(lk)) {
                continue;
            }
            if (override && (lk.equals("authorization") || lk.equals("x-api-key"))) {
                continue;
            }
            conn.setRequestProperty(name, req.getHeader(name));
        }
        if (override) {
            if ("anthropic".equals(wire)) {
                conn.setRequestProperty("x-api-key", cand.apiKey);
            } else {
                conn.setRequestProperty("Authorization", "Bearer " + cand.apiKey);
            }
        }
    }

    // 同 forwardResilientHeaders，但返回可变 map（供过滤器原地改写后再写到连接）。
    private Map<String, String> buildResilientHeaders(HttpServletRequest req, String wire, Provider cand) {
        boolean override = cand.apiKey != null;
        Map<String, String> out = new LinkedHashMap<>();
        Enumeration<String> names = req.getHeaderNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            String lk = name.toLowerCase(Locale.ROOT);
            if (HOP_BY_HOP.contains(lk)) {
                continue;
            }
            if (override && (lk.equals("authorization") || lk.equals("x-api-key"))) {
                continue;
            }
            out.put(name, req.getHeader(name));
        }
        if (override) {
            if ("anthropic".equals(wire)) {
                out.put("x-api-key", cand.apiKey);
            } else {
                out.put("Authorization", "Bearer " + cand.apiKey);
            }
        }
        return out;
    }

    private Map<String, String> collectResponseHeaders(HttpURLConnection conn) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : conn.getHeaderFields().entrySet()) {
            String name = e.getKey();
            if (name == null) {
                continue;
            }
            if (HOP_BY_HOP.contains(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            out.put(name, String.join(",", e.getValue()));
        }
        return out;
    }

    private String errorMessage(JsonNode obj, int status) {
        if (obj != null && obj.isObject()) {
            JsonNode err = obj.get("error");
            if (err != null && err.isObject() && err.hasNonNull("message")) {
                return err.get("message").asText();
            }
            if (obj.hasNonNull("message")) {
                return obj.get("message").asText();
            }
        }
        return "upstream " + status;
    }

    // =================================================================
    // 兼容路径：Anthropic /v1/messages -> OpenAI /v1/chat/completions
    //
    // 与透明 proxy() 不同，这里「双向改写」：
    //   请求：Translator.anthropicRequestToChat() -> POST /v1/chat/completions
    //   响应：OpenAI Chat（SSE 或 JSON）-> Anthropic 事件流回给客户端
    // 客户端（Claude Code）完全不知道厂商说的是另一种协议。
    // =================================================================
    private void handleAnthropicToChat(HttpServletRequest req, HttpServletResponse resp, byte[] reqBody, long started) throws Exception {
        JsonNode anthBody = treeOrEmpty(new String(reqBody, StandardCharsets.UTF_8));
        boolean wantStream = anthBody.path("stream").asBoolean(false);
        String originalModel = anthBody.path("model").asText(null);
        String displayModel = originalModel;
        Map<String, String> modelMap = translator.parseModelMap(props.resolveModelMapRaw());

        // 1) 翻译请求体并选定映射后的模型。
        Map<String, Object> chatBody = translator.anthropicRequestToChat(anthBody, modelMap);
        String mappedModel = String.valueOf(chatBody.get("model"));
        if (displayModel == null) {
            displayModel = mappedModel;
        }
        byte[] chatBytes = translator.toJson(chatBody).getBytes(StandardCharsets.UTF_8);

        // 2) 构造发往 OpenAI 风格厂商的上游请求。
        String url = props.getOpenaiUpstream() + "/v1/chat/completions";

        // 3) 记录这条 Exchange：请求按 Anthropic（客户端发来的）记录，附 translation 标记。
        Exchange ex = new Exchange();
        ex.id = UUID.randomUUID().toString();
        ex.ts = Instant.ofEpochMilli(started).toString();
        ex.wire = "anthropic";
        ex.method = req.getMethod();
        ex.url = url;
        ex.reqHeaders = collectRequestHeaders(req);
        ex.requestBodyRaw = truncate(reqBody);
        ex.request = parsers.get("anthropic").parseRequest(new String(reqBody, StandardCharsets.UTF_8));
        Map<String, Object> translation = new LinkedHashMap<>();
        translation.put("from", "anthropic");
        translation.put("to", "chat");
        translation.put("model", originalModel);
        translation.put("upstreamModel", mappedModel);
        ex.translation = translation;

        // 4) 打开上游连接并发出翻译后的请求（出站连接可选地走外部代理）。
        //    注意：兼容路径不做过滤器/工具名改写——请求体已被翻译成 chat 结构。
        HttpURLConnection conn = openConnection(url);
        conn.setInstanceFollowRedirects(false);
        conn.setRequestMethod("POST");
        forwardCompatHeaders(req, conn);  // 含 x-api-key -> Bearer 鉴权翻译
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setFixedLengthStreamingMode(chatBytes.length);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(chatBytes);
        }

        int status;
        try {
            status = conn.getResponseCode();
        } catch (Exception e) {
            ex.error = "upstream request error: " + e.getMessage();
            ex.durationMs = System.currentTimeMillis() - started;
            recorder.record(ex);
            resp.setStatus(502);
            resp.setContentType("application/json");
            resp.getWriter().write(translator.toJson(translator.chatErrorToAnthropic(null, "upstream request failed")));
            return;
        }
        ex.resStatus = status;
        ex.resHeaders = new LinkedHashMap<>();
        String contentType = conn.getContentType();
        boolean sse = contentType != null && contentType.contains("text/event-stream");

        InputStream upstream = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (upstream == null) {
            upstream = new ByteArrayInputStream(new byte[0]);
        }

        WireParser chatParser = parsers.get("chat");

        if (sse) {
            // ---- 流式翻译 ----
            resp.setStatus(status);
            resp.setContentType("text/event-stream; charset=utf-8");
            resp.setHeader("Cache-Control", "no-cache");
            final OutputStream clientOut = resp.getOutputStream();
            final Translator.ChatToAnthropicStream tstream = translator.newStream(displayModel);
            final StreamAggregator agg = chatParser.newAggregator();  // 仅供落盘归一化

            SseParser sseParser = new SseParser(evt -> {
                agg.feed(evt);  // 落盘用的归一化响应
                String raw = evt.data == null ? "" : evt.data.trim();
                if (raw.isEmpty() || "[DONE]".equals(raw)) {
                    return;
                }
                JsonNode data = treeOrNull(raw);
                if (data == null) {
                    return;
                }
                try {
                    for (String frame : tstream.feed(data)) {
                        clientOut.write(frame.getBytes(StandardCharsets.UTF_8));
                        clientOut.flush();
                    }
                } catch (IOException io) {
                    throw new UncheckedIOException(io);
                }
            });

            byte[] buf = new byte[8192];
            int n;
            try {
                while ((n = upstream.read(buf)) != -1) {
                    sseParser.push(new String(buf, 0, n, StandardCharsets.UTF_8));
                }
                sseParser.flush();
                for (String frame : tstream.end()) {
                    clientOut.write(frame.getBytes(StandardCharsets.UTF_8));
                    clientOut.flush();
                }
            } catch (Exception e) {
                ex.error = "upstream stream error: " + e.getMessage();
            } finally {
                upstream.close();
            }
            try {
                ex.response = agg.result();
            } catch (Exception e) {
                NormalizedResponse r = new NormalizedResponse();
                r.parseError = e.getMessage();
                ex.response = r;
            }
        } else {
            // ---- 缓冲（非 SSE）翻译 ----
            // 覆盖普通 JSON chat completion 以及错误体。若客户端要求流式（但厂商回了
            // 单段 JSON），我们仍合成 Anthropic 事件序列补还给它。
            ByteArrayOutputStream copy = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            try {
                while ((n = upstream.read(buf)) != -1) {
                    copy.write(buf, 0, n);
                }
            } finally {
                upstream.close();
            }
            String bodyText = new String(copy.toByteArray(), StandardCharsets.UTF_8);
            JsonNode obj = treeOrNull(bodyText);
            boolean isError = status >= 400 || (obj != null && obj.has("error"));

            OutputStream clientOut = resp.getOutputStream();
            if (isError) {
                Map<String, Object> anthErr = translator.chatErrorToAnthropic(obj, bodyText);
                if (wantStream) {
                    resp.setStatus(status);
                    resp.setContentType("text/event-stream; charset=utf-8");
                    clientOut.write(translator.anthropicErrorSse(obj, bodyText).getBytes(StandardCharsets.UTF_8));
                } else {
                    resp.setStatus(status);
                    resp.setContentType("application/json; charset=utf-8");
                    clientOut.write(translator.toJson(anthErr).getBytes(StandardCharsets.UTF_8));
                }
                Object errObj = ((Map<String, Object>) anthErr.get("error")).get("message");
                ex.error = errObj == null ? null : String.valueOf(errObj);
            } else {
                Map<String, Object> anthObj = translator.chatResponseToAnthropic(
                        obj != null ? obj : mapper.createObjectNode(), displayModel);
                if (wantStream) {
                    resp.setStatus(status);
                    resp.setContentType("text/event-stream; charset=utf-8");
                    resp.setHeader("Cache-Control", "no-cache");
                    for (String frame : translator.anthropicMessageToSse(anthObj)) {
                        clientOut.write(frame.getBytes(StandardCharsets.UTF_8));
                    }
                } else {
                    resp.setStatus(status);
                    resp.setContentType("application/json; charset=utf-8");
                    clientOut.write(translator.toJson(anthObj).getBytes(StandardCharsets.UTF_8));
                }
            }
            clientOut.flush();
            try {
                ex.response = chatParser.parseResponse(bodyText);
            } catch (Exception e) {
                NormalizedResponse r = new NormalizedResponse();
                r.parseError = e.getMessage();
                ex.response = r;
            }
        }

        ex.durationMs = System.currentTimeMillis() - started;
        recorder.record(ex);
    }

    // 兼容模式转发请求头：去掉逐跳头 + Anthropic 专属头，并把 Claude Code 的
    // x-api-key 翻译成 OpenAI 厂商期望的 Authorization: Bearer。
    private void forwardCompatHeaders(HttpServletRequest req, HttpURLConnection conn) {
        Enumeration<String> names = req.getHeaderNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            String lk = name.toLowerCase(Locale.ROOT);
            if (HOP_BY_HOP.contains(lk) || COMPAT_DROP.contains(lk)) {
                continue;
            }
            conn.setRequestProperty(name, req.getHeader(name));
        }
        String auth = req.getHeader("authorization");
        String apiKey = req.getHeader("x-api-key");
        if (auth != null) {
            conn.setRequestProperty("Authorization", auth);
        } else if (apiKey != null) {
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        }
    }

    private JsonNode treeOrNull(String body) {
        try {
            return mapper.readTree(body);
        } catch (Exception e) {
            return null;
        }
    }

    private JsonNode treeOrEmpty(String body) {
        JsonNode node = treeOrNull(body);
        return node != null ? node : mapper.createObjectNode();
    }

    // 整流读入为字节数组（兼容 JDK 8；InputStream.readAllBytes() 是 Java 9 才有）。
    private static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    // 落盘前对凭证脱敏：转发给上游的仍是真实 key，只有写入日志的副本被打码，
    // 因此 JSONL 文件里不会出现可用的 API key。
    private Map<String, String> collectRequestHeaders(HttpServletRequest req) {
        Map<String, String> out = new LinkedHashMap<>();
        Enumeration<String> names = req.getHeaderNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            String value = req.getHeader(name);
            String lk = name.toLowerCase(Locale.ROOT);
            if (props.isRedactAuth() && (lk.equals("authorization") || lk.equals("x-api-key") || lk.equals("api-key"))) {
                out.put(name, redact(value));
            } else {
                out.put(name, value);
            }
        }
        return out;
    }

    private void forwardRequestHeaders(HttpServletRequest req, HttpURLConnection conn) {
        Enumeration<String> names = req.getHeaderNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            if (HOP_BY_HOP.contains(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            conn.setRequestProperty(name, req.getHeader(name));
        }
    }

    /** 从请求构建可变的出站请求头 map（去掉逐跳头）；供过滤器原地改写后再写到连接。 */
    private Map<String, String> buildForwardHeaders(HttpServletRequest req) {
        Map<String, String> out = new LinkedHashMap<>();
        Enumeration<String> names = req.getHeaderNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            if (HOP_BY_HOP.contains(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            out.put(name, req.getHeader(name));
        }
        return out;
    }

    /** 把出站请求头 map 写到连接（content-length 由调用方按最终 body 另设）。 */
    private void writeHeaders(HttpURLConnection conn, Map<String, String> headers) {
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if ("content-length".equalsIgnoreCase(e.getKey())) {
                continue;
            }
            conn.setRequestProperty(e.getKey(), e.getValue());
        }
    }

    private Map<String, String> copyResponseHeaders(HttpURLConnection conn, HttpServletResponse resp) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : conn.getHeaderFields().entrySet()) {
            String name = e.getKey();
            if (name == null) {
                continue; // 状态行
            }
            if (HOP_BY_HOP.contains(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            String value = String.join(",", e.getValue());
            resp.setHeader(name, value);
            out.put(name, value);
        }
        return out;
    }

    private String truncate(byte[] body) {
        if (body.length <= props.getMaxBodyBytes()) {
            return new String(body, StandardCharsets.UTF_8);
        }
        return new String(body, 0, props.getMaxBodyBytes(), StandardCharsets.UTF_8)
                + "\n...[truncated " + (body.length - props.getMaxBodyBytes()) + " bytes]";
    }

    private String redact(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 12 ? "***" : s.substring(0, 6) + "..." + s.substring(s.length() - 4);
    }
}
