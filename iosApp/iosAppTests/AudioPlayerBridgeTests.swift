import AVFoundation
import Riffle
import XCTest

/// Drives the real `IosAudioPlayerBridgeImpl` against real (silent) audio files.
///
/// These are the assertions that cover the Swift half of issue #1071 §P0 2–3: the multi-track
/// position corruption and the dead backward cross-track seek. `IosAudioPlayerControllerTest`
/// (Kotlin, `shared/src/iosTest`) covers the projection onto the book timeline; nothing but a real
/// `AVQueuePlayer` can cover the queue-consumption semantics the index tracking is built around.
final class AudioPlayerBridgeTests: XCTestCase {

    private var tempDir: URL!
    private var trackUrls: [URL] = []

    // Short enough to keep the suite quick, long enough that a track boundary is unambiguous.
    private let trackSeconds: Double = 1.0

    override func setUpWithError() throws {
        try super.setUpWithError()
        tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("AudioPlayerBridgeTests-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
        trackUrls = try (0..<3).map { index in
            let url = tempDir.appendingPathComponent("track\(index).wav")
            try Self.writeSilentWav(seconds: trackSeconds, to: url)
            return url
        }
    }

    override func tearDownWithError() throws {
        if let tempDir { try? FileManager.default.removeItem(at: tempDir) }
        trackUrls = []
        try super.tearDownWithError()
    }

    private var trackUrlStrings: [String] { trackUrls.map { $0.absoluteString } }

    // MARK: - The premise the index tracking is built around

    /// Documents `AVQueuePlayer`'s queue semantics: `items()` returns only the items that have NOT
    /// been consumed, so the old `items().firstIndex(of: currentItem)` reported 0 again after every
    /// track boundary — which is exactly how the book-absolute position lost the earlier tracks.
    func testAVQueuePlayerDropsConsumedItemsSoQueueMembershipCannotIdentifyTheTrack() throws {
        let items = trackUrls.map { AVPlayerItem(asset: AVURLAsset(url: $0)) }
        let player = AVQueuePlayer(items: items)
        player.actionAtItemEnd = .advance
        XCTAssertEqual(player.items().count, 3, "queue starts with every track")

        player.play()
        let advanced = waitUntil { player.currentItem !== items[0] && player.currentItem != nil }
        XCTAssertTrue(advanced, "player never advanced past the first track")

        XCTAssertEqual(player.items().count, 2, "AVQueuePlayer removes each item as it finishes")
        XCTAssertEqual(
            player.items().firstIndex(of: player.currentItem!),
            0,
            "the second track sits at queue slot 0 — queue membership cannot be used as a track index"
        )
        player.pause()
    }

    // MARK: - §P0 2: multi-track position

    func testCurrentTrackIndexAdvancesWithPlaybackAcrossATrackBoundary() throws {
        let bridge = IosAudioPlayerBridgeImpl()
        defer { bridge.dispose() }
        bridge.preparePlayer(trackUrls: trackUrlStrings, startTrackIndex: 0, startOffsetSec: 0)
        XCTAssertEqual(bridge.currentTrackIndex(), 0)

        bridge.play()
        XCTAssertTrue(
            waitUntil { bridge.currentTrackIndex() == 1 },
            "after the first track finishes the bridge must report track 1, not fall back to 0 "
                + "(index was \(bridge.currentTrackIndex()))"
        )
        bridge.pause()
    }

    func testPositionCallbackReportsTheAdvancedTrackIndex() throws {
        let bridge = IosAudioPlayerBridgeImpl()
        defer { bridge.dispose() }
        let recorder = PositionRecorder()
        bridge.setPositionCallback(callback: recorder)
        bridge.preparePlayer(trackUrls: trackUrlStrings, startTrackIndex: 0, startOffsetSec: 0)
        bridge.play()

        XCTAssertTrue(
            waitUntil { recorder.maxTrackIndex >= 1 },
            "the periodic position callback must report the real track index; "
                + "highest seen was \(recorder.maxTrackIndex)"
        )
        bridge.pause()
    }

    // MARK: - §P0 3: cross-track seeking

    func testBackwardCrossTrackSeekRewindsIntoAnAlreadyConsumedTrack() throws {
        let bridge = IosAudioPlayerBridgeImpl()
        defer { bridge.dispose() }
        bridge.preparePlayer(trackUrls: trackUrlStrings, startTrackIndex: 2, startOffsetSec: 0.2)
        XCTAssertEqual(bridge.currentTrackIndex(), 2, "queue must start at the resume track")

        bridge.seekToTrack(trackIndex: 0, offsetSec: 0.4)

        XCTAssertTrue(
            waitUntil { bridge.currentTrackIndex() == 0 && bridge.currentTrackOffsetSec() > 0.3 },
            "a backward cross-track seek must land in track 0 (index=\(bridge.currentTrackIndex()), "
                + "offset=\(bridge.currentTrackOffsetSec()))"
        )
    }

    func testForwardCrossTrackSeekLandsAtTheRequestedOffsetNotAtTheTrackStart() throws {
        let bridge = IosAudioPlayerBridgeImpl()
        defer { bridge.dispose() }
        bridge.preparePlayer(trackUrls: trackUrlStrings, startTrackIndex: 0, startOffsetSec: 0)

        bridge.seekToTrack(trackIndex: 2, offsetSec: 0.5)

        XCTAssertTrue(
            waitUntil { bridge.currentTrackIndex() == 2 && bridge.currentTrackOffsetSec() > 0.4 },
            "a forward cross-track seek must carry the in-track offset — advanceToNextItem() would "
                + "start track 2 at 0 (index=\(bridge.currentTrackIndex()), "
                + "offset=\(bridge.currentTrackOffsetSec()))"
        )
    }

    func testSameTrackSeekStillWorks() throws {
        let bridge = IosAudioPlayerBridgeImpl()
        defer { bridge.dispose() }
        bridge.preparePlayer(trackUrls: trackUrlStrings, startTrackIndex: 1, startOffsetSec: 0)

        bridge.seekToTrack(trackIndex: 1, offsetSec: 0.6)

        XCTAssertTrue(
            waitUntil { bridge.currentTrackIndex() == 1 && bridge.currentTrackOffsetSec() > 0.5 },
            "in-track seek regressed (index=\(bridge.currentTrackIndex()), "
                + "offset=\(bridge.currentTrackOffsetSec()))"
        )
    }

    // MARK: - §17: swapTracksFromIndex is no longer a no-op

    func testReplaceTracksFromSwapsTheTailAndLeavesThePlayheadAlone() throws {
        let bridge = IosAudioPlayerBridgeImpl()
        defer { bridge.dispose() }
        bridge.preparePlayer(trackUrls: trackUrlStrings, startTrackIndex: 0, startOffsetSec: 0)
        XCTAssertEqual(bridge.remainingQueuedItemCount, 3)

        let replacement = [trackUrls[1].absoluteString]
        bridge.replaceTracksFrom(fromIndex: 1, trackUrls: replacement)

        XCTAssertEqual(bridge.currentTrackIndex(), 0, "the playing track must not be replaced")
        XCTAssertEqual(
            bridge.remainingQueuedItemCount,
            2,
            "the unplayed tail must be replaced by the new track list"
        )
    }

    func testReplaceTracksAtOrBeforeTheCurrentTrackIsIgnored() throws {
        let bridge = IosAudioPlayerBridgeImpl()
        defer { bridge.dispose() }
        bridge.preparePlayer(trackUrls: trackUrlStrings, startTrackIndex: 0, startOffsetSec: 0)

        bridge.replaceTracksFrom(fromIndex: 0, trackUrls: [trackUrls[2].absoluteString])

        XCTAssertEqual(bridge.remainingQueuedItemCount, 3, "swapping the current track would restart it")
    }

    // MARK: - Helpers

    private final class PositionRecorder: NSObject, IosPositionCallback {
        private(set) var maxTrackIndex: Int32 = -1
        private(set) var lastOffsetSec: Double = -1

        func onPosition(trackIndex: Int32, offsetSec: Double) {
            maxTrackIndex = max(maxTrackIndex, trackIndex)
            lastOffsetSec = offsetSec
        }
    }

    /// Spins the main run loop until `condition` holds (AVFoundation delivers its callbacks there).
    private func waitUntil(timeout: TimeInterval = 8, _ condition: () -> Bool) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if condition() { return true }
            RunLoop.current.run(until: Date().addingTimeInterval(0.05))
        }
        return condition()
    }

    /// 8 kHz mono 16-bit PCM silence — enough for AVURLAsset to treat it as a real, seekable track
    /// without shipping a binary fixture into the unit-test target (which has no resources phase).
    private static func writeSilentWav(seconds: Double, to url: URL) throws {
        let sampleRate = 8000
        let frameCount = Int(Double(sampleRate) * seconds)
        let dataSize = frameCount * 2
        var data = Data()
        func appendAscii(_ text: String) { data.append(text.data(using: .ascii)!) }
        func append32(_ value: UInt32) {
            var le = value.littleEndian
            withUnsafeBytes(of: &le) { data.append(contentsOf: $0) }
        }
        func append16(_ value: UInt16) {
            var le = value.littleEndian
            withUnsafeBytes(of: &le) { data.append(contentsOf: $0) }
        }
        appendAscii("RIFF")
        append32(UInt32(36 + dataSize))
        appendAscii("WAVE")
        appendAscii("fmt ")
        append32(16)
        append16(1)
        append16(1)
        append32(UInt32(sampleRate))
        append32(UInt32(sampleRate * 2))
        append16(2)
        append16(16)
        appendAscii("data")
        append32(UInt32(dataSize))
        data.append(Data(count: dataSize))
        try data.write(to: url)
    }
}
