package com.practice.cliproxy.kernel;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 内核管理器：把 xray-core / sing-box 作为本地子进程拉起，暴露一个 loopback SOCKS5
 * 入站，让现有出站 SOCKS5 隧道经由高级协议（VMess/VLESS/Trojan/Shadowsocks/
 * Hysteria2/TUIC）出网。零三方依赖——直接 shell out 到官方二进制。
 * Node {@code src/kernel/index.js} / Python {@code kernel/manager.py} 的 Java 等价实现。
 */
public final class Kernel {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final boolean IS_WIN =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    /** 构造选项（与 Node/Python 的 kernelOpts 对齐）。 */
    public static final class Options {
        public String link;          // 分享链接（与 configPath 二选一）
        public String configPath;    // 内核原生配置文件（与 link 二选一）
        public String kernel = "auto"; // auto | xray | sing-box
        public String xrayBin = "";
        public String singboxBin = "";
        public int socksPort = 0;     // 0 = 自动选空闲端口
        public long readyTimeoutMs = 10_000L;
        public Consumer<String> logger;
    }

    private final Options opts;
    private final Consumer<String> logger;
    private final String kind;       // xray | sing-box
    private final String bin;
    private Spec spec;
    private Map<String, Object> nativeConfig;
    private int socksPort;
    private Process child;
    private File configDir;
    private volatile boolean stopped;

    private Kernel(Options o) {
        this.opts = o;
        this.logger = o.logger != null ? o.logger : (line -> System.err.println("[kernel] " + line));
        if (notEmpty(o.configPath)) {
            try {
                this.nativeConfig = MAPPER.readValue(new File(o.configPath), Map.class);
            } catch (IOException e) {
                throw new IllegalArgumentException("kernel: cannot read config " + o.configPath, e);
            }
        } else if (notEmpty(o.link)) {
            this.spec = ShareLink.parse(o.link);
        } else {
            throw new IllegalArgumentException("Kernel: provide either link or configPath");
        }
        this.kind = chooseKernel(spec, o.kernel == null ? "auto" : o.kernel);
        String explicit = "xray".equals(kind) ? o.xrayBin : o.singboxBin;
        this.bin = resolveBin(binCandidates(kind, explicit));
        this.socksPort = o.socksPort;
    }

    public static Kernel create(Options o) {
        return new Kernel(o);
    }

    public String kind() {
        return kind;
    }

    public int socksPort() {
        return socksPort;
    }

    public Spec spec() {
        return spec;
    }

    private static String chooseKernel(Spec spec, String preference) {
        if (preference.equals("xray") || preference.equals("sing-box")) {
            if (preference.equals("xray") && spec != null && ShareLink.SINGBOX_ONLY.contains(spec.type)) {
                throw new IllegalArgumentException(
                        "xray-core has no native " + spec.type + " outbound; use PROXY_KERNEL=sing-box");
            }
            return preference;
        }
        if (spec != null && ShareLink.SINGBOX_ONLY.contains(spec.type)) {
            return "sing-box";
        }
        return "xray";
    }

    private static List<String> binCandidates(String kind, String explicit) {
        String exe = IS_WIN ? ".exe" : "";
        String name = kind.equals("xray") ? "xray" + exe : "sing-box" + exe;
        List<String> out = new ArrayList<>();
        if (notEmpty(explicit)) {
            out.add(explicit);
        }
        out.add("vendor" + File.separator + name); // 项目同级 vendor/
        out.add(name);                              // PATH
        return out;
    }

    private static String resolveBin(List<String> candidates) {
        for (String c : candidates) {
            if (c.contains(File.separator) || c.contains("/")) {
                if (new File(c).exists()) {
                    return c;
                }
            } else {
                return c; // 裸名 -> 交给 OS 在 PATH 中解析
            }
        }
        return candidates.get(candidates.size() - 1);
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            return s.getLocalPort();
        }
    }

    @SuppressWarnings("unchecked")
    private static int ensureSocksInbound(Map<String, Object> cfg, String kind, int port) {
        Object inboundsObj = cfg.get("inbounds");
        List<Object> inbounds;
        if (inboundsObj instanceof List) {
            inbounds = (List<Object>) inboundsObj;
        } else {
            inbounds = new ArrayList<>();
            cfg.put("inbounds", inbounds);
        }
        if (kind.equals("sing-box")) {
            for (Object o : inbounds) {
                if (o instanceof Map && "socks".equals(((Map<String, Object>) o).get("type"))) {
                    Object lp = ((Map<String, Object>) o).get("listen_port");
                    return lp instanceof Number ? ((Number) lp).intValue() : port;
                }
            }
            Map<String, Object> in = new java.util.LinkedHashMap<>();
            in.put("type", "socks");
            in.put("tag", "cpl-socks-in");
            in.put("listen", "127.0.0.1");
            in.put("listen_port", port);
            inbounds.add(in);
        } else {
            for (Object o : inbounds) {
                if (o instanceof Map && "socks".equals(((Map<String, Object>) o).get("protocol"))) {
                    Object p = ((Map<String, Object>) o).get("port");
                    return p instanceof Number ? ((Number) p).intValue() : port;
                }
            }
            Map<String, Object> in = new java.util.LinkedHashMap<>();
            in.put("tag", "cpl-socks-in");
            in.put("listen", "127.0.0.1");
            in.put("port", port);
            in.put("protocol", "socks");
            Map<String, Object> settings = new java.util.LinkedHashMap<>();
            settings.put("auth", "noauth");
            settings.put("udp", true);
            in.put("settings", settings);
            inbounds.add(in);
        }
        return port;
    }

    /** 渲染配置、spawn 内核、轮询就绪。返回本地 SOCKS5 端口。 */
    public int start() throws IOException {
        if (socksPort == 0) {
            socksPort = freePort();
        }
        Map<String, Object> cfg;
        if (nativeConfig != null) {
            cfg = nativeConfig;
            socksPort = ensureSocksInbound(cfg, kind, socksPort);
        } else if (kind.equals("xray")) {
            cfg = XrayConfig.build(spec, socksPort);
        } else {
            cfg = SingboxConfig.build(spec, socksPort);
        }

        configDir = Files.createTempDirectory("cpl-kernel-").toFile();
        File configFile = new File(configDir, kind.equals("xray") ? "xray.json" : "sing-box.json");
        Files.write(configFile.toPath(),
                MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(cfg));

        // xray-core 与 sing-box 都用 `run -c <config>`。把 .py/.js/.jar 视作 shim，
        // 用对应解释器运行——便于自定义包装与无 Go 二进制时测试。
        List<String> command = new ArrayList<>();
        String lower = bin.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".py")) {
            command.add(IS_WIN ? "python" : "python3");
            command.add(bin);
        } else if (lower.endsWith(".js") || lower.endsWith(".mjs")) {
            command.add("node");
            command.add(bin);
        } else if (lower.endsWith(".jar")) {
            command.add("java");
            command.add("-jar");
            command.add(bin);
        } else {
            command.add(bin);
        }
        command.add("run");
        command.add("-c");
        command.add(configFile.getAbsolutePath());

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        try {
            child = pb.start();
        } catch (IOException e) {
            throw new IOException("failed to spawn " + kind + " (" + bin + "): " + e.getMessage(), e);
        }

        Thread pump = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (!line.isEmpty()) {
                        logger.accept(line);
                    }
                }
            } catch (IOException ignored) {
                // process ended
            }
        });
        pump.setDaemon(true);
        pump.start();

        long deadline = System.currentTimeMillis() + opts.readyTimeoutMs;
        while (true) {
            if (!child.isAlive()) {
                int code = child.exitValue();
                throw new IOException(kind + " exited before becoming ready (code=" + code
                        + "). Is the binary \"" + bin + "\" installed and the config valid?");
            }
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", socksPort), 500);
                break;
            } catch (IOException notReady) {
                if (System.currentTimeMillis() > deadline) {
                    stop();
                    throw new IOException("kernel SOCKS port " + socksPort + " not ready within "
                            + opts.readyTimeoutMs + "ms");
                }
                sleep(150);
            }
        }
        logger.accept(kind + " ready: SOCKS5 127.0.0.1:" + socksPort + " -> " + describe());
        return socksPort;
    }

    public void stop() {
        stopped = true;
        if (child != null && child.isAlive()) {
            child.destroy();
        }
        if (configDir != null) {
            deleteRecursively(configDir);
            configDir = null;
        }
    }

    public String describe() {
        if (spec != null) {
            String via = (spec.tls != null && spec.tls.reality != null) ? "+reality"
                    : ((spec.tls != null && spec.tls.enabled) ? "+tls" : "");
            String net = spec.transport != null ? "/" + spec.transport.type : "";
            return kind + ":" + spec.type + net + via + " " + spec.server + ":" + spec.port;
        }
        String base = opts.configPath == null ? "" : new File(opts.configPath).getName();
        return kind + ":config(" + base + ")";
    }

    private static void deleteRecursively(File f) {
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {
                deleteRecursively(k);
            }
        }
        // best-effort
        f.delete();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.isEmpty();
    }
}
