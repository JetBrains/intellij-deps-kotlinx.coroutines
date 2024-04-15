/**
 * A special file that contains IntelliJ-related functions
 */
package kotlinx.coroutines.internal.intellij

import kotlinx.coroutines.InternalCoroutinesApi
import kotlin.coroutines.*

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
}
