package com.writer.ui.writing

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Typeface
import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import com.writer.view.HandwritingCanvasView

data class AnnotationStroke(
    val points: List<StrokePoint>,
    val color: Int,
    val strokeWidth: Float = 4f
)

data class TextAnnotation(
    val text: String,
    val x: Float,
    val y: Float,
    val color: Int,
    val size: Float = 34f,
    val centered: Boolean = false
)

data class TutorialData(
    val strokes: List<InkStroke>,
    val annotations: List<AnnotationStroke>,
    val textAnnotations: List<TextAnnotation>,
    val scrollOffsetY: Float,
    val textParagraphs: List<List<WritingCoordinator.TextSegment>>
)

object TutorialContent {

    private val LINE_SPACING = HandwritingCanvasView.LINE_SPACING
    private val TOP_MARGIN = HandwritingCanvasView.TOP_MARGIN
    private val GUTTER_WIDTH = HandwritingCanvasView.GUTTER_WIDTH

    private val textPaint = Paint().apply {
        typeface = Typeface.create("cursive", Typeface.NORMAL)
        isAntiAlias = true
    }

    fun generate(canvasWidth: Int, canvasHeight: Int): TutorialData {
        val writingWidth = canvasWidth - GUTTER_WIDTH

        val strokes = mutableListOf<InkStroke>()
        val annotations = mutableListOf<AnnotationStroke>()
        val textAnnotations = mutableListOf<TextAnnotation>()

        val red = Color.rgb(200, 50, 50)
        val blue = Color.rgb(50, 50, 200)
        val green = Color.rgb(40, 150, 40)

        fun lineTop(idx: Int): Float = TOP_MARGIN + idx * LINE_SPACING
        fun baseline(idx: Int): Float = TOP_MARGIN + (idx + 1) * LINE_SPACING - 20f

        // --- Lines 0-1: Demo text + scroll annotation ---
        strokes.addAll(textToStrokes("The quick brown fox", 60f, baseline(0), 64f))
        strokes.addAll(textToStrokes("jumps over the lazy dog", 60f, baseline(1), 64f))

        // Blue arrow pointing to gutter
        val arrowY = lineTop(0) + 40f
        annotations.addAll(
            makeArrow(writingWidth - 300f, arrowY, writingWidth - 30f, arrowY, blue)
        )
        textAnnotations.add(
            TextAnnotation("Drag in gutter to scroll", writingWidth - 680f, arrowY + 10f, blue, 34f)
        )

        // --- Line 2: Strikethrough demo ---
        strokes.addAll(textToStrokes("Hello beautiful world", 60f, baseline(2), 64f))

        // Red strikethrough across "beautiful" only
        val strikeY = baseline(2) - 22f
        annotations.add(makeLine(180f, strikeY, 400f, strikeY, red, 5f))
        textAnnotations.add(
            TextAnnotation("Strike through to delete words", 560f, strikeY + 10f, red, 32f)
        )

        // --- Line 3: Delete line demo (X gesture) ---
        strokes.addAll(textToStrokes("Once upon a time", 60f, baseline(3), 64f))

        // Red X gesture: two crossing diagonals
        val xCenterX = 580f
        val xCenterY = baseline(3) - 22f
        val xSize = 28f
        // First diagonal: top-left to bottom-right
        annotations.add(makeLine(xCenterX - xSize, xCenterY - xSize, xCenterX + xSize, xCenterY + xSize, red, 5f))
        // Second diagonal: top-right to bottom-left
        annotations.add(makeLine(xCenterX + xSize, xCenterY - xSize, xCenterX - xSize, xCenterY + xSize, red, 5f))
        textAnnotations.add(
            TextAnnotation("Draw X to delete entire line", xCenterX + xSize + 20f, xCenterY + 10f, red, 32f)
        )

        // --- Lines 4-6: Insert line demo (two vertical lines) ---
        strokes.addAll(textToStrokes("Line above", 60f, baseline(4), 64f))
        strokes.addAll(textToStrokes("Line below", 60f, baseline(6), 64f))

        // Downward vertical line (draw ↓ to insert below) — right of "Line above" text
        val vertDownX = 450f
        val vertDownStart = lineTop(4) + LINE_SPACING / 2f
        val vertDownEnd = vertDownStart + LINE_SPACING * 1.5f - 10f
        annotations.add(makeLine(vertDownX, vertDownStart, vertDownX, vertDownEnd, green, 5f))
        // Arrowhead pointing down
        annotations.add(makeLine(vertDownX - 12f, vertDownEnd - 20f, vertDownX, vertDownEnd, green, 4f))
        annotations.add(makeLine(vertDownX + 12f, vertDownEnd - 20f, vertDownX, vertDownEnd, green, 4f))
        textAnnotations.add(
            TextAnnotation("Draw ↓ to insert below", vertDownX + 30f, (vertDownStart + vertDownEnd) / 2f + 8f, green, 32f)
        )

        // Upward vertical line (draw ↑ to insert above) — farther right, clear of "Draw down"
        val vertUpX = 850f
        val vertUpMid = baseline(6) - 10f
        val vertUpEnd = vertUpMid - LINE_SPACING * 1.5f
        annotations.add(makeLine(vertUpX, vertUpMid, vertUpX, vertUpEnd, green, 5f))
        // Arrowhead pointing up
        annotations.add(makeLine(vertUpX - 12f, vertUpEnd + 20f, vertUpX, vertUpEnd, green, 4f))
        annotations.add(makeLine(vertUpX + 12f, vertUpEnd + 20f, vertUpX, vertUpEnd, green, 4f))
        textAnnotations.add(
            TextAnnotation("Draw ↑ to insert above", vertUpX + 30f, (vertUpMid + vertUpEnd) / 2f + 8f, green, 32f)
        )

        // --- Auto-scroll hint near the bottom ---
        textAnnotations.add(
            TextAnnotation(
                "Writing will auto-scroll up as you reach the bottom",
                writingWidth / 2f, canvasHeight - 55f - LINE_SPACING, blue, 34f,
                centered = true
            )
        )

        // --- Text paragraphs for the text view ---
        val textParagraphs = listOf(
            listOf(
                WritingCoordinator.TextSegment("The quick brown fox", dimmed = false, lineIndex = 0),
                WritingCoordinator.TextSegment("jumps over the lazy dog", dimmed = false, lineIndex = 1)
            ),
            listOf(
                WritingCoordinator.TextSegment("Hello world", dimmed = false, lineIndex = 2)
            ),
            listOf(
                WritingCoordinator.TextSegment("Line above", dimmed = false, lineIndex = 4),
                WritingCoordinator.TextSegment("Line below", dimmed = false, lineIndex = 6)
            )
        )

        return TutorialData(
            strokes = strokes,
            annotations = annotations,
            textAnnotations = textAnnotations,
            scrollOffsetY = 0f,
            textParagraphs = textParagraphs
        )
    }

    private fun textToStrokes(text: String, x: Float, y: Float, textSize: Float): List<InkStroke> {
        val paint = Paint(textPaint)
        paint.textSize = textSize

        val path = Path()
        paint.getTextPath(text, 0, text.length, x, y, path)

        val strokes = mutableListOf<InkStroke>()
        val measure = PathMeasure(path, false)
        val pos = FloatArray(2)

        do {
            val length = measure.length
            if (length < 2f) continue

            val points = mutableListOf<StrokePoint>()
            val step = 2f
            var dist = 0f
            while (dist <= length) {
                measure.getPosTan(dist, pos, null)
                points.add(StrokePoint(pos[0], pos[1], 0.5f, 0L))
                dist += step
            }
            measure.getPosTan(length, pos, null)
            points.add(StrokePoint(pos[0], pos[1], 0.5f, 0L))

            if (points.size >= 2) {
                strokes.add(InkStroke(points = points, strokeWidth = 2f))
            }
        } while (measure.nextContour())

        return strokes
    }

    private fun makeArrow(
        fromX: Float, fromY: Float,
        toX: Float, toY: Float,
        color: Int
    ): List<AnnotationStroke> {
        val shaft = makeLine(fromX, fromY, toX, toY, color, 4f)

        val arrowSize = 18f
        val dx = toX - fromX
        val dy = toY - fromY
        val len = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        val ux = dx / len
        val uy = dy / len

        val head1 = makeLine(
            toX, toY,
            toX - arrowSize * ux + arrowSize * 0.5f * uy,
            toY - arrowSize * uy - arrowSize * 0.5f * ux,
            color, 4f
        )
        val head2 = makeLine(
            toX, toY,
            toX - arrowSize * ux - arrowSize * 0.5f * uy,
            toY - arrowSize * uy + arrowSize * 0.5f * ux,
            color, 4f
        )

        return listOf(shaft, head1, head2)
    }

    private fun makeLine(
        x1: Float, y1: Float,
        x2: Float, y2: Float,
        color: Int, width: Float
    ): AnnotationStroke {
        return AnnotationStroke(
            points = listOf(
                StrokePoint(x1, y1, 0.5f, 0L),
                StrokePoint(x2, y2, 0.5f, 0L)
            ),
            color = color,
            strokeWidth = width
        )
    }
}
