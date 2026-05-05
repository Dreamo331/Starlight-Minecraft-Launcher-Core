// ============================================================
// Fabric / Quilt 加载器启动器
// ============================================================
// 适用于 Fabric Loader 和 Quilt Loader 的 Mod 加载环境。
// Fabric 使用 KnotClient 作为主类，通过 -cp 标准类路径启动。
// 需要额外处理:
//   - Fabric 专用的 mods 目录（Dfabric.mod.loader.modDir）
//   - 版本隔离下的 mods 目录重定向
// ============================================================
package com.startgame.launcher;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.startgame.LaunchInfo;

/**
 * Fabric / Quilt 加载器启动器
 *
 * 启动特征:
 *   - mainClass = net.fabricmc.loader.impl.launch.knot.KnotClient
 *   - 通过 -Dfabric.mod.loader.modDir 指定 mods 目录
 *   - 类路径结构与原版一致（版本 JAR + libraries）
 *
 * 测试版本: 1.20.1-Fabric 0.17.2
 */
public class FabricLauncher extends BaseLauncher {

    public FabricLauncher(LaunchInfo launchInfo) {
        super(launchInfo);
    }

    @Override
    public int launch() throws IOException, InterruptedException {
        writeLog("[模式] Fabric/Quilt 加载器", "INFO");

        // ========== 启动前命令 ==========
        executePreLaunchCommand();

        // ========== 构建启动参数 ==========
        List<String> jvmBase = buildJvmBase();

        // 类路径
        List<String> libs = getLibraries(true);
        String cp = formatClassPath(libs);
        writeLog("类路径条目数: " + libs.size(), "INFO");

        // Mods 目录（考虑版本隔离）
        Path modsDir = resolveModsDir();

        // 有效游戏数据目录
        String effectiveGameDir = launchInfo.resolveEffectiveGameDir().toString();

        // 完整启动参数
        List<String> startArgs = new ArrayList<>();
        startArgs.addAll(jvmBase);
        startArgs.add("-cp");
        startArgs.add(cp);
        startArgs.add("-Dfabric.mod.loader.modDir=" + modsDir);
        startArgs.add(mainClass);
        startArgs.addAll(buildGameArgs(effectiveGameDir));

        // ========== 启动进程 ==========
        writeLog("启动命令构建完成，正在启动 " + launchInfo.getVersion() + " ...", "INFO");
        writeLog("Mods 目录: " + modsDir, "INFO");
        writeLog("日志文件: " + logFile, "INFO");

        int exitCode = startProcessWithMonitoring(launchInfo.getJavaPath(), startArgs);

        // ========== 退出后命令 ==========
        executePostExitCommand();

        return exitCode;
    }
}
