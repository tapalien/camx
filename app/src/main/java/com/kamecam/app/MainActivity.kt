package com.kamecam.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.util.Rational
import android.util.Size
import android.view.Surface
import android.view.View
import android.widget.Chronometer
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.AspectRatioStrategy
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.hypot

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var topBar: LinearLayout
    private lateinit var bottomPanel: LinearLayout
    private lateinit var tvStatus: TextView
    private lateinit var presetContainer: LinearLayout
    private lateinit var recordButton: FrameLayout
    private lateinit var recordInner: View
    private lateinit var btnShare: ImageButton
    private lateinit var recBadge: LinearLayout
    private lateinit var chrono: Chronometer

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
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars =
            false

        previewView = findViewById(R.id.previewView)
        topBar = findViewById(R.id.topBar)
        bottomPanel = findViewById(R.id.bottomPanel)
        tvStatus = findViewById(R.id.tvStatus)
        presetContainer = findViewById(R.id.presetContainer)
        recordButton = findViewById(R.id.recordButton)
        recordInner = findViewById(R.id.recordInner)
        btnShare = findViewById(R.id.btnShare)
        recBadge = findViewById(R.id.recBadge)
        chrono = findViewById(R.id.chrono)

        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER

        // Beri ruang untuk status bar dan navigation bar.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            topBar.setPadding(dp(16), bars.top + dp(12), dp(16), dp(24))
            bottomPanel.setPadding(0, dp(32), 0, bars.bottom + dp(16))
            insets
        }

        analysisExecutor = Executors.newSingleThreadExecutor()
        overlayThread = HandlerThread("overlay").also { it.start() }

        buildPresetChips()

        // Tekan lama judul untuk mode debug (cincin biru muda di posisi deteksi).
        findViewById<TextView>(R.id.tvTitle).setOnLongClickListener {
            renderer.debug = !renderer.debug
            tvStatus.text = if (renderer.debug) "Mode debug AKTIF" else "Mode debug mati"
            true
        }

        recordButton.setOnClickListener {
            if (recording == null) startRecording() else stopRecording()
        }
        btnShare.setOnClickListener { shareLastVideo() }

        if (hasCameraPermission()) {
            previewView.post { startCamera() }
        } else {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            )
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasAudioPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun buildPresetChips() {
        presetContainer.removeAllViews()
        renderer.preset = PRESETS[presetIndex]
        PRESETS.forEachIndexed { index, preset ->
            val chip = TextView(this).apply {
                text = preset.name
                setTextColor(Color.WHITE)
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(18), dp(10), dp(18), dp(10))
                setBackgroundResource(R.drawable.chip_bg)
                isSelected = index == presetIndex
                setOnClickListener { selectPreset(index) }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(6)
                marginEnd = dp(6)
            }
            presetContainer.addView(chip, lp)
        }
    }

    private fun selectPreset(index: Int) {
        presetIndex = index
        renderer.preset = PRESETS[index]
        renderer.clear()
        for (i in 0 until presetContainer.childCount) {
            presetContainer.getChildAt(i).isSelected = i == index
        }
    }

    private fun setRecordingUi(on: Boolean) {
        recordInner.setBackgroundResource(
            if (on) R.drawable.record_square else R.drawable.record_dot
        )
        val size = dp(if (on) 30 else 58)
        val lp = recordInner.layoutParams as FrameLayout.LayoutParams
        lp.width = size
        lp.height = size
        recordInner.layoutParams = lp
        recBadge.visibility = if (on) View.VISIBLE else View.GONE
        if (on) {
            chrono.base = SystemClock.elapsedRealtime()
            chrono.start()
        } else {
            chrono.stop()
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()

            val ratio16x9 = AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY

            val preview = Preview.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder().setAspectRatioStrategy(ratio16x9).build()
                )
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

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
                        .setAspectRatioStrategy(ratio16x9)
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(1280, 720),
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
                        val toBuffer = image.imageInfo.sensorToBufferTransformMatrix
                        if (toBuffer.invert(inv)) {
                            // Koordinat buffer analisis -> koordinat sensor, supaya cocok
                            // dengan overlay yang digambar di preview dan video.
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

            // ViewPort: semua use case (preview, video, analisis) memakai bidang pandang
            // yang sama, supaya video tidak distretch dan efek tepat di posisi benda.
            val portrait =
                resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
            val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
            val viewPort = ViewPort.Builder(
                if (portrait) Rational(9, 16) else Rational(16, 9),
                rotation
            ).setScaleType(ViewPort.FILL_CENTER).build()

            val group = UseCaseGroup.Builder()
                .setViewPort(viewPort)
                .addUseCase(preview)
                .addUseCase(vc)
                .addUseCase(analysis)
                .addEffect(effect)
                .build()

            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, group)
                tvStatus.text = "Pasang HP di tripod, lalu gerakkan mainan"
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
        btnShare.visibility = View.INVISIBLE

        recording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            if (event is VideoRecordEvent.Start) {
                setRecordingUi(true)
                tvStatus.text = "Merekam..."
            } else if (event is VideoRecordEvent.Finalize) {
                recording = null
                setRecordingUi(false)
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                if (event.hasError()) {
                    tvStatus.text = "Rekaman gagal (kode ${event.error})"
                } else {
                    lastVideoUri = event.outputResults.outputUri
                    tvStatus.text = "Tersimpan di Movies/KameCam"
                    btnShare.visibility = View.VISIBLE
                }
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
