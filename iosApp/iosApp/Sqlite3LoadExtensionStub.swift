// iOS system SQLite is compiled with SQLITE_OMIT_LOAD_EXTENSION.
// The androidx.sqlite KMP cinterop references sqlite3_load_extension even though it is never
// called at runtime on iOS. Without this stub dyld aborts on launch with "symbol not found in
// flat namespace". Returning SQLITE_ERROR (1) is safe: any caller would handle the error.
@_cdecl("sqlite3_load_extension")
func sqlite3LoadExtension(
    db: OpaquePointer?,
    zFile: UnsafePointer<CChar>?,
    zProc: UnsafePointer<CChar>?,
    pzErrMsg: UnsafeMutablePointer<UnsafeMutablePointer<CChar>?>?
) -> Int32 {
    pzErrMsg?.pointee = nil
    return 1 // SQLITE_ERROR
}
