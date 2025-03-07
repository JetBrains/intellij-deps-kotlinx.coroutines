package kotlinx.coroutines

import kotlinx.coroutines.internal.ScopeCoroutine
import kotlin.coroutines.*

@PublishedApi // Used from kotlinx-coroutines-test via suppress, not part of ABI
internal actual val DefaultDelay: Delay
    get() = Dispatchers.Default as Delay

public actual fun CoroutineScope.newCoroutineContext(context: CoroutineContext): CoroutineContext {
    val combined = coroutineContext + context
    return if (combined !== Dispatchers.Default && combined[ContinuationInterceptor] == null)
        combined + Dispatchers.Default else combined
}

public actual fun CoroutineContext.newCoroutineContext(addedContext: CoroutineContext): CoroutineContext {
    return this + addedContext
}

internal actual fun Continuation<*>.toDebugString(): String = toString()
internal actual val CoroutineContext.coroutineName: String? get() = null // not supported on Wasm and JS

internal actual inline fun <T> withThreadLocalContext(context: CoroutineContext, block: () -> T) : T = block()
