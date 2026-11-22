package jp.jrfanmap.rail

data class LineFact(val text: String, val source: String)
fun RailLine.fact(): LineFact? = when (name) {
    "山手線" -> LineFact("东京的环状列车连接東京、品川、渋谷、新宿、池袋和上野。列车运行的环线范围与铁路登记的山手線线段定义不同。",
        "https://www.jreast.co.jp/map/")
    "東海道新幹線" -> LineFact("1964 年开通，连接東京与新大阪，是日本最早开业的新干线。",
        "https://global.jr-central.co.jp/en/company/about_shinkansen/")
    "山陽新幹線" -> LineFact("连接新大阪与博多，并与東海道新幹線及九州新幹線接轨，部分列车跨线运行。",
        "https://www.westjr.co.jp/global/en/timetable/")
    "東北新幹線" -> LineFact("线路由東京经仙台、盛岡到新青森，并衔接北海道新幹線。部分服务在盛岡或其他车站与支线列车分合。",
        "https://www.jreast.co.jp/multi/en/routemaps/")
    "北海道新幹線" -> LineFact("2016 年开通新青森至新函館北斗段，经青函隧道连接本州和北海道。",
        "https://www.jrhokkaido.co.jp/shinkansen_h5/")
    else -> null
}
fun RailLine.categoryLabel() = when (category) {
    "1" -> "新幹線"
    "2" -> "JR在来線"
    "3" -> "公営鉄道"
    "4" -> "民営鉄道"
    "5" -> "第三セクター等"
    else -> "鉄道"
}
