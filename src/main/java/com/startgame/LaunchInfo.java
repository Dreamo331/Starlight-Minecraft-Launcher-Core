package com.startgame;

import java.nio.file.Path;

/**
 * 启动配置信息载体
 * 
 * 包含所有启动所需的参数和路径解析逻辑。
 * 所有字段默认使用无参构造 + setter 方式赋值。
 */
public class LaunchInfo {

    // ==================== 目录 / 路径字段 ====================
    private Path gameDirPath;       // 游戏根目录（如 .minecraft）
    private String version;        // 版本名称（如 "1.20.1-Forge_47.4.16"）
    private String javaPath;       // Java 可执行文件路径

    // ==================== 用户 / 认证字段 ====================
    private String userName;       // 玩家名
    private String uuid;           // 玩家 UUID
    private String accessToken;    // 认证令牌
    private String userType;       // 账号类型（mojang / msa）

    // ==================== JVM / 游戏参数字段 ====================
    private String minMemory;      // 最小内存（如 "2G"）
    private String maxMemory;      // 最大内存（如 "4G"）
    private String jvmArgs;        // 额外 JVM 参数（空格分隔）
    private String gameArgs;       // 额外游戏参数（空格分隔）

    // ==================== 窗口设置 ====================
    private int windowWidth = 854;     // 默认窗口宽度
    private int windowHeight = 480;    // 默认窗口高度
    private boolean fullscreen = false;

    // ==================== 启动器行为 ====================
    private String loaderType;         // 加载器类型（Vanilla / Fabric / Forge ...）
    private boolean versionIsolation = false;  // 是否启用版本隔离
    private String preLaunchCommand;   // 启动前命令
    private String postExitCommand;    // 退出后命令

    // ==================== 运行时设置（由启动器内部设置） ====================
    private Path nativesDir;           // natives 目录路径

    // ==================== 路径解析方法 ====================

    /**
     * @return 版本目录：游戏根目录/versions/当前版本
     */
    public Path resolveVersionDir() {
        return gameDirPath.resolve("versions").resolve(version);
    }

    /**
     * @return assets 目录：游戏根目录/assets
     */
    public Path resolveAssetsDir() {
        return gameDirPath.resolve("assets");
    }

    /**
     * @return libraries 根目录：游戏根目录/libraries
     */
    public Path resolveLibrariesRoot() {
        return gameDirPath.resolve("libraries");
    }

    /**
     * @return 有效游戏数据目录（启用版本隔离时返回版本目录，否则返回游戏根目录）
     */
    public Path resolveEffectiveGameDir() {
        if (versionIsolation) {
            return resolveVersionDir();
        }
        return gameDirPath;
    }

    /**
     * @return 游戏根目录字符串（用于环境变量）
     */
    public String getGameDir() {
        return gameDirPath.toString();
    }

    // ==================== Getter / Setter ====================

    public Path getGameDirPath() {
        return gameDirPath;
    }

    public void setGameDirPath(Path gameDirPath) {
        this.gameDirPath = gameDirPath;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getJavaPath() {
        return javaPath;
    }

    public void setJavaPath(String javaPath) {
        this.javaPath = javaPath;
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public String getUserType() {
        return userType;
    }

    public void setUserType(String userType) {
        this.userType = userType;
    }

    public String getMinMemory() {
        return minMemory;
    }

    public void setMinMemory(String minMemory) {
        this.minMemory = minMemory;
    }

    public String getMaxMemory() {
        return maxMemory;
    }

    public void setMaxMemory(String maxMemory) {
        this.maxMemory = maxMemory;
    }

    public String getJvmArgs() {
        return jvmArgs;
    }

    public void setJvmArgs(String jvmArgs) {
        this.jvmArgs = jvmArgs;
    }

    public String getGameArgs() {
        return gameArgs;
    }

    public void setGameArgs(String gameArgs) {
        this.gameArgs = gameArgs;
    }

    public int getWindowWidth() {
        return windowWidth;
    }

    public void setWindowWidth(int windowWidth) {
        this.windowWidth = windowWidth;
    }

    public int getWindowHeight() {
        return windowHeight;
    }

    public void setWindowHeight(int windowHeight) {
        this.windowHeight = windowHeight;
    }

    public boolean isFullscreen() {
        return fullscreen;
    }

    public void setFullscreen(boolean fullscreen) {
        this.fullscreen = fullscreen;
    }

    public String getLoaderType() {
        return loaderType;
    }

    public void setLoaderType(String loaderType) {
        this.loaderType = loaderType;
    }

    public boolean isVersionIsolation() {
        return versionIsolation;
    }

    public void setVersionIsolation(boolean versionIsolation) {
        this.versionIsolation = versionIsolation;
    }

    public String getPreLaunchCommand() {
        return preLaunchCommand;
    }

    public void setPreLaunchCommand(String preLaunchCommand) {
        this.preLaunchCommand = preLaunchCommand;
    }

    public String getPostExitCommand() {
        return postExitCommand;
    }

    public void setPostExitCommand(String postExitCommand) {
        this.postExitCommand = postExitCommand;
    }

    public Path getNativesDir() {
        return nativesDir;
    }

    public void setNativesDir(Path nativesDir) {
        this.nativesDir = nativesDir;
    }
}