package com.practice.cliproxy.kernel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规范化 {@link Spec} -> sing-box 配置（{@code Map}，可由 Jackson 序列化为 JSON）。
 * Node {@code src/kernel/singbox.js} 的 Java 等价实现。产出最小配置：一个 loopback SOCKS
 * 入站 + 一个对应协议的出站。sing-box 额外支持 Hysteria2 和 TUIC（xray 不支持）。
 */
public final class SingboxConfig {

    private SingboxConfig() {
    }

    private static Map<String, Object> tlsBlock(Spec spec) {
        Spec.Tls tls = spec.tls;
        if (tls == null || !tls.enabled) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", true);
        if (!isEmpty(tls.serverName)) {
            out.put("server_name", tls.serverName);
        }
        if (tls.insecure) {
            out.put("insecure", true);
        }
        if (tls.alpn != null && !tls.alpn.isEmpty()) {
            out.put("alpn", tls.alpn);
        }
        if (!isEmpty(tls.fingerprint)) {
            Map<String, Object> utls = new LinkedHashMap<>();
            utls.put("enabled", true);
            utls.put("fingerprint", tls.fingerprint);
            out.put("utls", utls);
        }
        if (tls.reality != null) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("enabled", true);
            r.put("public_key", tls.reality.publicKey == null ? "" : tls.reality.publicKey);
            r.put("short_id", tls.reality.shortId == null ? "" : tls.reality.shortId);
            out.put("reality", r);
            if (!out.containsKey("utls")) {
                Map<String, Object> utls = new LinkedHashMap<>();
                utls.put("enabled", true);
                utls.put("fingerprint", isEmpty(tls.fingerprint) ? "chrome" : tls.fingerprint);
                out.put("utls", utls);
            }
        }
        return out;
    }

    private static Map<String, Object> transportBlock(Spec spec) {
        Spec.Transport tr = spec.transport;
        if (tr == null || tr.type.equals("tcp")) {
            return null;
        }
        Map<String, Object> t = new LinkedHashMap<>();
        if (tr.type.equals("ws")) {
            t.put("type", "ws");
            t.put("path", isEmpty(tr.path) ? "/" : tr.path);
            if (!isEmpty(tr.host)) {
                Map<String, Object> h = new LinkedHashMap<>();
                h.put("Host", tr.host);
                t.put("headers", h);
            }
            return t;
        }
        if (tr.type.equals("grpc")) {
            t.put("type", "grpc");
            t.put("service_name", tr.serviceName == null ? "" : tr.serviceName);
            return t;
        }
        if (tr.type.equals("http")) {
            t.put("type", "http");
            t.put("path", isEmpty(tr.path) ? "/" : tr.path);
            if (!isEmpty(tr.host)) {
                t.put("host", new ArrayList<>(Arrays.asList(tr.host)));
            }
            return t;
        }
        if (tr.type.equals("httpupgrade")) {
            t.put("type", "httpupgrade");
            t.put("path", isEmpty(tr.path) ? "/" : tr.path);
            if (!isEmpty(tr.host)) {
                t.put("host", tr.host);
            }
            return t;
        }
        return null;
    }

    private static Map<String, Object> outboundFor(Spec spec) {
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("type", spec.type);
        base.put("tag", "proxy");
        base.put("server", spec.server);
        base.put("server_port", spec.port);
        Map<String, Object> tls = tlsBlock(spec);
        Map<String, Object> transport = transportBlock(spec);
        switch (spec.type) {
            case "vmess":
                base.put("uuid", spec.uuid);
                base.put("security", isEmpty(spec.security) ? "auto" : spec.security);
                if (spec.alterId != 0) {
                    base.put("alter_id", spec.alterId);
                }
                if (tls != null) {
                    base.put("tls", tls);
                }
                if (transport != null) {
                    base.put("transport", transport);
                }
                return base;
            case "vless":
                base.put("uuid", spec.uuid);
                if (!isEmpty(spec.flow)) {
                    base.put("flow", spec.flow);
                }
                if (tls != null) {
                    base.put("tls", tls);
                }
                if (transport != null) {
                    base.put("transport", transport);
                }
                return base;
            case "trojan":
                base.put("password", spec.password);
                if (tls != null) {
                    base.put("tls", tls);
                }
                if (transport != null) {
                    base.put("transport", transport);
                }
                return base;
            case "shadowsocks":
                base.put("method", spec.method);
                base.put("password", spec.password);
                return base;
            case "hysteria2":
                base.put("password", spec.password);
                if (spec.obfs != null) {
                    Map<String, Object> obfs = new LinkedHashMap<>();
                    obfs.put("type", spec.obfs.type);
                    obfs.put("password", spec.obfs.password);
                    base.put("obfs", obfs);
                }
                base.put("tls", tls != null ? tls : defaultTls(spec));
                return base;
            case "tuic":
                base.put("uuid", spec.uuid);
                base.put("password", spec.password);
                if (!isEmpty(spec.congestionControl)) {
                    base.put("congestion_control", spec.congestionControl);
                }
                if (!isEmpty(spec.udpRelayMode)) {
                    base.put("udp_relay_mode", spec.udpRelayMode);
                }
                base.put("tls", tls != null ? tls : defaultTls(spec));
                return base;
            default:
                throw new IllegalArgumentException("sing-box: unsupported outbound protocol: " + spec.type);
        }
    }

    private static Map<String, Object> defaultTls(Spec spec) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("enabled", true);
        t.put("server_name", spec.server);
        return t;
    }

    /** 构建完整 sing-box 配置；socksPort 是 loopback SOCKS5 入站端口。 */
    public static Map<String, Object> build(Spec spec, int socksPort) {
        return build(spec, socksPort, "127.0.0.1");
    }

    public static Map<String, Object> build(Spec spec, int socksPort, String listen) {
        Map<String, Object> inbound = new LinkedHashMap<>();
        inbound.put("type", "socks");
        inbound.put("tag", "socks-in");
        inbound.put("listen", listen);
        inbound.put("listen_port", socksPort);

        Map<String, Object> direct = new LinkedHashMap<>();
        direct.put("type", "direct");
        direct.put("tag", "direct");

        Map<String, Object> log = new LinkedHashMap<>();
        log.put("level", "warn");

        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("log", log);
        cfg.put("inbounds", new ArrayList<>(Arrays.asList(inbound)));
        List<Object> outbounds = new ArrayList<>();
        outbounds.add(outboundFor(spec));
        outbounds.add(direct);
        cfg.put("outbounds", outbounds);
        return cfg;
    }

    private static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }
}
