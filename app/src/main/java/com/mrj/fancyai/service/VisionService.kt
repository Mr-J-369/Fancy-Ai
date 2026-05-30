package com.mrj.fancyai.service

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class VisionService(context: Context) {
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private val _recognizedText = Channel<String>(1)
    val recognizedText: Flow<String> = _recognizedText.receiveAsFlow()

    private var processCameraProvider: ProcessCameraProvider? = null

    fun initializeCamera(lifecycleOwner: LifecycleOwner) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(lifecycleOwner as Context)
        cameraProviderFuture.addListener(Runnable {
            try {
                processCameraProvider = cameraProviderFuture.get()

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    processImage(imageProxy)
                }

                val cameraProvider = processCameraProvider!!
                cameraProvider.unbindAll()

                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    imageAnalysis
                )
            } catch (e: Exception) {
                Log.e("VisionService", "Camera initialization failed", e)
            }
        }, { it.run() })
    }

    private fun processImage(imageProxy: ImageProxy) {
        try {
            val mediaImage = imageProxy.image
            if (mediaImage != null) {
                val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

                textRecognizer.process(image)
                    .addOnSuccessListener { visionText ->
                        if (visionText.text.isNotEmpty()) {
                            Log.d("VisionService", "Text detected: ${visionText.text.take(100)}")
                            _recognizedText.trySend(visionText.text)
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.e("VisionService", "Text recognition failed", e)
                    }
                    .addOnCompleteListener {
                        imageProxy.close()
                    }
            }
        } catch (e: Exception) {
            Log.e("VisionService", "Image processing error", e)
            imageProxy.close()
        }
    }

    fun stopCamera() {
        processCameraProvider?.unbindAll()
    }

    fun destroy() {
        stopCamera()
        cameraExecutor.shutdown()
        textRecognizer.close()
    }
}
