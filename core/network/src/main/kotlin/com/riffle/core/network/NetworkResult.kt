package com.riffle.core.network

import com.riffle.core.domain.InsecureConnectionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import java.io.IOException
import javax.net.ssl.SSLHandshakeException

/**
 * Single result type for every ABS / Storyteller endpoint.
 *
 * Per-endpoint sealed classes (`NetworkLibrariesResult`, `NetworkLoginResult`, …) used to repeat
 * the same error variants and only the Success payload differed. That made cross-cutting concerns
 * (telemetry, retries, "what does Offline mean") impossible to express once; this collapses them.
 */
sealed class NetworkResult<out T> {
    data class Success<T>(val value: T) : NetworkResult<T>()
    data class Offline(val cause: Throwable) : NetworkResult<Nothing>()
    data object Auth : NetworkResult<Nothing>()
    data class ServerError(val code: Int, val errorMessage: String? = null) : NetworkResult<Nothing>()
    data class Parse(val cause: Throwable) : NetworkResult<Nothing>()
    data class InsecureConnection(val type: InsecureConnectionType) : NetworkResult<Nothing>()
    data class Unknown(val cause: Throwable) : NetworkResult<Nothing>()
}

/** Thrown by endpoint blocks to signal a non-success HTTP code; the classifier maps 401 → Auth. */
class HttpException(val code: Int, msg: String? = null) : IOException(msg)

object OkHttpClassifier {
    /**
     * Run [block], producing a `Success`. Any thrown exception is mapped to the matching
     * `NetworkResult` variant. Block authors throw `HttpException` for non-success codes and
     * `IOException("Empty response body")` for missing bodies.
     */
    suspend fun <T> classify(block: suspend () -> T): NetworkResult<T> = withContext(Dispatchers.IO) {
        try {
            NetworkResult.Success(block())
        } catch (e: HttpException) {
            if (e.code == 401) NetworkResult.Auth
            else NetworkResult.ServerError(e.code, e.message)
        } catch (e: SSLHandshakeException) {
            NetworkResult.InsecureConnection(InsecureConnectionType.SELF_SIGNED)
        } catch (e: SerializationException) {
            NetworkResult.Parse(e)
        } catch (e: IOException) {
            NetworkResult.Offline(e)
        } catch (e: Throwable) {
            NetworkResult.Unknown(e)
        }
    }
}

inline fun <T, R> NetworkResult<T>.mapResult(f: (T) -> R): NetworkResult<R> = when (this) {
    is NetworkResult.Success -> NetworkResult.Success(f(value))
    is NetworkResult.Offline -> this
    is NetworkResult.Auth -> this
    is NetworkResult.ServerError -> this
    is NetworkResult.Parse -> this
    is NetworkResult.InsecureConnection -> this
    is NetworkResult.Unknown -> this
}

inline fun <T> NetworkResult<T>.onError(report: (NetworkResult<Nothing>) -> Unit): NetworkResult<T> {
    if (this !is NetworkResult.Success) {
        @Suppress("UNCHECKED_CAST")
        report(this as NetworkResult<Nothing>)
    }
    return this
}

/** Convenience: did this terminate in any non-success state? */
val NetworkResult<*>.isError: Boolean get() = this !is NetworkResult.Success

/** Get the value or null. */
fun <T> NetworkResult<T>.getOrNull(): T? = (this as? NetworkResult.Success)?.value

/**
 * Best-effort `Throwable` for non-success variants so legacy domain types that wrap a cause
 * (e.g. `EpubOpenResult.NetworkError(cause)`) can keep being driven from a single call site.
 */
fun NetworkResult<*>.errorAsThrowable(): Throwable = when (this) {
    is NetworkResult.Success -> error("Success has no error")
    is NetworkResult.Offline -> cause
    is NetworkResult.Parse -> cause
    is NetworkResult.Unknown -> cause
    is NetworkResult.ServerError -> java.io.IOException("HTTP $code${errorMessage?.let { ": $it" } ?: ""}")
    NetworkResult.Auth -> java.io.IOException("HTTP 401")
    is NetworkResult.InsecureConnection -> javax.net.ssl.SSLHandshakeException("Insecure connection ($type)")
}
