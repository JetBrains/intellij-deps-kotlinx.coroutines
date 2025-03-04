/**
 * A special file that contains IntelliJ-related functions
 */
package kotlinx.coroutines.internal.intellij

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.InternalCoroutinesApi
import kotlin.coroutines.*
import kotlinx.coroutines.internal.softLimitedParallelism as softLimitedParallelismImpl
import kotlinx.coroutines.internal.SoftLimitedDispatcher
import kotlinx.coroutines.runBlockingWithParallelismCompensation as runBlockingWithParallelismCompensationImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.scheduling.withCompensatedParallelism
import kotlin.coroutines.CoroutineContext
import kotlin.jvm.Throws
import kotlin.time.Duration

internal val currentContextThreadLocal : ThreadLocal<CoroutineContext?> = ThreadLocal.withInitial { null }

/**
 * [IntellijCoroutines] exposes the API added as part of IntelliJ patches.
 * Prefer to use the corresponding API from the IntelliJ Platform instead of accessing this object directly.
 */
@InternalCoroutinesApi
public object IntellijCoroutines {

    /**
     * IntelliJ Platform would like to introspect coroutine contexts outside the coroutine framework.
     * This function is a non-suspend version of [coroutineContext].
     *
     * @return null if current thread is not used by coroutine dispatchers,
     * or [coroutineContext] otherwise.
     */
    public fun currentThreadCoroutineContext(): CoroutineContext? {
        return currentContextThreadLocal.get()
    }

    /**
     * An analogue of [runBlocking][kotlinx.coroutines.runBlocking] that [compensates parallelism][kotlinx.coroutines.scheduling.withCompensatedParallelism]
     * while the coroutine is not complete and the associated event loop has no immediate work available.
     */
    @Throws(InterruptedException::class)
    public fun <T> runBlockingWithParallelismCompensation(
        context: CoroutineContext,
        block: suspend CoroutineScope.() -> T
    ): T =
        runBlockingWithParallelismCompensationImpl(context, block)

    /**
     * Constructs a [SoftLimitedDispatcher] from the specified [CoroutineDispatcher].
     * [SoftLimitedDispatcher] behaves as [LimitedDispatcher][kotlinx.coroutines.internal.LimitedDispatcher] but allows
     * temporarily exceeding the parallelism limit in case [parallelism compensation][kotlinx.coroutines.scheduling.withCompensatedParallelism]
     * was requested (e.g., by [kotlinx.coroutines.runBlocking]).
     *
     * This extension can only be used on instances of [Dispatchers.Default], [Dispatchers.IO] and also on what this extension
     * has returned. Throws [UnsupportedOperationException] if [this] does not support the parallelism compensation mechanism.
     */
    public fun CoroutineDispatcher.softLimitedParallelism(parallelism: Int, name: String?): CoroutineDispatcher =
        softLimitedParallelismImpl(parallelism, name)

    @Deprecated("use named version", level = DeprecationLevel.HIDDEN)
    public fun CoroutineDispatcher.softLimitedParallelism(parallelism: Int): CoroutineDispatcher =
        softLimitedParallelismImpl(parallelism, null)

    /**
     * Executes [action] and **advises** to compensate parallelism if [action] does not finish within [timeout].
     *
     * Suppose that you are dealing with an operation that blocks the underlying thread.
     * If the blocked thread happens to be from a limited-thread-pool dispatcher, then you face the problem of thread starvation:
     * your system underutilizes the available CPU. In severe cases, this may result in a deadlock.
     * To increase the size of the thread pool, you can wrap the operation into this function.
     *
     * Remember that threads are expensive, so you need to choose an appropriate [timeout]
     * so that parallelism compensation would be less expensive than waiting for [action] to finish.
     *
     * There are intentionally few guarantees that this function will have side effects.
     * Generally, parallelism compensation applies only to threads that are ready for compensation,
     * such as [Dispatchers.Default] or [Dispatchers.IO].
     */
    public fun <T> runAndCompensateParallelism(timeout: Duration, action: () -> T): T {
        return withCompensatedParallelism(timeout, action)
    }
}
