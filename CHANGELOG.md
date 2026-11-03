# Changelog

Tutte le modifiche rilevanti a questo progetto saranno documentate in questo file.

Il formato è basato su [Keep a Changelog](https://keepachangelog.com/it/1.1.0/).

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
