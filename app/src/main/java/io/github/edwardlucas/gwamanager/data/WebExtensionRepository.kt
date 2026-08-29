package io.github.edwardlucas.gwamanager.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipFile
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

data class ImportedWebExtension(
    val file: File,
    val name: String,
    val version: String,
    val manifestId: String?,
    val requiredPermissions: List<String>,
    val requiredOrigins: List<String>
)

data class StoredWebExtension(
    val id: String,
    val name: String,
    val version: String,
    val fileName: String = "",
    val sourceUrl: String? = null
)

class InvalidWebExtensionException(message: String) : IOException(message)

class WebExtensionRepository(context: Context) {
    private val preferences = context.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )
    private val resolver = context.contentResolver
    private val extensionDirectory = File(context.filesDir, EXTENSION_DIRECTORY_NAME)
    private val lock = Any()

    fun getAll(): List<StoredWebExtension> {
        return synchronized(lock) {
            readExtensions()
        }
    }

    fun importPackage(uri: Uri): ImportedWebExtension {
        synchronized(lock) {
            if (!extensionDirectory.exists() && !extensionDirectory.mkdirs()) {
                throw IOException("Unable to create the WebExtension directory.")
            }
            val file = File(extensionDirectory, "${UUID.randomUUID()}.xpi")
            try {
                copyPackage(uri, file)
                return readPackageMetadata(file)
            } catch (exception: IOException) {
                file.delete()
                throw exception
            } catch (exception: SecurityException) {
                file.delete()
                throw exception
            }
        }
    }

    fun discard(imported: ImportedWebExtension) {
        synchronized(lock) {
            if (imported.file.parentFile == extensionDirectory) {
                imported.file.delete()
            }
        }
    }

    fun fileFor(extension: StoredWebExtension): File {
        return File(extensionDirectory, extension.fileName)
    }

    fun save(extension: StoredWebExtension): Boolean {
        return synchronized(lock) {
            val extensions = readExtensions().toMutableList()
            val existingIndex = extensions.indexOfFirst { it.id == extension.id }
            if (existingIndex >= 0) {
                extensions[existingIndex] = extension
            } else {
                extensions.add(extension)
            }
            writeExtensions(extensions)
        }
    }

    fun remove(id: String): Boolean {
        return synchronized(lock) {
            val extensions = readExtensions().toMutableList()
            val removed = extensions.firstOrNull { it.id == id } ?: return false
            extensions.remove(removed)
            if (!writeExtensions(extensions)) {
                return false
            }
            if (removed.fileName.isNotBlank()) {
                fileFor(removed).delete()
            }
            true
        }
    }

    private fun copyPackage(uri: Uri, destination: File) {
        val input = resolver.openInputStream(uri)
            ?: throw IOException("Unable to open the selected WebExtension.")
        input.use { source ->
            destination.outputStream().use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                var totalBytes = 0L
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) {
                        break
                    }
                    totalBytes += count
                    if (totalBytes > MAX_PACKAGE_BYTES) {
                        throw InvalidWebExtensionException("The WebExtension package is too large.")
                    }
                    output.write(buffer, 0, count)
                }
            }
        }
    }

    private fun readPackageMetadata(file: File): ImportedWebExtension {
        ZipFile(file).use { zip ->
            val entry = zip.getEntry(MANIFEST_FILE_NAME)
                ?: throw InvalidWebExtensionException("manifest.json was not found.")
            if (entry.isDirectory || entry.size > MAX_MANIFEST_BYTES) {
                throw InvalidWebExtensionException("The WebExtension manifest is invalid.")
            }
            val manifestBytes = zip.getInputStream(entry).use { input ->
                readAtMost(input, MAX_MANIFEST_BYTES)
            } ?: throw InvalidWebExtensionException("The WebExtension manifest is too large.")
            val manifest = try {
                JSONObject(String(manifestBytes, Charsets.UTF_8))
            } catch (exception: JSONException) {
                throw InvalidWebExtensionException("The WebExtension manifest is not valid JSON.")
            }
            val manifestVersion = manifest.optInt(JSON_MANIFEST_VERSION, -1)
            if (manifestVersion != 2 && manifestVersion != 3) {
                throw InvalidWebExtensionException("Only WebExtension manifest version 2 or 3 is supported.")
            }
            val name = manifest.optString(JSON_NAME).trim()
            val version = manifest.optString(JSON_VERSION).trim()
            if (name.isEmpty() || version.isEmpty()) {
                throw InvalidWebExtensionException("The WebExtension name or version is missing.")
            }
            return ImportedWebExtension(
                file = file,
                name = name.take(MAX_TEXT_LENGTH),
                version = version.take(MAX_TEXT_LENGTH),
                manifestId = findGeckoId(manifest),
                requiredPermissions = readStringArray(manifest, JSON_PERMISSIONS),
                requiredOrigins = readStringArray(manifest, JSON_HOST_PERMISSIONS)
            )
        }
    }

    private fun findGeckoId(manifest: JSONObject): String? {
        val browserGeckoId = manifest
            .optJSONObject(JSON_BROWSER_SPECIFIC_SETTINGS)
            ?.optJSONObject(JSON_GECKO)
            ?.optString(JSON_ID)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (browserGeckoId != null) {
            return browserGeckoId
        }
        return manifest
            .optJSONObject(JSON_APPLICATIONS)
            ?.optJSONObject(JSON_GECKO)
            ?.optString(JSON_ID)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun readStringArray(manifest: JSONObject, key: String): List<String> {
        val values = manifest.optJSONArray(key) ?: return emptyList()
        return buildList(values.length()) {
            for (index in 0 until values.length()) {
                val value = values.optString(index).trim()
                if (value.isNotEmpty()) {
                    add(value.take(MAX_TEXT_LENGTH))
                }
            }
        }
    }

    private fun readExtensions(): List<StoredWebExtension> {
        val encoded = preferences.getString(KEY_EXTENSIONS, null) ?: return emptyList()
        return try {
            val array = JSONArray(encoded)
            buildList(array.length()) {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString(JSON_ID).trim()
                    val name = item.optString(JSON_NAME).trim()
                    val version = item.optString(JSON_VERSION).trim()
                    val fileName = item.optString(JSON_FILE_NAME).trim()
                    val sourceUrl = item.optString(JSON_SOURCE_URL)
                        .trim()
                        .takeIf { it.isNotEmpty() }
                    if (id.isEmpty() ||
                        name.isEmpty() ||
                        version.isEmpty() ||
                        (fileName.isEmpty() && sourceUrl == null)
                    ) {
                        Log.w(TAG, "Ignoring incomplete WebExtension at index $index.")
                        continue
                    }
                    add(StoredWebExtension(id, name, version, fileName, sourceUrl))
                }
            }
        } catch (exception: JSONException) {
            Log.e(TAG, "Unable to read stored WebExtensions.", exception)
            emptyList()
        }
    }

    private fun writeExtensions(extensions: List<StoredWebExtension>): Boolean {
        val array = JSONArray()
        extensions.forEach { extension ->
            val item = JSONObject()
                    .put(JSON_ID, extension.id)
                    .put(JSON_NAME, extension.name)
                    .put(JSON_VERSION, extension.version)
                    .put(JSON_FILE_NAME, extension.fileName)
            extension.sourceUrl?.let { sourceUrl ->
                item.put(JSON_SOURCE_URL, sourceUrl)
            }
            array.put(item)
        }
        return preferences.edit().putString(KEY_EXTENSIONS, array.toString()).commit()
    }

    private fun readAtMost(input: java.io.InputStream, maximumBytes: Long): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_SIZE)
        var totalBytes = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) {
                break
            }
            totalBytes += count
            if (totalBytes > maximumBytes) {
                return null
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private companion object {
        const val TAG = "WebExtensionRepository"
        const val PREFERENCES_NAME = "web_app_manager"
        const val KEY_EXTENSIONS = "web_extensions"
        const val EXTENSION_DIRECTORY_NAME = "web-extensions"
        const val MANIFEST_FILE_NAME = "manifest.json"
        const val JSON_ID = "id"
        const val JSON_NAME = "name"
        const val JSON_VERSION = "version"
        const val JSON_FILE_NAME = "fileName"
        const val JSON_SOURCE_URL = "sourceUrl"
        const val JSON_MANIFEST_VERSION = "manifest_version"
        const val JSON_PERMISSIONS = "permissions"
        const val JSON_HOST_PERMISSIONS = "host_permissions"
        const val JSON_BROWSER_SPECIFIC_SETTINGS = "browser_specific_settings"
        const val JSON_APPLICATIONS = "applications"
        const val JSON_GECKO = "gecko"
        const val MAX_PACKAGE_BYTES = 50 * 1024 * 1024L
        const val MAX_MANIFEST_BYTES = 1024 * 1024L
        const val MAX_TEXT_LENGTH = 200
        const val BUFFER_SIZE = 8192
    }
}
