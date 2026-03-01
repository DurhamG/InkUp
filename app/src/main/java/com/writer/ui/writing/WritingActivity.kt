package com.writer.ui.writing

import android.os.Bundle
import android.util.Log
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.LinearLayout
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.PopupWindow
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.writer.R
import com.writer.model.DocumentModel
import com.writer.model.InkStroke
import com.writer.recognition.HandwritingRecognizer
import com.writer.storage.DocumentData
import com.writer.storage.DocumentStorage
import com.writer.view.HandwritingCanvasView
import com.writer.view.RecognizedTextView
import kotlinx.coroutines.launch

class WritingActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "WritingActivity"
    }

    private lateinit var inkCanvas: HandwritingCanvasView
    private lateinit var recognizedTextView: RecognizedTextView

    private lateinit var documentModel: DocumentModel
    private lateinit var recognizer: HandwritingRecognizer
    private var coordinator: WritingCoordinator? = null

    // Split resize state
    private var defaultTextHeight = 0
    private var defaultCanvasHeight = 0
    private var splitOffset = 0f

    // Saved data loaded before coordinator is ready
    private var pendingRestore: DocumentData? = null

    // Tutorial state (saved while tutorial is active)
    private var savedTutorialStrokes: List<InkStroke>? = null
    private var savedTutorialScrollY: Float = 0f
    private var savedTutorialState: DocumentData? = null
    private var inTutorialMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_writing)

        // Go truly fullscreen — hide status bar and navigation bar
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.insetsController?.let { controller ->
            controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            controller.systemBarsBehavior =
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        inkCanvas = findViewById(R.id.inkCanvas)
        recognizedTextView = findViewById(R.id.recognizedTextView)

        documentModel = DocumentModel()
        recognizer = HandwritingRecognizer()

        // Load saved document data
        pendingRestore = DocumentStorage.load(this)

        // Tap "W" logo to open menu
        recognizedTextView.onLogoTap = { showMenu() }

        // Capture default heights after initial layout, then wire up the gutter
        recognizedTextView.post {
            defaultTextHeight = recognizedTextView.height
            defaultCanvasHeight = inkCanvas.height
            setupTextGutter()
        }

        // Initialize recognizer then start the coordinator
        recognizedTextView.statusMessage = "Loading..."
        lifecycleScope.launch {
            try {
                recognizer.initialize(documentModel.language)
                recognizedTextView.statusMessage = ""
                startCoordinator()
                restoreSavedDocument()
            } catch (e: Exception) {
                recognizedTextView.statusMessage = "Error"
                Toast.makeText(
                    this@WritingActivity,
                    "Failed to load recognition model: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
                startCoordinatorWithoutRecognition()
                restoreSavedDocument()
            }
        }
    }

    private fun restoreSavedDocument() {
        val data = pendingRestore ?: return
        pendingRestore = null

        Log.i(TAG, "Restoring ${data.strokes.size} strokes, scroll=${data.scrollOffsetY}")

        // Restore strokes to document model and canvas
        documentModel.activeStrokes.addAll(data.strokes)
        inkCanvas.loadStrokes(data.strokes)
        inkCanvas.scrollOffsetY = data.scrollOffsetY
        inkCanvas.drawToSurface()

        // Restore coordinator state (text cache, hidden lines, etc.)
        coordinator?.restoreState(data)
    }

    private fun setupTextGutter() {
        recognizedTextView.onGutterDrag = { delta ->
            splitOffset = (splitOffset + delta).coerceIn(0f, defaultCanvasHeight.toFloat())

            val newTextHeight = defaultTextHeight + splitOffset.toInt()
            val newCanvasHeight = defaultCanvasHeight - splitOffset.toInt()

            val textParams = recognizedTextView.layoutParams as LinearLayout.LayoutParams
            textParams.height = newTextHeight
            textParams.weight = 0f
            recognizedTextView.layoutParams = textParams

            val canvasParams = inkCanvas.layoutParams as LinearLayout.LayoutParams
            canvasParams.height = newCanvasHeight.coerceAtLeast(0)
            canvasParams.weight = 0f
            inkCanvas.layoutParams = canvasParams
        }
    }

    private fun startCoordinator() {
        coordinator = WritingCoordinator(
            documentModel = documentModel,
            recognizer = recognizer,
            inkCanvas = inkCanvas,
            textView = recognizedTextView,
            scope = lifecycleScope,
            onStatusUpdate = { status ->
                runOnUiThread {
                    recognizedTextView.statusMessage = status
                }
            }
        )
        coordinator?.start()
    }

    private fun startCoordinatorWithoutRecognition() {
        inkCanvas.onStrokeCompleted = { _ ->
            val count = inkCanvas.getStrokeCount()
            recognizedTextView.statusMessage = "$count strokes"
        }
    }

    private fun showMenu() {
        inkCanvas.pauseRawDrawing()

        val popupView = LayoutInflater.from(this).inflate(R.layout.popup_menu, null)

        // Measure to get actual content size
        popupView.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED),
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)
        )
        val popupWidth = popupView.measuredWidth
        val popupHeight = popupView.measuredHeight

        val popup = PopupWindow(popupView, popupWidth, popupHeight, true)
        popup.elevation = 0f // no shadow for e-ink

        var openingTutorial = false

        popup.setOnDismissListener {
            if (!openingTutorial) {
                inkCanvas.resumeRawDrawing()
            }
        }

        popupView.findViewById<android.view.View>(R.id.menuTutorial).setOnClickListener {
            openingTutorial = true
            popup.dismiss()
            showTutorial()
        }
        popupView.findViewById<android.view.View>(R.id.menuClose).setOnClickListener {
            popup.dismiss()
            saveDocument()
            finish()
        }

        // Position to the left of the gutter, at the top of the text view
        val gutterWidth = 144 // matches GUTTER_WIDTH in RecognizedTextView
        val loc = IntArray(2)
        recognizedTextView.getLocationOnScreen(loc)
        val x = loc[0] + recognizedTextView.width - gutterWidth - popupWidth
        val y = loc[1]
        popup.showAtLocation(recognizedTextView, Gravity.NO_GRAVITY, x, y)
    }

    private fun showTutorial() {
        // Save current document state
        savedTutorialState = coordinator?.getState()
        savedTutorialStrokes = inkCanvas.getStrokes()
        savedTutorialScrollY = inkCanvas.scrollOffsetY

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
        recognizedTextView.tutorialMode = true
        recognizedTextView.textScrollOffset = 0f
        recognizedTextView.setParagraphs(tutorial.textParagraphs)

        // Wire close tutorial taps (both the button and the W logo)
        recognizedTextView.onCloseTutorialTap = { closeTutorial() }
        recognizedTextView.onLogoTap = { closeTutorial() }

        inTutorialMode = true
    }

    private fun closeTutorial() {
        // Clear tutorial annotations
        inkCanvas.clearAnnotations()

        // Restore original document
        val strokes = savedTutorialStrokes
        if (strokes != null) {
            inkCanvas.loadStrokes(strokes)
            inkCanvas.scrollOffsetY = savedTutorialScrollY
            inkCanvas.drawToSurface()
        }

        // Clear text view tutorial mode and reset paragraphs before restore
        recognizedTextView.tutorialMode = false
        recognizedTextView.setParagraphs(emptyList())

        // Reset and restart coordinator (restoreState will set correct paragraphs)
        coordinator?.reset()
        coordinator?.start()
        savedTutorialState?.let { coordinator?.restoreState(it) }

        recognizedTextView.invalidate()

        // Restore logo tap to show menu and clear close button handler
        recognizedTextView.onLogoTap = { showMenu() }
        recognizedTextView.onCloseTutorialTap = null

        // Resume raw drawing
        inkCanvas.resumeRawDrawing()

        // Clean up saved state
        savedTutorialStrokes = null
        savedTutorialState = null
        inTutorialMode = false
    }

    override fun onStop() {
        super.onStop()
        saveDocument()
    }

    private fun saveDocument() {
        if (inTutorialMode) return // Don't save tutorial state as a document
        val state = coordinator?.getState() ?: return
        DocumentStorage.save(this, state)
    }

    override fun onDestroy() {
        super.onDestroy()
        coordinator?.stop()
        recognizer.close()
    }
}
