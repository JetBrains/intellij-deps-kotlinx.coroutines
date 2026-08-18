package kotlinx.coroutines.scheduling

import kotlinx.coroutines.testing.*
import org.junit.Test
import java.lang.Runnable
import java.util.concurrent.*
import kotlin.test.*

class CpuParallelismControlTest : TestBase() {

    private fun awaitCondition(timeoutMs: Long = 5_000, intervalMs: Long = 10, condition: () -> Boolean) {
        val deadlineNs = System.nanoTime() + timeoutMs * 1_000_000
        while (!condition()) {
            if (System.nanoTime() >= deadlineNs) {
                assertTrue(condition(), "Condition not met within ${timeoutMs}ms")
                return
            }
            Thread.sleep(intervalMs)
        }
    }

    private fun CoroutineScheduler.liveWorkerThreads(): List<CoroutineScheduler.Worker> =
        Thread.getAllStackTraces().keys.filterIsInstance<CoroutineScheduler.Worker>().filter { it.scheduler === this }

    // CoroutineScheduler.toString() already reports these two figures for diagnostic/logging
    // purposes; parsing it here avoids adding fields to production code purely for test visibility.
    private val CREATED_WORKERS_REGEX = Regex("created workers= (\\d+)")
    private val CPUS_ACQUIRED_REGEX = Regex("CPUs acquired = (-?\\d+)")

    private fun CoroutineScheduler.createdWorkersSnapshot(): Int =
        CREATED_WORKERS_REGEX.find(toString())!!.groupValues[1].toInt()

    private fun CoroutineScheduler.availableCpuPermitsSnapshot(): Int =
        corePoolSize - CPUS_ACQUIRED_REGEX.find(toString())!!.groupValues[1].toInt()

    @Test
    fun testIncreaseCpuParallelismCreatesWorkerFromZero() {
        val corePoolSize = 1
        CoroutineScheduler(corePoolSize, corePoolSize * 5, schedulerName = "IncreaseFromZero").use { scheduler ->
            assertEquals(0, scheduler.createdWorkersSnapshot())
            assertEquals(corePoolSize, scheduler.availableCpuPermitsSnapshot())

            assertTrue(
                scheduler.tryIncreaseCpuParallelism(),
                "tryIncreaseCpuParallelism() should succeed when there is room to grow"
            )

            assertEquals(1, scheduler.createdWorkersSnapshot())
            // The new worker's startup scan transiently holds the freed permit while it looks for
            // a task (there is none), then releases it and parks -- so the permit count only
            // settles at corePoolSize + 1 once that scan finishes, not synchronously here.
            awaitCondition { scheduler.availableCpuPermitsSnapshot() == corePoolSize + 1 }
            awaitCondition { scheduler.liveWorkerThreads().isNotEmpty() }
        }
    }

    @Test
    fun testIncreaseCpuParallelismFailsAfterTermination() {
        val corePoolSize = 1
        val scheduler = CoroutineScheduler(corePoolSize, corePoolSize, schedulerName = "Terminated")
        scheduler.close()
        assertFalse(
            scheduler.tryIncreaseCpuParallelism(),
            "tryIncreaseCpuParallelism() must fail once the scheduler is terminated"
        )
    }

    @Test
    fun testIncreaseCpuParallelismWhenAllPermitsHeld() {
        val corePoolSize = 2
        CoroutineScheduler(corePoolSize, corePoolSize * 2, schedulerName = "IncreaseAllHeld").use { scheduler ->
            assertEquals(corePoolSize, scheduler.availableCpuPermitsSnapshot())

            val started = CountDownLatch(corePoolSize)
            val release = CountDownLatch(1)
            repeat(corePoolSize) {
                scheduler.dispatch(Runnable {
                    started.countDown()
                    release.await()
                })
            }
            try {
                assertTrue(started.await(10, TimeUnit.SECONDS))
                awaitCondition { scheduler.availableCpuPermitsSnapshot() == 0 }

                assertTrue(scheduler.tryIncreaseCpuParallelism())
                awaitCondition { scheduler.availableCpuPermitsSnapshot() == 1 }

                // Prove the increase is a real, reclaimable credit (not just a transient bump): a matching
                // decrease consumes it, and once the busy workers finish, the pool settles exactly back at
                // corePoolSize rather than corePoolSize + 1.
                assertTrue(scheduler.tryDecreaseCpuParallelism())
            } finally {
                release.countDown()
            }

            awaitCondition { scheduler.availableCpuPermitsSnapshot() == corePoolSize }
        }
    }

    @Test
    fun testIncreaseThenDecreaseCpuParallelismRoundTrip() {
        val corePoolSize = 2
        val maxPoolSize = 20
        CoroutineScheduler(corePoolSize, maxPoolSize, schedulerName = "RoundTrip").use { scheduler ->
            assertEquals(corePoolSize, scheduler.availableCpuPermitsSnapshot())

            val n = 3
            repeat(n) { assertTrue(scheduler.tryIncreaseCpuParallelism()) }
            awaitCondition { scheduler.availableCpuPermitsSnapshot() == corePoolSize + n }

            repeat(n) {
                assertTrue(scheduler.tryDecreaseCpuParallelism())
                // Drive a trivial completed non-blocking task so some CPU-permit-holding worker calls
                // tryDecompensateCpu() right after running it.
                val latch = CountDownLatch(1)
                scheduler.dispatch(Runnable { latch.countDown() })
                assertTrue(latch.await(5, TimeUnit.SECONDS))
            }

            awaitCondition { scheduler.availableCpuPermitsSnapshot() == corePoolSize }
        }
    }

    @Test
    fun testDecreaseCpuParallelismCannotGoBelowCorePoolSize() {
        val corePoolSize = 2
        CoroutineScheduler(corePoolSize, corePoolSize, schedulerName = "FloorInvariant").use { scheduler ->
            // No prior increaseCpuParallelism() calls -> zero legitimate headroom, so every one of these
            // should be a no-op, as reported by its own return value.
            repeat(corePoolSize + 3) { assertFalse(scheduler.tryDecreaseCpuParallelism()) }

            // Drive corePoolSize workers to actually acquire and then relinquish their CPU permits, so that
            // any (incorrectly) accepted decompensation requests get a chance to permanently drain permits.
            val started = CountDownLatch(corePoolSize)
            val release = CountDownLatch(1)
            repeat(corePoolSize) {
                scheduler.dispatch(Runnable {
                    started.countDown()
                    release.await()
                })
            }
            assertTrue(started.await(10, TimeUnit.SECONDS))
            release.countDown()

            awaitCondition(timeoutMs = 3_000) { scheduler.availableCpuPermitsSnapshot() == corePoolSize }
            assertEquals(corePoolSize, scheduler.availableCpuPermitsSnapshot())
        }
    }

    @Test
    fun testDecreaseCpuParallelismInPlaceWhenPermitIsFree() {
        val corePoolSize = 2
        CoroutineScheduler(corePoolSize, corePoolSize * 5, schedulerName = "DecreaseInPlace").use { scheduler ->
            assertEquals(corePoolSize, scheduler.availableCpuPermitsSnapshot())

            // Nothing is using the pool, so the permit increaseCpuParallelism() grants here is
            // never claimed by any worker -- it just sits free.
            assertTrue(scheduler.tryIncreaseCpuParallelism())
            awaitCondition { scheduler.availableCpuPermitsSnapshot() == corePoolSize + 1 }

            // With a free permit available, decreaseCpuParallelism() should steal it back via
            // tryAcquireCpuPermit() synchronously, with no need for any worker to complete a task
            // or to go through cpuDecompensationRequests.
            assertTrue(scheduler.tryDecreaseCpuParallelism())

            assertEquals(corePoolSize, scheduler.availableCpuPermitsSnapshot())
        }
    }

    @Test
    @Ignore("Creates up to 1024 native threads; run manually with sufficient resources")
    fun testIncreaseCpuParallelismCannotExceedMaxOutstandingCompensations() {
        val corePoolSize = 1
        val maxPoolSize = CoroutineScheduler.MAX_SUPPORTED_POOL_SIZE
        CoroutineScheduler(corePoolSize, maxPoolSize, schedulerName = "MaxOutstandingCompensations").use { scheduler ->
            repeat(MAX_OUTSTANDING_CPU_COMPENSATIONS) {
                assertTrue(
                    scheduler.tryIncreaseCpuParallelism(),
                    "compensation #$it should be within the configured limit of $MAX_OUTSTANDING_CPU_COMPENSATIONS"
                )
            }

            assertFalse(
                scheduler.tryIncreaseCpuParallelism(),
                "must not allow more than $MAX_OUTSTANDING_CPU_COMPENSATIONS outstanding compensations at once"
            )
        }
    }

    @Test
    fun testShutdownWithUnclaimedOutstandingCompensationDoesNotHang() {
        val corePoolSize = 2
        CoroutineScheduler(corePoolSize, corePoolSize * 2, schedulerName = "ShutdownUnclaimed").use { scheduler ->
            // Leaves outstandingCpuCompensations == 1 with nobody ever having claimed the extra
            // permit -- shutdown() must drain this itself before its availableCpuPermits ==
            // corePoolSize assertion, via decreaseCpuParallelism().
            assertTrue(scheduler.tryIncreaseCpuParallelism())
            awaitCondition { scheduler.availableCpuPermitsSnapshot() == corePoolSize + 1 }

            val shutdownThread = Thread { scheduler.close() }
            shutdownThread.start()
            shutdownThread.join(10_000)
            assertFalse(
                shutdownThread.isAlive,
                "shutdown() did not complete in time -- likely hung draining outstandingCpuCompensations"
            )
        }
    }

    @Test
    fun testCpuPermitCounterDoesNotOverflowAtPackedFieldBoundary() {
        val corePoolSize = CoroutineScheduler.MAX_SUPPORTED_POOL_SIZE

        CoroutineScheduler(
            corePoolSize = corePoolSize,
            maxPoolSize = corePoolSize,
            schedulerName = "PackedCounterOverflow"
        ).use { scheduler ->
            assertEquals(
                corePoolSize,
                scheduler.availableCpuPermitsSnapshot()
            )

            assertFalse(
                scheduler.tryIncreaseCpuParallelism(),
                "An increase that can overflow the packed CPU-permit field must be rejected"
            )
        }
    }

    @Test
    fun testIncreaseCannotExceedMaxPoolSize() {
        val corePoolSize = 2
        val maxPoolSize = 4

        CoroutineScheduler(
            corePoolSize = corePoolSize,
            maxPoolSize = maxPoolSize,
            schedulerName = "MaximumCpuParallelism"
        ).use { scheduler ->
            val availableHeadroom =
                maxPoolSize - corePoolSize

            repeat(availableHeadroom) {
                assertTrue(
                    scheduler.tryIncreaseCpuParallelism(),
                    "Increase #$it should fit within maxPoolSize"
                )
            }

            assertFalse(
                scheduler.tryIncreaseCpuParallelism(),
                "The logical CPU parallelism must not exceed maxPoolSize"
            )

            repeat(availableHeadroom) {
                assertTrue(
                    scheduler.tryDecreaseCpuParallelism()
                )
            }

            assertFalse(
                scheduler.tryDecreaseCpuParallelism(),
                "All outstanding increases should be reclaimed"
            )
        }
    }
}