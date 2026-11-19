package com.gonzalowebdev.sr

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView

class PlayerActivity : Activity() {

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        findViewById<View>(R.id.root).padForSystemBars()
        val vv = findViewById<VideoView>(R.id.video)
        vv.setMediaController(MediaController(this))
        vv.setVideoURI(intent.data)
        vv.setOnPreparedListener { vv.start() }
        vv.setOnErrorListener { _, _, _ ->
            Toast.makeText(this, "No se pudo reproducir el video", Toast.LENGTH_LONG).show()
            true
        }
    }
}
