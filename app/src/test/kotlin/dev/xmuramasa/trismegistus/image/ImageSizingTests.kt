package dev.xmuramasa.trismegistus.image

import kotlin.test.Test
import kotlin.test.assertEquals

class ImageSizingTests {
    @Test
    fun theLongestEdgeBecomes2048() {
        assertEquals(2048 to 1536, ImageSizing.fit(4032, 3024))
        assertEquals(1536 to 2048, ImageSizing.fit(3024, 4032))
        assertEquals(2048 to 2048, ImageSizing.fit(5000, 5000))
    }

    @Test
    fun smallImagesAreNotEnlarged() {
        assertEquals(800 to 600, ImageSizing.fit(800, 600))
        assertEquals(2048 to 10, ImageSizing.fit(2048, 10))
    }

    @Test
    fun aThinSideKeepsAPixel() {
        assertEquals(2048 to 1, ImageSizing.fit(100_000, 3))
    }

    @Test
    fun sampleSizeNeverDecodesBelowTheTarget() {
        assertEquals(1, ImageSizing.sampleSize(2048, 1000))
        assertEquals(1, ImageSizing.sampleSize(4095, 3000))
        assertEquals(2, ImageSizing.sampleSize(4096, 3000))
        assertEquals(4, ImageSizing.sampleSize(9000, 12000))
        assertEquals(1, ImageSizing.sampleSize(640, 480))
    }
}
