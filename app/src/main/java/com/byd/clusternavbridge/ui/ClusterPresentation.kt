package com.byd.clusternavbridge.ui

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import android.view.WindowManager
import android.widget.TextView
import com.byd.clusternavbridge.R
import com.byd.clusternavbridge.model.NavState

class ClusterPresentation(
    outerContext: Context,
    display: Display
) : Presentation(outerContext, display) {

    private var tvSourceApp: TextView? = null
    private var tvDistance: TextView? = null
    private var tvInstruction: TextView? = null
    private var tvEta: TextView? = null
    private var tvRemaining: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Secondary presentation window setup
        window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        setContentView(R.layout.layout_cluster_presentation)

        tvSourceApp = findViewById(R.id.tvPresSourceApp)
        tvDistance = findViewById(R.id.tvPresDistance)
        tvInstruction = findViewById(R.id.tvPresInstruction)
        tvEta = findViewById(R.id.tvPresEta)
        tvRemaining = findViewById(R.id.tvPresRemaining)
    }

    fun updateNav(state: NavState) {
        tvSourceApp?.text = if (state.sourceName.isNotBlank()) state.sourceName.uppercase() else "NAVIGAZIONE"
        tvDistance?.text = if (state.distance.isNotBlank()) state.distance else "--"
        tvInstruction?.text = if (state.instruction.isNotBlank()) state.instruction else "In viaggio..."
        tvEta?.text = if (state.eta.isNotBlank()) "Arrivo ${state.eta}" else "In navigazione"
        tvRemaining?.text = if (state.remainingTimeDistance.isNotBlank()) "• ${state.remainingTimeDistance}" else ""
    }
}
