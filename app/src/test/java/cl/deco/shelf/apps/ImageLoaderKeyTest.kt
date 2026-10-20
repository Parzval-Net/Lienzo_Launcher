package cl.deco.shelf.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ImageLoaderKeyTest {
    @Test fun elTamanoFormaParteDeLaClave() {
        // Continuar viendo (320x180) y Top Shelf (cardW x cardH) comparten URI pero no bitmap
        assertNotEquals(uriCacheKey("https://x/a.jpg", 320, 180), uriCacheKey("https://x/a.jpg", 180, 101))
    }
    @Test fun mismoUriYTamanoComparten() = assertEquals(uriCacheKey("content://m/1", 320, 180), uriCacheKey("content://m/1", 320, 180))
    @Test fun uriDistintoNoComparte() = assertNotEquals(uriCacheKey("https://x/a.jpg", 320, 180), uriCacheKey("https://x/b.jpg", 320, 180))
    @Test fun formatoDeLaClave() = assertEquals("u:https://x/a.jpg:320x180", uriCacheKey("https://x/a.jpg", 320, 180))
}
