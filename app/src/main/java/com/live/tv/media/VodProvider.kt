package com.live.tv.media

import okhttp3.OkHttpClient
import org.json.JSONObject

/** Native catalog contract; additional protocols can implement this without changing UI/LAN. */
internal interface VodProvider {
    fun catalog(categoryId: String, query: String, page: Int, request: VodRequest): JSONObject
    fun detail(vodId: String, request: VodRequest): VodParsedDetail
    fun probe(locator: String, request: VodRequest): JSONObject

    companion object {
        fun create(source: VodSource, client: OkHttpClient): VodProvider =
            if (WsyzyProvider.supports(source)) WsyzyProvider(source, client) else MacCmsProvider(source, client)
    }
}
