package com.drifly.app

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.*
import kotlin.random.Random

/**
 * Adversarial Empirical Stress Test Suite for Milestone 1 (DriFly Overlay Touch Math).
 * 
 * Conducts stress testing across:
 * 1. Screen densities: 0.75, 1.0, 1.33, 1.5, 2.0, 2.625, 3.0, 4.0.
 * 2. Boundary precision (floating-point epsilon, cardinal & diagonal angles, fuzzing).
 * 3. Drag vs Tap threshold (micro-movements < 10dp vs genuine movements >= 10dp, diagonal vs axis-aligned).
 * 4. Debounce and single-shot protection (0ms, 2999ms, 3000ms, 3001ms boundary timings & high-frequency load).
 * 5. High-concurrency race condition testing on accept dispatch.
 */
class OverlayTouchChallengerStressTest {

    private val targetDensities = floatArrayOf(0.75f, 1.0f, 1.33f, 1.5f, 2.0f, 2.625f, 3.0f, 4.0f)

    // ----------------------------------------------------------------------------------
    // 1. Floating-Point Precision on the 52 dp exclusion boundary across screen densities
    // ----------------------------------------------------------------------------------

    @Test
    fun testExclusionBoundary_AcrossAllTargetDensities_CardinalDirections() {
        val centerX = 400.0f
        val centerY = 250.0f
        val epsilon = 0.05f // Sub-pixel delta

        for (density in targetDensities) {
            val radiusPx = 52.0f * density

            // Exact center must always be inside
            assertTrue(
                "Center must be inside exclusion zone for density $density",
                OverlayTouchMath.isTouchInExclusionZone(centerX, centerY, centerX, centerY, density)
            )

            // Test 4 cardinal directions (Right, Left, Down, Up)
            val angles = doubleArrayOf(0.0, PI / 2.0, PI, 3.0 * PI / 2.0)
            for (angle in angles) {
                val cosA = cos(angle).toFloat()
                val sinA = sin(angle).toFloat()

                // Point strictly INSIDE: (radiusPx - epsilon)
                val inX = centerX + (radiusPx - epsilon) * cosA
                val inY = centerY + (radiusPx - epsilon) * sinA
                assertTrue(
                    "Point at (R - eps) angle=$angle density=$density must be INSIDE",
                    OverlayTouchMath.isTouchInExclusionZone(inX, inY, centerX, centerY, density)
                )

                // Point EXACTLY at radius: radiusPx
                val exactX = centerX + radiusPx * cosA
                val exactY = centerY + radiusPx * sinA
                assertTrue(
                    "Point at EXACT R angle=$angle density=$density must be INSIDE",
                    OverlayTouchMath.isTouchInExclusionZone(exactX, exactY, centerX, centerY, density)
                )

                // Point strictly OUTSIDE: (radiusPx + epsilon)
                val outX = centerX + (radiusPx + epsilon) * cosA
                val outY = centerY + (radiusPx + epsilon) * sinA
                assertFalse(
                    "Point at (R + eps) angle=$angle density=$density must be OUTSIDE",
                    OverlayTouchMath.isTouchInExclusionZone(outX, outY, centerX, centerY, density)
                )
            }
        }
    }

    @Test
    fun testExclusionBoundary_DiagonalPrecision_AcrossDensities() {
        val centerX = 500.0f
        val centerY = 500.0f
        val epsilon = 0.08f // Sub-pixel delta accounting for sqrt(2) floating-point variance

        for (density in targetDensities) {
            val radiusPx = 52.0f * density

            // Test 4 diagonal angles (45°, 135°, 225°, 315°)
            val diagonalAngles = doubleArrayOf(PI / 4.0, 3.0 * PI / 4.0, 5.0 * PI / 4.0, 7.0 * PI / 4.0)
            for (angle in diagonalAngles) {
                val cosA = cos(angle).toFloat()
                val sinA = sin(angle).toFloat()

                // Inside
                val inX = centerX + (radiusPx - epsilon) * cosA
                val inY = centerY + (radiusPx - epsilon) * sinA
                assertTrue(
                    "Diagonal inside point angle=$angle density=$density must be INSIDE",
                    OverlayTouchMath.isTouchInExclusionZone(inX, inY, centerX, centerY, density)
                )

                // Outside
                val outX = centerX + (radiusPx + epsilon) * cosA
                val outY = centerY + (radiusPx + epsilon) * sinA
                assertFalse(
                    "Diagonal outside point angle=$angle density=$density must be OUTSIDE",
                    OverlayTouchMath.isTouchInExclusionZone(outX, outY, centerX, centerY, density)
                )
            }
        }
    }

    @Test
    fun testExclusionBoundary_Fuzzing10kRandomAngles() {
        val centerX = 320.0f
        val centerY = 180.0f
        val random = Random(42)

        for (density in targetDensities) {
            val radiusPx = 52.0f * density
            for (i in 0 until 1250) {
                val angle = random.nextDouble(0.0, 2.0 * PI)
                val cosA = cos(angle).toFloat()
                val sinA = sin(angle).toFloat()

                // Test points just inside the boundary
                val inDist = radiusPx - 0.1f
                val inX = centerX + inDist * cosA
                val inY = centerY + inDist * sinA
                assertTrue(
                    "Fuzz inside iteration $i density=$density failed",
                    OverlayTouchMath.isTouchInExclusionZone(inX, inY, centerX, centerY, density)
                )

                // Test points just outside the boundary
                val outDist = radiusPx + 0.1f
                val outX = centerX + outDist * cosA
                val outY = centerY + outDist * sinA
                assertFalse(
                    "Fuzz outside iteration $i density=$density failed",
                    OverlayTouchMath.isTouchInExclusionZone(outX, outY, centerX, centerY, density)
                )
            }
        }
    }

    // ----------------------------------------------------------------------------------
    // 2. Touch Drag vs Tap Threshold (< 10dp micro-movements vs >= 10dp genuine movements)
    // ----------------------------------------------------------------------------------

    @Test
    fun testDragDetection_MicroMovementsUnder10dp_AcrossDensities() {
        val startX = 150f
        val startY = 250f

        for (density in targetDensities) {
            // Test 0 dp (static tap)
            assertFalse(
                "0 dp movement must NOT be drag for density $density",
                OverlayTouchMath.isDrag(startX, startY, startX, startY, density)
            )

            // Test 1.0 dp micro-jitter
            assertFalse(
                "1 dp jitter must NOT be drag for density $density",
                OverlayTouchMath.isDrag(startX, startY, startX + 1f * density, startY, density)
            )

            // Test 5.0 dp micro-movement
            assertFalse(
                "5 dp movement must NOT be drag for density $density",
                OverlayTouchMath.isDrag(startX, startY, startX, startY + 5f * density, density)
            )

            // Test 9.9 dp micro-movement (just below 10 dp threshold)
            assertFalse(
                "9.9 dp movement must NOT be drag for density $density",
                OverlayTouchMath.isDrag(startX, startY, startX + 9.9f * density, startY, density)
            )

            // Test 9.99 dp micro-movement
            assertFalse(
                "9.99 dp movement must NOT be drag for density $density",
                OverlayTouchMath.isDrag(startX, startY, startX, startY + 9.99f * density, density)
            )
        }
    }

    @Test
    fun testDragDetection_GenuineMovementsExceeding10dp_AcrossDensities() {
        val startX = 200f
        val startY = 300f

        for (density in targetDensities) {
            // 10.01 dp (just above 10 dp threshold)
            assertTrue(
                "10.01 dp horizontal movement must be drag for density $density",
                OverlayTouchMath.isDrag(startX, startY, startX + 10.01f * density, startY, density)
            )

            assertTrue(
                "10.01 dp vertical movement must be drag for density $density",
                OverlayTouchMath.isDrag(startX, startY, startX, startY + 10.01f * density, density)
            )

            // 15 dp movement
            assertTrue(
                "15 dp horizontal movement must be drag for density $density",
                OverlayTouchMath.isDrag(startX, startY, startX + 15f * density, startY, density)
            )

            // Negative direction movements (left / up)
            assertTrue(
                "Negative horizontal drag (-15 dp) must be drag for density $density",
                OverlayTouchMath.isDrag(startX, startY, startX - 15f * density, startY, density)
            )
            assertTrue(
                "Negative vertical drag (-20 dp) must be drag for density $density",
                OverlayTouchMath.isDrag(startX, startY, startX, startY - 20f * density, density)
            )
        }
    }

    @Test
    fun testDragDetection_ExactThresholdBoundaryAnalysis() {
        // Observational analysis of exact 10.0 dp movement
        val density = 1.0f
        val slopDp = 10.0f
        val startX = 100f
        val startY = 100f

        val isDragAtExactly10 = OverlayTouchMath.isDrag(startX, startY, startX + 10.0f, startY, density, slopDp)
        val isDragAt10_0001 = OverlayTouchMath.isDrag(startX, startY, startX + 10.0001f, startY, density, slopDp)

        // OverlayTouchMath uses `dx > slopPx || dy > slopPx`.
        // At exactly 10.0f, 10.0f > 10.0f is false.
        assertFalse("At exactly 10.0f, dx > slopPx evaluates to false", isDragAtExactly10)
        assertTrue("At 10.0001f, dx > slopPx evaluates to true", isDragAt10_0001)

        // Diagonal displacement: dx = 8.0 dp, dy = 8.0 dp -> Euclidean distance = sqrt(64+64) = 11.31 dp (> 10 dp)
        val isDragDiagonal8x8 = OverlayTouchMath.isDrag(startX, startY, startX + 8.0f, startY + 8.0f, density, slopDp)
        // With axis-aligned Chebyshev check (dx > slop || dy > slop), 8.0 > 10.0 is false.
        // Document this characteristic behavior:
        assertFalse("Chebyshev axis-aligned check treats 8x8 dp (Euclidean 11.31 dp) as tap", isDragDiagonal8x8)
    }

    // ----------------------------------------------------------------------------------
    // 3. Debounce and Single-Shot Protection Boundary Timings
    // ----------------------------------------------------------------------------------

    @Test
    fun testDebounceTimingBoundaries_0ms_2999ms_3000ms_3001ms() {
        val baseTime = 50_000L

        // 1. Delta = 0 ms (immediate second tap)
        val allowDelta0 = OverlayTouchMath.shouldAllowAcceptTap(
            isTriggered = false,
            lastTapTimestamp = baseTime,
            currentTimestamp = baseTime
        )
        assertFalse("Delta 0ms must be BLOCKED by debounce", allowDelta0)

        // 2. Delta = 2999 ms (1 ms before 3000 ms cooldown expiration)
        val allowDelta2999 = OverlayTouchMath.shouldAllowAcceptTap(
            isTriggered = false,
            lastTapTimestamp = baseTime,
            currentTimestamp = baseTime + 2999L
        )
        assertFalse("Delta 2999ms must be BLOCKED by debounce", allowDelta2999)

        // 3. Delta = 3000 ms (exact boundary: 3000 - 3000 is NOT < 3000)
        val allowDelta3000 = OverlayTouchMath.shouldAllowAcceptTap(
            isTriggered = false,
            lastTapTimestamp = baseTime,
            currentTimestamp = baseTime + 3000L
        )
        assertTrue("Delta 3000ms must be ALLOWED (cooldown elapsed)", allowDelta3000)

        // 4. Delta = 3001 ms (1 ms after 3000 ms cooldown expiration)
        val allowDelta3001 = OverlayTouchMath.shouldAllowAcceptTap(
            isTriggered = false,
            lastTapTimestamp = baseTime,
            currentTimestamp = baseTime + 3001L
        )
        assertTrue("Delta 3001ms must be ALLOWED", allowDelta3001)
    }

    @Test
    fun testSingleShotPriorityOverTiming() {
        val baseTime = 100_000L

        // Even if 1 hour has elapsed, if isTriggered is true, it MUST remain blocked
        val blockedByFlag = OverlayTouchMath.shouldAllowAcceptTap(
            isTriggered = true,
            lastTapTimestamp = baseTime,
            currentTimestamp = baseTime + 3_600_000L
        )
        assertFalse("isTriggered = true must take absolute precedence over time elapsed", blockedByFlag)
    }

    @Test
    fun testHighFrequencySimulation_10kRapidEvents() {
        val baseTime = 1_000_000L
        var lastAcceptedTime = 0L
        var acceptedCount = 0

        // Simulate 10,000 rapid touch events arriving every 1ms
        for (i in 0 until 10_000) {
            val currentTime = baseTime + i
            val isAllowed = OverlayTouchMath.shouldAllowAcceptTap(
                isTriggered = false,
                lastTapTimestamp = lastAcceptedTime,
                currentTimestamp = currentTime
            )
            if (isAllowed) {
                acceptedCount++
                lastAcceptedTime = currentTime
            }
        }

        // At 1ms interval, an accept is only allowed every 3000ms:
        // i=0 (time=1000000), i=3000 (time=1003000), i=6000 (time=1006000), i=9000 (time=1009000).
        // Total allowed in 10,000ms at 3000ms debounce: exactly 4!
        assertEquals("In 10k 1ms events, exactly 4 taps should pass 3000ms debounce", 4, acceptedCount)
    }

    @Test
    fun testMultiThreadedConcurrencyStressOnDebounce() {
        val threadCount = 20
        val iterationsPerThread = 500
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)

        val totalAccepted = AtomicInteger(0)
        var lastAcceptedTimestamp = 0L
        val lock = Any()

        val startTime = 5_000_000L

        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    for (i in 0 until iterationsPerThread) {
                        // Rapid concurrent requests within the same millisecond or adjacent
                        val now = startTime + (i % 5) // oscillates within 5ms window (< 3000ms)
                        val allowed = synchronized(lock) {
                            if (OverlayTouchMath.shouldAllowAcceptTap(false, lastAcceptedTimestamp, now)) {
                                lastAcceptedTimestamp = now
                                true
                            } else {
                                false
                            }
                        }
                        if (allowed) {
                            totalAccepted.incrementAndGet()
                        }
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await()
        executor.shutdown()

        // Across all 20 threads hammering within a 5ms window, exactly 1 tap should be allowed!
        assertEquals(
            "Concurrent bombardment in a 5ms window must yield exactly 1 accepted tap",
            1,
            totalAccepted.get()
        )
    }

    // ----------------------------------------------------------------------------------
    // 4. Bounds Check & Coordinate Robustness
    // ----------------------------------------------------------------------------------

    @Test
    fun testInsideBounds_EdgeAndCornerCases() {
        val left = 100
        val top = 200
        val width = 150
        val height = 80
        val right = left + width // 250
        val bottom = top + height // 280

        // Inside center
        assertTrue(OverlayTouchMath.isInsideBounds(175f, 240f, left, top, width, height))

        // Exact corners (inclusive)
        assertTrue("Top-left corner must be inside", OverlayTouchMath.isInsideBounds(left.toFloat(), top.toFloat(), left, top, width, height))
        assertTrue("Top-right corner must be inside", OverlayTouchMath.isInsideBounds(right.toFloat(), top.toFloat(), left, top, width, height))
        assertTrue("Bottom-left corner must be inside", OverlayTouchMath.isInsideBounds(left.toFloat(), bottom.toFloat(), left, top, width, height))
        assertTrue("Bottom-right corner must be inside", OverlayTouchMath.isInsideBounds(right.toFloat(), bottom.toFloat(), left, top, width, height))

        // 1 pixel outside
        assertFalse("1px left must be outside", OverlayTouchMath.isInsideBounds((left - 1).toFloat(), 240f, left, top, width, height))
        assertFalse("1px right must be outside", OverlayTouchMath.isInsideBounds((right + 1).toFloat(), 240f, left, top, width, height))
        assertFalse("1px above must be outside", OverlayTouchMath.isInsideBounds(175f, (top - 1).toFloat(), left, top, width, height))
        assertFalse("1px below must be outside", OverlayTouchMath.isInsideBounds(175f, (bottom + 1).toFloat(), left, top, width, height))
    }
}
