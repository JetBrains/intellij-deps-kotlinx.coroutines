package kotlinx.coroutines.internal.intellij

import kotlinx.coroutines.Job

internal fun probeJobCreated(job: Job): Unit {}

internal fun probeJobCompleted(job: Job): Unit {}

internal fun probeJobCancelled(job: Job): Unit {}