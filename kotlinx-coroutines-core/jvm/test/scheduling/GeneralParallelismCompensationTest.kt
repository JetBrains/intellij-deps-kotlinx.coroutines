package kotlinx.coroutines.scheduling

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.internal.intellij.IntellijCoroutines
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.util.concurrent.CountDownLatch
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class GeneralParallelismCompensationTest : SchedulerTestBase() {

    @Test
    fun `soundness - parallelism compensation ensures progress of operations`() {
        val operationsCount = CORE_POOL_SIZE * 10
        val barrier = CountDownLatch(operationsCount)
        runBlocking(Dispatchers.Default) {
            repeat(operationsCount) {
                launch {
                    IntellijCoroutines.runAndCompensateParallelism(10.milliseconds) {
                        barrier.countDown()
                        barrier.await()
                    }
                }
            }
        }
    }

    @Test
    fun `laziness - parallelism compensation does not do anything before deadline`() {
        val operationsCount = CORE_POOL_SIZE * 3
        val barrier = CountDownLatch(operationsCount)
        runBlocking(Dispatchers.Default) {
            launch(Dispatchers.IO) {
                delay(1.seconds)
                assertTrue(barrier.count == CORE_POOL_SIZE.toLong() * 2)
                delay(2.seconds)
                assertTrue(barrier.count == CORE_POOL_SIZE.toLong())
                delay(2.seconds)
                assertTrue(barrier.count == 0L)
            }
            repeat(operationsCount) {
                launch {
                    IntellijCoroutines.runAndCompensateParallelism(2.seconds) {
                        barrier.countDown()
                        barrier.await()
                    }
                }
            }
        }
    }
}