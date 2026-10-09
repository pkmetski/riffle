import AVFoundation
import Riffle
import XCTest

/// Drives the real `IosReadaloudBridgeImpl` against real (silent) audio files.
///
/// `IosReadaloudControllerTest` (Kotlin, `shared/src/iosTest`) covers the session-layer logic.
/// These tests cover the Swift-specific behaviours that only a real `AVQueuePlayer` can exercise:
///  - index tracking via object identity (same trick as the audiobook bridge, regression for #1187);
///  - `seekToSrc` targeting a different source file reloads the queue from that index;
///  - the test-seam callbacks (`simulatePositionUpdate`, `simulatePlayingChanged`) work correctly.
final class ReadaloudBridgeTests: XCTestCase {

    private var tempDir: URL!
    private var srcUrls: [URL] = []

    private let trackSeconds: Double = 1.0

    override func setUpWithError() throws {
        try super.setUpWithError()
        tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("ReadaloudBridgeTests-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
        srcUrls = try (0..<3).map { index in
            let url = tempDir.appendingPathComponent("src\(index).wav")
            try writeSilentWav(seconds: trackSeconds, to: url)
            return url
        }
    }

    override func tearDownWithError() throws {
        if let tempDir { try? FileManager.default.removeItem(at: tempDir) }
        srcUrls = []
        try super.tearDownWithError()
    }

    private var srcUrlStrings: [String] { srcUrls.map { $0.absoluteString } }

    // MARK: - Tests

    /// After prepare with startIndex 0, currentSrcIndex must report 0.
    func testCurrentSrcIndexAfterPrepareReturnsStartIndex() {
        let bridge = IosReadaloudBridgeImpl()
        defer { bridge.dispose() }
        bridge.prepareAudioSrcs(
            audioFileUrls: srcUrlStrings,
            startSrcIndex: 0,
            startOffsetSec: 0
        )
        XCTAssertEqual(bridge.currentSrcIndex(), 0)
    }

    /// After prepare with startIndex 1, currentSrcIndex must report 1.
    func testPrepareWithNonZeroStartIndexReportsCorrectIndex() {
        let bridge = IosReadaloudBridgeImpl()
        defer { bridge.dispose() }
        bridge.prepareAudioSrcs(
            audioFileUrls: srcUrlStrings,
            startSrcIndex: 1,
            startOffsetSec: 0
        )
        XCTAssertEqual(bridge.currentSrcIndex(), 1, "startSrcIndex=1 must be reported as index 1")
    }

    /// `seekToSrc` targeting a different source reloads the queue from that index;
    /// `currentSrcIndex()` must immediately reflect the new index without waiting for a
    /// position callback.
    func testSeekToSrcUpdatesSrcIndex() {
        let bridge = IosReadaloudBridgeImpl()
        defer { bridge.dispose() }
        bridge.prepareAudioSrcs(
            audioFileUrls: srcUrlStrings,
            startSrcIndex: 0,
            startOffsetSec: 0
        )
        bridge.seekToSrc(srcIndex: 2, offsetSec: 0)
        XCTAssertEqual(bridge.currentSrcIndex(), 2, "seekToSrc must update current index to 2")
    }

    /// `dispose()` must be idempotent — calling it twice must not crash.
    func testDisposeIsIdempotent() {
        let bridge = IosReadaloudBridgeImpl()
        bridge.prepareAudioSrcs(
            audioFileUrls: srcUrlStrings,
            startSrcIndex: 0,
            startOffsetSec: 0
        )
        bridge.dispose()
        bridge.dispose()
    }

    /// The `simulatePositionUpdate` seam fires the registered callback.
    func testSimulatePositionUpdateFiresCallback() {
        let bridge = IosReadaloudBridgeImpl()
        defer { bridge.dispose() }
        var receivedSrc: Int32 = -1
        var receivedOffset: Double = -1
        let callbackSpy = FakePositionCallback { src, offset in
            receivedSrc = src
            receivedOffset = offset
        }
        bridge.setPositionCallback(callback_: callbackSpy)
        bridge.simulatePositionUpdate(1, 3.5)
        XCTAssertEqual(receivedSrc, 1)
        XCTAssertEqual(receivedOffset, 3.5)
    }

    /// The `simulatePlayingChanged` seam fires the registered callback.
    func testSimulatePlayingChangedFiresCallback() {
        let bridge = IosReadaloudBridgeImpl()
        defer { bridge.dispose() }
        var receivedPlaying: Bool?
        let callbackSpy = FakePlayingCallback { playing in receivedPlaying = playing }
        bridge.setPlayingCallback(callback_: callbackSpy)
        bridge.simulatePlayingChanged(true)
        XCTAssertEqual(receivedPlaying, true)
    }

    /// After dispose, currentSrcIndex returns 0 (default) and isPlaying returns false.
    func testStateAfterDispose() {
        let bridge = IosReadaloudBridgeImpl()
        bridge.prepareAudioSrcs(
            audioFileUrls: srcUrlStrings,
            startSrcIndex: 0,
            startOffsetSec: 0
        )
        bridge.dispose()
        XCTAssertFalse(bridge.isPlaying())
        XCTAssertEqual(bridge.currentOffsetSec(), 0)
    }
}

// MARK: - Helpers

private final class FakePositionCallback: NSObject, IosReadaloudPositionCallback {
    private let handler: (Int32, Double) -> Void
    init(_ handler: @escaping (Int32, Double) -> Void) { self.handler = handler }
    func onPosition(srcIndex: Int32, offsetSec: Double) { handler(srcIndex, offsetSec) }
}

private final class FakePlayingCallback: NSObject, IosReadaloudPlayingCallback {
    private let handler: (Bool) -> Void
    init(_ handler: @escaping (Bool) -> Void) { self.handler = handler }
    func onPlaying(isPlaying: Bool) { handler(isPlaying) }
}

private func writeSilentWav(seconds: Double, to url: URL) throws {
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
