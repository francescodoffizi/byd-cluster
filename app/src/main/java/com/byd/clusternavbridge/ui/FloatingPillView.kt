package com.byd.clusternavbridge.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.byd.clusternavbridge.R
import com.byd.clusternavbridge.cluster.ClusterCommBridge
import kotlin.math.abs

class FloatingPillView(
    private val context: Context,
    private val onModeSelected: (Int) -> Unit,
    private val onReleaseFocus: () -> Unit
) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var pillView: View? = null
    private var isShowing = false

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    private var tvStatus: TextView? = null
    private var btnPiccolo: TextView? = null
    private var btnGrande: TextView? = null
    private var btnSemplice: TextView? = null
    private var btnOff: TextView? = null

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (isShowing) return

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 80
            y = 120
        }

        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.layout_floating_pill, null)

        tvStatus = view.findViewById(R.id.tvPillStatus)
        btnPiccolo = view.findViewById(R.id.btnPillPiccolo)
        btnGrande = view.findViewById(R.id.btnPillGrande)
        btnSemplice = view.findViewById(R.id.btnPillSemplice)
        btnOff = view.findViewById(R.id.btnPillOff)

        btnPiccolo?.setOnClickListener {
            highlightButton(ClusterCommBridge.MODE_PICCOLO)
            onModeSelected(ClusterCommBridge.MODE_PICCOLO)
        }

        btnGrande?.setOnClickListener {
            highlightButton(ClusterCommBridge.MODE_GRANDE)
            onModeSelected(ClusterCommBridge.MODE_GRANDE)
        }

        btnSemplice?.setOnClickListener {
            highlightButton(ClusterCommBridge.MODE_SEMPLICE)
            onModeSelected(ClusterCommBridge.MODE_SEMPLICE)
        }

        btnOff?.setOnClickListener {
            highlightButton(ClusterCommBridge.MODE_OFF)
            onReleaseFocus()
        }

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = (event.rawX - initialTouchX).toInt()
                    val deltaY = (event.rawY - initialTouchY).toInt()
                    layoutParams.x = initialX + deltaX
                    layoutParams.y = initialY + deltaY
                    windowManager.updateViewLayout(view, layoutParams)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val deltaX = abs(event.rawX - initialTouchX)
                    val deltaY = abs(event.rawY - initialTouchY)
                    if (deltaX < 10 && deltaY < 10) {
                        view.performClick()
                    }
                    true
                }
                else -> false
            }
        }

        try {
            windowManager.addView(view, layoutParams)
            pillView = view
            isShowing = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun hide() {
        if (!isShowing) return
        try {
            pillView?.let { windowManager.removeView(it) }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            pillView = null
            isShowing = false
        }
    }

    fun isShown(): Boolean = isShowing

    fun updateStatus(isActive: Boolean, appName: String) {
        tvStatus?.post {
            if (isActive) {
                tvStatus?.text = "● $appName"
                tvStatus?.setTextColor(Color.parseColor("#00E676"))
            } else {
                tvStatus?.text = "○ Nav"
                tvStatus?.setTextColor(Color.parseColor("#B0BEC5"))
            }
        }
    }

    private fun highlightButton(mode: Int) {
        btnPiccolo?.setTextColor(if (mode == ClusterCommBridge.MODE_PICCOLO) Color.parseColor("#00E676") else Color.WHITE)
        btnGrande?.setTextColor(if (mode == ClusterCommBridge.MODE_GRANDE) Color.parseColor("#00E676") else Color.parseColor("#00B0FF"))
        btnSemplice?.setTextColor(if (mode == ClusterCommBridge.MODE_SEMPLICE) Color.parseColor("#00E676") else Color.parseColor("#B0BEC5"))
    }
}
