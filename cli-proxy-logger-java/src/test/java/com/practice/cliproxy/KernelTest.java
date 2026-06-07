package com.practice.cliproxy;

import com.practice.cliproxy.kernel.ShareLink;
import com.practice.cliproxy.kernel.SingboxConfig;
import com.practice.cliproxy.kernel.Spec;
import com.practice.cliproxy.kernel.XrayConfig;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * xray/sing-box 内核集成单元测试：分享链接解析 -> 规范化 spec、spec -> 各内核配置渲染、
 * 内核选择规则。对应 Node {@code test/kernel.js} / Python {@code tests/test_kernel.py}
 * 的单元部分（不 spawn 真实进程，故无需 Go 工具链）。
 */
class KernelTest {

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void schemeDetection() {
        assertTrue(ShareLink.isKernelScheme("vmess://abc"));
        assertTrue(ShareLink.isKernelScheme("vless://u@h:1"));
        assertTrue(ShareLink.isKernelScheme("hy2://p@h:1"));
        assertFalse(ShareLink.isKernelScheme("http://h:8080"));
        assertFalse(ShareLink.isKernelScheme("socks5://h:1080"));
        assertFalse(ShareLink.isKernelScheme(""));
        assertTrue(ShareLink.SINGBOX_ONLY.contains("hysteria2"));
        assertTrue(ShareLink.SINGBOX_ONLY.contains("tuic"));
    }

    @Test
    void shareLinkParsing() {
        String vmess = "vmess://" + b64("{\"add\":\"example.com\",\"port\":\"443\",\"id\":\"uuid-1\","
                + "\"aid\":\"0\",\"scy\":\"auto\",\"net\":\"ws\",\"host\":\"cdn.example.com\","
                + "\"path\":\"/ray\",\"tls\":\"tls\",\"sni\":\"example.com\",\"ps\":\"node-a\"}");
        Spec v = ShareLink.parse(vmess);
        assertEquals("example.com", v.server);
        assertEquals(443, v.port);
        assertEquals("uuid-1", v.uuid);
        assertEquals("auto", v.security);
        assertEquals("ws", v.transport.type);
        assertEquals("/ray", v.transport.path);
        assertEquals("cdn.example.com", v.transport.host);
        assertTrue(v.tls.enabled);
        assertEquals("example.com", v.tls.serverName);

        Spec vl = ShareLink.parse("vless://uuid-2@host.net:8443?encryption=none&security=tls&sni=h.net"
                + "&type=grpc&serviceName=gs&flow=xtls-rprx-vision#vl");
        assertEquals("uuid-2", vl.uuid);
        assertEquals("xtls-rprx-vision", vl.flow);
        assertEquals("grpc", vl.transport.type);
        assertEquals("gs", vl.transport.serviceName);
        assertTrue(vl.tls.enabled);
        assertEquals("h.net", vl.tls.serverName);

        Spec vr = ShareLink.parse("vless://uuid-3@h.net:443?security=reality&pbk=PUBKEY&sid=ab12"
                + "&sni=www.apple.com&fp=chrome&type=tcp#r");
        assertEquals("PUBKEY", vr.tls.reality.publicKey);
        assertEquals("ab12", vr.tls.reality.shortId);

        Spec tj = ShareLink.parse("trojan://secret@h.net:443?security=tls&sni=h.net#t");
        assertEquals("secret", tj.password);
        assertEquals("h.net", tj.server);
        assertEquals(443, tj.port);

        Spec ss = ShareLink.parse("ss://" + b64("aes-256-gcm:pw123") + "@1.2.3.4:8388#s");
        assertEquals("aes-256-gcm", ss.method);
        assertEquals("pw123", ss.password);
        assertEquals("1.2.3.4", ss.server);
        assertEquals(8388, ss.port);

        Spec ssLegacy = ShareLink.parse("ss://" + b64("chacha20-ietf-poly1305:pw@5.6.7.8:9999") + "#s2");
        assertEquals("chacha20-ietf-poly1305", ssLegacy.method);
        assertEquals("5.6.7.8", ssLegacy.server);
        assertEquals(9999, ssLegacy.port);

        Spec hy = ShareLink.parse("hysteria2://pw@h.net:8443?sni=h.net&insecure=1"
                + "&obfs=salamander&obfs-password=op#h");
        assertEquals("pw", hy.password);
        assertEquals("salamander", hy.obfs.type);
        assertEquals("op", hy.obfs.password);
        assertTrue(hy.tls.enabled);
        assertTrue(hy.tls.insecure);
        assertEquals("hysteria2", ShareLink.parse("hy2://pw@h.net:443#h2").type);

        Spec tu = ShareLink.parse("tuic://uuid-9:pw@h.net:443?congestion_control=bbr&alpn=h3&sni=h.net#u");
        assertEquals("uuid-9", tu.uuid);
        assertEquals("pw", tu.password);
        assertEquals("bbr", tu.congestionControl);

        assertThrows(IllegalArgumentException.class, () -> ShareLink.parse("ftp://nope"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void xrayConfigRendering() {
        Spec v = ShareLink.parse("vless://u@h.net:443?encryption=none&security=tls&sni=h.net"
                + "&type=ws&path=/p&host=cdn#x");
        Map<String, Object> cfg = XrayConfig.build(v, 10800);
        List<Object> inbounds = (List<Object>) cfg.get("inbounds");
        Map<String, Object> in0 = (Map<String, Object>) inbounds.get(0);
        assertEquals("socks", in0.get("protocol"));
        assertEquals(10800, in0.get("port"));
        assertEquals("127.0.0.1", in0.get("listen"));

        List<Object> outbounds = (List<Object>) cfg.get("outbounds");
        Map<String, Object> out = (Map<String, Object>) outbounds.get(0);
        assertEquals("vless", out.get("protocol"));
        Map<String, Object> settings = (Map<String, Object>) out.get("settings");
        List<Object> vnext = (List<Object>) settings.get("vnext");
        Map<String, Object> node = (Map<String, Object>) vnext.get(0);
        assertEquals("h.net", node.get("address"));
        List<Object> users = (List<Object>) node.get("users");
        assertEquals("u", ((Map<String, Object>) users.get(0)).get("id"));
        Map<String, Object> ssettings = (Map<String, Object>) out.get("streamSettings");
        assertEquals("tls", ssettings.get("security"));
        assertEquals("ws", ssettings.get("network"));
        assertEquals("/p", ((Map<String, Object>) ssettings.get("wsSettings")).get("path"));
        boolean hasFreedom = outbounds.stream().anyMatch(o ->
                "freedom".equals(((Map<String, Object>) o).get("protocol")));
        assertTrue(hasFreedom);

        Spec vm = ShareLink.parse("vmess://" + b64("{\"add\":\"a\",\"port\":443,\"id\":\"id\",\"aid\":2,\"net\":\"tcp\"}"));
        Map<String, Object> vmCfg = XrayConfig.build(vm, 10801);
        Map<String, Object> vmOut = (Map<String, Object>) ((List<Object>) vmCfg.get("outbounds")).get(0);
        Map<String, Object> vmNode = (Map<String, Object>) ((List<Object>) ((Map<String, Object>) vmOut.get("settings")).get("vnext")).get(0);
        Map<String, Object> vmUser = (Map<String, Object>) ((List<Object>) vmNode.get("users")).get(0);
        assertEquals("id", vmUser.get("id"));
        assertEquals(2, vmUser.get("alterId"));

        Spec ss = ShareLink.parse("ss://" + b64("aes-256-gcm:pw") + "@1.1.1.1:80#s");
        Map<String, Object> ssCfg = XrayConfig.build(ss, 10802);
        Map<String, Object> ssOut = (Map<String, Object>) ((List<Object>) ssCfg.get("outbounds")).get(0);
        assertEquals("shadowsocks", ssOut.get("protocol"));
        Map<String, Object> ssServer = (Map<String, Object>) ((List<Object>) ((Map<String, Object>) ssOut.get("settings")).get("servers")).get(0);
        assertEquals("aes-256-gcm", ssServer.get("method"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void singboxConfigRendering() {
        Spec v = ShareLink.parse("vless://u@h.net:443?encryption=none&security=tls&sni=h.net"
                + "&type=ws&path=/p&host=cdn#x");
        Map<String, Object> cfg = SingboxConfig.build(v, 10810);
        Map<String, Object> in0 = (Map<String, Object>) ((List<Object>) cfg.get("inbounds")).get(0);
        assertEquals("socks", in0.get("type"));
        assertEquals(10810, in0.get("listen_port"));

        Map<String, Object> out = (Map<String, Object>) ((List<Object>) cfg.get("outbounds")).get(0);
        assertEquals("vless", out.get("type"));
        assertEquals("h.net", out.get("server"));
        assertEquals(443, out.get("server_port"));
        assertEquals("u", out.get("uuid"));
        Map<String, Object> tls = (Map<String, Object>) out.get("tls");
        assertEquals(true, tls.get("enabled"));
        assertEquals("h.net", tls.get("server_name"));
        Map<String, Object> transport = (Map<String, Object>) out.get("transport");
        assertEquals("ws", transport.get("type"));
        assertEquals("/p", transport.get("path"));

        Spec hy = ShareLink.parse("hysteria2://pw@h.net:8443?sni=h.net&obfs=salamander&obfs-password=op#h");
        Map<String, Object> hyOut = (Map<String, Object>) ((List<Object>) SingboxConfig.build(hy, 10811).get("outbounds")).get(0);
        assertEquals("hysteria2", hyOut.get("type"));
        assertEquals("pw", hyOut.get("password"));
        assertEquals("salamander", ((Map<String, Object>) hyOut.get("obfs")).get("type"));

        Spec tu = ShareLink.parse("tuic://uuid:pw@h.net:443?congestion_control=bbr&sni=h.net#u");
        Map<String, Object> tuOut = (Map<String, Object>) ((List<Object>) SingboxConfig.build(tu, 10812).get("outbounds")).get(0);
        assertEquals("tuic", tuOut.get("type"));
        assertEquals("uuid", tuOut.get("uuid"));
        assertEquals("bbr", tuOut.get("congestion_control"));
    }

    @Test
    void selectionRules() {
        com.practice.cliproxy.kernel.Kernel.Options o1 = new com.practice.cliproxy.kernel.Kernel.Options();
        o1.link = "hysteria2://pw@h:443";
        o1.kernel = "auto";
        assertEquals("sing-box", com.practice.cliproxy.kernel.Kernel.create(o1).kind());

        com.practice.cliproxy.kernel.Kernel.Options o2 = new com.practice.cliproxy.kernel.Kernel.Options();
        o2.link = "vmess://" + b64("{\"add\":\"a\",\"port\":1,\"id\":\"x\",\"net\":\"tcp\"}");
        o2.kernel = "auto";
        assertEquals("xray", com.practice.cliproxy.kernel.Kernel.create(o2).kind());

        com.practice.cliproxy.kernel.Kernel.Options o3 = new com.practice.cliproxy.kernel.Kernel.Options();
        o3.link = "tuic://u:p@h:443";
        o3.kernel = "xray";
        assertThrows(IllegalArgumentException.class, () -> com.practice.cliproxy.kernel.Kernel.create(o3));
    }
}
