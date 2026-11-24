package com.live.tv

import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.provider.Settings
import com.live.tv.media.VodLibraryActivity
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DesktopModeTest {
    private lateinit var context: Context
    private lateinit var manager: PackageManager

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        manager = context.packageManager
        manager.setComponentEnabledSetting(DesktopMode.component(context), PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            PackageManager.DONT_KILL_APP)
        homeResolvesTo("fixture.launcher", "fixture.launcher.Home")
    }

    private fun homeIntent() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    private fun homeResolvesTo(packageName: String, className: String) {
        shadowOf(manager).setResolveInfosForIntent(homeIntent(), listOf(ResolveInfo().apply {
            isDefault = true
            activityInfo = ActivityInfo().apply { this.packageName = packageName; name = className; exported = true }
        }))
    }

    private fun entries(category: String, flags: Int = 0) = manager.queryIntentActivities(
        Intent(Intent.ACTION_MAIN).addCategory(category).setPackage(context.packageName), flags)
        .map { it.activityInfo.name }.toSet()

    @Test fun homeIsOffByDefaultAndTheOrdinaryTvAndLauncherEntriesStayAvailable() {
        assertFalse(DesktopMode.isEnabled(context)); assertFalse(DesktopMode.isDefaultHome(context))
        assertTrue(entries(Intent.CATEGORY_HOME).isEmpty())
        assertEquals(setOf(MainActivity::class.java.name), entries(Intent.CATEGORY_LAUNCHER))
        assertEquals(setOf(MainActivity::class.java.name), entries(Intent.CATEGORY_LEANBACK_LAUNCHER))
        val alias = manager.getActivityInfo(DesktopMode.component(context), PackageManager.MATCH_DISABLED_COMPONENTS)
        assertFalse(alias.enabled); assertTrue(alias.exported)
        assertEquals(MainActivity::class.java.name, alias.targetActivity)
        val internalActivities = listOf(VodLibraryActivity::class.java, TvSourcesActivity::class.java)
        internalActivities.forEach { assertFalse(manager.getActivityInfo(ComponentName(context, it), 0).exported) }
    }

    @Test fun sidebarControlEnablesAndDisablesDesktopModeInOnePlace() {
        val activity = android.app.Activity()
        val context = RuntimeEnvironment.getApplication()
        org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().let { controller ->
            val host = controller.get()
            assertFalse(DesktopMode.isEnabled(host))
            val enabled = DesktopMode.toggleFromSidebar(host)
            assertTrue(enabled, enabled.startsWith("桌面模式已开启"))
            assertTrue(DesktopMode.isEnabled(host))
            assertEquals("桌面模式已关闭", DesktopMode.toggleFromSidebar(host))
            assertFalse(DesktopMode.isEnabled(host))
            controller.pause().stop().destroy()
        }
    }

    @Test fun enablingAndDisablingArePersistentIdempotentAndOnlyAffectTheHomeAlias() {
        val alias = DesktopMode.component(context)
        val main = ComponentName(context, MainActivity::class.java)
        assertTrue(DesktopMode.setEnabled(context, false))
        repeat(2) { assertTrue(DesktopMode.setEnabled(context, true)) }
        assertTrue(DesktopMode.isEnabled(context.applicationContext))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, manager.getComponentEnabledSetting(alias))
        assertEquals(setOf(alias.className), entries(Intent.CATEGORY_HOME, PackageManager.MATCH_DEFAULT_ONLY))
        assertEquals(setOf(MainActivity::class.java.name), entries(Intent.CATEGORY_LAUNCHER))
        assertEquals(setOf(MainActivity::class.java.name), entries(Intent.CATEGORY_LEANBACK_LAUNCHER))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, manager.getComponentEnabledSetting(main))
        repeat(2) { assertTrue(DesktopMode.setEnabled(context, false)) }
        assertFalse(DesktopMode.isEnabled(context.applicationContext))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, manager.getComponentEnabledSetting(alias))
        assertTrue(entries(Intent.CATEGORY_HOME).isEmpty())
        assertEquals(setOf(MainActivity::class.java.name), entries(Intent.CATEGORY_LAUNCHER))
        assertEquals(setOf(MainActivity::class.java.name), entries(Intent.CATEGORY_LEANBACK_LAUNCHER))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, manager.getComponentEnabledSetting(main))
    }

    @Test fun repeatedHomeAndLauncherResolutionUsesTheSameSingleTaskTarget() {
        assertTrue(DesktopMode.setEnabled(context, true))
        val main = manager.getActivityInfo(ComponentName(context, MainActivity::class.java), 0)
        assertEquals(ActivityInfo.LAUNCH_SINGLE_TASK, main.launchMode)
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(context.packageName)
        val explicitHome = Intent(home).setComponent(DesktopMode.component(context))
        repeat(2) {
            listOf(home, explicitHome).forEach { intent ->
                val route = intent.resolveActivityInfo(manager, PackageManager.MATCH_DEFAULT_ONLY)
                assertNotNull(route)
                assertEquals(DesktopMode.component(context).className, route.name)
                assertEquals(main.name, route.targetActivity)
                assertEquals(ActivityInfo.LAUNCH_SINGLE_TASK, route.launchMode)
                assertEquals(main.taskAffinity, route.taskAffinity)
            }
        }
        // Launchers first query their category and launch the returned explicit component. The
        // implicit Intent.resolveActivityInfo helper adds MATCH_DEFAULT_ONLY, unlike a launcher.
        listOf(manager.getLaunchIntentForPackage(context.packageName),
            manager.getLeanbackLaunchIntentForPackage(context.packageName)).forEach { intent ->
            assertNotNull(intent)
            assertEquals(ComponentName(context, MainActivity::class.java), intent!!.component)
            val route = intent.resolveActivityInfo(manager, 0)
            assertNotNull(route)
            assertEquals(main.name, route.name)
            assertEquals(ActivityInfo.LAUNCH_SINGLE_TASK, route.launchMode)
        }
    }

    @Test fun aDisabledHomeAliasNeverStartsASelectionFlow() {
        val launched = mutableListOf<Intent>()
        assertFalse(DesktopMode.openHomeSelection(context) { launched.add(it) })
        assertTrue(launched.isEmpty()); assertFalse(DesktopMode.isEnabled(context))
    }

    @Test fun availableHomeRoleOpensUserConsentWithoutAssigningTheRole() {
        assertTrue(DesktopMode.setEnabled(context, true))
        val roles = context.getSystemService(RoleManager::class.java)
        shadowOf(roles).addAvailableRole(RoleManager.ROLE_HOME)
        val launched = mutableListOf<Intent>()
        assertTrue(DesktopMode.openHomeSelection(context) { launched.add(it) })
        assertEquals(1, launched.size)
        assertEquals("android.app.role.action.REQUEST_ROLE", launched.single().action)
        assertEquals(RoleManager.ROLE_HOME, launched.single().getStringExtra("android.intent.extra.ROLE_NAME"))
        assertFalse(roles.isRoleHeld(RoleManager.ROLE_HOME))
        assertFalse(DesktopMode.isDefaultHome(context)); assertTrue(DesktopMode.isEnabled(context))
    }

    @Test fun anExistingHomeRoleOpensTheSystemChooserSoTheUserCanChangeIt() {
        assertTrue(DesktopMode.setEnabled(context, true))
        val roles = context.getSystemService(RoleManager::class.java)
        shadowOf(roles).addAvailableRole(RoleManager.ROLE_HOME)
        shadowOf(roles).addHeldRole(RoleManager.ROLE_HOME)
        homeResolvesTo(context.packageName, DesktopMode.component(context).className)
        assertTrue(DesktopMode.isDefaultHome(context))
        val launched = mutableListOf<Intent>()
        assertTrue(DesktopMode.openHomeSelection(context) { launched.add(it) })
        assertEquals(listOf(Settings.ACTION_HOME_SETTINGS), launched.map { it.action })
        assertTrue(roles.isRoleHeld(RoleManager.ROLE_HOME))
    }

    @Test fun aGrantedRoleDoesNotClaimSuccessWhenTheSystemStillRoutesHomeElsewhere() {
        assertTrue(DesktopMode.setEnabled(context, true))
        val roles = context.getSystemService(RoleManager::class.java)
        shadowOf(roles).addAvailableRole(RoleManager.ROLE_HOME)
        shadowOf(roles).addHeldRole(RoleManager.ROLE_HOME)
        assertTrue(roles.isRoleHeld(RoleManager.ROLE_HOME))
        assertFalse(DesktopMode.isDefaultHome(context))
        assertEquals(DesktopMode.HomeStatus.ROLE_NOT_EFFECTIVE, DesktopMode.homeStatus(context))
        val launched = mutableListOf<Intent>()
        assertTrue(DesktopMode.openHomeSelection(context) { launched.add(it) })
        assertEquals(Settings.ACTION_HOME_SETTINGS, launched.single().action)
    }

    @Test fun actualHomeRoutingConfirmsTheAliasOrItsTargetEvenWithoutRoleManagerSupport() {
        assertTrue(DesktopMode.setEnabled(context, true))
        assertFalse(context.getSystemService(RoleManager::class.java).isRoleAvailable(RoleManager.ROLE_HOME))
        listOf(DesktopMode.component(context).className, MainActivity::class.java.name).forEach { target ->
            homeResolvesTo(context.packageName, target)
            assertTrue(DesktopMode.isDefaultHome(context))
            assertEquals(DesktopMode.HomeStatus.DEFAULT, DesktopMode.homeStatus(context))
        }
    }

    @Test fun anUnresolvedOrChooserHomeNeverTreatsTheGrantedRoleAsTheDefault() {
        assertTrue(DesktopMode.setEnabled(context, true))
        val roles = context.getSystemService(RoleManager::class.java)
        shadowOf(roles).addAvailableRole(RoleManager.ROLE_HOME)
        shadowOf(roles).addHeldRole(RoleManager.ROLE_HOME)
        shadowOf(manager).setResolveInfosForIntent(homeIntent(), emptyList())
        // Empty overrides fall back to manifest filters in Robolectric. Keep the alias enabled
        // while simulating a firmware/package-visibility query that exposes no HOME route.
        shadowOf(manager).clearIntentFilterForActivity(DesktopMode.component(context))
        assertNull(manager.resolveActivity(homeIntent(), PackageManager.MATCH_DEFAULT_ONLY))
        assertFalse(DesktopMode.isDefaultHome(context))
        assertEquals(DesktopMode.HomeStatus.UNKNOWN, DesktopMode.homeStatus(context))
        homeResolvesTo("android", "com.android.internal.app.ResolverActivity")
        assertFalse(DesktopMode.isDefaultHome(context))
    }

    @Test fun disablingModeWinsEvenWhileTheRoleAndResolvedComponentAreStillStale() {
        assertTrue(DesktopMode.setEnabled(context, true))
        val roles = context.getSystemService(RoleManager::class.java)
        shadowOf(roles).addAvailableRole(RoleManager.ROLE_HOME)
        shadowOf(roles).addHeldRole(RoleManager.ROLE_HOME)
        homeResolvesTo(context.packageName, DesktopMode.component(context).className)
        assertTrue(DesktopMode.isDefaultHome(context))
        assertTrue(DesktopMode.setEnabled(context, false))
        assertTrue(roles.isRoleHeld(RoleManager.ROLE_HOME))
        assertFalse(DesktopMode.isDefaultHome(context))
        assertEquals(DesktopMode.HomeStatus.DISABLED, DesktopMode.homeStatus(context))
    }

    @Test fun blockedRolePromptFallsBackToHomeSettings() {
        assertTrue(DesktopMode.setEnabled(context, true))
        shadowOf(context.getSystemService(RoleManager::class.java)).addAvailableRole(RoleManager.ROLE_HOME)
        val attempts = mutableListOf<String?>()
        assertTrue(DesktopMode.openHomeSelection(context) {
            attempts.add(it.action)
            if (it.action == "android.app.role.action.REQUEST_ROLE") throw SecurityException("OEM policy")
        })
        assertEquals(listOf("android.app.role.action.REQUEST_ROLE", Settings.ACTION_HOME_SETTINGS), attempts)
    }

    @Test fun missingOrRestrictedOemPagesFallBackToDefaultAppsThenGeneralSettings() {
        assertTrue(DesktopMode.setEnabled(context, true))
        val attempts = mutableListOf<String?>()
        assertTrue(DesktopMode.openHomeSelection(context) {
            attempts.add(it.action)
            when (it.action) {
                Settings.ACTION_HOME_SETTINGS -> throw ActivityNotFoundException("OEM omitted this page")
                Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS -> throw SecurityException("OEM restricted this page")
            }
        })
        assertEquals(listOf(Settings.ACTION_HOME_SETTINGS, Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, Settings.ACTION_SETTINGS), attempts)
        assertTrue(DesktopMode.isEnabled(context))
    }

    @Test fun aDeviceWithNoSettingsActivityReturnsFailureWithoutDisablingTheOptIn() {
        assertTrue(DesktopMode.setEnabled(context, true))
        val attempts = mutableListOf<String?>()
        assertFalse(DesktopMode.openHomeSelection(context) {
            attempts.add(it.action); throw ActivityNotFoundException()
        })
        assertEquals(3, attempts.size); assertTrue(DesktopMode.isEnabled(context))
        assertFalse(DesktopMode.openSystemSettings { throw ActivityNotFoundException() })
        assertFalse(DesktopMode.openSystemSettings { throw SecurityException() })
        val system = mutableListOf<Intent>()
        assertTrue(DesktopMode.openSystemSettings { system.add(it) })
        assertEquals(Settings.ACTION_SETTINGS, system.single().action)
    }

    @Test @Config(sdk = [26]) fun preRoleManagerAndroidUsesHomeSettingsDirectly() {
        assertTrue(DesktopMode.setEnabled(context, true))
        val launched = mutableListOf<Intent>()
        assertTrue(DesktopMode.openHomeSelection(context) { launched.add(it) })
        assertEquals(listOf(Settings.ACTION_HOME_SETTINGS), launched.map { it.action })
        assertTrue(DesktopMode.isEnabled(context))
    }
}
