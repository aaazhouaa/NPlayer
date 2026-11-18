package com.nplayer.core.common

import java.io.File

/**
 * Receives the session files [LogStore] removes, as far as the logger is concerned.
 *
 * The newest session is usually the one being written. Deleting a file that is still open keeps
 * absorbing writes into an unlinked inode: every later entry returns normally and none of them
 * shows up in the directory, so the viewer looks empty while the logger appears to work. Removing
 * the open session therefore has to replace it instead of unlinking it.
 *
 * This is an interface so [LogStore] can be tested without a running [Logger], which needs a
 * [android.content.Context] and writes to logcat.
 */
interface ActiveLogSession {

    /**
     * Called after [from] was removed. Implementations start a fresh session and keep recording
     * when [from] is the session they are writing; a file that is not open is ignored, so deleting
     * an older session cannot end the running one.
     */
    fun onSessionDeleted(from: File)
}
