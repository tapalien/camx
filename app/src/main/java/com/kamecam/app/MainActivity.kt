package com.kamecam.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PorterDuff
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.effects.OverlayEffect
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.hypot

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var tvStatus: TextView
    private lateinit var btnRecord: Button
    private lateinit var btnShare: Button
    private lateinit var btnPreset: Button

    private val tracker = MotionTracker()
    private val renderer = TrailRenderer()
    private var presetIndex = 0

    private lateinit var analysisExecutor: ExecutorService
    private lateinit var overlayThread: HandlerThread
    private var overlay: OverlayEffect? = null

    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var lastVideoUri: Uri? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result[Manifest.permission.CAMERA] == true || hasCameraPermission()) {
                startCamera()
            } else {
                Toast.makeText(this, "Izin kamera diperlukan", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        tvStatus = findViewById(R.id.tvStatus)
        btnRecord = findViewById(R.id.btnRecord)
        btnShare = findViewById(R.id.btnShare)
        btnPreset = findViewById(R.id.btnPreset)

        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER

        analysisExecutor = Executors.newSingleThreadExecutor()
        overlayThread = HandlerThread("overlay").also { it.start() }

        updatePresetUi()
        btnPreset.setOnClickListener {
            presetIndex = (presetIndex + 1) % PRESETS.size
            renderer.preset = PRESETS[presetIndex]
            renderer.clear()
            updatePresetUi()
        }
        btnRecord.setOnClickListener {
            if (recording == null) startRecording() else stopRecording()
        }
        btnShare.setOnClickListener { shareLastVideo() }

        if (hasCameraPermission()) {
            startCamera()
        } else {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            )
        }
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasAudioPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun updatePresetUi() {
        btnPreset.text = PRESETS[presetIndex].name
        renderer.preset = PRESETS[presetIndex]
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.from(
                        Quality.FHD,
                        FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                    )
                )
                .build()
            val vc = VideoCapture.withOutput(recorder)
            videoCapture = vc

            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(640, 480),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                            )
                        )
                        .build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(analysisExecutor) { image ->
                try {
                    val det = tracker.analyze(image)
                    if (det != null) {
                        val inv = Matrix()
                        if (image.imageInfo.sensorToBufferTransform.invert(inv)) {
                            // Ubah koordinat buffer analisis -> koordinat sensor,
                            // supaya cocok dengan overlay yang digambar di preview + video.
                            val p = floatArrayOf(det.x, det.y, 0f, 0f, 1f, 0f)
                            inv.mapPoints(p)
                            val unit = hypot(p[4] - p[2], p[5] - p[3])
                            val width = unit * image.width * 0.012f
                            renderer.add(
                                p[0], p[1], det.color, width,
                                SystemClock.elapsedRealtimeNanos()
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "analisis gagal", e)
                } finally {
                    image.close()
                }
            }

            overlay?.close()
            val effect = OverlayEffect(
                CameraEffect.PREVIEW or CameraEffect.VIDEO_CAPTURE,
                0,
                Handler(overlayThread.looper)
            ) { t -> Log.e(TAG, "overlay error", t) }
            effect.setOnDrawListener { frame ->
                val canvas = frame.overlayCanvas
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                canvas.setMatrix(frame.sensorToBufferTransform)
                renderer.draw(canvas, SystemClock.elapsedRealtimeNanos())
                true
            }
            overlay = effect

            val group = UseCaseGroup.Builder()
                .addUseCase(preview)
                .addUseCase(vc)
                .addUseCase(analysis)
                .addEffect(effect)
                .build()

            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, group)
                tvStatus.text = "Siap. Pasang HP di tripod, lalu gerakkan mainan."
            } catch (e: Exception) {
                Log.e(TAG, "bind gagal", e)
                tvStatus.text = "Kamera gagal: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        val vc = videoCapture ?: return
        val name = "KameCam_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/KameCam")
        }
        val options = MediaStoreOutputOptions.Builder(
            contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(values).build()

        var pending = vc.output.prepareRecording(this, options)
        if (hasAudioPermission()) pending = pending.withAudioEnabled()

        // Kunci orientasi selama merekam supaya video tidak rusak.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        btnShare.visibility = android.view.View.GONE

        recording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    btnRecord.text = "STOP"
                    tvStatus.text = "● Merekam..."
                }
                is VideoRecordEvent.Finalize -> {
                    recording = null
                    btnRecord.text = "REKAM"
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    if (event.hasError()) {
                        tvStatus.text = "Rekaman gagal (kode ${event.error})"
                    } else {
                        lastVideoUri = event.outputResults.outputUri
                        tvStatus.text = "Tersimpan di Movies/KameCam"
                        btnShare.visibility = android.view.View.VISIBLE
                    }
                }
                else -> {}
            }
        }
    }

    private fun stopRecording() {
        recording?.stop()
    }

    private fun shareLastVideo() {
        val uri = lastVideoUri ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Bagikan video"))
    }

    override fun onDestroy() {
        super.onDestroy()
        recording?.stop()
        overlay?.close()
        overlayThread.quitSafely()
        analysisExecutor.shutdown()
    }

    companion object {
        private const val TAG = "KameCam"
    }
}
