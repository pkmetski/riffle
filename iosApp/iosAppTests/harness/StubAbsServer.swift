import Foundation
import Network
import UIKit
import XCTest

// In-process ABS HTTP stub backed by NWListener. Mirrors Android's StubAbsServer.kt so both
// platforms test against an identical canned catalogue.
final class StubAbsServer {

    // MARK: - Constants

    static let TEST_USER_ID = "test-user-id"
    static let TEST_TOKEN = "test-token"
    static let TEST_LIBRARY_ID = "lib-test-1"
    static let TEST_LIBRARY_NAME = "Test Library"
    static let TEST_ITEM_ID = "item-test-1"
    static let TEST_ITEM_TITLE = "Test EPUB"
    static let TEST_ITEM_AUTHOR = "Test Author"
    static let TEST_FILE_INO = "ino-test-1"
    static let TEST_SERIES_ID = "series-test-1"
    static let TEST_SERIES_NAME = "Test Series"
    static let TEST_COLLECTION_ID = "collection-test-1"
    static let TEST_COLLECTION_NAME = "Test Collection"
    static let TEST_STANDALONE_ITEM_ID = "item-test-2"
    static let TEST_STANDALONE_ITEM_TITLE = "Test EPUB Standalone"
    static let TEST_STANDALONE_FILE_INO = "ino-test-2"
    static let TEST_PDF_ITEM_ID = "item-test-3"
    static let TEST_PDF_ITEM_TITLE = "Test PDF"
    static let TEST_PDF_FILE_INO = "ino-test-3"
    static let TEST_FOOTNOTE_ITEM_ID = "item-test-4"
    static let TEST_FOOTNOTE_ITEM_TITLE = "Test Footnotes EPUB"
    static let TEST_FOOTNOTE_FILE_INO = "ino-test-4"
    static let TEST_AUDIO_ITEM_ID = "item-audio-1"
    static let TEST_AUDIO_ITEM_TITLE = "Test Audiobook"
    static let TEST_CBZ_ITEM_ID = "item-cbz-1"
    static let TEST_CBZ_ITEM_TITLE = "Test CBZ"
    static let TEST_CBZ_FILE_INO = "ino-cbz-1"
    static let TEST_SESSION_ID = "playback-session-1"

    // MARK: - State

    private var listener: NWListener?
    private let queue = DispatchQueue(label: "riffle.stub.abs", qos: .userInitiated)
    private(set) var port: UInt16 = 0

    var baseUrl: String { "http://127.0.0.1:\(port)" }

    // MARK: - Lifecycle

    func start() {
        let params = NWParameters.tcp
        params.requiredLocalEndpoint = NWEndpoint.hostPort(host: .ipv4(.loopback), port: 0)
        listener = try! NWListener(using: params)
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

    private func dispatch(_ req: StubHTTPRequest) -> Data {
        let lib = Self.TEST_LIBRARY_ID
        let audioId = Self.TEST_AUDIO_ITEM_ID
        let sessionId = Self.TEST_SESSION_ID
        let p = req.path

        if req.method == "POST" && p == "/login" { return loginResponse() }
        if req.method == "GET" && p == "/api/libraries" { return librariesResponse() }
        if req.method == "GET" && p == "/api/libraries/\(lib)/items" { return libraryItemsResponse() }
        if req.method == "GET" && p.hasPrefix("/api/libraries/\(lib)/series") { return seriesResponse() }
        if req.method == "GET" && p.hasPrefix("/api/libraries/\(lib)/collections") { return collectionsResponse() }
        if req.method == "GET" && p.hasPrefix("/api/libraries/\(lib)/playlists") { return json(200, #"{"results":[]}"#) }
        if req.method == "GET" && p == "/api/items/\(Self.TEST_ITEM_ID)" { return itemResponse(Self.TEST_ITEM_ID, Self.TEST_FILE_INO) }
        if req.method == "GET" && p == "/api/items/\(Self.TEST_ITEM_ID)/ebook/\(Self.TEST_FILE_INO)" { return epubResponse("test.epub") }
        if req.method == "GET" && p == "/api/items/\(Self.TEST_STANDALONE_ITEM_ID)" { return itemResponse(Self.TEST_STANDALONE_ITEM_ID, Self.TEST_STANDALONE_FILE_INO) }
        if req.method == "GET" && p == "/api/items/\(Self.TEST_STANDALONE_ITEM_ID)/ebook/\(Self.TEST_STANDALONE_FILE_INO)" { return epubResponse("test.epub") }
        if req.method == "GET" && p == "/api/items/\(Self.TEST_PDF_ITEM_ID)" { return itemResponse(Self.TEST_PDF_ITEM_ID, Self.TEST_PDF_FILE_INO) }
        if req.method == "GET" && p == "/api/items/\(Self.TEST_PDF_ITEM_ID)/ebook/\(Self.TEST_PDF_FILE_INO)" { return pdfResponse() }
        if req.method == "GET" && p == "/api/items/\(Self.TEST_FOOTNOTE_ITEM_ID)" { return itemResponse(Self.TEST_FOOTNOTE_ITEM_ID, Self.TEST_FOOTNOTE_FILE_INO) }
        if req.method == "GET" && p == "/api/items/\(Self.TEST_FOOTNOTE_ITEM_ID)/ebook/\(Self.TEST_FOOTNOTE_FILE_INO)" { return epubResponse("test-footnotes.epub") }
        if req.method == "GET" && p == "/api/items/\(Self.TEST_CBZ_ITEM_ID)" { return cbzItemResponse() }
        if req.method == "GET" && p == "/api/items/\(Self.TEST_CBZ_ITEM_ID)/ebook/\(Self.TEST_CBZ_FILE_INO)" { return cbzFileResponse() }
        if req.method == "GET" && p == "/api/items/\(audioId)" { return audioItemResponse() }
        if req.method == "POST" && p == "/api/items/\(audioId)/play" { return audioPlayResponse() }
        if req.method == "GET" && p == "/api/items/\(audioId)/file/audio.mp3" { return mp3Response() }
        if req.method == "GET" && p == "/api/me" { return json(200, #"{"mediaProgress":[]}"#) }
        if req.method == "GET" && p.hasPrefix("/api/me/progress/") { return json(200, #"{"ebookLocation":"","ebookProgress":0.0,"lastUpdate":-1}"#) }
        if req.method == "PATCH" && p.hasPrefix("/api/me/progress/") { return json(200, "{}") }
        if req.method == "POST" && p.hasPrefix("/api/session/") { return json(200, "{}") }
        if req.method == "GET" && p == "/status" { return json(200, #"{"serverVersion":"1.0.0"}"#) }
        return StubHTTPResponse(status: 404, contentType: "text/plain", body: Data("Not Found".utf8)).toData()
    }

    // MARK: - Response builders

    private func loginResponse() -> Data {
        json(200, """
        {"user":{"id":"\(Self.TEST_USER_ID)","username":"testuser","token":"\(Self.TEST_TOKEN)"}}
        """)
    }

    private func librariesResponse() -> Data {
        json(200, """
        {"libraries":[{"id":"\(Self.TEST_LIBRARY_ID)","name":"\(Self.TEST_LIBRARY_NAME)","mediaType":"book","settings":{"audiobooksOnly":false}}]}
        """)
    }

    private func libraryItemsResponse() -> Data {
        let lib = Self.TEST_LIBRARY_ID
        return json(200, """
        {"results":[
          {"id":"\(Self.TEST_ITEM_ID)","libraryId":"\(lib)","media":{"metadata":{"title":"\(Self.TEST_ITEM_TITLE)","authorName":"\(Self.TEST_ITEM_AUTHOR)","genres":null},"ebookFormat":"epub","ebookFile":{"ino":"\(Self.TEST_FILE_INO)"},"numAudioFiles":0},"userMediaProgress":null},
          {"id":"\(Self.TEST_STANDALONE_ITEM_ID)","libraryId":"\(lib)","media":{"metadata":{"title":"\(Self.TEST_STANDALONE_ITEM_TITLE)","authorName":"\(Self.TEST_ITEM_AUTHOR)","genres":null},"ebookFormat":"epub","ebookFile":{"ino":"\(Self.TEST_STANDALONE_FILE_INO)"},"numAudioFiles":0},"userMediaProgress":null},
          {"id":"\(Self.TEST_PDF_ITEM_ID)","libraryId":"\(lib)","media":{"metadata":{"title":"\(Self.TEST_PDF_ITEM_TITLE)","authorName":"\(Self.TEST_ITEM_AUTHOR)","genres":null},"ebookFormat":"pdf","ebookFile":{"ino":"\(Self.TEST_PDF_FILE_INO)"},"numAudioFiles":0},"userMediaProgress":null},
          {"id":"\(Self.TEST_FOOTNOTE_ITEM_ID)","libraryId":"\(lib)","media":{"metadata":{"title":"\(Self.TEST_FOOTNOTE_ITEM_TITLE)","authorName":"\(Self.TEST_ITEM_AUTHOR)","genres":null},"ebookFormat":"epub","ebookFile":{"ino":"\(Self.TEST_FOOTNOTE_FILE_INO)"},"numAudioFiles":0},"userMediaProgress":null},
          {"id":"\(Self.TEST_AUDIO_ITEM_ID)","libraryId":"\(lib)","media":{"metadata":{"title":"\(Self.TEST_AUDIO_ITEM_TITLE)","authorName":"\(Self.TEST_ITEM_AUTHOR)","genres":null},"ebookFormat":null,"ebookFile":null,"numAudioFiles":1},"userMediaProgress":null},
          {"id":"\(Self.TEST_CBZ_ITEM_ID)","libraryId":"\(lib)","media":{"metadata":{"title":"\(Self.TEST_CBZ_ITEM_TITLE)","authorName":"\(Self.TEST_ITEM_AUTHOR)","genres":null},"ebookFormat":"cbz","ebookFile":{"ino":"\(Self.TEST_CBZ_FILE_INO)"},"numAudioFiles":0},"userMediaProgress":null}
        ]}
        """)
    }

    private func itemResponse(_ id: String, _ ino: String) -> Data {
        json(200, #"{"id":"\#(id)","media":{"ebookFile":{"ino":"\#(ino)"}}}"#)
    }

    private func audioItemResponse() -> Data {
        json(200, #"{"id":"\#(Self.TEST_AUDIO_ITEM_ID)","media":{"numAudioFiles":1,"audioFiles":[{"index":0,"ino":"audio-ino-1","duration":10.0,"mimeType":"audio/mpeg","metadata":{"filename":"audio.mp3"}}]}}"#)
    }

    private func audioPlayResponse() -> Data {
        let trackUrl = "/api/items/\(Self.TEST_AUDIO_ITEM_ID)/file/audio.mp3"
        return json(200, """
        {"id":"\(Self.TEST_SESSION_ID)","currentTime":0.0,"duration":10.0,
         "audioTracks":[{"index":0,"startOffset":0.0,"duration":10.0,"contentUrl":"\(trackUrl)","mimeType":"audio/mpeg"}],
         "chapters":[]}
        """)
    }

    private func seriesResponse() -> Data {
        let lib = Self.TEST_LIBRARY_ID
        return json(200, """
        {"results":[{"id":"\(Self.TEST_SERIES_ID)","libraryId":"\(lib)","name":"\(Self.TEST_SERIES_NAME)","books":[
          {"id":"\(Self.TEST_ITEM_ID)","libraryId":"\(lib)","seriesSequence":"1","media":{"metadata":{"title":"\(Self.TEST_ITEM_TITLE)","authorName":"\(Self.TEST_ITEM_AUTHOR)","genres":null},"ebookFormat":"epub","ebookFile":{"ino":"\(Self.TEST_FILE_INO)"},"numAudioFiles":0},"userMediaProgress":null}
        ]}]}
        """)
    }

    private func collectionsResponse() -> Data {
        let lib = Self.TEST_LIBRARY_ID
        return json(200, """
        {"results":[{"id":"\(Self.TEST_COLLECTION_ID)","libraryId":"\(lib)","name":"\(Self.TEST_COLLECTION_NAME)","books":[
          {"id":"\(Self.TEST_ITEM_ID)","libraryId":"\(lib)","media":{"metadata":{"title":"\(Self.TEST_ITEM_TITLE)","authorName":"\(Self.TEST_ITEM_AUTHOR)","genres":null},"ebookFormat":"epub","ebookFile":{"ino":"\(Self.TEST_FILE_INO)"},"numAudioFiles":0},"userMediaProgress":null}
        ]}]}
        """)
    }

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
        json(200, #"{"id":"\#(Self.TEST_CBZ_ITEM_ID)","media":{"ebookFile":{"ino":"\#(Self.TEST_CBZ_FILE_INO)"}}}"#)
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
        for i in 0..<pages {
            let name = String(format: "%03d.png", i + 1)
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
            cd.appendLE(offsets[i]); cd.append(nameData)
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

    private static let crc32Table: [UInt32] = (0..<256).map { i -> UInt32 in
        var c = UInt32(i)
        for _ in 0..<8 { c = c & 1 == 1 ? 0xedb88320 ^ (c >> 1) : c >> 1 }
        return c
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
    mutating func appendLE(_ v: UInt16) {
        var val = v.littleEndian
        Swift.withUnsafeBytes(of: &val) { append(contentsOf: $0) }
    }
    mutating func appendLE(_ v: UInt32) {
        var val = v.littleEndian
        Swift.withUnsafeBytes(of: &val) { append(contentsOf: $0) }
    }
}
