package cn.limpu.hita.ui.campus

/** Activity overlap is navigation; zero-to-one is an app launch/return, except configuration recreation. */
internal class CampusCardForegroundGate {
    private var started = 0
    private var configurationRestart = false

    fun started(): Boolean {
        val entered = started++ == 0
        val refresh = entered && !configurationRestart
        configurationRestart = false
        return refresh
    }

    fun stopped(changingConfiguration: Boolean) {
        started = (started - 1).coerceAtLeast(0)
        if (started == 0 && changingConfiguration) configurationRestart = true
    }
}
