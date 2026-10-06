# LibreHU-service

Service libre de gestion des autoradios Jancar / Autochips AC8257 (UJC201) : MCU, alimentation, puce audio,
radio, touches, caméra. Il vise à remplacer `com.jancar.services` (ivi-services) et à exposer une API propre
aux apps LibreHU (MCU Toolkit, fork ViPER4Android, radio, caméra…).

État : **premières bases (v0, non testée sur l'appareil)**. Le protocole MCU et le pilote audio sont couverts par
des tests unitaires ; le comportement réel sur l'UJC201 reste à valider.

## Ce qui est implémenté

| Module | Contenu | Où |
|---|---|---|
| Transport MCU | trame `JAC_V1` (`EE FA LEN CMD DATA CS`), ACK `C0`, renvoi 500 ms × 5 pour `1F 08 01 F1 80`, trames longues (CAN, LEN ≥ 0x80) | `core/.../mcu` |
| Véhicule | ACC, frein à main, feux, version MCU (trames MCU) ; marche arrière et clignotants (GPIO 2 / 7 / 6, actifs bas ; clignotants filtrés comme ivi-services, entrée bloquée à 0 sans clignoter = non branchée, désactivables) | `core/.../unit/HeadUnit.kt` |
| Alimentation | `1F 01` au démarrage ; ACC on : démute (MCU `08 00`, GPIO 166, puce), rétroéclairage GPIO 5 après 800 ms, ampli externe `44` ; ACC off : mute, rétroéclairage coupé, `44 00` | idem |
| Horloge | heure Android réglée depuis la RTC de la MCU une fois par démarrage (si `SET_TIME`), puis heure Android envoyée à la MCU chaque minute ; heure GPS périodique (15 min à 12 h) ; chaque sens activable, synchro manuelle. Voir [docs/gps-time.md](docs/gps-time.md) | `HeadUnit.kt`, `time/TimeController.kt` |
| Audio | pilote **ROHM BD37534** (I2C 6 @0x40) : séquence d'init de Jancar, entrée Android, volume (courbe 0..40), mute doux, graves/médiums/aigus, balance/fader 4 voies, loudness, caisson on/off + niveau ; réglages mémorisés | `core/.../audio/Bd37534.kt` |
| Touches, CAN | trames `20`/`30` et `10` relayées brutes aux clients ; envoi CAN (`10`) | API |
| Antenne radio | demandée par l'app radio (API 2 `setRadioAntenna`) : GPIO 110 + MCU `43`, seulement contact mis | `HeadUnit.kt` |
| API | AIDL `ILibreHuService` + `ILibreHuCallback`, broadcasts `org.librehu.action.VEHICLE_STATE` / `ACC` / `REVERSE` | `app/src/main/aidl` |
| Accès bas niveau | JNI C : tty brut 115200 8N1, `/dev/gpios_ioctl`, I2C (`I2C_SLAVE_FORCE` + `I2C_RDWR`) | `app/src/main/cpp` |
| Bluetooth | équivalent d'`ivi-btservice` : appels (HFP client), musique (A2DP sink + AVRCP), répertoire et journal (PBAP), appairage, reconnexion du dernier téléphone, sonnerie ; API `ILibreHuBluetooth` (API 3). Voir [docs/bluetooth.md](docs/bluetooth.md) | `app/.../bt`, `core/.../bt` |
| Protocoles MCU | profils JSON importables / exportables (format, ACK, signaux, trames, câblage de la carte) ; Jancar JAC_V1 intégré, moteur générique pour les autres. Voir [docs/mcu-profiles.md](docs/mcu-profiles.md) | `core/.../mcu/McuProfile.kt`, `mcu/ProfileStore.kt` |
| OBD-II | ELM327 en Bluetooth ou USB : valeurs moteur, tension batterie, codes défaut ; widgets pour le launcher (valeurs, compteurs analogiques, combiné numérique) ; API 4. Voir [docs/obd.md](docs/obd.md) | `core/.../obd`, `app/.../obd` |
| Affichage | mode sombre d'Android et luminosité selon les feux (liaison MCU, ou ivi-services tant qu'il tient la MCU) | `display/DisplayController.kt` |
| Dalle tactile | calibration 5 points (matrice du pilote Goodix, essai 20 s avec retour automatique, réappliquée au démarrage) ; sans ivi-services, la calibration d'usine Jancar (`pointercal[-LxH].xml`) est réappliquée au démarrage (sinon tactile décalé, comme dans TWRP) ; touches de façade sérigraphiées hors écran = zones de la dalle, actions appui court / long / répétition (root). Voir [docs/touch.md](docs/touch.md) | `core/.../touch`, `app/.../touch` |
| Watchdog MCU | PC_READY renvoyé jusqu'à la réponse du MCU (toutes les 4 s) et au réveil de veille (le MCU coupe le SoC après 10 s sans) ; trame de désarmement `1F 05` (réglable, fichier `librehu-service.conf`) ; notification au démarrage ; redémarrage via le MCU (`0E`, API 5) | `HeadUnit.kt`, `config/ServiceConfig.kt` |
| Indicateur de volume | panneau façon Android par-dessus les applis à chaque changement du volume de la puce (vertical / horizontal, bord, taille, durée, réglage au toucher), couleurs du launcher ; remplace la barre de volume d'ivi-services | `overlay/VolumeOverlay.kt`, onglet Audio |
| CAN | messages du boîtier (Hiworld `5A A5`) reconstitués, par commande avec octets modifiés, export texte ; **profils de voiture** sélectionnables / importables (JSON, syntaxe des signaux de CliOS) : Renault Clio 3 LNP002 intégré, valeurs décodées. Voir [docs/can-vehicles.md](docs/can-vehicles.md) | `core/.../can`, `can/CanVehicleStore.kt`, onglet CAN |
| Outils MCU | repris de [MCU-tools-app](https://github.com/LibreHU/MCU-tools-app) : état du MCU (version, ACC, frein, feux, mute, antenne, date / heure, dernière touche, compteurs), commandes (mute, antenne, REM, PWM, LED de façade, seuils batterie, heure, apprentissage des touches, vitesse du boîtier CAN), simulation de trames MCU dans le vrai décodeur (rien n'est écrit sur le port) ; trafic décodé, `80` bloquée, `01` / `0E` / `F1` confirmées, export du journal, board id et puce audio | `core/.../mcu/JacDebug.kt`, `ui/McuToolsScreen.kt` |
| Interface | réglages façon Android Auto : rail d'onglets Accueil, Audio, Bluetooth, OBD, Affichage, GPS et heure, Dalle tactile, MCU, CAN, Outils MCU, Diagnostic (trafic MCU décodé, journal, envoi de trames, export) ; thème du launcher | `MainActivity`, `ui/` |

Ce qui manque encore (veille, sources audio, caméra…) : voir le tableau ci-dessous. Pas encore fait non plus : couche
de compatibilité `com.jancar.services.*`, permission `signature|privileged`.

## LibreHU vs ivi-services

Comparaison avec `com.jancar.services` (analyse : [docs/ivi-services/](docs/ivi-services/README.md)). Les ✅ côté
LibreHU sont écrits et passent la CI, **pas encore testés sur l'appareil**.

Légende : ✅ fait · 🟡 partiel · ❌ absent · ➖ sans objet sur l'UJC201

| Domaine | Fonction | ivi-services | LibreHU | Où (LibreHU) |
|---|---|---|---|---|
| **MCU** | Liaison série, trames, accusés de réception, renvois | ✅ | ✅ | service (`core/mcu`) |
| | Protocoles d'autres autoradios (profils importables / exportables) | ❌ | ✅ | service, onglet MCU |
| | Mise à jour du firmware de la MCU | ✅ | ❌ | — |
| **Véhicule** | Contact (ACC), frein à main, feux, version MCU | ✅ | ✅ | service |
| | Marche arrière, clignotants (GPIO) | ✅ | ✅ | service |
| | Diffusion aux apps | ✅ (sans contrôle d'accès) | ✅ (avec permission) | API AIDL + broadcasts |
| **Alimentation** | Contact mis / coupé : sourdine, rétroéclairage, ampli externe | ✅ | ✅ | service |
| | Mise en veille après coupure du contact (fermeture des apps, mode avion, veille MCU `F1`) | ✅ | ❌ | — |
| | Reset du hub USB, sourdine au démarrage | ✅ | 🟡 (sourdine seulement) | service |
| | Horloge MCU ↔ Android | ✅ | ✅ | service |
| **Audio (BD37534)** | Volume, sourdine, tonalité, balance/fader, loudness, caisson | ✅ | ✅ | service, onglet Audio |
| | Ampli externe (sortie REM) | ✅ | ✅ | service |
| | Choix de la source de la puce (Android, radio, AUX, AV) + volume par source | ✅ | ❌ (entrée Android fixe) | — |
| | Priorités : appel, navigation, marche arrière, sourdines anti-« pop » | ✅ | 🟡 (focus audio pendant les appels) | module Bluetooth |
| **Radio** | Tuner FM interne MediaTek | ✅ (app Jancar) | ✅ | app [LibreHU FM](https://github.com/LibreHU/LibreHU-FM-App) |
| | Alimentation de l'antenne | ✅ | ✅ | service (API 2) |
| | Tuner DAB | ✅ | ➖ | — |
| **Bluetooth** | Appels, musique, répertoire, appairage, reconnexion | ✅ (`ivi-btservice`) | ✅ | service, onglet Bluetooth |
| **Touches** | Lecture des touches volant / façade / molette | ✅ | ✅ (relayées brutes) | service |
| | Choix de l'action de chaque touche | ✅ (config Jancar) | ✅ | app [LibreHU BtnRemap](https://github.com/LibreHU/LibreHU-BtnRemap-app) |
| | Apprentissage des touches du volant | ✅ | 🟡 (trames relayées, pas d'écran) | — |
| | Zones tactiles hors écran (touches de façade) | ✅ (`touch_key.xml`) | ✅ (root) | service, onglet Dalle tactile |
| | Télécommande infrarouge | ✅ | ❌ | — |
| | Touche power (verrouillage écran, actions court / long) | ✅ | ❌ | — |
| **Écran** | Luminosité jour / nuit selon les feux | ✅ | ✅ | service, onglet Affichage |
| | Mode sombre Android selon les feux | ❌ | ✅ | service ou launcher |
| | Calibration tactile | ✅ (`pointercal.xml`) | ✅ (root ; peut aussi écrire `pointercal.xml`) | onglet Dalle tactile |
| | Rotation, économiseur d'écran | ✅ | ❌ | — |
| **Caméra / vidéo** | Lancement de la caméra de recul | ✅ (avec l'app Autochips) | ❌ (recul rapide Autochips toujours actif) | — |
| | Entrées AV, blocage vidéo frein desserré | ✅ | ❌ | — |
| **CAN (Hiworld)** | Relais des trames du boîtier | ✅ | ✅ | service (`onCanData`, `sendCanData`) |
| | Décodage (portes, clim, volant…) | ➖ (fait par `ivi-canbus`) | 🟡 (touches volant seulement) | app BtnRemap |
| **Véhicule (extra)** | OBD-II par ELM327 (valeurs moteur, codes défaut) | ❌ | ✅ | service, onglet OBD + widget |
| | Pression des pneus (TPMS USB) | ❌ (app à part) | ✅ | [LibreHU Launcher](https://github.com/LibreHU/LibreHU-Launcher-App) (+ widget) |
| **Matériel divers** | LED de façade, ventilateur, G-sensor | ✅ | ❌ | — |
| | Accès I2C brut pour les apps | ✅ (ouvert à toutes) | ❌ (volontairement) | — |
| **Système** | Réinitialisation usine, reboot, journal logcat | ✅ | 🟡 (journal + trafic MCU) | onglet Diagnostic |
| | Heure GPS (+ test du GPS) | ✅ | ✅ (+ synchro MCU ↔ Android réglable) | service, onglet GPS et heure |
| | Fermeture / ouverture d'apps | ✅ | 🟡 (arrêt forcé depuis le launcher) | launcher |
| **Média / projection** | Scanner de fichiers et lecteurs Jancar | ✅ | ➖ (MediaStore / MediaSession d'Android) | — |
| | Coordination CarPlay / Android Auto / HiCar… | ✅ | ❌ | — |
| **Spécifique clients** | Voix aispeech, combiné, TBox, caméra 360°, TV | ✅ | ➖ | — |
| **Interface** | Réglages | apps Jancar séparées | ✅ app unique façon Android Auto (9 onglets) | service |

Reste à faire pour se passer d'ivi-services, par ordre d'importance :
1. mise en veille après la coupure du contact (sinon risque de décharger la batterie) ;
2. choix de la source de la puce audio et priorités (navigation, marche arrière) ;
3. caméra de recul après le démarrage d'Android ;
4. touche power, rotation, économiseur d'écran ;
5. mise à jour de la MCU, LED de façade, infrarouge.

## Installer et tester

1. Récupérer l'APK de l'Action **Build** (artefact `LibreHU-service-debug-…`) et l'installer : `adb install`.
2. **Désactiver ivi-services** : deux programmes sur `/dev/ttyS1` se volent les octets, le service refuse donc de
   démarrer tant qu'il est actif :
   ```
   adb shell pm disable-user --user 0 com.jancar.services
   ```
   Retour arrière : `adb shell pm enable com.jancar.services`. Attention : les apps Jancar (réglages, radio, BT,
   écran de recul…) dépendent d'ivi-services et perdront ces fonctions. Le bouton « Démarrer malgré ivi-services »
   force le démarrage pour un essai rapide, au risque de conflits sur le port série.
3. Ouvrir **LibreHU service** : la liaison doit passer à `RUNNING`, la version MCU s'afficher, et le trafic MCU
   défiler (ACC `00 01`, version `0A`, heure `09`).
4. Pour l'horloge (`SET_TIME`) et un démarrage fiable, installer l'APK comme app privilégiée (`/system/priv-app`,
   avec la liste blanche de permissions) — nécessaire aussi sous LineageOS.

Points à vérifier sur l'appareil (non confirmés par l'analyse statique) : quel fader du BD37534 (1 ou 2) est à
gauche, l'effet réel de `0x2C = 0x00` (coupure caisson), et la logique des GPIO de marche arrière / clignotants.

## Utiliser l'API depuis une app

```xml
<uses-permission android:name="org.librehu.permission.HEADUNIT" />
<queries><package android:name="org.librehu.service" /></queries>
```
```kotlin
bindService(Intent("org.librehu.service.BIND").setPackage("org.librehu.service"), connection, BIND_AUTO_CREATE)
// onServiceConnected : val api = ILibreHuService.Stub.asInterface(binder)
api.setVolume(20); api.setBalanceFade(30, 20); api.registerCallback(callback)
```
Copier les fichiers `.aidl` dans l'app cliente (même paquet `org.librehu.service`) ; pour le Bluetooth, aussi
ceux de `org/librehu/service/bt/` et `bt/BtParcels.kt`.

## Construire

- Tout : `./gradlew assembleDebug` (SDK Android + NDK).
- Cœur seul, sans SDK Android : `./gradlew -PcoreOnly :core:test`.

## Documentation

- Analyse d'ivi-services et plan de réécriture : [docs/ivi-services/](docs/ivi-services/README.md)
- Bluetooth (analyse d'ivi-btservice, module, installation) : [docs/bluetooth.md](docs/bluetooth.md)
- Profils de protocole MCU : [docs/mcu-profiles.md](docs/mcu-profiles.md)
- OBD-II / ELM327 : [docs/obd.md](docs/obd.md)
- Profils de voiture (décodage CAN) : [docs/can-vehicles.md](docs/can-vehicles.md)
- Firmware MCU et protocole : [MCU-tools-app/docs/mcu_firmware.md](https://github.com/LibreHU/MCU-tools-app/blob/main/docs/mcu_firmware.md)
- Puce audio (BD37534), AIDL `IAudio` : [MCU-tools-app/docs/ivi_audio.md](https://github.com/LibreHU/MCU-tools-app/blob/main/docs/ivi_audio.md)
