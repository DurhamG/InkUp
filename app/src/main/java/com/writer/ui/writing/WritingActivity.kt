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
    private lateinit var tutorialManager: TutorialManager

    // Split resize state
    private var defaultTextHeight = 0
    private var defaultCanvasHeight = 0
    private var splitOffset = 0f

    // Saved data loaded before coordinator is ready
    private var pendingRestore: DocumentData? = null

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

        tutorialManager = TutorialManager(
            context = this,
            inkCanvas = inkCanvas,
            textView = recognizedTextView,
            getCoordinator = { coordinator },
            getPendingRestore = { pendingRestore },
            clearPendingRestore = { pendingRestore = null },
            onClosed = { recognizedTextView.onLogoTap = { showMenu() } }
        )

        // Load saved document data and restore strokes immediately (no recognizer needed)
        pendingRestore = DocumentStorage.load(this)
        restoreDocumentVisuals()

        // Tap "W" logo to open menu
        recognizedTextView.onLogoTap = { showMenu() }

        // Capture default heights after initial layout, then wire up the gutter
        recognizedTextView.post {
            defaultTextHeight = recognizedTextView.height
            defaultCanvasHeight = inkCanvas.height
            setupTextGutter()

            // Show tutorial on first launch
            if (tutorialManager.shouldAutoShow()) {
                inkCanvas.pauseRawDrawing()
                tutorialManager.show()
            }
        }

        // Initialize recognizer then start the coordinator
        recognizedTextView.statusMessage = "Loading..."
        lifecycleScope.launch {
            try {
                recognizer.initialize(documentModel.language)
                recognizedTextView.statusMessage = ""
                startCoordinator()
                restoreCoordinatorState()
            } catch (e: Exception) {
                recognizedTextView.statusMessage = "Error"
                Toast.makeText(
                    this@WritingActivity,
                    "Failed to load recognition model: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
                startCoordinatorWithoutRecognition()
            }
        }
    }

    /** Restore strokes and scroll to canvas immediately — no recognizer needed. */
    private fun restoreDocumentVisuals() {
        val data = pendingRestore ?: return

        Log.i(TAG, "Restoring ${data.strokes.size} strokes, scroll=${data.scrollOffsetY}")

        documentModel.activeStrokes.addAll(data.strokes)
        inkCanvas.loadStrokes(data.strokes)
        inkCanvas.scrollOffsetY = data.scrollOffsetY
        inkCanvas.drawToSurface()
    }

    /** Restore coordinator state (text cache, hidden lines) after recognizer is ready. */
    private fun restoreCoordinatorState() {
        if (tutorialManager.isActive) return // defer until tutorial closes
        val data = pendingRestore ?: return
        pendingRestore = null

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
            tutorialManager.show()
        }
        popupView.findViewById<android.view.View>(R.id.menuClose).setOnClickListener {
            popup.dismiss()
            saveDocument()
            finish()
        }
        popupView.findViewById<android.view.View>(R.id.menuDebugReset).setOnClickListener {
            popup.dismiss()
            tutorialManager.resetSeen()
            Toast.makeText(this, "Tutorial reset — will show on next launch", Toast.LENGTH_SHORT).show()
        }

        // Position to the left of the gutter, at the top of the text view
        val gutterWidth = HandwritingCanvasView.GUTTER_WIDTH.toInt()
        val loc = IntArray(2)
        recognizedTextView.getLocationOnScreen(loc)
        val x = loc[0] + recognizedTextView.width - gutterWidth - popupWidth
        val y = loc[1]
        popup.showAtLocation(recognizedTextView, Gravity.NO_GRAVITY, x, y)
    }

    override fun onStop() {
        super.onStop()
        saveDocument()
    }

    private fun saveDocument() {
        if (tutorialManager.isActive) return // Don't save tutorial state as a document
        val state = coordinator?.getState() ?: return
        DocumentStorage.save(this, state)
    }

    override fun onDestroy() {
        super.onDestroy()
        coordinator?.stop()
        recognizer.close()
    }
}
