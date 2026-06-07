package com.practice.cliproxy.kernel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规范化 {@link Spec} -> Xray-core 配置（{@code Map}，可由 Jackson 序列化为 JSON）。
 * Node {@code src/kernel/xray.js} 的 Java 等价实现。产出最小配置：一个 loopback SOCKS
 * 入站 + 一个对应协议的出站。Xray-core 无原生 Hysteria2/TUIC 出站（见 SINGBOX_ONLY）。
 */
public final class XrayConfig {

    private XrayConfig() {
    }

    private static Map<String, Object> streamSettings(Spec spec) {
        Spec.Tls tls = spec.tls;
        Spec.Transport tr = spec.transport;
        String network = tr != null ? tr.type : "tcp";
        if (tls == null && (tr == null || network.equals("tcp"))) {
            return null;
        }
        Map<String, Object> ss = new LinkedHashMap<>();
        ss.put("network", network.equals("http") ? "h2" : network);
        if (tls != null) {
            if (tls.reality != null) {
                ss.put("security", "reality");
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("serverName", tls.serverName == null ? "" : tls.serverName);
                r.put("publicKey", tls.reality.publicKey == null ? "" : tls.reality.publicKey);
                r.put("shortId", tls.reality.shortId == null ? "" : tls.reality.shortId);
                r.put("spiderX", tls.reality.spiderX == null ? "" : tls.reality.spiderX);
                r.put("fingerprint", isEmpty(tls.fingerprint) ? "chrome" : tls.fingerprint);
                ss.put("realitySettings", r);
            } else {
                ss.put("security", "tls");
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("serverName", tls.serverName == null ? "" : tls.serverName);
                t.put("allowInsecure", tls.insecure);
                if (tls.alpn != null && !tls.alpn.isEmpty()) {
                    t.put("alpn", tls.alpn);
                }
                if (!isEmpty(tls.fingerprint)) {
                    t.put("fingerprint", tls.fingerprint);
                }
                ss.put("tlsSettings", t);
            }
        }
        if (tr != null) {
            if (network.equals("ws")) {
                Map<String, Object> ws = new LinkedHashMap<>();
                ws.put("path", isEmpty(tr.path) ? "/" : tr.path);
                if (!isEmpty(tr.host)) {
                    ws.put("host", tr.host);
                }
                ss.put("wsSettings", ws);
            } else if (network.equals("grpc")) {
                Map<String, Object> g = new LinkedHashMap<>();
                g.put("serviceName", tr.serviceName == null ? "" : tr.serviceName);
                ss.put("grpcSettings", g);
            } else if (network.equals("http")) {
                Map<String, Object> h = new LinkedHashMap<>();
                h.put("path", isEmpty(tr.path) ? "/" : tr.path);
                h.put("host", isEmpty(tr.host) ? new ArrayList<String>() : new ArrayList<>(Arrays.asList(tr.host)));
                ss.put("httpSettings", h);
            } else if (network.equals("httpupgrade")) {
                Map<String, Object> h = new LinkedHashMap<>();
                h.put("path", isEmpty(tr.path) ? "/" : tr.path);
                h.put("host", tr.host == null ? "" : tr.host);
                ss.put("httpupgradeSettings", h);
            }
        }
        return ss;
    }

    private static Map<String, Object> outboundSettings(Spec spec) {
        Map<String, Object> out = new LinkedHashMap<>();
        switch (spec.type) {
            case "vmess": {
                out.put("protocol", "vmess");
                Map<String, Object> user = new LinkedHashMap<>();
                user.put("id", spec.uuid);
                user.put("alterId", spec.alterId);
                user.put("security", isEmpty(spec.security) ? "auto" : spec.security);
                out.put("settings", vnext(spec, user));
                return out;
            }
            case "vless": {
                out.put("protocol", "vless");
                Map<String, Object> user = new LinkedHashMap<>();
                user.put("id", spec.uuid);
                user.put("encryption", isEmpty(spec.encryption) ? "none" : spec.encryption);
                if (!isEmpty(spec.flow)) {
                    user.put("flow", spec.flow);
                }
                out.put("settings", vnext(spec, user));
                return out;
            }
            case "trojan": {
                out.put("protocol", "trojan");
                Map<String, Object> server = new LinkedHashMap<>();
                server.put("address", spec.server);
                server.put("port", spec.port);
                server.put("password", spec.password);
                if (!isEmpty(spec.flow)) {
                    server.put("flow", spec.flow);
                }
                Map<String, Object> settings = new LinkedHashMap<>();
                settings.put("servers", new ArrayList<>(Arrays.asList(server)));
                out.put("settings", settings);
                return out;
            }
            case "shadowsocks": {
                out.put("protocol", "shadowsocks");
                Map<String, Object> server = new LinkedHashMap<>();
                server.put("address", spec.server);
                server.put("port", spec.port);
                server.put("method", spec.method);
                server.put("password", spec.password);
                Map<String, Object> settings = new LinkedHashMap<>();
                settings.put("servers", new ArrayList<>(Arrays.asList(server)));
                out.put("settings", settings);
                return out;
            }
            default:
                throw new IllegalArgumentException("xray: unsupported outbound protocol: " + spec.type);
        }
    }

    private static Map<String, Object> vnext(Spec spec, Map<String, Object> user) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("address", spec.server);
        node.put("port", spec.port);
        node.put("users", new ArrayList<>(Arrays.asList(user)));
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("vnext", new ArrayList<>(Arrays.asList(node)));
        return settings;
    }

    /** 构建完整 Xray 配置；socksPort 是 loopback SOCKS5 入站端口。 */
    public static Map<String, Object> build(Spec spec, int socksPort) {
        return build(spec, socksPort, "127.0.0.1");
    }

    public static Map<String, Object> build(Spec spec, int socksPort, String listen) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tag", "proxy");
        out.putAll(outboundSettings(spec));
        Map<String, Object> ss = streamSettings(spec);
        if (ss != null) {
            out.put("streamSettings", ss);
        }

        Map<String, Object> inbound = new LinkedHashMap<>();
        inbound.put("tag", "socks-in");
        inbound.put("listen", listen);
        inbound.put("port", socksPort);
        inbound.put("protocol", "socks");
        Map<String, Object> inSettings = new LinkedHashMap<>();
        inSettings.put("auth", "noauth");
        inSettings.put("udp", true);
        inbound.put("settings", inSettings);

        Map<String, Object> direct = new LinkedHashMap<>();
        direct.put("tag", "direct");
        direct.put("protocol", "freedom");

        Map<String, Object> log = new LinkedHashMap<>();
        log.put("loglevel", "warning");

        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("log", log);
        cfg.put("inbounds", new ArrayList<>(Arrays.asList(inbound)));
        List<Object> outbounds = new ArrayList<>();
        outbounds.add(out);
        outbounds.add(direct);
        cfg.put("outbounds", outbounds);
        return cfg;
    }

    private static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }
}
