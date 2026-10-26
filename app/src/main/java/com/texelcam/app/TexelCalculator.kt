package com.texelcam.app

import kotlin.math.atan
import kotlin.math.roundToInt

/**
 * Optical photogrammetry and texel density calculations for TexelCam.
 */
object TexelCalculator {

    // Default smartphone camera fallback intrinsics (1/2.55" sensor, ~26mm eq)
    const val DEFAULT_SENSOR_WIDTH_MM = 5.76f
    const val DEFAULT_SENSOR_HEIGHT_MM = 4.29f
    const val DEFAULT_FOCAL_LENGTH_MM = 4.38f

    data class Guidance(
        val message: String,
        val isOptimal: Boolean,
        val deltaCm: Int
    )

    /**
     * Compute horizontal Field of View in radians:
     * theta_H = 2 * atan(sensorWidth / (2 * focalLength))
     */
    fun computeHFOV(sensorWidthMm: Float, focalLengthMm: Float): Float {
        val fl = if (focalLengthMm > 0.1f) focalLengthMm else DEFAULT_FOCAL_LENGTH_MM
        val sw = if (sensorWidthMm > 0.1f) sensorWidthMm else DEFAULT_SENSOR_WIDTH_MM
        return (2.0 * atan((sw / (2.0 * fl)).toDouble())).toFloat()
    }

    /**
     * Compute vertical Field of View in radians:
     * theta_V = 2 * atan(sensorHeight / (2 * focalLength))
     */
    fun computeVFOV(sensorHeightMm: Float, focalLengthMm: Float): Float {
        val fl = if (focalLengthMm > 0.1f) focalLengthMm else DEFAULT_FOCAL_LENGTH_MM
        val sh = if (sensorHeightMm > 0.1f) sensorHeightMm else DEFAULT_SENSOR_HEIGHT_MM
        return (2.0 * atan((sh / (2.0 * fl)).toDouble())).toFloat()
    }

    /**
     * Physical horizontal width in meters framed at distance D (meters):
     * W_real = D * (sensorWidth / focalLength)
     */
    fun computePhysicalWidth(distanceMeters: Float, sensorWidthMm: Float, focalLengthMm: Float): Float {
        val d = if (distanceMeters > 0.05f) distanceMeters else 0.5f
        val fl = if (focalLengthMm > 0.1f) focalLengthMm else DEFAULT_FOCAL_LENGTH_MM
        val sw = if (sensorWidthMm > 0.1f) sensorWidthMm else DEFAULT_SENSOR_WIDTH_MM
        return d * (sw / fl)
    }

    /**
     * Physical vertical height in meters framed at distance D (meters):
     * H_real = D * (sensorHeight / focalLength)
     */
    fun computePhysicalHeight(distanceMeters: Float, sensorHeightMm: Float, focalLengthMm: Float): Float {
        val d = if (distanceMeters > 0.05f) distanceMeters else 0.5f
        val fl = if (focalLengthMm > 0.1f) focalLengthMm else DEFAULT_FOCAL_LENGTH_MM
        val sh = if (sensorHeightMm > 0.1f) sensorHeightMm else DEFAULT_SENSOR_HEIGHT_MM
        return d * (sh / fl)
    }

    /**
     * Live Texel Density (px/meter):
     * TD = imageWidthPx / physicalWidthMeters
     */
    fun computeTexelDensity(imageWidthPx: Int, physicalWidthMeters: Float): Float {
        if (physicalWidthMeters <= 0.001f) return 0f
        return imageWidthPx / physicalWidthMeters
    }

    /**
     * Calculate required distance (in meters) to achieve target Texel Density T:
     * D_target = (imageWidthPx * focalLengthMm) / (targetDensity * sensorWidthMm)
     */
    fun computeRequiredDistance(
        targetDensityPxPerM: Float,
        imageWidthPx: Int,
        sensorWidthMm: Float,
        focalLengthMm: Float
    ): Float {
        val t = if (targetDensityPxPerM > 10f) targetDensityPxPerM else 1024f
        val fl = if (focalLengthMm > 0.1f) focalLengthMm else DEFAULT_FOCAL_LENGTH_MM
        val sw = if (sensorWidthMm > 0.1f) sensorWidthMm else DEFAULT_SENSOR_WIDTH_MM
        return (imageWidthPx * fl) / (t * sw)
    }

    /**
     * Generate user-friendly step guidance text based on current vs target distance.
     */
    fun getStepGuidance(currentDistMeters: Float, targetDistMeters: Float, targetDensity: Int): Guidance {
        val deltaMeters = currentDistMeters - targetDistMeters
        val deltaCm = (deltaMeters * 100f).roundToInt()

        return when {
            deltaMeters > 0.04f -> Guidance("STEP CLOSER: -${deltaCm} cm", false, -deltaCm)
            deltaMeters < -0.04f -> Guidance("STEP BACK: +${-deltaCm} cm", false, -deltaCm)
            else -> Guidance("PERFECT DISTANCE (${targetDensity} px/m)", true, 0)
        }
    }
}
