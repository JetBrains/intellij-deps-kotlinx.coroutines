package kotlinx.coroutines.internal

import kotlinx.atomicfu.*
import kotlinx.coroutines.*
import kotlinx.coroutines.scheduling.ParallelismCompensation
import kotlin.coroutines.*

/**
 * Introduced as part of IntelliJ patches.
 *
 * CoroutineDispatchers may optionally implement this interface to declare an ability to construct [SoftLimitedDispatcher]
 * on top of themselves. This is not possible in general case, because the worker of the underlying dispatcher must
 * implement [ParallelismCompensation] and properly propagate such requests to the task it is running.
 */
internal interface SoftLimitedParallelism {
    fun softLimitedParallelism(parallelism: Int, name: String?): CoroutineDispatcher

    /**
     * Attempts to adjust the parallelism limit by [parallelismDelta].
     *
     * It is not guaranteed that the new parallelism limit will be equal to `oldParallelism + parallelismDelta`:
     * the adjustment may be applied only partially, or not at all.
     *
     * @param parallelismDelta a positive or negative value; it is recommended to use +1 and -1.
     * @return the actual adjustment that was made.
     */
    fun tryAdjustParallelism(parallelismDelta: Int): Int
}

/**
 * Introduced as part of IntelliJ patches.
 */
internal fun CoroutineDispatcher.softLimitedParallelism(parallelism: Int, name: String?): CoroutineDispatcher {
    if (this is SoftLimitedParallelism) {
        return this.softLimitedParallelism(parallelism, name)
    }
    // SoftLimitedDispatcher cannot be used on top of LimitedDispatcher, because the latter doesn't propagate compensation requests
    throw UnsupportedOperationException("CoroutineDispatcher.softLimitedParallelism cannot be applied to $this")
}

internal fun CoroutineDispatcher.tryAdjustParallelism(parallelismDelta: Int): Int {
    if (this is SoftLimitedParallelism) {
        return this.tryAdjustParallelism(parallelismDelta)
    }
    throw UnsupportedOperationException("CoroutineDispatcher.tryAdjustParallelism cannot be applied to $this")
}
/**
 * Introduced as part of IntelliJ patches.
 *
 * Shamelessly copy-pasted from [LimitedDispatcher], but [ParallelismCompensation] is
 * implemented for [Worker] to allow compensation.
 *
 * [ParallelismCompensation] breaks the contract of [LimitedDispatcher] so a separate class is made to implement a
 * dispatcher that mostly behaves as limited, but can temporarily increase parallelism if necessary.
 */
internal class SoftLimitedDispatcher(
    private val dispatcher: CoroutineDispatcher,
    parallelism: Int,
    private val name: String?,
    private val hardParallelism: Int = Int.MAX_VALUE
) : CoroutineDispatcher(), Delay by (dispatcher as? Delay ?: DefaultDelay), SoftLimitedParallelism {
    private val initialParallelism = parallelism
    private var additionalSoftParallelism = 0
    // `parallelism limit - runningWorkers`; may be < 0 if decompensation is expected
    private val availablePermits = atomic(parallelism)

    private val queue = LockFreeTaskQueue<Runnable>(singleConsumer = false)

    private val workerAllocationLock = SynchronizedObject()
    private val adjustmentLock = SynchronizedObject()

    override fun limitedParallelism(parallelism: Int, name: String?): CoroutineDispatcher {
        return super.limitedParallelism(parallelism, name)
    }

    override fun softLimitedParallelism(parallelism: Int, name: String?): CoroutineDispatcher {
        parallelism.checkParallelism()
        if (parallelism >= initialParallelism) return namedOrThis(name)
        return SoftLimitedDispatcher(this, parallelism, name, hardParallelism)
    }

    override fun tryAdjustParallelism(parallelismDelta: Int): Int = synchronized(adjustmentLock) {
        // totalParallelism doesn't detect if parallelism was compensated for a specific thread
        val targetTotalParallelism =
            (initialParallelism.toLong() + additionalSoftParallelism + parallelismDelta)
                .coerceIn(initialParallelism.toLong(), hardParallelism.toLong())
        val actualDelta = (targetTotalParallelism - initialParallelism - additionalSoftParallelism).toInt()
        if (actualDelta == 0) {
            return 0
        }
        additionalSoftParallelism += actualDelta
        availablePermits.addAndGet(actualDelta)

        // we need to signal scheduler that new worker can be allocated and can take tasks
        // instead of exposing signaling API, let's just dispatch empty task
        // It doesn't make sense to wake up more workers than there is tasks in queue
        repeat(actualDelta.coerceIn(0, queue.size)) {
            dispatch(EmptyCoroutineContext, { })
        }
        return actualDelta
    }

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        dispatchInternal(block) { worker ->
            dispatcher.safeDispatch(this, worker)
        }
    }

    @InternalCoroutinesApi
    override fun dispatchYield(context: CoroutineContext, block: Runnable) {
        dispatchInternal(block) { worker ->
            dispatcher.dispatchYield(this, worker)
        }
    }

    /**
     * Tries to dispatch the given [block].
     * If there are not enough workers, it starts a new one via [startWorker].
     */
    private inline fun dispatchInternal(block: Runnable, startWorker: (Worker) -> Unit) {
        queue.addLast(block)
        if (availablePermits.value <= 0) return
        if (!tryAllocateWorker()) return
        val task = obtainTaskOrDeallocateWorker() ?: return
        startWorker(Worker(task))
    }

    /**
     * Tries to obtain the permit to start a new worker.
     */
    private fun tryAllocateWorker(): Boolean {
        synchronized(workerAllocationLock) {
            val permits = availablePermits.value
            if (permits <= 0) return false
            return availablePermits.compareAndSet(permits, permits - 1)
        }
    }

    /**
     * Obtains the next task from the queue, or logically deallocates the worker if the queue is empty.
     */
    private fun obtainTaskOrDeallocateWorker(): Runnable? {
        val permits = availablePermits.value
        if (permits < 0) { // decompensation
            if (availablePermits.compareAndSet(permits, permits + 1)) {
                return null
            }
        }
        while (true) {
            when (val nextTask = queue.removeFirstOrNull()) {
                null -> synchronized(workerAllocationLock) {
                    availablePermits.incrementAndGet()
                    if (queue.size == 0) return null
                    availablePermits.decrementAndGet()
                }
                else -> return nextTask
            }
        }
    }

    override fun toString() = name ?: "$dispatcher.softLimitedParallelism($initialParallelism)"

    /**
     * Every running Worker holds a permit
     */
    private inner class Worker(private var currentTask: Runnable) : Runnable, ParallelismCompensation {
        override fun run() {
            var fairnessCounter = 0
            while (true) {
                try {
                    currentTask.run()
                } catch (e: Throwable) {
                    handleCoroutineException(EmptyCoroutineContext, e)
                }
                currentTask = obtainTaskOrDeallocateWorker() ?: return
                // 16 is our out-of-thin-air constant to emulate fairness. Used in JS dispatchers as well
                if (++fairnessCounter >= 16 && dispatcher.safeIsDispatchNeeded(this@SoftLimitedDispatcher)) {
                    // Do "yield" to let other views execute their runnable as well
                    // Note that we do not decrement 'runningWorkers' as we are still committed to our part of work
                    dispatcher.safeDispatch(this@SoftLimitedDispatcher, this)
                    return
                }
            }
        }

        override fun increaseParallelismAndLimit() {
            val newTask = obtainTaskOrDeallocateWorker() // either increases the number of permits or we launch a new worker (which holds a permit)
            if (newTask != null) {
                dispatcher.safeDispatch(this@SoftLimitedDispatcher, Worker(newTask))
            }
            (currentTask as? ParallelismCompensation)?.increaseParallelismAndLimit()
        }

        override fun decreaseParallelismLimit() {
            try {
                (currentTask as? ParallelismCompensation)?.decreaseParallelismLimit()
            } finally {
                availablePermits.decrementAndGet()
            }
        }
    }
}