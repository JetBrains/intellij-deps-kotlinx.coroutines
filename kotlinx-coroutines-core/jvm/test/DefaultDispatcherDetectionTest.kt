import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.testing.TestBase
import kotlinx.coroutines.withContext
import org.junit.Test

class DefaultDispatcherDetectionTest : TestBase() {
    companion object {
        const val FRAME_NAME = "runDefaultDispatcherTask"
    }

    @Test
    fun `default dispatcher has a specific stacktrace in dump`() {
        runBlocking {
            withContext(Dispatchers.Default) {
                checkContainsMarker()
            }
        }
    }

    @Test
    fun `IO dispatcher does not contain the specific stacktrace`() {
        runBlocking {
            withContext(Dispatchers.IO) {
                checkDoesNotContainMarker()
            }
        }
    }

    @Test
    fun `IO dispatcher within Default dispatcher does not contain the specific stacktrace`() {
        runBlocking {
            withContext(Dispatchers.Default) {
                withContext(Dispatchers.IO) {
                    checkDoesNotContainMarker()
                }
            }
        }
    }

    @Test
    fun `Default dispatcher within IO dispatcher does not contain the specific stacktrace`() {
        runBlocking {
            withContext(Dispatchers.IO) {
                withContext(Dispatchers.Default) {
                    checkContainsMarker()
                }
            }
        }
    }

    @Test
    fun `Limited parallelism of Default dispatcher does contain the specific stacktrace`() {
        runBlocking {
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                checkContainsMarker()
            }
        }
    }

    @Test
    fun `Limited parallelism of IO dispatcher does not contain the specific stacktrace`() {
        runBlocking {
            withContext(Dispatchers.IO.limitedParallelism(1)) {
                checkDoesNotContainMarker()
            }
        }
    }

    private fun checkContainsMarker() {
        val trace = Throwable().stackTraceToString()
        check(trace.contains(FRAME_NAME)) {
            "Expected `$FRAME_NAME` in trace:\n$trace"
        }
    }

    private fun checkDoesNotContainMarker() {
        val trace = Throwable().stackTraceToString()
        check(!trace.contains(FRAME_NAME)) {
            "Did not expect `$FRAME_NAME` in trace:\n$trace"
        }
    }
}