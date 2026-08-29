package io.github.edwardlucas.gwamanager.data

enum class UserAgentMode {
    MOBILE,
    DESKTOP;

    companion object {
        fun fromStoredValue(value: String?): UserAgentMode {
            return values().firstOrNull { it.name == value } ?: MOBILE
        }
    }
}
