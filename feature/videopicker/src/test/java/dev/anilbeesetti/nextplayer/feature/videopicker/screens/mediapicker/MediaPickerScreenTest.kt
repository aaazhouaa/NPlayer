package dev.anilbeesetti.nextplayer.feature.videopicker.screens.mediapicker

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.anilbeesetti.nextplayer.core.domain.MediaHolder
import dev.anilbeesetti.nextplayer.core.model.ApplicationPreferences
import dev.anilbeesetti.nextplayer.core.model.Folder
import dev.anilbeesetti.nextplayer.core.model.MediaLayoutMode
import dev.anilbeesetti.nextplayer.core.model.MediaViewMode
import dev.anilbeesetti.nextplayer.core.model.PlaylistSummary
import dev.anilbeesetti.nextplayer.core.model.PlaylistType
import dev.anilbeesetti.nextplayer.core.model.Video
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.base.DataState
import dev.anilbeesetti.nextplayer.core.ui.components.LocalTopLevelFabSetter
import dev.anilbeesetti.nextplayer.core.ui.components.TopLevelFabState
import dev.anilbeesetti.nextplayer.core.ui.theme.NextPlayerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class MediaPickerScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun rootResumeFabUsesAutomaticPlaylistInEveryViewMode() {
        assertResumeFabUsesAutomaticPlaylist(folderName = null)
    }

    @Test
    fun folderResumeFabUsesAutomaticPlaylistInEveryViewMode() {
        assertResumeFabUsesAutomaticPlaylist(folderName = "Movies")
    }

    private fun assertResumeFabUsesAutomaticPlaylist(folderName: String?) {
        val application = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(application).grantPermissions(Manifest.permission.READ_MEDIA_VIDEO)
        val actions = mutableListOf<MediaPickerAction>()
        val video = Video.sample.copy(uriString = "content://media/external/video/media/2")
        var viewMode by mutableStateOf(MediaViewMode.FOLDERS)
        var rootFab: TopLevelFabState? = null
        val setFab: (String, TopLevelFabState?) -> Unit = { _, fab -> rootFab = fab }
        composeRule.setContent {
            CompositionLocalProvider(value = LocalTopLevelFabSetter provides setFab) {
                NextPlayerTheme {
                    MediaPickerScreenContent(
                        state = MediaPickerUiState(
                            folderName = folderName,
                            recentlyPlayedVideo = video,
                            preferences = ApplicationPreferences(mediaViewMode = viewMode),
                            mediaDataState = DataState.Success(
                                value = MediaHolder(
                                    videos = if (folderName == null &&
                                        viewMode != MediaViewMode.VIDEOS
                                    ) {
                                        emptyList()
                                    } else {
                                        listOf(
                                            Video.sample.copy(
                                                uriString =
                                                    "content://media/external/video/media/1",
                                            ),
                                            video,
                                        )
                                    },
                                    folders = listOf(
                                        element = Folder(
                                            name = "Movies",
                                            path = "/Movies",
                                            dateModified = 0,
                                        ),
                                    ),
                                ),
                            ),
                        ),
                        onAction = actions::add,
                    )
                }
            }
        }

        for (mode in MediaViewMode.entries) {
            composeRule.runOnIdle {
                viewMode = mode
                actions.clear()
            }
            if (folderName == null) {
                composeRule.runOnIdle { checkNotNull(value = rootFab).onClick() }
            } else {
                composeRule.onNodeWithContentDescription(label = "Play").performClick()
            }
            // A single-video launch lets the player build the sorted queue; an explicit
            // one-item PlaySelectedItems queue disables that behavior.
            composeRule.runOnIdle {
                assertEquals(
                    "Resume in $mode",
                    listOf(MediaPickerAction.OnPlayVideo(video.uriString.toUri())),
                    actions,
                )
            }
        }
    }

    @Test
    @Config(qualifiers = "w960dp-h540dp-land-television")
    fun tvFolderFabUpReturnsToTheVideoInsteadOfSettings() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(application).grantPermissions(Manifest.permission.READ_MEDIA_VIDEO)
        shadowOf(application.packageManager).setSystemFeature(
            PackageManager.FEATURE_LEANBACK,
            true,
        )
        val video = Video.sample.copy(
            nameWithExtension = "Clip.mp4",
            uriString = "content://video/1",
        )
        composeRule.setContent {
            NextPlayerTheme {
                MediaPickerScreenContent(
                    state = MediaPickerUiState(
                        folderName = "Movies",
                        mediaDataState = DataState.Success(
                            value = MediaHolder(
                                videos = listOf(element = video),
                                folders = emptyList(),
                            ),
                        ),
                    ),
                )
            }
        }

        composeRule.onNodeWithText(text = "Clip").requestFocus()
        composeRule.onNodeWithContentDescription(label = "Play").requestFocus()
        composeRule.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithText(text = "Clip").assertIsFocused()
    }

    @Test
    fun gridLayoutRendersVideosWithThumbnails() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(application).grantPermissions(Manifest.permission.READ_MEDIA_VIDEO)
        val video = Video.sample.copy(
            nameWithExtension = "Clip.mp4",
            uriString = "file:///storage/emulated/0/.Movies/Clip.mp4",
        )
        composeRule.setContent {
            NextPlayerTheme {
                MediaPickerScreenContent(
                    state = MediaPickerUiState(
                        folderName = ".Movies",
                        mediaDataState = DataState.Success(
                            value = MediaHolder(
                                videos = listOf(element = video),
                                folders = emptyList(),
                            ),
                        ),
                        preferences = ApplicationPreferences(
                            mediaViewMode = MediaViewMode.VIDEOS,
                            mediaLayoutMode = MediaLayoutMode.GRID,
                        ),
                    ),
                )
            }
        }

        // Grid items are sized with IntrinsicSize.Min, which must never reach a
        // SubcomposeLayout (BoxWithConstraints) inside the thumbnail.
        composeRule.onNodeWithText(text = "Clip").assertIsDisplayed()
    }

    @Test
    fun gridLayoutRendersFieldChipsWhenEnabled() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(application).grantPermissions(Manifest.permission.READ_MEDIA_VIDEO)
        val video = Video.sample.copy(
            nameWithExtension = "Clip.mp4",
            uriString = "file:///storage/emulated/0/.Movies/Clip.mp4",
        )
        composeRule.setContent {
            NextPlayerTheme {
                MediaPickerScreenContent(
                    state = MediaPickerUiState(
                        folderName = ".Movies",
                        mediaDataState = DataState.Success(
                            value = MediaHolder(
                                videos = listOf(element = video),
                                folders = emptyList(),
                            ),
                        ),
                        preferences = ApplicationPreferences(
                            mediaViewMode = MediaViewMode.VIDEOS,
                            mediaLayoutMode = MediaLayoutMode.GRID,
                            showPathField = true,
                            showSizeField = true,
                            showResolutionField = true,
                        ),
                    ),
                )
            }
        }

        // Resolution is overlaid on the thumbnail, the size chip sits at the
        // thumbnail's bottom-left corner, and the path stays in the supporting fields.
        composeRule.onNodeWithText(text = "1080p").assertIsDisplayed()
        composeRule.onNodeWithText(text = "320KB").assertIsDisplayed()
        composeRule.onNodeWithText(text = "Download").assertIsDisplayed()
    }

    @Test
    fun gridLayoutKeepsNewBadgeAndResolutionOnTheSameRow() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(application).grantPermissions(Manifest.permission.READ_MEDIA_VIDEO)
        val video = Video.sample.copy(
            nameWithExtension = "Clip.mp4",
            uriString = "file:///storage/emulated/0/.Movies/Clip.mp4",
            dateAdded = System.currentTimeMillis() / 1000,
        )
        composeRule.setContent {
            NextPlayerTheme {
                MediaPickerScreenContent(
                    state = MediaPickerUiState(
                        folderName = ".Movies",
                        mediaDataState = DataState.Success(
                            value = MediaHolder(
                                videos = listOf(element = video),
                                folders = emptyList(),
                            ),
                        ),
                        preferences = ApplicationPreferences(
                            mediaViewMode = MediaViewMode.VIDEOS,
                            mediaLayoutMode = MediaLayoutMode.GRID,
                            showResolutionField = true,
                        ),
                    ),
                )
            }
        }

        // The chips are looked up in the unmerged tree: the list item merges its
        // descendants, which would collapse both chips into a single node.
        val newBadgeBounds = composeRule
            .onNodeWithText(
                text = "NEW",
                useUnmergedTree = true,
            )
            .getBoundsInRoot()
        val resolutionBounds = composeRule
            .onNodeWithText(
                text = "1080p",
                useUnmergedTree = true,
            )
            .getBoundsInRoot()

        // The resolution chip shares the new badge's row instead of starting a new one.
        assertTrue(
            "new=$newBadgeBounds res=$resolutionBounds",
            resolutionBounds.top < newBadgeBounds.bottom,
        )
        assertTrue(
            "new=$newBadgeBounds res=$resolutionBounds",
            resolutionBounds.left >= newBadgeBounds.right,
        )
    }

    @Test
    fun newBadgeDisappearsAsSoonAsTheVideoIsNoLongerNew() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(application).grantPermissions(Manifest.permission.READ_MEDIA_VIDEO)
        val newVideo = Video.sample.copy(
            nameWithExtension = "Clip.mp4",
            uriString = "content://media/external/video/media/1",
            dateAdded = System.currentTimeMillis() / 1000,
            lastPlayedAt = null,
        )
        var video by mutableStateOf(value = newVideo)
        composeRule.setContent {
            NextPlayerTheme {
                MediaPickerScreenContent(
                    state = MediaPickerUiState(
                        folderName = "Movies",
                        mediaDataState = DataState.Success(
                            value = MediaHolder(
                                videos = listOf(element = video),
                                folders = emptyList(),
                            ),
                        ),
                    ),
                )
            }
        }

        composeRule.onNodeWithText(
            text = "NEW",
            useUnmergedTree = true,
        ).assertIsDisplayed()

        // Playing the video (or marking it read) sets isRead, which makes the video no longer
        // new. The badge must react to that change immediately instead of waiting for the next
        // polling tick or a manual refresh.
        composeRule.runOnIdle { video = newVideo.copy(isRead = true) }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(
            text = "NEW",
            useUnmergedTree = true,
        ).assertDoesNotExist()
    }

    @Test
    fun longPressOnItemVibratesOnce() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(application).grantPermissions(Manifest.permission.READ_MEDIA_VIDEO)
        val video = Video.sample.copy(
            nameWithExtension = "Clip.mp4",
            uriString = "content://media/external/video/media/1",
        )
        val hapticTypes = mutableListOf<HapticFeedbackType>()
        val hapticFeedback = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                hapticTypes.add(element = hapticFeedbackType)
            }
        }
        composeRule.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides hapticFeedback) {
                NextPlayerTheme {
                    MediaPickerScreenContent(
                        state = MediaPickerUiState(
                            folderName = "Movies",
                            mediaDataState = DataState.Success(
                                value = MediaHolder(
                                    videos = listOf(element = video),
                                    folders = emptyList(),
                                ),
                            ),
                        ),
                    )
                }
            }
        }

        composeRule.onNodeWithText(text = "Clip").performTouchInput { longClick() }

        // Exactly one haptic: the list item's combinedClickable performs the framework
        // long-press feedback, and MediaView must not add a second one.
        assertEquals(
            listOf(element = HapticFeedbackType.LongPress),
            hapticTypes,
        )
    }

    @Test
    fun loadingWithoutStoragePermissionShowsPermissionMessage() {
        val permissionMessage = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getString(R.string.permission_not_granted)

        composeRule.setContent {
            NextPlayerTheme {
                MediaPickerScreenContent(
                    state = MediaPickerUiState(folderName = null),
                )
            }
        }

        composeRule.onNodeWithText(text = permissionMessage).assertIsDisplayed()
    }

    @Test
    fun grantingStoragePermissionFromSettingsStartsMediaCollection() {
        val actions = mutableListOf<MediaPickerAction>()
        composeRule.setContent {
            NextPlayerTheme {
                MediaPickerScreenContent(
                    state = MediaPickerUiState(folderName = null),
                    onAction = actions::add,
                )
            }
        }
        composeRule.waitForIdle()

        val application = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(application).grantPermissions(Manifest.permission.READ_MEDIA_VIDEO)
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        composeRule.waitForIdle()

        assertTrue(MediaPickerAction.OnPermissionAccepted in actions)
    }

    @Test
    fun choosingPlaylistDispatchesActionAndExitsSelectionMode() {
        val actions = mutableListOf<MediaPickerAction>()
        val application = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(application).grantPermissions(Manifest.permission.READ_MEDIA_VIDEO)
        var showPlaylistDialog by mutableStateOf(value = false)
        val video = Video.sample.copy(
            nameWithExtension = "Video.mp4",
            uriString = "content://video/1",
        )
        composeRule.setContent {
            NextPlayerTheme {
                MediaPickerScreenContent(
                    state = MediaPickerUiState(
                        folderName = null,
                        mediaDataState = DataState.Success(
                            value = MediaHolder(
                                folders = emptyList(),
                                videos = listOf(element = video),
                            ),
                        ),
                        playlists = listOf(
                            element = PlaylistSummary(
                                id = 7,
                                name = "Movies",
                                type = PlaylistType.LOCAL,
                                itemCount = 2,
                                lastRefreshedAt = null,
                            ),
                        ),
                        addToPlaylistState = AddToPlaylistState(
                            isVisible = showPlaylistDialog,
                            hasVideos = true,
                        ),
                    ),
                    onAction = actions::add,
                )
            }
        }

        composeRule.onNodeWithText(text = "Video").performTouchInput { longClick() }
        composeRule.onNodeWithText(text = "1 / 1 Selected").assertIsDisplayed()
        composeRule.runOnIdle { showPlaylistDialog = true }

        composeRule.onNodeWithText(text = "Choose a playlist").assertIsDisplayed()
        composeRule.onNodeWithText(text = "Create new playlist").assertIsDisplayed()
        composeRule.onNodeWithText(text = "Movies").performClick()

        assertTrue(MediaPickerAction.AddSelectionToPlaylist(playlistId = 7) in actions)
        composeRule.runOnIdle { showPlaylistDialog = false }
        composeRule.onNodeWithText(text = "1 / 1 Selected").assertDoesNotExist()
    }
}
