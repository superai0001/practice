package com.practice.cliproxy.outbound;

import com.practice.cliproxy.kernel.Kernel;
import com.practice.cliproxy.kernel.ShareLink;

import java.io.IOException;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.URI;
import java.util.Locale;

/**
 * 出站代理（出站代理）—— opt-in。让代理发往「上游」的连接走一个外部 HTTP/HTTPS 或
 * SOCKS5 代理（常见于只允许经企业代理出网的内网）。Node {@code src/outbound.js} /
 * Python {@code outbound.py} 的等价实现；区别是 Java 直接用内置 {@link java.net.Proxy}
 * （HttpURLConnection 会自动对 https 上游做 CONNECT 隧道、对 socks 走 TCP 转发），
 * 无需自己实现握手。
 *
 * <p>用单个 URL 配置，例如：
 * <pre>
 *   UPSTREAM_PROXY=http://user:pass@proxy.corp:8080
 *   UPSTREAM_PROXY=socks5://10.0.0.1:1080
 * </pre>
 * 未配置时直连（默认行为不变）。
 */
public final class OutboundProxy {

    public final String kind; // http | https | socks5
    public final String host;
    public final int port;
    public final String username;
    public final String password;
    private final Proxy javaProxy;
    /** 非 null 时表示出站经由一个本地 xray/sing-box 内核子进程（高级协议）。 */
    private Kernel kernel;

    private OutboundProxy(String kind, String host, int port, String username, String password) {
        this.kind = kind;
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        Proxy.Type type = "socks5".equals(kind) ? Proxy.Type.SOCKS : Proxy.Type.HTTP;
        this.javaProxy = new Proxy(type, new InetSocketAddress(host, port));
    }

    /**
     * 解析代理 URL；无/无效返回 null。支持 http、https、socks/socks5/socks5h（均按 SOCKS5）。
     */
    public static OutboundProxy create(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        URI u;
        try {
            u = new URI(raw.trim());
        } catch (Exception e) {
            return null;
        }
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        String kind;
        if ("http".equals(scheme)) {
            kind = "http";
        } else if ("https".equals(scheme)) {
            kind = "https";
        } else if ("socks".equals(scheme) || "socks5".equals(scheme) || "socks5h".equals(scheme)) {
            kind = "socks5";
        } else {
            return null;
        }
        String host = u.getHost();
        if (host == null || host.isEmpty()) {
            return null;
        }
        int defaultPort = "http".equals(kind) ? 80 : "https".equals(kind) ? 443 : 1080;
        int port = u.getPort() > 0 ? u.getPort() : defaultPort;
        String username = "";
        String password = "";
        String userInfo = u.getUserInfo();
        if (userInfo != null && !userInfo.isEmpty()) {
            int idx = userInfo.indexOf(':');
            if (idx >= 0) {
                username = decode(userInfo.substring(0, idx));
                password = decode(userInfo.substring(idx + 1));
            } else {
                username = decode(userInfo);
            }
        }
        OutboundProxy proxy = new OutboundProxy(kind, host, port, username, password);
        proxy.installAuthenticatorIfNeeded();
        return proxy;
    }

    /** 该链接是否为需要内核的高级协议（vmess/vless/trojan/ss/hysteria2/tuic）。 */
    public static boolean isKernelScheme(String raw) {
        return ShareLink.isKernelScheme(raw);
    }

    /**
     * 在 {@link #create(String)} 基础上支持高级协议：当 {@code raw} 是高级协议分享链接
     * （或提供了 {@code kernelOpts.configPath}）时，拉起一个本地 xray/sing-box 内核子进程，
     * 暴露 loopback SOCKS5，并返回一个指向它的 socks5 出站。普通 http/socks 链接走原逻辑。
     *
     * @param raw        UPSTREAM_PROXY（普通代理 URL 或高级协议分享链接）
     * @param kernelOpts 内核选项（kernel/xrayBin/singboxBin/configPath/socksPort），可为 null
     */
    public static OutboundProxy create(String raw, Kernel.Options kernelOpts) {
        Kernel.Options o = kernelOpts != null ? kernelOpts : new Kernel.Options();
        boolean useKernel = ShareLink.isKernelScheme(raw)
                || (o.configPath != null && !o.configPath.isEmpty());
        if (!useKernel) {
            return create(raw);
        }
        o.link = ShareLink.isKernelScheme(raw) ? raw : null;
        Kernel kernel = Kernel.create(o);
        int socksPort;
        try {
            socksPort = kernel.start();
        } catch (IOException e) {
            throw new RuntimeException("failed to start outbound kernel: " + e.getMessage(), e);
        }
        OutboundProxy proxy = new OutboundProxy("socks5", "127.0.0.1", socksPort, "", "");
        proxy.kernel = kernel;
        return proxy;
    }

    /** 停止内核子进程（若有）。普通代理为 no-op。 */
    public void stop() {
        if (kernel != null) {
            kernel.stop();
        }
    }

    private static String decode(String s) {
        try {
            return java.net.URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    /** 供 {@code new URL(url).openConnection(proxy())} 使用。 */
    public Proxy proxy() {
        return javaProxy;
    }

    public String describe() {
        if (kernel != null) {
            return "kernel " + kernel.describe();
        }
        return kind + "://" + host + ":" + port + (hasAuth() ? " (auth)" : "");
    }

    public boolean hasAuth() {
        return (username != null && !username.isEmpty()) || (password != null && !password.isEmpty());
    }

    /**
     * 有凭证时为代理鉴权装一个进程级 Authenticator（只响应 PROXY 请求）。HttpURLConnection
     * 通过它给 HTTP/HTTPS 代理的 CONNECT 隧道或 SOCKS 提供用户名/口令。同一时刻只有一个
     * 出站代理配置，进程级安装可接受。
     */
    private void installAuthenticatorIfNeeded() {
        if (!hasAuth()) {
            return;
        }
        // 允许 Basic 走 https 隧道（默认被 JDK 禁用）。
        System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");
        final String u = username;
        final char[] p = (password == null ? "" : password).toCharArray();
        final String h = host;
        final int pt = port;
        Authenticator.setDefault(new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                if (getRequestorType() == RequestorType.PROXY
                        && getRequestingHost().equalsIgnoreCase(h)
                        && getRequestingPort() == pt) {
                    return new PasswordAuthentication(u, p);
                }
                return null;
            }
        });
    }
}
