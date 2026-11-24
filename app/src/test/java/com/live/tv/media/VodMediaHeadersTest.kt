package com.live.tv.media

import android.content.Context
import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VodMediaHeadersTest {
    private fun spec(url: String) = JSONObject().put("url",url).put("headers",JSONObject()
        .put("User-Agent","LiveFixture").put("Referer","https://media.example/").put("Origin","https://media.example")
        .put("Authorization","secret-token").put("Cookie","private=session").put("X-Api-Key","secret-key"))

    private fun capture(server:ServerSocket,response:String):Map<String,String> = server.accept().use { socket ->
        val reader=socket.getInputStream().bufferedReader();reader.readLine()
        val headers=mutableMapOf<String,String>()
        while(true){val line=reader.readLine();if(line.isNullOrEmpty())break
            headers[line.substringBefore(':').lowercase()]=line.substringAfter(':').trim()}
        socket.getOutputStream().write(response.toByteArray());headers
    }
    private val ok="HTTP/1.1 200 OK\r\nContent-Length: 1\r\nConnection: close\r\n\r\nx"
    private fun open(context:Context,spec:JSONObject,url:String){
        val source=VodMediaHttp.factory(context,spec).createDataSource()
        try{source.open(DataSpec(Uri.parse(url)))}finally{source.close()}
    }
    private fun assertPortable(headers:Map<String,String>){
        assertEquals("LiveFixture",headers["user-agent"]);assertEquals("https://media.example/",headers["referer"])
        assertEquals("https://media.example",headers["origin"])
    }
    private fun assertNoCredentials(headers:Map<String,String>){
        assertFalse(headers.containsKey("authorization"));assertFalse(headers.containsKey("cookie"));assertFalse(headers.containsKey("x-api-key"))
    }
    @Test fun sameOriginKeepsCredentialsButCrossOriginSegmentsKeepOnlyPortableHeaders() {
        val context:Context=ApplicationProvider.getApplicationContext()
        ServerSocket(0).use { server ->
            val executor=Executors.newSingleThreadExecutor()
            try{
                for(same in listOf(true,false)){
                    val captured=executor.submit<Map<String,String>>{capture(server,ok)}
                    val actual="http://127.0.0.1:${server.localPort}/segment.ts"
                    val origin=if(same)actual else "http://127.0.0.1:1/master.m3u8"
                    open(context,spec(origin),actual)
                    val headers=captured.get(3,TimeUnit.SECONDS);assertPortable(headers)
                    if(same){assertEquals("secret-token",headers["authorization"]);assertEquals("private=session",headers["cookie"]);assertEquals("secret-key",headers["x-api-key"])}
                    else assertNoCredentials(headers)
                }
            }finally{executor.shutdownNow()}
        }
    }
    @Test fun redirectCannotCarryCredentialsToAnotherOriginAndDoesNotLoadHistoricalCache() {
        val base:Context=ApplicationProvider.getApplicationContext()
        val dir=java.nio.file.Files.createTempDirectory("vod-online-http").toFile()
        val legacy=java.io.File(dir,"media-library.json").apply{writeText("historical database")}
        val cache=java.io.File(dir,"media-cache.json").apply{writeText("historical cache metadata")}
        val context=object:android.content.ContextWrapper(base){override fun getFilesDir()=dir}
        ServerSocket(0).use { origin -> ServerSocket(0).use { cdn ->
            val executor=Executors.newFixedThreadPool(2)
            try{
                val url="http://127.0.0.1:${origin.localPort}/master.m3u8"
                val first=executor.submit<Map<String,String>>{capture(origin,"HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:${cdn.localPort}/media.m3u8\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")}
                val redirected=executor.submit<Map<String,String>>{capture(cdn,ok)}
                open(context,spec(url),url)
                assertEquals("secret-token",first.get(3,TimeUnit.SECONDS)["authorization"])
                val headers=redirected.get(3,TimeUnit.SECONDS);assertPortable(headers);assertNoCredentials(headers)
                assertEquals("historical database",legacy.readText());assertEquals("historical cache metadata",cache.readText())
                assertEquals(setOf("media-library.json","media-cache.json"),dir.list()!!.toSet())
            }finally{executor.shutdownNow()}
        }}
    }
    @Test fun rejectsLocalSmbAndUrlEmbeddedCredentialsBeforePlayback() {
        val context:Context=ApplicationProvider.getApplicationContext()
        for(url in listOf("file:///legacy/movie.mp4","smb://legacy/share/movie.mp4","https://user:secret@media.example/movie.m3u8"))
            assertThrows(IllegalArgumentException::class.java){VodMediaHttp.factory(context,spec(url))}
    }
}
