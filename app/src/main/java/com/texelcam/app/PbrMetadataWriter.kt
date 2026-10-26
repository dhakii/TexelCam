package com.texelcam.app

import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PbrMetadataWriter {

    data class CaptureMetadata(
        val imageFile: File,
        val imageWidth: Int,
        val imageHeight: Int,
        val distanceMeters: Float,
        val texelDensityPxPerM: Float,
        val physicalWidthMeters: Float,
        val physicalHeightMeters: Float,
        val alignmentState: AlignmentState,
        val targetDensityPxPerM: Int,
        val focalLengthMm: Float,
        val sensorWidthMm: Float,
        val sensorHeightMm: Float
    )

    fun writeSidecarFiles(meta: CaptureMetadata): Pair<File, File>? {
        return try {
            val baseName = meta.imageFile.nameWithoutExtension
            val dir = meta.imageFile.parentFile ?: return null

            val isoDate = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date())

            val rootJson = JSONObject().apply {
                put("generator", "TexelCam 1.0 (Android PBR Camera)")
                put("timestamp", isoDate)
                put("image_file", meta.imageFile.name)
                put("resolution", JSONObject().apply {
                    put("width", meta.imageWidth)
                    put("height", meta.imageHeight)
                })
                put("surface_mode", meta.alignmentState.mode.name)
                put("is_perpendicular", meta.alignmentState.isAligned)
                put("distance_meters", meta.distanceMeters.toDouble())
                put("texel_density_px_per_m", meta.texelDensityPxPerM.toDouble())
                put("physical_dimensions_m", JSONObject().apply {
                    put("width_m", meta.physicalWidthMeters.toDouble())
                    put("height_m", meta.physicalHeightMeters.toDouble())
                })
                put("alignment_deviation", JSONObject().apply {
                    put("pitch_deg", meta.alignmentState.pitchDeg.toDouble())
                    put("roll_deg", meta.alignmentState.rollDeg.toDouble())
                    put("pitch_error_deg", meta.alignmentState.pitchErrorDeg.toDouble())
                    put("roll_error_deg", meta.alignmentState.rollErrorDeg.toDouble())
                    put("total_deviation_deg", meta.alignmentState.totalDeviationDeg.toDouble())
                })
                put("camera_intrinsics", JSONObject().apply {
                    put("focal_length_mm", meta.focalLengthMm.toDouble())
                    put("sensor_width_mm", meta.sensorWidthMm.toDouble())
                    put("sensor_height_mm", meta.sensorHeightMm.toDouble())
                })
                put("textureripper", JSONObject().apply {
                    put("version_compat", "2.0")
                    put("physical_unit", "meters")
                    put("reference_box_size_m", 1.0)
                    put("target_texel_density", meta.targetDensityPxPerM)
                    put("suggested_crop_pixels", meta.targetDensityPxPerM)
                })
            }

            val jsonString = rootJson.toString(2)

            // 1. Write TextureRipper .combine.json sidecar
            val combineFile = File(dir, "$baseName.combine.json")
            FileWriter(combineFile).use { it.write(jsonString) }

            // 2. Write TexelCam .texel.json sidecar
            val texelFile = File(dir, "$baseName.texel.json")
            FileWriter(texelFile).use { it.write(jsonString) }

            Pair(combineFile, texelFile)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
