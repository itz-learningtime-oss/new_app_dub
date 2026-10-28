package com.voxstudio.app

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Voices shipped with the app. Like cloned voices, each is a short reference clip and its exact
 * transcript for the single shared speech model; none is a separate model. Clips live in
 * assets/voices and are copied to private storage once per install, because the native
 * engine reads references from a file path. Copies are refreshed whenever the app is reinstalled.
 */
object BuiltInVoices {
    const val PREFIX = "builtin-"
    data class Entry(val id: String, val name: String, val gender: String, val accent: String, val style: String,
                     val transcript: String, val file: String, val placeholder: Boolean) {
        val voiceId get() = PREFIX + id
        val label get() = listOf(gender.replaceFirstChar { it.uppercase() }, accentName(accent), style).filter { it.isNotBlank() }.joinToString(" · ")
    }
    fun accentName(code: String) = when (code) {
        "US" -> "American"; "UK" -> "British"; "AU" -> "Australian"; "IE" -> "Irish"
        "ZA" -> "South African"; "IN" -> "Indian"; else -> code
    }
    fun parse(json: String): List<Entry> {
        val voices = JSONObject(json).getJSONArray("voices")
        return (0 until voices.length()).map { i ->
            val v = voices.getJSONObject(i)
            Entry(v.getString("id"), v.getString("name"), v.getString("gender"), v.getString("accent"), v.getString("style"),
                v.getString("transcript"), v.getString("file"), v.optBoolean("placeholder", false))
        }
    }
    @Volatile private var cached: List<Entry>? = null
    /** Catalog entries whose clip is actually packaged; a checkout without clips simply has none. */
    fun catalog(context: Context): List<Entry> = cached ?: runCatching {
        val assets = context.assets
        val packaged = assets.list("voices")?.toSet() ?: emptySet()
        parse(assets.open("voices/catalog.json").bufferedReader().use { it.readText() }).filter { it.file in packaged }
    }.getOrDefault(emptyList()).also { cached = it }
    fun entry(context: Context, voiceId: String?) = catalog(context).firstOrNull { it.voiceId == voiceId }
    fun voice(context: Context, voiceId: String?): VoiceLibrary.Voice? {
        val entry = entry(context, voiceId) ?: return null
        return VoiceLibrary.Voice(entry.voiceId, entry.name, entry.transcript, 0L, reference(context, entry))
    }
    fun default(context: Context) = catalog(context).firstOrNull()?.let { voice(context, it.voiceId) }
    @Synchronized fun reference(context: Context, entry: Entry): File {
        val root = File(context.filesDir, "builtin-voices")
        // Keyed by install time, not versionCode: any reinstall may ship different clips.
        val installed = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        val dir = File(root, installed.toString())
        val target = File(dir, entry.file)
        if (target.isFile) return target
        // Clips can change between versions; keep only the current version's copies.
        root.listFiles()?.filter { it != dir }?.forEach { it.deleteRecursively() }
        dir.mkdirs()
        val temp = File(dir, entry.file + ".tmp")
        context.assets.open("voices/${entry.file}").use { input -> temp.outputStream().use { input.copyTo(it) } }
        check(temp.renameTo(target)) { "Could not prepare the ${entry.name} voice." }
        return target
    }
}
