// ============================================================
// Forge Legacy 加载器启动器（Minecraft 1.16.5 及以下）
// ============================================================
// 适用于 Forge 1.12.2 ~ 1.16.5 等使用 LaunchWrapper 的版本。
// 关键特征:
//   - 使用 LaunchWrapper 作为主类
//   - 不支持 Java 9+ 模块系统参数（需要过滤 --add-* 等）
//   - 通过 tweakClass 参数指定 Mod 加载器
//   - 使用 minecraftArguments 旧格式而非 arguments.game
// ============================================================
package com.startgame.launcher;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import com.startgame.LaunchInfo;

/**
 * Forge Legacy 加载器启动器
 *
 * 启动特征:
 *   - mainClass = net.minecraft.launchwrapper.Launch
 *   - 过滤所有 --add-* / --patch-module / --illegal-access 等 Java 9+ 参数
 *   - 支持从 minecraftArguments 和 arguments.game 提取 tweakClass
 *   - 通过 -Dfml.modFolders 指定 mods 目录
 *
 * 测试版本: 1.12.2-Forge_14.23.5.2860
 */
public class ForgeLegacyLauncher extends BaseLauncher {

    public ForgeLegacyLauncher(LaunchInfo launchInfo) {
        super(launchInfo);
    }

    @Override
    public int launch() throws IOException, InterruptedException {
        writeLog("[模式] Forge 1.16.5- (Legacy)", "INFO");

        // ========== 启动前命令 ==========
        executePreLaunchCommand();

        // ========== 过滤 Java 9+ 参数 ==========
        // Legacy Forge 使用 LaunchWrapper，不兼容 Java 模块系统参数
        List<String> jvmBaseForLegacy = buildJvmBase().stream()
                .filter(arg -> !arg.startsWith("--add-")
                            && !arg.startsWith("--patch-module")
                            && !arg.contains("--illegal-access"))
                .collect(Collectors.toList());
        writeLog("已过滤 Java 9+ 参数，剩余 " + jvmBaseForLegacy.size() + " 个 JVM 参数", "INFO");

        // ========== 类路径 ==========
        List<String> libs = getLibraries(true);
        String cp = formatClassPath(libs);
        writeLog("类路径条目数: " + libs.size(), "INFO");

        // ========== Mods 目录 ==========
        Path modsDir = resolveModsDir();

        // ========== 提取 tweakClass ==========
        List<String> tweaks = extractTweakClasses();

        // ========== 有效游戏数据目录 ==========
        String effectiveGameDir = launchInfo.resolveEffectiveGameDir().toString();

        // ========== 组装启动参数 ==========
        List<String> argList = new ArrayList<>(jvmBaseForLegacy);
        argList.add("-Dfml.modFolders=" + modsDir);
        argList.add("-cp");
        argList.add(cp);
        argList.add(mainClass);
        argList.addAll(buildGameArgs(effectiveGameDir));
        argList.addAll(tweaks);

        writeLog("启动参数构建完成，共 " + argList.size() + " 个参数", "INFO");
        writeLog("Mods 目录: " + modsDir, "INFO");
        writeLog("日志文件: " + logFile, "INFO");

        // ========== 启动进程 ==========
        int exitCode = startProcessWithMonitoring(launchInfo.getJavaPath(), argList);

        // ========== 退出后命令 ==========
        executePostExitCommand();

        return exitCode;
    }

    /**
     * 从 version.json 中提取 tweakClass 参数
     *
     * Forge Legacy 通过 --tweakClass 指定 Mod 加载器入口，
     * 这些参数可能存在于:
     *   1. arguments.game 数组中的非占位符字符串
     *   2. minecraftArguments 字符串
     */
    private List<String> extractTweakClasses() {
        List<String> tweaks = new ArrayList<>();

        // ---- 方式1: 从 arguments.game 提取 ----
        if (versionJson.has("arguments") && versionJson.get("arguments").isJsonObject()) {
            JsonObject argsObj = versionJson.getAsJsonObject("arguments");
            if (argsObj.has("game")) {
                JsonArray gameArgs = argsObj.getAsJsonArray("game");
                for (JsonElement arg : gameArgs) {
                    if (arg.isJsonPrimitive()) {
                        String str = arg.getAsString();
                        // 只取非占位符字符串（占位符以 ${ 开头）
                        if (!str.startsWith("${")) {
                            tweaks.add(str);
                        }
                    }
                }
            }
        }

        // ---- 方式2: 从 minecraftArguments 提取（旧格式） ----
        if (versionJson.has("minecraftArguments")) {
            String legacyArgs = versionJson.get("minecraftArguments").getAsString();
            String[] parts = legacyArgs.split("\\s+");
            for (int i = 0; i < parts.length; i++) {
                if ("--tweakClass".equals(parts[i]) && i + 1 < parts.length) {
                    tweaks.add("--tweakClass");
                    tweaks.add(parts[i + 1]);
                }
            }
        }

        if (!tweaks.isEmpty()) {
            writeLog("提取到 " + (tweaks.size() / 2) + " 个 tweakClass", "INFO");
        }

        return tweaks;
    }
}
