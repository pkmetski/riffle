import Foundation

// The androidx.sqlite cinterop layer generates a wrapper that references sqlite3_load_extension,
// which iOS system SQLite (and androidx.sqlite-bundled) omit. This no-op stub satisfies the
// linker; it is never called at runtime because BundledSQLiteDriver never invokes the extension
// loading path.
@_cdecl("sqlite3_load_extension")
func sqlite3LoadExtensionStub(
    _ db: OpaquePointer?,
    _ zFile: UnsafePointer<CChar>?,
    _ zProc: UnsafePointer<CChar>?,
    _ pzErrMsg: UnsafeMutablePointer<UnsafeMutablePointer<CChar>?>?
) -> Int32 {
    return 1 // SQLITE_ERROR
}
