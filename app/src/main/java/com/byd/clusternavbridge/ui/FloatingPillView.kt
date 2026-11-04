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
import com.byd.clusternavbridge.model.NavState
import kotlin.math.abs

class FloatingPillView(
    private val context: Context,
    private val onModeSelected: (Int) -> Unit,
    private val onToggleVoice: (() -> Unit)? = null
) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var pillView: View? = null
    var isShowing = false
        private set

    private var currentMode = ClusterCommBridge.MODE_SEMPLICE
    private var isVoiceMuted = false

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    private var tvStatus: TextView? = null
    private var btnPiccolo: TextView? = null
    private var btnGrande: TextView? = null
    private var btnSemplice: TextView? = null
    private var btnOff: TextView? = null
    private var btnVoice: TextView? = null

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
        btnVoice = view.findViewById(R.id.btnPillVoice)

        btnPiccolo?.visibility = View.VISIBLE
        btnGrande?.visibility = View.VISIBLE
        btnSemplice?.visibility = View.VISIBLE
        btnOff?.visibility = View.VISIBLE
        btnVoice?.visibility = View.VISIBLE

        updateButtonStates()

        btnPiccolo?.setOnClickListener {
            onModeSelected(ClusterCommBridge.MODE_PICCOLO)
        }

        btnGrande?.setOnClickListener {
            onModeSelected(ClusterCommBridge.MODE_GRANDE)
        }

        btnSemplice?.setOnClickListener {
            onModeSelected(ClusterCommBridge.MODE_SEMPLICE)
        }

        btnOff?.setOnClickListener {
            onModeSelected(ClusterCommBridge.MODE_OFF)
        }

        btnVoice?.setOnClickListener {
            onToggleVoice?.invoke()
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
                    val moved = abs(event.rawX - initialTouchX) > 10 || abs(event.rawY - initialTouchY) > 10
                    if (!moved) {
                        view.performClick()
                    }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(view, layoutParams)
        pillView = view
        isShowing = true
    }

    fun hide() {
        if (!isShowing) return
        pillView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
        }
        pillView = null
        isShowing = false
    }

    fun updateMode(mode: Int) {
        currentMode = mode
        updateButtonStates()
    }

    fun updateVoiceMute(isMuted: Boolean) {
        isVoiceMuted = isMuted
        updateButtonStates()
    }

    private fun updateButtonStates() {
        btnPiccolo?.setBackgroundColor(if (currentMode == ClusterCommBridge.MODE_PICCOLO) Color.parseColor("#00E5FF") else Color.parseColor("#223344"))
        btnPiccolo?.setTextColor(if (currentMode == ClusterCommBridge.MODE_PICCOLO) Color.BLACK else Color.WHITE)

        btnGrande?.setBackgroundColor(if (currentMode == ClusterCommBridge.MODE_GRANDE || currentMode == ClusterCommBridge.MODE_INTERO) Color.parseColor("#00E5FF") else Color.parseColor("#223344"))
        btnGrande?.setTextColor(if (currentMode == ClusterCommBridge.MODE_GRANDE || currentMode == ClusterCommBridge.MODE_INTERO) Color.BLACK else Color.WHITE)

        btnSemplice?.setBackgroundColor(if (currentMode == ClusterCommBridge.MODE_SEMPLICE) Color.parseColor("#00E676") else Color.parseColor("#223344"))
        btnSemplice?.setTextColor(if (currentMode == ClusterCommBridge.MODE_SEMPLICE) Color.BLACK else Color.WHITE)

        btnOff?.setBackgroundColor(if (currentMode == ClusterCommBridge.MODE_OFF) Color.parseColor("#FF5252") else Color.parseColor("#223344"))
        btnOff?.setTextColor(if (currentMode == ClusterCommBridge.MODE_OFF) Color.WHITE else Color.parseColor("#B0BEC5"))

        btnVoice?.setBackgroundColor(if (!isVoiceMuted) Color.parseColor("#00E676") else Color.parseColor("#37474F"))
        btnVoice?.setTextColor(if (!isVoiceMuted) Color.BLACK else Color.parseColor("#90A4AE"))
        btnVoice?.text = if (!isVoiceMuted) "Voce ON" else "Muto"
    }

    fun updateNavState(state: NavState) {
        tvStatus?.text = if (state.isActive) {
            if (state.distance.isNotEmpty()) "${state.sourceName.take(4)} • ${state.distance}" else state.sourceName
        } else {
            "Standby"
        }
    }
}
