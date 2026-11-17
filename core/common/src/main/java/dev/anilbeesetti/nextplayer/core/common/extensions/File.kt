package dev.anilbeesetti.nextplayer.core.common.extensions

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun File.getSubtitles(): List<File> = withContext(Dispatchers.IO) {
    val mediaName = this@getSubtitles.nameWithoutExtension
    val parentDir = this@getSubtitles.parentFile
    val subtitleExtensions = listOf(
        "srt",
        "ssa",
        "ass",
        "vtt",
        "ttml",
    )

    subtitleExtensions.mapNotNull { extension ->
        val file = File(
            parentDir,
            "$mediaName.$extension",
        )
        file.takeIf { it.exists() && it.isFile }
    }
}

suspend fun File.getLocalSubtitles(
    context: Context,
    excludeSubsList: List<Uri> = emptyList(),
): List<Uri> = withContext(Dispatchers.Default) {
    val excludeSubsPathSet = excludeSubsList.mapNotNull { context.getPath(uri = it) }.toSet()

    getSubtitles().mapNotNull { file ->
        if (file.path !in excludeSubsPathSet) {
            file.toUri()
        } else {
            null
        }
    }
}

fun String.getThumbnail(): File? {
    val filePathWithoutExtension = this.substringBeforeLast(delimiter = ".")
    val imageExtensions = listOf(
        "png",
        "jpg",
        "jpeg",
    )
    for (imageExtension in imageExtensions) {
        val file = File("$filePathWithoutExtension.$imageExtension")
        if (file.exists()) return file
    }
    return null
}

fun File.isSubtitle(): Boolean {
    val subtitleExtensions = listOf(
        "srt",
        "ssa",
        "ass",
        "vtt",
        "ttml",
    )
    return extension.lowercase() in subtitleExtensions
}

fun File.deleteFiles() {
    try {
        listFiles()?.onEach { it.delete() }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

val File.prettyName: String
    get() = name.takeIf { parent != "/storage/emulated" } ?: "Internal Storage"

/**
 * The storage-volume root containing this absolute path: "/storage/emulated/<user>" for
 * emulated (internal) storage, or "/storage/<volume>" for removable volumes (SD card / USB
 * OTG). Returns null for unrecognised layouts, including protocol-relative paths such as the
 * ones network clients use.
 */
fun volumeRootOf(path: String): String? {
    val parts = path.split('/').filter { it.isNotEmpty() }
    return when {
        parts.size >= 3 && parts[0] == "storage" && parts[1] == "emulated" ->
            "/storage/emulated/${parts[2]}"

        parts.size >= 2 && parts[0] == "storage" -> "/storage/${parts[1]}"

        else -> null
    }
}

/**
 * This directory path as shown to the user: relative to its storage-volume root, so
 * "/storage/emulated/0/Download" becomes "Download" and "/storage/emulated/0" becomes
 * "Internal Storage". Paths outside a recognised volume (network clients, vault) are returned
 * unchanged.
 */
val String.readablePath: String
    get() {
        val root = volumeRootOf(path = this) ?: return this
        val relative = removePrefix(prefix = root).trim('/')
        return relative.ifEmpty { "Internal Storage" }
    }
