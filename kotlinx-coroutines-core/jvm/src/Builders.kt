@file:JvmMultifileClass
@file:JvmName("BuildersKt")

package kotlinx.coroutines

import kotlinx.coroutines.scheduling.withCompensatedParallelism
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.coroutines.*
import kotlin.time.Duration

/**
 * The same as [runBlocking], but for consumption from Java.
 * From Kotlin's point of view, this function has the exact same signature as the regular [runBlocking].
 * This is done so that it can not be called from Kotlin, despite the fact that it is public.
 *
 * We do not expose this [runBlocking] in the documentation, because it is not supposed to be used from Kotlin.
 *
 * @suppress
 */
@Throws(InterruptedException::class)
@Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@kotlin.internal.LowPriorityInOverloadResolution
public fun <T> runBlocking(
    context: CoroutineContext = EmptyCoroutineContext, block: suspend CoroutineScope.() -> T
): T = runBlocking(context, block)

@Throws(InterruptedException::class)
internal actual fun <T> runBlockingImpl(
    newContext: CoroutineContext, eventLoop: EventLoop?, block: suspend CoroutineScope.() -> T
): T {
    val coroutine = BlockingCoroutine<T>(newContext, Thread.currentThread(), eventLoop, false)
    coroutine.start(CoroutineStart.DEFAULT, coroutine, block)
    return coroutine.joinBlocking()
}

// This function combines implementation of `runBlocking` from the common module and `runBlockingImpl` from this file
@OptIn(ExperimentalContracts::class)
@Suppress("LEAKED_IN_PLACE_LAMBDA", "WRONG_INVOCATION_KIND")
@Throws(InterruptedException::class)
internal fun <T> runBlockingWithParallelismCompensation(context: CoroutineContext = EmptyCoroutineContext, block: suspend CoroutineScope.() -> T): T {
    contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
    val contextInterceptor = context[ContinuationInterceptor]
    val eventLoop: EventLoop?
    val newContext: CoroutineContext
    if (contextInterceptor == null) {
        // create or use private event loop if no dispatcher is specified
        eventLoop = ThreadLocalEventLoop.eventLoop
        newContext = GlobalScope.newCoroutineContext(context + eventLoop)
    } else {
        eventLoop = ThreadLocalEventLoop.currentOrNull()
        newContext = GlobalScope.newCoroutineContext(context)
    }
    val coroutine = BlockingCoroutine<T>(newContext, Thread.currentThread(), eventLoop, true)
    coroutine.start(CoroutineStart.DEFAULT, coroutine, block)
    return coroutine.joinBlocking()
}

private class BlockingCoroutine<T>(
    parentContext: CoroutineContext,
    private val blockedThread: Thread,
    private val eventLoop: EventLoop?,
    private val compensateParallelism: Boolean,
) : AbstractCoroutine<T>(parentContext, true, true) {

    override val isScopedCoroutine: Boolean get() = true

    override fun afterCompletion(state: Any?) {
        // wake up blocked thread
        if (Thread.currentThread() != blockedThread)
            unpark(blockedThread)
    }

    @Suppress("UNCHECKED_CAST")
    fun joinBlocking(): T {
        registerTimeLoopThread()
        try {
            eventLoop?.incrementUseCount()
            try {
                while (true) {
                    val parkNanos = eventLoop?.processNextEvent() ?: Long.MAX_VALUE
                    // note: process next even may loose unpark flag, so check if completed before parking
                    if (isCompleted) break
                    if (parkNanos > 0) {
                        if (compensateParallelism) {
                            withCompensatedParallelism(Duration.ZERO) {
                                parkNanos(this, parkNanos)
                            }
                        } else {
                            parkNanos(this, parkNanos)
                        }
                    }
                    if (Thread.interrupted()) cancelCoroutine(InterruptedException())
                }
            } finally { // paranoia
                eventLoop?.decrementUseCount()
            }
        } finally { // paranoia
            unregisterTimeLoopThread()
        }
        // now return result
        val state = this.state.unboxState()
        (state as? CompletedExceptionally)?.let { throw it.cause }
        return state as T
    }
}
