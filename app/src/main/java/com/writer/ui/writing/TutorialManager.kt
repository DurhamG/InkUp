package com.writer.ui.writing

import android.content.Context
import com.writer.model.InkStroke
import com.writer.storage.DocumentData
import com.writer.view.HandwritingCanvasView
import com.writer.view.RecognizedTextView

/**
 * Manages tutorial lifecycle: showing the interactive tutorial overlay,
 * saving/restoring document state around it, and tracking whether the
 * user has already seen the tutorial.
 */
class TutorialManager(
    private val context: Context,
    private val inkCanvas: HandwritingCanvasView,
    private val textView: RecognizedTextView,
    private val getCoordinator: () -> WritingCoordinator?,
    private val getPendingRestore: () -> DocumentData?,
    private val clearPendingRestore: () -> Unit,
    private val onClosed: () -> Unit
) {

    companion object {
        private const val PREFS_NAME = "writer_prefs"
        private const val KEY_TUTORIAL_SEEN = "tutorial_seen"
    }

    var isActive = false
        private set

    private var savedStrokes: List<InkStroke>? = null
    private var savedScrollY: Float = 0f
    private var savedState: DocumentData? = null

    fun shouldAutoShow(): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return !prefs.getBoolean(KEY_TUTORIAL_SEEN, false)
    }

    fun resetSeen() {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_TUTORIAL_SEEN, false).apply()
    }

    fun show() {
        val coordinator = getCoordinator()

        // Save current document state
        savedState = coordinator?.getState()
        savedStrokes = inkCanvas.getStrokes()
        savedScrollY = inkCanvas.scrollOffsetY

        // Stop coordinator (removes callbacks)
        coordinator?.stop()

        // Generate tutorial content
        val tutorial = TutorialContent.generate(inkCanvas.width, inkCanvas.height)

        // Load tutorial strokes into canvas
        inkCanvas.loadStrokes(tutorial.strokes)
        inkCanvas.scrollOffsetY = tutorial.scrollOffsetY
        inkCanvas.annotationStrokes = tutorial.annotations
        inkCanvas.textAnnotations = tutorial.textAnnotations
        inkCanvas.tutorialMode = true
        inkCanvas.drawToSurface()

        // Set text view to tutorial mode with demo text
        textView.tutorialMode = true
        textView.textScrollOffset = 0f
        textView.setParagraphs(tutorial.textParagraphs)

        // Wire close tutorial taps (both the button and the W logo)
        textView.onCloseTutorialTap = { close() }
        textView.onLogoTap = { close() }

        isActive = true
    }

    fun close() {
        // Mark tutorial as seen
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_TUTORIAL_SEEN, true).apply()

        // Clear tutorial annotations
        inkCanvas.clearAnnotations()

        // Restore original document
        val strokes = savedStrokes
        if (strokes != null) {
            inkCanvas.loadStrokes(strokes)
            inkCanvas.scrollOffsetY = savedScrollY
            inkCanvas.drawToSurface()
        }

        // Clear text view tutorial mode and reset paragraphs before restore
        textView.tutorialMode = false
        textView.setParagraphs(emptyList())

        // Reset and restart coordinator (restoreState will set correct paragraphs)
        val coordinator = getCoordinator()
        coordinator?.reset()
        coordinator?.start()
        if (savedState != null) {
            coordinator?.restoreState(savedState!!)
        } else {
            val pending = getPendingRestore()
            if (pending != null) {
                clearPendingRestore()
                coordinator?.restoreState(pending)
            }
        }

        textView.invalidate()

        // Clear close button handler
        textView.onCloseTutorialTap = null

        // Resume raw drawing
        inkCanvas.resumeRawDrawing()

        // Clean up saved state
        savedStrokes = null
        savedState = null
        isActive = false

        onClosed()
    }
}
