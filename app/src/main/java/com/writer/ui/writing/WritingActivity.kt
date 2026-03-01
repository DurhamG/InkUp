package com.writer.ui.writing

import android.os.Bundle
import android.util.Log
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.LinearLayout
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

        // Load saved document data
        pendingRestore = DocumentStorage.load(this)

        // Tap "W" logo to save & close (temporary for testing persistence)
        recognizedTextView.onLogoTap = {
            saveDocument()
            finish()
        }

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

    override fun onStop() {
        super.onStop()
        saveDocument()
    }

    private fun saveDocument() {
        val state = coordinator?.getState() ?: return
        DocumentStorage.save(this, state)
    }

    override fun onDestroy() {
        super.onDestroy()
        coordinator?.stop()
        recognizer.close()
    }
}
