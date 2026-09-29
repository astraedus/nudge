package com.astraedus.nudge

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineExceptionHandler

/**
 * TEMPORARY DIAGNOSTIC (issue #53). Registered as a process-global
 * `CoroutineExceptionHandler` through
 * `app/src/test/resources/META-INF/services/kotlinx.coroutines.CoroutineExceptionHandler`, so
 * kotlinx calls it for EVERY coroutine exception that reaches `handleCoroutineException` with no
 * handler in its own context.
 *
 * kotlinx's own `ExceptionCollector` (the one that queues the leak) does not swallow the exception
 * when no `runTest` is active -- it just adds it to a static list and returns -- so iteration
 * continues and this handler still sees exactly the leaks we care about.
 *
 * Delete this file and the services file before committing.
 */
class LeakProbeHandler : CoroutineExceptionHandler {

    override val key: CoroutineContext.Key<*> get() = CoroutineExceptionHandler

    override fun handleException(context: CoroutineContext, exception: Throwable) {
        val report = buildString {
            appendLine("=== NUDGE-LEAK-PROBE hit #${counter.incrementAndGet()} ===")
            appendLine("thread: ${Thread.currentThread().name}")
            appendLine("context: $context")
            appendLine("exception: ${exception::class.java.name}: ${exception.message}")
            appendLine("--- exception stack ---")
            append(stackOf(exception))
            appendLine("--- nudge frames visible in ANY live thread ---")
            Thread.getAllStackTraces().forEach { (thread, frames) ->
                frames.filter { it.className.startsWith("com.astraedus.nudge") }
                    .forEach { appendLine("  [${thread.name}] $it") }
            }
            appendLine("=== end NUDGE-LEAK-PROBE hit ===")
        }
        System.err.print(report)
        System.err.flush()
        synchronized(counter) { logFile.appendText(report) }
    }

    private fun stackOf(t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }

    private companion object {
        val counter = AtomicInteger()
        val logFile: File by lazy {
            File(System.getProperty("nudge.leakprobe.out") ?: "leak-probe.log").also {
                it.absoluteFile.parentFile?.mkdirs()
            }
        }
    }
}
