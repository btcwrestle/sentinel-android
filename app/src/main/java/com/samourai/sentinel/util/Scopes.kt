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
// dataBaseScope deliberately unchanged in #130 - same defect class one line
// down, filed as its own follow-up (see the #130 PR's out-of-scope census).
val dataBaseScope: CoroutineScope = CoroutineScope(context = Dispatchers.IO)+ SupervisorJob()
