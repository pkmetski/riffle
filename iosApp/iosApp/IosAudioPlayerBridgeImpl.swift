import AVFoundation
import MediaPlayer
import Riffle
import UIKit

/// Swift implementation of IosAudioPlayerBridge, backed by AVQueuePlayer.
/// One instance per audiobook player open — created by IosAudioPlayerBridgeFactoryImpl.
///
/// The bridge is deliberately *index-addressed*: it reports `(trackIndex, offsetInTrack)` and never a
/// book-absolute position. `IosAudioPlayerController` owns the projection using the shared
/// `AudiobookTracks` math. The reason is `AVQueuePlayer`'s queue semantics — `items()` returns only
/// the items that have **not** been consumed yet, so `items().firstIndex(of: currentItem)` collapses
/// back to 0 after every track boundary. The current track is therefore resolved by *object identity*
/// against the full list of items this bridge queued, which survives consumption.
@objc final class IosAudioPlayerBridgeImpl: NSObject, IosAudioPlayerBridge {

    private var player: AVQueuePlayer?
    private var timeObserverToken: Any?
    private var statusObservations: [NSKeyValueObservation] = []

    /// Every track URL of the book, indexed by track index.
    private var trackUrls: [String] = []
    /// The items handed to the player for tracks `queueBaseIndex ..< queueBaseIndex + queuedItems.count`.
    /// Retained here even after AVQueuePlayer drops them from `items()`, so identity lookup keeps working.
    private var queuedItems: [AVPlayerItem] = []
    private var queueBaseIndex: Int = 0
    /// Last index we successfully resolved — used once the queue is exhausted and `currentItem` is nil.
    private var lastResolvedIndex: Int = 0

    // Callbacks stored as protocol objects (not closures) to avoid KotlinDouble/KotlinBoolean boxing.
    private var positionCallback: (any IosPositionCallback)?
    private var playingCallback: (any IosPlayingCallback)?
    private var remoteCommandCallback: (any IosRemoteCommandCallback)?

    private var isDisposed = false
    private var pendingRate: Float = 1.0
    private var endOfBookCallback: (any IosEndOfBookCallback)?
    // Tracks the most recently requested cover URL so stale fetch completions are discarded.
    private var currentArtworkUrl: String?

    // MARK: - IosAudioPlayerBridge

    func preparePlayer(
        trackUrls: [String],
        startTrackIndex: Int32,
        startOffsetSec: Double
    ) {
        guard !isDisposed else { return }

        self.trackUrls = trackUrls
        configureAudioSession()

        let queuePlayer = AVQueuePlayer()
        queuePlayer.actionAtItemEnd = .advance
        self.player = queuePlayer

        // Observe isPlaying changes via rate
        let rateObs = queuePlayer.observe(\.rate, options: [.new]) { [weak self] player, _ in
            guard let self, !self.isDisposed else { return }
            let playing = player.rate > 0
            DispatchQueue.main.async { self.playingCallback?.onPlaying(isPlaying: playing) }
        }
        statusObservations.append(rateObs)

        // Periodic position updates
        let interval = CMTime(seconds: 0.5, preferredTimescale: CMTimeScale(NSEC_PER_SEC))
        timeObserverToken = queuePlayer.addPeriodicTimeObserver(
            forInterval: interval,
            queue: .main
        ) { [weak self] _ in
            guard let self, !self.isDisposed else { return }
            self.emitPosition()
        }

        // Queue from the resume track rather than from track 0 + seek: AVQueuePlayer would
        // otherwise buffer and briefly play the first track before the seek lands.
        loadQueue(fromIndex: Int(startTrackIndex), offsetSec: startOffsetSec, resumePlaying: false)
    }

    @objc private func lastItemDidFinish(_ notification: Notification) {
        guard !isDisposed else { return }
        endOfBookCallback?.onEndOfBook()
    }

    func play() {
        guard !isDisposed else { return }
        player?.play()
        // Apply saved rate after play() since setting rate while paused restarts playback.
        if pendingRate != 1.0 {
            player?.rate = pendingRate
        }
    }

    func pause() {
        guard !isDisposed else { return }
        player?.pause()
    }

    func seekToTrack(trackIndex: Int32, offsetSec: Double) {
        guard let player, !isDisposed else { return }
        let target = Int(trackIndex)
        guard trackUrls.indices.contains(target) else { return }
        let offset = max(offsetSec, 0)

        if target == currentTrackIndexInt() {
            let cmTime = CMTime(seconds: offset, preferredTimescale: CMTimeScale(NSEC_PER_SEC))
            player.seek(to: cmTime, toleranceBefore: .zero, toleranceAfter: .zero) { [weak self] _ in
                self?.emitPosition()
            }
            return
        }

        // Cross-track seek, in EITHER direction. AVQueuePlayer cannot rewind into an item it has
        // already consumed and `advanceToNextItem()` always starts the next item at 0, so the only
        // correct move is to rebuild the queue from the target track and seek within it.
        loadQueue(fromIndex: target, offsetSec: offset, resumePlaying: player.rate > 0)
    }

    func setSpeed(speed: Float) {
        let rate = speed > 0 ? speed : 1.0
        pendingRate = rate
        // Only apply immediately when already playing; when paused, setting rate starts playback.
        if (player?.rate ?? 0) > 0 {
            player?.rate = rate
        }
    }

    func currentTrackIndex() -> Int32 {
        Int32(currentTrackIndexInt())
    }

    func currentTrackOffsetSec() -> Double {
        guard let player, player.currentItem != nil else { return 0 }
        let seconds = player.currentTime().seconds
        guard seconds.isFinite else { return 0 }
        return max(seconds, 0)
    }

    func currentTrackBufferedSec() -> Double {
        guard let item = player?.currentItem, let range = item.loadedTimeRanges.last?.timeRangeValue else { return 0 }
        let end = CMTimeGetSeconds(CMTimeAdd(range.start, range.duration))
        let now = currentTrackOffsetSec()
        guard end.isFinite else { return 0 }
        return max(end - now, 0)
    }

    func isPlaying() -> Bool {
        return (player?.rate ?? 0) > 0
    }

    func replaceTracksFrom(fromIndex: Int32, trackUrls newUrls: [String]) {
        guard let player, !isDisposed else { return }
        let from = Int(fromIndex)
        let current = currentTrackIndexInt()
        // Never touch the track under the playhead — replacing it would restart it audibly.
        guard from > current, !newUrls.isEmpty else { return }
        // `from == trackUrls.count` is an append; beyond that there is nothing to splice onto and
        // `replaceSubrange` would trap.
        guard from <= trackUrls.count else { return }
        guard let currentItem = player.currentItem else { return }

        // Splice the new URLs into the master list so later index lookups stay correct.
        trackUrls.replaceSubrange(from..<trackUrls.count, with: newUrls)

        for queued in player.items().dropFirst() {
            player.remove(queued)
        }
        // `queuedItems` must stay CONTIGUOUS from `queueBaseIndex`, because that is the only thing
        // `currentTrackIndexInt()` knows: it reads `queueBaseIndex + <slot>`. So the rebuild covers
        // every track from the playhead onward, not just the ones being replaced. Rebuilding only
        // `from...` while leaving the base at `current` claimed that slot 1 was track `current + 1`
        // when it actually held track `from` — so for any `from > current + 1` every position
        // reported after the next boundary was short by `from - current - 1` tracks, and the
        // untouched tracks in between were dropped from the queue and silently skipped.
        var rebuilt: [AVPlayerItem] = [currentItem]
        for index in (current + 1)..<trackUrls.count {
            // Stop rather than skip: a hole would shift every later slot and corrupt the index.
            guard let item = makeItem(at: index) else { break }
            player.insert(item, after: nil)
            rebuilt.append(item)
        }
        queuedItems = rebuilt
        queueBaseIndex = current
        registerEndOfBookObserver(on: rebuilt.last)
    }

    func setPositionCallback(callback: (any IosPositionCallback)?) {
        positionCallback = callback
    }

    func setPlayingCallback(callback: (any IosPlayingCallback)?) {
        playingCallback = callback
    }

    func setRemoteCommandCallback(callback: (any IosRemoteCommandCallback)?) {
        remoteCommandCallback = callback
    }

    func setEndOfBookCallback(callback: (any IosEndOfBookCallback)?) {
        endOfBookCallback = callback
    }

    func setNowPlayingInfo(
        title: String,
        author: String,
        durationSec: Double,
        positionSec: Double,
        coverUrl: String?
    ) {
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: title,
            MPMediaItemPropertyArtist: author,
            MPMediaItemPropertyPlaybackDuration: durationSec,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: positionSec,
            MPNowPlayingInfoPropertyPlaybackRate: Double(player?.rate ?? 1),
        ]
        // Preserve artwork already fetched for this session; a chapter-change refresh must not
        // blank the lock-screen cover while the (unchanged) image is re-fetched.
        if let existing = MPNowPlayingInfoCenter.default().nowPlayingInfo?[MPMediaItemPropertyArtwork],
           currentArtworkUrl == coverUrl {
            info[MPMediaItemPropertyArtwork] = existing
        }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info

        guard let urlStr = coverUrl, let url = URL(string: urlStr), currentArtworkUrl != urlStr else { return }
        currentArtworkUrl = urlStr
        let requestedUrl = urlStr
        URLSession.shared.dataTask(with: url) { [weak self] data, _, _ in
            guard let self, self.currentArtworkUrl == requestedUrl else { return }
            guard let data, let uiImage = UIImage(data: data) else { return }
            let artwork = MPMediaItemArtwork(boundsSize: uiImage.size) { _ in uiImage }
            DispatchQueue.main.async { [weak self] in
                guard let self, self.currentArtworkUrl == requestedUrl else { return }
                var updated = MPNowPlayingInfoCenter.default().nowPlayingInfo ?? [:]
                updated[MPMediaItemPropertyArtwork] = artwork
                MPNowPlayingInfoCenter.default().nowPlayingInfo = updated
            }
        }.resume()
    }

    func dispose() {
        guard !isDisposed else { return }
        isDisposed = true

        if let token = timeObserverToken {
            player?.removeTimeObserver(token)
            timeObserverToken = nil
        }
        statusObservations.forEach { $0.invalidate() }
        statusObservations.removeAll()

        NotificationCenter.default.removeObserver(self, name: .AVPlayerItemDidPlayToEndTime, object: nil)
        removeRemoteCommands()

        player?.pause()
        player?.removeAllItems()
        player = nil
        queuedItems = []
        trackUrls = []

        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil

        currentArtworkUrl = nil
        positionCallback = nil
        playingCallback = nil
        endOfBookCallback = nil
        remoteCommandCallback = nil
    }

    // MARK: - Simulate helpers for tests

    @objc func simulatePositionUpdate(_ trackIndex: Int, _ offsetSec: Double) {
        positionCallback?.onPosition(trackIndex: Int32(trackIndex), offsetSec: offsetSec)
    }

    @objc func simulatePlayingChanged(_ playing: Bool) {
        playingCallback?.onPlaying(isPlaying: playing)
    }

    /// Test seam: how many items AVQueuePlayer still holds. Used to pin the queue-consumption
    /// semantics this bridge's index tracking is built around.
    @objc var remainingQueuedItemCount: Int { player?.items().count ?? 0 }

    /// Test seam: the track index this bridge believes each queue slot holds. Must always be a
    /// contiguous run starting at the current track — that invariant is the whole basis of
    /// `currentTrackIndexInt()`, and `replaceTracksFrom` used to break it.
    @objc var queuedTrackIndices: [Int] { queuedItems.indices.map { queueBaseIndex + $0 } }

    /// Test seam: the master track list, after any `replaceTracksFrom` splices.
    @objc var currentTrackUrls: [String] { trackUrls }

    // MARK: - Private helpers

    /// Resolves the current track by *object identity* against the items this bridge queued.
    /// `player.items()` is deliberately not consulted: it drops consumed items, so a membership
    /// lookup reports 0 again after every track boundary.
    private func currentTrackIndexInt() -> Int {
        guard let current = player?.currentItem,
              let offset = queuedItems.firstIndex(where: { $0 === current }) else { return lastResolvedIndex }
        lastResolvedIndex = queueBaseIndex + offset
        return lastResolvedIndex
    }

    private func emitPosition() {
        positionCallback?.onPosition(
            trackIndex: Int32(currentTrackIndexInt()),
            offsetSec: currentTrackOffsetSec()
        )
    }

    private func makeItem(at index: Int) -> AVPlayerItem? {
        guard trackUrls.indices.contains(index), let url = URL(string: trackUrls[index]) else { return nil }
        return AVPlayerItem(asset: AVURLAsset(url: url))
    }

    /// (Re)builds the queue so it starts at `index`, then seeks to `offsetSec` inside it.
    private func loadQueue(fromIndex index: Int, offsetSec: Double, resumePlaying: Bool) {
        guard let player else { return }
        let start = min(max(index, 0), max(trackUrls.count - 1, 0))
        var items: [AVPlayerItem] = []
        for idx in start..<trackUrls.count {
            // Stop rather than skip — see replaceTracksFrom: a hole breaks the contiguity that
            // `currentTrackIndexInt()` relies on.
            guard let item = makeItem(at: idx) else { break }
            items.append(item)
        }
        guard !items.isEmpty else { return }

        player.pause()
        player.removeAllItems()
        for item in items { player.insert(item, after: nil) }
        queuedItems = items
        queueBaseIndex = start
        lastResolvedIndex = start
        registerEndOfBookObserver(on: items.last)

        let cmTime = CMTime(seconds: max(offsetSec, 0), preferredTimescale: CMTimeScale(NSEC_PER_SEC))
        player.seek(to: cmTime, toleranceBefore: .zero, toleranceAfter: .zero) { [weak self] _ in
            guard let self, !self.isDisposed else { return }
            self.emitPosition()
        }
        if resumePlaying {
            player.play()
            if pendingRate != 1.0 { player.rate = pendingRate }
        }
    }

    private func registerEndOfBookObserver(on item: AVPlayerItem?) {
        NotificationCenter.default.removeObserver(self, name: .AVPlayerItemDidPlayToEndTime, object: nil)
        guard let item else { return }
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(lastItemDidFinish(_:)),
            name: .AVPlayerItemDidPlayToEndTime,
            object: item
        )
    }

    private func configureAudioSession() {
        do {
            try AVAudioSession.sharedInstance().setCategory(
                .playback,
                mode: .spokenAudio,
                options: []
            )
            try AVAudioSession.sharedInstance().setActive(true)
        } catch {
            // Non-fatal: playback still works without background audio
        }
        // Registered regardless of whether the session activated — the lock-screen transport
        // controls are useful even when AVAudioSession configuration failed.
        setupRemoteCommands()
    }

    private func setupRemoteCommands() {
        let center = MPRemoteCommandCenter.shared()
        center.playCommand.addTarget { [weak self] _ in
            self?.play()
            return .success
        }
        center.pauseCommand.addTarget { [weak self] _ in
            self?.pause()
            return .success
        }
        center.changePlaybackPositionCommand.addTarget { [weak self] event in
            guard let event = event as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            // The Now Playing scrubber runs over the *book's* duration, so this is a book-absolute
            // position. Only the Kotlin controller knows the track offsets that resolve it.
            self?.remoteCommandCallback?.onSeekAbsolute(positionSec: event.positionTime)
            return .success
        }
        center.skipForwardCommand.preferredIntervals = [30]
        center.skipForwardCommand.addTarget { [weak self] event in
            guard let self, let event = event as? MPSkipIntervalCommandEvent else { return .commandFailed }
            self.remoteCommandCallback?.onSkip(deltaSec: event.interval)
            return .success
        }
        center.skipBackwardCommand.preferredIntervals = [15]
        center.skipBackwardCommand.addTarget { [weak self] event in
            guard let self, let event = event as? MPSkipIntervalCommandEvent else { return .commandFailed }
            self.remoteCommandCallback?.onSkip(deltaSec: -event.interval)
            return .success
        }
        // Track skip: the lock screen and CarPlay show these for multi-track content, and without
        // a registered target the buttons are dead.
        center.nextTrackCommand.isEnabled = true
        center.nextTrackCommand.addTarget { [weak self] _ in
            self?.remoteCommandCallback?.onTrackDelta(delta: 1)
            return .success
        }
        center.previousTrackCommand.isEnabled = true
        center.previousTrackCommand.addTarget { [weak self] _ in
            self?.remoteCommandCallback?.onTrackDelta(delta: -1)
            return .success
        }
    }

    private func removeRemoteCommands() {
        let center = MPRemoteCommandCenter.shared()
        center.playCommand.removeTarget(nil)
        center.pauseCommand.removeTarget(nil)
        center.changePlaybackPositionCommand.removeTarget(nil)
        center.skipForwardCommand.removeTarget(nil)
        center.skipBackwardCommand.removeTarget(nil)
        center.nextTrackCommand.removeTarget(nil)
        center.previousTrackCommand.removeTarget(nil)
    }
}

// MARK: - Factory

@objc final class IosAudioPlayerBridgeFactoryImpl: NSObject, IosAudioPlayerBridgeFactory {
    func create() -> any IosAudioPlayerBridge {
        IosAudioPlayerBridgeImpl()
    }
}
