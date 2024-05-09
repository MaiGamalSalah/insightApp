package com.example.insight

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.graphics.drawable.Drawable
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import android.os.Build
import android.hardware.camera2.CameraCaptureSession
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Log
import android.util.Size
import android.util.SparseIntArray
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Multipart
import retrofit2.http.Part
import retrofit2.http.POST
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.*
import java.util.concurrent.TimeUnit

// تعريف نتيجة البيانات التي يتم استلامها من السيرفر
data class ResultData(val label: String?, val confidence: Float?, val box: List<Float>?)

// واجهة API لـ Retrofit
interface ApiService {
    @Multipart
    @POST("predict")
    suspend fun convertImage(@Part image: MultipartBody.Part): List<ResultData>
}

// نشاط اكتشاف الأجسام
class DetectObjectActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    companion object {
        const val CAMERA_REQUEST_RESULT = 1
        const val TAG = "DetectObjectActivity"
    }

    private lateinit var tts: TextToSpeech
    private lateinit var textureView: TextureView
    private lateinit var cameraId: String
    private lateinit var backgroundHandlerThread: HandlerThread
    private lateinit var backgroundHandler: Handler
    private lateinit var cameraManager: CameraManager
    private lateinit var cameraDevice: CameraDevice
    private lateinit var captureRequestBuilder: CaptureRequest.Builder
    private lateinit var cameraCaptureSession: CameraCaptureSession
    private lateinit var imageReader: ImageReader
    private lateinit var previewSize: Size
    private var shouldProceedWithOnResume = true
    private val orientations = SparseIntArray(4).apply {
        append(Surface.ROTATION_0, 0)
        append(Surface.ROTATION_90, 90)
        append(Surface.ROTATION_180, 180)
        append(Surface.ROTATION_270, 270)
    }

    // قائمة الأصناف من ملف labels.txt
    private val labels = mutableListOf<String>()

    private var isTtsInitialized = false
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(200, TimeUnit.SECONDS)
        .readTimeout(200, TimeUnit.SECONDS)
        .writeTimeout(200, TimeUnit.SECONDS)
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl("http://20.185.38.99:5000/")
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    private val apiService = retrofit.create(ApiService::class.java)

    override fun onCreate(savedState: Bundle?) {
        super.onCreate(savedState)
        setContentView(R.layout.activity_detect_object)
        tts = TextToSpeech(this, this)

        textureView = findViewById(R.id.texture_view)
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        textureView.setOnClickListener { takePhoto() }

        loadLabels()

        if (!wasCameraPermissionGiven()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_REQUEST_RESULT)
            }
        }
        startBackgroundThread()
    }

    private fun loadLabels() {
        // تحميل قائمة الأصناف من ملف labels.txt
        val inputStream: InputStream = assets.open("labels.txt")
        inputStream.bufferedReader().useLines { lines ->
            labels.addAll(lines.map { it.trim().toLowerCase() })
        }
    }

    @SuppressLint("MissingPermission")
    override fun onResume() {
        super.onResume()
        startBackgroundThread()
        if (textureView.isAvailable && shouldProceedWithOnResume) {
            setupCamera()
        } else if (!textureView.isAvailable) {
            textureView.surfaceTextureListener = surfaceTextureListener
        }
        shouldProceedWithOnResume = !shouldProceedWithOnResume
    }

    private fun setupCamera() {
        val cameraIds = cameraManager.cameraIdList

        for (id in cameraIds) {
            val cameraCharacteristics = cameraManager.getCameraCharacteristics(id)

            if (cameraCharacteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) {
                cameraId = id
                val streamConfigMap = cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                previewSize = streamConfigMap?.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.height * it.width } ?: continue
                imageReader = ImageReader.newInstance(previewSize.width, previewSize.height, ImageFormat.JPEG, 1)
                imageReader.setOnImageAvailableListener(onImageAvailableListener, backgroundHandler)
                break
            }
        }
    }

    private fun wasCameraPermissionGiven(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            textureView.surfaceTextureListener?.onSurfaceTextureAvailable(textureView.surfaceTexture!!, textureView.width, textureView.height)
        } else {
            Toast.makeText(this, "Camera permission is needed to run this application", Toast.LENGTH_LONG).show()
            if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)) {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            }
        }
    }

    private fun takePhoto() {
        if (this::cameraCaptureSession.isInitialized) {
            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureRequestBuilder.addTarget(imageReader.surface)
            val rotation = windowManager.defaultDisplay.rotation
            captureRequestBuilder.set(CaptureRequest.JPEG_ORIENTATION, orientations.get(rotation))
            cameraCaptureSession.capture(captureRequestBuilder.build(), captureCallback, null)
        } else {
            Log.e(TAG, "cameraCaptureSession not initialized")
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectCamera() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val executor = ContextCompat.getMainExecutor(this)
            cameraManager.openCamera(cameraId, executor, cameraStateCallback)
        } else {
            cameraManager.openCamera(cameraId, cameraStateCallback, backgroundHandler)
        }
    }

    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        @SuppressLint("MissingPermission")
        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            if (wasCameraPermissionGiven()) {
                setupCamera()
                connectCamera()
            }
        }

        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {}

        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
            return true
        }

        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
    }

    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            val surfaceTexture = textureView.surfaceTexture
            surfaceTexture?.setDefaultBufferSize(previewSize.width, previewSize.height)
            val previewSurface = Surface(surfaceTexture)

            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            captureRequestBuilder.addTarget(previewSurface)

            cameraDevice.createCaptureSession(
                listOf(previewSurface, imageReader.surface),
                captureStateCallback,
                backgroundHandler
            )
        }

        override fun onDisconnected(cameraDevice: CameraDevice) {
            cameraDevice.close()
        }

        override fun onError(cameraDevice: CameraDevice, error: Int) {
            Log.e(TAG, "CameraDevice error: $error")
        }
    }

    private fun startBackgroundThread() {
        backgroundHandlerThread = HandlerThread("CameraThread")
        backgroundHandlerThread.start()
        backgroundHandler = Handler(backgroundHandlerThread.looper)
    }

    private fun stopBackgroundThread() {
        backgroundHandlerThread.quitSafely()
        backgroundHandlerThread.join()
    }

    private val captureStateCallback = object : CameraCaptureSession.StateCallback() {
        override fun onConfigureFailed(session: CameraCaptureSession) {
            Log.e(TAG, "CameraCaptureSession configuration failed")
        }

        override fun onConfigured(session: CameraCaptureSession) {
            cameraCaptureSession = session
            cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), null, backgroundHandler)
        }
    }


    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureStarted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            timestamp: Long,
            frameNumber: Long
        ) {}

        override fun onCaptureProgressed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            partialResult: CaptureResult
        ) {}

        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {}

    }

    private fun imageToBitmap(image: Image): Bitmap? {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        val options = BitmapFactory.Options()
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private val onImageAvailableListener = object : ImageReader.OnImageAvailableListener {
        override fun onImageAvailable(reader: ImageReader) {
            val image: Image = reader.acquireLatestImage()
            val bitmap = imageToBitmap(image)
            image.close()

            if (bitmap != null) {
                Glide.with(this@DetectObjectActivity)
                    .asBitmap()
                    .load(bitmap)
                    .into(object : CustomTarget<Bitmap>() {
                        override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                            val byteArrayOutputStream = ByteArrayOutputStream()
                            resource.compress(Bitmap.CompressFormat.JPEG, 100, byteArrayOutputStream)
                            val byteArray = byteArrayOutputStream.toByteArray()

                            val requestBody = RequestBody.create(
                                "image/jpeg".toMediaTypeOrNull(),
                                byteArray
                            )
                            val imagePart = MultipartBody.Part.createFormData(
                                "image",
                                "image.jpg",
                                requestBody
                            )

                            CoroutineScope(Dispatchers.IO).launch {
                                try {
                                    val response = apiService.convertImage(imagePart)
                                    Log.d(TAG, "Received response: $response")
                                    if (response.isNotEmpty()) {
                                        CoroutineScope(Dispatchers.Main).launch {
                                            if (isTtsInitialized) {
                                                // خذ قائمة الأصناف وقارنها مع labels
                                                val listOfObjects = response.mapNotNull { it.label?.toLowerCase() }
                                                val validLabels = listOfObjects.filter { labels.contains(it) }

                                                if (validLabels.isNotEmpty()) {
                                                    val speakText = validLabels.joinToString()
                                                    Log.d(TAG, "Speaking: $speakText")
                                                    tts.speak(speakText, TextToSpeech.QUEUE_FLUSH, null)
                                                } else {
                                                    Log.w(TAG, "No valid labels in the response")
                                                    tts.speak("No valid labels in the response.", TextToSpeech.QUEUE_FLUSH, null)
                                                }
                                            } else {
                                                Log.e(TAG, "TextToSpeech not initialized yet")
                                            }
                                        }
                                    } else {
                                        Log.e(TAG, "No objects detected in the image.")
                                        CoroutineScope(Dispatchers.Main).launch {
                                            if (isTtsInitialized) {
                                                tts.speak("No objects detected in the image.", TextToSpeech.QUEUE_FLUSH, null)
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "API call error: $e")
                                    CoroutineScope(Dispatchers.Main).launch {
                                        if (isTtsInitialized) {
                                            tts.speak("An error occurred during object detection.", TextToSpeech.QUEUE_FLUSH, null)
                                        }
                                    }
                                }
                            }
                        }

                        override fun onLoadCleared(placeholder: Drawable?) {
                            // تنظيف الموارد
                        }
                    })
            } else {
                Log.e(TAG, "Failed to convert image to Bitmap")
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isTtsInitialized = true
            val result = tts.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "Language not supported")
            } else {
                tts.speak("You can detect objects by clicking the screen now", TextToSpeech.QUEUE_FLUSH, null)
            }
        } else {
            Log.e(TAG, "TextToSpeech initialization failed")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopBackgroundThread()
        tts.shutdown()
    }
}
