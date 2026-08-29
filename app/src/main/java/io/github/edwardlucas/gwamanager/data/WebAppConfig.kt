package io.github.edwardlucas.gwamanager.data

data class WebAppConfig(
    val id: String,
    val name: String,
    val url: String,
    val userAgentMode: UserAgentMode = UserAgentMode.MOBILE
)
