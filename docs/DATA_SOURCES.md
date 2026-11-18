# 数据来源与许可边界

## 国土地理院

- 官方项目：[gsimaps-vector-experiment](https://github.com/gsi-cyberjapan/gsimaps-vector-experiment)
- 官方样式：[gsivectortile-mapbox-gl-js/std.json](https://github.com/gsi-cyberjapan/gsivectortile-mapbox-gl-js/blob/master/std.json)
- 瓦片：`https://cyberjapandata.gsi.go.jp/xyz/experimental_bvmap/{z}/{x}/{y}.pbf`，原生层级 4–16。
- 字体：`https://maps.gsi.go.jp/xyz/noto-jp/{fontstack}/{range}.pbf`。
- 使用规定：[国土地理院コンテンツ利用規約](https://www.gsi.go.jp/kikakuchousei/kikakuchousei40182.html)。项目官方说明要求注明“国土地理院ベクトルタイル提供実験”等出处。应用设置与地图 attribution 均保留来源。

`app/src/main/assets/gsi-style.json` 从上述官方 std.json 保存，无修改图层内容。以本地样式启动，瓦片及字体仍按需联网或从 MapLibre 缓存读取。实验服务可能更换结构、停止或更新，不保证持续可用。小范围离线下载不等于离线数据再分发许可；商业发布和地图大规模处理应再次核对条款。

## JR 东日本 / ODPT

- [JR 东日本官方运行信息](https://www.jreast.co.jp/t_i/unko.html)：浏览器入口，应用不抓取页面。
- [ODPT 开发者站](https://developer.odpt.org/)；接口 `https://api.odpt.org/api/v4/odpt:TrainInformation`，按 `odpt:operator=odpt.Operator:JR-East` 过滤。
- 用户申请 Key 与相应数据访问权，遵守 ODPT 及各数据提供者的许可、署名和请求频率要求。没有 API Key 不阻碍设备仪表、地图和轨迹记录。
- 客户端实现不证明数据集可用。真实 Key 的授权、JR 东日本覆盖及生产响应尚需验证。空数组、请求失败、缺少状态和过期信息均不能推出“正常运行”。

## 开源组件

Jetpack Compose / AndroidX（Apache 2.0）、Kotlin / kotlinx.coroutines（Apache 2.0）、MapLibre Native（BSD-2-Clause；包含各自第三方组件许可）、Gradle（Apache 2.0）。正式分发前应生成并随包提供完整依赖许可清单。本仓库保留各组件的原始元数据与地图内置 attribution，不使用 Mapbox 商业服务或 Key。
