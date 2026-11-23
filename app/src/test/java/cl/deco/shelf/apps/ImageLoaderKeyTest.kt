package cl.deco.shelf.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageLoaderKeyTest {
    @Test fun elTamanoFormaParteDeLaClave() {
        // Continuar viendo (320x180) y Top Shelf (cardW x cardH) comparten URI pero no bitmap
        assertNotEquals(uriCacheKey("https://x/a.jpg", 320, 180), uriCacheKey("https://x/a.jpg", 180, 101))
    }
    @Test fun mismoUriYTamanoComparten() = assertEquals(uriCacheKey("content://m/1", 320, 180), uriCacheKey("content://m/1", 320, 180))
    @Test fun uriDistintoNoComparte() = assertNotEquals(uriCacheKey("https://x/a.jpg", 320, 180), uriCacheKey("https://x/b.jpg", 320, 180))
    @Test fun formatoDeLaClave() = assertEquals("u:https://x/a.jpg:320x180", uriCacheKey("https://x/a.jpg", 320, 180))

    @Test fun filtroDeSeguridadDeUris() {
        assertTrue(isSafeCardUri("https://cdn.example.com/poster.jpg"))
        assertTrue(isSafeCardUri("content://android.media.tv/program/1"))
        assertTrue(isSafeCardUri("android.resource://cl.deco.shelf/drawable/banner"))
        assertFalse(isSafeCardUri("http://cdn.example.com/poster.jpg"))
        assertFalse(isSafeCardUri("https://localhost/a.jpg"))
        assertFalse(isSafeCardUri("https://tv.local/a.jpg"))
        assertFalse(isSafeCardUri("https://nas.home.arpa/a.jpg"))
        assertFalse(isSafeCardUri("https://127.0.0.1/a.jpg"))
        assertFalse(isSafeCardUri("https://192.168.1.10/a.jpg"))
        assertFalse(isSafeCardUri("https://10.0.0.5/a.jpg"))
        assertFalse(isSafeCardUri("https://172.16.4.2/a.jpg"))
        assertFalse(isSafeCardUri("https://169.254.1.1/a.jpg"))
        assertFalse(isSafeCardUri("https://[::1]/a.jpg"))
        assertFalse(isSafeCardUri("file:///sdcard/a.jpg"))
    }
}
