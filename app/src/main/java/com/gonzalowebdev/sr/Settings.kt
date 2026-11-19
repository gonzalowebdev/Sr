package com.gonzalowebdev.sr

import android.content.Context
import android.content.SharedPreferences

class Settings private constructor(private val sp: SharedPreferences) {

    var hevc: Boolean
        get() = sp.getBoolean("hevc", Encoders.hasHevc())
        set(v) = sp.edit().putBoolean("hevc", v).apply()

    var shortSide: Int
        get() = sp.getInt("res", 1080)
        set(v) = sp.edit().putInt("res", v).apply()

    var fps: Int
        get() = sp.getInt("fps", 30)
        set(v) = sp.edit().putInt("fps", v).apply()

    /** 0 = ahorro, 1 = balance, 2 = máxima */
    var quality: Int
        get() = sp.getInt("q", 1)
        set(v) = sp.edit().putInt("q", v).apply()

    var audio: Boolean
        get() = sp.getBoolean("audio", true)
        set(v) = sp.edit().putBoolean("audio", v).apply()

    companion object {
        fun of(c: Context) = Settings(c.applicationContext.getSharedPreferences("sr", Context.MODE_PRIVATE))
    }
}
