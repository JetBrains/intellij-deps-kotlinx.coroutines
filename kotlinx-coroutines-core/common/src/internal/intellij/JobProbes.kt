package kotlinx.coroutines.internal.intellij

import kotlinx.coroutines.Job
import kotlin.coroutines.CoroutineContext

internal fun probeJobCreated(job: Job, parentContext: CoroutineContext): Unit {}

internal fun probeJobCompleted(job: Job): Unit {}

internal fun probeJobCancelled(job: Job): Unit {}