# JRFANMAP

面向日本铁道爱好者的 Android 随车实时观测站。原生 Kotlin / Jetpack Compose / Material 3，手机横竖屏布局，Android 8.0+。

[![Android APK](https://github.com/jpysina/JRFANMAP/actions/workflows/android.yml/badge.svg)](https://github.com/jpysina/JRFANMAP/actions/workflows/android.yml)

安装包见 [GitHub Releases](https://github.com/jpysina/JRFANMAP/releases)：下载 **JRFANMAP-debug.apk**，无需合并分卷。每个发布的来源提交均与完整应用源码对应。

## 自动构建与发布

- 推送到 `main`：检查通过后自动发布 `dev-运行编号.重跑编号` 预发布，附完整 APK 和 SHA-256 文件。
- 推送 `v*` 标签：自动发布对应版本；包含 `-` 的标签作为预发布，例如 `v0.1.0-preview.2`。
- PR 执行构建、JVM 单元测试和 Lint。在支持 KVM 的 runner 上还会运行 Android 模拟点测。
- 可在仓库 **Actions → Android APK → Run workflow** 对 `main` 手动构建和发布。
- 构建失败时不发布；附件上传后校验服务器端 SHA-256，再公开 Release；拒绝覆盖已经发布的版本。

工作流使用 GitHub 自带的 `GITHUB_TOKEN`，只给发布任务 `contents: write`；无需配置之前云环境中的上传 Token。构建报告和 APK 也保存在 Actions Artifacts。

默认生成调试签名 APK，尚未配置固定密钥时，每次 CI 的签名可能不同，不能保证覆盖安装；卸载会删除本机轨迹，需先导出。若需持续覆盖升级，可在仓库 **Settings → Secrets and variables → Actions** 添加 `ANDROID_DEBUG_KEYSTORE_BASE64`，内容为私有调试 keystore 的 Base64，使用 Android 标准调试别名/密码。构建会恢复这份密钥，避免签名变化。不要把 keystore、Token 或用户 API Key 提交到仓库；正式商店发行需要独立的发布签名方案。

## 0.2.2 功能

- **LIVE**：打开应用即开始前台定位（首次需要授权）。地图占满画面，悬浮卡片显示速度、方向、定位精度和前方日文站序。支持自动跟随、手动拖动和全屏。
- **GPS**：定位来源、更新时间和误差，椭球高与设备支持的海拔，卫星天空图、按星座分组的卫星列表、载噪比、方位角、仰角、频率、星历、DOP 与原始观测数。设备支持时显示气压、线性加速度、磁场、磁北和陀螺仪读数。
- **地图**：内置日本概略底图与全国铁路 SQLite 数据。日本境外或尚未定位时从东京新宿駅开始浏览。点击铁路打开可收起的悬浮资料卡，查看运营方、分类、日文站名和来源。可切换地理院标准地图或实验矢量图。标准地图可按区域下载，在离线时直接读取本机瓦片。
- **行程**：开始记录后由定位前台服务支持锁屏采样。轨迹保存在本机，可停止、删除或导出 CSV。
- **数据**：管理铁路库版本、下载更新、清理地图缓存；可打开 JR 各公司公开的官方运行信息。

应用内置国土交通省 2025 年度铁路资料，首次离线打开也会导入。匹配使用多次精确 GPS 观测、位移方向和相连线形。遇到平行线路、分岔、隧道断流或低精度时会暂停或缩短推断。前方站点是几何推断，实际列车可能通过不停站。线路归属遵循数据源登记口径，和列车服务名可能不同。

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

1. 打开应用，允许精确位置即可开始观测。锁屏采样需要在 LIVE 页开始行程记录，建议允许通知。
2. 点“全屏”进入沉浸模式，点“退出全屏”或返回键恢复导航。
3. 地图默认打开内置底图。放大查看车站，点击铁路线查看其资料。点“下载标准地图”保存当前范围。最多 5 个区域，每区最多 600 个瓦片。断网后选“地理院标准地图”查看，LIVE 会自动使用已下载区域。
4. 在数据页联网检查铁路库更新，或重新下载本年度资料。已安装版本在下载或导入失败之后仍可使用。
5. 从数据页或线路资料页打开铁路公司的公开运行信息。
6. 在行程页导出 CSV。轨迹保存在本机，导出文件含精确位置。

数据来源、许可和实时覆盖见 [数据与许可](docs/DATA_SOURCES.md)。
