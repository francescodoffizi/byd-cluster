# Technical Paper: BYD DiLink Digital Instrument Cluster Architecture & Secondary Display Integration

**Subtitle:** Accessing and Leveraging the Secondary Cockpit Display for Custom Dashboards, Telemetry, and Auxiliary Applications on Qualcomm Snapdragon Automotive Platforms  
**Author:** Francesco D'Offizi  
**Version:** 1.0.0 (November 2026)  
**License:** GNU General Public License v3 (GPLv3)  

---

## 1. Executive Summary & Purpose

Modern electric vehicles manufactured by BYD and powered by the DiLink intelligent cockpit system (such as the Sealion 7, Seal, Atto 3, Dolphin, Tang, and Han) feature a dual-display cockpit architecture. While the central rotating touchscreen (Display 0) provides the primary In-Vehicle Infotainment (IVI) and user interaction surface, the digital instrument cluster positioned behind the steering wheel includes a dedicated hardware video pipe (`Display 1`).

This paper shares technical findings, architectural patterns, inter-process communication (IPC) protocols, and hardware display constraints with the automotive developer and open-source community. The objective is to demystify the hardware and software layers driving the instrument cluster, enabling third-party developers, hardware enthusiasts, and open-source contributors to build non-intrusive auxiliary applications—such as custom navigation heads-up displays (HUD), live EV powertrain telemetry monitors, media widgets, and secondary dashboards—utilizing the existing vehicle display pipeline without hardware modifications.

Furthermore, this paper documents the design and integration model of **BYD Cluster** (`BydClusterNavBridge`), an open-source bridge application that interfaces with the cockpit cluster service to route live navigation maneuvers and secondary map presentations onto the instrument panel.

Crucially, this document candidly outlines the unresolved technical challenges, timing sensitivities, GPU memory boundaries, and platform idiosyncrasies encountered during development, providing the community with an actionable foundation for future innovation.

```text
 +-----------------------------------------------------------------------+
 |                     QUALCOMM SNAPDRAGON AUTOMOTIVE                    |
 |                               (SA8155P)                               |
 +-----------------------------------+-----------------------------------+
                                     |
           +-------------------------+-------------------------+
           |                                                   |
           v                                                   v
 +-------------------+                               +-------------------+
 |   QNX HYPERVISOR  |                               | ANDROID AUTOMOTIVE|
 | (Safety & Cluster)|                               |    (DiLink IVI)   |
 +---------+---------+                               +---------+---------+
           |                                                   |
           | [Physical LCD Drive]                              | [Android SurfaceFlinger]
           |                                                   |
           v                                                   v
 +-------------------+           Display 1 EGL / HDMI        +-------------------+
 |  DIGITAL CLUSTER  | <==================================== |  SECONDARY DISPLAY|
 |   (Behind Wheel)  |       (1920x720 @ 60Hz Video Pipe)    |   (Cluster View)  |
 +-------------------+                                       +-------------------+
           ^                                                   |
           |                 Binder IPC (AIDL)                 |
           +---------------------------------------------------+
                   [com.ts.appservice.cluster.service]
```

---

## 2. Cockpit Topology & System Architecture

### 2.1 Hardware Foundation
The underlying cockpit computing platform is built upon the Qualcomm Snapdragon Automotive platform (SA8155P / DiLink 5.0 / DiLink 100), operating a high-performance heterogeneous multi-core SoC with dedicated Adreno GPU, Kryo CPU clusters, and hardware-accelerated video composition pipelines.

### 2.2 Dual Operating System Model
The system enforces strict separation of safety-critical functions and user entertainment services:
1. **Safety & Instrument Domain (QNX 7.1 Real-Time OS):**
   - Directly drives the physical LCD panel behind the steering wheel.
   - Manages critical vehicular telemetry: digital speedometer, power train status, battery state of charge (SoC), safety tell-tales, ADAS lane warnings, and vehicle fault indicators.
   - Hosts the primary display compositor responsible for blending native instrument graphics with external video feeds.
2. **Infotainment Domain (Android Automotive OS / DiLink):**
   - Operates the application runtime, multimedia subsystems, third-party Android packages, and wireless connectivity.
   - Governs `Display 0` (the 15.6-inch primary center touchscreen) and exposes an auxiliary virtual/hardware display interface designated as `Display 1`.

### 2.3 The Secondary Display Pipe (`Display 1`)
Within Android, the instrument cluster space is registered in the Android `DisplayManager` as a secondary physical output:
- **Display ID:** `1` (or index 1 of `DisplayManager.getDisplays()`)
- **Native Resolution:** `1920 x 720` pixels
- **Aspect Ratio:** `8:3` (Panoramic ultra-wide)
- **Refresh Rate:** 60 Hz
- **Color Depth:** 32-bit ARGB_8888
- **Compositor Routing:** Rendered via EGL / Hardware Composer through a virtualized display pipe shared with the QNX compositor.

---

## 3. Cluster Display Modes & Window Geometries

The QNX cluster compositor dynamically partitions the 1920x720 physical panel based on vehicular state and user configuration. Four primary operational modes govern the screen estate:

```text
====================================================================================
MODE 0 / 1: STANDARD OEM CLUSTER (MODE_OFF)
+----------------------------------------------------------------------------------+
| [Power/Gear]                 [SPEEDOMETER / TACHO]               [Battery / ODO] |
|                                                                                  |
|                         (Native Gauges & ADAS Display)                           |
+----------------------------------------------------------------------------------+

MODE 2: SIMPLE MODE / NATIVE VECTOR TBT (MODE_SEMPLICE)
+----------------------------------------------------------------------------------+
| [Speed]         |  <- 500 m   Via Roma             | [Battery / Power]           |
|                 |  Turn Right - ETA 18:45 (12.4 km)|                             |
|                 +----------------------------------+                             |
|                     (Rendered natively by QNX HUD pipeline - 0% Android GPU)     |
+----------------------------------------------------------------------------------+

MODE 3: SMALL WINDOW APERTURE (MODE_PICCOLO)
+----------------------------------------------------------------------------------+
| [Speedometer]   | [ADAS Center] |  +-----------------------------+ [Battery/Temp]|
|                 |               |  |  Display 1 Video Window     |               |
|                 |               |  |  (X=1340..1880, Y=220..640) |               |
|                 |               |  |  Custom UI / Mini-Map       |               |
|                 |               |  +-----------------------------+               |
+----------------------------------------------------------------------------------+

MODE 4: FULL-SCREEN PANORAMIC CLUSTER (MODE_INTERO / MODE_GRANDE)
+----------------------------------------------------------------------------------+
| [QNX Status Overlay: Speed / Battery / Safety Bar] (Y: 0 .. 214px - Semi-Opaque) |
|----------------------------------------------------------------------------------|
|                                                                                  |
|                  FULL 1920 x 720 SECONDARY DISPLAY SURFACE                       |
|               (Safe Content Area: Y=220px to Y=720px, Full Width)                |
|                                                                                  |
+----------------------------------------------------------------------------------+
====================================================================================
```

### 3.1 Mode 0 / Mode 1: Standard OEM Instrument Cluster (`MODE_OFF`)
- The cluster functions in default automotive gauge mode.
- Android `Display 1` video routing is deactivated or held in dormant state (`MAP_STATE_NAVI_CLOSE = 1`).

### 3.2 Mode 2: Simple Mode / Native Vector TBT (`MODE_SEMPLICE`)
- **Zero GPU Overhead:** The secondary HDMI video stream is not active.
- Vector telemetry (maneuver turn icon, distance to next intersection, road names, and destination ETA) is dispatched from Android via inter-process Binder IPC directly to the cluster service.
- The QNX instrument cluster renders vector icons, turn arrows, and distance indicators natively within its built-in HUD graphics pipeline.

### 3.3 Mode 3: Small Viewport Aperture (`MODE_PICCOLO`)
- The QNX compositor opens a dedicated sub-window (aperture) on the instrument panel (positioned on the right-hand quadrant or between primary gauge dials, bounded approximately between `X: 1340..1880px` and `Y: 220..640px` on models such as the Sealion 7).
- The Android presentation surface renders into this window, enabling auxiliary data display, navigation widgets, or mini-maps without occluding speed or safety alerts.

### 3.4 Mode 4: Full Panoramic Display (`MODE_INTERO` / `MODE_GRANDE`)
- The entire 1920x720 canvas is allocated to the Android secondary display.
- **Critical Display Constraint:** The upper band (`Y: 0 .. 214px`) is reserved by QNX for mandatory vehicle indicators (digital speed readout, turn signals, high beams, battery percentage, drive mode). Custom UI elements must maintain a top margin of at least 220px (`topMargin = 220px`) to prevent visual overlap.

---

## 4. Inter-Process Communication (IPC) Protocol

Communication between user-space applications and the instrument cluster is facilitated through a system service exposed by the DiLink automotive framework.

### 4.1 Cluster Communication Service Endpoints
- **Service Package:** `com.ts.appservice.cluster`
- **Service Class:** `com.ts.appservice.cluster.service.ClusterCoreService`
- **AIDL Descriptor:** `com.ts.lib.cluster.common.IClusterCommService`
- **Transaction Code:** `1` (`TRANSACTION_sendCommand`)
- **Primary Command ID:** `16` (`CMD_NAVI`)

### 4.2 Core AIDL Interface Definition
```aidl
package com.ts.lib.cluster.common;

interface IClusterCommService {
    void sendCommand(int commandId, in Bundle bundle);
    boolean registerCallback(int commandId, in IClusterCommCallback callback);
    boolean unregisterCallback(int commandId, in IClusterCommCallback callback);
    Bundle requestCacheData(int commandId, int dataType);
}
```

### 4.3 Data Type Specifications

| DataType ID | Constant Name | Purpose | Key Bundle Fields |
|---|---|---|---|
| `15` | `DATA_TYPE_NAVI_SWITCH` | Navigation session lifecycle control | `DataType=15`, `NaviSource=1`, `NaviTbtSwitch=(1\|0)`, `NaviState=(2\|0)` |
| `16` | `DATA_TYPE_NAVI_INFO` | Vector Turn-by-Turn maneuver payload | `DataType=16`, `NaviIconTbt=(Int)`, `NaviNextRoadDistance=(Meters)`, `NaviNextRoadName=(String)`, `NaviToDestTime=(Sec)`, `NaviDistanceToDest=(Meters)` |
| `30` | `DATA_TYPE_NAVI_SEND_DISPLAY_MODE` | Active display mode notification | `DataType=30`, `display_mode=(2\|3\|4)` |
| `31` | `DATA_TYPE_NAVI_PRESENTATION_STATE`| QNX presentation window aperture state | `DataType=31`, `mapType=1`, `state=(1\|5\|6\|7)` |
| `32` | `DATA_TYPE_NAVI_TBT_INFO` | Protobuf-encoded turn instruction packet | Standardized Protobuf payload structure |
| `33` | `DATA_TYPE_REQUEST_MAP_FOCUS` | Request QNX video routing to cluster | `msgType=3`, `DataType=33`, `mapType=1` |
| `34` | `DATA_TYPE_ABANDON_MAP_FOCUS` | Release QNX video routing | `msgType=3`, `DataType=34`, `mapType=1` |
| `35` | `DATA_TYPE_NAVI_STATE` | Navigation run state | `msgType=3`, `subtype=2`, `DataType=35`, `naviState=(0\|1)` |
| `37` | `DATA_TYPE_NAVI_TBT_STRING_INFO` | Formatted turn text and guidance labels | Textual street and maneuver descriptions |

### 4.4 Presentation State Enumeration (`state` in DataType 31)
- `1` (`MAP_STATE_NAVI_CLOSE`): Presentation closed; default instrument cluster.
- `5` (`MAP_STATE_SMALL_OPEN`): Dedicated right-hand/center viewport active (Mode 3).
- `6` (`MAP_STATE_FULL_OPEN`): Full panoramic cluster presentation active (Mode 4).
- `7` (`MAP_STATE_SIMPLE_OPEN`): Vector TBT mode active without video presentation (Mode 2).

### 4.5 Standardized Maneuver Icon IDs (`NaviIconTbt`)
```text
+-------------------+------------------------------------------+
| NaviIconTbt Value | Visual Maneuver Description              |
+-------------------+------------------------------------------+
| 1                 | Straight ahead / Continue on current road|
| 2                 | Turn Left (90 degrees)                   |
| 3                 | Turn Right (90 degrees)                  |
| 4                 | Slight Left (45 degrees)                 |
| 5                 | Slight Right (45 degrees)                |
| 6                 | Sharp Left                               |
| 7                 | Sharp Right                              |
| 8                 | U-Turn (Invert direction)                |
| 9                 | Keep Left / Keep Right                   |
| 11                | Highway Exit / Deceleration ramp         |
| 16                | Roundabout entrance / Rotary             |
+-------------------+------------------------------------------+
```

---

## 5. Implementation Guide: Accessing Display 1 and IPC from Kotlin

### 5.1 Zero-Dependency Binder Client
To interface with `ClusterCoreService` without requiring private OEM compile-time dependencies, developers can utilize standard Android `Parcel` serialization over `IBinder`:

```kotlin
class BydClusterIpcClient(private val context: Context) {

    private var clusterBinder: IBinder? = null
    private val DESCRIPTOR = "com.ts.lib.cluster.common.IClusterCommService"
    private val TRANSACTION_SEND_COMMAND = 1
    private val CMD_NAVI = 16

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            clusterBinder = service
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            clusterBinder = null
        }
    }

    fun bindService(): Boolean {
        val intent = Intent().apply {
            component = ComponentName(
                "com.ts.appservice.cluster",
                "com.ts.appservice.cluster.service.ClusterCoreService"
            )
        }
        return context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    fun sendCommand(bundle: Bundle): Boolean {
        val binder = clusterBinder ?: return false
        val data = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(CMD_NAVI)
            data.writeInt(1) // Bundle presence indicator
            bundle.writeToParcel(data, 0)
            binder.transact(TRANSACTION_SEND_COMMAND, data, null, IBinder.FLAG_ONEWAY)
            true
        } catch (e: Exception) {
            false
        } finally {
            data.recycle()
        }
    }

    fun setSimpleModeTbt(iconId: Int, distanceMeters: Int, streetName: String, etaSeconds: Int) {
        val bundle = Bundle().apply {
            putInt("DataType", 16)
            putInt("NaviSource", 1)
            putInt("NaviIconTbt", iconId)
            putInt("NaviNextRoadDistance", distanceMeters)
            putString("NaviNextRoadName", streetName)
            putInt("NaviToDestTime", etaSeconds)
            putInt("NaviState", 2)
        }
        sendCommand(bundle)
    }

    fun requestVideoFocus(mode: Int): Boolean {
        val bundle = Bundle().apply {
            putInt("msgType", 3)
            putInt("DataType", 33)
            putInt("mapType", 1)
        }
        return sendCommand(bundle)
    }
}
```

### 5.2 Presenting UI on Display 1 via Android `Presentation`
To render custom layouts (telemetry, gauges, maps) directly onto the instrument cluster:

```kotlin
class SecondaryClusterPresentation(
    context: Context,
    display: Display
) : Presentation(context, display) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.layout_cluster_secondary)
        
        // Window flags for persistent hardware presentation
        window?.addFlags(
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
    }
}

// Spawning the presentation from a Service or Activity:
fun launchClusterDisplay(context: Context) {
    val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    val displays = displayManager.getDisplays()
    
    // Display 1 represents the instrument cluster video feed
    val clusterDisplay = displays.firstOrNull { it.displayId == 1 }
    
    if (clusterDisplay != null) {
        val presentation = SecondaryClusterPresentation(context, clusterDisplay)
        presentation.show()
    }
}
```

---

## 6. Case Study: The `BYD Cluster` (BydClusterNavBridge) Architecture

The `BydClusterNavBridge` project serves as a real-world reference implementation demonstrating seamless integration between Android navigation applications and the cluster display.

```text
 +--------------------------------------------------------------------+
 |                       CONSUMER NAVIGATION APPS                     |
 |        (Google Maps, Waze, A Better Routeplanner - ABRP, HERE)     |
 +---------------------------------+----------------------------------+
                                   |
              [Android Notifications & Accessibility Events]
                                   |
                                   v
 +--------------------------------------------------------------------+
 |                       BydClusterNavBridge                          |
 |                                                                    |
 |  +--------------------------+      +----------------------------+  |
 |  | NavNotificationListener  |      | NavAccessibilityService    |  |
 |  | (Extracts distance, turn |      | (Inspects HUD turn banners |  |
 |  |  text, street name, ETA) |      |  from Flutter/hybrid apps) |  |
 |  +-------------+------------+      +-------------+--------------+  |
 |                |                                 |                 |
 |                +----------------+----------------+                 |
 |                                 v                                  |
 |                   +---------------------------+                    |
 |                   |   NavClusterService (FGS) |                    |
 |                   |   - Telemetry Normalizer  |                    |
 |                   |   - GPS Speed & Tracking  |                    |
 |                   |   - Mode State Machine    |                    |
 |                   +-------------+-------------+                    |
 +---------------------------------+----------------------------------+
                                   |
         +-------------------------+-------------------------+
         |                                                   |
         v [Vector Data]                                     v [Display 1 Surface]
+---------------------------------+                 +---------------------------------+
|      ClusterCommBridge (IPC)    |                 |   ClusterPresentation (EGL)     |
| - DataType 15 / 16 / 31 / 33    |                 | - MapLibre GL 3D Vector Engine  |
| - Controls QNX HUD Mode Switch  |                 | - High-contrast OLED Theme      |
+----------------+----------------+                 +----------------+----------------+
                 |                                                   |
                 +-------------------------+-------------------------+
                                           |
                                           v
                 +---------------------------------------------------+
                 |           BYD DIGITAL INSTRUMENT CLUSTER          |
                 +---------------------------------------------------+
```

### Key Technical Patterns in BydClusterNavBridge:
1. **Dynamic Multi-Mode Switching:** Supports instant transitions between Mode 2 (Simple Vector HUD) and Mode 3 / 4 (Hardware Map Presentation).
2. **Hybrid Ingestion Pipeline:** Combines an Android `NotificationListenerService` with a low-latency `AccessibilityService` to capture turn maneuvers from both standard native apps and hybrid/Flutter navigation platforms (such as ABRP and HERE WeGo).
3. **GPU & Memory Optimization:** Utilizes MapLibre GL configured with dark OLED base styles (`cartocdn dark-matter`) and optimized tile caching, operating within constrained Adreno GPU thermal limits.
4. **Resilient Window Geometries:** Respects QNX safety status bar heights and viewport offsets, ensuring instrument speedometers and high-voltage alerts remain completely visible and legible under all conditions.

---

## 7. Community Applications & Future Explorations

The ability to access `Display 1` and dispatch IPC events opens numerous possibilities for community automotive innovation:

1. **Custom CAN / OBD-II Telemetry Dashboards:**
   - Real-time battery cell voltage distribution, state of health (SoH), inverter temperature, and instantaneous power draw (kW).
   - Tire pressure monitoring (TPMS) and energy efficiency curve plotting.
2. **Media Player & Communications HUD:**
   - Album artwork, track titles, and audio source indications displayed directly in front of the driver.
   - Incoming communication alerts with discrete steering-wheel interaction.
3. **Track & Performance Instrumentation:**
   - Real-time G-force meters, 0-100 km/h acceleration timers, and lap timing synchronized with vehicular GPS.
4. **Auxiliary Camera Feeds:**
   - Displaying side blind-spot camera feeds or rear-view video streams directly on the instrument display during maneuvering.

---

## 8. Known Limitations, Open Challenges & Edge Cases

To foster collaborative open-source problem solving, this section details the operational boundaries, known failure modes, and platform behaviors that are not yet fully resolved.

### 8.1 Google Maps Primary Viewport Collapse
When a secondary `Presentation` is attached to `Display 1`, Android Automotive's window manager can intermittently detach the rendering surface on `Display 0` during audio ducking or orientation resize cycles. If Google Maps is running in navigation mode on `Display 0`, its internal layout engine (`NavInternal`) calculates a viewport size of `0x0`, throwing a fatal `ArithmeticException at uai.a(PG:383)` (division by zero).  
*Current Mitigation:* Running `BydClusterNavBridge` in **Mode 2 (Simple Vector Mode)** avoids secondary display attachment and prevents this crash entirely.

### 8.2 Adreno GPU Memory Budgeting & Watchdog Reboots
The Qualcomm SA8155P SoC shares Adreno GPU memory between Android's SurfaceFlinger and the QNX compositor. Instantiating unconstrained MapView instances or multiple high-resolution OpenGL textures on `Display 1` simultaneously with 3D map views on `Display 0` triggers kernel memory pressure warnings (`GFX_DBG no mem for req ... mem used 1300%`). Prolonged GPU starvation causes Android's `system_server` watchdog to trigger a full system reboot.  
*Current Mitigation:* Programmatic lazy-loading of `MapView`, setting `mapContainer.visibility = GONE` when not in Map mode, and disabling raster caching layers.

### 8.3 Absolute Lack of Touch Input on Display 1
The physical LCD cluster panel behind the steering wheel has **no capacitive or resistive touch digitizer**. Android `Display 1` is an output-only video pipe. Any interactive element, button, or slider rendered on `Display 1` cannot receive direct user touch events. All interactivity must be orchestrated through:
- Automated vehicle events (speed thresholds, gear shifts).
- Companion floating widgets on `Display 0`.
- Background automation services.

### 8.4 Steering Wheel Button Interception
Physical steering wheel buttons on DiLink vehicles do not dispatch standard Android `KeyEvent` actions to third-party applications unless an active `AccessibilityService` intercepts specific media/assist triggers. Direct CAN bus key mapping for arbitrary third-party apps remains non-standardized.

### 8.5 Model-Specific Geometry Variations Across Vehicle Lines
Different vehicle models within the BYD lineup utilize distinctly different physical cluster panels:
- **Dolphin / Atto 3:** Compact 5.0" to 8.8" floating instrument pods.
- **Seal / Sealion 7:** 10.25" widescreen integrated digital panels.
- **Han / Tang:** 12.3" full-size panoramic clusters.

Because the QNX compositor overlays native gauges at fixed pixel coordinates, the rectangular cutout ("meter hole") for Mode 3 (`MODE_PICCOLO`) varies between models:
- On Sealion 7, the visible window is located on the right side (`X: 1340..1880px, Y: 220..640px`).
- On other models, the aperture may be situated centrally between twin circular dials (`X: 760..1160px`).  
Currently, these window coordinates are tuned empirically per vehicle model; a universal auto-discovery mechanism for QNX viewport bounds remains an open challenge.

### 8.6 QNX TBT State Machine & Timing Sensitivity ("Naviga prima")
In Mode 2 (`MODE_SEMPLICE`), the QNX instrument cluster renders vector arrows natively. However, the QNX state machine requires exact handshake sequences. If a packet is dropped during initial ignition or mode switching, the cluster display will freeze on the placeholder string `"Naviga prima"` ("Navigate first").  
*Current Mitigation:* Implementing immediate re-broadcasts (`+220ms` and `+700ms`) of `DataType 32` and `DataType 37` packets upon route initialization. On certain firmware revisions where QNX drops external navigation sources, `BydClusterNavBridge` falls back to rendering its own lightweight HUD card on a pure OLED black canvas (`#000000`) on `Display 1`.

### 8.7 Newer DiLink Firmware Protobuf Migration
On newer DiLink firmware versions (e.g. DiLink 100), legacy JSON/Bundle payloads (`DataType 15/16`, `msgType = 4100`) are rejected by the cluster daemon with `"type is not right 4100"`. The system strictly expects Protobuf-encoded payloads (`DataType 32` / `DataType 37`) with specific floating-point formatting. While basic turn icons and distances are fully mapped, advanced fields—such as lane guidance vectors, dynamic highway exit previews, and speed limit indicators—remain unmapped.

### 8.8 Virtual vs Hardware Display Selection
On certain vehicle builds, Android registers multiple auxiliary display handles (`shared_fission_bg_XDJAScreenProjection_0`, `_1`). Instantiating a `Presentation` on a virtualized mirroring pipe instead of the physical EGL hardware pipe yields a black screen on the instrument cluster despite Android reporting a successful render. The application must explicitly filter for physical hardware display IDs.

### 8.9 Background Location Throttling on Android 11
When the bridge application operates in the background while another navigation app occupies `Display 0`, Android 11 aggressively throttles GPS location updates (`monitoring location: false` in `dumpsys location`). To ensure uninterrupted vehicular tracking and speed monitoring, the application requires:
- `android.permission.ACCESS_BACKGROUND_LOCATION`
- `android:foregroundServiceType="location|connectedDevice"`
- Explicit battery optimization exemptions.

---

## 9. Prerequisites & Environment Setup

To deploy and test auxiliary cluster applications on supported BYD vehicles:

1. **Enabling Developer Options & ADB:**
   - Access vehicle settings, enable USB/Wireless debugging via standard DiLink developer procedures.
2. **Required Android 11 Permissions:**
   - `WRITE_SECURE_SETTINGS`:
     ```bash
     adb shell pm grant <package.name> android.permission.WRITE_SECURE_SETTINGS
     ```
   - `ACCESS_BACKGROUND_LOCATION` & `ACCESS_FINE_LOCATION`: Required for persistent vehicle speed and tracking.
   - `SYSTEM_ALERT_WINDOW`: Required for overlay floating widgets and presentation triggers.
3. **Enabling Notification Listener Access:**
   ```bash
   adb shell settings put secure enabled_notification_listeners "<package.name>/<service.class>"
   ```

---

## 10. Disclaimers & Safety Notice

> **IMPORTANT AUTOMOTIVE SAFETY NOTICE**  
> This documentation, software, and associated specifications are provided strictly for educational, experimental, and research purposes.
>
> 1. **Driver Responsibility:** The driver retains full and ultimate responsibility for the safe operation of the motor vehicle at all times. Distraction while driving can lead to severe injury, death, or property damage. Never interact with secondary displays, configuration interfaces, or developer tools while the vehicle is in motion.
> 2. **Non-Affiliation:** This project is an independent community research initiative. It is neither affiliated with, nor authorized, sponsored, supported, or endorsed by **BYD Company Ltd.** or any of its subsidiaries or affiliates. All trademarks, service marks, trade names, and brand names referenced herein belong to their respective owners.
> 3. **As-Is Warranty:** All code, protocols, and instructions are provided on an "AS IS" and "AS AVAILABLE" basis, without warranties or conditions of any kind, either express or implied, including, without limitation, any warranties or conditions of TITLE, NON-INFRINGEMENT, MERCHANTABILITY, or FITNESS FOR A PARTICULAR PURPOSE. Users assume all risks associated with deploying third-party software on automotive hardware.

---

## 11. License

Copyright (C) 2026 Francesco D'Offizi

This project and documentation are free software: you can redistribute them and/or modify them under the terms of the **GNU General Public License as published by the Free Software Foundation**, either version 3 of the License, or (at your option) any later version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the [GNU General Public License](https://www.gnu.org/licenses/gpl-3.0.html) for more details.
