package com.gonzalowebdev.sr

import android.content.Context
import android.graphics.Insets
import android.graphics.Point
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

inline fun safe(block: () -> Unit) {
    try {
        block()
    } catch (e: Exception) {
    }
}

/** Tamaño real de la pantalla en la orientación actual. */
@Suppress("DEPRECATION")
fun realSize(ctx: Context): Point {
    return try {
        val wm = ctx.getSystemService(WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            Point(b.width(), b.height())
        } else {
            val p = Point()
            wm.defaultDisplay.getRealSize(p)
            p
        }
    } catch (e: Exception) {
        val d = ctx.resources.displayMetrics
        Point(d.widthPixels, d.heightPixels)
    }
}

/** Respeta barras del sistema (edge-to-edge en Android 15+). */
fun View.padForSystemBars() {
    val l = paddingLeft
    val t = paddingTop
    val r = paddingRight
    val b = paddingBottom
    setOnApplyWindowInsetsListener { v, ins ->
        @Suppress("DEPRECATION")
        val bars: Insets = if (Build.VERSION.SDK_INT >= 30) {
            ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        } else {
            Insets.of(ins.systemWindowInsetLeft, ins.systemWindowInsetTop, ins.systemWindowInsetRight, ins.systemWindowInsetBottom)
        }
        v.setPadding(l + bars.left, t + bars.top, r + bars.right, b + bars.bottom)
        ins
    }
    requestApplyInsets()
}
