# BYD DiLink 100 - Turn-by-Turn Cluster Integration Guide (for Yash / OverDrive)

This guide documents the IPC architecture and protocol specification required to display Turn-by-Turn (TBT) navigation maneuvers directly on the digital instrument cluster of BYD vehicles equipped with DiLink 100 / Qualcomm SA8155P (e.g. Sealion 7, Seal, Tang, Han, Dolphin).

---

## 1. Architectural Overview & Lessons Learned

### Why "Mini-Map" and "Full-Map" (Display 1 / HDMI) fail or crash
- **Hardware Display 1**: In DiLink 100, the instrument cluster map feed relies on a dedicated secondary HDMI/EGL surface (`Display 1`, 1920x720) managed by the QNX 7.1 Hypervisor.
- **Why Google Maps Crashed**: When external navigation apps try to "cast" or mirror to a secondary presentation display, Android Automotive detaches the viewport on Display 0 (the central 15.6" screen) leaving its width and height at 0x0. When audio ducking or microphone events fire, `NavInternal` in Google Maps throws a fatal division by zero (`ArithmeticException at uai.a(PG:383)`).
- **GPU Adreno Memory Exhaustion**: Spawning second activities or `Presentation` views on Display 1 exhausts Adreno GPU memory pools (`GFX_DBG no mem for req ... mem used 1300%`), leading to `system_server` watchdog reboots.

### The Ideal Solution: "Simple Mode" (Modalità Semplice - Native TBT)
In **Simple Mode** (`moet_extension_navi_display_mode = 2`):
- Navigation apps (Google Maps, Waze, ABRP) run normally on Display 0 with full touch and voice control.
- Only vector navigation events (maneuver icon, meters to turn, road name, ETA) are transmitted via IPC Binder to the cluster service.
- The instrument cluster itself handles rendering the graphics natively within its QNX HUD pipeline with zero GPU burden on Android.

---

## 2. IPC Protocol Specification (`ClusterCoreService`)

The OEM service responsible for instrument cluster communication is:
- **Package**: `com.ts.appservice.cluster`
- **Class**: `com.ts.appservice.cluster.service.ClusterCoreService`
- **AIDL Interface**: `com.ts.lib.cluster.common.IClusterCommService`
- **Binder Transaction ID**: `1` (`sendCommand`)
- **Required Permission**: `android.car.permission.CAR_CLUSTER_COMMUNICATION` (Normal protection)

### AIDL Definition
```aidl
package com.ts.lib.cluster.common;

interface IClusterCommService {
    void sendCommand(int commandId, in Bundle bundle);
    boolean registerCallback(int commandId, in IClusterCommCallback callback);
    boolean unregisterCallback(int commandId, in IClusterCommCallback callback);
    Bundle requestCacheData(int commandId, int dataType);
}
```

---

## 3. Communication Sequences

All commands use `commandId = 16` (`CMD_NAVI`).

### A. Initializing / Activating Simple Mode
Before sending maneuver updates, set the cluster display mode and enable the TBT switch:

1. **System Settings**:
   ```kotlin
   Settings.Global.putInt(contentResolver, "moet_extension_navi_display_mode", 2) // 2 = Simple Mode
   Settings.Global.putInt(contentResolver, "moet_extension_navi_focus_map", 0)    // 0 = No video capture
   ```

2. **Send Start TBT Command (`DataType = 15`)**:
   ```kotlin
   val bundle = Bundle().apply {
       putInt("DataType", 15)      // DATA_TYPE_NAVI_SWITCH
       putInt("NaviSource", 1)    // 1: Local / AMAP compatible
       putInt("NaviTbtSwitch", 1) // 1: OPEN TBT
       putInt("NaviState", 2)     // 2: Navigation active
   }
   clusterBinder.sendCommand(16, bundle)
   ```

---

### B. Sending Maneuver Information (`DataType = 16`)
When a navigation update is parsed from notifications:

```kotlin
val bundle = Bundle().apply {
    putInt("DataType", 16)                     // DATA_TYPE_NAVI_INFO
    putInt("NaviSource", 1)
    putInt("NaviIconTbt", iconId)              // Maneuver Icon ID (see table below)
    putInt("NaviNextRoadDistance", meters)     // Distance to next turn in meters (Int)
    putString("NaviNextRoadName", roadName)    // Next street or instruction (String)
    putInt("NaviToDestTime", remainingSeconds) // ETA in seconds (Int)
    putInt("NaviDistanceToDest", destMeters)   // Total trip remaining meters (Int)
    putInt("NaviDistanceToDestUnit", 0)        // 0: KM
    putInt("NaviRoadType", 0)
    putInt("NaviCrossingStatus", 0)
    putInt("NaviNextRoadPrecent", 0)
    putInt("NaviState", 2)                     // 2: Active
}
clusterBinder.sendCommand(16, bundle)
```

#### Maneuver Icon ID Reference Table
| `NaviIconTbt` ID | Maneuver Description |
|---|---|
| `1` | Straight ahead / Keep driving |
| `2` | Turn Left |
| `3` | Turn Right |
| `4` | Slight Left |
| `5` | Slight Right |
| `6` | U-Turn |
| `7` | Fork / Branch |
| `8` | Highway Exit / Ramp |
| `11`| Roundabout |

---

### C. Stopping Navigation & Reverting to Standard Cluster
When the route ends or navigation stops:

```kotlin
val bundle = Bundle().apply {
    putInt("DataType", 15)
    putInt("NaviSource", 1)
    putInt("NaviTbtSwitch", 0) // 0: CLOSE TBT
    putInt("NaviState", 0)     // 0: Idle
}
clusterBinder.sendCommand(16, bundle)

Settings.Global.putInt(contentResolver, "moet_extension_navi_display_mode", 0) // 0 = Standard gauges
```

---

## 4. Kotlin Binder Implementation (Zero-Dependency)

If you don't want to import the OEM AIDL file into OverDrive, you can use direct Binder IPC with `Parcel`:

```kotlin
class BydClusterComm(private val context: Context) {

    private var binder: IBinder? = null
    private val DESCRIPTOR = "com.ts.lib.cluster.common.IClusterCommService"
    private val TRANSACTION_SEND_COMMAND = 1

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binder = service
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            binder = null
        }
    }

    fun bind() {
        val intent = Intent().apply {
            component = ComponentName("com.ts.appservice.cluster", "com.ts.appservice.cluster.service.ClusterCoreService")
        }
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    fun sendCommand(commandId: Int, bundle: Bundle): Boolean {
        val current = binder ?: return false
        val data = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(commandId)
            data.writeInt(1) // Bundle presence flag
            bundle.writeToParcel(data, 0)
            current.transact(TRANSACTION_SEND_COMMAND, data, null, IBinder.FLAG_ONEWAY)
            true
        } catch (e: Exception) {
            false
        } finally {
            data.recycle()
        }
    }
}
```

---

## 5. Map Presentation & HDMI Switching (Small & Full Map Modes)

If OverDrive wishes to render a 3D vector map directly onto the instrument cluster (Small Mode `1` or Full Mode `3`), QNX requires explicit video routing commands in addition to setting system properties:

### A. Requesting Map Focus (`DataType = 33`)
Tells QNX to route Android's secondary HDMI display feed (`Display 1`) to the cluster LCD:
```kotlin
fun requestMapFocus(mapType: Int = 1): Boolean {
    val bundle = Bundle().apply {
        putInt("msgType", 3)
        putInt("DataType", 33)
        putInt("mapType", mapType)
    }
    return sendCommand(16, bundle)
}
```

### B. Notifying Presentation Window State (`DataType = 31`)
Controls the aperture and layout of the video window on the QNX compositor:
```kotlin
fun sendNaviPresentationState(mapType: Int = 1, state: Int): Boolean {
    val bundle = Bundle().apply {
        putInt("msgType", 3)
        putInt("subtype", 15)
        putInt("DataType", 31)
        putInt("mapType", mapType)
        putInt("naviPresentationState", state)
    }
    return sendCommand(16, bundle)
}
```
**BYD Official State Values**:
- `state = 5` (`MAP_STATE_SMALL_OPEN`): Center aperture between speedometer dials (Mode 1 / Piccolo).
- `state = 6` (`MAP_STATE_FULL_OPEN`): Full-screen panoramic cluster view (Mode 3 / Grande).
- `state = 7` (`MAP_STATE_SIMPLE_OPEN`): Simple vector TBT mode.
- `state = 1` (`MAP_STATE_NAVI_CLOSE`): Navigation closed / standard OEM dials.

### C. Releasing Video Focus (`DataType = 34`)
```kotlin
fun abandonMapFocus(mapType: Int = 1): Boolean {
    val bundle = Bundle().apply {
        putInt("msgType", 3)
        putInt("DataType", 34)
        putInt("mapType", mapType)
    }
    return sendCommand(16, bundle)
}
```

---

## 6. Critical Android 11 Permissions & System Settings

To allow OverDrive to write cluster settings and receive navigation notifications without interruption:

1. **`WRITE_SECURE_SETTINGS`**:
   Must be granted via ADB once:
   ```bash
   adb shell pm grant com.overdrive.app android.permission.WRITE_SECURE_SETTINGS
   ```

2. **`enabled_notification_listeners`**:
   In Android 11, multiple notification listeners must be concatenated with a colon `:` in `Settings.Secure`:
   ```bash
   adb shell settings put secure enabled_notification_listeners "com.overdrive.app/com.overdrive.app.service.NotificationListener:com.byd.clusternavbridge/com.byd.clusternavbridge.service.NavNotificationListener"
   ```

---
*Created for the OverDrive project by MacAgy & Francesco Doffizi.*
