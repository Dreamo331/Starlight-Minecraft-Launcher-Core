// ============================================================
// Forge Modern 加载器启动器（Minecraft 1.17+）
// ============================================================
// 适用于 Forge 1.17+ 使用模块化启动架构的版本。
// 关键特征:
//   - 使用 BootstrapLauncher 作为主类
//   - 需要构建模块路径（module-path）：bootstraplauncher, securejarhandler 等
//   - 需要 --add-opens / --add-exports 模块系统参数
//   - 使用 arguments.game JSON 格式，支持占位符替换
// ============================================================
package com.startgame.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.startgame.LaunchInfo;

/**
 * Forge Modern 加载器启动器
 *
 * 启动特征:
 *   - mainClass = cpw.mods.modlauncher.BootstrapLauncher 或类似
 *   - 需要构建 --patch-module / --add-opens 模块参数
 *   - 使用 -p (module-path) 指定引导模块
 *   - 使用 -DignoreList 排除已通过模块加载的库
 *   - 使用 -DmergeModules 合并特定 JAR
 *
 * 必需引导模块:
 *   - cpw.mods:bootstraplauncher
 *   - cpw.mods:securejarhandler
 *   - org.ow2.asm:asm / asm-commons / asm-tree / asm-util / asm-analysis
 *   - net.minecraftforge:JarJarFileSystems
 *
 * 测试版本: 1.20.1-Forge_47.4.16
 */
public class ForgeModernLauncher extends BaseLauncher {

    /** Modern Forge 必需的引导模块列表 */
    private static final List<String> REQUIRED_MODULES = Arrays.asList(
        "cpw.mods:bootstraplauncher",
        "cpw.mods:securejarhandler",
        "org.ow2.asm:asm",
        "org.ow2.asm:asm-commons",
        "org.ow2.asm:asm-tree",
        "org.ow2.asm:asm-util",
        "org.ow2.asm:asm-analysis",
        "net.minecraftforge:JarJarFileSystems"
    );

    public ForgeModernLauncher(LaunchInfo launchInfo) {
        super(launchInfo);
    }

    @Override
    public int launch() throws IOException, InterruptedException {
        writeLog("[模式] Forge 1.17+ (Modern)", "INFO");

        // ========== 启动前命令 ==========
        executePreLaunchCommand();

        // ========== 构建模块路径 ==========
        List<String> modulePaths = buildModulePath();
        String modulePathStr = modulePaths.stream()
                .map(p -> p)
                .collect(Collectors.joining(";"));
        writeLog("动态构建模块路径: 共 " + modulePaths.size() + " 个模块", "INFO");

        // ========== 类路径 ==========
        List<String> libs = getLibraries(true);
        String cp = formatClassPath(libs);

        // ========== Mods 目录 ==========
        Path modsDir = resolveModsDir();

        // ========== 有效游戏数据目录 ==========
        String effectiveGameDir = launchInfo.resolveEffectiveGameDir().toString();

        // ========== 构建 ignoreList ==========
        String ignoreList = REQUIRED_MODULES.stream()
                .map(m -> m.split(":")[1])
                .collect(Collectors.joining(","))
                + ",client-extra,fmlcore,javafmllanguage,lowcodelanguage,mclanguage,forge-,"
                + launchInfo.getVersion() + ".jar";

        // ========== 组装 JVM 参数 ==========
        List<String> jvmBase = buildJvmBase();

        List<String> startArgs = new ArrayList<>();
        startArgs.addAll(jvmBase);
        startArgs.add("-Djava.net.preferIPv6Addresses=system");
        startArgs.add("-DignoreList=" + ignoreList);
        startArgs.add("-DmergeModules=jna-5.10.0.jar,jna-platform-5.10.0.jar");
        startArgs.add("-DlibraryDirectory=" + libsRoot);
        startArgs.add("-Dfml.modFolders=" + modsDir);

        // 模块路径与模块系统参数
        startArgs.add("-p");
        startArgs.add(modulePathStr);
        startArgs.add("--add-modules");
        startArgs.add("ALL-MODULE-PATH");
        startArgs.add("--add-opens");
        startArgs.add("java.base/java.util.jar=cpw.mods.securejarhandler");
        startArgs.add("--add-opens");
        startArgs.add("java.base/java.lang.invoke=cpw.mods.securejarhandler");
        startArgs.add("--add-exports");
        startArgs.add("java.base/sun.security.util=cpw.mods.securejarhandler");
        startArgs.add("--add-exports");
        startArgs.add("jdk.naming.dns/com.sun.jndi.dns=java.naming");

        // 类路径
        startArgs.add("-cp");
        startArgs.add(cp);

        // 主类及游戏参数
        startArgs.add(mainClass);
        startArgs.addAll(buildGameArgsFromJson(effectiveGameDir));

        // ========== 启动进程 ==========
        writeLog("启动命令构建完成，正在启动 " + launchInfo.getVersion() + " ...", "INFO");
        writeLog("Mods 目录: " + modsDir, "INFO");
        writeLog("日志文件: " + logFile, "INFO");

        // 打印完整命令
        //String fullCommand = launchInfo.getJavaPath() + " " + String.join(" ", startArgs);
        //writeLog("[完整启动命令] " + fullCommand, "INFO");

        int exitCode = startProcessWithMonitoring(launchInfo.getJavaPath(), startArgs);

        // ========== 退出后命令 ==========
        executePostExitCommand();

        return exitCode;
    }

    /**
     * 从 libraries 中查找必需的引导模块 JAR 文件
     *
     * 扫描 libsRoot 目录，根据模块坐标(group:artifact)匹配对应的 JAR。
     * 同时支持:
     *   - downloads.artifact.path（精确路径）
     *   - name 字段推导的 Maven 路径（降级方案）
     */
    private List<String> buildModulePath() {
        List<String> modulePaths = new ArrayList<>();
        JsonArray libraries = versionJson.getAsJsonArray("libraries");

        if (libraries == null) return modulePaths;

        for (String module : REQUIRED_MODULES) {
            String[] parts = module.split(":");
            String group = parts[0];
            String artifact = parts[1];

            for (JsonElement libElem : libraries) {
                JsonObject lib = libElem.getAsJsonObject();
                if (!lib.has("name")) continue;

                String name = lib.get("name").getAsString();
                if (!name.startsWith(group + ":" + artifact + ":")) continue;

                // 优先使用 downloads.artifact.path
                if (lib.has("downloads")
                    && lib.getAsJsonObject("downloads").has("artifact")) {
                    String path = lib.getAsJsonObject("downloads")
                            .getAsJsonObject("artifact")
                            .get("path").getAsString();
                    Path libPath = libsRoot.resolve(path);
                    if (Files.exists(libPath)) {
                        modulePaths.add(libPath.toString());
                        break;
                    }
                }

                // 降级: 从 name 推导
                String[] nameParts = name.split(":");
                String groupPath = nameParts[0].replace('.', '/');
                String art = nameParts[1];
                String ver = nameParts[2];
                String classifier = nameParts.length >= 4 ? "-" + nameParts[3] : "";
                String path = groupPath + "/" + art + "/" + ver + "/" + art + "-" + ver + classifier + ".jar";
                Path libPath = libsRoot.resolve(path);
                if (Files.exists(libPath)) {
                    modulePaths.add(libPath.toString());
                }
                break;
            }
        }

        return modulePaths.stream().distinct().collect(Collectors.toList());
    }
}
