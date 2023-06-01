package com.photopro

import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat

//File with useful functions for MainActivity

//The savedInstanceState is passed only on the first camera initialization at the beginning of onCreate
fun startCamera(activity: MainActivity, preferences: SharedPreferences, savedInstanceState: Bundle? = null) : Pair<ImageCapture, MultiPurposeAnalyzer?>{
    var imageCapture: ImageCapture? = null

    // This is used to bind the lifecycle of cameras to the lifecycle owner (the main activity).
    // This eliminates the task of opening and closing the camera since CameraX is lifecycle-aware.
    val cameraProviderFuture = ProcessCameraProvider.getInstance(activity)

    // Created outside cameraProviderFuture listener to be able to return the analyzer and activate the frame averaging
    val imageAnalysisCreatorResult = createImageAnalysis(activity, preferences)
    val imageAnalysis : ImageAnalysis? = imageAnalysisCreatorResult.first
    val analyzer : MultiPurposeAnalyzer? = imageAnalysisCreatorResult.second

    cameraProviderFuture.addListener({
        // Used to bind the lifecycle of cameras to the lifecycle owner
        val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

        // Attach the preview of the camera to the UI widget that will contain that preview
        val cameraPreview : PreviewView = activity.findViewById(R.id.camera_preview)

        val preview = Preview.Builder().build()
            .also {
                it.setSurfaceProvider(cameraPreview.surfaceProvider)
            }

        // Select back camera as a default when starting at first
        var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        var cameraSelected = Constant.CAMERA_BACK

        //Change only if not camera back (default value
        if(preferences.getInt(SharedPrefs.CAMERA_FACING_KEY, Constant.CAMERA_BACK) == Constant.CAMERA_FRONT) {
            cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
            cameraSelected = Constant.CAMERA_FRONT
        }

        // Make sure nothing is bound to the cameraProvider,
        // and then bind our cameraSelector and preview object to the cameraProvider.
        try {
            // Unbind use cases before rebinding
            cameraProvider.unbindAll()

            // Bind use cases to camera

            val camera =
                if(imageAnalysis == null){
                    cameraProvider.bindToLifecycle(activity, cameraSelector, preview, imageCapture)
                }else{
                    cameraProvider.bindToLifecycle(activity, cameraSelector, preview, imageCapture, imageAnalysis)
                }

            if(preferences.getInt(SharedPrefs.FLASH_KEY, Constant.FLASH_OFF) == Constant.FLASH_ALWAYS_ON){
                camera.cameraControl.enableTorch(true)
            }else{
                camera.cameraControl.enableTorch(false)
            }

            //If it is the first initialisation of the camera after the creation of the activity
            if(savedInstanceState != null){
                //If the camera remained the same
                if(savedInstanceState.getInt(Constant.ZOOM_VALUE_CAMERA_KEY) == cameraSelected) {
                    //Keep the zoom value. So the zoom is kept when changing from landscape to portrait
                    val zoomValue = savedInstanceState.getFloat(Constant.ZOOM_VALUE_KEY)
                    camera.cameraControl.setZoomRatio(zoomValue)
                }
            }

            activity.camera = camera

        } catch(exc: Exception) {
            Log.e(Constant.TAG, "Use case binding failed", exc)
        }

    }, ContextCompat.getMainExecutor(activity))

    //When starting the camera, build the ImageCapture object that will be able to take pictures

    val savedFlashValue = preferences.getInt(SharedPrefs.FLASH_KEY, Constant.FLASH_OFF)
    imageCapture = ImageCapture.Builder().build()

    when(savedFlashValue){
        Constant.FLASH_OFF -> imageCapture.flashMode = ImageCapture.FLASH_MODE_OFF
        Constant.FLASH_ON -> imageCapture.flashMode = ImageCapture.FLASH_MODE_ON
        Constant.FLASH_AUTO -> imageCapture.flashMode = ImageCapture.FLASH_MODE_AUTO
        Constant.FLASH_ALWAYS_ON -> imageCapture.flashMode = ImageCapture.FLASH_MODE_OFF  //Flash always one overrides the imageCapture flash mode

        else -> imageCapture.flashMode = ImageCapture.FLASH_MODE_OFF
    }

    return Pair(imageCapture, analyzer)
}