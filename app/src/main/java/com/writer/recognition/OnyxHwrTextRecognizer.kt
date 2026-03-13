package com.writer.recognition

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.MemoryFile
import android.os.ParcelFileDescriptor
import android.util.Log
import com.onyx.android.sdk.hwr.service.HWRInputArgs
import com.onyx.android.sdk.hwr.service.HWROutputArgs
import com.onyx.android.sdk.hwr.service.HWROutputCallback
import com.onyx.android.sdk.hwr.service.IHWRService
import com.writer.model.InkLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.FileDescriptor
import java.io.FileInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Handwriting recognition using the Boox firmware's built-in MyScript engine.
 * Communicates via AIDL IPC with `com.onyx.android.ksync.service.KHwrService`.
 *
 * Onyx Boox devices only. Falls back gracefully (returns empty strings) if
 * the service is unavailable.
 *
 * Based on the approach used by [Notable](https://github.com/jshph/notable).
 */
class OnyxHwrTextRecognizer(private val context: Context) : TextRecognizer {

    companion object {
        private const val TAG = "OnyxHwrTextRecognizer"
        private const val SERVICE_PACKAGE = "com.onyx.android.ksync"
        private const val SERVICE_CLASS = "com.onyx.android.ksync.service.KHwrService"
        private const val BIND_TIMEOUT_MS = 3000L
        private const val RECOGNIZE_TIMEOUT_MS = 10_000L
    }

    @Volatile private var service: IHWRService? = null
    @Volatile private var bound = false
    @Volatile private var initialized = false
    private var connectLatch = CountDownLatch(1)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IHWRService.Stub.asInterface(binder)
            bound = true
            Log.i(TAG, "HWR service connected")
            connectLatch.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
            initialized = false
            Log.w(TAG, "HWR service disconnected")
        }
    }

    override suspend fun initialize(languageTag: String) {
        if (bound && service != null) return

        connectLatch = CountDownLatch(1)
        val intent = Intent().apply {
            component = ComponentName(SERVICE_PACKAGE, SERVICE_CLASS)
        }

        val bindStarted = try {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to bind HWR service: ${e.message}")
            throw IllegalStateException("Onyx HWR service not available", e)
        }

        if (!bindStarted) {
            throw IllegalStateException("Onyx HWR service not found — is this a Boox device?")
        }

        val connected = withContext(Dispatchers.IO) {
            connectLatch.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
        if (!connected || service == null) {
            throw IllegalStateException("Onyx HWR service bind timed out")
        }

        // Initialize the MyScript recognizer
        val svc = service!!
        val inputArgs = HWRInputArgs().apply {
            lang = languageTag.replace("-", "_")  // "en-US" → "en_US"
            contentType = "Text"
            recognizerType = "MS_ON_SCREEN"
            viewWidth = 1000f   // will be overridden per-recognition
            viewHeight = 200f
            isTextEnable = true
        }

        suspendCancellableCoroutine { cont ->
            svc.init(inputArgs, true, object : HWROutputCallback.Stub() {
                override fun read(args: HWROutputArgs?) {
                    initialized = args?.recognizerActivated == true
                    Log.i(TAG, "HWR init: activated=$initialized")
                    cont.resume(Unit)
                }
            })
        }

        if (!initialized) {
            throw IllegalStateException("MyScript recognizer failed to activate")
        }
        Log.i(TAG, "Recognizer initialized for $languageTag")
    }

    override suspend fun recognizeLine(line: InkLine, preContext: String): String {
        val svc = service ?: return ""
        if (line.strokes.isEmpty()) return ""

        val bb = line.boundingBox
        val viewWidth = if (bb.width() > 0) bb.width() else 1000f
        val viewHeight = if (bb.height() > 0) bb.height() else 200f

        val protoBytes = HwrProtobuf.buildProtobuf(line, viewWidth, viewHeight)
        val pfd = createMemoryFilePfd(protoBytes) ?: return ""

        return try {
            val result = withTimeoutOrNull(RECOGNIZE_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    svc.batchRecognize(pfd, object : HWROutputCallback.Stub() {
                        override fun read(args: HWROutputArgs?) {
                            try {
                                val errorJson = args?.hwrResult
                                if (!errorJson.isNullOrBlank()) {
                                    Log.e(TAG, "HWR error: ${errorJson.take(300)}")
                                    cont.resume("")
                                    return
                                }
                                val resultPfd = args?.pfd
                                if (resultPfd == null) {
                                    cont.resume("")
                                    return
                                }
                                val json = readPfdAsString(resultPfd)
                                resultPfd.close()
                                val text = HwrProtobuf.parseHwrResult(json)
                                Log.d(TAG, "Recognized: \"$text\"")
                                cont.resume(text)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error parsing HWR result: ${e.message}")
                                cont.resumeWithException(e)
                            }
                        }
                    })
                }
            }
            result ?: ""
        } finally {
            pfd.close()
        }
    }

    override fun close() {
        if (bound) {
            try {
                service?.closeRecognizer()
                context.unbindService(connection)
            } catch (_: Exception) {}
            bound = false
            service = null
            initialized = false
        }
    }

    private fun readPfdAsString(pfd: ParcelFileDescriptor): String {
        val input = FileInputStream(pfd.fileDescriptor)
        val baos = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        var n: Int
        while (input.read(buf).also { n = it } != -1) {
            baos.write(buf, 0, n)
        }
        return baos.toString("UTF-8")
    }

    private fun createMemoryFilePfd(data: ByteArray): ParcelFileDescriptor? {
        return try {
            val memFile = MemoryFile("hwr_input", data.size)
            memFile.writeBytes(data, 0, 0, data.size)
            val method = MemoryFile::class.java.getDeclaredMethod("getFileDescriptor")
            method.isAccessible = true
            val fd = method.invoke(memFile) as FileDescriptor
            val pfd = ParcelFileDescriptor.dup(fd)
            memFile.close()
            pfd
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create MemoryFile PFD: ${e.message}")
            null
        }
    }
}
