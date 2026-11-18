package jp.jrfanmap.data

import org.junit.Assert.*
import org.junit.Test

class RailParserTest {
    @Test fun localizesOperatorNoticeAndPreservesTimestamp() {
        val notices = RailParser.parse("""[{"odpt:railway":"odpt.Railway:JR-East.ChuoRapid","odpt:trainInformationStatus":{"ja":"遅延","en":"Delay"},"odpt:trainInformationText":{"ja":"強風の影響"},"dc:date":"2026-11-19T09:00:00+09:00"}]""")
        assertEquals("ChuoRapid", notices.single().railway)
        assertEquals("遅延", notices.single().status)
        assertEquals("強風の影響", notices.single().text)
        assertEquals("2026-11-19T09:00:00+09:00", notices.single().published)
    }
    @Test fun emptyResponseDoesNotCreateNormalOperationNotice() { assertTrue(RailParser.parse("[]").isEmpty()) }
    @Test fun missingStatusIsUnknown() { assertEquals("状态未提供", RailParser.parse("[{}]").single().status) }
    @Test(expected = org.json.JSONException::class) fun rejectsNonArrayResponse() { RailParser.parse("{\"error\":\"denied\"}") }
}
