package kotlinx.coroutines.scheduling

import kotlinx.coroutines.*
import kotlinx.coroutines.internal.*
import java.util.concurrent.*
import kotlin.coroutines.*

// Instance of Dispatchers.Default
internal object DefaultScheduler : SchedulerCoroutineDispatcher(
    CORE_POOL_SIZE, MAX_POOL_SIZE,
    IDLE_WORKER_KEEP_ALIVE_NS, DEFAULT_SCHEDULER_NAME
) {

    @ExperimentalCoroutinesApi
    override fun limitedParallelism(parallelism: Int): CoroutineDispatcher {
        parallelism.checkParallelism()
        if (parallelism >= CORE_POOL_SIZE) return this
        return super.limitedParallelism(parallelism)
    }

    // Shuts down the dispatcher, used only by Dispatchers.shutdown()
    internal fun shutdown() {
        super.close()
    }

    // Overridden in case anyone writes (Dispatchers.Default as ExecutorCoroutineDispatcher).close()
    override fun close() {
        throw UnsupportedOperationException("Dispatchers.Default cannot be closed")
    }

    override fun toString(): String = "Dispatchers.Default"
}

// The unlimited instance of Dispatchers.IO that utilizes all the threads CoroutineScheduler provides
private object UnlimitedIoScheduler : CoroutineDispatcher(), SoftLimitedParallelism {

    @InternalCoroutinesApi
    override fun dispatchYield(context: CoroutineContext, block: Runnable) {
        DefaultScheduler.dispatchWithContext(block, BlockingContext, true)
    }

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        DefaultScheduler.dispatchWithContext(block, BlockingContext, false)
    }

    @ExperimentalCoroutinesApi
    override fun limitedParallelism(parallelism: Int): CoroutineDispatcher {
        parallelism.checkParallelism()
        if (parallelism >= MAX_POOL_SIZE) return this
        return super.limitedParallelism(parallelism)
    }

    override fun softLimitedParallelism(parallelism: Int): CoroutineDispatcher {
        parallelism.checkParallelism()
        if (parallelism >= MAX_POOL_SIZE) return this
        return SoftLimitedDispatcher(this, parallelism)
    }
}

// Dispatchers.IO
internal object DefaultIoScheduler : ExecutorCoroutineDispatcher(), Executor, SoftLimitedParallelism {

    private val default = UnlimitedIoScheduler.softLimitedParallelism(
        systemProp(
            IO_PARALLELISM_PROPERTY_NAME,
            64.coerceAtLeast(AVAILABLE_PROCESSORS)
        )
    )

    override val executor: Executor
        get() = this

    override fun execute(command: java.lang.Runnable) = dispatch(EmptyCoroutineContext, command)

    @ExperimentalCoroutinesApi
    override fun limitedParallelism(parallelism: Int): CoroutineDispatcher {
        // See documentation to Dispatchers.IO for the rationale
        return UnlimitedIoScheduler.limitedParallelism(parallelism)
    }

    override fun softLimitedParallelism(parallelism: Int): CoroutineDispatcher {
        return UnlimitedIoScheduler.softLimitedParallelism(parallelism)
    }

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        default.dispatch(context, block)
    }

    @InternalCoroutinesApi
    override fun dispatchYield(context: CoroutineContext, block: Runnable) {
        default.dispatchYield(context, block)
    }

    override fun close() {
        error("Cannot be invoked on Dispatchers.IO")
    }

    override fun toString(): String = "Dispatchers.IO"
}

// Instantiated in tests so we can test it in isolation
internal open class SchedulerCoroutineDispatcher(
    private val corePoolSize: Int = CORE_POOL_SIZE,
    private val maxPoolSize: Int = MAX_POOL_SIZE,
    private val idleWorkerKeepAliveNs: Long = IDLE_WORKER_KEEP_ALIVE_NS,
    private val schedulerName: String = "CoroutineScheduler",
) : ExecutorCoroutineDispatcher(), SoftLimitedParallelism {

    override val executor: Executor
        get() = coroutineScheduler

    // This is variable for test purposes, so that we can reinitialize from clean state
    private var coroutineScheduler = createScheduler()

    private fun createScheduler() =
        CoroutineScheduler(corePoolSize, maxPoolSize, idleWorkerKeepAliveNs, schedulerName)

    override fun dispatch(context: CoroutineContext, block: Runnable): Unit = coroutineScheduler.dispatch(block)

    override fun dispatchYield(context: CoroutineContext, block: Runnable): Unit =
        coroutineScheduler.dispatch(block, tailDispatch = true)

    internal fun dispatchWithContext(block: Runnable, context: TaskContext, tailDispatch: Boolean) {
        coroutineScheduler.dispatch(block, context, tailDispatch)
    }

    override fun close() {
        coroutineScheduler.close()
    }

    // fot tests only
    @Synchronized
    internal fun usePrivateScheduler() {
        coroutineScheduler.shutdown(1_000L)
        coroutineScheduler = createScheduler()
    }

    // for tests only
    @Synchronized
    internal fun shutdown(timeout: Long) {
        coroutineScheduler.shutdown(timeout)
    }

    // for tests only
    internal fun restore() = usePrivateScheduler() // recreate scheduler

    override fun softLimitedParallelism(parallelism: Int): CoroutineDispatcher {
        parallelism.checkParallelism()
        if (parallelism >= corePoolSize) return this
        return SoftLimitedDispatcher(this, parallelism)
    }
}
