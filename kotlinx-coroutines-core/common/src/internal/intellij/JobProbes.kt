package kotlinx.coroutines.internal.intellij

import kotlinx.coroutines.Job

/**
 * Methods declared here can be used to track Job states by observability/profiling tools (similarly to `DebugProbes`).
 */

/**
 * Called whenever a job is created.
 * Note: when this probe is called, the job itself is only in the process of initialization,
 * so it should only be used for instance registration rather than state querying.
 *
 * This probe covers only job instances that inherit from [kotlinx.coroutines.JobSupport].
 */
internal fun probeJobCreated(job: Job): Unit {}

/**
 * Called whenever a job transitions to a completed state. TODO likely imprecise description
 *
 * This probe covers only job instances that inherit from [kotlinx.coroutines.JobSupport].
 */
internal fun probeJobCompleted(job: Job): Unit {}

/**
 * Called whenever a job transitions to a cancelled state. TODO likely imprecise description
 *
 * This probe covers only job instances that inherit from [kotlinx.coroutines.JobSupport].
 */
internal fun probeJobCancelled(job: Job): Unit {}