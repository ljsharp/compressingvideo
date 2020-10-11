package com.ljsharp.compressingvideo

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.os.*
import android.provider.MediaStore
import android.text.format.DateUtils
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
//import com.ljsharp.compressingvideo.getFileSize
//import com.ljsharp.compressingvideo.getMediaPath
import com.abedelazizshe.lightcompressorlibrary.CompressionListener
import com.abedelazizshe.lightcompressorlibrary.VideoCompressor
import com.abedelazizshe.lightcompressorlibrary.VideoQuality
import com.bumptech.glide.Glide
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.content_main.*
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

class MainActivity : AppCompatActivity() {

    companion object {
        const val REQUEST_SELECT_VIDEO = 0
    }

    private lateinit var path: String
    private lateinit var extension: String
    private lateinit var uploadVideo: ExtendedFloatingActionButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setReadStoragePermission()
        uploadVideo = findViewById(R.id.upload_video)
        uploadVideo.visibility = View.GONE
        fab.setOnClickListener {
            pickVideo()
        }

        uploadVideo.setOnClickListener {
            if (txtFileName.text != null) {
                UploadUtility(this).uploadFile(path, txtFileName?.text.toString() + extension)
            }
            else {
                UploadUtility(this).uploadFile(path)
            }
        }

        cancel.setOnClickListener {
            VideoCompressor.cancel()
        }

        videoLayout.setOnClickListener { VideoPlayer.start(this, path) }
    }

    //Pick a video file from device
    private fun pickVideo() {
        val intent = Intent()
        intent.apply {
            type = "video/*"
            action = Intent.ACTION_PICK
        }
        startActivityForResult(Intent.createChooser(intent, "Select video"), REQUEST_SELECT_VIDEO)
    }

    @SuppressLint("SetTextI18n")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {

        mainContents.visibility = View.GONE
        timeTaken.text = ""
        newSize.text = ""

        if (resultCode == Activity.RESULT_OK)
            if (requestCode == REQUEST_SELECT_VIDEO) {
                if (data != null && data.data != null) {
                    val uri = data.data
                    newPath.text = uri?.let { getPathFromUri(this, it) }

                    uri?.let {
                        mainContents.visibility = View.VISIBLE
                        Glide.with(applicationContext).load(uri).into(videoImage)

                        GlobalScope.launch {
                            // run in background as it can take a long time if the video is big,
                            // this implementation is not the best way to do it,
                            // todo(abed): improve threading

                            val job = async { getMediaPath(applicationContext, uri) }
                            path = job.await()

                            val desFile = saveVideoFile(path)
                            extension = getVideoExtension(uri)
                            desFile?.let {
                                var time = 0L
                                VideoCompressor.start(
                                        path,
                                        desFile.path,
                                        object : CompressionListener {
                                            override fun onProgress(percent: Float) {
                                                //Update UI
                                                if (percent <= 100 && percent.toInt() % 5 == 0)
                                                    runOnUiThread {
                                                        progress.text = "${percent.toLong()}%"
                                                        progressBar.progress = percent.toInt()
                                                    }
                                            }

                                            override fun onStart() {
                                                time = System.currentTimeMillis()
                                                progress.visibility = View.VISIBLE
                                                progressBar.visibility = View.VISIBLE
                                                originalSize.text =
                                                        "Original size: ${getFileSize(File(path).length())}"
                                                progress.text = ""
                                                progressBar.progress = 0
                                            }

                                            override fun onSuccess() {
                                                val newSizeValue = desFile.length()
                                                val newPathValue = desFile.path.toString()

                                                newSize.text =
                                                        "Size after compression: ${getFileSize(newSizeValue)}"
                                                newPath.text = "New Path after compression: $newPathValue"

                                                time = System.currentTimeMillis() - time
                                                timeTaken.text =
                                                        "Duration: ${DateUtils.formatElapsedTime(time / 1000)}"

                                                path = desFile.path

                                                Looper.myLooper()?.let {
                                                    Handler(it).postDelayed({
                                                        uploadVideo.visibility = View.VISIBLE
                                                        progress.visibility = View.GONE
                                                        progressBar.visibility = View.GONE
                                                    }, 50)
                                                }
                                            }

                                            override fun onFailure(failureMessage: String) {
                                                progress.text = failureMessage
                                                Log.wtf("failureMessage", failureMessage)
                                            }

                                            override fun onCancelled() {
                                                Log.wtf("TAG", "compression has been cancelled")
                                                // make UI changes, cleanup, etc
                                            }
                                        },
                                        VideoQuality.MEDIUM,
                                        isMinBitRateEnabled = false,
                                        keepOriginalResolution = true,
                                )
                            }
                        }
                    }
                }
            }

        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun setReadStoragePermission() {
        if (ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ) != PackageManager.PERMISSION_GRANTED
        ) {

            if (!ActivityCompat.shouldShowRequestPermissionRationale(
                            this,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE
                    )
            ) {
                ActivityCompat.requestPermissions(
                        this,
                        arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                        1
                )
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun saveVideoFile(filePath: String?): File? {
        filePath?.let {
            val videoFile = File(filePath)
            val videoFileName = "${System.currentTimeMillis()}_${videoFile.name}"
            val folderName = Environment.DIRECTORY_MOVIES
            if (Build.VERSION.SDK_INT >= 30) {

                val values = ContentValues().apply {

                    put(
                            MediaStore.Images.Media.DISPLAY_NAME,
                            videoFileName
                    )
                    put(MediaStore.Images.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Images.Media.RELATIVE_PATH, folderName)
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }

                val collection =
                        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

                val fileUri = applicationContext.contentResolver.insert(collection, values)

                fileUri?.let {
                    application.contentResolver.openFileDescriptor(fileUri, "rw")
                            .use { descriptor ->
                                descriptor?.let {
                                    FileOutputStream(descriptor.fileDescriptor).use { out ->
                                        FileInputStream(videoFile).use { inputStream ->
                                            val buf = ByteArray(4096)
                                            while (true) {
                                                val sz = inputStream.read(buf)
                                                if (sz <= 0) break
                                                out.write(buf, 0, sz)
                                            }
                                        }
                                    }
                                }
                            }

                    values.clear()
                    values.put(MediaStore.Video.Media.IS_PENDING, 0)
                    applicationContext.contentResolver.update(fileUri, values, null, null)

                    return File(getMediaPath(applicationContext, fileUri))
                }
            } else {
                val downloadsPath =
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val desFile = File(downloadsPath, videoFileName)

                if (desFile.exists())
                    desFile.delete()

                try {
                    desFile.createNewFile()
                } catch (e: IOException) {
                    e.printStackTrace()
                }

                return desFile
            }
        }
        return null
    }
}
