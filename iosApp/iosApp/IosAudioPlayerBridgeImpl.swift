import AVFoundation
import MediaPlayer
import Riffle

/// Swift implementation of IosAudioPlayerBridge, backed by AVQueuePlayer.
/// One instance per audiobook player open — created by IosAudioPlayerBridgeFactoryImpl.
@objc final class IosAudioPlayerBridgeImpl: NSObject, IosAudioPlayerBridge {

    private var player: AVQueuePlayer?
    private var timeObserverToken: Any?
    private var statusObservations: [NSKeyValueObservation] = []

    private var trackStartOffsets: [Double] = []
    private var totalDurationSec: Double = 0
    private var positionCallback: ((Double) -> Void)?
    private var playingCallback: ((Bool) -> Void)?

    private var isDisposed = false

    // MARK: - IosAudioPlayerBridge

    func preparePlayer(
        trackUrls: [String],
        trackStartOffsetsSec: [Double],
        startAtSec: Double,
        totalDurationSec: Double
    ) {
        guard !isDisposed else { return }
        self.trackStartOffsets = trackStartOffsetsSec
        self.totalDurationSec = totalDurationSec

        configureAudioSession()

        let items = trackUrls.compactMap { urlStr -> AVPlayerItem? in
            guard let url = URL(string: urlStr) else { return nil }
            let asset = AVURLAsset(url: url)
            return AVPlayerItem(asset: asset)
        }

        let queuePlayer = AVQueuePlayer(items: items)
        queuePlayer.actionAtItemEnd = .advance
        self.player = queuePlayer

        // Observe isPlaying changes via rate
        let rateObs = queuePlayer.observe(\.rate, options: [.new]) { [weak self] player, _ in
            guard let self, !self.isDisposed else { return }
            let playing = player.rate > 0
            DispatchQueue.main.async { self.playingCallback?(playing) }
        }
        statusObservations.append(rateObs)

        // Periodic position updates
        let interval = CMTime(seconds: 0.5, preferredTimescale: CMTimeScale(NSEC_PER_SEC))
        timeObserverToken = queuePlayer.addPeriodicTimeObserver(
            forInterval: interval,
            queue: .main
        ) { [weak self] _ in
            guard let self, !self.isDisposed else { return }
            self.positionCallback?(self.currentPositionSec())
        }

        // Seek to start position
        if startAtSec > 0 {
            seekTo(positionSec: startAtSec)
        }
    }

    func play() {
        guard !isDisposed else { return }
        player?.play()
    }

    func pause() {
        guard !isDisposed else { return }
        player?.pause()
    }

    func seekTo(positionSec: Double) {
        guard let player, !isDisposed else { return }
        guard !trackStartOffsets.isEmpty else { return }

        // Find the track that covers positionSec
        var targetTrackIndex = 0
        for (i, offset) in trackStartOffsets.enumerated() {
            if offset <= positionSec { targetTrackIndex = i }
        }

        let targetOffset = trackStartOffsets[targetTrackIndex]
        let inTrackSec = (positionSec - targetOffset).clamped(to: 0...Double.greatestFiniteMagnitude)

        // If the current item is already the correct track, seek directly
        let items = player.items()
        let currentIndex = currentTrackIndex()

        if currentIndex == targetTrackIndex {
            let cmTime = CMTime(seconds: inTrackSec, preferredTimescale: CMTimeScale(NSEC_PER_SEC))
            player.seek(to: cmTime, toleranceBefore: .zero, toleranceAfter: .zero)
        } else if targetTrackIndex > currentIndex, targetTrackIndex < items.count {
            // Advance to target track then seek
            let targetItem = items[targetTrackIndex - currentIndex]
            let cmTime = CMTime(seconds: inTrackSec, preferredTimescale: CMTimeScale(NSEC_PER_SEC))
            player.seek(to: cmTime, toleranceBefore: .zero, toleranceAfter: .zero) { [weak self] _ in
                guard let self else { return }
                // Advance queue to target item
                for _ in 0..<(targetTrackIndex - currentIndex) {
                    self.player?.advanceToNextItem()
                }
            }
        }
    }

    func setSpeed(speed: Float) {
        player?.rate = speed > 0 ? speed : 1.0
    }

    func currentPositionSec() -> Double {
        guard let player else { return 0 }
        let trackIdx = currentTrackIndex()
        let trackOffset = trackStartOffsets[safe: trackIdx] ?? 0
        let inTrackSec = player.currentTime().seconds
        let pos = trackOffset + max(inTrackSec, 0)
        return pos.isNaN || pos.isInfinite ? 0 : pos
    }

    func isPlaying() -> Bool {
        return (player?.rate ?? 0) > 0
    }

    func setPositionCallback(callback: ((Double) -> Void)?) {
        positionCallback = callback
    }

    func setPlayingCallback(callback: ((Bool) -> Void)?) {
        playingCallback = callback
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
        // Artwork loading is asynchronous; for v1 we use a plain text placeholder
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
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

        player?.pause()
        player = nil

        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil

        positionCallback = nil
        playingCallback = nil
    }

    // MARK: - Simulate helpers for tests

    @objc func simulatePositionUpdate(_ positionSec: Double) {
        positionCallback?(positionSec)
    }

    @objc func simulatePlayingChanged(_ playing: Bool) {
        playingCallback?(playing)
    }

    // MARK: - Private helpers

    private func currentTrackIndex() -> Int {
        guard let player else { return 0 }
        let items = player.items()
        guard let currentItem = player.currentItem,
              let idx = items.firstIndex(of: currentItem) else { return 0 }
        return idx
    }

    private func configureAudioSession() {
        do {
            try AVAudioSession.sharedInstance().setCategory(
                .playback,
                mode: .spokenAudio,
                options: []
            )
            try AVAudioSession.sharedInstance().setActive(true)
            setupRemoteCommands()
        } catch {
            // Non-fatal: playback still works without background audio
        }
    }

    private func setupRemoteCommands() {
        let center = MPRemoteCommandCenter.shared()
        center.playCommand.addTarget { [weak self] _ in
            self?.player?.play()
            return .success
        }
        center.pauseCommand.addTarget { [weak self] _ in
            self?.player?.pause()
            return .success
        }
        center.changePlaybackPositionCommand.addTarget { [weak self] event in
            guard let event = event as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            self?.seekTo(positionSec: event.positionTime)
            return .success
        }
    }
}

// MARK: - Factory

@objc final class IosAudioPlayerBridgeFactoryImpl: NSObject, IosAudioPlayerBridgeFactory {
    func create() -> any IosAudioPlayerBridge {
        IosAudioPlayerBridgeImpl()
    }
}

// MARK: - Safe subscript helper

private extension Array {
    subscript(safe index: Int) -> Element? {
        indices.contains(index) ? self[index] : nil
    }
}

private extension Double {
    func clamped(to range: ClosedRange<Double>) -> Double {
        min(max(self, range.lowerBound), range.upperBound)
    }
}
