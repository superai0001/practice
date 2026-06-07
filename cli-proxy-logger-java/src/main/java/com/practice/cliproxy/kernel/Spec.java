package com.practice.cliproxy.kernel;

import java.util.List;
import java.util.Map;

/**
 * 规范化出站节点描述（kernel-agnostic）。由 {@link ShareLink} 从分享链接解析得到，
 * 再由 {@link XrayConfig} / {@link SingboxConfig} 渲染成各内核的原生配置。
 * 对应 Node {@code src/kernel/links.js} 的 spec 对象。
 */
public final class Spec {
    public String type;       // vmess | vless | trojan | shadowsocks | hysteria2 | tuic
    public String tag = "proxy";
    public String server;
    public int port;
    public String name = "";

    // 协议相关字段（按需填充）
    public String uuid;
    public String security;      // vmess
    public int alterId;          // vmess
    public String flow;          // vless / trojan
    public String encryption;    // vless
    public String password;      // trojan / shadowsocks / hysteria2 / tuic
    public String method;        // shadowsocks
    public String plugin = "";   // shadowsocks
    public String congestionControl; // tuic
    public String udpRelayMode;       // tuic
    public List<String> alpn;         // tuic

    public Tls tls;
    public Transport transport;
    public Obfs obfs;            // hysteria2

    public static final class Tls {
        public boolean enabled;
        public boolean insecure;
        public String serverName = "";
        public String fingerprint = "";
        public List<String> alpn;
        public Reality reality;
    }

    public static final class Reality {
        public String publicKey = "";
        public String shortId = "";
        public String spiderX = "";
    }

    public static final class Transport {
        public String type;          // ws | grpc | http | httpupgrade
        public String path = "";
        public String host = "";
        public String serviceName = "";
        public Map<String, String> headers;
    }

    public static final class Obfs {
        public String type;
        public String password = "";
    }
}
