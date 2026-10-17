package com.samourai.sentinel.util

import kotlinx.coroutines.*
import timber.log.Timber

/**
 * sentinel-android
 *
 * @author Sarath
 */

// #130: a launch child dying uncaught on a SupervisorJob still reaches the
// thread's uncaughtExceptionHandler - process death (witnessed: the #87 NPE
// in saveUtxos mid-erase ran through exactly this path on apiScope). This
// handler is terminal containment only: the failing launch still completes
// exceptionally, siblings are untouched (SupervisorJob), and
// invokeOnCompletion consumers still see the real cause. Normal scope
// teardown (CancellationException) does not land here. async() children are
// NOT covered - their failures surface at await() (#103's path, closed at
// the consumer by #126).
val apiExceptionHandler = CoroutineExceptionHandler { _, t ->
    Timber.e(t, "apiScope: uncaught launch failure contained")
}
val apiJob = SupervisorJob()
val apiScope: CoroutineScope = CoroutineScope(context = Dispatchers.IO) + apiJob + apiExceptionHandler
// #149: same containment for dataBaseScope - the #130 out-of-scope follow-up,
// resolved. Writers (census on #149's thread): SentinelState label rescope,
// FeeRepository fee writes, CollectionRepository sync write. Same semantics
// as apiExceptionHandler above - terminal containment, siblings untouched
// (SupervisorJob), CancellationException passes through teardown. A call-site
// launch(Dispatchers.IO) folds the dispatcher into the child and keeps this
// handler.
val dataBaseExceptionHandler = CoroutineExceptionHandler { _, t ->
    Timber.e(t, "dataBaseScope: uncaught launch failure contained")
}
val dataBaseJob = SupervisorJob()
val dataBaseScope: CoroutineScope = CoroutineScope(context = Dispatchers.IO) + dataBaseJob + dataBaseExceptionHandler
