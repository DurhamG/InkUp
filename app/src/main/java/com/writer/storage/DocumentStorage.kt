package com.writer.storage

import android.content.Context
import android.util.Log
import com.writer.model.InkStroke
import com.writer.model.StrokePoint
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class DocumentData(
    val strokes: List<InkStroke>,
    val scrollOffsetY: Float,
    val lineTextCache: Map<Int, String>,
    val everHiddenLines: Set<Int>,
    val highestLineIndex: Int,
    val currentLineIndex: Int
)

object DocumentStorage {

    private const val TAG = "DocumentStorage"
    private const val FILE_NAME = "document.json"

    fun save(context: Context, data: DocumentData) {
        try {
            val json = JSONObject()

            json.put("scrollOffsetY", data.scrollOffsetY.toDouble())
            json.put("highestLineIndex", data.highestLineIndex)
            json.put("currentLineIndex", data.currentLineIndex)

            // lineTextCache
            val cacheObj = JSONObject()
            for ((key, value) in data.lineTextCache) {
                cacheObj.put(key.toString(), value)
            }
            json.put("lineTextCache", cacheObj)

            // everHiddenLines
            val hiddenArr = JSONArray()
            for (line in data.everHiddenLines) {
                hiddenArr.put(line)
            }
            json.put("everHiddenLines", hiddenArr)

            // strokes
            val strokesArr = JSONArray()
            for (stroke in data.strokes) {
                val strokeObj = JSONObject()
                strokeObj.put("strokeId", stroke.strokeId)
                strokeObj.put("strokeWidth", stroke.strokeWidth.toDouble())

                val pointsArr = JSONArray()
                for (pt in stroke.points) {
                    val ptObj = JSONObject()
                    ptObj.put("x", pt.x.toDouble())
                    ptObj.put("y", pt.y.toDouble())
                    ptObj.put("pressure", pt.pressure.toDouble())
                    ptObj.put("timestamp", pt.timestamp)
                    pointsArr.put(ptObj)
                }
                strokeObj.put("points", pointsArr)
                strokesArr.put(strokeObj)
            }
            json.put("strokes", strokesArr)

            val file = File(context.filesDir, FILE_NAME)
            file.writeText(json.toString())
            Log.i(TAG, "Saved ${data.strokes.size} strokes to $FILE_NAME")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save document", e)
        }
    }

    fun load(context: Context): DocumentData? {
        try {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists()) return null

            val json = JSONObject(file.readText())

            val scrollOffsetY = json.optDouble("scrollOffsetY", 0.0).toFloat()
            val highestLineIndex = json.optInt("highestLineIndex", -1)
            val currentLineIndex = json.optInt("currentLineIndex", -1)

            // lineTextCache
            val lineTextCache = mutableMapOf<Int, String>()
            val cacheObj = json.optJSONObject("lineTextCache")
            if (cacheObj != null) {
                for (key in cacheObj.keys()) {
                    lineTextCache[key.toInt()] = cacheObj.getString(key)
                }
            }

            // everHiddenLines
            val everHiddenLines = mutableSetOf<Int>()
            val hiddenArr = json.optJSONArray("everHiddenLines")
            if (hiddenArr != null) {
                for (i in 0 until hiddenArr.length()) {
                    everHiddenLines.add(hiddenArr.getInt(i))
                }
            }

            // strokes
            val strokes = mutableListOf<InkStroke>()
            val strokesArr = json.optJSONArray("strokes")
            if (strokesArr != null) {
                for (i in 0 until strokesArr.length()) {
                    val strokeObj = strokesArr.getJSONObject(i)
                    val strokeId = strokeObj.getString("strokeId")
                    val strokeWidth = strokeObj.optDouble("strokeWidth", 5.0).toFloat()

                    val pointsArr = strokeObj.getJSONArray("points")
                    val points = mutableListOf<StrokePoint>()
                    for (j in 0 until pointsArr.length()) {
                        val ptObj = pointsArr.getJSONObject(j)
                        points.add(
                            StrokePoint(
                                x = ptObj.getDouble("x").toFloat(),
                                y = ptObj.getDouble("y").toFloat(),
                                pressure = ptObj.getDouble("pressure").toFloat(),
                                timestamp = ptObj.getLong("timestamp")
                            )
                        )
                    }

                    strokes.add(
                        InkStroke(
                            strokeId = strokeId,
                            points = points,
                            strokeWidth = strokeWidth
                        )
                    )
                }
            }

            Log.i(TAG, "Loaded ${strokes.size} strokes from $FILE_NAME")
            return DocumentData(
                strokes = strokes,
                scrollOffsetY = scrollOffsetY,
                lineTextCache = lineTextCache,
                everHiddenLines = everHiddenLines,
                highestLineIndex = highestLineIndex,
                currentLineIndex = currentLineIndex
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load document", e)
            return null
        }
    }
}
