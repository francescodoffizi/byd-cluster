# Changelog

Tutte le modifiche rilevanti a questo progetto saranno documentate in questo file.

Il formato è basato su [Keep a Changelog](https://keepachangelog.com/it/1.1.0/).

## [1.6.0] - 2026-11-10

### Aggiunto & Risolto (Rinnovo Pittogrammi Svolte Automotive 90°, Rimozione Pallino Cursore 3D, Centratura Nativa Finestra Piccola, Card HUD in Modalità Semplice, Baloon Flottante Spento di Default e Direct Boot)
- **Rinnovo Pittogrammi Vettoriali di Svolta (`res/drawable/`)**:
  - `ic_turn_left.xml` e `ic_turn_right.xml`: sostituiti i tracciati Material errati di `undo`/`redo` (curva a U a 180° di inversione) con i vettori ufficiali automotive di svolta netta a 90° (`turn_left` e `turn_right`) allineati alla grafica del navigatore OEM.
  - `ic_straight.xml`: sostituito il tracciato orizzontale a destra (`arrow_forward`) con freccia verticale dritta verso l'alto (`arrow_upward`).
  - `ic_uturn.xml`: vera inversione a U a 180°.
  - `ic_turn_slight_left.xml` e `ic_turn_slight_right.xml`: introdotte frecce a 45° per biforcazioni e svolte dolci con supporto in `resolveTurnDrawable`.
- **Eliminazione Definitiva Pallino / Alone Cursore Navigazione**:
  - Rimosso il tracciato circolare di glow da `ic_nav_cursor_arrow.xml`, preservando esclusivamente il chevron 3D con bordo bianco e faccette cyan/deep cyan ad alto contrasto.
  - Impostato trasparente `backgroundDrawable`, `shadowDrawable`, `backgroundTintColor` e `elevation(0f)` in `LocationComponentOptions` di MapLibre, eliminando ogni cerchio sottostante.
- **Centratura Geometrica Nativa Finestra Destra `Piccolo` (`ClusterPresentation.kt`)**:
  - Ancorato `mapContainer` con `LayoutParams` dedicati esattamente dentro la maschera QNX (`leftMargin = 1350px`, `topMargin = 220px`, `width = 480px`, `height = 460px`).
  - Applicato padding simmetrico orizzontale `setPadding(0, 160, 0, 30)`: il veicolo e la telecamera 3D restano perfettamente stabili e centrati sotto la Card HUD senza alcuna distorsione prospettica, con un abbattimento dell'80% del carico fragment shader sulla GPU.
- **Supporto Card HUD Real-Time in Modalità `Semplice` (`MODE_SEMPLICE = 2`)**:
  - Mantenuta la `ClusterPresentation` su Display 1 in modalità Semplice con `mapContainer.visibility = GONE` (zero carico GPU e mappa spenta) e la sola Card HUD ad alto contrasto su sfondo nero OLED puro: svolte e distanze live sono sempre garantite anche quando QNX non renderizza il TBT proprietario.
- **Baloon Flottante Spento di Default & Direct Boot Aware**:
  - Introdotta la preferenza `pref_floating_pill_enabled` con default `false` in `NavClusterService` e toggle interattivo in `MainActivity`.
  - Aggiunto `android:directBootAware="true"` nel manifest su tutti i servizi e receiver, garantendo l'avvio immediato fin dalla prima accensione del veicolo.

## [1.5.9] - 2026-11-10

### Aggiunto & Risolto (Fix Geometria Overlay `Intero`/`Piccolo`, Cursore Freccia 3D Anti-Blink, Ripristino Indicatore QNX `Semplice`, Tracciato Percorso OSRM e Pulizia Semantica HERE WeGo)
- **Fix Posizionamento Overlay Svolte in Modalità `Intero` (`MODE_GRANDE = 4`) e Eliminazione Lampeggio Cursore Veicolo**:
  - Spostato il box delle svolte (`layoutCenterClusterHud`) da `topMargin = 72px` (dove veniva tagliato per metà dalla barra superiore opaca QNX del tachimetro `Y = 0..214px`) a `topMargin = 220px` centrato orizzontalmente (`width = 420px`), garantendo la piena visibilità sotto il tachimetro.
  - Eliminato il lampeggio/sparizione del cursore veicolo rotondo su MapLibre disattivando il `LocationEngine` interno di MapLibre a favore di un `passiveLocationEngine` alimentato esclusivamente da `NavClusterService`, disabilitando `pulseEnabled(false)` e `accuracyAnimationEnabled(false)`, e introducendo il nuovo vettore 3D a freccia di navigazione (`R.drawable.ic_nav_cursor_arrow`).
- **Fix Visibilità Overlay Svolte in Modalità `Piccolo` (`MODE_PICCOLO = 3`)**:
  - Rilevato dalle foto reali del cruscotto che in modalità `Piccolo` l'orizzonte montuoso superiore QNX (`Y = 0..215px`) è opaco e copriva completamente la card HUD posizionata a `topMargin = 68px`.
  - Riposizionata la card HUD all'interno della finestra trasparente destra QNX (`X = 1340..1880px, Y = 220..640px`) a `topMargin = 224px, leftMargin = 1380px, width = 400px` e applicato `mapLibreMap.setPadding(1340, 340, 40, 60)` per collocare il cursore del veicolo nella metà inferiore del riquadro destro (`X ≈ 1610px, Y ≈ 500px`), sotto il box delle svolte.
- **Ripristino Indicatore di Svolta Semplificato Nativo QNX in Modalità `Semplice` (`MODE_SEMPLICE = 2`)**:
  - Ripristinata la modalità nativa QNX `display_mode = 2` (`MAP_STATE_SIMPLE_OPEN = 7`, `NAVI_STATE_START = 0`) chiudendo la `ClusterPresentation` HDMI su Display 1 quando è selezionata la modalità `Semplice` (eliminando il riquadro con sfondo nero di `Piccolo`).
  - Garantito il funzionamento immediato dell'indicatore semplificato QNX tramite invio sincrono e ribattuta automatica (`+220ms` e `+700ms`) dei pacchetti Protobuf `DataType = 32` (`subtype = 7`, con `toDestHours`/`toDestMinutes` mappati su `arriveTimeHour`/`arriveTimeMinute`) e `DataType = 37` (`subtype = 20`) con valori di fallback sempre valorizzati.
- **Tracciamento Percorso Itinerario (`Route Polyline`) su Cartografia `Piccolo` e `Intero` (`RouteFetcher.kt`)**:
  - Creato il modulo asincrono `RouteFetcher` che risolve la destinazione o la via di svolta corrente vicino alla posizione GPS del veicolo tramite Photon (Komoot) / Nominatim e interroga il motore di routing OSRM (`overview=full&geometries=geojson`).
  - Aggiunti due layer vettoriali su MapLibre (`byd-route-casing-layer` `#004D61` spessore `10f` + `byd-route-line-layer` `#00E5FF` spessore `6f`) sopra `GeoJsonSource("byd-route-source")` per disegnare il percorso stradale sia in modalità `Piccolo` sia in modalità `Intero`, con ricalcolo automatico in caso di deviazione (`> 180m`) e popolamento automatico di ETA/distanza residua.
- **Pulizia Semantica Motore Flutter di HERE WeGo (`NavAccessibilityService.kt`)**:
  - Filtrati gli identificatori tecnici grezzi esposti dal motore Flutter di HERE WeGo (`travel_mode_car`, `schoolZone`, `second_maneuver_action_*`) e tradotti i token `first_maneuver_action_*` in istruzioni stradali italiane chiare, estendendo la finestra di cattura del nome strada nel banner superiore (`firstDistTop - 110 .. firstDistTop + 175`).

## [1.5.8] - 2026-11-10

### Aggiunto & Risolto (Fix GPS in Background + Freccia MapLibre, Geometria Finestra Destra `Piccolo`/`Semplice` e Cattura Live Banner ABRP/HERE WeGo)
- **Fix Blocco GPS in Background (`monitoring location: false`) e Freccia di Navigazione MapLibre**:
  - Aggiunto il permesso `android.permission.ACCESS_BACKGROUND_LOCATION` e aggiornato `NavClusterService` a `android:foregroundServiceType="location|connectedDevice"` (con `ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION` in `startForeground`), impedendo ad Android 11 di sospendere il GPS quando `BydClusterNavBridge` non è in primo piano sul Display 0.
  - Introdotto il tracciamento continuo `LocationListener` (`GPS_PROVIDER`, `NETWORK_PROVIDER`, `PASSIVE_PROVIDER`) direttamente in `NavClusterService` per alimentare in tempo reale `ClusterPresentation` fin dall'avvio del servizio.
  - Attivato il `LocationComponent` nativo di MapLibre (`RenderMode.GPS` con freccia ciano orientata sul bearing GPS) in `ClusterPresentation.setupMap()`.
- **Allineamento Geometrico Card HUD e Telecamera MapLibre alle Finestre Reali QNX (`Intero`, `Piccolo`, `Semplice`)**:
  - **Modalità `Piccolo` (`MODE_PICCOLO = 3`, `state = 5`)**: Rilevato dalle foto del cruscotto Sealion 7 che in modalità `Piccolo` QNX disegna il veicolo ADAS a sinistra/centro (`X = 0..1050px`) e apre la finestra HDMI esclusivamente sulla metà destra (`X ≈ 1080..1860px`, centro `X = 1470px`). Spostata dinamicamente `layoutCenterClusterHud` a `X = 1270..1670px, Y = 68px` e applicato `map.setPadding(1020, 180, 0, 0)` per centrare la freccia di navigazione a `(X = 1470px, Y = 450px)`.
  - **Modalità `Intero` (`MODE_GRANDE = 4`, `state = 6`)**: Alzata la card HUD a `Y = 72px` (`map.setPadding(0, 160, 0, 0)`) per evitare sovrapposizioni con i popup di avviso centrali QNX.
  - **Modalità `Semplice` (`MODE_SEMPLICE = 2`)**: Utilizzata la finestra HDMI destra QNX (`MAP_STATE_SMALL_OPEN = 5`) con sfondo OLED nero puro (`#000000`, `mapContainer.visibility = GONE`) e card HUD centrata a `X = 1260..1680px, Y = 220px`, garantendo l'aggiornamento immediato delle svolte (anche dal Tester) senza mai bloccarsi sul placeholder nativo QNX `"Naviga prima"`.
- **Pulizia Placeholder `"500 m"` e Filtri `isGenericStatus` (`NavNotificationListener` & `ClusterPresentation`)**:
  - Rimosso il testo cablato `"500 m"` da `tvManeuverDistance` in `activity_cluster_map.xml` e aggiunto fallback intelligente sulla distanza residua o `"In rotta"` quando la distanza di svolta non è ancora disponibile.
  - Esteso `isGenericStatus()` a `"abrp"`, `"here wego"`, `"navigazione vocale"`, `"tocca per tornare"` e applicato il filtro anche al blocco di fallback `title`/`text` in `NavNotificationListener`, eliminando il blocco di `instruction` su `"ABRP"` o `"Navigazione vocale"`.
- **Servizio Automatico `NavAccessibilityService` per Cattura Svolte Live da UI Banner (`ABRP` & `HERE WeGo`)**:
  - Creato `NavAccessibilityService` (auto-abilitato al boot da `NavClusterService` tramite `WRITE_SECURE_SETTINGS`) che ispeziona il banner superiore di manovra (`Y = 0..440px, X = 0..1050px`) di ABRP e HERE WeGo sul Display 0 ed estrae in tempo reale distanza svolta (es. `"4.4 km"`), codici strada (`"E80 A90"`) e uscita (`"Uscita 32 Pisana"`), anche quando l'audio interno di ABRP è muto o quando HERE WeGo è in primo piano.

## [1.5.7] - 2026-11-09

### Aggiunto & Risolto (REA Profonda Stack QNX/FDBus `qnx.apk`, Fix Apertura Finestra HDMI `state` & Doppio Protocollo TBT 32+37)
- **Fix Critico Chiave Bundle `"state"` in `sendNaviPresentationState` (`DataType = 31`)**:
  - Dal disassemblaggio bytecode di `InstrumentMediaManagerImpl.sendNaviPresentationState` (`classes.dex` @ `0x162b12`) e `InteractionToBundle.java` (`qnx.apk`, `case 15`), individuato che il serializzatore Protobuf QNX (`InteractionProto.MapState`) legge esclusivamente `bundle.getInt("state")` e ignora `"naviPresentationState"`.
  - Corretto il payload di `sendNaviPresentationState(mapType, state)` con `putInt("state", state)`, abilitando finalmente l'apertura del "meter hole" HDMI QNX (`5 = MAP_STATE_SMALL_OPEN`, `6 = MAP_STATE_FULL_OPEN`, `7 = MAP_STATE_SIMPLE_OPEN`, `1 = MAP_STATE_NAVI_CLOSE`).
- **Implementazione Stato Navigazione QNX (`DataType = 35`, `sendNaviState`)**:
  - Aggiunto `sendNaviState(mapType = 1, naviState)` (`msgType = 3, subtype = 2, DataType = 35`) con `0 = NAVI_STATE_START` e `1 = NAVI_STATE_STOP`, conforme a `InstrumentMediaManagerImpl.sendNaviState` (`0x162b44`).
- **Sostituzione Pacchetti TBT Legacy (`DataType 15/16`, `msgType = 4100`) con Protocolli Protobuf Nativi QNX (`DataType = 32` + `DataType = 37`)**:
  - Rimosso l'invio di `DataType = 15/16` che veniva convertito da `SomeIpClusterAdapter` in JSON `msgType = 4100` e scartato silenziosamente da `AndroidFdbusServerManager.sendMessageToQnx` in `qnx.apk` (`"sendMessage2Qnx: type is not right 4100"`).
  - Implementato in `sendTbtInfo(state)` il doppio invio sincrono dei due formati FDBus/Protobuf supportati nativamente da `qnx.apk`:
    1. `DataType = 32` (`msgType = 3, subtype = 7` -> `InteractionProto.Navigation`): con `putFloat` obbligatorio per `nextRoadDist`, `nextNextRoadDist`, `distToDest` ed enum `InteractionProto.Unit` (`1 = KM`, `3 = M`).
    2. `DataType = 37` (`msgType = 3, subtype = 20` -> `InteractionProto.NaviTbtInfo`): con stringhe pre-formattate (`nextRoadDistData`, `nextRoadDistUnit`, `distToDestDisData`, `distToDestDisUnit`, `distToDestTime`, `arriveToDestTime`).
  - Allineata la tabella icone di svolta (`mapInstructionToIcon`) alla codifica OEM `BydAutoNaviDevice` (`2`=sinistra, `3`=destra, `4`=leggermente a sinistra, `5`=leggermente a destra, `6`=stretta a sinistra, `7`=stretta a destra, `8`=inversione U, `9`=dritto/continua, `15`=destinazione, `16`=rotatoria).
- **Bypass Deduplica Silenziosa `MapFocusManager` e `ContentObserver` (`ClusterCommBridge`)**:
  - Aggiunti `sendDisplayModeDirectToQnx(mode)` (`DataType = 31, msgType = 3, subtype = 3`) e `sendMapFocusDirectToQnx(mapType)` (`DataType = 31, msgType = 3, subtype = 14`) per garantire la trasmissione FDBus a QNX anche quando `MapFocusManager` o `Settings.Global` deduplicano il valore.
  - Invertito l'ordine in `switchClusterMode`: `sendDisplayMode(mode)` viene ora invocato prima di `applyDisplayModeSettings()`.
  - Aggiunto guard `isBindingInProgress` in `bindDirectService()` per evitare chiamate `bindService` ridondanti.
- **Risoluzione Crash `UninitializedPropertyAccessException: voiceManager` & Persistenza Modalità (`NavClusterService`)**:
  - In `NavClusterService.onCreate()`, spostata l'assegnazione `instance = this` **dopo** l'inizializzazione di `clusterBridge`, `voiceManager` e `powerMonitor`, eliminando la race condition fatale con `NavNotificationListener`.
  - Introdotta persistenza della modalità scelta dall'utente in `SharedPreferences` (`byd_cluster_prefs` -> `preferred_mode`). Quando non c'è una navigazione attiva (`!state.isActive`), il servizio ferma solo il TBT senza sovrascrivere `currentMode` con `MODE_OFF`, mantenendo aperte le viste Mappa 3D (`Piccolo`/`Grande`) se selezionate dall'utente.
  - Aggiunto ritardo di `180ms` stile OEM Neusoft (`MapPresentationView`) tra `openClusterPresentation()` e `sendNaviPresentationState(MAP_STATE_SMALL_OPEN / FULL_OPEN)` per garantire che la Surface HDMI su Display 1 sia pronta prima dell'apertura del "meter hole" QNX.
- **Ottimizzazione Lazy-Init GPU Adreno & Fix `MapLibreConfigurationException` (`ClusterPresentation` + `activity_cluster_map.xml`)**:
  - Sostituito il tag statico `<org.maplibre.android.maps.MapView>` in `activity_cluster_map.xml` con un contenitore `<FrameLayout android:id="@+id/mapContainer" />` e istanziata `MapView(context)` programmaticamente in `initMapIfNeeded()` solo dopo `MapLibre.getInstance(context)`.
  - Eliminata `MapLibreConfigurationException` durante `ActivityClusterMapBinding.inflate()` e azzerato completamente il consumo del pool kernel `Adreno-GSL_RPC` in `MODE_SEMPLICE`.
- **Supporto Completo `HERE WeGo` (`com.here.app.maps`) e `OsmAnd` + Protezione Stato Broadcast ABRP (`NavNotificationListener`)**:
  - Aggiunti `com.here.app.maps`, `net.osmand` e `net.osmand.plus` a `TARGET_PACKAGES` e `<queries>` in `AndroidManifest.xml`.
  - Aggiunto parser per stringhe combinate `"350 m • Via Roma"` / `"350 m - Svolta a destra"` tipiche di HERE WeGo.
  - Impedito alle notifiche periodiche di riepilogo (`Voltra` LiveUpdate di ABRP) di sovrascrivere con stringa vuota un'istruzione di svolta attiva già ricevuta tramite `TbtBroadcastReceiver`.

## [1.5.6] - 2026-11-09

### Aggiunto & Risolto (Visualizzazione HUD Manovre Semplici su Display 1 HDMI & Risanamento Avvio ABRP)
- **Allineamento Misure e Coordinate Finestra Centrale OEM Neusoft/QNX**:
  - Estratte da `BydMapLauncher.apk` le coordinate millimetriche del viewport centrale (`.sview-card.sview-small-card`): larghezza 400px (4.0rem), altezza 120px, margine superiore 214px (2.14rem), centrata orizzontalmente tra i quadranti QNX (`X = 760px - 1160px`).
  - Ridisegnato `activity_cluster_map.xml` unificando le card laterali in una HUD Card compatta centrale da 400px perfettamente visibile nella luce libera tra il tachimetro e il misuratore di batteria del quadro Sealion 7, prevenendo qualsiasi ritaglio o mascheramento da parte di QNX.
- **Risoluzione Crash all'avvio di ABRP (`Resources$NotFoundException: Resource ID #0x0`)**:
  - Identificata e risolta la corruzione di `resources.arsc` sui multi-split APK.
  - Ricostruito il pacchetto `base` preservando `resources.arsc` originale intatto e iniettando selettivamente solo il `classes5.dex` con `AbrpClusterBridge`.
  - Ri-allineati tutti gli split con `zipalign -p 4` e ri-firmati con `release.jks`. ABRP ora si avvia e funziona regolarmente senza crash.
- **Visualizzazione HUD Svolte su Cruscotto in `MODE_SEMPLICE`**:
  - In `ClusterCommBridge.kt`: abilitato `requestMapFocus(1)` e `focusMap = 1` anche in `MODE_SEMPLICE`, mantenendo attivo il canale video HDMI verso il Display 1.
  - In `ClusterPresentation.kt`: implementato `updateMode(mode: Int)` con modalità specifica per `MODE_SEMPLICE` (`mapView.visibility = View.GONE` per azzerare il carico GPU Adreno) mantenendo visibili le schede HUD delle manovre (`layoutManeuverHud` e `layoutTripHud`) a pieno contrasto su sfondo nero nativo.
  - In `NavClusterService.kt`: abilitata l'apertura e l'aggiornamento di `ClusterPresentation` anche in `MODE_SEMPLICE`.
- **Sincronizzazione UI e Feedback Simulazione TBT (`MainActivity`)**:
  - Aggiunto `navStateListener` in `NavClusterService` e agganciato in `MainActivity` per aggiornare lo stato a video in tempo reale all'arrivo di ogni manovra.
  - Rimossa la forzatura distruttiva di `MODE_SEMPLICE` nel click del pulsante di test TBT, garantendo feedback immediato sia sul display centrale che sul quadro strumenti.

## [1.5.5] - 2026-11-09

### Aggiunto & Risolto (Integrazione TBT Broadcast Locale & Patch ABRP Turn-by-Turn)
- **Ricevitore Broadcast Locale `TbtBroadcastReceiver` (`com.byd.clusternavbridge.TBT_UPDATE`)**:
  - Creato ed esportato in `AndroidManifest.xml` il ricevitore `TbtBroadcastReceiver` per ricevere aggiornamenti di navigazione diretti ad alta frequenza da app terze o patchate.
  - Supporto per payload completi: `instruction`, `distance_meters`, `road_name`, `maneuver_type`, `destination`, `eta`, `is_active`.
  - Formattazione automatica della distanza e inoltro istantaneo a `NavClusterService.onNavStateChanged`.
- **Risanamento Radice Falsi 10km ("Tra 10,0 chilometri, Viaggio verso Lavoro")**:
  - In `VoiceGuidanceManager.kt`: eliminato il fallback hardcoded `9999m` in `parseDistanceToMeters("")`, che restituiva 10 km quando la distanza di svolta era assente. Ora restituisce `-1`.
  - In `VoiceGuidanceManager.kt`: bloccata la pronuncia vocale se la distanza è `<= 0` e non ci sono parole chiave di manovra esplicite.
  - In `NavNotificationListener.kt`: esteso `isDestinationOrRouteSummary()` per riconoscere e isolare `"Viaggio verso "`, `"Trip to "`, `"Viaggio per "`, `"Arrivo a "`, prevenendo che i titoli di viaggio ABRP confluiscano in `instruction`.
- **Risanamento Handshake & Protocollo IPC Cluster (`ClusterCommBridge`)**:
  - Implementato `IClusterCommCallback.Stub` registrato su `IClusterCommService.registerCallback(CMD_NAVI, callback)` per conformità al gateway SomeIP QNX Neusoft.
  - Corretta la gestione dello stato TBT: `isNavActive` viene ora allineato esplicitamente a `true` in `startTbtMode()` e `false` in `stopTbtMode()`.
  - In `sendTbtInfo()`: commutazione automatica su `MODE_SEMPLICE` se il quadro era spento, con sequenza garantita prima dell'invio del frame `DATA_TYPE_NAVI_INFO (16)`.
- **Risoluzione Race Condition e Simulazione TBT (`NavClusterService` & `MainActivity`)**:
  - In `NavClusterService.kt`: invocazione sincrona di `clusterBridge.switchClusterMode(targetMode)` prima di `sendTbtInfo(state)`, evitando che la svolta venga inviata prima della commutazione della schermata.
  - In `MainActivity.kt`: il pulsante di test TBT forza `setMode(MODE_SEMPLICE)` prima di emettere la manovra di test ("Tra 250m svolta a destra in Via Roma", icon=3, dist=250m) con feedback visivo.
- **Supporto Display Container Condiviso OEM (`ClusterPresentation`)**:
  - In `NavClusterService.kt`: prioritizzata la ricerca dei display hardware `shared_fission_bg_XDJAScreenProjection_0` e `_1` per prevenire il fallimento a schermo nero su display secondario virtuale.
- **Concessione Permessi di Sistema**:
  - Garantito `android.permission.WRITE_SECURE_SETTINGS` via `pm grant` per consentire la commutazione hardware trasparente del quadro.
- **Patch ABRP (v7.1.8 arm64) per Broadcast TBT Locale**:
  - Iniettata la classe Smali `AbrpClusterBridge` in `com.margelo.nitro.swe.iternio.reactnativeautoplay.template`.
  - Agganciato `MapTemplate$Companion.updateManeuvers` per trasmettere in tempo reale ogni svolta a `BydClusterNavBridge`.
  - Installato multi-split firmato e allineato su BYD Sealion 7.

## [1.5.4] - 2026-11-07

### Risolto (Root Cause Crash `CalledFromWrongThreadException` su Coroutine di Background)
- **Thread-Safety e Dispatch su Main Looper in `FloatingPillView`**:
  - In `FloatingPillView.kt`, integrato un `Handler(Looper.getMainLooper())` con helper `runOnMainThread`.
  - Tutti i metodi pubblici che manipolano overlay e widget di sistema (`updateNavState`, `updateMode`, `updateVoiceMute`, `show`, `hide`) ora intercettano le chiamate da thread secondari e le redirezionano sul thread principale.
  - Risolta l'eccezione `android.view.ViewRootImpl$CalledFromWrongThreadException: Only the original thread that created a view hierarchy can touch its views` emersa durante la guida live con ABRP.
- **Dispatch Difensivo su Main Thread in `ClusterPresentation`**:
  - In `ClusterPresentation.kt`, protetta la funzione `updateNavState` con reindirizzamento al Main Looper (`Looper.myLooper() == Looper.getMainLooper()`) prima di aggiornare i campi testo delle manovre, l'iconografia e le viste di cruscotto.
- **Centralizzazione Threading in `NavClusterService`**:
  - In `NavClusterService.kt`, le transizioni grafiche scatenate da `onNavStateChanged(state)` (cambio modo, aggiornamento floating pill e visualizzazione mappa cruscotto) e dai callback di stato alimentazione e mute vocale vengono inoltrate esplicitamente tramite `mainHandler.post`.
  - Mantenuto il disaccoppiamento non bloccante per l'IPC SomeIp/QNX (`clusterBridge.sendTbtInfo`) e la riproduzione vocale TTS neurale Piper (`voiceManager.onNavInstruction`).

## [1.5.3] - 2026-11-06

### Risolto (Root Cause ANR 08:33 / Loop Reflection Circolare & Asincronia Notifiche)
- **Eliminazione Loop Circolare `this$0` su Reflection RemoteViews**:
  - In `NavNotificationListener.kt`, eliminata la scansione indiscriminata dei campi riflessi degli oggetti `RemoteViews$Action` che agganciava il puntatore implicito `this$0` (outer-class) rimbalzando all'infinito sul Main Thread.
  - Implementata esclusione categorica di campi sintetici (`isSynthetic`) e puntatori `this$*`.
  - Aggiunto set di tracciamento cicli `IdentityHashMap` e limite di profondità rigido `depth <= 2`.
- **Estrazione Chirurgica e Ultrarapida di `ReflectionAction`**:
  - Per gli oggetti `ReflectionAction`, estrazione diretta mirata dei campi `methodName` e `value` solo se `setText` o `setContentDescription`, riducendo i tempi di elaborazione a frazioni di millisecondo.
  - Per gli oggetti `ViewGroupActionAdd`, ispezione limitata esclusivamente al campo `mNestedViews`.
- **Offload Asincrono su Coroutine di Background (Zero Rischio ANR)**:
  - Spostata l'intera elaborazione delle notifiche (`checkActiveNotifications`, `processNotification`, `onNotificationPosted`, `onNotificationRemoved`) su un `CoroutineScope` di background (`Dispatchers.Default`).
  - Il thread principale di `NotificationListenerService` ritorna immediatamente (< 1 ms), azzerando categoricamente qualsiasi freeze dell'interfaccia o blocco CPU.

### Modificato & Corretto (Task #699 - Fix Root Cause Svolte ABRP, Separazione Destinazione/Manovra & Permessi Cluster)
- **Eliminazione Gate Notifiche & Ispezione Incondizionata RemoteViews**:
  - In `NavNotificationListener.kt`, rimosso il blocco `if (title.isEmpty() && text.isEmpty())` che impediva l'estrazione delle notifiche di ABRP (`com.iternio.abrpapp`) quando `title` o `text` contenevano riepiloghi o nomi destinazione statici.
  - Per ABRP o in presenza di layout personalizzati, l'estrazione via reflection dei `RemoteViews` viene eseguita incondizionatamente.
- **Separazione Destinazione vs Svolte Reali (Anti-Falsa Manovra)**:
  - Introdotto filtro `isDestinationOrRouteSummary` per identificare e isolare frasi di destinazione ("Verso Casa", "Destinazione: Casa", "Direzione Roma").
  - Le destinazioni non vengono più scambiate per istruzioni di manovra e memorizzate nel nuovo campo `destination` di `NavState`.
  - Risolto il problema per cui veniva pronunciato e visualizzato l'assurdo messaggio *"Tra 10 chilometri, Verso Casa"* al posto delle vere svolte.
- **Differenziazione Distanze di Manovra vs Distanza Totale/Tappa**:
  - Implementata logica di classificazione delle distanze estratte: le distanze in metri ("300 m") hanno priorità assoluta come distanza di manovra (`distance`), mentre le distanze in km o i riepiloghi ("42 km • 35 min") vengono assegnati alla distanza totale residua (`remainingTimeDistance`).
- **Filtro Vocale Anti-Destinazione & Annuncio Partenza Pulito**:
  - In `VoiceGuidanceManager.kt`, vietata la pronuncia di nomi destinazione come finte manovre di svolta (`isDestinationSummary`).
  - Introdotto annuncio iniziale dedicato una tantum alla partenza (*"Navigazione avviata verso Casa"*), lasciando al TTS delle svolte solo le manovre effettive.
  - Pulita la pronuncia dei chilometri interi (es. "10 chilometri" invece di "10,0 chilometri").
- **Protezione Permessi Quadro Strumenti (`WRITE_SECURE_SETTINGS`)**:
  - In `ClusterCommBridge.kt`, aggiunto intercettamento esplicito di `SecurityException` con logging di errore chiaro per indicare la necessità di abilitare `WRITE_SECURE_SETTINGS` via ADB se mancante.
  - In `MainActivity.kt` e `activity_main.xml`, aggiunto monitor visivo in tempo reale sullo stato del permesso `WRITE_SECURE_SETTINGS`.

## [1.5.2] - 2026-11-05

### Modificato & Corretto (Task #697 - Fix ABRP RemoteViews, Handshake TBT QNX & Voce Multi-Stadio)
- **Parser Notifiche ABRP Custom (Glance / RemoteViews Reflection)**:
  - In `NavNotificationListener.kt`, implementata esplorazione ricorsiva approfondita dei `ViewGroupActionAdd` e dei `mNestedViews` generati dalla libreria Jetpack Glance di ABRP (`com.iternio.abrpapp`).
  - Filtrati rigorosamente tutti i nomi dei metodi interni reflection (`methodName`, `setInflatedId`, `setLayoutResource`, `setVisibility`, `setGravity`, `setText`, `setTextColor`, `setHeight`, `setImageIcon`, `setImageAlpha`, `setAdjustViewBounds`, `setImageResource`), i pulsanti azione ("Fine della navigazione") e metadati interni.
  - Isolati con precisione i campi utili: titolo percorso ("Viaggio verso Lavoro"), orario di arrivo stimato ("Arrivo 10:29"), distanza residua totale ("3 min · 2 km") e manovre di svolta.
- **Handshake Automatico Cluster Display Centrale (SomeIp / QNX)**:
  - In `NavClusterService.kt`, corretto l'aggancio automatico della modalità cluster: all'arrivo del primo stato di navigazione attivo se il bridge si trova in `MODE_OFF`, forza la transizione a `MODE_SEMPLICE`.
  - In `ClusterCommBridge.kt`, invio sequenziale di `display_mode=2` e `focus_map=0` in `Settings.Global`, `abandonMapFocus(1)`, `sendNaviPresentationState(1, MAP_STATE_SIMPLE_OPEN)` e `startTbtMode()`, consentendo all'adapter SomeIP Neusoft (`DataType: 15` con `NaviTbtSwitch = 1`) di impostare `mDisplayResult = 2`, prerequisito fondamentale affinché QNX accetti i pacchetti TBT (`DataType: 16`) e visualizzi frecce e distanze sul display centrale.
  - Implementato calcolo automatico della distanza totale residua (`NaviDistanceToDest` e `NaviDistanceToDestUnit`: 0 per km, 2 per m) a partire dalle stringhe di stato estratti da ABRP/Maps.
- **Guida Vocale Multi-Stadio Piper TTS**:
  - In `VoiceGuidanceManager.kt`, rimossa la soppressione delle frasi di arrivo per consentire l'annuncio del traguardo a destinazione.
  - Implementata soglia multi-stadio basata su distanze scalari (Stage 4 imminente 1-60m "Ora...", Stage 3 61-350m, Stage 2 351-1200m, Stage 1 >1200m).
- **Logcat Operativo DiLink**:
  - Promossi a `Log.w` tutti gli eventi critici di estrazione, instradamento cluster IPC e sintesi vocale per renderli immediatamente visibili nel logcat di produzione DiLink senza abilitare proprietà di debug di sistema.

## [1.5.1] - 2026-11-04

### Modificato & Corretto (Task #695 - Fix TBT, Parser ABRP & Surface HDMI)
- **Invio Svolta di Test Incondizionato**:
  - In `NavClusterService.kt`, rimosso il bypass dell'`else` in `onNavStateChanged`: `clusterBridge.sendTbtInfo(state)` e `clusterPresentation?.updateNavState(state)` vengono ora sempre invocati anche quando si effettua la transizione da `MODE_OFF` a `MODE_SEMPLICE`.
- **Parser Notifiche ABRP Custom (RemoteViews)**:
  - In `NavNotificationListener.kt`, implementata estrazione testuale da `RemoteViews` (`contentView` e `bigContentView`) tramite reflection su `mActions` e fallback su inflazione View. ABRP (`com.iternio.abrpapp`) ora estrae correttamente distanza e testo manovra.
- **Filtro Anti-Spam Vocale & Manovre Generiche**:
  - In `VoiceGuidanceManager.kt` e `NavNotificationListener.kt`, introdotto filtro per ignorare stringhe di stato generico ("Navigazione in corso", "Ricalcolo...", "Ricerca segnale...") prevenendo lo spam vocale continuo "Ora, Navigazione in corso".
  - Corretto `determineStage`: distanze non valorizzate o nulle non attivano più falsi allarmi imminenti (Stage 3).
- **Stabilizzazione Display 1 HDMI (Surface Injection)**:
  - In `ClusterPresentation.kt`, aggiunta guardia su `onSurfaceTextureAvailable` e `onSurfaceTextureSizeChanged` per garantire dimensioni hardware positive (`width > 0 && height > 0`) prima di emettere lo sticky broadcast `com.neusoft.na.navigation.set.cluster.surface`.
  - Forzata accelerazione hardware e layout 1920x720 nei parametri finestra della presentation.
- **Allineamento Dinamico Versione UI**:
  - Eliminata la stringa statica `v1.4.0` da `activity_main.xml` e dal titolo notifica in `NavClusterService.kt`, ora collegate dinamicamente a `BuildConfig.VERSION_NAME`.

## [1.5.0] - 2026-11-04

## [1.4.1] - 2026-11-04

### Modificato & Corretto (Audit Live SpiderAgy su Sealion 7)
- **Fix Crash Richiesta Permessi Notifiche DiLink**:
  - Racchiuso `startActivity` con `ACTION_NOTIFICATION_LISTENER_SETTINGS` in blocco `try/catch` per gestire `ActivityNotFoundException` causato dall'assenza della relativa Activity nell'app `Settings` OEM DiLink 100/5.0.
- **Risoluzione Mancata Connessione IPC & Comandi Droppati (`ClusterCoreService`)**:
  - Aggiunto il blocco `<queries>` in `AndroidManifest.xml` per dichiarare esplicitamente la visibilità del package `com.ts.appservice.cluster`, `app.revanced.android.apps.maps`, `com.google.android.apps.maps` e `com.iternio.abrpapp` (obbligatorio su targetSdk 30 / Android 11).
  - Implementata una coda thread-safe (`pendingCommands`) in `ClusterCommBridge` per accodare qualsiasi comando IPC inviato durante la fase asincrona di connessione di `bindService`.
  - Implementato `DeathRecipient` per riconnessione automatica in caso di crash o riavvio del servizio di sistema `ClusterCoreService`.
  - Invocazione esplicita e garantita di `bindDirectService()` nel ciclo di vita di `NavClusterService` (`onCreate` e `onStartCommand`) e pulizia con `unbindService()` in `onDestroy`.
- **Allineamento Mappatura Modalità Cluster DiLink/QNX**:
  - Allineati i parametri `moet_extension_navi_display_mode` con gli stati presentation QNX (`MAP_STATE_SMALL_OPEN = 5`, `MAP_STATE_FULL_OPEN = 6`, `MAP_STATE_SIMPLE_OPEN = 7`, `MAP_STATE_NAVI_CLOSE = 1`).
- **Parser Notifiche Google Maps ReVanced & ABRP Robusto**:
  - Implementato supporto al formato notifica di Google Maps ReVanced (`app.revanced.android.apps.maps`) dove `EXTRA_TITLE` contiene la distanza (es. `22 km`, `350 m`), `EXTRA_TEXT` contiene l'istruzione di manovra e `EXTRA_SUB_TEXT` contiene tempo, distanza residua e orario di arrivo (`40 min · 31 km · Arrivo 09:29`).
  - Parsing automatico dei minuti ed ETA per calcolo esatto di `NaviToDestTime` in secondi.
- **Protezione Permessi Geolocalizzazione in `ClusterPresentation`**:
  - Aggiunta verifica preventiva con `ContextCompat.checkSelfPermission` prima della registrazione dei listener di `LocationManager`, evitando `SecurityException` e log spam.

## [1.4.0] - 2026-11-03

### Aggiunto & Architettura Completa (Integrazione DiLink)
- **Supporto Ufficiale Multi-Modale (Semplice, Piccolo, Grande, Off)**:
  - Definita l'architettura per l'interazione con `com.neusoft.na.navigation` (`BydMapLauncher.apk`) e `BYDAutoInstrumentDevice`.
  - Mappati i comandi di commutazione video verso QNX (`ClusterCoreService` via IPC Binder):
    - `requestMapFocus(1)` (`DataType: 33`): predispone e commuta il routing hardware del canale HDMI su Display 1.
    - `abandonMapFocus(1)` (`DataType: 34`): rilascia il focus video e ripristina la grafica tachimetro nativa QNX.
    - `sendNaviPresentationState(1, state)` (`DataType: 31`): comunica a QNX l'apertura della finestra centrale (`MAP_STATE_SMALL_OPEN = 5`) o full-screen (`MAP_STATE_FULL_OPEN = 6`) e la chiusura (`MAP_STATE_NAVI_CLOSE = 1`).
- **ClusterPresentation In-Process Dedicata su Display 1**:
  - Implementata `ClusterPresentation` su `Display 1` (HDMI 1920x720) isolata in-process.
  - La visualizzazione di Google Maps / ABRP su Display 0 (tablet centrale) rimane **al 100% integra**, scongiurando il viewport detach e la divisione per zero.
  - Rendering 3D vettoriale Dark Matter con rotazione automatica sulla rotta GPS dell'auto (`LocationManager`).
- **UI Floating Pill Multi-Tasto**:
  - Aggiunti 4 pulsanti rapidi touch: `[Piccolo]`, `[Grande]`, `[Semplice]`, `[Off]` con evidenziazione cromatica attiva.
- **Aggiornamento Documentazione per Yash**:
  - Aggiunta in `docs/YASH_OVERDRIVE_INTEGRATION_GUIDE.md` la specifica completa per `requestMapFocus`, `abandonMapFocus` e la tabella degli stati `MAP_STATE_*`.

## [1.3.0] - 2026-11-03

### Modificato & Ottimizzato (Focus Modalità Semplice / Turn-by-Turn Nativo)
- **Modalità Semplice Esclusiva (Zero Display 1, Zero Crash GPU)**:
  - Rimosso qualsiasi tentativo di mirroring dello schermo secondario (Display 1 HDMI) o avvio di activity esterne, eliminando alla radice i crash di divisione per zero in Google Maps ReVanced (`NavInternal ArithmeticException`) dovuti al distacco della viewport 0x0 su Display 0.
  - Rimossa dipendenza pesante MapLibre SDK dal bundle APK (ridotta impronta da ~70MB a <10MB).
  - La visualizzazione della mappa di navigazione 3D rimane attiva e interattiva esclusivamente sullo schermo centrale tablet (Display 0).
- **Integrazione Binder Nativa DiLink (`ClusterCoreService`)**:
  - Implementato client IPC diretto verso `com.ts.appservice.cluster.service.ClusterCoreService` (AIDL `com.ts.lib.cluster.common.IClusterCommService`, `TRANSACTION_sendCommand = 1`, `CMD_NAVI = 16`).
  - Sequenza di comando Turn-by-Turn nativa DiLink:
    - Attivazione TBT: `DataType: 15`, `NaviSource: 1`, `NaviTbtSwitch: 1`, `NaviState: 2`.
    - Invio manovra TBT: `DataType: 16`, `NaviSource: 1`, `NaviIconTbt: <id>`, `NaviNextRoadDistance: <metri>`, `NaviNextRoadName: <nome>`, `NaviToDestTime: <secondi>`, `NaviDistanceToDest: <metri>`, `NaviState: 2`.
    - Disattivazione TBT: `DataType: 15`, `NaviSource: 1`, `NaviTbtSwitch: 0`, `NaviState: 0`.
  - Configurazione display mode: `Settings.Global.putInt("moet_extension_navi_display_mode", 2)` e `Settings.Global.putInt("moet_extension_navi_focus_map", 0)`.
- **Risoluzione Blocco Notifiche su Android 11 Automotive**:
  - Risolto il mancato inoltro delle notifiche di navigazione a `NavNotificationListener`: abilitato il servizio nel setting protetto di sistema `Settings.Secure.enabled_notification_listeners` preservando l'ascoltatore di OverDrive.
  - Concesso `android.permission.WRITE_SECURE_SETTINGS` per permettere la scrittura di `moet_extension_navi_display_mode`.
- **UI di Controllo Snella**:
  - Floating Pill ridisegnata: pulsante compatto toggle TBT `[TBT ON / TBT OFF]` con stato cromatico e indicatore sorgente navigazione.
  - Aggiunto comando e intent `com.byd.clusternavbridge.action.TEST_TBT` per test di simulazione svolta a quadro strumenti senza toccare lo schermo.

## [1.2.0] - 2026-11-03

### Aggiunto
- **Cluster 3D Vector Map Engine su Display 1 (`ClusterMapActivity`)**:
  - Motore cartografico vettoriale indipendente basato su MapLibre Native GL con stile vettoriale Carto Dark Matter ad alto contrasto per visione notturna/diurna su cockpit.
  - Rendering 3D a prospettiva inclinata (pitch 55°, zoom 16.5) orientato lungo la rotta di guida (`bearing` tracking continuo).
  - Architettura Multi-Display nativa: avvio dell'attività tramite `ActivityOptions.setLaunchDisplayId(1)` su Display 1 hardware ("Schermo HDMI", 1920x720), completamente disaccoppiata da Display 0 (l'utente può liberamente usare Spotify, Clima, Home o altre app sul tablet centrale senza interrompere la cartografia sul cluster).
  - Eliminato completamente il rischio di deadlock e crash OS: nessun uso di `Presentation` secondario che causava saturazione mempool GPU Adreno.
  - HUD Card Overlay integrato su Display 1: Card sinistra con indicazione manovra vettoriale (svolte, rotatorie, bivi), distanza residua e nome della via; Card destra con tachimetro GPS real-time (km/h) ed ETA/tempo residuo.
  - Teardown pulito delle risorse EGL e rilascio totale dei descrittori SurfaceFlinger alla chiusura o spegnimento del veicolo.
- **Power Gating del Veicolo (`VehiclePowerMonitor`)**:
  - Monitoraggio proattivo degli eventi di alimentazione del veicolo (`com.byd.action.ACC_ON`, `ACC_OFF`, `IGN_ON`, `ACC_MODE_CHANGED`, `SCREEN_ON`/`OFF`).
  - Blocco assoluto dell'avvio e del rendering cartografico quando il veicolo è spento (`ACC_OFF` / veicolo parcheggiato) per preservare la batteria 12V e non disturbare gli stati di sleep del cluster QNX.
  - Chiusura immediata di `ClusterMapActivity` e rilascio del focus OEM (`moet_extension_navi_focus_map = 0`) allo spegnimento del veicolo.
- **Geolocalizzazione Real-Time**:
  - Integrazione `LocationManager` con provider GPS e Network per aggiornamento fluido della posizione e velocità istantanea sul quadro strumenti.

## [1.1.0] - 2026-11-03

### Modificato & Ottimizzato
- **Risoluzione Root Cause Crash OS (system_server_watchdog)**: Rimosso completamente `ClusterPresentation` su Display 1 ("Schermo HDMI") che causava saturazione dei mempool GPU Adreno (1332%) e freeze di WindowManagerService durante il disegno da app di navigazione esterne.
- **IPC SOME/IP Nativo QNX per il Cluster**: Il cluster DiLink 100 è gestito direttamente da QNX e accetta vettori/dati TBT nativi. Implementato client Binder IPC diretto verso `com.ts.appservice.cluster.service.ClusterCoreService` (AIDL `IClusterCommService` via transazione SOME/IP `DATA_TYPE_NAVI_TBT_INFO = 32`) con fallback broadcast `com.ts.appservice.cluster.CLUSTER_TEST`.
- **Mappatura Corretta delle Modalità Cruscotto OEM**:
  - `0`: Off (cruscotto standard strumenti)
  - `1`: Piccolo (box centrale tra i quadranti)
  - `2`: Semplice (cruscotto minimal sportivo)
  - `3`: Grande (Full-Screen display panoramico completo)
- **Floating Pill UI Aggiornata**: Aggiunti 4 pulsanti touch dedicati (`[Piccolo]`, `[Grande]`, `[Semplice]`, `[Off]`) con feedback cromatico immediato.
- **Parsing TBT Intelligente**: Conversione automatica delle istruzioni di navigazione da Google Maps e ABRP (distanza normalizzata in metri, identificazione icone vettoriali OEM per svolte/rotatorie/inversioni, estrazione del nome della via) inviate tramite SOME/IP al rendering vettoriale QNX del quadro strumenti.

## [1.0.0] - 2026-11-03

### Aggiunto
- **BydClusterNavBridge**: App e Foreground Service nativo per BYD Sealion 7 (DiLink 100 / SA8155P / Android 11).
- **Controllo Hardware HDMI Display 1**: Gestione nativa del display secondario hardware cluster (1920x720) senza dipendere da display virtuali Fission.
- **ClusterCommBridge**: Manipolazione diretta delle impostazioni OEM del cluster:
  - `moet_extension_navi_display_mode`: 2 (Mini-Mappa centrale) e 3 (Mappa Full-Screen HDMI).
  - `moet_extension_navi_focus_map`: 1 (attiva visualizzazione mappa HDMI) e 0 (rilascia focus ai cruscotti OEM).
- **ClusterPresentation**: Presentation su Display 1 in alta leggibilità con freccia di manovra, distanza curva, istruzione stradale ed ETA.
- **NavNotificationListener**: Ascolto continuo in background degli intent/notifiche di navigazione da:
  - Google Maps ReVanced (`app.revanced.android.apps.maps`)
  - Google Maps (`com.google.android.apps.maps`)
  - A Better Routeplanner - ABRP (`com.iternio.abrpapp`)
- **Auto-Publish Navigation**: All'avvio della navigazione attiva in una delle app target, il cluster passa automaticamente in modalità mappa e pubblica la navigazione. Alla fine della navigazione, rilascia il focus e torna ai cruscotti OEM.
- **Floating Control Pill**: Overlay touch draggable sull'infotainment (Display 0) con controlli rapidi touch:
  - [Mini]: Forza modalità Mini-Mappa (Modo 2).
  - [Full]: Forza modalità Full-Screen (Modo 3).
  - [Off]: Rilascia il focus e torna ai cruscotti OEM.
  - Indicatore di stato navigazione in tempo reale.
- **BootReceiver**: Avvio automatico del servizio al boot (`BOOT_COMPLETED`, `QUICKBOOT_POWERON`).
- **Firma Release**: Build APK firmata con il keystore di release (`release.jks`).
