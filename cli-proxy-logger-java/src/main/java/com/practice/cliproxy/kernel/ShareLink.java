package com.practice.cliproxy.kernel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 分享链接 -> 规范化 {@link Spec}（纯函数）。Node {@code src/kernel/links.js} /
 * Python {@code kernel/links.py} 的 Java 等价实现。支持：
 * vmess（v2rayN base64-JSON）、vless、trojan、ss（SIP002 / 全 base64）、
 * hysteria2（含 hy2 别名）、tuic。
 */
public final class ShareLink {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern SCHEME_RE = Pattern.compile("^([a-z0-9]+)://", Pattern.CASE_INSENSITIVE);

    /** 需要内核的高级协议 scheme（与普通 http/socks 区分）。 */
    public static final Set<String> ADVANCED_SCHEMES = new HashSet<>();
    /** 仅 sing-box 支持的协议（xray-core 无原生 hy2/tuic 出站）。 */
    public static final Set<String> SINGBOX_ONLY = new HashSet<>();

    static {
        ADVANCED_SCHEMES.add("vmess");
        ADVANCED_SCHEMES.add("vless");
        ADVANCED_SCHEMES.add("trojan");
        ADVANCED_SCHEMES.add("ss");
        ADVANCED_SCHEMES.add("shadowsocks");
        ADVANCED_SCHEMES.add("hysteria2");
        ADVANCED_SCHEMES.add("hy2");
        ADVANCED_SCHEMES.add("tuic");
        SINGBOX_ONLY.add("hysteria2");
        SINGBOX_ONLY.add("tuic");
    }

    private ShareLink() {
    }

    /** 是否为需要内核的高级协议链接（vs 普通 http/socks 代理）。 */
    public static boolean isKernelScheme(String raw) {
        if (raw == null) {
            return false;
        }
        Matcher m = SCHEME_RE.matcher(raw.trim());
        return m.find() && ADVANCED_SCHEMES.contains(m.group(1).toLowerCase(Locale.ROOT));
    }

    private static String b64decode(String s) {
        String txt = s.trim().replace('-', '+').replace('_', '/');
        int pad = (4 - txt.length() % 4) % 4;
        for (int i = 0; i < pad; i++) {
            txt += "=";
        }
        return new String(Base64.getDecoder().decode(txt), StandardCharsets.UTF_8);
    }

    private static String decode(String s) {
        if (s == null) {
            return "";
        }
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    /** 解析 query 字符串 -> Map（首值生效，已 URL 解码）。 */
    private static Map<String, String> parseQuery(String query) {
        Map<String, String> q = new LinkedHashMap<>();
        if (query == null || query.isEmpty()) {
            return q;
        }
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String k = eq >= 0 ? pair.substring(0, eq) : pair;
            String v = eq >= 0 ? pair.substring(eq + 1) : "";
            k = decode(k);
            if (!q.containsKey(k)) {
                q.put(k, decode(v));
            }
        }
        return q;
    }

    private static String get(Map<String, String> q, String key) {
        String v = q.get(key);
        return v == null ? "" : v;
    }

    private static Spec.Tls tlsFromQuery(Map<String, String> q, String fallbackSni) {
        String security = get(q, "security").toLowerCase(Locale.ROOT);
        String sni = !get(q, "sni").isEmpty() ? get(q, "sni")
                : (!get(q, "peer").isEmpty() ? get(q, "peer") : (fallbackSni == null ? "" : fallbackSni));
        List<String> alpn = splitCsv(get(q, "alpn"));
        String fp = get(q, "fp");
        boolean insecure = "1".equals(get(q, "allowInsecure")) || "1".equals(get(q, "insecure"))
                || "1".equals(get(q, "allow_insecure"));
        boolean isReality = "reality".equals(security);
        boolean enabled = "tls".equals(security) || "xtls".equals(security) || isReality;
        if (!enabled && sni.isEmpty() && alpn.isEmpty() && fp.isEmpty() && !insecure) {
            return null;
        }
        Spec.Tls tls = new Spec.Tls();
        tls.enabled = enabled || isReality;
        tls.serverName = sni;
        tls.insecure = insecure;
        tls.alpn = alpn;
        tls.fingerprint = fp;
        if (isReality) {
            Spec.Reality r = new Spec.Reality();
            r.publicKey = get(q, "pbk");
            r.shortId = get(q, "sid");
            r.spiderX = get(q, "spx");
            tls.reality = r;
        }
        return tls;
    }

    private static List<String> splitCsv(String s) {
        List<String> out = new ArrayList<>();
        if (s == null || s.isEmpty()) {
            return out;
        }
        for (String part : s.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    private static Spec.Transport transportFromQuery(Map<String, String> q) {
        String type = get(q, "type");
        if (type.isEmpty()) {
            type = "tcp";
        }
        type = type.toLowerCase(Locale.ROOT);
        if (type.equals("tcp") || type.equals("none") || type.equals("raw")) {
            return null;
        }
        String host = get(q, "host");
        String path = get(q, "path");
        String serviceName = !get(q, "serviceName").isEmpty() ? get(q, "serviceName")
                : (!get(q, "servicename").isEmpty() ? get(q, "servicename") : path);
        Spec.Transport tr = new Spec.Transport();
        tr.type = type.equals("h2") ? "http" : type;
        tr.path = path;
        tr.host = host;
        tr.serviceName = serviceName;
        if (!host.isEmpty()) {
            Map<String, String> h = new LinkedHashMap<>();
            h.put("Host", host);
            tr.headers = h;
        }
        return tr;
    }

    private static Spec parseVmess(String raw) {
        String body = raw.substring("vmess://".length());
        JsonNode obj;
        try {
            obj = MAPPER.readTree(b64decode(body));
        } catch (Exception e) {
            throw new IllegalArgumentException("vmess: not a base64-encoded JSON link", e);
        }
        String net = textOr(obj, "net", "tcp").toLowerCase(Locale.ROOT);
        boolean tlsOn = "tls".equalsIgnoreCase(textOr(obj, "tls", ""));
        String host = textOr(obj, "host", "");
        String path = textOr(obj, "path", "");

        Spec spec = new Spec();
        spec.type = "vmess";
        spec.server = textOr(obj, "add", null);
        spec.port = Integer.parseInt(textOr(obj, "port", "0"));
        spec.uuid = textOr(obj, "id", null);
        spec.alterId = parseIntSafe(textOr(obj, "aid", "0"));
        spec.security = !textOr(obj, "scy", "").isEmpty() ? textOr(obj, "scy", "")
                : (!textOr(obj, "security", "").isEmpty() ? textOr(obj, "security", "") : "auto");
        spec.name = textOr(obj, "ps", "");
        if (!net.equals("tcp") && !net.isEmpty()) {
            Spec.Transport tr = new Spec.Transport();
            tr.type = net.equals("h2") ? "http" : net;
            tr.path = path;
            tr.host = host;
            tr.serviceName = path;
            if (!host.isEmpty()) {
                Map<String, String> h = new LinkedHashMap<>();
                h.put("Host", host);
                tr.headers = h;
            }
            spec.transport = tr;
        }
        if (tlsOn) {
            Spec.Tls tls = new Spec.Tls();
            tls.enabled = true;
            tls.serverName = !textOr(obj, "sni", "").isEmpty() ? textOr(obj, "sni", "") : host;
            tls.insecure = false;
            tls.alpn = splitCsv(textOr(obj, "alpn", ""));
            tls.fingerprint = textOr(obj, "fp", "");
            spec.tls = tls;
        }
        return spec;
    }

    private static Spec parseUserHostLink(String raw, String type) {
        URI u = URI.create(raw);
        Map<String, String> q = parseQuery(u.getRawQuery());
        Spec spec = new Spec();
        spec.type = type;
        spec.server = decode(u.getHost());
        spec.port = u.getPort();
        spec.name = u.getRawFragment() != null ? decode(u.getRawFragment()) : "";
        spec.tls = tlsFromQuery(q, u.getHost());
        spec.transport = transportFromQuery(q);
        String userInfo = u.getRawUserInfo();
        if ("vless".equals(type)) {
            spec.uuid = decode(userInfo);
            spec.flow = get(q, "flow");
            spec.encryption = !get(q, "encryption").isEmpty() ? get(q, "encryption") : "none";
        } else if ("trojan".equals(type)) {
            spec.password = decode(userInfo);
        }
        return spec;
    }

    private static Spec parseShadowsocks(String raw) {
        int hashIdx = raw.indexOf('#');
        String name = hashIdx >= 0 ? decode(raw.substring(hashIdx + 1)) : "";
        String body = (hashIdx >= 0 ? raw.substring(0, hashIdx) : raw).substring("ss://".length());
        int qIdx = body.indexOf('?');
        String query = "";
        if (qIdx >= 0) {
            query = body.substring(qIdx + 1);
            body = body.substring(0, qIdx);
        }

        String decoded;
        String hostport;
        if (body.contains("@")) {
            int at = body.lastIndexOf('@');
            String userinfo = body.substring(0, at);
            hostport = body.substring(at + 1);
            if (userinfo.contains(":") && !userinfo.matches("[A-Za-z0-9+/_=-]+")) {
                decoded = userinfo;
            } else {
                decoded = b64decode(userinfo);
            }
        } else {
            String decodedAll = b64decode(body);
            int at = decodedAll.lastIndexOf('@');
            decoded = decodedAll.substring(0, at);
            hostport = decodedAll.substring(at + 1);
        }
        int ci = decoded.indexOf(':');
        String method = decoded.substring(0, ci);
        String password = decoded.substring(ci + 1);
        int hp = hostport.lastIndexOf(':');
        String server = hostport.substring(0, hp);
        int port = Integer.parseInt(hostport.substring(hp + 1));

        Map<String, String> q = parseQuery(query);
        Spec spec = new Spec();
        spec.type = "shadowsocks";
        spec.server = server;
        spec.port = port;
        spec.method = method;
        spec.password = password;
        spec.plugin = get(q, "plugin");
        spec.name = name;
        return spec;
    }

    private static Spec parseHysteria2(String raw) {
        URI u = URI.create(raw.replaceFirst("^hy2://", "hysteria2://"));
        Map<String, String> q = parseQuery(u.getRawQuery());
        Spec.Tls tls = tlsFromQuery(q, u.getHost());
        if (tls == null) {
            tls = new Spec.Tls();
            tls.serverName = u.getHost() == null ? "" : u.getHost();
        }
        tls.enabled = true;

        Spec spec = new Spec();
        spec.type = "hysteria2";
        spec.server = decode(u.getHost());
        spec.port = u.getPort() > 0 ? u.getPort() : 443;
        spec.password = decode(u.getRawUserInfo());
        spec.tls = tls;
        spec.name = u.getRawFragment() != null ? decode(u.getRawFragment()) : "";
        if (!get(q, "obfs").isEmpty()) {
            Spec.Obfs obfs = new Spec.Obfs();
            obfs.type = get(q, "obfs");
            obfs.password = !get(q, "obfs-password").isEmpty() ? get(q, "obfs-password") : get(q, "obfs_password");
            spec.obfs = obfs;
        }
        return spec;
    }

    private static Spec parseTuic(String raw) {
        URI u = URI.create(raw);
        Map<String, String> q = parseQuery(u.getRawQuery());
        Spec.Tls tls = tlsFromQuery(q, u.getHost());
        if (tls == null) {
            tls = new Spec.Tls();
            tls.serverName = u.getHost() == null ? "" : u.getHost();
        }
        tls.enabled = true;

        String uuid = "";
        String password = "";
        String userInfo = u.getRawUserInfo();
        if (userInfo != null) {
            int idx = userInfo.indexOf(':');
            if (idx >= 0) {
                uuid = decode(userInfo.substring(0, idx));
                password = decode(userInfo.substring(idx + 1));
            } else {
                uuid = decode(userInfo);
            }
        }

        Spec spec = new Spec();
        spec.type = "tuic";
        spec.server = decode(u.getHost());
        spec.port = u.getPort() > 0 ? u.getPort() : 443;
        spec.uuid = uuid;
        spec.password = password;
        spec.congestionControl = !get(q, "congestion_control").isEmpty()
                ? get(q, "congestion_control") : get(q, "congestion");
        spec.udpRelayMode = get(q, "udp_relay_mode");
        spec.alpn = splitCsv(get(q, "alpn"));
        spec.tls = tls;
        spec.name = u.getRawFragment() != null ? decode(u.getRawFragment()) : "";
        return spec;
    }

    private static String textOr(JsonNode obj, String field, String fallback) {
        JsonNode n = obj.get(field);
        return (n == null || n.isNull()) ? fallback : n.asText();
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /** 把分享链接解析成规范化 spec。非法输入抛 {@link IllegalArgumentException}。 */
    public static Spec parse(String raw) {
        if (raw == null || raw.isEmpty()) {
            throw new IllegalArgumentException("empty link");
        }
        String link = raw.trim();
        Matcher m = SCHEME_RE.matcher(link);
        if (!m.find()) {
            throw new IllegalArgumentException("link missing scheme");
        }
        String scheme = m.group(1).toLowerCase(Locale.ROOT);
        switch (scheme) {
            case "vmess":
                return parseVmess(link);
            case "vless":
                return parseUserHostLink(link, "vless");
            case "trojan":
                return parseUserHostLink(link, "trojan");
            case "ss":
            case "shadowsocks":
                return parseShadowsocks(link);
            case "hysteria2":
            case "hy2":
                return parseHysteria2(link);
            case "tuic":
                return parseTuic(link);
            default:
                throw new IllegalArgumentException("unsupported link scheme: " + scheme);
        }
    }
}
