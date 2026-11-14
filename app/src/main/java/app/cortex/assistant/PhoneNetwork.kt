package app.cortex.assistant

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import kotlinx.coroutines.delay

internal class PhoneNetwork(private val context: Context) {
    private data class State(
        val radio: Int?,
        val wifiConnected: Boolean,
        val wifiValidated: Boolean,
        val captive: Boolean,
        val active: String,
        val airplane: Boolean
    ) {
        val wifiOn get() = radio == WifiManager.WIFI_STATE_ENABLED
    }

    private fun wifiRadio(): Int? = runCatching {
        context.applicationContext.getSystemService(WifiManager::class.java)?.wifiState
    }.getOrNull()

    private data class Toggle(val frame: VisualFrame, val node: VisualNode, val checked: Boolean)

    suspend fun run(
        request: NetworkRequest,
        screen: CortexScreenService?,
        beforeScreen: () -> Unit,
        report: (String) -> Unit
    ): CortexActionEngine.Result {
        report("Проверяю Wi-Fi, активную сеть и режим полёта…")
        val initial = state()
        val changes = mutableListOf<String>()
        val limits = mutableListOf<String>()
        var hotspot: Boolean? = null
        val repair = request.mode == NetworkRequest.Mode.REPAIR
        val check = request.mode == NetworkRequest.Mode.CHECK
        val open = request.mode == NetworkRequest.Mode.OPEN
        val screenUsable = screen?.takeUnless { it.locked() }
        val navigation = screenUsable?.let { VisualNavigation(context, it) }
        val settings = settingsPackage()
        val user = context.getSystemService(UserManager::class.java)
        val restricted = Build.VERSION.SDK_INT >= 33 && user?.hasUserRestriction(UserManager.DISALLOW_CHANGE_WIFI_STATE) == true
        val wifiGoal = when {
            !request.wifi -> null
            request.mode == NetworkRequest.Mode.ENABLE -> true
            request.mode == NetworkRequest.Mode.DISABLE -> false
            repair && initial.radio in setOf(WifiManager.WIFI_STATE_DISABLED, WifiManager.WIFI_STATE_DISABLING) -> true
            else -> null
        }
        val hotspotGoal = when {
            !request.hotspot -> null
            request.mode == NetworkRequest.Mode.ENABLE -> true
            request.mode == NetworkRequest.Mode.DISABLE || repair && request.wifi && !initial.wifiOn -> false
            else -> null
        }

        if (context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked && !check) {
            return CortexActionEngine.Result("Разблокируйте телефон, чтобы открыть сетевые настройки.", false)
        }

        if (request.hotspot && !open) {
            if (settings != null && navigation != null) {
                beforeScreen()
                report("Открываю настройки точки доступа…")
                val control = hotspotPage(settings, navigation, screenUsable)?.let { switch(it, hotspotLabels) }
                hotspot = control?.checked
                if (control != null && hotspotGoal != null && control.checked != hotspotGoal) {
                    report(if (hotspotGoal) "Включаю точку доступа…" else "Выключаю точку доступа…")
                    val applied = screenUsable.click(control.frame, control.node) || screenUsable.tap(control.frame, control.node)
                    val actual = if (applied) navigation.await(3_500L) { frame ->
                        safeSettings(frame, settings) && switch(frame, hotspotLabels)?.checked == hotspotGoal
                    } else null
                    if (actual != null) {
                        hotspot = hotspotGoal
                        changes += if (hotspotGoal) "Включил точку доступа." else "Выключил точку доступа."
                    } else {
                        hotspot = null
                        limits += "Android не подтвердил изменение точки доступа."
                    }
                }
            }
            if (hotspot == null && limits.isEmpty()) {
                if (navigation == null && settings != null && hotspotGoal != null) {
                    beforeScreen()
                    val opened = launchSettings(TETHER_SETTINGS, settings) || launchSettings(Settings.ACTION_WIRELESS_SETTINGS, settings)
                    if (opened) limits += "Открыл сетевые настройки. Для нажатия нужен доступ к управлению экраном."
                }
                limits += "Состояние точки доступа пока не удалось прочитать."
            }
        }

        if (wifiGoal != null && (if (wifiGoal) wifiRadio() != WifiManager.WIFI_STATE_ENABLED else wifiRadio() != WifiManager.WIFI_STATE_DISABLED)) {
            when {
                restricted -> limits += "Политика Android запрещает менять Wi-Fi на этом устройстве."
                settings == null || navigation == null -> {
                    if (settings != null) {
                        beforeScreen()
                        if (launchSettings(Settings.ACTION_WIFI_SETTINGS, settings)) limits += "Открыл настройки Wi-Fi."
                    }
                    limits += "Для нажатия переключателя нужен доступ «CORTEX — управление экраном»."
                }
                else -> {
                    beforeScreen()
                    report("Открываю настройки Wi-Fi…")
                    val launched = navigation.launch(Intent(Settings.ACTION_WIFI_SETTINGS).setPackage(settings))
                    val control = if (launched) navigation.await(3_000L) {
                        safeSettings(it, settings) && switch(it, wifiLabels)?.checked == (wifiRadio() == WifiManager.WIFI_STATE_ENABLED)
                    }?.let { switch(it, wifiLabels) } else null
                    if (control == null) limits += if (launched) "Открыл Wi-Fi. Переключатель пока недоступен для управления."
                        else "Android не открыл настройки Wi-Fi."
                    else {
                        val accepted = control.checked == wifiGoal || run {
                            report(if (wifiGoal) "Включаю Wi-Fi…" else "Выключаю Wi-Fi…")
                            screenUsable.click(control.frame, control.node) || screenUsable.tap(control.frame, control.node)
                        }
                        when {
                            !accepted -> limits += "Android отклонил нажатие переключателя Wi-Fi."
                            awaitRadio(wifiGoal) -> changes += if (wifiGoal) "Включил Wi-Fi." else "Выключил Wi-Fi."
                            else -> limits += "Android не подтвердил изменение Wi-Fi."
                        }
                    }
                }
            }
        } else if (open || repair && request.wifi && !initial.wifiValidated && changes.isEmpty()) {
            if (settings != null) {
                beforeScreen()
                if (request.hotspot && !request.wifi) {
                    if (navigation != null && hotspotPage(settings, navigation, screenUsable) != null ||
                        navigation == null && launchSettings(TETHER_SETTINGS, settings)) {
                        changes += "Открыл настройки точки доступа."
                    } else if (launchSettings(Settings.ACTION_WIRELESS_SETTINGS, settings)) {
                        changes += "Открыл раздел сетевых настроек."
                    } else limits += "Android не открыл настройки точки доступа."
                } else if (launchSettings(Settings.ACTION_WIFI_SETTINGS, settings)) changes += "Открыл настройки Wi-Fi."
                else limits += "Android не открыл настройки Wi-Fi."
            } else limits += "Системные сетевые настройки недоступны."
        }

        val actual = if (wifiGoal == true && changes.any { it == "Включил Wi-Fi." }) awaitConnection() else state()
        val details = buildList {
            addAll(changes)
            if (request.wifi || !request.hotspot) add(when (actual.radio) {
                WifiManager.WIFI_STATE_ENABLED -> when {
                    actual.wifiValidated -> "Wi-Fi подключён, Android подтвердил доступ к интернету."
                    actual.captive -> "Wi-Fi подключён. Сеть запрашивает вход."
                    actual.wifiConnected -> "Wi-Fi подключён, Android пока не подтвердил доступ к интернету."
                    else -> "Wi-Fi включён и пока не подключён к сети."
                }
                WifiManager.WIFI_STATE_DISABLED -> "Wi-Fi выключен."
                WifiManager.WIFI_STATE_ENABLING -> "Wi-Fi включается."
                WifiManager.WIFI_STATE_DISABLING -> "Wi-Fi выключается."
                else -> "Android не сообщил состояние Wi-Fi."
            })
            if (request.hotspot && hotspot != null && changes.none { it.contains("точку доступа") }) {
                add(if (hotspot == true) "Точка доступа включена." else "Точка доступа выключена.")
            }
            if ((check || repair) && actual.active.isNotBlank()) add("Активное подключение — ${actual.active}.")
            if (actual.airplane) add("Режим полёта включён.")
            if ((check || repair) && actual.radio != initial.radio && changes.none { it.contains("Wi-Fi") }) {
                add("Состояние Wi-Fi изменилось во время проверки.")
            }
            addAll(limits)
            if (repair && wifiGoal != false && request.wifi && actual.wifiValidated && changes.isEmpty()) {
                add("Сейчас сбой не проявляется. Причину автоматического переключения пока не удалось подтвердить.")
            }
        }
        val ok = when (request.mode) {
            NetworkRequest.Mode.CHECK -> (!request.wifi || actual.radio != null) && (!request.hotspot || hotspot != null)
            NetworkRequest.Mode.REPAIR -> limits.isEmpty() && (!request.wifi || actual.wifiValidated)
            NetworkRequest.Mode.OPEN -> changes.isNotEmpty()
            else -> limits.isEmpty() && (wifiGoal == null ||
                if (wifiGoal) actual.wifiOn else actual.radio == WifiManager.WIFI_STATE_DISABLED) &&
                (hotspotGoal == null || hotspot == hotspotGoal)
        }
        return CortexActionEngine.Result(details.joinToString(" "), ok)
    }

    private fun state(): State {
        val radio = wifiRadio()
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val default = runCatching { cm?.getNetworkCapabilities(cm.activeNetwork) }.getOrNull()
        @Suppress("DEPRECATION")
        val wifiNetworks = runCatching {
            cm?.allNetworks.orEmpty().mapNotNull { cm?.getNetworkCapabilities(it) }.filter {
                it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            }
        }.getOrDefault(emptyList())
        val active = when {
            default == null -> ""
            default.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            default.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            default.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "мобильные данные"
            default.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> ""
        }
        val airplane = Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        return State(radio, wifiNetworks.isNotEmpty() || active == "Wi-Fi",
            wifiNetworks.any { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) } ||
                active == "Wi-Fi" && default?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            wifiNetworks.any { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) }, active, airplane)
    }

    private suspend fun awaitRadio(enabled: Boolean): Boolean {
        val target = if (enabled) WifiManager.WIFI_STATE_ENABLED else WifiManager.WIFI_STATE_DISABLED
        val deadline = SystemClock.uptimeMillis() + 3_200L
        do {
            if (wifiRadio() == target) return true
            delay(100)
        } while (SystemClock.uptimeMillis() < deadline)
        return false
    }

    private suspend fun awaitConnection(): State {
        val deadline = SystemClock.uptimeMillis() + 2_400L
        var current = state()
        while (!current.wifiValidated && !current.captive && current.wifiOn && SystemClock.uptimeMillis() < deadline) {
            delay(160)
            current = state()
        }
        return current
    }

    private fun settingsPackage(): String? =
        context.packageManager.resolveActivity(Intent(Settings.ACTION_SETTINGS), PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.takeIf { it.applicationInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0 }
            ?.packageName

    private fun launchSettings(action: String, settings: String) = runCatching {
        context.startActivity(Intent(action).setPackage(settings).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)

    private suspend fun hotspotPage(settings: String, navigation: VisualNavigation, screen: CortexScreenService): VisualFrame? {
        fun settingsFrame(frame: VisualFrame) = safeSettings(frame, settings)
        if (navigation.launch(Intent(TETHER_SETTINGS).setPackage(settings))) {
            val direct = navigation.await(1_450L) { settingsFrame(it) && switch(it, hotspotLabels) != null }
            if (direct != null) return direct
        }
        if (!navigation.launch(Intent(Settings.ACTION_WIRELESS_SETTINGS).setPackage(settings))) return null
        var frame = navigation.await(2_200L, ::settingsFrame) ?: return null
        repeat(3) {
            if (switch(frame, hotspotLabels) != null) return frame
            val row = hotspotRoutes.firstNotNullOfOrNull { label ->
                frame.target(label) { node -> node.enabled && !node.editable && node.checked == null }
            } ?: return null
            if (!(screen.click(frame, row) || screen.tap(frame, row))) return null
            frame = navigation.after(frame)?.takeIf(::settingsFrame) ?: return null
        }
        return frame.takeIf { switch(it, hotspotLabels) != null }
    }

    private fun safeSettings(frame: VisualFrame, settings: String) =
        frame.pkg == settings && !frame.secure && !frame.hasPrivateFields

    private fun switch(frame: VisualFrame, labels: Set<String>): Toggle? {
        val controls = frame.tree.filter { it.enabled && it.checked != null && !it.editable }
        val named = controls.filter { control ->
            val identifiers = listOf(control.label, control.description, control.resource.substringAfterLast('/'))
            identifiers.any { VisualLabels.normalize(it) in labels } || frame.tree.any { node ->
                node.enabled && node.path.size >= 2 && control.path.size >= 2 &&
                    (node.path.startsWith(control.path) ||
                        node.path.dropLast(1) == control.path.dropLast(1)) &&
                    VisualLabels.normalize(node.label) in labels
            }
        }.distinctBy { it.bounds }
        val candidate = named.singleOrNull() ?: controls.singleOrNull()?.takeIf { control ->
            named.isEmpty() && switchBar.containsMatchIn(control.resource.substringAfterLast('/')) &&
                frame.tree.any { VisualLabels.normalize(it.label) in labels && it.bounds.top <= control.bounds.bottom }
        } ?: return null
        return Toggle(frame, candidate, candidate.checked ?: return null)
    }

    private fun List<Int>.startsWith(other: List<Int>) = size >= other.size && take(other.size) == other

    private companion object {
        const val TETHER_SETTINGS = "android.settings.TETHER_SETTINGS"
        val wifiLabels = setOf("wi fi", "wifi", "wlan", "use wi fi", "использовать wi fi", "включить wi fi", "wifi switch")
        val hotspotLabels = setOf("wi fi hotspot", "wifi hotspot", "мобильная точка доступа", "точка доступа wi fi",
            "переносная точка доступа wi fi", "точка доступа", "hotspot", "use wi fi hotspot", "use hotspot",
            "использовать точку доступа", "включить точку доступа")
        val hotspotRoutes = hotspotLabels + setOf("hotspot tethering", "hotspot and tethering", "точка доступа и модем",
            "мобильная точка доступа и модем", "режим модема", "модем и точка доступа", "connections", "подключения",
            "network internet", "network and internet", "сеть и интернет")
        val switchBar = Regex("""(?i)(?:switch_bar|main_switch|mainSwitch|switch_widget|switch_text|switch)$""")
    }
}
