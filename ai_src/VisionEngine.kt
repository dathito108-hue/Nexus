package com.hypernexus.nit.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * CAP5: real on-device image analysis without a remote vision API.
 *
 * This first executable layer produces deterministic visual features that can
 * be consumed by the agent planner: geometry, luminance, contrast, dominant
 * color and edge density. It deliberately does not claim semantic object
 * recognition or OCR.
 */
object VisionEngine {
    private const val MAX_SIDE = 512
    private const val SAMPLE_GRID = 32

    fun analyze(context: Context, source: String): String {
        val bitmap = decode(context, source) ?: return "LỖI: không giải mã được ảnh."
        return try {
            analyzeBitmap(bitmap, source)
        } finally {
            bitmap.recycle()
        }
    }

    private fun decode(context: Context, source: String): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sampleSize(source, context)
        }
        return if (source.startsWith("content://") || source.startsWith("file://") || source.startsWith("android.resource://")) {
            context.contentResolver.openInputStream(Uri.parse(source)).use { input ->
                if (input == null) null else BitmapFactory.decodeStream(input, null, options)
            }
        } else {
            val file = File(source).canonicalFile
            val filesRoot = context.filesDir.canonicalFile
            val cacheRoot = context.cacheDir.canonicalFile
            val filesPrefix = filesRoot.path + File.separator
            val cachePrefix = cacheRoot.path + File.separator
            if (file != filesRoot && !file.path.startsWith(filesPrefix) &&
                file != cacheRoot && !file.path.startsWith(cachePrefix)) {
                return null
            }
            BitmapFactory.decodeFile(file.absolutePath, options)
        }
    }

    private fun sampleSize(source: String, context: Context): Int {
        if (source.startsWith("content://") || source.startsWith("file://") || source.startsWith("android.resource://")) {
            return 1
        }
        val file = File(source)
        if (!file.exists()) return 1
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        return calculateSample(bounds.outWidth, bounds.outHeight)
    }

    private fun calculateSample(width: Int, height: Int): Int {
        var sample = 1
        while (max(width / sample, height / sample) > MAX_SIDE) sample *= 2
        return sample
    }

    private fun analyzeBitmap(bitmap: Bitmap, source: String): String {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return "LỖI: ảnh không có kích thước hợp lệ."

        val stepX = max(1, width / SAMPLE_GRID)
        val stepY = max(1, height / SAMPLE_GRID)
        var count = 0L
        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0
        var sumLum = 0.0
        var sumLum2 = 0.0
        var edges = 0L
        var edgePairs = 0L
        var previousRow = DoubleArray((width + stepX - 1) / stepX)

        var y = 0
        while (y < height) {
            val currentRow = DoubleArray(previousRow.size)
            var xi = 0
            var x = 0
            while (x < width) {
                val c = bitmap.getPixel(x, y)
                val r = Color.red(c).toDouble()
                val g = Color.green(c).toDouble()
                val b = Color.blue(c).toDouble()
                val lum = (0.2126 * r) + (0.7152 * g) + (0.0722 * b)
                val index = xi.coerceAtMost(currentRow.lastIndex)
                currentRow[index] = lum
                sumR += r
                sumG += g
                sumB += b
                sumLum += lum
                sumLum2 += lum * lum
                count++
                if (xi > 0) {
                    val delta = abs(lum - currentRow[index - 1])
                    edgePairs++
                    if (delta >= 32.0) edges++
                }
                if (y > 0 && xi < previousRow.size) {
                    val delta = abs(lum - previousRow[xi])
                    edgePairs++
                    if (delta >= 32.0) edges++
                }
                xi++
                x += stepX
            }
            previousRow = currentRow
            y += stepY
        }

        val meanR = sumR / count
        val meanG = sumG / count
        val meanB = sumB / count
        val meanLum = sumLum / count
        val variance = max(0.0, (sumLum2 / count) - (meanLum * meanLum))
        val contrast = sqrt(variance) / 255.0
        val edgeDensity = if (edgePairs == 0L) 0.0 else edges.toDouble() / edgePairs.toDouble()
        val aspect = width.toDouble() / height.toDouble()
        val orientation = when {
            aspect > 1.15 -> "landscape"
            aspect < 0.87 -> "portrait"
            else -> "square"
        }
        val brightness = when {
            meanLum < 70.0 -> "dark"
            meanLum > 185.0 -> "bright"
            else -> "balanced"
        }
        val structure = when {
            edgeDensity > 0.28 -> "high_detail"
            edgeDensity > 0.12 -> "structured"
            else -> "smooth"
        }
        val dominant = when {
            max(meanR, max(meanG, meanB)) - min(meanR, min(meanG, meanB)) < 18.0 -> "neutral"
            meanR >= meanG && meanR >= meanB -> "red_warm"
            meanG >= meanR && meanG >= meanB -> "green"
            else -> "blue_cool"
        }

        return buildString {
            append("VISION_ANALYSIS").append('\n')
            append("source=").append(source).append('\n')
            append("width=").append(width).append(" height=").append(height).append('\n')
            append("orientation=").append(orientation).append(" aspect=").append(String.format("%.3f", aspect)).append('\n')
            append("brightness=").append(brightness).append(" mean_luminance=").append(String.format("%.1f", meanLum)).append('\n')
            append("contrast=").append(String.format("%.3f", contrast)).append('\n')
            append("edge_density=").append(String.format("%.3f", edgeDensity)).append(" structure=").append(structure).append('\n')
            append("mean_rgb=").append(String.format("%.1f,%.1f,%.1f", meanR, meanG, meanB)).append('\n')
            append("dominant_color_family=").append(dominant).append('\n')
            append("note=feature-level vision; no OCR/object detector is claimed")
        }
    }
}
