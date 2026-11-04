# Changelog

Tutte le modifiche rilevanti a questo progetto saranno documentate in questo file.

Il formato è basato su [Keep a Changelog](https://keepachangelog.com/it/1.1.0/).

## [Unreleased]

### Aggiunto
- **Guida Vocale Turn-By-Turn Integrata (VoiceGuidanceManager)**:
  - Implementata sintesi vocale TBT nativa all'interno di `NavClusterService` superando le restrizioni di Package Visibility di Android 11 su ABRP.
  - Collegamento diretto a `org.woheller69.ttsengine` (modello neurale offline Piper VITS con lingua italiana e voce Riccardo/Paola).
  - Canale audio dedicato per veicoli (`USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`, `CONTENT_TYPE_SPEECH`) per abilitare l'audio ducking automatico della radio/multimedia del veicolo durante gli annunci vocali.
  - Algoritmo di pronuncia intelligente a 3 stadi (preavviso iniziale, avvicinamento a ~200m, manovra imminente a <40m) per prevenire lo spam vocale continuo sui cambi di metro di ABRP e Google Maps.
  - Controllo Mute rapido touch sulla Floating Pill (`[Voce ON]` verde / `[Muto]` grigio) e action intent di sistema `ACTION_TOGGLE_VOICE_MUTE` e `ACTION_TEST_VOICE`.

### Modificato & Corretto (Neusoft OEM Reverse Engineering)
- **Allineamento Costanti Display Mode OEM Neusoft**:
  - Riallineate le costanti alle specifiche originali Neusoft (`1 = MODE_OFF`, `2 = MODE_SEMPLICE`, `3 = MODE_PICCOLO`, `4 = MODE_INTERO`), eliminando lo sfasamento 0..3 che provocava selezioni errate da touch.
- **Risoluzione Display Nero su Display 1 HDMI (Surface Injection)**:
  - Integrata `presentationMapTextureView` nel layout XML ed implementato lo sticky broadcast `com.neusoft.na.navigation.set.cluster.surface` con l'handle hardware `Surface` per consentire al video compositor QNX del quadro strumenti di agganciare il flusso video di MapLibre.

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

### Aggiunto & Architettura Completa (Reverse Engineering Neusoft DiLink)
- **Supporto Ufficiale Multi-Modale (Semplice, Piccolo, Grande, Off)**:
  - Completato il Reverse Engineering architetturale del pacchetto di fabbrica `com.neusoft.na.navigation` (`BydMapLauncher.apk`) e di `BYDAutoInstrumentDevice`.
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
