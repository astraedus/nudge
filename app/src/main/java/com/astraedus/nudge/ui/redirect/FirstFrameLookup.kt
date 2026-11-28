package com.astraedus.nudge.ui.redirect

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull

/**
 * How long the block screen's first frame may wait for the redirect app. Normally the lookup is a
 * few DataStore/Room reads and one app's label and icon, done long before this; it is only reached
 * when the lookup has to list every installed app (a rule's group checks a category and the
 * installed-app cache is cold) on a slow phone. Far below the platform's 5 s input-dispatch ANR.
 */
internal const val REDIRECT_FIRST_FRAME_BUDGET_MS = 300L

/**
 * A lookup the block screen would like to have before its first frame, but must never wait on for
 * long: it starts on [dispatcher] (never the main thread) the moment this is built, and the first
 * frame takes what is ready within a budget ([awaitWithin]). A late answer is still delivered
 * ([await]) to whoever fills it in afterwards.
 *
 * Why a [Deferred] and not `withTimeoutOrNull { lookup() }`: the slow part is a blocking
 * PackageManager binder call inside `withContext(Dispatchers.IO)`, which cancellation cannot
 * interrupt, and `withContext` does not return until its block does. A timeout around the CALL
 * therefore fires on time and still waits the whole call out. Waiting on [Deferred.await] instead
 * is cancellable, so the budget holds however long the lookup runs. (Two Play ANRs, moto g32,
 * 1.31.0 and 1.31.2: the main thread parked in the block screen's `runBlocking` while a worker
 * listed the installed apps for the redirect app.)
 */
internal class FirstFrameLookup<T>(
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher,
    lookup: suspend () -> T
) {
    /** The answer, distinguishing "ready, and it is null" from "not ready yet". */
    data class Ready<T>(val value: T)

    private val deferred: Deferred<T> = scope.async(dispatcher) { lookup() }

    /** The answer if it arrives within [budgetMs], otherwise null (not ready: fill it in later). */
    suspend fun awaitWithin(budgetMs: Long): Ready<T>? =
        withTimeoutOrNull(budgetMs) { Ready(deferred.await()) }

    /** The answer, however long it takes. */
    suspend fun await(): T = deferred.await()
}
