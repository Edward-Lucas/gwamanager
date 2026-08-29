package io.github.edwardlucas.gwamanager.browser

import io.github.edwardlucas.gwamanager.data.UserAgentMode

object UserAgentProvider {
    const val MOBILE_USER_AGENT =
        "Mozilla/5.0 (Android 13; Mobile; rv:128.0) Gecko/128.0 Firefox/128.0"
    const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (X11; Linux x86_64; rv:128.0) Gecko/20100101 Firefox/128.0"

    fun forMode(mode: UserAgentMode): String {
        return when (mode) {
            UserAgentMode.MOBILE -> MOBILE_USER_AGENT
            UserAgentMode.DESKTOP -> DESKTOP_USER_AGENT
        }
    }
}
