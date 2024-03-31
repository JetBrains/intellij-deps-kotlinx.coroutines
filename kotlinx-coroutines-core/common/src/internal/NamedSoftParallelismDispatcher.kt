package kotlinx.coroutines.internal

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.DefaultDelay
import kotlinx.coroutines.Delay
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Runnable
import kotlin.coroutines.CoroutineContext

/**
 * Copy of [NamedDispatcher] for SoftParallelism hierarchy of dispatchers
 */
internal class NamedSoftParallelismDispatcher<D>(
    private val dispatcher: D,
    private val name: String
) : CoroutineDispatcher(), SoftLimitedParallelism, Delay by (dispatcher as? Delay ?: DefaultDelay)
    where D : CoroutineDispatcher, D : SoftLimitedParallelism
{

    override fun isDispatchNeeded(context: CoroutineContext): Boolean = dispatcher.isDispatchNeeded(context)

    override fun dispatch(context: CoroutineContext, block: Runnable) = dispatcher.dispatch(context, block)

    @InternalCoroutinesApi
    override fun dispatchYield(context: CoroutineContext, block: Runnable) = dispatcher.dispatchYield(context, block)

    override fun toString(): String {
        return name
    }

    override fun softLimitedParallelism(
        parallelism: Int,
        name: String?
    ): CoroutineDispatcher {
        // behaves like NamedDispatcher does with LimitedDispatcher
        parallelism.checkParallelism()
        return SoftLimitedDispatcher(this, parallelism, name)
    }
}