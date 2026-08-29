package io.github.edwardlucas.gwamanager.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

class WebAppRepository(context: Context) {
    private val preferences: SharedPreferences = context.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )
    private val lock = Any()

    fun getAll(): List<WebAppConfig> {
        return synchronized(lock) {
            readConfigs()
        }
    }

    fun findById(id: String): WebAppConfig? {
        return getAll().firstOrNull { it.id == id }
    }

    fun save(config: WebAppConfig): Boolean {
        return synchronized(lock) {
            val configs = readConfigs().toMutableList()
            val existingIndex = configs.indexOfFirst { it.id == config.id }
            if (existingIndex >= 0) {
                configs[existingIndex] = config
            } else {
                configs.add(config)
            }
            writeConfigs(configs)
        }
    }

    fun create(name: String, url: String, userAgentMode: UserAgentMode): WebAppConfig {
        val config = WebAppConfig(
            id = UUID.randomUUID().toString(),
            name = name,
            url = url,
            userAgentMode = userAgentMode
        )
        check(save(config)) { "Unable to persist WebApp configuration." }
        return config
    }

    fun delete(id: String): Boolean {
        return synchronized(lock) {
            val configs = readConfigs().toMutableList()
            val removed = configs.removeAll { it.id == id }
            if (removed) {
                if (!writeConfigs(configs)) {
                    Log.e(TAG, "Unable to persist WebApp deletion.")
                    return@synchronized false
                }
            }
            removed
        }
    }

    private fun readConfigs(): List<WebAppConfig> {
        val encoded = preferences.getString(KEY_WEB_APPS, null) ?: return emptyList()
        return try {
            val array = JSONArray(encoded)
            buildList(array.length()) {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString(JSON_ID).trim()
                    val name = item.optString(JSON_NAME).trim()
                    val url = item.optString(JSON_URL).trim()
                    if (id.isEmpty() || name.isEmpty() || url.isEmpty()) {
                        Log.w(TAG, "Ignoring incomplete WebApp configuration at index $index.")
                        continue
                    }
                    add(
                        WebAppConfig(
                            id = id,
                            name = name,
                            url = url,
                            userAgentMode = UserAgentMode.fromStoredValue(
                                item.optString(JSON_USER_AGENT).takeIf { it.isNotEmpty() }
                            )
                        )
                    )
                }
            }
        } catch (exception: JSONException) {
            Log.e(TAG, "Unable to read stored WebApp configurations.", exception)
            emptyList()
        }
    }

    private fun writeConfigs(configs: List<WebAppConfig>): Boolean {
        val array = JSONArray()
        configs.forEach { config ->
            array.put(
                JSONObject()
                    .put(JSON_ID, config.id)
                    .put(JSON_NAME, config.name)
                    .put(JSON_URL, config.url)
                    .put(JSON_USER_AGENT, config.userAgentMode.name)
            )
        }
        return preferences.edit().putString(KEY_WEB_APPS, array.toString()).commit()
    }

    private companion object {
        const val TAG = "WebAppRepository"
        const val PREFERENCES_NAME = "web_app_manager"
        const val KEY_WEB_APPS = "web_apps"
        const val JSON_ID = "id"
        const val JSON_NAME = "name"
        const val JSON_URL = "url"
        const val JSON_USER_AGENT = "userAgentMode"
    }
}
