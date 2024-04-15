# Included IntelliJ-related patches

## Introspection of `coroutineContext`

### Description: 
To support the threading framework of IntelliJ, 
we need to cooperate with kotlin coroutines better than they allow it by default.

One example of this cooperation is an ability to get coroutine context from non-suspending places.
Essentially, we put the coroutine context into a thread local variable on every coroutine resumption, 
which allows us to read necessary information without a significant change in semantics. 
This change has a mild performance penalty, namely, modification of a thread local variable. 
However, coroutines themselves use thread local states via `ThreadLocalContextElement`, which hints that 
one more thread local variable would not harm.

### API:

We provide a single method `kotlinx.coroutines.internal.intellij.IntellijCoroutines.currentThreadCoroutineContext`.
The invariant is that the result of this method is always equal to `coroutineContext` in suspending environment, 
and it does not change during the non-suspending execution within the same thread.

