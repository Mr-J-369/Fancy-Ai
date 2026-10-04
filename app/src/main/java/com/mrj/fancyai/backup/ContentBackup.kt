package com.mrj.fancyai.backup

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.mrj.fancyai.util.AppLog
import com.mrj.fancyai.util.exportDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal object ContentBackup {
    val lock = Mutex()

    suspend fun write(context: Context, destination: Uri) = withContext(Dispatchers.IO) {
        exportDocument(context, destination) { output ->
            ZipOutputStream(output.buffered()).use { zip ->
                for (name in BackupContent.preferenceNames +
                    File(context.applicationInfo.dataDir, "shared_prefs").listFiles().orEmpty()
                        .filter { it.extension == "xml" && it.name.startsWith("character_creator_draft.") }
                        .map { it.nameWithoutExtension }) {
                    val json = BackupPreferences(context, name).encode()
                    zip.putNextEntry(ZipEntry("preferences/$name.json"))
                    zip.write(json.toString().toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
                BackupFiles(context).write(zip)
            }
        }
        AppLog.write(android.util.Log.INFO, "Backup", "Backup saved")
    }

    suspend fun restore(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
        var restored = false
        val savedPreferences = mutableMapOf<String, JsonObject>()
        val files = BackupFiles(context)
        val input = context.contentResolver.openInputStream(uri) ?: error("Backup unavailable")
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name.removePrefix("preferences/").removeSuffix(".json")
                when {
                    entry.name.startsWith("content/") -> {
                        if (files.restore(entry, zip)) restored = true
                    }
                    entry.name.startsWith("preferences/") && entry.name.endsWith(".json") &&
                        BackupContent.safePath(name) && '/' !in name &&
                        (name in BackupContent.preferenceNames || name.startsWith("character_creator_draft.")) -> {
                        savedPreferences[name] = Json.parseToJsonElement(zip.bufferedReader(Charsets.UTF_8).readText()).jsonObject
                    }
                }
                zip.closeEntry()
            }
        }
        for ((name, json) in savedPreferences) {
            if (BackupPreferences(context, name).restore(json)) restored = true
        }
        AppLog.write(android.util.Log.INFO, "Backup", if (restored) "Content restored" else "No supported content found in backup")
        restored
    }

}

/** Owns the typed preference records shared by backup export and restore. */
private class BackupPreferences(context: Context, private val name: String) {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    fun encode(): JsonObject = buildJsonObject {
        prefs.all.forEach { (key, value) ->
            val type = BackupContent.type(name, key) ?: return@forEach
            val savedValue = when (value) {
                is String -> JsonPrimitive(value)
                is Boolean -> JsonPrimitive(value)
                is Number -> JsonPrimitive(value)
                is Set<*> -> JsonArray(value.map { JsonPrimitive(it as String) })
                else -> return@forEach
            }
            put(key, buildJsonObject {
                put("type", type)
                put("value", savedValue)
            })
        }
    }

    fun restore(json: JsonObject): Boolean {
        val editor = prefs.edit()
        var restored = false
        for ((key, record) in json) {
            val value = (record as? JsonObject)?.get("value")
            if (restorePreference(editor, key, value, json)) restored = true
        }
        if (restored) check(editor.commit()) { "Could not restore settings: $name" }
        return restored
    }

    private fun restorePreference(
        editor: SharedPreferences.Editor,
        key: String,
        value: JsonElement?,
        json: JsonObject,
    ): Boolean {
        val type = BackupContent.type(name, key)
        val primitive = (value as? JsonPrimitive)?.takeIf { it.isString == (type == "string") }
        val applied = when (type) {
            "string" -> primitive?.content?.let { text ->
                editor.putString(key, text).also {
                    if (name == "system_prompts" && (key.startsWith("title_") || key.startsWith("instruction_"))) {
                        val templateKey = "template_${key.substringAfter('_')}"
                        if (templateKey !in json) editor.remove(templateKey)
                    }
                }
            }
            "boolean" -> primitive?.booleanOrNull?.let { editor.putBoolean(key, it) }
            "float" -> primitive?.floatOrNull?.let { editor.putFloat(key, it) }
            "int" -> primitive?.doubleOrNull?.toInt()?.let { parsed ->
                val number = if (name == "system_prompts" && key == "prompt_count") {
                    maxOf(prefs.getInt(key, 0), parsed)
                } else parsed
                editor.putInt(key, number)
            }
            "string_set" -> (value as? JsonArray)?.let { array ->
                editor.putStringSet(key, array.map { it.jsonPrimitive.content }.toSet())
            }
            else -> null
        }
        return applied != null
    }
}

/** Owns backed-up files, cancellable copying, and staged replacement under the character lock. */
private class BackupFiles(private val context: Context) {
    private val base = context.filesDir.canonicalFile

    suspend fun write(zip: ZipOutputStream) {
        for (root in BackupContent.roots) {
            File(base, root).walkTopDown().onEnter { it.canonicalFile == it.absoluteFile }
                .onFail { _, error -> throw error }.forEach { file ->
                val path = file.relativeTo(base).invariantSeparatorsPath
                if (file.isFile && BackupContent.file(path) &&
                    file.canonicalFile == file.absoluteFile) {
                    zip.putNextEntry(ZipEntry("content/$path").apply { time = file.lastModified() })
                    file.inputStream().use { copy(it, zip) }
                    zip.closeEntry()
                }
            }
        }
    }

    suspend fun restore(entry: ZipEntry, zip: ZipInputStream): Boolean = withContext(Dispatchers.IO) {
        val path = entry.name.removePrefix("content/")
        if (!BackupContent.file(path)) return@withContext false
        val target = File(base, path)
        if (target.canonicalFile != target.absoluteFile) return@withContext false
        // Stage before taking the character lock; copying remains cancellable.
        val temporary = File.createTempFile(".restore-", ".tmp", context.cacheDir)
        try {
            temporary.outputStream().use { copy(zip, it) }
            synchronized(com.mrj.fancyai.ui.settings.ProAccess.charactersLock) {
                target.parentFile!!.mkdirs()
                check(temporary.renameTo(target)) { "Could not restore $path" }
            }
            if (entry.time > 0) target.setLastModified(entry.time)
        } finally { temporary.delete() }
        true
    }

    private suspend fun copy(input: InputStream, output: OutputStream) = withContext(Dispatchers.IO) {
        val buffer = ByteArray(64 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
        }
    }
}
