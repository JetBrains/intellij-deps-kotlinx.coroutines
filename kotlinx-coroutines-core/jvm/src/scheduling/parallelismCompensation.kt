package kotlinx.coroutines.scheduling

import kotlinx.coroutines.DefaultDelay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.internal.synchronized
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration

private val parallelismCompensationEnabled: Boolean =
    System.getProperty("kotlinx.coroutines.parallelism.compensation", "true").toBoolean()

/**
 * Introduced as part of IntelliJ patches.
 *
 * Increases the parallelism limit of the coroutine dispatcher associated with the current thread for the duration of [body] execution.
 * After the [body] completes, the effective parallelism may stay higher than the associated limit, but it is said
 * that eventually it will adjust to meet it.
 */
@Suppress("NOTHING_TO_INLINE") // better stacktrace
internal inline fun <T> withCompensatedParallelism(timeout: Duration, noinline body: () -> T): T {
    if (!parallelismCompensationEnabled) {
        return body()
    }
    // CoroutineScheduler.Worker implements ParallelismCompensation
    val worker = Thread.currentThread() as? ParallelismCompensation
        ?: return body()
    return if (timeout == Duration.ZERO) {
        worker.withCompensatedParallelismImmediate(body)
    } else {
        worker.withCompensatedParallelismAfterDeadline(timeout, body)
    }
}

private fun <T> ParallelismCompensation.withCompensatedParallelismImmediate(body: () -> T): T {
    increaseParallelismAndLimit()
    try {
        return body()
    } finally {
        decreaseParallelismLimit()
    }
}

private fun <T> ParallelismCompensation.withCompensatedParallelismAfterDeadline(timeout: Duration, body: () -> T): T {
    val holder = WorkerProtector()
    val compensationHandle = initCompensationWithDeadline(this, holder, timeout)
    try {
        return body()
    } finally {
        endCompensationWithDeadline(this, holder, compensationHandle)
    }
}

/**
 * The fields in [CoroutineScheduler.Worker] are not thread safe: these are plain java fields, and they are intended to
 * be accessed only by [CoroutineScheduler.Worker] itself. Since we want to modify them from other threads,
 * we need to establish proper happens-before relations for accesses to these fields.
 */
private class WorkerProtector() {
    @Volatile
    var wasTaken: Boolean = false
}

private fun initCompensationWithDeadline(
    thread: ParallelismCompensation,
    state: WorkerProtector,
    timeout: Duration
): DisposableHandle {
    return DefaultDelay.invokeOnTimeout(timeout.inWholeMilliseconds, {
        if (!state.wasTaken) {
            synchronized(state) {
                if (!state.wasTaken) {
                    // so the task was not finished yet, which means that we can increase parallelism here
                    thread.increaseParallelismAndLimit()
                    state.wasTaken = true
                }
            }
        }
    }, EmptyCoroutineContext)
}

private fun endCompensationWithDeadline(
    thread: ParallelismCompensation,
    state: WorkerProtector,
    compensationHandle: DisposableHandle,
) {
    synchronized(state) {
        if (!state.wasTaken) {
            // so parallelism was not compensated at this moment; we can abort the compensating coroutine
            compensationHandle.dispose()
            state.wasTaken = true
        } else {
            // the compensating coroutine managed to publish a request for compensation. Now we need to decompensate
            thread.decreaseParallelismLimit()
        }
    }
}