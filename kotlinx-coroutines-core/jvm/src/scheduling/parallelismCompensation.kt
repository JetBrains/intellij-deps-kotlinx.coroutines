package kotlinx.coroutines.scheduling

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
internal inline fun <T> withCompensatedParallelism(noinline body: () -> T): T {
    if (!parallelismCompensationEnabled) {
        return body()
    }
    // CoroutineScheduler.Worker implements ParallelismCompensation
    val worker = Thread.currentThread() as? ParallelismCompensation
        ?: return body()
    return worker.withCompensatedParallelism(body)
}

private fun <T> ParallelismCompensation.withCompensatedParallelism(body: () -> T): T {
    increaseParallelismAndLimit()
    try {
        return body()
    } finally {
        decreaseParallelismLimit()
    }
}