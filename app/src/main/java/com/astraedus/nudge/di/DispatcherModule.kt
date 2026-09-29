package com.astraedus.nudge.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * The off-main-thread dispatcher, as a dependency rather than as a global a class reaches for.
 *
 * ## Why this exists ([#53](https://github.com/astraedus/nudge/issues/53))
 *
 * A `ViewModel` that names `Dispatchers.IO` inside its own flow chain puts part of that chain on a
 * REAL thread pool, outside the `TestScope` of any test that drives it. `runTest` returns without
 * waiting for it (it is not a child of the test job), the test's `@After` calls
 * `Dispatchers.resetMain()`, and in a JVM unit test fork the dispatcher that `resetMain` restores
 * is `MissingMainCoroutineDispatcher`, whose `isDispatchNeeded` **throws**. The pool thread then
 * finishes the work it was already doing, tries to resume its `Dispatchers.Main` continuation, and
 * gets an `IllegalStateException` with no `CoroutineExceptionHandler` anywhere in its context —
 * which `kotlinx-coroutines-test`'s process-global `ExceptionCollector` queues, and which the next
 * `runTest` **anywhere in the fork** then dies of as `UncaughtExceptionsBeforeTest`.
 *
 * That race is only reachable because the dispatcher is hard-coded. Injected, a test hands in its
 * own test dispatcher, the whole chain lives on the test scheduler and the test's own thread, and
 * there is no background worker left to lose the race to. The fix is the parameter, not a cleanup.
 *
 * Production behaviour is unchanged: this provides exactly `Dispatchers.IO`.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Module
@InstallIn(SingletonComponent::class)
object DispatcherModule {

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO
}
