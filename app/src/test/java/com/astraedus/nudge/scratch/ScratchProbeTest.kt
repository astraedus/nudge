package com.astraedus.nudge.scratch

import android.content.Context
import android.graphics.Rect
import com.astraedus.nudge.service.AwarenessOverlayWindow
import io.mockk.mockk
import org.junit.Test

class ScratchProbeTest {
    @Test
    fun probe() {
        try {
            val r = Rect()
            r.left = 1; r.top = 2; r.right = 3; r.bottom = 4
            println("PROBE rect-ctor OK ${r.left},${r.top},${r.right},${r.bottom}")
        } catch (e: Throwable) { println("PROBE rect-ctor FAIL $e") }
        try {
            println("PROBE rect-isEmpty ${Rect().isEmpty}")
        } catch (e: Throwable) { println("PROBE rect-isEmpty FAIL $e") }
        try {
            val v = AwarenessOverlayWindow.Container(mockk<Context>(relaxed = true))
            println("PROBE view-ctor OK $v")
        } catch (e: Throwable) { println("PROBE view-ctor FAIL $e") }
        try {
            val h = android.os.Handler(android.os.Looper.getMainLooper())
            println("PROBE looper OK $h")
        } catch (e: Throwable) { println("PROBE looper FAIL $e") }
    }
}
