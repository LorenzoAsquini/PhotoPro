package com.project_photopro

import android.content.ContentValues
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.os.CountDownTimer
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils.bitmapToMat
import org.opencv.android.Utils.matToBitmap
import org.opencv.core.Core.addWeighted
import org.opencv.core.Mat
import org.opencv.core.Size
import java.io.OutputStream
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.accurate.AccuratePoseDetectorOptions
import com.google.mlkit.vision.common.InputImage
import java.util.Calendar

class MultiPurposeAnalyzer(private val activity: MainActivity, private val rotation: Int) : ImageAnalysis.Analyzer{

    private val preferences : SharedPreferences = activity.getSharedPreferences(SharedPrefs.SHARED_PREFERENCES_KEY,
        AppCompatActivity.MODE_PRIVATE
    )

    //Needed for PoseDetection
    private lateinit var listener : SmartDelayListener
    private lateinit var smartDelayRecognizer : PoseDetector

    //A person must be recognized for multiple frames before being sure that it is a person
    private var smartDelayFramesWithPerson = 0
    var personDetected = false
        set(value) {
            field = value

            if(!personDetected){
                //Hide timer if person is not detected
                val smartDelayTimer : TextView = activity.findViewById(R.id.smart_delay_timer)
                smartDelayTimer.visibility = View.INVISIBLE
            }
        }

    //Contains the time when the last photo was taken using smart delay
    //Wait a bit before starting to find a person again
    var smartDelayLastPhotoTaken : Long = 0

    //Global variable so it can be accessed without passing it as a parameter
    private var imageBitmap : Bitmap? = null

    private var forceNightMode = false

    //Running result of frame avg
    private var frameAvgResult : Mat? = null
    //Number of frames averaged till now
    var framesAveraged = -1

    init {
        OpenCVLoader.initDebug()
    }

    @ExperimentalGetImage
    override fun analyze(image: ImageProxy){

        val smartDelayValue = preferences.getInt(SharedPrefs.SMART_DELAY_KEY, Constant.SMART_DELAY_OFF)
        val nightModeValue = preferences.getInt(SharedPrefs.NIGHT_MODE_KEY, Constant.NIGHT_MODE_OFF)
        val frameAvgValue = preferences.getInt(SharedPrefs.FRAME_AVG_KEY, Constant.FRAME_AVG_OFF)

        if(nightModeValue == Constant.NIGHT_MODE_AUTO){
            imageBitmap = image.toBitmap()
            isNightModeActive()
        }

        if(frameAvgValue == Constant.FRAME_AVG_ON){
            //If averaging is happening, create imageBitmap if not already created
            if(framesAveraged >= 0){
                imageBitmap = imageBitmap ?: image.toBitmap()
                frameAvg()
            }
        }

        //Necessary to call as the last one because it may close the ImageProxy
        //If a person is detected, do not search again until the photo is taken

        if(smartDelayValue == Constant.SMART_DELAY_ON && !personDetected){

            val timeBetweenDetections = 2000 //ms
            if(Calendar.getInstance().timeInMillis - smartDelayLastPhotoTaken >= timeBetweenDetections) {
                smartDelay(image)
            }else{
                image.close()  //Close to prevent blocking
            }
        }

        //Close the imageProxy if it was not already closed by the smart delay function
        //This will not happen if the smartDelay is stopped between two detections, so it is close above
        if(!(smartDelayValue == Constant.SMART_DELAY_ON && !personDetected)){
            image.close()
        }
    }

    ////////////////////

    private fun isNightModeActive()
    {
        val isDark = (getAverageBrightness() < 80)

        //Check after one second if the brightness level has changed
        object : CountDownTimer(1000, 1000) {
            override fun onTick(millisUntilFinished: Long) {}

            override fun onFinish() {
                val brightness = getAverageBrightness()

                //It is still dark. Do not create a new camera if it is not necessary
                if(isDark && brightness < 80 && !forceNightMode) {
                    val nighModeButton: ImageButton = activity.findViewById(R.id.night_mode_button)
                    nighModeButton.setColorFilter(activity.getColor(R.color.night_mode_is_on_color))
                    forceNightMode = true
                    activity.startCameraWrapper(forceNightMode = true)
                }

                //It is still bright. Do not create a new camera if it is not necessary
                else if (!isDark && brightness >= 80 && forceNightMode)
                {
                    val nighModeButton: ImageButton = activity.findViewById(R.id.night_mode_button)
                    nighModeButton.setColorFilter(activity.getColor(R.color.white))
                    forceNightMode = false
                    activity.startCameraWrapper(forceNightMode = false)
                }
            }
        }.start()

    }

    private fun getAverageBrightness(): Double
    {
        var sum = 0
        val totalPixels = imageBitmap!!.width * imageBitmap!!.height
        val pixels = IntArray(totalPixels)
        imageBitmap!!.getPixels(pixels, 0, imageBitmap!!.width, 0, 0, imageBitmap!!.width, imageBitmap!!.height)

        for (pixel in pixels) {
            //luminance formula
            sum += (0.299 * Color.red(pixel) + 0.587 * Color.green(pixel) + 0.114 * Color.blue(pixel)).toInt()
        }

        return sum.toDouble() / totalPixels.toDouble()
    }

    ////////////////////

    fun startFrameAvg() {
        //Start capturing a picture with frame average
        framesAveraged = 0

        //Change color to make visible that image averaging is happening
        val frameAvgButton: ImageButton = activity.findViewById(R.id.frame_avg_button)
        frameAvgButton.setColorFilter(activity.getColor(R.color.active_frame_avg_color))
    }

    private fun frameAvg(){
        //Load current frame in Mat
        val currentFrame = Mat()
        bitmapToMat(imageBitmap, currentFrame)

        if(frameAvgResult == null){
            //Create the result Mat with the same characteristics as the input images
            frameAvgResult = Mat(Size(currentFrame.width().toDouble(), currentFrame.height().toDouble()), currentFrame.type())
        }
        //Add weighted to add current frame to result
        val alpha = 1.0/(framesAveraged + 1)
        val beta = 1.0 - alpha
        addWeighted(currentFrame, alpha, frameAvgResult, beta, 0.0, frameAvgResult)

        //Increase the number of frames used
        framesAveraged++

        val framesToAverage = preferences.getInt(SharedPrefs.NR_FRAMES_TO_AVERAGE_KEY, Constant.DEFAULT_FRAMES_TO_AVERAGE)

        //If enough frames are averaged
        if(framesAveraged >= framesToAverage){
            //Create bitmap for result from Mat
            val resultBitmap = imageBitmap!!.copy(imageBitmap!!.config, true)
            matToBitmap(frameAvgResult, resultBitmap)

            //Reverse portrait is not supported by the app
            //Image delivered by the analyzer are not rotated correctly
            val rotationDegrees =
                when(rotation){
                    Surface.ROTATION_0 -> 90.0f
                    Surface.ROTATION_90 -> 0.0f
                    Surface.ROTATION_270 -> 180.0f
                    else -> 0.0f
                }

            //Rotate correctly the final image
            val matrix = Matrix()
            matrix.setRotate(rotationDegrees)
            val rotatedResultBitmap = Bitmap.createBitmap(resultBitmap, 0, 0, resultBitmap.width, resultBitmap.height, matrix, true)

            val contentValues = getSaveImageContentValues()

            //Get Uri where to save the image
            val uri: Uri? = activity.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                saveImageToStream(rotatedResultBitmap, activity.contentResolver.openOutputStream(uri))
                activity.contentResolver.update(uri, contentValues, null, null)
            }

            //Vibration when frame avg has ended
            vibratePhone(activity, 50)

            //Clear memory and stop frame averaging
            frameAvgResult = null
            framesAveraged = -1

            //Set the image color to white when not in used
            val frameAvgButton: ImageButton = activity.findViewById(R.id.frame_avg_button)
            frameAvgButton.setColorFilter(activity.getColor(R.color.white))

            return
        }
    }

    private fun saveImageToStream(bitmap: Bitmap, outputStream: OutputStream?) {
        if (outputStream != null) {
            try {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
                outputStream.close()
                Log.i(Constant.TAG, "Photo saved")
            } catch (e: Exception) {
                Log.e(Constant.TAG, "Photo save failed: ${e.printStackTrace()}")
            }
        }
    }

    ////////////////////

    //Initialize the PoseDetector for smart delay
    private fun mlPoseDetection(){
        val options = AccuratePoseDetectorOptions.Builder()
            .setDetectorMode(AccuratePoseDetectorOptions.SINGLE_IMAGE_MODE)
            .build()
        smartDelayRecognizer = PoseDetection.getClient(options)
    }

    //Listener for smart delay
    fun addListener(ls: SmartDelayListener) {
        listener = ls
    }

    private fun notifyActivity(){
        listener.onPersonDetected(this)
    }

    @ExperimentalGetImage
    fun smartDelay(image: ImageProxy) {
        mlPoseDetection()

        val inImage = InputImage.fromMediaImage(image.image!!, image.imageInfo.rotationDegrees)

        smartDelayRecognizer.process(inImage)
            .addOnSuccessListener { posList ->

                var detectionLikelihood = 0F

                for (landmark in posList.allPoseLandmarks) {
                    if (!landmark.equals(null)) {
                        detectionLikelihood += landmark.inFrameLikelihood
                    }
                }

                Log.d("PoseDetection: ", "Pose detected: $detectionLikelihood")

                if (detectionLikelihood >= 27.0) {  //27.0 is a good value from various tests
                    smartDelayFramesWithPerson++
                    Log.d("PoseDetection: ", "Person in $smartDelayFramesWithPerson frames")
                } else {
                    //Reset counter if for one frame the person is not detected
                    smartDelayFramesWithPerson = 0
                }

                //Person recognized multiple times. It should be a real person
                if (smartDelayFramesWithPerson == 3) {
                    Log.d("PoseDetection: ", "Taking picture in few seconds")
                    smartDelayFramesWithPerson = 0
                    notifyActivity()
                }

                image.close()  //The ImageProxy MUST be closed here
            }
            .addOnFailureListener {
                image.close()
                Log.d(ContentValues.TAG, "Error from analyzer")
            }
    }
}