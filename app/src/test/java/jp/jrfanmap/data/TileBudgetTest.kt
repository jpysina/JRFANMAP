package jp.jrfanmap.data

import jp.jrfanmap.map.TileBudget
import org.junit.Assert.*
import org.junit.Test

class TileBudgetTest {
    @Test fun smallAreaHasBoundedBudget() { assertTrue(TileBudget.count(35.69, 35.68, 139.78, 139.76, 10, 12) in 3..20) }
    @Test fun worldDownloadExceedsLimit() { assertTrue(TileBudget.count(80.0, -80.0, 180.0, -180.0, 4, 16) > 1500) }
    @Test fun reversedOrWrappedBoundsAreRejected() {
        assertEquals(Int.MAX_VALUE, TileBudget.count(35.0, 36.0, 140.0, 139.0, 4, 6))
        assertEquals(Int.MAX_VALUE, TileBudget.count(36.0, 35.0, -179.0, 179.0, 4, 6))
    }
}
