package com.writer.ui.writing

import android.util.Log
import com.writer.model.DocumentModel
import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import com.writer.recognition.LineSegmenter
import com.writer.view.HandwritingCanvasView

/**
 * Detects and handles ink gestures: strikethrough (delete words),
 * delete line (strikethrough extending to gutter), and insert line
 * (vertical stroke). Extracted from WritingCoordinator to keep
 * gesture logic separate from recognition and scroll orchestration.
 */
class GestureHandler(
    private val documentModel: DocumentModel,
    private val inkCanvas: HandwritingCanvasView,
    private val lineSegmenter: LineSegmenter,
    private val onLinesChanged: (invalidatedLines: Set<Int>) -> Unit
) {

    companion object {
        private const val TAG = "GestureHandler"
    }

    /**
     * Try to handle [stroke] as a gesture. Returns true if handled
     * (caller should not add the stroke to the document model).
     */
    fun tryHandle(stroke: InkStroke): Boolean {
        if (isStrikethroughGesture(stroke)) {
            val gestureMaxX = stroke.points.maxOf { it.x }
            val canvasWritingWidth = inkCanvas.width - HandwritingCanvasView.GUTTER_WIDTH
            if (gestureMaxX >= canvasWritingWidth) {
                handleDeleteLine(stroke)
            } else {
                handleStrikethrough(stroke)
            }
            return true
        }
        if (isVerticalLineGesture(stroke)) {
            handleInsertLine(stroke)
            return true
        }
        return false
    }

    private fun isStrikethroughGesture(stroke: InkStroke): Boolean {
        if (stroke.points.size < 2) return false

        val minX = stroke.points.minOf { it.x }
        val maxX = stroke.points.maxOf { it.x }
        val minY = stroke.points.minOf { it.y }
        val maxY = stroke.points.maxOf { it.y }

        val xRange = maxX - minX
        val yRange = maxY - minY

        if (xRange < 100f) return false
        if (yRange > xRange * 0.3f) return false

        val startLineIdx = lineSegmenter.getLineIndex(stroke.points.first().y)
        val endLineIdx = lineSegmenter.getLineIndex(stroke.points.last().y)
        if (startLineIdx != endLineIdx) return false

        return true
    }

    private fun isVerticalLineGesture(stroke: InkStroke): Boolean {
        if (stroke.points.size < 2) return false

        val minX = stroke.points.minOf { it.x }
        val maxX = stroke.points.maxOf { it.x }
        val minY = stroke.points.minOf { it.y }
        val maxY = stroke.points.maxOf { it.y }

        val xRange = maxX - minX
        val yRange = maxY - minY

        if (yRange < HandwritingCanvasView.LINE_SPACING * 1.5f) return false
        if (xRange > yRange * 0.3f) return false

        return true
    }

    private fun handleStrikethrough(gestureStroke: InkStroke) {
        val centroidY = gestureStroke.points.map { it.y }.average().toFloat()
        val lineIdx = lineSegmenter.getLineIndex(centroidY)

        val gestureMinX = gestureStroke.points.minOf { it.x }
        val gestureMaxX = gestureStroke.points.maxOf { it.x }

        val lineStrokes = lineSegmenter.getStrokesForLine(documentModel.activeStrokes, lineIdx)
        val overlapping = lineStrokes.filter { stroke ->
            val strokeMinX = stroke.points.minOf { it.x }
            val strokeMaxX = stroke.points.maxOf { it.x }
            strokeMaxX >= gestureMinX && strokeMinX <= gestureMaxX
        }

        if (overlapping.isEmpty()) {
            refreshCanvas { inkCanvas.removeStrokes(setOf(gestureStroke.strokeId)) }
            return
        }

        val idsToRemove = overlapping.map { it.strokeId }.toMutableSet()
        idsToRemove.add(gestureStroke.strokeId)

        documentModel.activeStrokes.removeAll { it.strokeId in idsToRemove }
        refreshCanvas { inkCanvas.removeStrokes(idsToRemove) }

        onLinesChanged(setOf(lineIdx))

        Log.i(TAG, "Strikethrough on line $lineIdx: removed ${overlapping.size} strokes")
    }

    private fun handleDeleteLine(gestureStroke: InkStroke) {
        val centroidY = gestureStroke.points.map { it.y }.average().toFloat()
        val lineIdx = lineSegmenter.getLineIndex(centroidY)

        val lineStrokes = lineSegmenter.getStrokesForLine(documentModel.activeStrokes, lineIdx)
        val idsToRemove = lineStrokes.map { it.strokeId }.toMutableSet()
        idsToRemove.add(gestureStroke.strokeId)
        documentModel.activeStrokes.removeAll { it.strokeId in idsToRemove }

        val shiftAmount = -HandwritingCanvasView.LINE_SPACING
        val replacements = mutableMapOf<String, InkStroke>()
        val newActiveStrokes = mutableListOf<InkStroke>()

        for (stroke in documentModel.activeStrokes) {
            val strokeLine = lineSegmenter.getStrokeLineIndex(stroke)
            if (strokeLine > lineIdx) {
                val shifted = shiftStroke(stroke, shiftAmount)
                replacements[stroke.strokeId] = shifted
                newActiveStrokes.add(shifted)
            } else {
                newActiveStrokes.add(stroke)
            }
        }

        documentModel.activeStrokes.clear()
        documentModel.activeStrokes.addAll(newActiveStrokes)

        refreshCanvas {
            inkCanvas.replaceStrokes(replacements)
            inkCanvas.removeStrokes(idsToRemove)
        }

        // Invalidate this line and all lines that were shifted
        val invalidated = (lineIdx..lineIdx + replacements.size).toSet()
        onLinesChanged(invalidated)

        Log.i(TAG, "Delete line $lineIdx: removed ${lineStrokes.size} strokes, shifted ${replacements.size} up")
    }

    private fun handleInsertLine(gestureStroke: InkStroke) {
        val startY = gestureStroke.points.first().y
        val endY = gestureStroke.points.last().y
        val startLineIdx = lineSegmenter.getLineIndex(startY)
        val drawingDownward = endY > startY

        val shiftFromLine = if (drawingDownward) startLineIdx + 1 else startLineIdx
        val shiftAmount = HandwritingCanvasView.LINE_SPACING

        val replacements = mutableMapOf<String, InkStroke>()
        val newActiveStrokes = mutableListOf<InkStroke>()

        for (stroke in documentModel.activeStrokes) {
            val lineIdx = lineSegmenter.getStrokeLineIndex(stroke)

            if (lineIdx >= shiftFromLine) {
                val shifted = shiftStroke(stroke, shiftAmount)
                replacements[stroke.strokeId] = shifted
                newActiveStrokes.add(shifted)
            } else {
                newActiveStrokes.add(stroke)
            }
        }

        documentModel.activeStrokes.clear()
        documentModel.activeStrokes.addAll(newActiveStrokes)

        refreshCanvas {
            inkCanvas.replaceStrokes(replacements)
            inkCanvas.removeStrokes(setOf(gestureStroke.strokeId))
        }

        // Invalidate all shifted lines
        val invalidated = (shiftFromLine..shiftFromLine + replacements.size).toSet()
        onLinesChanged(invalidated)

        val direction = if (drawingDownward) "below" else "above"
        Log.i(TAG, "Insert line $direction line $startLineIdx (shifted ${replacements.size} strokes)")
    }

    private fun shiftStroke(stroke: InkStroke, dy: Float): InkStroke {
        val shiftedPoints = stroke.points.map { pt ->
            StrokePoint(pt.x, pt.y + dy, pt.pressure, pt.timestamp)
        }
        return InkStroke(
            strokeId = stroke.strokeId,
            points = shiftedPoints,
            strokeWidth = stroke.strokeWidth
        )
    }

    private fun refreshCanvas(operations: () -> Unit) {
        inkCanvas.pauseRawDrawing()
        operations()
        inkCanvas.drawToSurface()
        inkCanvas.resumeRawDrawing()
    }
}
