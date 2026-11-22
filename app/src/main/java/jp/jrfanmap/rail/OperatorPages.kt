package jp.jrfanmap.rail

data class OperatorPage(val operator: String, val title: String, val url: String)
val operatorPages = listOf(
    OperatorPage("北海道旅客鉄道", "JR北海道", "https://www3.jrhokkaido.co.jp/webunkou/"),
    OperatorPage("東日本旅客鉄道", "JR東日本", "https://traininfo.jreast.co.jp/train_info/kanto.aspx"),
    OperatorPage("東海旅客鉄道", "JR東海", "https://traininfo.jr-central.co.jp/zairaisen/index.html"),
    OperatorPage("西日本旅客鉄道", "JR西日本", "https://trafficinfo.westjr.co.jp/"),
    OperatorPage("四国旅客鉄道", "JR四国", "https://www.jr-shikoku.co.jp/info/"),
    OperatorPage("九州旅客鉄道", "JR九州", "https://www.jrkyushu.co.jp/trains/info/")
)
