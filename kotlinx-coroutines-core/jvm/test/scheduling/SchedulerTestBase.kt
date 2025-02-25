@file:Suppress("UNUSED_VARIABLE")

package kotlinx.coroutines.scheduling

import kotlinx.coroutines.testing.*
import kotlinx.coroutines.*
import kotlinx.coroutines.internal.*
import org.junit.*
import kotlin.coroutines.*
import kotlin.test.*

abstract class SchedulerTestBase : TestBase() {
    companion object {
        val CORES_COUNT = AVAILABLE_PROCESSORS

        /**
         * Asserts that [expectedThreadsCount] pool worker threads were created.
         * Note that 'created' doesn't mean 'exists' because pool supports dynamic shrinking
         */
        fun checkPoolThreadsCreated(expectedThreadsCount: Int = CORES_COUNT) {
            val threadsCount = maxSequenceNumber()!!
            assertEquals(expectedThreadsCount, threadsCount, "Expected $expectedThreadsCount pool threads, but has $threadsCount")
        }

        /**
         * Asserts that any number of pool worker threads in [range] were created.
         * Note that 'created' doesn't mean 'exists' because pool supports dynamic shrinking
         */
        fun checkPoolThreadsCreated(range: IntRange, base: Int = CORES_COUNT) {
            val maxSequenceNumber = maxSequenceNumber()!!
            val r = (range.first)..(range.last + base)
            assertTrue(
                maxSequenceNumber in r,
                "Expected pool threads to be in interval $r, but has $maxSequenceNumber"
            )
        }

        private fun maxSequenceNumber(): Int? {
            return Thread.getAllStackTraces().keys.asSequence().filter { it is CoroutineScheduler.Worker }
                .map { sequenceNumber(it.name) }.maxOrNull()
        }

        private fun sequenceNumber(threadName: String): Int {
            val suffix = threadName.substring(threadName.lastIndexOf("-") + 1)
            val separatorIndex = suffix.indexOf(' ')
            if (separatorIndex == -1) {
                return suffix.toInt()
            }

            return suffix.substring(0, separatorIndex).toInt()
        }

        suspend fun Iterable<Job>.joinAll() = forEach { it.join() }
    }

    protected var corePoolSize = CORES_COUNT
    protected var maxPoolSize = 1024
    protected var idleWorkerKeepAliveNs = IDLE_WORKER_KEEP_ALIVE_NS

    private var _dispatcher: SchedulerCoroutineDispatcher? = null
    protected val dispatcher: CoroutineDispatcher
        get() {
            if (_dispatcher == null) {
                _dispatcher = SchedulerCoroutineDispatcher(
                    corePoolSize,
                    maxPoolSize,
                    idleWorkerKeepAliveNs
                )
            }

            return _dispatcher!!
        }

    protected var blockingDispatcher = lazy {
        blockingDispatcher(1000)
    }

    protected var softBlockingDispatcher = lazy {
        softBlockingDispatcher(1000)
    }

    protected fun blockingDispatcher(parallelism: Int): CoroutineDispatcher {
        val intitialize = dispatcher
        return _dispatcher!!.blocking(parallelism)
    }

    protected fun softBlockingDispatcher(parallelism: Int): CoroutineDispatcher {
        val intitialize = dispatcher
        return _dispatcher!!.softBlocking(parallelism)
    }

    protected fun view(parallelism: Int): CoroutineDispatcher {
        val intitialize = dispatcher
        return _dispatcher!!.limitedParallelism(parallelism)
    }

    @After
    fun after() {
        runBlocking {
            withTimeout(5_000) {
                _dispatcher?.close()
            }
        }
    }
}

/**
 * Implementation note:
 * Our [Dispatcher.IO] is a [limitedParallelism][CoroutineDispatcher.limitedParallelism] dispatcher
 * on top of unbounded scheduler. We want to test this scenario, but on top of non-singleton
 * scheduler so we can control the number of threads, thus this method.
 */
internal fun SchedulerCoroutineDispatcher.blocking(parallelism: Int = 16): CoroutineDispatcher {
    return object : CoroutineDispatcher() {

        @InternalCoroutinesApi
        override fun dispatchYield(context: CoroutineContext, block: Runnable) {
            this@blocking.dispatchWithContext(block, BlockingContext, true)
        }

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            this@blocking.dispatchWithContext(block, BlockingContext, false)
        }
    }.limitedParallelism(parallelism)
}

internal fun SchedulerCoroutineDispatcher.softBlocking(parallelism: Int = 16): CoroutineDispatcher {
    return object : CoroutineDispatcher(), SoftLimitedParallelism {

        @InternalCoroutinesApi
        override fun dispatchYield(context: CoroutineContext, block: Runnable) {
            this@softBlocking.dispatchWithContext(block, BlockingContext, true)
        }

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            this@softBlocking.dispatchWithContext(block, BlockingContext, false)
        }

        override fun softLimitedParallelism(parallelism: Int, name: String?): CoroutineDispatcher {
            return this@softBlocking.softLimitedParallelism(parallelism, name)
        }
    }.softLimitedParallelism(parallelism, null)
}
