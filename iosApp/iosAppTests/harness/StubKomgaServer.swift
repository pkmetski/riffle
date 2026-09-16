import Foundation
import Network
import UIKit
import XCTest

// In-process Komga HTTP stub backed by NWListener. Serves the minimal Komga REST surface needed
// for the add-source flow and comics-reader harness tests.
final class StubKomgaServer {

    // MARK: - Constants

    static let TEST_USER_ID = "komga-user-1"
    static let TEST_LIBRARY_ID = "komga-lib-1"
    static let TEST_LIBRARY_NAME = "Test Comics"
    static let TEST_CBZ_BOOK_ID = "komga-book-1"
    static let TEST_CBZ_BOOK_TITLE = "Test CBZ"
    static let TEST_CBZ_PAGE_COUNT = 5

    // MARK: - State

    private var listener: NWListener?
    private let queue = DispatchQueue(label: "riffle.stub.komga", qos: .userInitiated)
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
                XCTFail("StubKomgaServer failed to start: \(err)")
                sem.signal()
            }
        }
        listener?.newConnectionHandler = { [weak self] conn in self?.handle(conn) }
        listener?.start(queue: queue)
        sem.wait()
        XCTAssertNotEqual(port, 0, "StubKomgaServer must bind to a non-zero port")
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
        let book = Self.TEST_CBZ_BOOK_ID
        let p = req.path
        let m = req.method

        // Auth probe — both v2 and v1 paths
        if m == "GET" && (p == "/api/v2/users/me" || p == "/api/v1/users/me") {
            return json(200, #"{"id":"\#(Self.TEST_USER_ID)"}"#)
        }
        // Libraries
        if m == "GET" && p == "/api/v1/libraries" {
            return json(200, #"[{"id":"\#(lib)","name":"\#(Self.TEST_LIBRARY_NAME)","unavailable":false}]"#)
        }
        // Book detail (must come before the browse prefix match)
        if m == "GET" && p == "/api/v1/books/\(book)" {
            return json(200, bookDto(book))
        }
        // Books browse (query params are stripped by parse(), so match bare path)
        if m == "GET" && p == "/api/v1/books" {
            return json(200, booksPage())
        }
        // Book pages (image)
        if m == "GET" && p.hasPrefix("/api/v1/books/\(book)/pages/") {
            return pngResponse()
        }
        // Read-progress
        if m == "PATCH" && p.hasPrefix("/api/v1/books/\(book)/read-progress") {
            return json(200, "{}")
        }
        // Readlists
        if m == "GET" && p.hasPrefix("/api/v1/readlists") {
            return json(200, emptyPage())
        }
        // Series
        if m == "GET" && p.hasPrefix("/api/v1/series") {
            return json(200, emptyPage())
        }
        // Actuator
        if m == "GET" && p == "/actuator/info" {
            return json(200, #"{"build":{"version":"1.0.0"}}"#)
        }
        // Server info probe (reachability check in test)
        if m == "GET" && p == "/api/v1/settings" {
            return StubHTTPResponse(status: 401, contentType: "application/json", body: Data("{}".utf8)).toData()
        }
        return StubHTTPResponse(status: 404, contentType: "text/plain", body: Data("Not Found".utf8)).toData()
    }

    // MARK: - Response builders

    private func booksPage() -> String {
        let lib = Self.TEST_LIBRARY_ID
        let book = Self.TEST_CBZ_BOOK_ID
        return """
        {"content":[\(bookDto(book))],"number":0,"size":20,"totalPages":1,"totalElements":1,"first":true,"last":true,"empty":false}
        """
    }

    private func bookDto(_ id: String) -> String {
        let lib = Self.TEST_LIBRARY_ID
        return #"{"id":"\#(id)","libraryId":"\#(lib)","name":"\#(Self.TEST_CBZ_BOOK_TITLE)","media":{"mediaProfile":"DIVINA","pagesCount":\#(Self.TEST_CBZ_PAGE_COUNT),"status":"READY"},"metadata":{"title":"\#(Self.TEST_CBZ_BOOK_TITLE)","authors":[]},"readProgress":null}"#
    }

    private func emptyPage() -> String {
        #"{"content":[],"number":0,"size":20,"totalPages":0,"totalElements":0,"first":true,"last":true,"empty":true}"#
    }

    private func pngResponse() -> Data {
        let png = UIGraphicsImageRenderer(size: CGSize(width: 100, height: 100)).pngData { ctx in
            UIColor.lightGray.setFill()
            ctx.fill(CGRect(x: 0, y: 0, width: 100, height: 100))
        }
        return StubHTTPResponse(status: 200, contentType: "image/png", body: png).toData()
    }

    private func json(_ status: Int, _ body: String) -> Data {
        let bodyData = Data(body.trimmingCharacters(in: .whitespacesAndNewlines).utf8)
        return StubHTTPResponse(status: status, contentType: "application/json", body: bodyData).toData()
    }
}
