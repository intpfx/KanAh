package com.live.tv.remote

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.live.tv.LanConfig
import org.json.JSONObject
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Authenticated configuration surface; deliberately exposes no arbitrary TV keys or shell input. */
class RemoteWebManager internal constructor(context: Context, private val access: RemoteWebAccess = RemoteWebAccess()) : LanConfig.RemoteSettings {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val preferences = RemotePreferences(app)

    fun openAccess(): RemoteWebAccess.Grant = access.openAccess()
    fun renewAccess(): RemoteWebAccess.Grant = access.renewAccess()
    fun currentAccess(): RemoteWebAccess.Grant? = access.currentAccess()
    fun closeAccess() = access.closeAccess()
    override fun authorized(token: String): Boolean = access.authorized(token)

    override fun unlock(code: String): String {
        val token = access.unlock(code)
        return if (token == null) JSONObject().put("ok", false).put("authorized", false)
            .put("message", "管理码无效、已过期或尝试次数已用尽，请在电视上更新管理码").toString()
        else JSONObject().put("ok", true).put("authorized", true).put("token", token).toString()
    }

    override fun state(token: String): String {
        if (!authorized(token)) return JSONObject().put("authorized", false)
            .put("managementAvailable", access.managementAvailable()).toString()
        return snapshot().toString()
    }

    private fun snapshot(): JSONObject {
        val status = RemoteReceiverService.status(app)
        val now = System.currentTimeMillis()
        val pairing = preferences.enabled && status.pairingUntil > now
        return JSONObject().put("authorized", true).put("managementAvailable", access.managementAvailable())
            .put("enabled", preferences.enabled).put("deviceName", preferences.deviceName)
            .put("protocolState", status.protocolState).put("adbState", status.adbState)
            .put("detail", status.detail).put("localIp", status.localIp ?: JSONObject.NULL)
            .put("serverNow", now).put("pairingUntil", if (pairing) status.pairingUntil else 0)
            .put("pin", if (pairing) preferences.pin else "")
    }

    override fun control(token: String, form: Map<String, String>): String {
        if (!authorized(token)) throw SecurityException("management session expired")
        val action = form["action"] ?: ""
        val name = form["value"].orEmpty().filter { !it.isISOControl() }.trim()
        if (action !in ACTIONS || (action == "save_name" && (name.isEmpty() || name.length > 48))) {
            return snapshot().put("ok", false).put("message", "无效的设置，设备名称需为 1–48 个字符").toString()
        }
        return onMain {
            if (!authorized(token)) throw SecurityException("management session expired")
            if (action == "logout") {
                access.logout(token)
                return@onMain JSONObject().put("ok", true).put("authorized", false)
                    .put("managementAvailable", access.managementAvailable()).put("message", "已退出管理").toString()
            }
            when (action) {
                "enable" -> { preferences.enabled = true; RemoteReceiverService.start(app) }
                "disable" -> RemoteReceiverService.stop(app)
                "save_name" -> { preferences.deviceName = name; if (preferences.enabled) RemoteReceiverService.start(app) }
                "open_pairing" -> {
                    if (!preferences.enabled) return@onMain snapshot().put("ok", false).put("message", "请先开启 Apple TV 遥控").toString()
                    RemoteReceiverService.openPairingWindow(app)
                }
                "close_pairing" -> RemoteReceiverService.closePairingWindow(app)
                "rotate_pin" -> {
                    if (!preferences.enabled) return@onMain snapshot().put("ok", false).put("message", "请先开启 Apple TV 遥控，再更新配对码").toString()
                    RemoteReceiverService.rotatePairingPin(app)
                }
                "forget" -> {
                    if (!preferences.enabled) return@onMain snapshot().put("ok", false).put("message", "请先开启 Apple TV 遥控，再清除配对").toString()
                    RemoteReceiverService.forgetControllers(app)
                }
            }
            snapshot().put("ok", true).put("message", "设置请求已发送").toString()
        }
    }

    private fun onMain(action: () -> String): String {
        if (Looper.myLooper() == Looper.getMainLooper()) return action()
        val task = FutureTask(action)
        main.post(task)
        return try { task.get(3, TimeUnit.SECONDS) }
        catch (error: java.util.concurrent.ExecutionException) {
            if (error.cause is SecurityException) throw error.cause as SecurityException
            JSONObject().put("ok", false).put("authorized", true).put("message", "电视暂时无法应用设置，请稍后重试").toString()
        } catch (_: Exception) {
            task.cancel(false)
            JSONObject().put("ok", false).put("authorized", true).put("message", "电视忙碌，请稍后刷新状态重试").toString()
        }
    }

    companion object {
        private val ACTIONS = setOf("enable", "disable", "save_name", "open_pairing", "close_pairing", "rotate_pin", "forget", "logout")
        @Volatile private var instance: RemoteWebManager? = null
        @JvmStatic fun get(context: Context): RemoteWebManager = instance ?: synchronized(this) {
            instance ?: RemoteWebManager(context).also { instance = it }
        }
    }
}
