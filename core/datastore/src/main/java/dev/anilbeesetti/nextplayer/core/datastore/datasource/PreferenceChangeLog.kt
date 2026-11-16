package dev.anilbeesetti.nextplayer.core.datastore.datasource

import dev.anilbeesetti.nextplayer.core.common.Logger
import java.lang.reflect.Modifier

/**
 * Logs which preference fields a transform changed, so a setting that appears to have no effect
 * can be traced to the value that was actually persisted. Reflection over the data class fields
 * keeps this in sync with the model without a hand-written diff per preference.
 */
internal fun <T : Any> logPreferenceChanges(tag: String, previous: T, updated: T) {
    val changes = buildList {
        for (field in previous.javaClass.declaredFields) {
            if (Modifier.isStatic(field.modifiers)) continue
            field.isAccessible = true
            val before = field.get(previous)
            val after = field.get(updated)
            if (before != after) {
                add("${field.name}=$before -> $after")
            }
        }
    }
    if (changes.isNotEmpty()) {
        Logger.logInfo(
            tag = tag,
            message = "preferences changed: ${changes.joinToString(separator = ", ")}",
        )
    }
}
