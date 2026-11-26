package com.nuvio.tv.launcher

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.nuvio.tv.domain.model.AppIconOption
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class AppIconSettingsState(
    val selected: AppIconOption = AppIconOption.ORIGINAL,
    val pending: AppIconOption? = null,
    val changeFailed: Boolean = false,
    /** Live TV fork: show "+ IPTV" on the icon and banner (off: the original Nuvio icons). */
    val iptvBranding: Boolean = true
)

@Singleton
class AppIconManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val launcherClasses = mapOf(
        AppIconOption.ORIGINAL to "com.nuvio.tv.launcher.AppIconDefault",
        AppIconOption.ARCTIC_BLUE to "com.nuvio.tv.launcher.AppIconArcticBlue",
        AppIconOption.EMERALD to "com.nuvio.tv.launcher.AppIconEmerald",
        AppIconOption.ROSE_GOLD to "com.nuvio.tv.launcher.AppIconRoseGold",
        AppIconOption.COPPER to "com.nuvio.tv.launcher.AppIconCopper",
        AppIconOption.GRAPHITE to "com.nuvio.tv.launcher.AppIconGraphite"
    )

    // Live TV fork: the same colors with "+ IPTV" on the icon and banner.
    private val iptvClasses = mapOf(
        AppIconOption.ORIGINAL to "com.nuvio.tv.livetv.branding.IptvIconDefault",
        AppIconOption.ARCTIC_BLUE to "com.nuvio.tv.livetv.branding.IptvIconArcticBlue",
        AppIconOption.EMERALD to "com.nuvio.tv.livetv.branding.IptvIconEmerald",
        AppIconOption.ROSE_GOLD to "com.nuvio.tv.livetv.branding.IptvIconRoseGold",
        AppIconOption.COPPER to "com.nuvio.tv.livetv.branding.IptvIconCopper",
        AppIconOption.GRAPHITE to "com.nuvio.tv.livetv.branding.IptvIconGraphite"
    )
    private val brandingPrefs = context.getSharedPreferences("livetv_app_icon", Context.MODE_PRIVATE)
    private fun iptvBranding() = brandingPrefs.getBoolean("iptv_branding", true)
    private fun allClasses() = launcherClasses.values + iptvClasses.values
    private fun classFor(option: AppIconOption, iptv: Boolean) =
        (if (iptv) iptvClasses else launcherClasses).getValue(option)

    private val _state = MutableStateFlow(AppIconSettingsState())
    val state: StateFlow<AppIconSettingsState> = _state.asStateFlow()

    init {
        normalizeLauncherEntries()
        _state.value = AppIconSettingsState(selected = currentOption(), iptvBranding = iptvBranding())
    }

    /** Live TV fork: "+ IPTV" on the icon on or off, keeping the chosen color. */
    fun setIptvBranding(enabled: Boolean): Boolean {
        val current = _state.value
        if (current.pending != null || current.iptvBranding == enabled) return false
        brandingPrefs.edit().putBoolean("iptv_branding", enabled).apply()
        val changed = activate(current.selected, enabled)
        _state.value = if (changed) current.copy(iptvBranding = enabled, changeFailed = false)
        else current.copy(changeFailed = true)
        return changed
    }

    /**
     * Exactly one launcher entry enabled: the chosen color, with or without "+ IPTV". Also moves
     * people who picked a color before this existed onto the matching "+ IPTV" version.
     */
    private fun normalizeLauncherEntries() {
        val pm = context.packageManager
        val enabled = allClasses().filter { isEnabled(pm, it) }
        val option = currentOption()
        val wanted = classFor(option, iptvBranding())
        if (enabled == listOf(wanted)) return
        activate(option, iptvBranding())
    }

    private fun isEnabled(pm: PackageManager, className: String): Boolean {
        val setting = runCatching { pm.getComponentEnabledSetting(component(className)) }
            .getOrDefault(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
        return setting == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
            // The one entry the manifest enables by default.
            (setting == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && className == iptvClasses.getValue(AppIconOption.ORIGINAL))
    }

    fun select(option: AppIconOption): Boolean {
        val current = _state.value
        if (current.pending != null || current.selected == option) return false

        _state.value = current.copy(pending = option, changeFailed = false)
        val changed = activate(option, current.iptvBranding)
        _state.value = if (changed) {
            brandingPrefs.edit().putString("color", option.name).apply()
            AppIconSettingsState(selected = option, iptvBranding = current.iptvBranding)
        } else {
            current.copy(changeFailed = true)
        }
        return changed
    }

    fun clearFailure() {
        val current = _state.value
        if (!current.changeFailed) return
        _state.value = current.copy(changeFailed = false)
    }

    private fun currentOption(): AppIconOption {
        val packageManager = context.packageManager
        // The color in use, whichever of the two styles it's in.
        val explicitlyEnabled = (iptvClasses.entries + launcherClasses.entries).firstOrNull { (_, className) ->
            packageManager.getComponentEnabledSetting(component(className)) ==
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }
        if (explicitlyEnabled != null) return explicitlyEnabled.key
        return AppIconOption.ORIGINAL
    }

    private fun restoreDefaultIfNeeded() {
        val packageManager = context.packageManager
        val hasEnabledComponent = launcherClasses.values.any { className ->
            packageManager.getComponentEnabledSetting(component(className)) ==
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }
        if (hasEnabledComponent) return

        val defaultClass = launcherClasses.getValue(AppIconOption.ORIGINAL)
        if (
            packageManager.getComponentEnabledSetting(component(defaultClass)) !=
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        ) {
            return
        }
        packageManager.setComponentEnabledSetting(
            component(defaultClass),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        )
    }

    private fun activate(option: AppIconOption, iptv: Boolean = iptvBranding()): Boolean {
        val selectedClass = classFor(option, iptv)
        val packageManager = context.packageManager
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.setComponentEnabledSettings(
                    allClasses().map { className ->
                        PackageManager.ComponentEnabledSetting(
                            component(className),
                            if (className == selectedClass) {
                                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                            } else {
                                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                            },
                            PackageManager.DONT_KILL_APP
                        )
                    }
                )
            } else {
                packageManager.setComponentEnabledSetting(
                    component(selectedClass),
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
                allClasses()
                    .filterNot { it == selectedClass }
                    .forEach { className ->
                        packageManager.setComponentEnabledSetting(
                            component(className),
                            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                            PackageManager.DONT_KILL_APP
                        )
                    }
            }
        }.isSuccess
    }

    private fun component(className: String): ComponentName =
        ComponentName(context.packageName, className)
}
