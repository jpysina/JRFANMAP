# 数据来源与许可

| 用途 | 来源 | 覆盖 |
| --- | --- | --- |
| 铁路几何、线路、运营方和日文站名 | [国土交通省「国土数値情報（鉄道データ）」2025 年度版](https://nlftp.mlit.go.jp/ksj/gml/datalist/KsjTmplt-N02-2025.html) | 全国旅客铁路及轨道，基准日 2025-12-31。 |
| 内置日本概略底图 | [Natural Earth 1:50m Admin 0 Countries](https://github.com/nvkelso/natural-earth-vector/blob/master/geojson/ne_50m_admin_0_countries.geojson) | 日本概略边界，Public Domain。 |
| 可选详细矢量底图 | [国土地理院ベクトルタイル提供実験](https://github.com/gsi-cyberjapan/gsimaps-vector-experiment) | 可联网浏览和按区域下载的实验瓦片，原生层级 4–16。 |
| 铁路公告 | [GTFS Realtime Service Alerts](https://gtfs.org/documentation/realtime/feed-entities/service-alerts/) | 公开 HTTPS 端点，实际覆盖取决于提供方。 |

出典：国土交通省「国土数値情報（鉄道データ）」を加工して作成。[数据页](https://nlftp.mlit.go.jp/ksj/gml/datalist/KsjTmplt-N02-2025.html) · [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/)

由官方 UTF-8 GeoJSON 提取线路坐标与运营分类，将站点线形转为中心点，保留原始日文名称。基于资料整理的线路科普为应用自写摘要，详情页附来源。应用内置的压缩种子可用 `python3 scripts/prepare-rail-seed.py` 重建，脚本验证对应下载的 SHA-256。首次启动导入本机独立 SQLite 库。数据页从官方清单发现新版，校验压缩包结构与涵盖数量，在事务中替换数据。行程库和公告快照与铁路库分开。几何推断使用坐标近似，站点及轨道与实际运行服务可能存在差异。

Natural Earth 概略底图仅取日本要素。国土地理院详细地图保留出典和服务条款。该实验服务的接口和内容可能变化。[Natural Earth 使用条款](https://www.naturalearthdata.com/about/terms-of-use/) · [国土地理院使用条款](https://www.gsi.go.jp/kikakuchousei/kikakuchousei40182.html)

## 运营信息调研

- JR 东日本、西日本、四国等运营方提供网页公告。JR 西日本另有官方列车位置页面。应用内提供官方页面入口，避免将网页结构当作稳定的开放接口。[JR東日本](https://traininfo.jreast.co.jp/train_info/kanto.aspx) · [JR西日本](https://trafficinfo.westjr.co.jp/) · [JR西日本列車走行位置](https://www.train-guide.westjr.co.jp/) · [JR四国](https://www.jr-shikoku.co.jp/info/)
- Transitous 日本目录包含静态铁路 GTFS、部分电车及公交动态源，目录中的东京铁路公告和位置端点在本环境请求返回 HTTP 403，未设为默认源。TokyoGTFS 的静态铁路数据来自 Mini Tokyo 3D，其车辆动态集成涉及 ODPT。HokkaidoRailGTFS 是静态时刻表工具，无法当作全国实时源。[Transitous 日本目录](https://github.com/public-transport/transitous/blob/main/feeds/jp.json) · [TokyoGTFS](https://github.com/MKuranowski/TokyoGTFS) · [Mini Tokyo 3D](https://github.com/nagix/mini-tokyo-3d) · [HokkaidoRailGTFS](https://github.com/MKuranowski/HokkaidoRailGTFS)
- GTFS Japan 与 Mobility Database 可查找地区数据。已实现无需 ODPT 和账号的完整 Service Alerts 快照读取，保留源时间、获取时间及最近缓存。所选来源须允许客户端访问和保存。暂无经验证可独立于 ODPT 使用的全国 JR 实时源。[GTFS Japan](https://www.gtfs.jp/) · [Mobility Database](https://mobilitydatabase.org/)

LIVE 的线路、车速和前方站点来自设备观测与静态铁路库，不受公告源是否配置影响。运营方公告显示为独立来源，空公告不能推出正常运行，GTFS route ID 也不会被直接当成国土交通省线路 ID。
