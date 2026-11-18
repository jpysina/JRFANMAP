# JRFANMAP

面向日本铁道爱好者的 Android 随车实时观测站。原生 Kotlin / Jetpack Compose / Material 3，手机横竖屏布局，Android 8.0+。

[![Android APK](https://github.com/jpysina/JRFANMAP/actions/workflows/android.yml/badge.svg)](https://github.com/jpysina/JRFANMAP/actions/workflows/android.yml)

安装包见 [GitHub Releases](https://github.com/jpysina/JRFANMAP/releases)：下载 **JRFANMAP-debug.apk**，无需合并分卷。每个发布的来源提交均与完整应用源码对应。

## 自动构建与发布

- 推送到 `main`：检查通过后自动发布 `dev-运行编号.重跑编号` 预发布，附完整 APK 和 SHA-256 文件。
- 推送 `v*` 标签：自动发布对应版本；包含 `-` 的标签作为预发布，例如 `v0.1.0-preview.2`。
- PR：只构建、运行 JVM 单元测试和 Lint，不发布、不使用签名密钥。
- 可在仓库 **Actions → Android APK → Run workflow** 对 `main` 手动构建和发布。
- 构建失败时不发布；附件上传后校验服务器端 SHA-256，再公开 Release；拒绝覆盖已经发布的版本。

工作流使用 GitHub 自带的 `GITHUB_TOKEN`，只给发布任务 `contents: write`；无需配置之前云环境中的上传 Token。构建报告和 APK 也保存在 Actions Artifacts。

默认生成调试签名 APK，尚未配置固定密钥时，每次 CI 的签名可能不同，不能保证覆盖安装；卸载会删除本机轨迹，需先导出。若需持续覆盖升级，可在仓库 **Settings → Secrets and variables → Actions** 添加 `ANDROID_DEBUG_KEYSTORE_BASE64`，内容为私有调试 keystore 的 Base64，使用 Android 标准调试别名/密码。构建会恢复这份密钥，避免签名变化。不要把 keystore、Token 或用户 API Key 提交到仓库；正式商店发行需要独立的发布签名方案。

## 首版范围

- 实时仪表：定位服务给出的对地速度、速度精度、WGS84 经纬度、椭球高、定位精度、方向、数据年龄、最近速度曲线。
- GNSS：GPS、QZSS、Galileo、GLONASS、北斗等星座的可见/参与定位卫星和 C/N₀（以硬件实际能力为准）。
- 设备传感器：气压、手机磁北朝向、线性加速度；不利用加速度积分伪造隧道速度。
- 地图：MapLibre Native + 国土地理院全国实验矢量地图；位置、最近 3600 点轨迹、跟随/自由浏览；按当前视区下载 3 个缩放级别的小区域。
- 行程：用户在前台启动定位前台服务，锁屏后继续记录；本机 SQLite 保存，停止后 CSV 导出、删除；意外进程终止后的行程标为中断。
- 铁路：JR 东日本官方运行信息入口、用户配置 Key 后在应用可见时每分钟查询 ODPT TrainInformation，也可手动刷新；不承诺此数据集实时覆盖 JR 东日本所有线路。
- 隐私：无需账号、不上传轨迹；ODPT Key 用 Android Keystore AES-GCM 加密保存，禁止系统备份。地图服务能看到瓦片范围请求。

无定位时显示空值；超过 10 秒没有新定位或记录停止后不显示实时速度，位置保留为最后观测。精度大于 50m、模拟定位、明显跳点和超过 15 秒的断点不累计里程。里程是保守观测值，可能低估，非运营里程。

## 构建

需要 JDK 21、Android SDK Platform 37.2 / Build Tools 36.0.0。Compose BOM 2026.09.00 使用 API 37 系列编译；应用仍支持 Android 8.0+，targetSdk 为 36。Gradle Wrapper 固定版本与 SHA-256；项目使用 Google Maven / Maven Central。Android Studio 打开仓库后可直接同步。

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
# app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

当前云环境用以下命令（目录均在 /workspace，避免只读用户目录）：

```sh
export ANDROID_HOME=/workspace/android-sdk
export ANDROID_USER_HOME=/workspace/android-user
export GRADLE_USER_HOME=/workspace/.gradle
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

首次配置或补齐工具链可运行 `python3 scripts/bootstrap-cloud.py`（需要联网）。脚本固定并验证官方 JDK/命令行工具下载，安装所需 SDK，再执行构建、单元测试和 Lint。

受代理限制的环境需要为 Gradle/SDK Manager 配置平台提供的代理；不要关闭 TLS 或签名验证。项目不保存代理凭据、API Key 或本机 SDK 路径。

## 使用

1. 安装并打开，点击“开始定位与行程记录”；允许精确位置，建议允许通知。系统定位须开启。
2. 记录通知可随时停止行程。无需“始终允许位置”权限；从可见页面启动 location 前台服务。部分设备需要用户允许后台耗电才能长时间锁屏记录。
3. 在地图上关闭跟随、缩放到目标区域，点击下载范围并确认。最多 5 个区域，每区最多 1500 瓦片；字体资源另计。请在前台完成后再断网；中断区域可清除并重新下载。未下载的范围或缩放级别不保证离线可用。
4. 铁路页可直接打开官方网页。若需 ODPT 查询，到 [ODPT 开发者站](https://developer.odpt.org/) 申请有相应数据权限的 Key，在应用“设置”中输入。没有记录不代表运行正常；保留源发布时间和最近获取时间。
5. 行程页导出原始 CSV。轨迹不自动上传；导出含精确位置。

当前不实现自动车次识别、轨道匹配、历史地图回放、惯性导航、全日本一键下载、云同步或 Google Play 发布。后续计划和数据边界见 [产品计划](docs/PLAN.md)，来源见 [数据与许可](docs/DATA_SOURCES.md)。
