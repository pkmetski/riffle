import Foundation
import Network
import UIKit
import XCTest

// In-process ABS HTTP stub backed by NWListener. Mirrors Android's StubAbsServer.kt so both
// platforms test against an identical canned catalogue.
final class StubAbsServer {

    // MARK: - Constants

    static let testUserId = "test-user-id"
    static let testToken = "test-token"
    static let testLibraryId = "lib-test-1"
    static let testLibraryName = "Test Library"
    static let testLibraryId2 = "lib-test-2"
    static let testLibraryName2 = "Test Library 2"
    static let testItemId = "item-test-1"
    static let testItemTitle = "Test EPUB"
    static let testItemAuthor = "Test Author"
    static let testFileIno = "ino-test-1"
    static let testSeriesId = "series-test-1"
    static let testSeriesName = "Test Series"
    static let testCollectionId = "collection-test-1"
    static let testCollectionName = "Test Collection"
    static let testStandaloneItemId = "item-test-2"
    static let testStandaloneItemTitle = "Test EPUB Standalone"
    static let testStandaloneFileIno = "ino-test-2"
    static let testPdfItemId = "item-test-3"
    static let testPdfItemTitle = "Test PDF"
    static let testPdfFileIno = "ino-test-3"
    static let testFootnoteItemId = "item-test-4"
    static let testFootnoteItemTitle = "Test Footnotes EPUB"
    static let testFootnoteFileIno = "ino-test-4"
    static let testAudioItemId = "item-audio-1"
    static let testAudioItemTitle = "Test Audiobook"
    static let testCbzItemId = "item-cbz-1"
    static let testCbzItemTitle = "Test CBZ"
    static let testCbzFileIno = "ino-cbz-1"
    static let testSessionId = "playback-session-1"

    // MARK: - State

    private var listener: NWListener?
    private let queue = DispatchQueue(label: "riffle.stub.abs", qos: .userInitiated)
    private(set) var port: UInt16 = 0

    var baseUrl: String { "http://127.0.0.1:\(port)" }

    // MARK: - Lifecycle

    func start() {
        let params = NWParameters.tcp
        params.requiredLocalEndpoint = NWEndpoint.hostPort(host: .ipv4(.loopback), port: 0)
        guard let newListener = try? NWListener(using: params) else {
            XCTFail("StubAbsServer: NWListener init failed"); return
        }
        listener = newListener
        let sem = DispatchSemaphore(value: 0)
        listener?.stateUpdateHandler = { [weak self] state in
            if case .ready = state {
                self?.port = self?.listener?.port?.rawValue ?? 0
                sem.signal()
            } else if case .failed(let err) = state {
                XCTFail("StubAbsServer failed to start: \(err)")
                sem.signal()
            }
        }
        listener?.newConnectionHandler = { [weak self] conn in self?.handle(conn) }
        listener?.start(queue: queue)
        sem.wait()
        XCTAssertNotEqual(port, 0, "StubAbsServer must bind to a non-zero port")
    }

    func shutdown() {
        listener?.cancel()
        listener = nil
    }

    // MARK: - Connection handling

    private func handle(_ conn: NWConnection) {
        conn.start(queue: queue)
        receive(conn, buffer: Data())
    }

    private func receive(_ conn: NWConnection, buffer: Data) {
        conn.receive(minimumIncompleteLength: 1, maximumLength: 65536) { [weak self] chunk, _, done, _ in
            guard let self else { return }
            var buf = buffer
            if let chunk { buf.append(chunk) }
            if let req = StubHTTPRequest.parse(buf) {
                let resp = self.dispatch(req)
                conn.send(content: resp, completion: .contentProcessed { _ in conn.cancel() })
            } else if !done {
                self.receive(conn, buffer: buf)
            } else {
                conn.cancel()
            }
        }
    }

    // MARK: - Dispatch

    // swiftlint:disable:next cyclomatic_complexity
    private func dispatch(_ req: StubHTTPRequest) -> Data {
        let lib = Self.testLibraryId
        let audioId = Self.testAudioItemId
        // ABS serves an ebook file under both `/api/items/{id}/ebook/{ino}` (used by the Android
        // client) and `/api/items/{id}/file/{ino}` (used by the iOS downloaders). Fold the latter
        // onto the former so both platforms hit one canned response; audio keeps its own route.
        var path = req.path
        if path.hasPrefix("/api/items/") && path.contains("/file/") && !path.hasSuffix("/audio.mp3") {
            path = path.replacingOccurrences(of: "/file/", with: "/ebook/")
        }

        if req.method == "POST" && path == "/login" { return loginResponse() }
        if req.method == "GET" && path == "/api/libraries" { return librariesResponse() }
        if req.method == "GET" && path == "/api/libraries/\(lib)/items" { return libraryItemsResponse() }
        if req.method == "GET" && path == "/api/libraries/\(Self.testLibraryId2)/items" { return libraryItemsResponse() }
        if req.method == "GET" && path.hasPrefix("/api/libraries/\(lib)/series") { return seriesResponse() }
        if req.method == "GET" && path.hasPrefix("/api/libraries/\(Self.testLibraryId2)/series") { return json(200, #"{"results":[]}"#) }
        if req.method == "GET" && path.hasPrefix("/api/libraries/\(lib)/collections") { return collectionsResponse() }
        if req.method == "GET" && path.hasPrefix("/api/libraries/\(Self.testLibraryId2)/collections") { return json(200, #"{"results":[]}"#) }
        if req.method == "GET" && path.hasPrefix("/api/libraries/\(lib)/playlists") { return json(200, #"{"results":[]}"#) }
        if req.method == "GET" && path.hasPrefix("/api/libraries/\(Self.testLibraryId2)/playlists") { return json(200, #"{"results":[]}"#) }
        if req.method == "GET" && path == "/api/items/\(Self.testItemId)" { return itemResponse(Self.testItemId, Self.testFileIno) }
        if req.method == "GET" && path == "/api/items/\(Self.testItemId)/ebook/\(Self.testFileIno)" { return epubResponse("test.epub") }
        if req.method == "GET" && path == "/api/items/\(Self.testStandaloneItemId)" { return itemResponse(Self.testStandaloneItemId, Self.testStandaloneFileIno) }
        if req.method == "GET" && path == "/api/items/\(Self.testStandaloneItemId)/ebook/\(Self.testStandaloneFileIno)" { return epubResponse("test.epub") }
        if req.method == "GET" && path == "/api/items/\(Self.testPdfItemId)" { return itemResponse(Self.testPdfItemId, Self.testPdfFileIno) }
        if req.method == "GET" && path == "/api/items/\(Self.testPdfItemId)/ebook/\(Self.testPdfFileIno)" { return pdfResponse() }
        if req.method == "GET" && path == "/api/items/\(Self.testFootnoteItemId)" { return itemResponse(Self.testFootnoteItemId, Self.testFootnoteFileIno) }
        if req.method == "GET" && path == "/api/items/\(Self.testFootnoteItemId)/ebook/\(Self.testFootnoteFileIno)" { return epubResponse("test-footnotes.epub") }
        if req.method == "GET" && path == "/api/items/\(Self.testCbzItemId)" { return cbzItemResponse() }
        if req.method == "GET" && path == "/api/items/\(Self.testCbzItemId)/ebook/\(Self.testCbzFileIno)" { return cbzFileResponse() }
        if req.method == "GET" && path == "/api/items/\(audioId)" { return audioItemResponse() }
        if req.method == "POST" && path == "/api/items/\(audioId)/play" { return audioPlayResponse() }
        if req.method == "GET" && path == "/api/items/\(audioId)/file/audio.mp3" { return mp3Response() }
        if req.method == "GET" && path == "/api/me" { return json(200, #"{"mediaProgress":[]}"#) }
        if req.method == "GET" && path.hasPrefix("/api/me/progress/") { return json(200, #"{"ebookLocation":"","ebookProgress":0.0,"lastUpdate":-1}"#) }
        if req.method == "PATCH" && path.hasPrefix("/api/me/progress/") { return json(200, "{}") }
        if req.method == "POST" && path.hasPrefix("/api/session/") { return json(200, "{}") }
        if req.method == "GET" && path == "/status" { return json(200, #"{"serverVersion":"1.0.0"}"#) }
        return StubHTTPResponse(status: 404, contentType: "text/plain", body: Data("Not Found".utf8)).toData()
    }

    // MARK: - Response builders
    // swiftlint:disable line_length

    private func loginResponse() -> Data {
        json(200, """
        {"user":{"id":"\(Self.testUserId)","username":"testuser","token":"\(Self.testToken)"}}
        """)
    }

    private func librariesResponse() -> Data {
        json(200, """
        {"libraries":[
          {"id":"\(Self.testLibraryId)","name":"\(Self.testLibraryName)","mediaType":"book","settings":{"audiobooksOnly":false}},
          {"id":"\(Self.testLibraryId2)","name":"\(Self.testLibraryName2)","mediaType":"book","settings":{"audiobooksOnly":false}}
        ]}
        """)
    }

    private func libraryItemsResponse() -> Data {
        let lib = Self.testLibraryId
        return json(200, """
        {"results":[
          {"id":"\(Self.testItemId)","libraryId":"\(lib)","media":{"metadata":{"title":"\(Self.testItemTitle)","authorName":"\(Self.testItemAuthor)","genres":null},"ebookFormat":"epub","ebookFile":{"ino":"\(Self.testFileIno)"},"numAudioFiles":0},"userMediaProgress":null},
          {"id":"\(Self.testStandaloneItemId)","libraryId":"\(lib)","media":{"metadata":{"title":"\(Self.testStandaloneItemTitle)","authorName":"\(Self.testItemAuthor)","genres":null},"ebookFormat":"epub","ebookFile":{"ino":"\(Self.testStandaloneFileIno)"},"numAudioFiles":0},"userMediaProgress":null},
          {"id":"\(Self.testPdfItemId)","libraryId":"\(lib)","media":{"metadata":{"title":"\(Self.testPdfItemTitle)","authorName":"\(Self.testItemAuthor)","genres":null},"ebookFormat":"pdf","ebookFile":{"ino":"\(Self.testPdfFileIno)"},"numAudioFiles":0},"userMediaProgress":null},
          {"id":"\(Self.testFootnoteItemId)","libraryId":"\(lib)","media":{"metadata":{"title":"\(Self.testFootnoteItemTitle)","authorName":"\(Self.testItemAuthor)","genres":null},"ebookFormat":"epub","ebookFile":{"ino":"\(Self.testFootnoteFileIno)"},"numAudioFiles":0},"userMediaProgress":null},
          {"id":"\(Self.testAudioItemId)","libraryId":"\(lib)","media":{"metadata":{"title":"\(Self.testAudioItemTitle)","authorName":"\(Self.testItemAuthor)","genres":null},"ebookFormat":null,"ebookFile":null,"numAudioFiles":1},"userMediaProgress":null},
          {"id":"\(Self.testCbzItemId)","libraryId":"\(lib)","media":{"metadata":{"title":"\(Self.testCbzItemTitle)","authorName":"\(Self.testItemAuthor)","genres":null},"ebookFormat":"cbz","ebookFile":{"ino":"\(Self.testCbzFileIno)"},"numAudioFiles":0},"userMediaProgress":null}
        ]}
        """)
    }

    private func itemResponse(_ id: String, _ ino: String) -> Data {
        json(200, #"{"id":"\#(id)","media":{"ebookFile":{"ino":"\#(ino)"}}}"#)
    }

    private func audioItemResponse() -> Data {
        json(200, #"{"id":"\#(Self.testAudioItemId)","media":{"numAudioFiles":1,"audioFiles":[{"index":0,"ino":"audio-ino-1","duration":10.0,"mimeType":"audio/mpeg","metadata":{"filename":"audio.mp3"}}]}}"#)
    }

    private func audioPlayResponse() -> Data {
        let trackUrl = "/api/items/\(Self.testAudioItemId)/file/audio.mp3"
        return json(200, """
        {"id":"\(Self.testSessionId)","currentTime":0.0,"duration":10.0,
         "audioTracks":[{"index":0,"startOffset":0.0,"duration":10.0,"contentUrl":"\(trackUrl)","mimeType":"audio/mpeg"}],
         "chapters":[]}
        """)
    }

    private func seriesResponse() -> Data {
        let lib = Self.testLibraryId
        return json(200, """
        {"results":[{"id":"\(Self.testSeriesId)","libraryId":"\(lib)","name":"\(Self.testSeriesName)","books":[
          {"id":"\(Self.testItemId)","libraryId":"\(lib)","seriesSequence":"1","media":{"metadata":{"title":"\(Self.testItemTitle)","authorName":"\(Self.testItemAuthor)","genres":null},"ebookFormat":"epub","ebookFile":{"ino":"\(Self.testFileIno)"},"numAudioFiles":0},"userMediaProgress":null}
        ]}]}
        """)
    }

    private func collectionsResponse() -> Data {
        let lib = Self.testLibraryId
        return json(200, """
        {"results":[{"id":"\(Self.testCollectionId)","libraryId":"\(lib)","name":"\(Self.testCollectionName)","books":[
          {"id":"\(Self.testItemId)","libraryId":"\(lib)","media":{"metadata":{"title":"\(Self.testItemTitle)","authorName":"\(Self.testItemAuthor)","genres":null},"ebookFormat":"epub","ebookFile":{"ino":"\(Self.testFileIno)"},"numAudioFiles":0},"userMediaProgress":null}
        ]}]}
        """)
    }

    // swiftlint:enable line_length

    private func epubResponse(_ name: String) -> Data {
        let bytes = assetBytes(name)
        return StubHTTPResponse(status: 200, contentType: "application/epub+zip", body: bytes).toData()
    }

    private func pdfResponse() -> Data {
        let bytes = assetBytes("test.pdf")
        return StubHTTPResponse(status: 200, contentType: "application/pdf", body: bytes).toData()
    }

    private func mp3Response() -> Data {
        let bytes = assetBytes("test_tone.mp3")
        return StubHTTPResponse(status: 200, contentType: "audio/mpeg", body: bytes).toData()
    }

    private func cbzItemResponse() -> Data {
        json(200, #"{"id":"\#(Self.testCbzItemId)","media":{"ebookFile":{"ino":"\#(Self.testCbzFileIno)"}}}"#)
    }

    private func cbzFileResponse() -> Data {
        let bytes = makeCbz(pages: 5)
        return StubHTTPResponse(status: 200, contentType: "application/x-cbz", body: bytes).toData()
    }

    private func json(_ status: Int, _ body: String) -> Data {
        let bodyData = Data(body.trimmingCharacters(in: .whitespacesAndNewlines).utf8)
        return StubHTTPResponse(status: status, contentType: "application/json", body: bodyData).toData()
    }

    // MARK: - Assets

    private func assetBytes(_ name: String) -> Data {
        let bundle = Bundle(for: StubAbsServer.self)
        guard let url = bundle.url(forResource: name, withExtension: nil),
              let data = try? Data(contentsOf: url) else {
            fatalError("StubAbsServer: missing test asset '\(name)' in test bundle")
        }
        return data
    }

    // MARK: - CBZ generation

    private func makeCbz(pages: Int) -> Data {
        let png = makePng()
        var zip = Data()
        var centralDir = Data()
        var offsets = [UInt32]()
        for pageIndex in 0..<pages {
            let name = String(format: "%03d.png", pageIndex + 1)
            let nameData = Data(name.utf8)
            offsets.append(UInt32(zip.count))
            let crc = crc32(png)
            var lh = Data()
            lh.appendLE(UInt32(0x04034b50))
            lh.appendLE(UInt16(20)); lh.appendLE(UInt16(0)); lh.appendLE(UInt16(0))
            lh.appendLE(UInt16(0)); lh.appendLE(UInt16(0))
            lh.appendLE(UInt32(crc))
            lh.appendLE(UInt32(png.count)); lh.appendLE(UInt32(png.count))
            lh.appendLE(UInt16(nameData.count)); lh.appendLE(UInt16(0))
            lh.append(nameData); lh.append(png)
            zip.append(lh)
            var cd = Data()
            cd.appendLE(UInt32(0x02014b50))
            cd.appendLE(UInt16(20)); cd.appendLE(UInt16(20)); cd.appendLE(UInt16(0)); cd.appendLE(UInt16(0))
            cd.appendLE(UInt16(0)); cd.appendLE(UInt16(0))
            cd.appendLE(UInt32(crc))
            cd.appendLE(UInt32(png.count)); cd.appendLE(UInt32(png.count))
            cd.appendLE(UInt16(nameData.count)); cd.appendLE(UInt16(0)); cd.appendLE(UInt16(0))
            cd.appendLE(UInt16(0)); cd.appendLE(UInt16(0)); cd.appendLE(UInt32(0))
            cd.appendLE(offsets[pageIndex]); cd.append(nameData)
            centralDir.append(cd)
        }
        let cdOffset = UInt32(zip.count)
        zip.append(centralDir)
        var eocd = Data()
        eocd.appendLE(UInt32(0x06054b50))
        eocd.appendLE(UInt16(0)); eocd.appendLE(UInt16(0))
        eocd.appendLE(UInt16(pages)); eocd.appendLE(UInt16(pages))
        eocd.appendLE(UInt32(centralDir.count)); eocd.appendLE(UInt32(cdOffset))
        eocd.appendLE(UInt16(0))
        zip.append(eocd)
        return zip
    }

    private func makePng() -> Data {
        UIGraphicsImageRenderer(size: CGSize(width: 1, height: 1)).pngData { ctx in
            UIColor.white.setFill()
            ctx.fill(CGRect(x: 0, y: 0, width: 1, height: 1))
        }
    }

    private func crc32(_ data: Data) -> UInt32 {
        var crc: UInt32 = 0xffffffff
        for byte in data {
            crc = (crc >> 8) ^ StubAbsServer.crc32Table[Int((crc ^ UInt32(byte)) & 0xff)]
        }
        return crc ^ 0xffffffff
    }

    private static let crc32Table: [UInt32] = (0..<256).map { idx -> UInt32 in
        var entry = UInt32(idx)
        for _ in 0..<8 { entry = entry & 1 == 1 ? 0xedb88320 ^ (entry >> 1) : entry >> 1 }
        return entry
    }
}

// MARK: - Minimal HTTP helpers

struct StubHTTPRequest {
    let method: String
    let path: String

    static func parse(_ data: Data) -> StubHTTPRequest? {
        guard let str = String(data: data, encoding: .utf8),
              let headerEnd = str.range(of: "\r\n\r\n") else { return nil }
        let header = str[str.startIndex..<headerEnd.lowerBound]
        let firstLine = header.components(separatedBy: "\r\n").first ?? ""
        let parts = firstLine.components(separatedBy: " ")
        guard parts.count >= 2 else { return nil }
        return StubHTTPRequest(method: parts[0], path: String(parts[1].prefix(while: { $0 != "?" })))
    }
}

struct StubHTTPResponse {
    let status: Int
    let contentType: String
    let body: Data

    func toData() -> Data {
        let statusLine = "HTTP/1.1 \(status) \(statusText(status))\r\n"
        let headers = "Content-Type: \(contentType)\r\nContent-Length: \(body.count)\r\nConnection: close\r\n\r\n"
        var out = Data((statusLine + headers).utf8)
        out.append(body)
        return out
    }

    private func statusText(_ code: Int) -> String {
        switch code {
        case 200: return "OK"
        case 404: return "Not Found"
        default: return "Status \(code)"
        }
    }
}

private extension Data {
    mutating func appendLE(_ value: UInt16) {
        var le = value.littleEndian
        Swift.withUnsafeBytes(of: &le) { append(contentsOf: $0) }
    }
    mutating func appendLE(_ value: UInt32) {
        var le = value.littleEndian
        Swift.withUnsafeBytes(of: &le) { append(contentsOf: $0) }
    }
}
