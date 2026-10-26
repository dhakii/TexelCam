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
import android.view.ScaleGestureDetector
import android.view.GestureDetector
import android.view.MotionEvent
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import android.widget.Button
import com.texelcam.app.databinding.ActivityMainBinding
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

    private lateinit var binding: ActivityMainBinding

    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null

    private lateinit var densityButtons: List<Button>

    private lateinit var orientationTracker: OrientationTracker
    private var lastAlignmentState: AlignmentState? = null

    // Optics & Metrics
    private var focalLengthMm: Float = TexelCalculator.DEFAULT_FOCAL_LENGTH_MM
    private var sensorWidthMm: Float = TexelCalculator.DEFAULT_SENSOR_WIDTH_MM
    private var sensorHeightMm: Float = TexelCalculator.DEFAULT_SENSOR_HEIGHT_MM
    private var imageWidthPx: Int = 4000
    private var imageHeightPx: Int = 3000

    private var zoomRatio: Float = 1.0f

    private var isAeAfLocked: Boolean = false

    private var isAutoCaptureEnabled: Boolean = false
    private var perfectAlignmentStartTime: Long = 0
    private var isCapturing: Boolean = false

    private var currentDistanceMeters: Float = 0.85f
    private var isManualDistance: Boolean = false
    private var targetDensity: Int = 2048
    private var isTorchOn: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
        val btn512 = binding.btnDensity512
        val btn1024 = binding.btnDensity1024
        val btn2048 = binding.btnDensity2048
        val btn4096 = binding.btnDensity4096
        densityButtons = listOf(btn512, btn1024, btn2048, btn4096)

        btn512.setOnClickListener { setTargetDensity(512, btn512) }
        btn1024.setOnClickListener { setTargetDensity(1024, btn1024) }
        btn2048.setOnClickListener { setTargetDensity(2048, btn2048) }
        btn4096.setOnClickListener { setTargetDensity(4096, btn4096) }

        binding.sbDistance.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    isManualDistance = true
                    // Progress 10..250 maps to 0.10m..2.50m
                    val dist = (progress.coerceAtLeast(10)) / 100f
                    currentDistanceMeters = dist
                    binding.tvManualDistVal.text = String.format(Locale.US, "%.2fm*", dist)
                    updateMetrics()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.btnSurfaceMode.setOnClickListener {
            val newMode = if (orientationTracker.currentMode == SurfaceMode.WALL) {
                SurfaceMode.FLOOR
            } else {
                SurfaceMode.WALL
            }
            orientationTracker.currentMode = newMode
            binding.btnSurfaceMode.text = newMode.name
            binding.btnSurfaceMode.setTextColor(
                if (newMode == SurfaceMode.FLOOR) ContextCompat.getColor(this, R.color.accent_neon_green)
                else Color.WHITE
            )
        }

        binding.btnTorch.setOnClickListener {
            isTorchOn = !isTorchOn
            camera?.cameraControl?.enableTorch(isTorchOn)
            binding.btnTorch.setTextColor(
                if (isTorchOn) ContextCompat.getColor(this, R.color.accent_neon_yellow)
                else Color.WHITE
            )
        }

        binding.btnShutter.setOnClickListener {
            takePhoto()
        }
        binding.btnAeAfLock.setOnClickListener {
            isAeAfLocked = !isAeAfLocked
            if (isAeAfLocked) {
                // Lock focus and exposure
                camera?.cameraControl?.cancelFocusAndMetering()
                binding.btnAeAfLock.text = "AE/AF LOCKED"
                binding.btnAeAfLock.setTextColor(ContextCompat.getColor(this, R.color.accent_neon_yellow))
            } else {
                binding.btnAeAfLock.text = "AE/AF UNLOCKED"
                binding.btnAeAfLock.setTextColor(Color.WHITE)
            }
        }

        binding.btnAutoCapture.setOnClickListener {
            isAutoCaptureEnabled = !isAutoCaptureEnabled
            if (isAutoCaptureEnabled) {
                binding.btnAutoCapture.text = "AUTO: ON"
                binding.btnAutoCapture.setTextColor(ContextCompat.getColor(this, R.color.accent_neon_green))
            } else {
                binding.btnAutoCapture.text = "AUTO: OFF"
                binding.btnAutoCapture.setTextColor(Color.WHITE)
                perfectAlignmentStartTime = 0
                binding.hudView.updateAutoCaptureProgress(0f)
            }
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
                binding.hudView.updateOrientation(state.pitchErrorDeg, state.rollErrorDeg, state.isAligned)
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
                                binding.tvManualDistVal.text = String.format(Locale.US, "%.2fm", computedDist)
                                binding.sbDistance.progress = (computedDist * 100).toInt().coerceIn(0, 250)
                                updateMetrics()
                            }
                        }
                    }
                }
            })

            val preview = previewBuilder.build().also {
                it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            }

            // Pinch-to-zoom
            val scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val currentZoomRatio = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
                    val delta = detector.scaleFactor
                    val newZoom = currentZoomRatio * delta
                    camera?.cameraControl?.setZoomRatio(newZoom)

                    // Update our tracked zoom
                    zoomRatio = newZoom
                    updateMetrics()
                    return true
                }
            })

            val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    val factory = SurfaceOrientedMeteringPointFactory(
                        binding.viewFinder.width.toFloat(),
                        binding.viewFinder.height.toFloat()
                    )
                    val point = factory.createPoint(e.x, e.y)
                    val action = FocusMeteringAction.Builder(point).build()
                    camera?.cameraControl?.startFocusAndMetering(action)
                    return true
                }
            })

            binding.viewFinder.setOnTouchListener { _, event ->
                scaleGestureDetector.onTouchEvent(event)
                gestureDetector.onTouchEvent(event)
                true
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
        val physW = TexelCalculator.computePhysicalWidth(currentDistanceMeters, sensorWidthMm, focalLengthMm, zoomRatio)
        val physH = TexelCalculator.computePhysicalHeight(currentDistanceMeters, sensorHeightMm, focalLengthMm, zoomRatio)

        val liveDensity = TexelCalculator.computeTexelDensity(imageWidthPx, physW).roundToInt()

        binding.tvLiveDensity.text = "TD: $liveDensity px/m"
        binding.tvDistance.text = String.format(Locale.US, "Dist: %.2f m", currentDistanceMeters)

        val targetDist = TexelCalculator.computeRequiredDistance(
            targetDensity.toFloat(),
            imageWidthPx,
            sensorWidthMm,
            focalLengthMm,
            zoomRatio
        )

        val guidance = TexelCalculator.getStepGuidance(currentDistanceMeters, targetDist, targetDensity)
        binding.tvGuidance.text = guidance.message

        val isAligned = lastAlignmentState?.isAligned == true
        if (isAligned && guidance.isOptimal) {
            binding.tvGuidance.setTextColor(ContextCompat.getColor(this, R.color.accent_neon_green))
        } else if (guidance.isOptimal) {
            binding.tvGuidance.setTextColor(ContextCompat.getColor(this, R.color.accent_neon_cyan))
        } else {
            binding.tvGuidance.setTextColor(ContextCompat.getColor(this, R.color.accent_neon_yellow))
        }

        binding.hudView.updateMetrics(currentDistanceMeters, physW, physH, targetDensity)

        // Smart Auto-Capture Logic
        if (isAutoCaptureEnabled && !isCapturing) {
            if (isAligned && guidance.isOptimal) {
                if (perfectAlignmentStartTime == 0L) {
                    perfectAlignmentStartTime = System.currentTimeMillis()
                }

                val elapsed = System.currentTimeMillis() - perfectAlignmentStartTime
                val progress = (elapsed / 1500f).coerceIn(0f, 1f)
                binding.hudView.updateAutoCaptureProgress(progress)

                if (elapsed >= 1500L) {
                    isCapturing = true
                    perfectAlignmentStartTime = 0
                    binding.hudView.updateAutoCaptureProgress(0f)
                    takePhoto()
                }
            } else {
                perfectAlignmentStartTime = 0
                binding.hudView.updateAutoCaptureProgress(0f)
            }
        }
    }

    private fun takePhoto() {
        val imageCap = imageCapture ?: return

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "TEX_${timeStamp}.jpg"

        val outputDir = getOutputDirectory()
        val photoFile = File(outputDir, fileName)


        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TexelCam")
            }
        }
        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()

        imageCap.takePicture(
            outputOptions,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e("TexelCam", "Photo capture failed: ${exc.message}", exc)
                    runOnUiThread {
                        isCapturing = false
                        Toast.makeText(this@MainActivity, "Capture failed: ${exc.message}", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val physW = TexelCalculator.computePhysicalWidth(currentDistanceMeters, sensorWidthMm, focalLengthMm, zoomRatio)
                    val physH = TexelCalculator.computePhysicalHeight(currentDistanceMeters, sensorHeightMm, focalLengthMm, zoomRatio)
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

                    val sidecars = PbrMetadataWriter.writeSidecarFiles(this@MainActivity, meta)

                    runOnUiThread {
                        isCapturing = false
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
