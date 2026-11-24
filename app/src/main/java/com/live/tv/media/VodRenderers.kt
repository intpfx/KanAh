package com.live.tv.media

import android.content.Context
import android.os.Build
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

@UnstableApi
internal object VodRenderers {
    // The vendor AAC component advertises only one concurrent instance on this TV.
    val supportsParallelPlayback: Boolean get() = Build.DEVICE != "freeguy" || Build.MODEL != "MiTV-MFTR0"

    fun create(context: Context): DefaultRenderersFactory {
        val factory = DefaultRenderersFactory(context)
        if (!supportsParallelPlayback) {
            factory.setMediaCodecSelector(MediaCodecSelector(::decoders))
                .setEnableDecoderFallback(true)
        }
        return factory
    }

    private fun decoders(mimeType: String, secure: Boolean, tunneling: Boolean): List<MediaCodecInfo> {
        val defaults = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling)
        if (mimeType != MimeTypes.AUDIO_AAC || secure || tunneling) return defaults
        // This TV advertises its working MediaTek AAC decoder under a vendor MIME type.
        val hardware = MediaCodecSelector.DEFAULT.getDecoderInfos("audio/mp4a-latm-hw", false, false)
            .firstOrNull { it.name == "OMX.MS.AAC.Decoder" } ?: return defaults
        return listOf(MediaCodecInfo.newInstance(hardware.name, mimeType, hardware.codecMimeType,
            hardware.capabilities, true, false, true, false, false)) + defaults
    }
}
