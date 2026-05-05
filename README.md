# Starlight Minecraft Launcher Core

多版本 Minecraft 启动核心库，支持原版、Forge、Fabric、Quilt、NeoForge 等主流 Mod 加载器。

## 功能特性

- **多加载器支持** — 自动检测并适配 Vanilla / Fabric / Quilt / Forge Legacy / Forge Modern / NeoForge
- **Natives 自动提取** — 从 native JAR 中自动提取 DLL，支持缓存和完整性校验
- **版本隔离** — 可选择为每个版本使用独立的游戏数据目录
- **启动前/后命令** — 支持自定义 pre-launch / post-exit 系统命令
- **完整日志系统** — 自动记录启动日志和错误日志（带时间戳和级别标记）
- **进程监控** — 启动后持续监控子进程状态，记录运行时长和退出码
- **Forge Modern 模块路径** — 动态构建 `--module-path` 和 `--add-opens` 参数
- **Forge Legacy 兼容** — 自动过滤 Java 9+ 模块参数，兼容 LaunchWrapper

## 快速开始

### Maven 引入

```xml
<dependency>
    <groupId>com.starlight</groupId>
    <artifactId>mc-launcher-core</artifactId>
    <version>1.0.0</version>
</dependency>
```

> `mc-launcher-core` 不打包传递依赖，需自行引入 Gson（2.10.1+）：
> ```xml
> <dependency>
>     <groupId>com.google.code.gson</groupId>
>     <artifactId>gson</artifactId>
>     <version>2.10.1</version>
> </dependency>
> ```

### 基础用法

```java
// 1. 准备启动信息
LaunchInfo info = new LaunchInfo();
info.setGameDirPath(Path.of(".minecraft"));
info.setVersion("1.20.1");
info.setJavaPath("C:/Program Files/Java/jdk17/bin/java.exe");
info.setUserName("Steve");
info.setUuid("00000000-0000-0000-0000-000000000000");
info.setAccessToken("token");
info.setUserType("msa");
info.setMinMemory("2G");
info.setMaxMemory("4G");

// 2. 检测加载器
Path versionJsonFile = info.resolveVersionDir().resolve(info.getVersion() + ".json");
JsonObject versionJson = JsonParser.parseString(
    Files.readString(versionJsonFile)).getAsJsonObject();
String loader = LoaderDetector.detect(versionJson, info.getVersion());

// 3. 创建对应的启动器
BaseLauncher launcher;
switch (loader) {
    case LoaderDetector.FABRIC, LoaderDetector.QUILT ->
        launcher = new FabricLauncher(info);
    case LoaderDetector.FORGE_LEGACY ->
        launcher = new ForgeLegacyLauncher(info);
    case LoaderDetector.FORGE_MODERN, LoaderDetector.NEOFORGE ->
        launcher = new ForgeModernLauncher(info);
    default ->
        launcher = new VanillaLauncher(info);
}

// 4. 初始化并启动
Path logDir = info.getGameDirPath().resolve("logs");
try {
    launcher.initLogging(logDir);
    launcher.initPaths();
    launcher.setVersionJson(versionJson);
    launcher.processNatives();
    int exitCode = launcher.launch();
} finally {
    launcher.closeLog();
}
```

## 项目结构

```
mc-launcher-core/
├── src/main/java/com/startgame/
│   ├── LaunchInfo.java              # 启动参数载体（POJO）
│   └── launcher/
│       ├── BaseLauncher.java        # 抽象基类 — 模板方法模式
│       ├── FabricLauncher.java       # Fabric / Quilt 启动器
│       ├── ForgeLegacyLauncher.java  # Forge ≤1.16.5（LaunchWrapper）
│       ├── ForgeModernLauncher.java  # Forge ≥1.17（模块化启动）
│       ├── LoaderDetector.java       # 加载器类型检测工具
│       └── VanillaLauncher.java      # 原版启动器
├── pom.xml
└── README.md
```

## API 概览

### LaunchInfo

启动参数载体，所有字段通过 setter 赋值：

| 字段 | 类型 | 说明 |
|------|------|------|
| `gameDirPath` | `Path` | 游戏根目录（如 `.minecraft`） |
| `version` | `String` | 版本名称 |
| `javaPath` | `String` | Java 可执行文件路径 |
| `userName` / `uuid` / `accessToken` / `userType` | `String` | 认证信息 |
| `minMemory` / `maxMemory` | `String` | JVM 内存限制（如 `"4G"`） |
| `jvmArgs` / `gameArgs` | `String` | 额外参数（空格分隔） |
| `windowWidth` / `windowHeight` | `int` | 窗口尺寸（默认 854×480） |
| `fullscreen` | `boolean` | 全屏模式 |
| `versionIsolation` | `boolean` | 版本隔离 |
| `preLaunchCommand` / `postExitCommand` | `String` | 自定义命令 |
| `loaderType` | `String` | 加载器类型（通常由检测器自动填充） |
| `nativesDir` | `Path` | Natives 目录（通常由 `processNatives()` 设置） |

路径解析方法：
- `resolveVersionDir()` → `gameDir/versions/<version>`
- `resolveAssetsDir()` → `gameDir/assets`
- `resolveLibrariesRoot()` → `gameDir/libraries`
- `resolveEffectiveGameDir()` → 版本隔离时返回版本目录，否则返回游戏根目录

### BaseLauncher 抽象基类

公共初始化方法（需按顺序调用）：

```java
void initLogging(Path logDir)      // 1. 初始化日志
void initPaths()                   // 2. 初始化运行时路径
void setVersionJson(JsonObject)    // 3. 设置 version.json
void processNatives()              // 4. 处理 Natives DLL
int  launch()                      // 5. 启动游戏（抽象方法）
void closeLog()                    // 6. 关闭日志
```

### LoaderDetector 工具类

静态检测方法：

```java
String detect(JsonObject versionJson, String versionName)
boolean isForge(String loaderType)
boolean isFabricLike(String loaderType)
```

返回常量：`VANILLA` / `FABRIC` / `QUILT` / `FORGE_LEGACY` / `FORGE_MODERN` / `NEOFORGE`

检测优先级：
1. **mainClass** 全限定名（最高优先级）
2. **inheritsFrom** 字段
3. **版本名称** 关键词匹配（降级方案）

### 加载器适配对照表

| 加载器 | 对应类 | 启动架构 | 测试版本 |
|--------|--------|----------|----------|
| Vanilla | `VanillaLauncher` | 标准 classpath | 1.20.1 |
| Fabric | `FabricLauncher` | KnotClient | 1.20.1-Fabric 0.17.2 |
| Quilt | `FabricLauncher` | KnotClient（同 Fabric） | - |
| Forge Legacy | `ForgeLegacyLauncher` | LaunchWrapper | 1.12.2-Forge 14.23.5.2860 |
| Forge Modern | `ForgeModernLauncher` | 模块化启动（BootstrapLauncher） | 1.20.1-Forge 47.4.16 |
| NeoForge | `ForgeModernLauncher` | 模块化启动（同 Forge Modern） | - |

## 构建

```bash
mvn clean package -DskipTests
```

输出：`target/mc-launcher-core-1.0.0.jar`

要求：JDK 9+

## 依赖

| 依赖 | 版本 | 用途 |
|------|------|------|
| Gson | 2.10.1 | 解析 Minecraft version.json |

## 许可证

MIT License

## 开发者

- [Starlight Launcher](https://github.com/Dreamo331) — 项目开发
- [Starlight Launcher] DeepSeek — 项目优化
