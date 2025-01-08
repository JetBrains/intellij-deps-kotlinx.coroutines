# Included IntelliJ-related patches

## Parallelism compensation for `CoroutineDispatcher`s

If `runBlocking` happens to be invoked on a thread from `CoroutineDispatcher`, it may cause a thread starvation problem
(Kotlin#3983). This happens because `runBlocking` does not release an associated computational permit while it parks the
thread. To fix this, a parallelism compensation mechanism is introduced. Some `CoroutineDispatcher`s (such as 
`Dispatchers.Default`, `Dispatchers.IO` and others) support `ParallelismCompensation`, meaning that these dispatchers
can be notified that they should increase parallelism and parallelism limit, or they should decrease it. It is important that these
are only requests and dispatchers are in full control on how and when they need to adjust the effective parallelism.
It also means that the instantaneous parallelism may exceed the current allowed parallelism limit for the given dispatcher.

`runBlockingWithParallelismCompensation` (further abbreviated as `rBWPC`) is introduced as a counterpart of `runBlocking` 
with the following behavioral change. When `rBWPC` decides to park a `CoroutineDispatcher` thread, it first increases the allowed parallelism
limit of the `CoroutineDispatcher`. After the thread unparks, `rBWPC` notifies the dispatcher that the parallelism limit should be lowered back.
A separate function is introduced because parallelism compensation is not always a desirable behavior.

It is easy to see that this behavior cannot be general for `CoroutineDispatcher`s, at least because it breaks the contract
of `LimitedDispatcher` (one that can be acquired via `.limitedParallelism`). It means that parallelism compensation
cannot work for `LimitedDispatcher`, so `runBlockingWithParallelismCompensation` can still cause starvation issues there, but it seems rather 
expected.

Parallelism compensation support is internal and is implemented for `Dispatchers.Default` and `Dispatchers.IO`.
To acquire an analogue of `limitedParallelism` dispatcher which supports parallelism compensation, use 
`IntellijCoroutines.softLimitedParallelism`. Be advised that not every `.limitedParallelism` call can be substituted
with `.softLimitedParallelism`, e.g., `.limitedParallelism(1)` may be used as a synchronization manager and in this case
exceeding the parallelism limit would eliminate this (likely expected) side effect.

### API
- `runBlockingWithParallelismCompensation` - an analogue of `runBlocking` which also compensates parallelism of the
  associated coroutine dispatcher when it decides to park the thread
- `CoroutineDispatcher.softLimitedParallelism` – an analogue of `.limitedParallelism` which supports
  parallelism compensation
