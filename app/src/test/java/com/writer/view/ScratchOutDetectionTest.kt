package com.writer.view

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ScratchOutDetection].
 *
 * Uses a fixed line spacing of 118 px (standard device: 63 dp × 1.875 density).
 *
 * Geometry recap:
 *   MIN_REVERSALS = 2         → stroke must change X direction at least twice
 *   MIN_X_TRAVEL_SPANS = 1.5  → total |dx| ≥ 177 px at 118 px LS
 *   MAX_Y_DRIFT = 0.4         → yRange < 40% of total x-travel
 */
class ScratchOutDetectionTest {

    companion object {
        private const val LS = 118f
        private val X_THRESH get() = ScratchOutDetection.MIN_X_TRAVEL_SPANS * LS  // ≈ 177 px
    }

    // ── Strokes that SHOULD qualify ──────────────────────────────────────────

    @Test fun wideZigzag_detects() {
        // 4 segments: right → left → right → left — clearly a scratch-out
        val xs = zigzag(startX = 0f, segmentWidth = 60f, segments = 4)
        assertTrue(ScratchOutDetection.detect(xs, yRange = 5f, lineSpacing = LS))
    }

    @Test fun minimalZigzag_exactlyTwoReversals_detects() {
        // 3 segments → 2 reversals; total travel just above threshold
        val segW = X_THRESH / 2f + 2f
        val xs = zigzag(startX = 0f, segmentWidth = segW, segments = 3)
        assertTrue(ScratchOutDetection.detect(xs, yRange = 2f, lineSpacing = LS))
    }

    @Test fun manyReversals_detects() {
        val xs = zigzag(startX = 0f, segmentWidth = 50f, segments = 6)
        assertTrue(ScratchOutDetection.detect(xs, yRange = 3f, lineSpacing = LS))
    }

    // ── Strokes that should NOT qualify ──────────────────────────────────────

    @Test fun singleDirection_noReversal_notDetected() {
        val xs = floatArrayOf(0f, 50f, 100f, 200f, 300f)
        assertFalse(ScratchOutDetection.detect(xs, yRange = 5f, lineSpacing = LS))
    }

    @Test fun oneReversal_notDetected() {
        // Right then left — only 1 reversal (U-shape), not a scratch-out
        val xs = floatArrayOf(0f, 100f, 200f, 100f, 0f)
        assertFalse(ScratchOutDetection.detect(xs, yRange = 5f, lineSpacing = LS))
    }

    @Test fun twoReversals_tooNarrow_notDetected() {
        // Zigzag with 2 reversals but total travel below threshold
        val xs = zigzag(startX = 0f, segmentWidth = 10f, segments = 3)
        assertFalse(ScratchOutDetection.detect(xs, yRange = 1f, lineSpacing = LS))
    }

    @Test fun twoReversals_tooWobbly_notDetected() {
        // Wide zigzag but excessive vertical displacement
        val xs = zigzag(startX = 0f, segmentWidth = 100f, segments = 4)
        val totalXTravel = 100f * 4  // each segment is 100 px
        // yRange = 200% of total x-travel → way over MAX_Y_DRIFT (0.4)
        assertFalse(ScratchOutDetection.detect(xs, yRange = totalXTravel * 2f, lineSpacing = LS))
    }

    @Test fun tooFewPoints_notDetected() {
        // Less than 4 points — cannot reliably detect reversals
        assertFalse(ScratchOutDetection.detect(floatArrayOf(0f, 50f, 100f), yRange = 5f, lineSpacing = LS))
    }

    @Test fun emptyArray_notDetected() {
        assertFalse(ScratchOutDetection.detect(floatArrayOf(), yRange = 0f, lineSpacing = LS))
    }

    // ── Boundary ─────────────────────────────────────────────────────────────

    @Test fun exactlyAtYDriftLimit_notDetected() {
        // yRange == totalXTravel * MAX_Y_DRIFT — must be strictly less than
        val xs = zigzag(startX = 0f, segmentWidth = 60f, segments = 4)
        // total travel = 4 × 60 = 240 px; drift limit = 240 × 0.4 = 96 px
        assertFalse(ScratchOutDetection.detect(xs, yRange = 240f * ScratchOutDetection.MAX_Y_DRIFT, lineSpacing = LS))
    }

    @Test fun justBelowYDriftLimit_detects() {
        val xs = zigzag(startX = 0f, segmentWidth = 60f, segments = 4)
        val totalXTravel = 60f * 4
        assertFalse(ScratchOutDetection.detect(xs, yRange = totalXTravel * ScratchOutDetection.MAX_Y_DRIFT, lineSpacing = LS))
        // one less px → should detect
        assertTrue(ScratchOutDetection.detect(xs, yRange = totalXTravel * ScratchOutDetection.MAX_Y_DRIFT - 1f, lineSpacing = LS))
    }

    // ── Bug 1: closed-loop strokes must not trigger scratch-out ──────────────
    //
    // When the user draws a shape (e.g. a rounded-rectangle outline) around existing
    // handwritten letters, the shape snap may fail if the stroke is too wobbly.
    // checkPostStrokeScratchOut() then runs.  A closed loop with multiple x-reversals
    // satisfies all three scratch-out criteria (reversals ≥ 2, travel ≥ 177 px, low
    // y-drift) and currently ERASES the letters inside — a false positive.
    //
    // The fix: ScratchOutDetection.detect() must return false whenever the stroke is
    // a closed loop (stroke start ≈ stroke end relative to its own diagonal).

    /**
     * BUG 1 — CURRENTLY FAILS.
     *
     * A stroke whose x-coordinate series returns to the same value as it started
     * (xs.first() == xs.last()) with multiple x-reversals is detected as scratch-out.
     * This can happen when the user draws a bumpy oval around text:
     *   — shape snap fails (stroke too irregular for any shape detector)
     *   — scratch-out sees ≥ 2 reversals + ≥ 177 px travel + low y-drift → true
     *   — interior letters are erased
     *
     * Correct behaviour: a closed loop is NEVER a scratch-out.
     */
    @Test fun closedLoop_multipleXReversals_notScratchOut() {
        // xs traces a figure-8 / bumpy closed oval: 0 → 200 → 0 → 200 → 0
        // reversals = 3, total x-travel = 800 px, yRange = 30 px → passes all three checks.
        // But the stroke IS a closed loop — must never be treated as scratch-out.
        val xs = floatArrayOf(0f, 200f, 0f, 200f, 0f)
        val yRange = 30f
        assertFalse(
            "Closed loop must NOT trigger scratch-out (Bug 1: erases letters drawn inside a shape)",
            ScratchOutDetection.detect(xs, yRange, LS, isClosedLoop = true)
        )
    }

    /**
     * BUG 1 variant: a rectangular outline with corner overshoots.
     *
     * Real freehand rectangles commonly overshoot corners: after going right the pen
     * briefly continues then reverses, adding extra x-reversals.
     * 300×100 px rectangle, 2 corner overshoots → 2 reversals, total x-travel = 620 px,
     * yRange = 100 px → 100 < 0.4×620 = 248 — passes all scratch-out checks.
     * But the stroke is closed, so it must not trigger scratch-out.
     */
    @Test fun closedRectangleWithCornerOvershoots_notScratchOut() {
        val xs = floatArrayOf(0f, 310f, 300f, -10f, 0f, 0f)
        val yRange = 100f
        assertFalse(
            "Rectangular closed stroke with overshoots must NOT trigger scratch-out (Bug 1)",
            ScratchOutDetection.detect(xs, yRange, LS, isClosedLoop = true)
        )
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    /**
     * Build a zigzag x-coordinate array.
     * [segments] equal-width segments alternating right/left.
     * Result has [segments + 1] points.
     */
    private fun zigzag(startX: Float, segmentWidth: Float, segments: Int): FloatArray {
        val pts = FloatArray(segments + 1)
        pts[0] = startX
        for (i in 1..segments) {
            val dir = if (i % 2 == 1) 1f else -1f
            pts[i] = pts[i - 1] + dir * segmentWidth
        }
        return pts
    }
}
