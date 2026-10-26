package com.texelcam.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null

    private lateinit var viewFinder: PreviewView
    private lateinit var hudView: TexelHudView
    private lateinit var tvLiveDensity: TextView
    private lateinit var tvDistance: TextView
    private lateinit var tvGuidance: TextView
    private lateinit var tvManualDistVal: TextView
    private lateinit var sbDistance: SeekBar
    private lateinit var btnShutter: ImageButton
    private lateinit var btnSurfaceMode: Button
    private lateinit var btnTorch: Button
    private lateinit var densityButtons: List<Button>

    private lateinit var orientationTracker: OrientationTracker
    private var lastAlignmentState: AlignmentState? = null

    // Optics & Metrics
    private var focalLengthMm: Float = TexelCalculator.DEFAULT_FOCAL_LENGTH_MM
    private var sensorWidthMm: Float = TexelCalculator.DEFAULT_SENSOR_WIDTH_MM
    private var sensorHeightMm: Float = TexelCalculator.DEFAULT_SENSOR_HEIGHT_MM
    private var imageWidthPx: Int = 4000
    private var imageHeightPx: Int = 3000

    private var currentDistanceMeters: Float = 0.85f
    private var isManualDistance: Boolean = false
    private var targetDensity: Int = 2048
    private var isTorchOn: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        hideSystemUI()

        cameraExecutor = Executors.newSingleThreadExecutor()

        initViews()
        initOrientationTracker()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }
    }

    private fun initViews() {
        viewFinder = findViewById(R.id.viewFinder)
        hudView = findViewById(R.id.hudView)
        tvLiveDensity = findViewById(R.id.tvLiveDensity)
        tvDistance = findViewById(R.id.tvDistance)
        tvGuidance = findViewById(R.id.tvGuidance)
        tvManualDistVal = findViewById(R.id.tvManualDistVal)
        sbDistance = findViewById(R.id.sbDistance)
        btnShutter = findViewById(R.id.btnShutter)
        btnSurfaceMode = findViewById(R.id.btnSurfaceMode)
        btnTorch = findViewById(R.id.btnTorch)

        val btn512 = findViewById<Button>(R.id.btnDensity512)
        val btn1024 = findViewById<Button>(R.id.btnDensity1024)
        val btn2048 = findViewById<Button>(R.id.btnDensity2048)
        val btn4096 = findViewById<Button>(R.id.btnDensity4096)
        densityButtons = listOf(btn512, btn1024, btn2048, btn4096)

        btn512.setOnClickListener { setTargetDensity(512, btn512) }
        btn1024.setOnClickListener { setTargetDensity(1024, btn1024) }
        btn2048.setOnClickListener { setTargetDensity(2048, btn2048) }
        btn4096.setOnClickListener { setTargetDensity(4096, btn4096) }

        sbDistance.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    isManualDistance = true
                    // Progress 10..250 maps to 0.10m..2.50m
                    val dist = (progress.coerceAtLeast(10)) / 100f
                    currentDistanceMeters = dist
                    tvManualDistVal.text = String.format(Locale.US, "%.2fm*", dist)
                    updateMetrics()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnSurfaceMode.setOnClickListener {
            val newMode = if (orientationTracker.currentMode == SurfaceMode.WALL) {
                SurfaceMode.FLOOR
            } else {
                SurfaceMode.WALL
            }
            orientationTracker.currentMode = newMode
            btnSurfaceMode.text = newMode.name
            btnSurfaceMode.setTextColor(
                if (newMode == SurfaceMode.FLOOR) ContextCompat.getColor(this, R.color.accent_neon_green)
                else Color.WHITE
            )
        }

        btnTorch.setOnClickListener {
            isTorchOn = !isTorchOn
            camera?.cameraControl?.enableTorch(isTorchOn)
            btnTorch.setTextColor(
                if (isTorchOn) ContextCompat.getColor(this, R.color.accent_neon_yellow)
                else Color.WHITE
            )
        }

        btnShutter.setOnClickListener {
            takePhoto()
        }
    }

    private fun setTargetDensity(density: Int, selectedBtn: Button) {
        targetDensity = density
        for (btn in densityButtons) {
            btn.setBackgroundResource(R.drawable.bg_pill_unselected)
            btn.setTextColor(Color.WHITE)
        }
        selectedBtn.setBackgroundResource(R.drawable.bg_pill_selected)
        selectedBtn.setTextColor(Color.BLACK)
        updateMetrics()
    }

    private fun initOrientationTracker() {
        orientationTracker = OrientationTracker(this) { state ->
            lastAlignmentState = state
            runOnUiThread {
                hudView.updateOrientation(state.pitchErrorDeg, state.rollErrorDeg, state.isAligned)
                updateMetrics()
            }
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview with Camera2 session capture callback to read autofocus distance
            val previewBuilder = Preview.Builder()
            val camera2PreviewExtender = Camera2Interop.Extender(previewBuilder)
            camera2PreviewExtender.setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    super.onCaptureCompleted(session, request, result)
                    if (!isManualDistance) {
                        // Lens focus distance is reported in diopters: d = 1 / distance_in_meters
                        val focusDistanceDiopters = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
                        if (focusDistanceDiopters != null && focusDistanceDiopters > 0.1f) {
                            val computedDist = (1.0f / focusDistanceDiopters).coerceIn(0.1f, 5.0f)
                            runOnUiThread {
                                currentDistanceMeters = computedDist
                                tvManualDistVal.text = String.format(Locale.US, "%.2fm", computedDist)
                                sbDistance.progress = (computedDist * 100).toInt().coerceIn(0, 250)
                                updateMetrics()
                            }
                        }
                    }
                }
            })

            val preview = previewBuilder.build().also {
                it.setSurfaceProvider(viewFinder.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)

                // Query Camera Characteristics (Intrinsics)
                camera?.cameraInfo?.let { camInfo ->
                    val cam2Info = Camera2CameraInfo.from(camInfo)
                    val focalLengths = cam2Info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    if (focalLengths != null && focalLengths.isNotEmpty()) {
                        focalLengthMm = focalLengths[0]
                    }
                    val sensorSize = cam2Info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                    if (sensorSize != null) {
                        sensorWidthMm = sensorSize.width
                        sensorHeightMm = sensorSize.height
                    }
                    val pixelArraySize = cam2Info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                    if (pixelArraySize != null) {
                        imageWidthPx = pixelArraySize.width
                        imageHeightPx = pixelArraySize.height
                    }
                }

                updateMetrics()

            } catch (exc: Exception) {
                Log.e("TexelCam", "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun updateMetrics() {
        val physW = TexelCalculator.computePhysicalWidth(currentDistanceMeters, sensorWidthMm, focalLengthMm)
        val physH = TexelCalculator.computePhysicalHeight(currentDistanceMeters, sensorHeightMm, focalLengthMm)
        val liveDensity = TexelCalculator.computeTexelDensity(imageWidthPx, physW).roundToInt()

        tvLiveDensity.text = "TD: $liveDensity px/m"
        tvDistance.text = String.format(Locale.US, "Dist: %.2f m", currentDistanceMeters)

        val targetDist = TexelCalculator.computeRequiredDistance(
            targetDensity.toFloat(),
            imageWidthPx,
            sensorWidthMm,
            focalLengthMm
        )

        val guidance = TexelCalculator.getStepGuidance(currentDistanceMeters, targetDist, targetDensity)
        tvGuidance.text = guidance.message

        val isAligned = lastAlignmentState?.isAligned == true
        if (isAligned && guidance.isOptimal) {
            tvGuidance.setTextColor(ContextCompat.getColor(this, R.color.accent_neon_green))
        } else if (guidance.isOptimal) {
            tvGuidance.setTextColor(ContextCompat.getColor(this, R.color.accent_neon_cyan))
        } else {
            tvGuidance.setTextColor(ContextCompat.getColor(this, R.color.accent_neon_yellow))
        }

        hudView.updateMetrics(currentDistanceMeters, physW, physH, targetDensity)
    }

    private fun takePhoto() {
        val imageCap = imageCapture ?: return

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "TEX_${timeStamp}.jpg"

        val outputDir = getOutputDirectory()
        val photoFile = File(outputDir, fileName)

        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCap.takePicture(
            outputOptions,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e("TexelCam", "Photo capture failed: ${exc.message}", exc)
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Capture failed: ${exc.message}", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val physW = TexelCalculator.computePhysicalWidth(currentDistanceMeters, sensorWidthMm, focalLengthMm)
                    val physH = TexelCalculator.computePhysicalHeight(currentDistanceMeters, sensorHeightMm, focalLengthMm)
                    val currentTd = TexelCalculator.computeTexelDensity(imageWidthPx, physW)

                    val meta = PbrMetadataWriter.CaptureMetadata(
                        imageFile = photoFile,
                        imageWidth = imageWidthPx,
                        imageHeight = imageHeightPx,
                        distanceMeters = currentDistanceMeters,
                        texelDensityPxPerM = currentTd,
                        physicalWidthMeters = physW,
                        physicalHeightMeters = physH,
                        alignmentState = lastAlignmentState ?: AlignmentState(0f, 0f, 0f, 0f, 0f, false, SurfaceMode.WALL),
                        targetDensityPxPerM = targetDensity,
                        focalLengthMm = focalLengthMm,
                        sensorWidthMm = sensorWidthMm,
                        sensorHeightMm = sensorHeightMm
                    )

                    val sidecars = PbrMetadataWriter.writeSidecarFiles(meta)

                    runOnUiThread {
                        val msg = if (sidecars != null) {
                            "Saved: $fileName + .combine.json"
                        } else {
                            "Saved: $fileName"
                        }
                        Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                    }
                }
            }
        )
    }

    private fun getOutputDirectory(): File {
        val mediaDir = externalMediaDirs.firstOrNull()?.let {
            File(it, "TexelCam").apply { mkdirs() }
        }
        return if (mediaDir != null && mediaDir.exists()) mediaDir else filesDir
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "Camera permission is required for TexelCam.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        orientationTracker.start()
        hideSystemUI()
    }

    override fun onPause() {
        super.onPause()
        orientationTracker.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
        }
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}
