package com.practice.cliproxy.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 代理配置（前缀 proxy.*，见 application.yml）。
 */
@Component
@ConfigurationProperties(prefix = "proxy")
public class ProxyProperties {

    /** Anthropic 上游（Claude Code）。 */
    private String anthropicUpstream = "https://api.anthropic.com";
    /** OpenAI 上游（Codex）。 */
    private String openaiUpstream = "https://api.openai.com";
    /** JSONL 日志目录。 */
    private String logDir = "./logs";
    /** 落盘时是否对 x-api-key / authorization 脱敏。 */
    private boolean redactAuth = true;
    /** 单条 body 落盘上限（字节），超出截断。 */
    private int maxBodyBytes = 2_000_000;

    /**
     * 协议翻译开关。设为 "chat" 时，把进来的 Anthropic /v1/messages 翻译成 OpenAI
     * /v1/chat/completions 发往 openaiUpstream（见 Translator）。空=透明直通（默认）。
     * 可用 proxy.anthropic-compat（即 PROXY_ANTHROPIC_COMPAT）配置；为与 Node/Python
     * 版统一，未配置时回退读取裸环境变量 ANTHROPIC_COMPAT。
     */
    private String anthropicCompat;
    /** 模型映射（JSON 对象 {"a":"b"} 或逗号分隔 "a=b,c=d"）。回退读环境变量 MODEL_MAP。 */
    private String modelMap;
    /** 模型映射文件路径（JSON）。回退读环境变量 MODEL_MAP_FILE。 */
    private String modelMapFile;

    // ---- 弹性（全部 opt-in；不配置时默认行为完全不变）-------------------------
    /** 故障转移供应商池（JSON 数组）。回退读环境变量 PROVIDERS。 */
    private String providers;
    /** 供应商池 JSON 文件路径。回退读环境变量 PROVIDERS_FILE。 */
    private String providersFile;
    /** 开启熔断器（配置了供应商池时自动开启）。回退读环境变量 BREAKER。 */
    private Boolean breaker;
    /** provider 打开熔断前的失败次数。回退读环境变量 BREAKER_FAILURES。 */
    private Integer breakerFailures;
    /** OPEN 状态冷却毫秒数。回退读环境变量 BREAKER_COOLDOWN_MS。 */
    private Long breakerCooldownMs;
    /** 并发 half-open 探测数。回退读环境变量 BREAKER_HALFOPEN_MAX。 */
    private Integer breakerHalfOpenMax;
    /** 触发故障转移的 HTTP 状态码逗号列表。回退读环境变量 FAILOVER_STATUSES。 */
    private String failoverStatuses;
    /** 开启 Anthropic thinking 整流。回退读环境变量 RECTIFY / RECTIFIER。 */
    private Boolean rectify;
    /** 关闭 signature 子规则（设 false）。回退读环境变量 RECTIFY_SIGNATURE（"0" 关）。 */
    private Boolean rectifySignature;
    /** 关闭 budget 子规则（设 false）。回退读环境变量 RECTIFY_BUDGET（"0" 关）。 */
    private Boolean rectifyBudget;

    // ---- 扩展三件套（全部 opt-in；不配置时默认行为完全不变）----------------------
    /** 开启工具名规范化。回退读环境变量 TOOL_NAME_CASE。 */
    private Boolean toolNameCase;
    /** 关闭请求侧工具名改写（设 false）。回退读 TOOL_NAME_REQUEST（"0" 关）。 */
    private Boolean toolNameRequest;
    /** 关闭响应侧工具名改写（设 false）。回退读 TOOL_NAME_RESPONSE（"0" 关）。 */
    private Boolean toolNameResponse;
    /** 关闭 tool_use.input 修复（设 false）。回退读 TOOL_NAME_REPAIR_INPUT（"0" 关）。 */
    private Boolean toolNameRepairInput;
    /** 工具名映射（JSON 对象 {"a":"B"}）。回退读环境变量 TOOL_NAME_MAP。 */
    private String toolNameMap;
    /** 工具名映射 JSON 文件路径。回退读环境变量 TOOL_NAME_MAP_FILE。 */
    private String toolNameMapFile;
    /** 请求过滤器/规则（JSON 数组）。回退读环境变量 FILTERS。 */
    private String filters;
    /** 请求过滤器 JSON 文件路径。回退读环境变量 FILTERS_FILE。 */
    private String filtersFile;
    /** 出站代理 URL（http/https/socks5）。回退读 UPSTREAM_PROXY / HTTPS_PROXY / HTTP_PROXY。 */
    private String upstreamProxy;

    // ---- 内核出站（opt-in；UPSTREAM_PROXY 为高级协议链接时启用）-------------------
    /** 内核选择：auto | xray | sing-box。回退读环境变量 PROXY_KERNEL。 */
    private String proxyKernel;
    /** 内核原生配置文件路径（绕过链接解析）。回退读 PROXY_KERNEL_CONFIG。 */
    private String proxyKernelConfig;
    /** xray 二进制路径。回退读环境变量 XRAY_BIN。 */
    private String xrayBin;
    /** sing-box 二进制路径。回退读环境变量 SING_BOX_BIN。 */
    private String singBoxBin;
    /** 固定本地 SOCKS5 端口（0=自动）。回退读 PROXY_KERNEL_SOCKS_PORT。 */
    private Integer proxyKernelSocksPort;

    public String getAnthropicUpstream() {
        return anthropicUpstream;
    }

    public void setAnthropicUpstream(String anthropicUpstream) {
        this.anthropicUpstream = anthropicUpstream;
    }

    public String getOpenaiUpstream() {
        return openaiUpstream;
    }

    public void setOpenaiUpstream(String openaiUpstream) {
        this.openaiUpstream = openaiUpstream;
    }

    public String getLogDir() {
        return logDir;
    }

    public void setLogDir(String logDir) {
        this.logDir = logDir;
    }

    public boolean isRedactAuth() {
        return redactAuth;
    }

    public void setRedactAuth(boolean redactAuth) {
        this.redactAuth = redactAuth;
    }

    public int getMaxBodyBytes() {
        return maxBodyBytes;
    }

    public void setMaxBodyBytes(int maxBodyBytes) {
        this.maxBodyBytes = maxBodyBytes;
    }

    public String getAnthropicCompat() {
        return anthropicCompat;
    }

    public void setAnthropicCompat(String anthropicCompat) {
        this.anthropicCompat = anthropicCompat;
    }

    public String getModelMap() {
        return modelMap;
    }

    public void setModelMap(String modelMap) {
        this.modelMap = modelMap;
    }

    public String getModelMapFile() {
        return modelMapFile;
    }

    public void setModelMapFile(String modelMapFile) {
        this.modelMapFile = modelMapFile;
    }

    /** 是否开启 Anthropic -> Chat 翻译。配置项优先，否则回退裸环境变量 ANTHROPIC_COMPAT。 */
    public boolean isAnthropicToChat() {
        String v = anthropicCompat;
        if (v == null || v.isEmpty()) {
            v = System.getenv("ANTHROPIC_COMPAT");
        }
        return v != null && "chat".equals(v.toLowerCase(Locale.ROOT));
    }

    /**
     * 解析出模型映射的「原始字符串」（交给 Translator.parseModelMap 解析）。
     * 优先级：proxy.model-map-file / MODEL_MAP_FILE 文件内容 &gt; proxy.model-map / MODEL_MAP。
     * 没有任何配置时返回 null。
     */
    public String resolveModelMapRaw() {
        String file = (modelMapFile != null && !modelMapFile.isEmpty()) ? modelMapFile : System.getenv("MODEL_MAP_FILE");
        if (file != null && !file.isEmpty()) {
            try {
                return new String(Files.readAllBytes(Paths.get(file)), StandardCharsets.UTF_8);
            } catch (IOException e) {
                System.err.println("[config] failed to read MODEL_MAP_FILE: " + e.getMessage());
            }
        }
        if (modelMap != null && !modelMap.isEmpty()) {
            return modelMap;
        }
        return System.getenv("MODEL_MAP");
    }

    // ---- 弹性配置的 getter/setter + 解析助手 ---------------------------------

    public String getProviders() {
        return providers;
    }

    public void setProviders(String providers) {
        this.providers = providers;
    }

    public String getProvidersFile() {
        return providersFile;
    }

    public void setProvidersFile(String providersFile) {
        this.providersFile = providersFile;
    }

    public Boolean getBreaker() {
        return breaker;
    }

    public void setBreaker(Boolean breaker) {
        this.breaker = breaker;
    }

    public Integer getBreakerFailures() {
        return breakerFailures;
    }

    public void setBreakerFailures(Integer breakerFailures) {
        this.breakerFailures = breakerFailures;
    }

    public Long getBreakerCooldownMs() {
        return breakerCooldownMs;
    }

    public void setBreakerCooldownMs(Long breakerCooldownMs) {
        this.breakerCooldownMs = breakerCooldownMs;
    }

    public Integer getBreakerHalfOpenMax() {
        return breakerHalfOpenMax;
    }

    public void setBreakerHalfOpenMax(Integer breakerHalfOpenMax) {
        this.breakerHalfOpenMax = breakerHalfOpenMax;
    }

    public String getFailoverStatuses() {
        return failoverStatuses;
    }

    public void setFailoverStatuses(String failoverStatuses) {
        this.failoverStatuses = failoverStatuses;
    }

    public Boolean getRectify() {
        return rectify;
    }

    public void setRectify(Boolean rectify) {
        this.rectify = rectify;
    }

    public Boolean getRectifySignature() {
        return rectifySignature;
    }

    public void setRectifySignature(Boolean rectifySignature) {
        this.rectifySignature = rectifySignature;
    }

    public Boolean getRectifyBudget() {
        return rectifyBudget;
    }

    public void setRectifyBudget(Boolean rectifyBudget) {
        this.rectifyBudget = rectifyBudget;
    }

    /** 解析出供应商池的「原始 JSON 字符串」（交给 Providers.parse 解析）。 */
    public String resolveProvidersRaw() {
        String file = (providersFile != null && !providersFile.isEmpty()) ? providersFile : System.getenv("PROVIDERS_FILE");
        if (file != null && !file.isEmpty()) {
            try {
                return new String(Files.readAllBytes(Paths.get(file)), StandardCharsets.UTF_8);
            } catch (IOException e) {
                System.err.println("[config] failed to read PROVIDERS_FILE: " + e.getMessage());
            }
        }
        if (providers != null && !providers.isEmpty()) {
            return providers;
        }
        return System.getenv("PROVIDERS");
    }

    private static boolean truthy(String v) {
        if (v == null) {
            return false;
        }
        String s = v.toLowerCase(Locale.ROOT);
        return s.equals("1") || s.equals("on") || s.equals("true") || s.equals("yes");
    }

    /** 是否开启熔断器。配置项优先，否则回退裸环境变量 BREAKER。 */
    public boolean isBreakerEnabled() {
        if (breaker != null) {
            return breaker;
        }
        return truthy(System.getenv("BREAKER"));
    }

    public int resolveBreakerFailures() {
        if (breakerFailures != null) {
            return breakerFailures;
        }
        return intEnv("BREAKER_FAILURES", 5);
    }

    public long resolveBreakerCooldownMs() {
        if (breakerCooldownMs != null) {
            return breakerCooldownMs;
        }
        return intEnv("BREAKER_COOLDOWN_MS", 30000);
    }

    public int resolveBreakerHalfOpenMax() {
        if (breakerHalfOpenMax != null) {
            return breakerHalfOpenMax;
        }
        return intEnv("BREAKER_HALFOPEN_MAX", 1);
    }

    /** 触发故障转移的状态码集合。配置项优先，否则回退环境变量，最后默认 429/5xx。 */
    public Set<Integer> resolveFailoverStatuses() {
        String raw = (failoverStatuses != null && !failoverStatuses.isEmpty())
                ? failoverStatuses : System.getenv("FAILOVER_STATUSES");
        Set<Integer> out = new LinkedHashSet<>();
        if (raw != null && !raw.trim().isEmpty()) {
            for (String tok : raw.split(",")) {
                try {
                    out.add(Integer.parseInt(tok.trim()));
                } catch (NumberFormatException ignore) {
                    // skip
                }
            }
        }
        if (out.isEmpty()) {
            out.addAll(Arrays.asList(429, 500, 502, 503, 504));
        }
        return out;
    }

    /** 是否开启 Anthropic thinking 整流。配置项优先，否则回退 RECTIFY / RECTIFIER。 */
    public boolean isRectifyEnabled() {
        if (rectify != null) {
            return rectify;
        }
        return truthy(System.getenv("RECTIFY")) || truthy(System.getenv("RECTIFIER"));
    }

    public boolean isRectifySignatureEnabled() {
        if (rectifySignature != null) {
            return rectifySignature;
        }
        return !"0".equals(System.getenv("RECTIFY_SIGNATURE"));
    }

    public boolean isRectifyBudgetEnabled() {
        if (rectifyBudget != null) {
            return rectifyBudget;
        }
        return !"0".equals(System.getenv("RECTIFY_BUDGET"));
    }

    private static int intEnv(String name, int fallback) {
        String v = System.getenv(name);
        if (v == null || v.isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // ---- 扩展三件套：getter/setter + 解析助手 --------------------------------

    public Boolean getToolNameCase() {
        return toolNameCase;
    }

    public void setToolNameCase(Boolean toolNameCase) {
        this.toolNameCase = toolNameCase;
    }

    public Boolean getToolNameRequest() {
        return toolNameRequest;
    }

    public void setToolNameRequest(Boolean toolNameRequest) {
        this.toolNameRequest = toolNameRequest;
    }

    public Boolean getToolNameResponse() {
        return toolNameResponse;
    }

    public void setToolNameResponse(Boolean toolNameResponse) {
        this.toolNameResponse = toolNameResponse;
    }

    public Boolean getToolNameRepairInput() {
        return toolNameRepairInput;
    }

    public void setToolNameRepairInput(Boolean toolNameRepairInput) {
        this.toolNameRepairInput = toolNameRepairInput;
    }

    public String getToolNameMap() {
        return toolNameMap;
    }

    public void setToolNameMap(String toolNameMap) {
        this.toolNameMap = toolNameMap;
    }

    public String getToolNameMapFile() {
        return toolNameMapFile;
    }

    public void setToolNameMapFile(String toolNameMapFile) {
        this.toolNameMapFile = toolNameMapFile;
    }

    public String getFilters() {
        return filters;
    }

    public void setFilters(String filters) {
        this.filters = filters;
    }

    public String getFiltersFile() {
        return filtersFile;
    }

    public void setFiltersFile(String filtersFile) {
        this.filtersFile = filtersFile;
    }

    public String getUpstreamProxy() {
        return upstreamProxy;
    }

    public void setUpstreamProxy(String upstreamProxy) {
        this.upstreamProxy = upstreamProxy;
    }

    /** 是否开启工具名规范化。配置项优先，否则回退裸环境变量 TOOL_NAME_CASE。 */
    public boolean isToolNameEnabled() {
        if (toolNameCase != null) {
            return toolNameCase;
        }
        return truthy(System.getenv("TOOL_NAME_CASE"));
    }

    /** 请求侧改写是否开启（默认开，仅当显式设 false / "0" 关）。 */
    public boolean isToolNameRequestEnabled() {
        if (toolNameRequest != null) {
            return toolNameRequest;
        }
        return !"0".equals(System.getenv("TOOL_NAME_REQUEST"));
    }

    /** 响应侧改写是否开启（默认开）。 */
    public boolean isToolNameResponseEnabled() {
        if (toolNameResponse != null) {
            return toolNameResponse;
        }
        return !"0".equals(System.getenv("TOOL_NAME_RESPONSE"));
    }

    /** tool_use.input 修复是否开启（默认开）。 */
    public boolean isToolNameRepairInputEnabled() {
        if (toolNameRepairInput != null) {
            return toolNameRepairInput;
        }
        return !"0".equals(System.getenv("TOOL_NAME_REPAIR_INPUT"));
    }

    /**
     * 工具名映射「原始 JSON 字符串」（用户自定义覆盖，交给 ToolNameTransformer 合并到内置表）。
     * 优先级：proxy.tool-name-map-file / TOOL_NAME_MAP_FILE 文件 &gt; proxy.tool-name-map / TOOL_NAME_MAP。
     */
    public String resolveToolNameMapRaw() {
        String file = (toolNameMapFile != null && !toolNameMapFile.isEmpty())
                ? toolNameMapFile : System.getenv("TOOL_NAME_MAP_FILE");
        if (file != null && !file.isEmpty()) {
            try {
                return new String(Files.readAllBytes(Paths.get(file)), StandardCharsets.UTF_8);
            } catch (IOException e) {
                System.err.println("[config] failed to read TOOL_NAME_MAP_FILE: " + e.getMessage());
            }
        }
        if (toolNameMap != null && !toolNameMap.isEmpty()) {
            return toolNameMap;
        }
        return System.getenv("TOOL_NAME_MAP");
    }

    /** 过滤器「原始 JSON 数组字符串」（交给 FilterEngine.parseFilters 解析）。 */
    public String resolveFiltersRaw() {
        String file = (filtersFile != null && !filtersFile.isEmpty())
                ? filtersFile : System.getenv("FILTERS_FILE");
        if (file != null && !file.isEmpty()) {
            try {
                return new String(Files.readAllBytes(Paths.get(file)), StandardCharsets.UTF_8);
            } catch (IOException e) {
                System.err.println("[config] failed to read FILTERS_FILE: " + e.getMessage());
            }
        }
        if (filters != null && !filters.isEmpty()) {
            return filters;
        }
        return System.getenv("FILTERS");
    }

    public String getProxyKernel() {
        return proxyKernel;
    }

    public void setProxyKernel(String proxyKernel) {
        this.proxyKernel = proxyKernel;
    }

    public String getProxyKernelConfig() {
        return proxyKernelConfig;
    }

    public void setProxyKernelConfig(String proxyKernelConfig) {
        this.proxyKernelConfig = proxyKernelConfig;
    }

    public String getXrayBin() {
        return xrayBin;
    }

    public void setXrayBin(String xrayBin) {
        this.xrayBin = xrayBin;
    }

    public String getSingBoxBin() {
        return singBoxBin;
    }

    public void setSingBoxBin(String singBoxBin) {
        this.singBoxBin = singBoxBin;
    }

    public Integer getProxyKernelSocksPort() {
        return proxyKernelSocksPort;
    }

    public void setProxyKernelSocksPort(Integer proxyKernelSocksPort) {
        this.proxyKernelSocksPort = proxyKernelSocksPort;
    }

    private static String firstNonEmpty(String configured, String env) {
        if (configured != null && !configured.isEmpty()) {
            return configured;
        }
        String v = System.getenv(env);
        return (v != null && !v.isEmpty()) ? v : "";
    }

    /** 内核选择：配置项优先，否则回退 PROXY_KERNEL，默认 "auto"。 */
    public String resolveProxyKernel() {
        String v = firstNonEmpty(proxyKernel, "PROXY_KERNEL");
        return v.isEmpty() ? "auto" : v;
    }

    /** 内核原生配置文件路径（配置项优先，否则回退 PROXY_KERNEL_CONFIG）。 */
    public String resolveProxyKernelConfig() {
        return firstNonEmpty(proxyKernelConfig, "PROXY_KERNEL_CONFIG");
    }

    /** xray 二进制路径（配置项优先，否则回退 XRAY_BIN）。 */
    public String resolveXrayBin() {
        return firstNonEmpty(xrayBin, "XRAY_BIN");
    }

    /** sing-box 二进制路径（配置项优先，否则回退 SING_BOX_BIN）。 */
    public String resolveSingBoxBin() {
        return firstNonEmpty(singBoxBin, "SING_BOX_BIN");
    }

    /** 固定本地 SOCKS5 端口（配置项优先，否则回退 PROXY_KERNEL_SOCKS_PORT，默认 0）。 */
    public int resolveProxyKernelSocksPort() {
        if (proxyKernelSocksPort != null) {
            return proxyKernelSocksPort;
        }
        String v = System.getenv("PROXY_KERNEL_SOCKS_PORT");
        if (v != null && !v.isEmpty()) {
            try {
                return Integer.parseInt(v.trim());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    /** 出站代理 URL。配置项优先，否则回退 UPSTREAM_PROXY / HTTPS_PROXY / HTTP_PROXY（含小写）。 */
    public String resolveUpstreamProxyUrl() {
        if (upstreamProxy != null && !upstreamProxy.isEmpty()) {
            return upstreamProxy;
        }
        String[] names = {"UPSTREAM_PROXY", "HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy"};
        for (String n : names) {
            String v = System.getenv(n);
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return null;
    }
}
