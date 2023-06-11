package dev.steenbakker.mobile_scanner
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import android.graphics.BitmapFactory
import android.media.Image
import android.app.Activity
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import dev.steenbakker.mobile_scanner.objects.DetectionSpeed
import dev.steenbakker.mobile_scanner.objects.MobileScannerStartParameters
import io.flutter.view.TextureRegistry
import kotlin.math.roundToInt
import android.util.Log
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions

class MobileScanner(
    private val activity: Activity,
    private val textureRegistry: TextureRegistry,
    private val mobileScannerCallback: MobileScannerCallback,
    private val mobileScannerErrorCallback: MobileScannerErrorCallback
) {

    /// Internal variables
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var textureEntry: TextureRegistry.SurfaceTextureEntry? = null
    private var scanner = BarcodeScanning.getClient()
    private var lastScanned: List<String?>? = null
    private var scannerTimeout = false

    /// Configurable variables
    var scanWindow: List<Float>? = null
    private var detectionSpeed: DetectionSpeed = DetectionSpeed.NO_DUPLICATES
    private var detectionTimeout: Long = 250
    private var returnImage = false


    /**
     * callback for the camera. Every frame is passed through this function.
     */
    @ExperimentalGetImage
    val captureOutput = ImageAnalysis.Analyzer { imageProxy -> // YUV_420_888 format
        val mediaImage = imageProxy.image ?: return@Analyzer
        val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

        if (detectionSpeed == DetectionSpeed.NORMAL && scannerTimeout) {
            imageProxy.close()
            return@Analyzer
        } else if (detectionSpeed == DetectionSpeed.NORMAL) {
            scannerTimeout = true
        }

        scanner.process(inputImage)
            .addOnSuccessListener { barcodes ->
                if (detectionSpeed == DetectionSpeed.NO_DUPLICATES) {
                    val newScannedBarcodes = barcodes.map { barcode -> barcode.rawValue }
                    if (newScannedBarcodes == lastScanned) {
                        // New scanned is duplicate, returning
                        return@addOnSuccessListener
                    }
                    if (newScannedBarcodes.isNotEmpty()) lastScanned = newScannedBarcodes
                }

                val barcodeMap: MutableList<Map<String, Any?>> = mutableListOf()

                for (barcode in barcodes) {
                    if (scanWindow != null) {
                        val match = isBarcodeInScanWindow(scanWindow!!, barcode, imageProxy)
                        if (!match) {
                            continue
                        } else {
                            barcodeMap.add(barcode.data)
                        }
                    } else {
                        barcodeMap.add(barcode.data)
                    }
                }

                if (barcodeMap.isNotEmpty()) {
                    mobileScannerCallback(
                        barcodeMap,
                        if (returnImage) mediaImage.toByteArray() else null,
                        if (returnImage) mediaImage.width else null,
                        if (returnImage) mediaImage.height else null
                    )
                }
            }
            .addOnFailureListener { e ->
                mobileScannerErrorCallback(
                    e.localizedMessage ?: e.toString()
                )
            }
            .addOnCompleteListener { imageProxy.close() }

        if (detectionSpeed == DetectionSpeed.NORMAL) {
            // Set timer and continue
            Handler(Looper.getMainLooper()).postDelayed({
                scannerTimeout = false
            }, detectionTimeout)
        }
    }

    // scales the scanWindow to the provided inputImage and checks if that scaled
    // scanWindow contains the barcode
    private fun isBarcodeInScanWindow(
        scanWindow: List<Float>,
        barcode: Barcode,
        inputImage: ImageProxy
    ): Boolean {
        val barcodeBoundingBox = barcode.boundingBox ?: return false

        val imageWidth = inputImage.height
        val imageHeight = inputImage.width

        val left = (scanWindow[0] * imageWidth).roundToInt()
        val top = (scanWindow[1] * imageHeight).roundToInt()
        val right = (scanWindow[2] * imageWidth).roundToInt()
        val bottom = (scanWindow[3] * imageHeight).roundToInt()

        val scaledScanWindow = Rect(left, top, right, bottom)
        return scaledScanWindow.contains(barcodeBoundingBox)
    }

    /**
     * Start barcode scanning by initializing the camera and barcode scanner.
     */
    
    
    @ExperimentalGetImage
    fun start(
        barcodeScannerOptions: BarcodeScannerOptions?,
        returnImage: Boolean,
        cameraPosition: CameraSelector,
        torch: Boolean,
        detectionSpeed: DetectionSpeed,
        torchStateCallback: TorchStateCallback,
        zoomScaleStateCallback: ZoomScaleStateCallback,
        mobileScannerStartedCallback: MobileScannerStartedCallback,
        detectionTimeout: Long
    ) {

        

         // Create face detection options for ML Kit
        val faceDetectorOptions = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .build()

        // Create a face detector using ML Kit
         val faceDetector = FaceDetection.getClient(faceDetectorOptions)
            
        
        this.detectionSpeed = detectionSpeed
        this.detectionTimeout = detectionTimeout
        this.returnImage = returnImage

        if (camera?.cameraInfo != null && preview != null && textureEntry != null) {
            throw AlreadyStarted()
        }

        scanner = if (barcodeScannerOptions != null) {
            BarcodeScanning.getClient(barcodeScannerOptions)
        } else {
            BarcodeScanning.getClient()
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(activity)
        val executor = ContextCompat.getMainExecutor(activity)

        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            if (cameraProvider == null) {
                throw CameraError()
            }
            cameraProvider!!.unbindAll()
            textureEntry = textureRegistry.createSurfaceTexture()

            // Preview
            val surfaceProvider = Preview.SurfaceProvider { request ->
                val texture = textureEntry!!.surfaceTexture()
                texture.setDefaultBufferSize(
                    request.resolution.width,
                    request.resolution.height
                )

                val surface = Surface(texture)
                request.provideSurface(surface, executor) { }
            }

            // Build the preview to be shown on the Flutter texture
            val previewBuilder = Preview.Builder()
            preview = previewBuilder.build().apply { setSurfaceProvider(surfaceProvider) }

             // Build the analyzer to be passed on to MLKit
        val analysisBuilder = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
//                analysisBuilder.setTargetResolution(Size(1440, 1920))
        val analysis = analysisBuilder.build().apply {
            setAnalyzer(executor) { imageProxy ->
                val mediaImage = imageProxy.image
                if (mediaImage != null) {
                    val rotationDegrees = imageProxy.imageInfo.rotationDegrees

                    // Convert MediaImage to FirebaseVisionImage
                    val visionImage = InputImage.fromMediaImage(mediaImage, rotationDegrees)

                    // Perform face detection
                    faceDetector.process(visionImage)
                        .addOnSuccessListener { faces ->
                            // Blur the detected faces in the image
                            // val blurredImage = blurFaces(mediaImage, faces)
                             ///// Added
                            val paint = Paint().apply {
                                color = Color.BLACK
                                strokeWidth = 3f
                            }
                            val imageBitmap = createBitmapFromImageProxy(mediaImage)
                            if(imageBitmap != null){
                                val canvas = Canvas(imageBitmap)
                                // Clear the canvas
                                canvas.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR)

                                // Draw the original image on the canvas
                                canvas.drawBitmap(imageBitmap, 0f, 0f, null)

                                // Blur the detected faces in the image
                                for (face in faces) {
                                    val faceBounds = face.boundingBox
                                    Log.d("FACE_RECO", "FACERECO HERE 2")
                                    if (faceBounds != null) {
                                        canvas.drawRect(faceBounds, paint)
                                        Log.d("FACE_RECO", "FACERECO HERE 3")
                                    }
                                }
                            }else{
                                Log.d("imageBitmapAux", "null")
                            }
                            

                            // Release the image resources
                            imageProxy.close()
                        }
                        .addOnFailureListener { exception ->
                            // Handle face detection error
                            imageProxy.close()
                        }
                } else {
                    imageProxy.close()
                }
            }
        }

        camera = cameraProvider!!.bindToLifecycle(
            activity as LifecycleOwner,
            cameraPosition,
            preview,
            analysis
        )

            // Register the torch listener
            camera!!.cameraInfo.torchState.observe(activity) { state ->
                // TorchState.OFF = 0; TorchState.ON = 1
                torchStateCallback(state)
            }

            // Register the zoom scale listener
            camera!!.cameraInfo.zoomState.observe(activity) { state ->
                zoomScaleStateCallback(state.linearZoom.toDouble())
            }


            // Enable torch if provided
            camera!!.cameraControl.enableTorch(torch)

            val resolution = analysis.resolutionInfo!!.resolution
            val portrait = camera!!.cameraInfo.sensorRotationDegrees % 180 == 0
            val width = resolution.width.toDouble()
            val height = resolution.height.toDouble()

            mobileScannerStartedCallback(
                MobileScannerStartParameters(
                    if (portrait) width else height,
                    if (portrait) height else width,
                    camera!!.cameraInfo.hasFlashUnit(),
                    textureEntry!!.id()
                )
            )
        }, executor)

    }

    /**
     * Stop barcode scanning.
     */
    fun stop() {
        if (camera == null && preview == null) {
            throw AlreadyStopped()
        }

        val owner = activity as LifecycleOwner
        camera?.cameraInfo?.torchState?.removeObservers(owner)
        cameraProvider?.unbindAll()
        textureEntry?.release()

        camera = null
        preview = null
        textureEntry = null
        cameraProvider = null
    }


    /**
     * Toggles the flash light on or off.
     */
    fun toggleTorch(enableTorch: Boolean) {
        if (camera == null) {
            throw TorchWhenStopped()
        }
        camera!!.cameraControl.enableTorch(enableTorch)
    }

    /**
     * Analyze a single image.
     */
    fun analyzeImage(image: Uri, analyzerCallback: AnalyzerCallback) {
        val inputImage = InputImage.fromFilePath(activity, image)

        scanner.process(inputImage)
            .addOnSuccessListener { barcodes ->
                val barcodeMap = barcodes.map { barcode -> barcode.data }

                if (barcodeMap.isNotEmpty()) {
                    analyzerCallback(barcodeMap)
                } else {
                    analyzerCallback(null)
                }
            }
            .addOnFailureListener { e ->
                mobileScannerErrorCallback(
                    e.localizedMessage ?: e.toString()
                )
            }
    }

    /**
     * Set the zoom rate of the camera.
     */
    fun setScale(scale: Double) {
        if (camera == null) throw ZoomWhenStopped()
        if (scale > 1.0 || scale < 0) throw ZoomNotInRange()
        camera!!.cameraControl.setLinearZoom(scale.toFloat())
    }

    /**
     * Reset the zoom rate of the camera.
     */
    fun resetScale() {
        if (camera == null) throw ZoomWhenStopped()
        camera!!.cameraControl.setZoomRatio(1f)
    }

    // private fun createBitmapFromImageProxy(image: Image): Bitmap? {
    //     val buffer = image.planes[0].buffer
    //     val bytes = ByteArray(buffer.capacity())
    //     buffer.get(bytes)
    //     if (bytes != null) {
    //         return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    //     } else {
    //         return null
    //         // Handle the case when bytes is null
    //     }
        
    // }

    fun createBitmapFromImageProxy(image: Image): Bitmap? {
    val nv21 = yuv420888ToNv21(image)
    val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
    return yuvImage.toBitmap()
}

private fun YuvImage.toBitmap(): Bitmap? {
    val out = ByteArrayOutputStream()
    if (!compressToJpeg(Rect(0, 0, width, height), 100, out))
        return null
    val imageBytes: ByteArray = out.toByteArray()
    return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
}

private fun yuv420888ToNv21(image: ImageProxy): ByteArray {
    val pixelCount = image.cropRect.width() * image.cropRect.height()
    val pixelSizeBits = ImageFormat.getBitsPerPixel(ImageFormat.YUV_420_888)
    val outputBuffer = ByteArray(pixelCount * pixelSizeBits / 8)
    imageToByteBuffer(image, outputBuffer, pixelCount)
    return outputBuffer
}

private fun imageToByteBuffer(image: ImageProxy, outputBuffer: ByteArray, pixelCount: Int) {
    assert(image.format == ImageFormat.YUV_420_888)

    val imageCrop = image.cropRect
    val imagePlanes = image.planes

    imagePlanes.forEachIndexed { planeIndex, plane ->
        // How many values are read in input for each output value written
        // Only the Y plane has a value for every pixel, U and V have half the resolution i.e.
        //
        // Y Plane            U Plane    V Plane
        // ===============    =======    =======
        // Y Y Y Y Y Y Y Y    U U U U    V V V V
        // Y Y Y Y Y Y Y Y    U U U U    V V V V
        // Y Y Y Y Y Y Y Y    U U U U    V V V V
        // Y Y Y Y Y Y Y Y    U U U U    V V V V
        // Y Y Y Y Y Y Y Y
        // Y Y Y Y Y Y Y Y
        // Y Y Y Y Y Y Y Y
        val outputStride: Int

        // The index in the output buffer the next value will be written at
        // For Y it's zero, for U and V we start at the end of Y and interleave them i.e.
        //
        // First chunk        Second chunk
        // ===============    ===============
        // Y Y Y Y Y Y Y Y    V U V U V U V U
        // Y Y Y Y Y Y Y Y    V U V U V U V U
        // Y Y Y Y Y Y Y Y    V U V U V U V U
        // Y Y Y Y Y Y Y Y    V U V U V U V U
        // Y Y Y Y Y Y Y Y
        // Y Y Y Y Y Y Y Y
        // Y Y Y Y Y Y Y Y
        var outputOffset: Int

        when (planeIndex) {
            0 -> {
                outputStride = 1
                outputOffset = 0
            }
            1 -> {
                outputStride = 2
                // For NV21 format, U is in odd-numbered indices
                outputOffset = pixelCount + 1
            }
            2 -> {
                outputStride = 2
                // For NV21 format, V is in even-numbered indices
                outputOffset = pixelCount
            }
            else -> {
                // Image contains more than 3 planes, something strange is going on
                return@forEachIndexed
            }
        }

        val planeBuffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride

        // We have to divide the width and height by two if it's not the Y plane
        val planeCrop = if (planeIndex == 0) {
            imageCrop
        } else {
            Rect(
                    imageCrop.left / 2,
                    imageCrop.top / 2,
                    imageCrop.right / 2,
                    imageCrop.bottom / 2
            )
        }

        val planeWidth = planeCrop.width()
        val planeHeight = planeCrop.height()

        // Intermediate buffer used to store the bytes of each row
        val rowBuffer = ByteArray(plane.rowStride)

        // Size of each row in bytes
        val rowLength = if (pixelStride == 1 && outputStride == 1) {
            planeWidth
        } else {
            // Take into account that the stride may include data from pixels other than this
            // particular plane and row, and that could be between pixels and not after every
            // pixel:
            //
            // |---- Pixel stride ----|                    Row ends here --> |
            // | Pixel 1 | Other Data | Pixel 2 | Other Data | ... | Pixel N |
            //
            // We need to get (N-1) * (pixel stride bytes) per row + 1 byte for the last pixel
            (planeWidth - 1) * pixelStride + 1
        }

        for (row in 0 until planeHeight) {
            // Move buffer position to the beginning of this row
            planeBuffer.position(
                    (row + planeCrop.top) * rowStride + planeCrop.left * pixelStride)

            if (pixelStride == 1 && outputStride == 1) {
                // When there is a single stride value for pixel and output, we can just copy
                // the entire row in a single step
                planeBuffer.get(outputBuffer, outputOffset, rowLength)
                outputOffset += rowLength
            } else {
                // When either pixel or output have a stride > 1 we must copy pixel by pixel
                planeBuffer.get(rowBuffer, 0, rowLength)
                for (col in 0 until planeWidth) {
                    outputBuffer[outputOffset] = rowBuffer[col * pixelStride]
                    outputOffset += outputStride
                }
            }
        }
    }
}


}
