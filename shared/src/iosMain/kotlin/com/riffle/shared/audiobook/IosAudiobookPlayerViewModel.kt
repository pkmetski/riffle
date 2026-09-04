package com.riffle.shared.audiobook

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riffle.core.domain.AudiobookChapter
import com.riffle.core.domain.AudiobookTimeline
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.network.AbsPlaybackApi
import com.riffle.core.network.NetworkResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDefaults

data class IosAudiobookPlayerState(
    val loading: Boolean = true,
    val failed: Boolean = false,
    val title: String = "",
    val author: String = "",
    val coverUrl: String? = null,
    val isPlaying: Boolean = false,
    val speed: Float = 1f,
    val positionSec: Double = 0.0,
    val durationSec: Double = 0.0,
    val currentChapterTitle: String? = null,
    val chapters: List<AudiobookChapter> = emptyList(),
    val currentChapterIndex: Int = -1,
    val canPreviousChapter: Boolean = false,
    val canNextChapter: Boolean = false,
)

class IosAudiobookPlayerViewModel(
    private val itemId: String,
    private val sourceId: String?,
    private val bridgeFactory: IosAudioPlayerBridgeFactory,
    private val absPlaybackApi: AbsPlaybackApi,
    private val sourceRepository: SourceRepository,
    private val tokenStorage: TokenStorage,
) : ViewModel() {

    private val _state = MutableStateFlow(IosAudiobookPlayerState())
    val state: StateFlow<IosAudiobookPlayerState> = _state

    private var bridge: IosAudioPlayerBridge? = null
    private var timeline: AudiobookTimeline = AudiobookTimeline(0.0)
    private var sessionId: String? = null
    private var baseUrl: String = ""
    private var token: String = ""

    init {
        viewModelScope.launch { prepare() }
    }

    private suspend fun prepare() {
        val source = sourceId?.let { sourceRepository.getById(it) ?: sourceRepository.getActive() }
            ?: sourceRepository.getActive()

        if (source == null) {
            _state.value = _state.value.copy(loading = false, failed = true)
            return
        }

        baseUrl = source.url.value.trimEnd('/')
        token = tokenStorage.getToken(source.id) ?: ""

        val result = absPlaybackApi.openPlaybackSession(
            baseUrl = baseUrl,
            libraryItemId = itemId,
            deviceId = stableDeviceId(),
            token = token,
            insecureAllowed = source.insecureConnectionAllowed,
        )

        val session = result.getOrNull()
        if (session == null) {
            _state.value = _state.value.copy(loading = false, failed = true)
            return
        }

        sessionId = session.sessionId

        val chapters = session.chapters.map { c ->
            AudiobookChapter(
                index = c.id,
                startSec = c.startSec,
                endSec = c.endSec,
                title = c.title,
            )
        }
        timeline = AudiobookTimeline(durationSec = session.durationSec, chapters = chapters)

        val trackUrls = session.tracks.map { t -> "$baseUrl${t.contentUrl}?token=$token" }
        val trackOffsets = session.tracks.map { it.startOffsetSec }
        val startAt = session.currentTimeSec.coerceIn(0.0, session.durationSec)

        val b = bridgeFactory.create()
        bridge = b

        b.setPositionCallback(object : IosPositionCallback {
            override fun onPosition(positionSec: Double) {
                val ch = timeline.chapterAt(positionSec)
                _state.value = _state.value.copy(
                    positionSec = positionSec,
                    currentChapterTitle = ch?.title,
                    currentChapterIndex = ch?.index ?: -1,
                    canPreviousChapter = timeline.canPreviousChapter,
                    canNextChapter = timeline.canNextChapter,
                )
            }
        })
        b.setPlayingCallback(object : IosPlayingCallback {
            override fun onPlaying(isPlaying: Boolean) {
                _state.value = _state.value.copy(isPlaying = isPlaying)
            }
        })

        b.preparePlayer(trackUrls, trackOffsets.toDoubleArray(), startAt, session.durationSec)

        val startChapter = timeline.chapterAt(startAt)
        _state.value = _state.value.copy(
            loading = false,
            durationSec = session.durationSec,
            positionSec = startAt,
            chapters = chapters,
            currentChapterTitle = startChapter?.title,
            currentChapterIndex = startChapter?.index ?: -1,
            canPreviousChapter = timeline.canPreviousChapter,
            canNextChapter = timeline.canNextChapter,
        )
    }

    fun togglePlayPause() {
        val b = bridge ?: return
        if (_state.value.isPlaying) b.pause() else b.play()
    }

    fun seekTo(positionSec: Double) {
        bridge?.seekTo(positionSec.coerceIn(0.0, _state.value.durationSec))
    }

    fun setSpeed(speed: Float) {
        bridge?.setSpeed(speed)
        _state.value = _state.value.copy(speed = speed)
    }

    fun previousChapter() {
        val target = timeline.previousChapterTargetSec(_state.value.positionSec) ?: return
        bridge?.seekTo(target)
    }

    fun nextChapter() {
        val target = timeline.nextChapterTargetSec(_state.value.positionSec) ?: return
        bridge?.seekTo(target)
    }

    fun updateNowPlaying(title: String, author: String, coverUrl: String?) {
        val b = bridge ?: return
        val s = _state.value
        b.setNowPlayingInfo(
            title = title,
            author = author,
            durationSec = s.durationSec,
            positionSec = s.positionSec,
            coverUrl = coverUrl,
        )
    }

    override fun onCleared() {
        bridge?.dispose()
        bridge = null
    }

    companion object {
        private const val DEVICE_ID_KEY = "ios_riffle_device_id"

        private fun stableDeviceId(): String {
            val defaults = NSUserDefaults.standardUserDefaults
            val existing = defaults.stringForKey(DEVICE_ID_KEY)
            if (existing != null) return existing
            val id = NSUUID().UUIDString
            defaults.setObject(id, forKey = DEVICE_ID_KEY)
            return id
        }
    }
}

private fun <T> NetworkResult<T>.getOrNull(): T? =
    if (this is NetworkResult.Success) value else null
