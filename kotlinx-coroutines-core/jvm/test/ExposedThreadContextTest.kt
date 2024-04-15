package kotlinx.coroutines

import kotlinx.coroutines.flow.*
import kotlinx.coroutines.internal.intellij.*
import kotlinx.coroutines.testing.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.*

class ExposedThreadContextTest : TestBase() {
    @Test
    fun runBlocking() = runBlocking {
        assertContextEqualUnderResumption()
    }

    class C : AbstractCoroutineContextElement(C) {
        companion object Key : CoroutineContext.Key<C>
    }

    @Test
    fun withContext() = runBlocking {
        val element = C()
        withContext(element) {
            // the context changed
            assertContextEqualUnderResumption()
            withContext(element) {
                // checking fast path -- the context effectively does not change
                assertContextEqualUnderResumption()
            }
        }
    }

    @Test
    fun launch() = runBlocking {
        for (i in 0..10) {
            launch {
                assertContextEqualUnderResumption()
            }
        }
    }

    @Test
    fun coroutineScope() = runBlocking {
        coroutineScope {
            assertContextEqualUnderResumption()
            coroutineScope {
                assertContextEqualUnderResumption()
            }
        }
    }

    @Test
    fun supervisorScope() = runBlocking {
        supervisorScope {
            assertContextEqualUnderResumption()
            supervisorScope {
                assertContextEqualUnderResumption()
            }
        }
    }

    @Test
    fun testFlow() = runBlocking {
        coroutineScope {
            val flowVar = flow {
                    repeat(10) {
                        // Flow has encapsulated context
                        assertContextEqualUnderResumption()
                        emit(it)
                    }
                }.flowOn(C())
            flowVar.collect {
                assertContextEqualUnderResumption()
            }
        }
    }

    private suspend fun assertContextEqualUnderResumption() {
        // thread context should survive dispatches
        assertContextsEqual()
        yield()
        assertContextsEqual()
    }


    private suspend fun assertContextsEqual() {
        val coroutineContext = currentCoroutineContext()
        val threadContext = IntellijCoroutines.currentThreadCoroutineContext()
        assertEquals(coroutineContext, threadContext)
    }
}