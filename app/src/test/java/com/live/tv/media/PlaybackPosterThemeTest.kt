package com.live.tv.media

import android.graphics.Bitmap
import android.graphics.Color
import java.io.ByteArrayOutputStream
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlaybackPosterThemeTest {
    @Test fun onlyThreeConsecutiveFullyTransparentSuccessfulCopiesTriggerFallback() {
        val health = PlaybackSurfaceCopyHealth()
        val bitmap = Bitmap.createBitmap(48, 27, Bitmap.Config.ARGB_8888)
        try {
            repeat(2) { assertFalse(health.needsPosterFallback(true, bitmap)) }
            bitmap.eraseColor(Color.BLACK)
            repeat(10) { assertFalse(health.needsPosterFallback(true, bitmap)) }
            bitmap.eraseColor(Color.TRANSPARENT)
            repeat(2) { assertFalse(health.needsPosterFallback(true, bitmap)) }
            assertFalse(health.needsPosterFallback(false, bitmap))
            repeat(2) { assertFalse(health.needsPosterFallback(true, bitmap)) }
            assertTrue(health.needsPosterFallback(true, bitmap))
            health.reset()
            assertFalse(health.needsPosterFallback(true, bitmap))
            bitmap.setPixel(47, 26, Color.BLUE)
            assertFalse(health.needsPosterFallback(true, bitmap))
        } finally { bitmap.recycle() }
    }

    @Test fun boundedPosterDownloadDecodesColorWithoutCredentials() {
        val bitmap = Bitmap.createBitmap(512, 768, Bitmap.Config.ARGB_8888)
        val bytes = try {
            bitmap.eraseColor(Color.rgb(30, 100, 180))
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        } finally { bitmap.recycle() }
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            assertNull(chain.request().header("Authorization"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(bytes.toResponseBody()).build()
        }.build()
        val color = PlaybackPosterTheme.load("https://images.example/poster.png", client, VodRequest())
        assertNotNull(color)
        val hsv = FloatArray(3); Color.colorToHSV(color!!, hsv)
        assertEquals(212f, hsv[0], 3f)
        assertEquals(1, requests)
        assertNull(PlaybackPosterTheme.load("https://secret:password@images.example/a", client, VodRequest()))
        assertNull(PlaybackPosterTheme.load("file:///a", client, VodRequest()))
        val cancelled = VodRequest().also { it.cancel() }
        assertNull(PlaybackPosterTheme.load("https://images.example/a", client, cancelled))
        assertEquals(1, requests)
    }

    @Test fun invalidImagesAndOversizedResponsesRemainOptional() {
        assertNull(PlaybackPosterTheme.decode(byteArrayOf(1, 2, 3)))
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(ByteArray(2 * 1024 * 1024 + 1).toResponseBody()).build()
        }.build()
        assertNull(PlaybackPosterTheme.load("https://images.example/a", client, VodRequest()))
    }
}
