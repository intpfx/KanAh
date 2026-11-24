package com.live.tv

import android.app.Activity
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

/** HOME eligibility is separate from the ordinary TV/app entry and from the user's default choice. */
internal object DesktopMode {
    const val HOME_REQUEST = 620
    enum class HomeStatus { DISABLED, DEFAULT, NOT_DEFAULT, ROLE_NOT_EFFECTIVE, UNKNOWN }

    fun component(context: Context) = ComponentName(context.packageName, "${context.packageName}.DesktopHome")

    @JvmStatic fun returnToHome(activity: Activity) {
        try {
            activity.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            activity.moveTaskToBack(true)
        } catch (_: SecurityException) {
            // Some TV launchers reserve starting HOME for the system.
            activity.moveTaskToBack(true)
        }
    }

    // PackageManager persists component overrides. Read that state directly instead of keeping a
    // second preference that could disagree after a system restore or an interrupted setting change.
    private fun enabledState(context: Context): Boolean? = try {
        when (context.packageManager.getComponentEnabledSetting(component(context))) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> context.packageManager
                .getActivityInfo(component(context), PackageManager.MATCH_DISABLED_COMPONENTS).enabled
            else -> false
        }
    } catch (_: PackageManager.NameNotFoundException) { null }
    catch (_: IllegalArgumentException) { null }
    catch (_: SecurityException) { null }

    fun isEnabled(context: Context) = enabledState(context) == true

    @Synchronized fun setEnabled(context: Context, enabled: Boolean): Boolean {
        val current = enabledState(context) ?: return false
        if (current == enabled) return true
        return try {
            context.packageManager.setComponentEnabledSetting(component(context),
                if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP)
            enabledState(context) == enabled
        } catch (_: IllegalArgumentException) { false }
        catch (_: SecurityException) { false }
    }

    fun isDefaultHome(context: Context) = homeStatus(context) == HomeStatus.DEFAULT

    fun homeStatus(context: Context): HomeStatus {
        val enabled = enabledState(context) ?: return HomeStatus.UNKNOWN
        if (!enabled) return HomeStatus.DISABLED
        // Some TV firmware grants ROLE_HOME while its actual HOME route still names the vendor
        // launcher. Resolve the unrestricted system intent; role ownership alone is not success.
        val resolved = try {
            context.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
        } catch (_: SecurityException) { return HomeStatus.UNKNOWN }
        catch (_: IllegalArgumentException) { return HomeStatus.UNKNOWN }
        if (resolved == null) return HomeStatus.UNKNOWN
        if (resolved.packageName == context.packageName &&
            (resolved.name == component(context).className || resolved.name == MainActivity::class.java.name))
            return HomeStatus.DEFAULT
        return if (hasHomeRole(context)) HomeStatus.ROLE_NOT_EFFECTIVE else HomeStatus.NOT_DEFAULT
    }

    private fun hasHomeRole(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return try {
            val roles = context.getSystemService(RoleManager::class.java)
            roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME) && roles.isRoleHeld(RoleManager.ROLE_HOME)
        } catch (_: SecurityException) { false }
        catch (_: IllegalArgumentException) { false }
    }

    fun requestHomeSelection(activity: Activity): Boolean = openHomeSelection(activity) {
        activity.startActivityForResult(it, HOME_REQUEST)
    }

    internal fun openHomeSelection(context: Context, launch: (Intent) -> Unit): Boolean {
        if (!isEnabled(context)) return false
        // API 29+ role requests always show system-owned consent. Never set a role holder directly.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val roles = context.getSystemService(RoleManager::class.java)
                if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME) && !roles.isRoleHeld(RoleManager.ROLE_HOME)) {
                    if (tryLaunch(roles.createRequestRoleIntent(RoleManager.ROLE_HOME), launch)) return true
                }
            } catch (_: SecurityException) { /* Use the system settings fallback. */ }
            catch (_: IllegalArgumentException) { /* HOME is not implemented by this OEM. */ }
        }
        return listOf(Settings.ACTION_HOME_SETTINGS, Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, Settings.ACTION_SETTINGS)
            .any { tryLaunch(Intent(it), launch) }
    }

    fun openSystemSettings(context: Context): Boolean = openSystemSettings { context.startActivity(it) }

    /** One sidebar control: enable and ask for HOME, or disable when it is already on. */
    fun toggleFromSidebar(activity: Activity): String {
        if (isEnabled(activity)) {
            return if (setEnabled(activity, false)) "桌面模式已关闭" else "此设备无法更改桌面模式，请在系统设置中检查应用"
        }
        if (!setEnabled(activity, true)) return "此设备无法开启桌面模式，请在系统设置中检查应用"
        return if (requestHomeSelection(activity)) "桌面模式已开启，请在系统中选择 Maci"
        else "桌面模式已开启；此设备未提供默认桌面选择入口"
    }

    internal fun openSystemSettings(launch: (Intent) -> Unit): Boolean = tryLaunch(Intent(Settings.ACTION_SETTINGS), launch)

    private fun tryLaunch(intent: Intent, launch: (Intent) -> Unit): Boolean = try {
        launch(intent)
        true
    } catch (_: ActivityNotFoundException) { false }
    catch (_: SecurityException) { false }
}
