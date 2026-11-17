package com.nplayer.core.media.services

import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.CancellationException

/** Duration and resolution read from a container's index, without demuxing or decoding. */
internal data class ContainerMetadata(val duration: Long, val width: Int, val height: Int)

/**
 * Reads duration and resolution by jumping straight to the container's index.
 *
 * A platform metadata retriever opens a full demuxer - and, for some formats, a decoder - per
 * file, which costs tens of milliseconds. The three values the media list needs all live in an
 * index a handful of seeks away: `moov/mvhd` plus `trak/tkhd` in an ISO base media file, the
 * `Info` and `Tracks` elements in Matroska, `avih` plus `strf` in AVI. Reading them directly
 * costs about a millisecond per file, so summing a folder's durations is no longer dominated by
 * opening the files.
 *
 * Returns null when the container is not one of the supported layouts or is malformed, so the
 * caller can fall back to a full parse. A null never means the file is unusable.
 */
internal fun File.readContainerMetadata(): ContainerMetadata? = try {
    RandomAccessFile(
        this,
        "r",
    ).use { file ->
        file.readContainerMetadataByExtension(
            extension = name.substringAfterLast(
                '.',
                "",
            ),
        )
    }
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    // An unreadable or truncated container must never fail the scan that is listing it.
    null
}

private fun RandomAccessFile.readContainerMetadataByExtension(
    extension: String,
): ContainerMetadata? = when (extension.lowercase()) {
    "mp4", "m4v", "m4a", "mov", "3gp", "3g2", "f4v" -> readIsoBaseMedia()
    "mkv", "webm", "mka" -> readMatroska()
    "avi" -> readAvi()
    else -> null
}

// region ISO base media (MP4 / MOV / 3GP)

private const val BOX_HEADER_BYTES = 8L

/**
 * Walks the top-level box chain to `moov`, then reads `mvhd` for the duration and the first
 * video `trak/tkhd` for the resolution. Box sizes let every other box be skipped by seeking, so
 * a file with `moov` at the end still costs one seek per box instead of a full scan.
 */
private fun RandomAccessFile.readIsoBaseMedia(): ContainerMetadata? {
    val end = length()
    var pos = 0L
    while (pos + BOX_HEADER_BYTES <= end) {
        val header = readBoxHeader(
            pos = pos,
            parentEnd = end,
        ) ?: return null
        if (header.type == "moov") {
            val durationMillis = readMovieDuration(
                start = header.bodyStart,
                end = header.end,
            )
            if (durationMillis <= 0L) return null
            val resolution = readFirstVideoTrackResolution(
                start = header.bodyStart,
                end = header.end,
            )
            return ContainerMetadata(
                duration = durationMillis,
                width = resolution.first,
                height = resolution.second,
            )
        }
        pos = header.end
    }
    return null
}

private class BoxHeader(val type: String, val bodyStart: Long, val end: Long)

private fun RandomAccessFile.readBoxHeader(pos: Long, parentEnd: Long): BoxHeader? {
    seek(pos)
    val size32 = readUnsignedInt()
    val type = readFourCc()
    var bodyStart = pos + BOX_HEADER_BYTES
    val size = when (size32) {
        0L -> parentEnd - pos

        1L -> {
            bodyStart += 8
            readLong()
        }

        else -> size32
    }
    if (size < bodyStart - pos) return null
    val boxEnd = pos + size
    if (boxEnd > parentEnd || boxEnd <= pos) return null
    return BoxHeader(
        type = type,
        bodyStart = bodyStart,
        end = boxEnd,
    )
}

/** Reads `mvhd`, the movie header holding the timescale and the duration in that timescale. */
private fun RandomAccessFile.readMovieDuration(start: Long, end: Long): Long {
    var pos = start
    while (pos + BOX_HEADER_BYTES <= end) {
        val header = readBoxHeader(
            pos = pos,
            parentEnd = end,
        ) ?: return -1L
        if (header.type == "mvhd") {
            seek(header.bodyStart)
            val version = read()
            skipBytes(3)
            val timescale: Long
            val duration: Long
            if (version == 1) {
                skipBytes(16)
                timescale = readUnsignedInt()
                duration = readLong()
            } else {
                skipBytes(8)
                timescale = readUnsignedInt()
                duration = readUnsignedInt()
            }
            if (timescale <= 0L || duration <= 0L) return -1L
            return duration * 1000L / timescale
        }
        pos = header.end
    }
    return -1L
}

/** Returns the resolution of the first track whose handler is `vide`, or zeroes. */
private fun RandomAccessFile.readFirstVideoTrackResolution(
    start: Long,
    end: Long,
): Pair<Int, Int> {
    var pos = start
    while (pos + BOX_HEADER_BYTES <= end) {
        val header = readBoxHeader(
            pos = pos,
            parentEnd = end,
        ) ?: return 0 to 0
        if (header.type == "trak") {
            val resolution = readTrackResolution(
                start = header.bodyStart,
                end = header.end,
            )
            if (resolution.first > 0 && resolution.second > 0) return resolution
        }
        pos = header.end
    }
    return 0 to 0
}

private fun RandomAccessFile.readTrackResolution(start: Long, end: Long): Pair<Int, Int> {
    var pos = start
    var width = 0
    var height = 0
    var handler: String? = null
    while (pos + BOX_HEADER_BYTES <= end) {
        val header = readBoxHeader(
            pos = pos,
            parentEnd = end,
        ) ?: return 0 to 0
        when (header.type) {
            "tkhd" -> {
                seek(header.bodyStart)
                val version = read()
                // width and height are the last two 16.16 fixed point values of tkhd: at byte
                // 76 of the body for version 0 and byte 88 for version 1. One byte (the version)
                // has been consumed, so skip the rest of that offset.
                skipBytes(if (version == 1) 87 else 75)
                width = readUnsignedInt().toInt() shr 16
                height = readUnsignedInt().toInt() shr 16
            }

            "mdia" -> handler = readHandlerType(
                start = header.bodyStart,
                end = header.end,
            )
        }
        pos = header.end
    }
    // Dimensions of an audio or subtitle track describe nothing the list should show.
    return if (handler == "vide") width to height else 0 to 0
}

private fun RandomAccessFile.readHandlerType(start: Long, end: Long): String? {
    var pos = start
    while (pos + BOX_HEADER_BYTES <= end) {
        val header = readBoxHeader(
            pos = pos,
            parentEnd = end,
        ) ?: return null
        if (header.type == "hdlr") {
            seek(header.bodyStart)
            skipBytes(8)
            return readFourCc()
        }
        pos = header.end
    }
    return null
}

private fun RandomAccessFile.readFourCc(): String {
    val bytes = ByteArray(4)
    readFully(bytes)
    return String(
        bytes,
        Charsets.ISO_8859_1,
    )
}

private fun RandomAccessFile.readUnsignedInt(): Long = readInt().toLong() and 0xFFFFFFFFL

// endregion

// region Matroska (MKV / WebM)

private const val EBML_ID_SEGMENT = 0x18538067L
private const val EBML_ID_INFO = 0x1549A966L
private const val EBML_ID_TRACKS = 0x1654AE6BL
private const val EBML_ID_TRACK_ENTRY = 0xAEL
private const val EBML_ID_VIDEO = 0xE0L
private const val EBML_ID_TIMECODE_SCALE = 0x2AD7B1L
private const val EBML_ID_DURATION = 0x4489L
private const val EBML_ID_PIXEL_WIDTH = 0xB0L

/** `PixelHeight`. `0xB2` is `PixelCropBottom`, which is not the display height. */
private const val EBML_ID_PIXEL_HEIGHT = 0xBAL

private const val EBML_ID_CLUSTER = 0x1F43B675L

/** Sentinel for an EBML element written with the "unknown size" encoding. */
private const val EBML_SIZE_UNKNOWN = -1L

/** Longest EBML element ID, in bytes. */
private const val EBML_MAX_ID_BYTES = 4

/**
 * Walks the Matroska element tree until the duration and the video resolution are known.
 *
 * `Segment` is frequently written with an unknown size, so descending into it rather than
 * skipping past it is required. `Cluster` ends the walk: everything the list needs is written
 * before the first one.
 */
private fun RandomAccessFile.readMatroska(): ContainerMetadata? {
    val state = MatroskaState()
    walkMatroska(
        state = state,
        start = 0L,
        end = length(),
    )
    if (state.duration <= 0.0) return null
    val durationMillis = (state.duration * state.timecodeScale / 1_000_000.0).toLong()
    if (durationMillis <= 0L) return null
    return ContainerMetadata(
        duration = durationMillis,
        width = state.width,
        height = state.height,
    )
}

private class MatroskaState {
    /** Raw `Duration` value, in [timecodeScale] units. */
    var duration: Double = -1.0
    var timecodeScale: Long = 1_000_000L
    var width: Int = 0
    var height: Int = 0
}

private fun RandomAccessFile.walkMatroska(state: MatroskaState, start: Long, end: Long) {
    var pos = start
    while (pos < end) {
        seek(pos)
        val id = readEbmlId() ?: return
        // Unknown size is a valid encoding (live-muxed Segment/Cluster), not a read failure:
        // the element then simply extends to the end of its parent.
        val size = readEbmlSize() ?: return
        val bodyStart = filePointer
        val bodyEnd = if (size == EBML_SIZE_UNKNOWN) end else (bodyStart + size).coerceAtMost(end)
        when (id) {
            EBML_ID_SEGMENT,
            EBML_ID_INFO,
            EBML_ID_TRACKS,
            EBML_ID_TRACK_ENTRY,
            EBML_ID_VIDEO,
            -> walkMatroska(
                state = state,
                start = bodyStart,
                end = bodyEnd,
            )

            EBML_ID_TIMECODE_SCALE -> readEbmlUnsigned(
                bodyStart,
                bodyEnd,
            )?.let {
                if (it > 0L) state.timecodeScale = it
            }

            EBML_ID_DURATION -> state.duration = readEbmlFloat(
                bodyStart,
                bodyEnd,
            )

            EBML_ID_PIXEL_WIDTH -> readEbmlUnsigned(
                bodyStart,
                bodyEnd,
            )?.let {
                if (state.width == 0) state.width = it.toInt()
            }

            EBML_ID_PIXEL_HEIGHT -> readEbmlUnsigned(
                bodyStart,
                bodyEnd,
            )?.let {
                if (state.height == 0) state.height = it.toInt()
            }

            // Sample data follows the header elements; nothing after this is needed.
            EBML_ID_CLUSTER -> return
        }
        // Without a size the remaining elements cannot be located, so the parent is done.
        if (size == EBML_SIZE_UNKNOWN) return
        pos = bodyEnd
    }
}

/**
 * Reads an EBML element ID, keeping its length marker, and returns null at the end of the file.
 * An ID is at most four bytes long.
 */
private fun RandomAccessFile.readEbmlId(): Long? {
    val first = read()
    if (first < 0) return null
    var length = 1
    var mask = 0x80
    while (length <= EBML_MAX_ID_BYTES && (first and mask) == 0) {
        mask = mask shr 1
        length++
    }
    if (length > EBML_MAX_ID_BYTES) return null
    var value = first.toLong()
    repeat(length - 1) {
        val next = read()
        if (next < 0) return null
        value = (value shl 8) or next.toLong()
    }
    return value
}

/**
 * Reads an EBML element size, masking off the length marker. Returns null with the "unknown size"
 * encoding, which Matroska uses for live-muxed `Segment` and `Cluster` elements.
 */
private fun RandomAccessFile.readEbmlSize(): Long? {
    val first = read()
    if (first < 0) return null
    var length = 1
    var mask = 0x80
    while (length <= 8 && (first and mask) == 0) {
        mask = mask shr 1
        length++
    }
    if (length > 8) return null
    var value = (first and (mask - 1)).toLong()
    var allOnes = value == (mask - 1).toLong()
    repeat(length - 1) {
        val next = read()
        if (next < 0) return null
        if (next != 0xFF) allOnes = false
        value = (value shl 8) or next.toLong()
    }
    return if (allOnes) EBML_SIZE_UNKNOWN else value
}

private fun RandomAccessFile.readEbmlUnsigned(start: Long, end: Long): Long? {
    val size = (end - start).toInt()
    if (size <= 0 || size > 8) return null
    seek(start)
    var value = 0L
    repeat(size) {
        val next = read()
        if (next < 0) return null
        value = (value shl 8) or next.toLong()
    }
    return value
}

private fun RandomAccessFile.readEbmlFloat(start: Long, end: Long): Double {
    val size = (end - start).toInt()
    if (size != 4 && size != 8) return -1.0
    seek(start)
    return if (size == 4) {
        readFloat().toDouble()
    } else {
        readDouble()
    }
}

// endregion

// region AVI

private const val RIFF_HEADER_BYTES = 12L

/**
 * Reads `avih` for the frame timing and `strh/strf` for the video resolution.
 *
 * AVI duration is `totalFrames * dwMicroSecPerFrame`; there is no single field for it.
 */
private fun RandomAccessFile.readAvi(): ContainerMetadata? {
    val end = length()
    if (end < RIFF_HEADER_BYTES) return null
    seek(0)
    if (readFourCc() != "RIFF") return null
    readUnsignedInt()
    if (readFourCc() != "AVI ") return null

    var microSecPerFrame = 0L
    var totalFrames = 0L
    var width = 0
    var height = 0

    var pos = RIFF_HEADER_BYTES
    while (pos + BOX_HEADER_BYTES <= end) {
        val header = readBoxHeader(
            pos = pos,
            parentEnd = end,
        ) ?: break
        when (header.type) {
            "avih" -> {
                seek(header.bodyStart)
                microSecPerFrame = readUnsignedInt()
                skipBytes(16)
                totalFrames = readUnsignedInt()
            }

            "LIST" -> {
                seek(header.bodyStart)
                if (readFourCc() == "strl") {
                    val found = readAviStreamHeader(
                        start = header.bodyStart + 4,
                        end = header.end,
                    )
                    if (found != null) {
                        width = found.first
                        height = found.second
                    }
                }
            }
        }
        pos = header.end + (header.end - pos) % 2L
    }
    if (microSecPerFrame <= 0L || totalFrames <= 0L) return null
    return ContainerMetadata(
        duration = totalFrames * microSecPerFrame / 1000L,
        width = width,
        height = height,
    )
}

/** Returns the resolution of a stream list whose first stream is a video stream. */
private fun RandomAccessFile.readAviStreamHeader(start: Long, end: Long): Pair<Int, Int>? {
    var pos = start
    var isVideo = false
    var width = 0
    var height = 0
    while (pos + BOX_HEADER_BYTES <= end) {
        val header = readBoxHeader(
            pos = pos,
            parentEnd = end,
        ) ?: return null
        when (header.type) {
            "strh" -> {
                seek(header.bodyStart)
                isVideo = readFourCc() == "vids"
            }

            "strf" -> if (isVideo) {
                seek(header.bodyStart)
                skipBytes(4)
                width = readInt()
                height = readInt()
            }
        }
        pos = header.end
    }
    if (!isVideo || width <= 0 || height <= 0) return null
    return width to height
}

// endregion
