import AVFoundation
import Riffle

/// Swift implementation of IosReadaloudBridge, backed by AVQueuePlayer.
///
/// One instance per reader open — created by IosReadaloudBridgeFactoryImpl.
///
/// Unlike the audiobook bridge there are no lock-screen (Now Playing / MPRemoteCommandCenter)
/// controls: Readaloud is an in-app-only feature; the OS audio controls are reserved for the
/// audiobook player. The bridge still configures AVAudioSession so playback uses the `.playback`
/// category (headphone un-plug pauses, silent-mode is respected) and handles interruptions
/// (phone calls pause narration).
///
/// Index tracking uses the same object-identity trick as IosAudioPlayerBridgeImpl: AVQueuePlayer
/// drops consumed items from its `items()` array, so we retain every item we ever queued and
/// resolve the current track by object identity against that retained list.
@objc final class IosReadaloudBridgeImpl: NSObject, IosReadaloudBridge {

    private var player: AVQueuePlayer?
    private var timeObserverToken: Any?
    private var statusObservations: [NSKeyValueObservation] = []

    private var audioFileUrls: [String] = []
    private var queuedItems: [AVPlayerItem] = []
    private var queueBaseIndex: Int = 0
    private var lastResolvedIndex: Int = 0

    private var positionCallback: (any IosReadaloudPositionCallback)?
    private var playingCallback: (any IosReadaloudPlayingCallback)?

    private var isDisposed = false
    private var pendingRate: Float = 1.0

    // MARK: - IosReadaloudBridge

    func prepareAudioSrcs(
        audioFileUrls: [String],
        startSrcIndex: Int32,
        startOffsetSec: Double
    ) {
        guard !isDisposed else { return }

        self.audioFileUrls = audioFileUrls
        configureAudioSession()

        let queuePlayer = AVQueuePlayer()
        queuePlayer.actionAtItemEnd = .advance
        self.player = queuePlayer

        let rateObs = queuePlayer.observe(\.rate, options: [.new]) { [weak self] player, _ in
            guard let self, !self.isDisposed else { return }
            let playing = player.rate > 0
            DispatchQueue.main.async { self.playingCallback?.onPlaying(isPlaying: playing) }
        }
        statusObservations.append(rateObs)

        let interval = CMTime(seconds: 0.25, preferredTimescale: CMTimeScale(NSEC_PER_SEC))
        timeObserverToken = queuePlayer.addPeriodicTimeObserver(
            forInterval: interval,
            queue: .main
        ) { [weak self] _ in
            guard let self, !self.isDisposed else { return }
            self.emitPosition()
        }

        loadQueue(fromIndex: Int(startSrcIndex), offsetSec: max(startOffsetSec, 0), resumePlaying: false)
    }

    func play() {
        guard !isDisposed else { return }
        player?.play()
        if pendingRate != 1.0 { player?.rate = pendingRate }
    }

    func pause() {
        guard !isDisposed else { return }
        player?.pause()
    }

    func seekToSrc(srcIndex: Int32, offsetSec: Double) {
        guard let player, !isDisposed else { return }
        let target = Int(srcIndex)
        guard audioFileUrls.indices.contains(target) else { return }
        let offset = max(offsetSec, 0)

        if target == currentSrcIndexInt() {
            let cmTime = CMTime(seconds: offset, preferredTimescale: CMTimeScale(NSEC_PER_SEC))
            player.seek(to: cmTime, toleranceBefore: .zero, toleranceAfter: .zero) { [weak self] _ in
                self?.emitPosition()
            }
            return
        }

        loadQueue(fromIndex: target, offsetSec: offset, resumePlaying: player.rate > 0)
    }

    func setSpeed(speed: Float) {
        let rate = speed > 0 ? speed : 1.0
        pendingRate = rate
        if (player?.rate ?? 0) > 0 {
            player?.rate = rate
        }
    }

    func currentSrcIndex() -> Int32 {
        Int32(currentSrcIndexInt())
    }

    func currentOffsetSec() -> Double {
        guard let player, player.currentItem != nil else { return 0 }
        let seconds = player.currentTime().seconds
        guard seconds.isFinite else { return 0 }
        return max(seconds, 0)
    }

    func isPlaying() -> Bool {
        (player?.rate ?? 0) > 0
    }

    // swiftlint:disable:next identifier_name
    func setPositionCallback(callback_: (any IosReadaloudPositionCallback)?) {
        positionCallback = callback_
    }

    // swiftlint:disable:next identifier_name
    func setPlayingCallback(callback_: (any IosReadaloudPlayingCallback)?) {
        playingCallback = callback_
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

        NotificationCenter.default.removeObserver(self, name: AVAudioSession.interruptionNotification, object: nil)
        NotificationCenter.default.removeObserver(self, name: AVAudioSession.routeChangeNotification, object: nil)

        player?.pause()
        player?.removeAllItems()
        player = nil
        queuedItems = []
        audioFileUrls = []
        positionCallback = nil
        playingCallback = nil
    }

    // MARK: - Test seams

    @objc func simulatePositionUpdate(_ srcIndex: Int, _ offsetSec: Double) {
        positionCallback?.onPosition(srcIndex: Int32(srcIndex), offsetSec: offsetSec)
    }

    @objc func simulatePlayingChanged(_ playing: Bool) {
        playingCallback?.onPlaying(isPlaying: playing)
    }

    @objc var remainingQueuedItemCount: Int { player?.items().count ?? 0 }

    // MARK: - Private helpers

    private func currentSrcIndexInt() -> Int {
        guard let current = player?.currentItem,
              let offset = queuedItems.firstIndex(where: { $0 === current }) else { return lastResolvedIndex }
        lastResolvedIndex = queueBaseIndex + offset
        return lastResolvedIndex
    }

    private func emitPosition() {
        positionCallback?.onPosition(
            srcIndex: Int32(currentSrcIndexInt()),
            offsetSec: currentOffsetSec()
        )
    }

    private func makeItem(at index: Int) -> AVPlayerItem? {
        guard audioFileUrls.indices.contains(index), let url = URL(string: audioFileUrls[index]) else { return nil }
        return AVPlayerItem(asset: AVURLAsset(url: url))
    }

    private func loadQueue(fromIndex index: Int, offsetSec: Double, resumePlaying: Bool) {
        guard let player else { return }
        let start = min(max(index, 0), max(audioFileUrls.count - 1, 0))
        var items: [AVPlayerItem] = []
        for idx in start..<audioFileUrls.count {
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

        let cmTime = CMTime(seconds: max(offsetSec, 0), preferredTimescale: CMTimeScale(NSEC_PER_SEC))
        let shouldResume = resumePlaying
        player.seek(to: cmTime, toleranceBefore: .zero, toleranceAfter: .zero) { [weak self] _ in
            guard let self, !self.isDisposed else { return }
            self.emitPosition()
            if shouldResume {
                self.player?.play()
                if self.pendingRate != 1.0 { self.player?.rate = self.pendingRate }
            }
        }
    }

    private func configureAudioSession() {
        do {
            try AVAudioSession.sharedInstance().setCategory(
                .playback,
                mode: .spokenAudio,
                options: []
            )
            try AVAudioSession.sharedInstance().setActive(true)
        } catch {}
        // Remove any stale observers from a previous prepare before adding fresh ones.
        NotificationCenter.default.removeObserver(self, name: AVAudioSession.interruptionNotification, object: nil)
        NotificationCenter.default.removeObserver(self, name: AVAudioSession.routeChangeNotification, object: nil)
        setupAudioSessionObservers()
    }

    private func setupAudioSessionObservers() {
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleInterruption(_:)),
            name: AVAudioSession.interruptionNotification,
            object: AVAudioSession.sharedInstance()
        )
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleRouteChange(_:)),
            name: AVAudioSession.routeChangeNotification,
            object: AVAudioSession.sharedInstance()
        )
    }

    @objc private func handleInterruption(_ notification: Notification) {
        guard !isDisposed,
              let info = notification.userInfo,
              let typeValue = info[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: typeValue) else { return }
        switch type {
        case .began:
            player?.pause()
        case .ended:
            let optionsValue = info[AVAudioSessionInterruptionOptionKey] as? UInt ?? 0
            let options = AVAudioSession.InterruptionOptions(rawValue: optionsValue)
            if options.contains(.shouldResume) {
                player?.play()
                if pendingRate != 1.0 { player?.rate = pendingRate }
            }
        @unknown default:
            break
        }
    }

    @objc private func handleRouteChange(_ notification: Notification) {
        guard !isDisposed,
              let info = notification.userInfo,
              let reasonValue = info[AVAudioSessionRouteChangeReasonKey] as? UInt,
              let reason = AVAudioSession.RouteChangeReason(rawValue: reasonValue) else { return }
        if reason == .oldDeviceUnavailable { player?.pause() }
    }
}

// MARK: - Factory

@objc final class IosReadaloudBridgeFactoryImpl: NSObject, IosReadaloudBridgeFactory {
    func create() -> any IosReadaloudBridge {
        IosReadaloudBridgeImpl()
    }
}
