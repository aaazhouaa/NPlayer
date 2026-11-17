package com.nplayer

import android.Manifest
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.nplayer.core.thumbnail.FfmpegThumbnailRetriever
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class VideoThumbnailDecoderTest {

    private companion object {
        const val CONTENT_URI = "content://media/external/video/media/1"

        // Red/green/blue IDR frames start at PTS 30s, so relative seeks must apply the offset.
        const val TS_ASSET = "thumbnail-colors-offset.ts"
    }

    @Test
    fun contentVideoWithInaccessiblePathDecodesWithoutCachedThumbnail() = runBlocking {
        withContentVideo { context ->
            assertDecodesAllStrategies(
                context = context,
                uri = CONTENT_URI,
            )
        }
    }

    @Test
    fun fileVideoDecodesAllStrategiesThroughDescriptor() = runBlocking {
        withVideoAsset(assetName = "thumbnail-test.mp4") { context, file ->
            assertDecodesAllStrategies(
                context = context,
                uri = file.toURI().toString(),
            )
        }
    }

    @Test
    fun renamingParentToHiddenDirectoryPreservesPercentageFrame() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val parent = File(
            context.cacheDir,
            "thumbnail-folder-${UUID.randomUUID()}",
        ).apply { check(mkdir()) }
        val visibleDirectory = File(
            parent,
            "Movies",
        ).apply { check(mkdir()) }
        val hiddenDirectory = File(
            parent,
            ".Movies",
        )
        val video = File(
            visibleDirectory,
            "episode.mp4",
        )
        try {
            InstrumentationRegistry.getInstrumentation().context.assets.open(
                TS_ASSET,
            ).use { input ->
                video.outputStream().use { input.copyTo(out = it) }
            }
            val visibleFrame = assertDecodes(
                context = context,
                uri = video.toURI().toString(),
                strategy = ThumbnailStrategy.FrameAtPercentage(),
            ).image.toBitmap()
            check(visibleDirectory.renameTo(hiddenDirectory))
            val hiddenVideo = File(
                hiddenDirectory,
                video.name,
            )
            val hiddenFrame = assertDecodes(
                context = context,
                uri = hiddenVideo.toURI().toString(),
                strategy = ThumbnailStrategy.FrameAtPercentage(),
            ).image.toBitmap()
            assertTrue(visibleFrame.sameAs(hiddenFrame))
            assertDecodesDifferentFrameAtThirtyThreePercent(
                context = context,
                uri = hiddenVideo.toURI().toString(),
            )
        } finally {
            File(
                hiddenDirectory,
                video.name,
            ).delete()
            video.delete()
            hiddenDirectory.delete()
            visibleDirectory.delete()
            parent.delete()
        }
    }

    @Test
    fun framePositionPastEndOfVideoStillDecodesAThumbnail() = runBlocking {
        withContentVideo { context ->
            // A duration longer than the real stream makes the seek fail on both retrievers;
            // the decoder must still produce a frame instead of failing the request.
            assertDecodes(
                context = context,
                uri = CONTENT_URI,
                strategy = ThumbnailStrategy.FrameAtPercentage(percentage = 2f),
            )
            assertDecodes(
                context = context,
                uri = CONTENT_URI,
                strategy = ThumbnailStrategy.Hybrid(percentage = 2f),
            )
        }
    }

    @Test
    fun transportStreamWithNonzeroStartTimeFileDecodesDifferentFrameAtThirtyThreePercent() =
        runBlocking {
            withVideoAsset(assetName = TS_ASSET) { context, file ->
                assertDecodesDifferentFrameAtThirtyThreePercent(
                    context = context,
                    uri = file.toURI().toString(),
                )
            }
        }

    @Test
    fun transportStreamNamedMp4DecodesDifferentFrameAtThirtyThreePercent() = runBlocking {
        withVideoAsset(
            assetName = TS_ASSET,
            extension = "mp4",
        ) { context, file ->
            assertDecodesDifferentFrameAtThirtyThreePercent(
                context = context,
                uri = file.toURI().toString(),
            )
        }
    }

    @Test
    fun transportStreamWithNonzeroStartTimeContentUriDecodesDifferentFrameAtThirtyThreePercent() =
        runBlocking {
            for (mimeType in listOf(
                "video/mp2t",
                "video/mp2ts",
                "video/mp4",
            )) {
                withContentVideo(
                    assetName = TS_ASSET,
                    mimeType = mimeType,
                ) { context ->
                    assertDecodesDifferentFrameAtThirtyThreePercent(
                        context = context,
                        uri = CONTENT_URI,
                    )
                }
            }
        }

    @Test
    fun transportStreamRetrieverSeeksAfterOriginalDescriptorIsClosed() = runBlocking {
        withVideoAsset(assetName = TS_ASSET) { _, file ->
            FfmpegThumbnailRetriever().use { retriever ->
                ParcelFileDescriptor.open(
                    file,
                    ParcelFileDescriptor.MODE_READ_ONLY,
                ).use { descriptor ->
                    retriever.setDataSource(descriptor)
                }
                assertTrue(
                    "Expected about six seconds excluding the 30s PTS offset, " +
                        "got ${retriever.durationMillis}ms",
                    retriever.durationMillis in 5_000L..6_500L,
                )
                val firstFrame = checkNotNull(retriever.getFrameAtTime(0L))
                try {
                    val targetFrame = checkNotNull(
                        retriever.getFrameAtTime((retriever.durationMillis * 0.33 * 1000).toLong()),
                    )
                    try {
                        assertDifferentFrameColors(
                            firstFrame = firstFrame,
                            targetFrame = targetFrame,
                        )
                    } finally {
                        targetFrame.recycle()
                    }
                } finally {
                    firstFrame.recycle()
                }
            }
        }
    }

    private suspend fun withVideoAsset(
        assetName: String,
        extension: String = assetName.substringAfterLast('.'),
        block: suspend (Context, File) -> Unit,
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File.createTempFile(
            "thumbnail-test-",
            ".$extension",
            context.cacheDir,
        )
        try {
            InstrumentationRegistry.getInstrumentation().context.assets.open(
                assetName,
            ).use { input ->
                file.outputStream().use { input.copyTo(out = it) }
            }
            block(
                context,
                file,
            )
        } finally {
            file.delete()
        }
    }

    private suspend fun withContentVideo(
        assetName: String = "thumbnail-test.mp4",
        mimeType: String = "video/mp4",
        block: suspend (Context) -> Unit,
    ) = withVideoAsset(assetName = assetName) { targetContext, file ->
        val provider = object : ContentProvider() {
            override fun onCreate(): Boolean = true

            override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
                ParcelFileDescriptor.open(
                    file,
                    ParcelFileDescriptor.MODE_READ_ONLY,
                )

            override fun getType(uri: Uri): String = mimeType

            override fun query(
                uri: Uri,
                projection: Array<out String>?,
                selection: String?,
                selectionArgs: Array<out String>?,
                sortOrder: String?,
            ): Cursor = MatrixCursor(arrayOf(MediaStore.Video.Media.DATA)).apply {
                addRow(arrayOf("/inaccessible/.trashed-1234567890-$assetName"))
            }

            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun delete(
                uri: Uri,
                selection: String?,
                selectionArgs: Array<out String>?,
            ): Int = 0
            override fun update(
                uri: Uri,
                values: ContentValues?,
                selection: String?,
                selectionArgs: Array<out String>?,
            ): Int = 0
        }
        val resolver = ContentResolver.wrap(provider)
        val context = object : ContextWrapper(targetContext) {
            override fun getContentResolver(): ContentResolver = resolver
        }

        block(context)
    }

    @Test
    @SdkSuppress(minSdkVersion = 30)
    fun trashedMediaStoreVideoDecodesWithoutCachedThumbnail() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "nplayer-thumbnail-${UUID.randomUUID()}.mp4"
        val uri = checkNotNull(
            context.contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(
                        MediaStore.MediaColumns.DISPLAY_NAME,
                        name,
                    )
                    put(
                        MediaStore.MediaColumns.MIME_TYPE,
                        "video/mp4",
                    )
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        "Movies/",
                    )
                    put(
                        MediaStore.MediaColumns.IS_PENDING,
                        1,
                    )
                },
            ),
        )
        val readPermission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        instrumentation.uiAutomation.adoptShellPermissionIdentity(readPermission)
        try {
            instrumentation.context.assets.open("thumbnail-test.mp4").use { input ->
                context.contentResolver.openOutputStream(uri)!!.use { input.copyTo(out = it) }
            }
            context.contentResolver.update(
                uri,
                ContentValues().apply {
                    put(
                        MediaStore.MediaColumns.IS_PENDING,
                        0,
                    )
                },
                null,
                null,
            )
            context.grantUriPermission(
                context.packageName,
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            // Transfer ownership so the test covers another app's trashed video.
            shell(
                command = "content update --uri $uri " +
                    "--bind owner_package_name:s:com.android.shell --bind is_trashed:i:1",
            )
            assertTrue(
                shell(command = "content query --uri $uri --projection owner_package_name")
                    .contains("owner_package_name=com.android.shell"),
            )
            context.contentResolver.query(
                uri,
                arrayOf(
                    MediaStore.MediaColumns.IS_TRASHED,
                ),
                Bundle().apply {
                    putInt(
                        MediaStore.QUERY_ARG_MATCH_TRASHED,
                        MediaStore.MATCH_INCLUDE,
                    )
                },
                null,
            )!!.use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(
                    1,
                    cursor.getInt(0),
                )
            }

            assertDecodesAllStrategies(
                context = context,
                uri.toString(),
            )
        } finally {
            context.revokeUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            shell(command = "content delete --uri $uri")
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }

    private suspend fun assertDecodesAllStrategies(context: Context, uri: String) {
        for (strategy in listOf(
            ThumbnailStrategy.FirstFrame,
            ThumbnailStrategy.FrameAtPercentage(),
            ThumbnailStrategy.Hybrid(),
        )) {
            assertDecodes(
                context = context,
                uri = uri,
                strategy = strategy,
            )
        }
    }

    private suspend fun assertDecodesDifferentFrameAtThirtyThreePercent(
        context: Context,
        uri: String,
    ) {
        val firstFrame = assertDecodes(
            context = context,
            uri = uri,
            strategy = ThumbnailStrategy.FirstFrame,
        ).image.toBitmap()
        val targetFrame = assertDecodes(
            context = context,
            uri = uri,
            strategy = ThumbnailStrategy.FrameAtPercentage(percentage = 0.33f),
        ).image.toBitmap()
        assertDifferentFrameColors(
            firstFrame = firstFrame,
            targetFrame = targetFrame,
        )
    }

    private fun assertDifferentFrameColors(firstFrame: Bitmap, targetFrame: Bitmap) {
        val firstColor = firstFrame.getPixel(
            firstFrame.width / 2,
            firstFrame.height / 2,
        )
        val targetColor = targetFrame.getPixel(
            targetFrame.width / 2,
            targetFrame.height / 2,
        )
        val colorDistance = abs(Color.red(firstColor) - Color.red(targetColor)) +
            abs(Color.green(firstColor) - Color.green(targetColor)) +
            abs(Color.blue(firstColor) - Color.blue(targetColor))
        assertTrue(
            "Expected distinct first/33% frame colors, got " +
                "${Integer.toHexString(firstColor)} and ${Integer.toHexString(targetColor)}",
            colorDistance > 100,
        )
    }

    private suspend fun assertDecodes(
        context: Context,
        uri: String,
        strategy: ThumbnailStrategy,
    ): SuccessResult {
        val imageLoader = ImageLoader.Builder(context = context)
            .components { add(VideoThumbnailDecoder.Factory { strategy }) }
            .build()
        try {
            val result = imageLoader.execute(
                ImageRequest.Builder(context = context)
                    .data(data = uri)
                    .size(
                        width = 160,
                        height = 90,
                    )
                    .allowHardware(false)
                    .memoryCachePolicy(CachePolicy.DISABLED)
                    .diskCachePolicy(CachePolicy.DISABLED)
                    .build(),
            )
            assertTrue(
                "$strategy: $result",
                result is SuccessResult,
            )
            assertTrue((result as SuccessResult).image.width > 0)
            assertTrue(result.image.height > 0)
            return result
        } finally {
            imageLoader.shutdown()
        }
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
    ).bufferedReader().use { it.readText() }
}
