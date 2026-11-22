package jp.jrfanmap.data

import jp.jrfanmap.map.TileBudget
import jp.jrfanmap.map.isGsiPng
import org.junit.Assert.*
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class GsiTileTest {
    @Test fun downloadPlanContainsKnownTokyoTileAndRejectsExcessiveRegions() {
        val plan = TileBudget.tiles(35.687, 35.68, 139.775, 139.764, 11, 11)
        assertTrue(plan.any { it.path == "11/1819/806.png" })
        assertEquals(TileBudget.count(35.687, 35.68, 139.775, 139.764, 11, 11), plan.size)
        assertThrows(IllegalArgumentException::class.java) {
            TileBudget.tiles(84.0, -84.0, 180.0, -180.0, 4, 16)
        }
    }

    @Test fun refusesHtmlAndTruncatedPngFromAnUpstreamFailure() {
        val buffer = ByteArrayOutputStream()
        ImageIO.write(BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB), "png", buffer)
        val png = buffer.toByteArray()
        assertTrue(isGsiPng(png))
        assertFalse(isGsiPng(png.copyOf(png.size - 6)))
        assertFalse(isGsiPng("<html>Not found</html>".toByteArray()))
    }
}
