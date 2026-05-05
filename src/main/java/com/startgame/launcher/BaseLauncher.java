// ============================================================
// 启动器抽象基类
// ============================================================
// 提供所有加载器公用的基础设施：
//   - 日志系统
//   - 类路径(classpath)构建
//   - JVM 基础参数构建
//   - 游戏参数构建
//   - 进程启动与监控
//
// 子类只需实现 launch() 方法，填充特定加载器的启动逻辑。
// ============================================================
package com.startgame.launcher;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.startgame.LaunchInfo;

/**
 * 加载器基类 — 抽象模板方法模式
 *
 * @param <T> 启动返回值类型（通常为 Integer 退出码）
 */
public abstract class BaseLauncher {

    // ==================== 日志常量 ====================

    protected static final SimpleDateFormat LOG_TIMESTAMP = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    protected static final SimpleDateFormat FILE_TIMESTAMP = new SimpleDateFormat("yyyyMMdd_HHmmss");

    /** Natives DLL 最低有效数量（低于此值认为不完整） */
    protected static final int MIN_REQUIRED_DLLS = 5;

    // ==================== 字段 ====================

    /** 启动配置信息 */
    protected final LaunchInfo launchInfo;

    /** 运行时推导路径 */
    protected Path gameDirPath;
    protected Path verDir;
    protected Path jarPath;
    protected Path assetsDir;
    protected Path libsRoot;
    protected Path nativesPath;

    /** 版本 JSON 解析结果 */
    protected JsonObject versionJson;
    protected String mainClass;
    protected String assetIndex;

    /** 日志 Writer */
    protected BufferedWriter logWriter;
    protected BufferedWriter errorLogWriter;
    protected Path logFile;
    protected Path errorLogFile;

    // ==================== 构造 ====================

    public BaseLauncher(LaunchInfo launchInfo) {
        this.launchInfo = launchInfo;
    }

    // ==================== 子类需实现的抽象方法 ====================

    /**
     * 执行加载器特定的启动逻辑
     * @return 游戏进程退出码
     * @throws IOException          进程启动失败
     * @throws InterruptedException 进程等待中断
     */
    public abstract int launch() throws IOException, InterruptedException;

    // ==================== 初始化（由外部调用） ====================

    /**
     * 初始化日志系统
     * @param logDir 日志输出目录
     */
    public void initLogging(Path logDir) throws IOException {
        Files.createDirectories(logDir);

        String timestamp = FILE_TIMESTAMP.format(new Date());
        logFile = logDir.resolve("minecraft_launch_" + timestamp + ".log");
        errorLogFile = logDir.resolve("minecraft_error_" + timestamp + ".log");

        logWriter = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        errorLogWriter = Files.newBufferedWriter(errorLogFile, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /**
     * 初始化运行时路径（在解析 version.json 后调用）
     */
    public void initPaths() {
        gameDirPath = launchInfo.getGameDirPath();
        verDir = launchInfo.resolveVersionDir();
        jarPath = verDir.resolve(launchInfo.getVersion() + ".jar");
        assetsDir = launchInfo.resolveAssetsDir();
        libsRoot = launchInfo.resolveLibrariesRoot();
    }

    /**
     * 设置解析后的 version.json
     */
    public void setVersionJson(JsonObject json) {
        this.versionJson = json;
        this.mainClass = json.has("mainClass") ? json.get("mainClass").getAsString() : "";
        this.assetIndex = (json.has("assetIndex") && json.get("assetIndex").isJsonObject())
                ? json.getAsJsonObject("assetIndex").get("id").getAsString()
                : "legacy";
    }

    // ==================== Natives 处理 ====================

    /**
     * 处理 Natives DLL：查找已提取的目录，必要时从 JAR 中提取
     * 与 NativesRepairTool 互补：
     *   - NativesRepairTool 确保 DLL 文件存在
     *   - 此方法确保 DLL 目录路径正确设置
     */
    public void processNatives() {
        // ---------------------------------------------------------------
        // 第一步：尝试查找已有 natives 目录
        // ---------------------------------------------------------------
        List<Path> candidates = Arrays.asList(
            verDir.resolve(launchInfo.getVersion() + "-natives"),
            verDir.resolve("natives-windows-x86_64"),
            verDir.resolve("natives")
        );

        for (Path dir : candidates) {
            if (Files.exists(dir)) {
                try (java.util.stream.Stream<Path> files = Files.list(dir)) {
                    long dllCount = files.filter(p -> p.toString().endsWith(".dll")).count();
                    if (dllCount >= MIN_REQUIRED_DLLS) {
                        nativesPath = dir;
                        writeLog("找到完整的 natives 目录: " + nativesPath + " (" + dllCount + " 个 DLL)", "SUCCESS");
                        launchInfo.setNativesDir(nativesPath);
                        return;
                    } else if (dllCount > 0) {
                        writeLog("发现不完整的 natives 目录: " + dir + " (只有 " + dllCount + " 个 DLL)", "WARN");
                        deleteRecursively(dir);
                    }
                } catch (IOException e) {
                    writeLog("检查 natives 目录失败: " + e.getMessage(), "WARN");
                }
            }
        }

        // ---------------------------------------------------------------
        // 第二步：从 Native JAR 中提取 DLL
        // ---------------------------------------------------------------
        writeLog("从 native JAR 文件中提取 DLL...", "WARN");
        Path extractDir = verDir.resolve("natives-extracted");

        try {
            if (Files.exists(extractDir)) deleteRecursively(extractDir);
            Files.createDirectories(extractDir);

            List<Path> nativeJars = new ArrayList<>();
            if (Files.exists(libsRoot)) {
                Files.walk(libsRoot)
                    .filter(p -> p.toString().endsWith(".jar"))
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.contains("natives-windows")
                            && !name.contains("arm64")
                            && !name.contains("x86");
                    })
                    .forEach(nativeJars::add);
            }

            writeLog("找到 " + nativeJars.size() + " 个 native JAR 文件", "INFO");

            if (nativeJars.isEmpty()) {
                writeLog("警告: 找不到 native JAR，尝试不设置 natives 路径继续", "WARN");
                // 尝试用版本目录本身作为 fallback
                nativesPath = verDir;
                launchInfo.setNativesDir(nativesPath);
                return;
            }

            int extractedCount = 0;
            Set<String> extractedNames = new HashSet<>();

            for (Path jar : nativeJars) {
                try (ZipFile zip = new ZipFile(jar.toFile())) {
                    Enumeration<? extends ZipEntry> entries = zip.entries();
                    while (entries.hasMoreElements()) {
                        ZipEntry entry = entries.nextElement();
                        String name = entry.getName();
                        if (name.endsWith(".dll") && !name.contains("arm64")
                            && !name.contains("x86") && !extractedNames.contains(name)) {
                            Path dest = extractDir.resolve(Paths.get(name).getFileName());
                            try (InputStream is = zip.getInputStream(entry)) {
                                Files.copy(is, dest, StandardCopyOption.REPLACE_EXISTING);
                            }
                            extractedCount++;
                            extractedNames.add(name);
                            writeLog("  提取: " + name + " <- " + jar.getFileName(), "INFO");
                        }
                    }
                } catch (IOException e) {
                    writeLog("提取失败 " + jar.getFileName() + ": " + e.getMessage(), "WARN");
                }
            }

            writeLog("提取完成: " + extractedCount + " 个 DLL", "SUCCESS");
            if (extractedCount < MIN_REQUIRED_DLLS) {
                writeLog("警告: 提取的 DLL 数量偏少 (" + extractedCount + " 个)", "WARN");
            }

            nativesPath = extractDir;
            launchInfo.setNativesDir(nativesPath);

            long dllCount;
            try (java.util.stream.Stream<Path> files = Files.list(nativesPath)) {
                dllCount = files.filter(p -> p.toString().endsWith(".dll")).count();
            }
            writeLog("Natives 路径: " + nativesPath + " (" + dllCount + " 个 DLL)", "INFO");

        } catch (IOException e) {
            writeLog("提取 natives 失败: " + e.getMessage(), "ERROR");
            // 不直接退出，让调用方决定
            nativesPath = verDir;
            launchInfo.setNativesDir(nativesPath);
        }
    }

    // ==================== 类路径构建 ====================

    /**
     * 收集版本 JSON 中声明的所有 libraries 文件路径
     *
     * @param includeVersionJar 是否包含版本 JAR（versions/<ver>/<ver>.jar）
     * @return 类路径条目列表（绝对路径字符串）
     */
    protected List<String> getLibraries(boolean includeVersionJar) {
        List<String> libs = new ArrayList<>();

        if (versionJson.has("libraries")) {
            JsonArray libraries = versionJson.getAsJsonArray("libraries");
            for (JsonElement libElem : libraries) {
                JsonObject lib = libElem.getAsJsonObject();

                // ---- 规则检查（OS 过滤） ----
                boolean allowed = true;
                if (lib.has("rules")) {
                    allowed = false;
                    JsonArray rules = lib.getAsJsonArray("rules");
                    for (JsonElement ruleElem : rules) {
                        JsonObject rule = ruleElem.getAsJsonObject();
                        String action = rule.get("action").getAsString();
                        if (rule.has("os")) {
                            JsonObject os = rule.getAsJsonObject("os");
                            String osName = os.get("name").getAsString();
                            if ("windows".equals(osName) && "allow".equals(action)) allowed = true;
                            if (!"windows".equals(osName) && "disallow".equals(action)) allowed = true;
                        } else if ("allow".equals(action)) {
                            allowed = true;
                        }
                    }
                }
                if (!allowed) continue;

                // ---- 解析路径 ----
                String path = resolveLibraryPath(lib);
                if (path != null && !path.isEmpty()) {
                    Path libPath = libsRoot.resolve(path);
                    if (Files.exists(libPath)) {
                        libs.add(libPath.toString());
                    }
                }
            }
        }

        // 去重
        libs = libs.stream().distinct().collect(Collectors.toList());

        if (includeVersionJar && Files.exists(jarPath)) {
            libs.add(jarPath.toString());
        }

        return libs;
    }

    /**
     * 从 library JSON 对象中解析相对路径
     */
    private String resolveLibraryPath(JsonObject lib) {
        // 优先使用 downloads.artifact.path
        if (lib.has("downloads") && lib.getAsJsonObject("downloads").has("artifact")) {
            JsonObject artifact = lib.getAsJsonObject("downloads").getAsJsonObject("artifact");
            if (artifact.has("path")) {
                return artifact.get("path").getAsString();
            }
        }

        // 降级：从 name 字段推导 Maven 路径
        if (lib.has("name")) {
            String name = lib.get("name").getAsString();
            String[] parts = name.split(":");
            if (parts.length >= 3) {
                String group = parts[0].replace('.', '/');
                String artifact = parts[1];
                String version = parts[2];
                String classifier = parts.length >= 4 ? "-" + parts[3] : "";
                return group + "/" + artifact + "/" + version + "/" + artifact + "-" + version + classifier + ".jar";
            }
        }
        return null;
    }

    /**
     * 将类路径列表格式化为 Windows 分号分隔字符串
     */
    protected String formatClassPath(List<String> libs) {
        return String.join(";", libs);
    }

    // ==================== JVM 基础参数 ====================

    /**
     * 构建通用的 JVM 基础参数（内存、GC、Natives 路径等）
     * 子类若需修改，可 override 此方法
     */
    protected List<String> buildJvmBase() {
        List<String> base = new ArrayList<>();
        base.add("-Xms" + launchInfo.getMinMemory());
        base.add("-Xmx" + launchInfo.getMaxMemory());
        base.add("-XX:+UseG1GC");
        base.add("-XX:-UseAdaptiveSizePolicy");
        base.add("-XX:-OmitStackTraceInFastThrow");
        base.add("-Djdk.lang.Process.allowAmbiguousCommands=true");
        base.add("-Dfml.ignoreInvalidMinecraftCertificates=True");
        base.add("-Dfml.ignorePatchDiscrepancies=True");
        base.add("-Dlog4j2.formatMsgNoLookups=true");
        base.add("-XX:HeapDumpPath=MojangTricksIntelDriversForPerformance_javaw.exe_minecraft.exe.heapdump");

        // 额外 JVM 参数
        String extraJvm = launchInfo.getJvmArgs();
        if (extraJvm != null && !extraJvm.isEmpty()) {
            for (String arg : extraJvm.split("\\s+")) {
                if (!arg.isEmpty()) base.add(arg);
            }
        }

        // Natives / JAR 路径
        String nativesPathStr = (nativesPath != null) ? nativesPath.toString() : verDir.toString();
        String jarPathStr = jarPath.toString();

        base.add("-Djava.library.path=" + nativesPathStr);
        base.add("-Djna.tmpdir=" + nativesPathStr);
        base.add("-Dorg.lwjgl.system.SharedLibraryExtractPath=" + nativesPathStr);
        base.add("-Dio.netty.native.workdir=" + nativesPathStr);
        base.add("-Dminecraft.client.jar=" + jarPathStr);
        base.add("-Dstdout.encoding=UTF-8");
        base.add("-Dstderr.encoding=UTF-8");

        return base;
    }

    // ==================== 游戏参数 ====================

    /**
     * 构建标准游戏参数（适用于 Vanilla / Fabric / Forge Legacy）
     */
    protected List<String> buildGameArgs(String effectiveGameDir) {
        List<String> args = new ArrayList<>();
        args.add("--username");    args.add(launchInfo.getUserName());
        args.add("--version");     args.add(launchInfo.getVersion());
        args.add("--gameDir");     args.add(effectiveGameDir);
        args.add("--assetsDir");   args.add(assetsDir.toString());
        args.add("--assetIndex");  args.add(assetIndex);
        args.add("--uuid");        args.add(launchInfo.getUuid());
        args.add("--accessToken"); args.add(launchInfo.getAccessToken());
        args.add("--userType");    args.add(launchInfo.getUserType());
        args.add("--versionType"); args.add("Starlight Launcher");
        args.add("--width");       args.add(String.valueOf(launchInfo.getWindowWidth()));
        args.add("--height");      args.add(String.valueOf(launchInfo.getWindowHeight()));

        if (launchInfo.isFullscreen()) args.add("--fullscreen");

        String extra = launchInfo.getGameArgs();
        if (extra != null && !extra.isEmpty()) {
            for (String arg : extra.split("\\s+")) {
                if (!arg.isEmpty()) args.add(arg);
            }
        }

        return args;
    }

/**
 * 从 version.json 的 arguments.game 字段构建参数（Forge Modern 专用）
 * 支持占位符替换 ${...} 和规则(rules)检测，
 * 并自动过滤未替换的占位符以及指定的黑名单参数（如 --demo）。
 */
protected List<String> buildGameArgsFromJson(String effectiveGameDir) {
    // 需要强制移除的参数名集合（黑名单）
    // 例如 Forge 1.20.1 的 version.json 可能自带 --demo 参数，
    // 或以 --dream 等形式出现，视实际情况添加
    final Set<String> BLACKLISTED_ARGS = Set.of("--demo", "--dream");

    List<String> args = new ArrayList<>();

    // -------- 第一阶段：从 JSON 中提取原始参数 --------
    if (versionJson.has("arguments") && versionJson.get("arguments").isJsonObject()) {
        JsonObject argsObj = versionJson.getAsJsonObject("arguments");
        if (argsObj.has("game")) {
            JsonArray gameArgs = argsObj.getAsJsonArray("game");
            for (JsonElement elem : gameArgs) {
                if (elem.isJsonPrimitive()) {
                    String str = elem.getAsString();
                    args.add(replacePlaceholders(str, effectiveGameDir));
                } else if (elem.isJsonObject()) {
                    JsonObject obj = elem.getAsJsonObject();
                    boolean allow = true;
                    if (obj.has("rules")) {
                        JsonArray rules = obj.getAsJsonArray("rules");
                        for (JsonElement ruleElem : rules) {
                            JsonObject rule = ruleElem.getAsJsonObject();
                            if ("disallow".equals(rule.get("action").getAsString())
                                && rule.has("os")
                                && "windows".equals(rule.getAsJsonObject("os").get("name").getAsString())) {
                                allow = false;
                                break;
                            }
                        }
                    }
                    if (allow && obj.has("value")) {
                        if (obj.get("value").isJsonPrimitive()) {
                            args.add(replacePlaceholders(obj.get("value").getAsString(), effectiveGameDir));
                        } else if (obj.get("value").isJsonArray()) {
                            for (JsonElement v : obj.getAsJsonArray("value")) {
                                args.add(replacePlaceholders(v.getAsString(), effectiveGameDir));
                            }
                        }
                    }
                }
            }
        }
    } else {
        // 降级到旧格式
        args.addAll(buildGameArgs(effectiveGameDir));
    }

    // -------- 第二阶段：过滤无效参数对 --------
    List<String> filteredArgs = new ArrayList<>();
    for (int i = 0; i < args.size(); i++) {
        String arg = args.get(i);

        // 1. 如果当前参数值仍然包含未替换的占位符（如 ${quickPlaySingleplayer}）
        if (arg.contains("${")) {
            // 同时移除前一个选项名（例如 --quickPlaySingleplayer）
            if (i > 0 && args.get(i - 1).startsWith("--")) {
                filteredArgs.remove(filteredArgs.size() - 1);
            }
            continue; // 跳过当前占位符值
        }

        // 2. 如果当前参数是黑名单中的选项名（如 --demo）
        if (BLACKLISTED_ARGS.contains(arg)) {
            // 跳过该选项名本身（不加入 filteredArgs）
            // 同时检查下一个参数：如果不是以 "--" 开头的选项，说明是该选项携带的值，也一并跳过
            if (i + 1 < args.size() && !args.get(i + 1).startsWith("--")) {
                i++; // 多跳过一个值
            }
            continue;
        }

        // 通过过滤，加入最终列表
        filteredArgs.add(arg);
    }

    return filteredArgs;
}


    /**
     * 替换 Minecraft 游戏参数占位符
     */
    protected String replacePlaceholders(String str, String effectiveGameDir) {
        return str
            .replace("${version_name}", launchInfo.getVersion())
            .replace("${assets_root}", assetsDir.toString())
            .replace("${assets_index_name}", assetIndex)
            .replace("${auth_access_token}", launchInfo.getAccessToken())
            .replace("${auth_uuid}", launchInfo.getUuid())
            .replace("${auth_player_name}", launchInfo.getUserName())
            .replace("${user_type}", launchInfo.getUserType())
            .replace("${version_type}", "Starlight Launcher")
            .replace("${game_directory}", effectiveGameDir)
            .replace("${library_directory}", libsRoot.toString())
            .replace("${natives_directory}", nativesPath != null ? nativesPath.toString() : "")
            .replace("${launcher_name}", "Universal")
            .replace("${launcher_version}", "1.0")
            .replace("${resolution_width}", String.valueOf(launchInfo.getWindowWidth()))
            .replace("${resolution_height}", String.valueOf(launchInfo.getWindowHeight()));
    }

    // ==================== 进程启动与监控 ====================

    /**
     * 启动游戏进程并持续监控输出
     *
     * @param javaPath  Java 可执行文件路径
     * @param arguments 完整启动参数列表
     * @param envVars   附加环境变量
     * @param startTime 启动计时起点
     * @return 进程退出码
     */
    protected int startProcessWithMonitoring(
            String javaPath, List<String> arguments,
            Map<String, String> envVars, long startTime)
            throws IOException, InterruptedException {

        ProcessBuilder pb = new ProcessBuilder(javaPath);
        pb.command().addAll(arguments);
        pb.directory(gameDirPath.toFile());
        pb.environment().putAll(envVars);
        pb.redirectErrorStream(false);

        Process process = pb.start();
        long pid = process.pid();
        writeLog("游戏进程已启动 (PID: " + pid + ")，开始监控...", "INFO");

        // ----- 启动流读取线程 -----
        ExecutorService executor = Executors.newFixedThreadPool(3);

        // 标准输出
        executor.submit(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    writeLogToFile("[STDOUT] " + line);
                    System.out.println(line);
                }
            } catch (IOException ignored) {}
        });

        // 错误输出
        executor.submit(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    writeLogToFile("[STDERR] " + line);
                    System.err.println(line);
                }
            } catch (IOException ignored) {}
        });

        // 监控线程（每 10 秒检查进程状态）
        ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor();
        monitor.scheduleAtFixedRate(() -> {
            try {
                if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                    writeLogToFile("[MONITOR] PID " + pid + " - 运行中");
                }
            } catch (Exception ignored) {}
        }, 10, 10, TimeUnit.SECONDS);

        int exitCode = process.waitFor();
        monitor.shutdownNow();
        executor.shutdownNow();

        long duration = System.currentTimeMillis() - startTime;
        String durStr = String.format("%02d:%02d:%02d",
            TimeUnit.MILLISECONDS.toHours(duration),
            TimeUnit.MILLISECONDS.toMinutes(duration) % 60,
            TimeUnit.MILLISECONDS.toSeconds(duration) % 60);

        writeLog("游戏已退出，运行时长: " + durStr + "，退出代码: " + exitCode, "INFO");
        if (exitCode != 0) {
            writeLog("警告: 游戏异常退出 (代码: " + exitCode + ")", "WARN");
        }

        return exitCode;
    }

    /**
     * 简化版进程启动（自动处理环境变量和计时）
     */
    protected int startProcessWithMonitoring(String javaPath, List<String> arguments)
            throws IOException, InterruptedException {
        return startProcessWithMonitoring(javaPath, arguments, buildDefaultEnv(), System.currentTimeMillis());
    }

    /**
     * 构建默认环境变量
     */
    protected Map<String, String> buildDefaultEnv() {
        Map<String, String> env = new HashMap<>();
        env.put("APPDATA", gameDirPath.getParent().toString());
        env.put("INST_NAME", launchInfo.getVersion());
        env.put("INST_ID", launchInfo.getVersion());
        env.put("INST_DIR", verDir.toString());
        env.put("INST_MC_DIR", launchInfo.getGameDir());
        env.put("INST_JAVA", launchInfo.getJavaPath());

        String loaderType = launchInfo.getLoaderType();
        if (LoaderDetector.isFabricLike(loaderType)) env.put("INST_FABRIC", "1");
        if (LoaderDetector.isForge(loaderType))     env.put("INST_FORGE", "1");

        return env;
    }

    // ==================== Mods 目录处理 ====================

    /**
     * 获取有效的 mods 目录路径（考虑版本隔离）
     */
    protected Path resolveModsDir() {
        Path versionModsDir = verDir.resolve("mods");
        Path globalModsDir = gameDirPath.resolve("mods");

        if (launchInfo.isVersionIsolation()) {
            if (Files.exists(versionModsDir)) {
                writeLog("版本隔离已启用，使用版本专属 mods 目录: " + versionModsDir, "INFO");
                return versionModsDir;
            }
            try {
                Files.createDirectories(versionModsDir);
                writeLog("版本隔离已启用，已创建 mods 目录: " + versionModsDir, "INFO");
                return versionModsDir;
            } catch (IOException e) {
                writeLog("创建版本 mods 目录失败，回退到全局目录", "WARN");
            }
        }
        return globalModsDir;
    }

    // ==================== 日志工具 ====================

    /**
     * 写入带时间戳和级别的日志
     */
    protected void writeLog(String message, String level) {
        String timestamp = LOG_TIMESTAMP.format(new Date());
        String entry = String.format("[%s] [%s] %s", timestamp, level, message);
        System.out.println(message);
        if (logWriter != null) {
            try {
                logWriter.write(entry);
                logWriter.newLine();
                logWriter.flush();
            } catch (IOException ignored) {}
        }
    }

    /**
     * 写入纯文本日志（不带级别前缀，供 stdout/stderr 重定向使用）
     */
    protected void writeLogToFile(String message) {
        String timestamp = LOG_TIMESTAMP.format(new Date());
        String entry = String.format("[%s] %s", timestamp, message);
        if (logWriter != null) {
            try {
                logWriter.write(entry);
                logWriter.newLine();
                logWriter.flush();
            } catch (IOException ignored) {}
        }
    }

    /**
     * 记录异常堆栈到错误日志
     */
    protected void writeErrorLog(String context, Exception e) {
        if (errorLogWriter != null) {
            try {
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                e.printStackTrace(pw);
                errorLogWriter.write(String.format("[%s] [ERROR] %s%n", LOG_TIMESTAMP.format(new Date()), context));
                errorLogWriter.write(sw.toString());
                errorLogWriter.newLine();
                errorLogWriter.flush();
            } catch (IOException ignored) {}
        }
    }

    /**
     * 关闭日志 Writer
     */
    public void closeLog() {
        try {
            if (logWriter != null) logWriter.close();
            if (errorLogWriter != null) errorLogWriter.close();
        } catch (IOException ignored) {}
    }

    // ==================== 文件系统工具 ====================

    /**
     * 递归删除目录及所有子文件
     */
    protected void deleteRecursively(Path path) throws IOException {
        if (Files.exists(path)) {
            Files.walk(path)
                .sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try { Files.delete(p); } catch (IOException ignored) {}
                });
        }
    }

    // ==================== 启动前/后命令 ====================

    /**
     * 执行启动前系统命令（阻塞等待完成）
     */
    protected void executePreLaunchCommand() {
        String cmd = launchInfo.getPreLaunchCommand();
        if (cmd != null && !cmd.isEmpty()) {
            writeLog("执行启动前命令: " + cmd, "INFO");
            try {
                Process p = Runtime.getRuntime().exec(cmd);
                p.waitFor(30, TimeUnit.SECONDS);
            } catch (IOException | InterruptedException e) {
                writeLog("启动前命令执行失败: " + e.getMessage(), "WARN");
            }
        }
    }

    /**
     * 执行退出后系统命令（非阻塞触发）
     */
    protected void executePostExitCommand() {
        String cmd = launchInfo.getPostExitCommand();
        if (cmd != null && !cmd.isEmpty()) {
            writeLog("执行退出后命令: " + cmd, "INFO");
            try {
                Runtime.getRuntime().exec(cmd);
            } catch (IOException e) {
                writeLog("退出后命令执行失败: " + e.getMessage(), "WARN");
            }
        }
    }
}
