# 验证记录

日期：2026-11-19（Asia/Shanghai）。下文保留首版原型的本地验证结果。当前仓库已加入自动构建/发布流程，后续远程检查和对应源码提交以 [Actions 运行记录](https://github.com/jpysina/JRFANMAP/actions/workflows/android.yml) 为准。

## 通过

- 官方 Android 构建工具链完成安装，Gradle Wrapper 9.9.1 固定分发 SHA-256；JDK 和命令行工具下载经过校验，Java 使用系统证书信任库，未关闭 TLS 验证。
- `python3 scripts/bootstrap-cloud.py` 完整执行并重复运行成功，覆盖工具检测、SDK 安装、`:app:assembleDebug`、`:app:testDebugUnitTest`、`:app:lintDebug`。
- 11 个 JVM 测试：FixPolicy 4 个、ODPT 解析 4 个、离线瓦片预算 3 个；0 失败、0 错误、0 跳过。
- APK 已产出，Android v2 签名验证通过；调试签名，仅供开发/个人试用，未做商店发行签名。
- API 35 x86_64 模拟器成功安装 APK，启动 Activity 并渲染了监视页。此项不等于五页交互、定位或后台记录通过。
- Git diff 空白检查通过。依赖、构建、SDK 和本机配置不写入源码；无真实 API Key。

## 未通过或未完成

- `connectedDebugAndroidTest` 未通过：软件模拟器无 KVM，ADB 属性查询触发 `ShellCommandUnresponsiveException`，Gradle 将设备判为 Unknown API Level / 无兼容设备。没有实际完成测试用例，不能计为通过。
- 随后使用 ADB 直接安装测试 APK 并运行 instrumentation。模拟器出现 **System UI isn't responding**，未得到任何已完成用例结果，主动停止测试与模拟器后 runner 报告 `Process crashed`。原因未在应用层完全排除，不将此结果解释为应用测试成功。五页导航、SQLite 导出/恢复与 Keystore 设备测试源码已提供，需在响应正常的模拟器或真机重跑。
- 真实卫星、GNSS 速度精度、车内磁场/气压、隧道断流、长时间锁屏及 OEM 省电策略：无真机，尚未验证。
- 地图在线瓦片/字体及完成区域的断网重启显示：当前云实例网络策略未应用新域名，尚未完成在线与离线端到端验证。官方项目文档与样式通过 GitHub 读取，已确认全国矢量数据路径和出处要求。
- JR 东日本 / ODPT：无真实用户授权 Key，未验证生产响应和线路覆盖。应用提供官方网页入口、Key 配置、ODPT 请求/解析和前台分钟刷新，不承诺实时列车位置。
- 首次本地验证时尚未运行远程 CI。当前流程在 `main` / `v*` 推送后自动构建并发布完整 APK；PR 仅执行检查。Google Play 发布与真机验证仍未进行。

Lint 保留非阻断的建议：targetSdk 36 并非当前最高版本、部分工具依赖有更新、KTX 风格建议；无 Lint 错误。当前 compileSdk 37.2 用于最新稳定 Compose BOM，最低 Android 8.0。

## 真机验证步骤

1. 安装调试 APK，首次无权限应显示空值，地图默认东京并明确尚无设备位置。
2. 开启系统定位，开始记录并授予精确位置与通知；靠近车窗，核对 GNSS、速度、精度和更新时间。模拟位置须显示模拟标记。
3. 锁屏记录至少 15 分钟，再打开；检查行程点、断点和通知停止。撤销权限或关闭定位时不得继续声称速度有效；超过 10 秒无新位置，速度留空、地图标记过期。
4. 在停止后导出 CSV，核对行程点；强制结束进程后重新打开，未完成行程应标中断；确认删除需要二次操作。
5. 下载小范围地图至完成，飞行模式下重启，验证所选层级瓦片、字体和地图出处；范围外缺图应符合下载说明。
6. 应用内输入自己的 ODPT Key；核对真实授权响应、源发布时间、错误和空数组表现。不要把轨迹或 Key 写进聊天、源码或日志。

设备端测试命令见 README 和环境启动说明。真机测试通过前不宣称正式生产就绪。
