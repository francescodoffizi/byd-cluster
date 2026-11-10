# BydClusterNavBridge (BYD Cluster)

Bridge Android e motore di integrazione HUD per il quadro strumenti digitale (Cluster) dei veicoli BYD basati su architettura DiLink (Qualcomm Snapdragon Automotive SA8155P / DiLink 4.0 / DiLink 5.0 / DiLink 100).

Sviluppato da **Francesco D'Offizi** e rilasciato sotto licenza **GNU General Public License v3 (GPLv3)**.

---

## Panoramica

`BydClusterNavBridge` è un'applicazione Android di servizio concepita per veicolare le istruzioni di navigazione (Turn-by-Turn) e proiezioni grafiche vettoriali direttamente sul display secondario del quadro strumenti (Display 1) posizionato dietro al volante.

L'applicazione si interfaccia a basso livello con il servizio di comunicazione cluster di sistema (`ClusterCoreService`), consentendo a navigatori di terze parti (come Google Maps, Waze, A Better Routeplanner - ABRP e HERE WeGo) di integrarsi perfettamente con le modalità di visualizzazione del veicolo.

```text
 +--------------------------------------------------------------------+
 |                       CONSUMER NAVIGATION APPS                     |
 |        (Google Maps, Waze, A Better Routeplanner - ABRP, HERE)     |
 +---------------------------------+----------------------------------+
                                   |
              [Notifiche Android & Eventi di Accessibilità]
                                   |
                                   v
 +--------------------------------------------------------------------+
 |                       BydClusterNavBridge                          |
 |                                                                    |
 |  +--------------------------+      +----------------------------+  |
 |  | NavNotificationListener  |      | NavAccessibilityService    |  |
 |  | (Parsing manovra, svolta,|      | (Ispezione HUD dinamico    |  |
 |  |  distanza e via)         |      |  per app Flutter/ibride)   |  |
 |  +-------------+------------+      +-------------+--------------+  |
 |                |                                 |                 |
 |                +----------------+----------------+                 |
 |                                 v                                  |
 |                   +---------------------------+                    |
 |                   |   NavClusterService (FGS) |                    |
 |                   |   - Telemetria e tracking |                    |
 |                   |   - Macchina a stati HUD  |                    |
 |                   +-------------+-------------+                    |
 +---------------------------------+----------------------------------+
                                   |
         +-------------------------+-------------------------+
         |                                                   |
         v [Dati Vettoriali TBT]                             v [Superficie Video Display 1]
+---------------------------------+                 +---------------------------------+
|      ClusterCommBridge (IPC)    |                 |   ClusterPresentation (EGL)     |
| - DataType 15 / 16 / 31 / 33    |                 | - Motore Vettoriale MapLibre GL |
| - Commutazione modalità QNX     |                 | - Palette Dark OLED ad alto     |
|                                 |                 |   contrasto                     |
+----------------+----------------+                 +----------------+----------------+
                 |                                                   |
                 +-------------------------+-------------------------+
                                           |
                                           v
                 +---------------------------------------------------+
                 |           BYD DIGITAL INSTRUMENT CLUSTER          |
                 +---------------------------------------------------+
```

---

## Caratteristiche Principali

1. **Supporto Multi-Modale Completo**:
   - **Modalità Semplice (Mode 2)**: Visualizzazione HUD nativa a frecce vettoriali con zero carico GPU su Android (distanza, nome via, icona di manovra e orario stimato di arrivo).
   - **Modalità Piccolo (Mode 3)**: Apertura dinamica della finestra destra/centrale tra i quadranti del cruscotto, con mini-mappa 3D e box indicazioni compatto.
   - **Modalità Intero / Grande (Mode 4)**: Mappa panoramica a pieno schermo 1920x720 sul cruscotto con rispetto delle coordinate di sicurezza della barra superiore del quadro.

2. **Pipeline di Acquisizione Ibrida**:
   - `NotificationListenerService` ad alta efficienza per l'acquisizione delle notifiche di navigazione standard.
   - `AccessibilityService` leggero per la cattura in tempo reale dei banner di svolta da app ibride e multipiattaforma (es. ABRP e HERE WeGo).

3. **Rendering Grafico Adattivo**:
   - Mappa vettoriale 3D basata su MapLibre GL e stile dark ottimizzato per schermi OLED.
   - Tracciamento della rotta con polyline brillante (#00E5FF) e orientamento della vista basato su bussola e GPS veicolare.

---

## Requisiti e Permessi

Per il corretto funzionamento dell'applicazione sono necessari i seguenti permessi e impostazioni di sistema via ADB:

```bash
# 1. Permesso di scrittura impostazioni sicure (per commutazione display cluster)
adb shell pm grant com.byd.clusternavbridge android.permission.WRITE_SECURE_SETTINGS

# 2. Accesso alla posizione in background e foreground (per velocità e navigazione)
adb shell pm grant com.byd.clusternavbridge android.permission.ACCESS_FINE_LOCATION
adb shell pm grant com.byd.clusternavbridge android.permission.ACCESS_BACKGROUND_LOCATION

# 3. Abilitazione del listener delle notifiche (in caso di listener multipli concatenare con ':')
adb shell settings put secure enabled_notification_listeners "com.byd.clusternavbridge/com.byd.clusternavbridge.service.NavNotificationListener"
```

---

## Compilazione

Il progetto utilizza Gradle con Kotlin DSL:

```bash
# Compilazione debug
./gradlew assembleDebug

# Compilazione release
./gradlew assembleRelease
```

L'APK generato si troverà in `app/build/outputs/apk/release/app-release.apk`.

---

## Documentazione Tecnica Approfondita

Per un'analisi approfondita dell'architettura hardware, della suddivisione dei display, delle specifiche IPC Binder e delle modalità operative per progetti terzi della community, consultare il documento:
- [Technical Paper: BYD DiLink Digital Instrument Cluster Architecture (docs/paper.md)](docs/paper.md)

---

## Avviso di Sicurezza e Disclaimer Legale

> **IMPORTANTE AVVISO DI SICUREZZA AUTOMOBILISTICA**  
> Questo software e la relativa documentazione sono forniti esclusivamente per scopi didattici, sperimentali e di ricerca.
>
> 1. **Responsabilità del Conducente**: Il conducente del veicolo mantiene in ogni momento la totale ed esclusiva responsabilità della guida sicura del veicolo a motore nel pieno rispetto delle norme della circolazione stradale. La distrazione alla guida può provocare lesioni gravi o fatali e danni a persone o cose. Non interagire con impostazioni o configurazioni dell'applicazione durante la marcia del veicolo.
> 2. **Assenza di Affiliazione**: Questo progetto è un'iniziativa open-source indipendente della community. Non è sponsorizzato, autorizzato, associato né supportato in alcun modo da **BYD Company Ltd.** o dalle sue società controllate o affiliate. Tutti i marchi registrati appartengono ai rispettivi proprietari.
> 3. **Garanzia "As-Is"**: Il software è distribuito nello stato in cui si trova ("AS IS"), senza alcuna garanzia esplicita o implicita di commerciabilità, idoneità a uno scopo particolare o assenza di difetti. L'utente si assume ogni rischio e responsabilità derivante dall'installazione ed esecuzione di codice di terze parti su dispositivi e centraline automotive.

---

## Licenza

Questo progetto è distribuito sotto licenza **GNU General Public License v3 (GPLv3)**. Consultare il file [LICENSE](LICENSE) per i termini completi.

Qualsiasi modifica o prodotto derivato distribuito a terzi deve obbligatoriamente rilasciare i codici sorgenti sotto la medesima licenza GPLv3, preservando l'attribuzione all'autore originale.

Copyright (C) 2026 Francesco D'Offizi
