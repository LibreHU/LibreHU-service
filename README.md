# LibreHU-service

Service libre de gestion des autoradios Jancar / Autochips AC8257 (UJC201) : MCU, alimentation, puce audio,
radio, touches, caméra. Il vise à remplacer `com.jancar.services` (ivi-services) et à exposer une API propre
aux apps LibreHU (MCU Toolkit, fork ViPER4Android, radio, caméra…).

État : **premières bases (v0, non testée sur l'appareil)**. Le protocole MCU et le pilote audio sont couverts par
des tests unitaires ; le comportement réel sur l'UJC201 reste à valider.

## Captures d'écran

| | |
|---|---|
| ![Accueil](docs/screenshots/home.png)<br>Accueil | ![Véhicule : nom, caméra de recul](docs/screenshots/vehicle.png)<br>Véhicule : nom, caméra de recul |
| ![Trames CAN](docs/screenshots/can.png)<br>Trames CAN | ![Audio](docs/screenshots/audio.png)<br>Audio |
| ![Bluetooth](docs/screenshots/bluetooth.png)<br>Bluetooth | ![Écran](docs/screenshots/display.png)<br>Écran |
| ![Lumières (LED)](docs/screenshots/lights.png)<br>Lumières (LED) | ![Système](docs/screenshots/system.png)<br>Système |
| ![Avancé : MCU](docs/screenshots/advanced.png)<br>Avancé : MCU | ![Outils MCU](docs/screenshots/mcu-tools.png)<br>Outils MCU |

Captures sur l'émulateur Android 11 (1280×720, 160 dpi comme l'autoradio), sans autoradio branché : les valeurs du véhicule et de la MCU sont vides. Refaites-les avec l'action **Screenshots** de [LibreHU-OTA-app](https://github.com/LibreHU/LibreHU-OTA-app/tree/main/screenshots).

## Ce qui est implémenté

| Module | Contenu | Où |
|---|---|---|
| Transport MCU | trame `JAC_V1` (`EE FA LEN CMD DATA CS`), ACK `C0`, renvoi 500 ms × 5 pour `1F 08 01 F1 80`, trames longues (CAN, LEN ≥ 0x80) | `core/.../mcu` |
| Véhicule | ACC, frein à main, feux, version MCU (trames MCU) ; marche arrière et clignotants (GPIO 2 / 7 / 6, actifs bas ; clignotants filtrés comme ivi-services, entrée bloquée à 0 sans clignoter = non branchée, désactivables) | `core/.../unit/HeadUnit.kt` |
| Alimentation | `1F 01` au démarrage ; ACC on : démute (MCU `08 00`, GPIO 166, puce), rétroéclairage GPIO 5 après 800 ms, ampli externe `44` ; ACC off : mute, rétroéclairage coupé, `44 00` | idem |
| Horloge | heure Android réglée depuis la RTC de la MCU une fois par démarrage (si `SET_TIME`), puis heure Android envoyée à la MCU chaque minute ; heure GPS périodique (15 min à 12 h) ; chaque sens activable, synchro manuelle. Voir [docs/gps-time.md](docs/gps-time.md) | `HeadUnit.kt`, `time/TimeController.kt` |
| Audio | pilote **ROHM BD37534** (I2C 6 @0x40) : séquence d'init de Jancar, source Android ou **AUX** (entrée 0, +5 dB ; onglet Audio, API 6, intent `org.librehu.service.AUDIO_SOURCE`) ; pendant l'AUX, le son d'Android ajouté par l'entrée de mixage de la puce comme le fait ivi-services (mono, niveau suivant le volume, réglable), volume (courbe 0..40), mute doux, graves/médiums/aigus, balance/fader 4 voies, loudness, caisson on/off + niveau ; réglages mémorisés | `core/.../audio/Bd37534.kt` |
| Touches, CAN | trames `20`/`30` et `10` relayées brutes aux clients ; envoi CAN (`10`) | API |
| Antenne radio | demandée par l'app radio (API 2 `setRadioAntenna`) : GPIO 110 + MCU `43`, seulement contact mis | `HeadUnit.kt` |
| API | AIDL `ILibreHuService` + `ILibreHuCallback`, broadcasts `org.librehu.action.VEHICLE_STATE` / `ACC` / `REVERSE` | `app/src/main/aidl` |
| Accès bas niveau | JNI C : tty brut 115200 8N1, `/dev/gpios_ioctl`, I2C (`I2C_SLAVE_FORCE` + `I2C_RDWR`) | `app/src/main/cpp` |
| Bluetooth | équivalent d'`ivi-btservice` : appels (HFP client), musique (A2DP sink + AVRCP), répertoire et journal (PBAP), appairage, reconnexion du dernier téléphone, sonnerie ; API `ILibreHuBluetooth` (API 3). Voir [docs/bluetooth.md](docs/bluetooth.md) | `app/.../bt`, `core/.../bt` |
| Protocoles MCU | profils JSON importables / exportables (format, ACK, signaux, trames, câblage de la carte) ; Jancar JAC_V1 intégré, moteur générique pour les autres. Voir [docs/mcu-profiles.md](docs/mcu-profiles.md) | `core/.../mcu/McuProfile.kt`, `mcu/ProfileStore.kt` |
| OBD-II | ELM327 en Bluetooth ou USB : valeurs moteur, tension batterie, codes défaut ; widgets pour le launcher (valeurs, compteurs analogiques, combiné numérique) ; API 4. Voir [docs/obd.md](docs/obd.md) | `core/.../obd`, `app/.../obd` |
| Affichage | mode sombre d'Android et luminosité selon les feux (liaison MCU, ou ivi-services tant qu'il tient la MCU) | `display/DisplayController.kt` |
| Dalle tactile | calibration 5 points (matrice du pilote Goodix, essai 20 s avec retour automatique, réappliquée au démarrage) ; sans ivi-services, la calibration d'usine Jancar (`pointercal[-LxH].xml`) est réappliquée au démarrage (sinon tactile décalé, comme dans TWRP) ; touches de façade sérigraphiées hors écran = zones de la dalle, actions appui court / long / répétition, mapping d'usine (`touch_key.xml`), menu power / tiroir / verrouillage du launcher, luminosité ± (root). Voir [docs/touch.md](docs/touch.md) | `core/.../touch`, `app/.../touch` |
| Watchdog MCU | PC_READY renvoyé jusqu'à la réponse du MCU (toutes les 4 s) et au réveil de veille (le MCU coupe le SoC après 10 s sans) ; trame de désarmement `1F 05` (réglable, fichier `librehu-service.conf`) ; notification au démarrage ; redémarrage via le MCU (`0E`, API 5) | `HeadUnit.kt`, `config/ServiceConfig.kt` |
| Indicateurs de volume et de luminosité | panneaux façon Android par-dessus les applis : volume de la puce (remplace la barre d'ivi-services) et luminosité d'Android (touches, centre de contrôle, applis ; pas les changements automatiques des feux). Chacun activable, style vertical / horizontal, bord, place le long du bord, distance, 5 tailles, opacité, durée, valeur affichée, réglage au toucher ; couleurs du launcher | `overlay/LevelOverlay.kt`, onglets Audio et Affichage |
| CAN | messages du boîtier (Hiworld `5A A5`) reconstitués, par commande avec octets modifiés, export texte ; **profils de voiture** sélectionnables / importables (JSON, syntaxe des signaux de CliOS) : Renault Clio 3 LNP002 intégré, valeurs décodées. Voir [docs/can-vehicles.md](docs/can-vehicles.md) | `core/.../can`, `can/CanVehicleStore.kt`, onglet CAN |
| Outils MCU | repris de [MCU-tools-app](https://github.com/LibreHU/MCU-tools-app) : état du MCU (version, ACC, frein, feux, mute, antenne, date / heure, dernière touche, compteurs), commandes (mute, antenne, REM, PWM, LED de façade, seuils batterie, heure, apprentissage des touches, vitesse du boîtier CAN), simulation de trames MCU dans le vrai décodeur (rien n'est écrit sur le port) ; trafic décodé, `80` bloquée, `01` / `0E` / `F1` confirmées, export du journal, board id et puce audio | `core/.../mcu/JacDebug.kt`, `ui/McuToolsScreen.kt` |
| Touches du volant (CAN) | touches relayées par le boîtier Hiworld (`0x11`) transformées en actions (volume, piste, sourdine, appli…) comme les touches de façade ; actives par défaut quand ivi-services est désactivé ; code de la dernière touche affiché pour trouver les boutons inconnus ; les boutons réglés dans LibreHU BtnRemap lui sont laissés (diffusion `org.librehu.service.CLAIM_KEYS`, une seule action par appui). Voir [docs/can-vehicles.md](docs/can-vehicles.md#touches-du-volant) | `core/.../can/HiworldWheelKeys.kt`, `can/CanWheelKeys.kt`, onglet CAN |
| Contrôle d'accès | façon SuperSU : applis `org.librehu.*` et système autorisées d'office, les autres sur notification Autoriser / Refuser ; liste dans Avancé → Diagnostic → Accès au service | `ServiceAccess.kt` |
| Arrêt sécurisé | sourdine, pause des lecteurs, écriture des données (`sync`), écran noir « vous pouvez couper le contact » ; le SoC reste allumé, « Reprendre » relance tout (bouton dans la carte Watchdog, intent `org.librehu.service.SAFE_SHUTDOWN`) | `power/SafeShutdownActivity.kt`, `LibreHuService.kt` |
| Diagnostic audio | onglet Diag audio : vu-mètres (sortie Android crête / RMS par le `Visualizer`, haut-parleurs et caisson estimés : la puce ne se relit pas), test son par haut-parleur (1 kHz, 50 Hz caisson), volumes des flux Android, lecteurs actifs, registres écrits dans le BD37534 | `ui/AudioDiagScreen.kt` |
| GPS | réinitialisation (démarrage à froid : `delete_aiding_data`, injection heure / XTRA) et redémarrage du démon GNSS MediaTek (root) ; alerte « satellites à 0 dB = aucun signal » | `time/TimeController.kt`, onglet GPS et heure |
| Nom de la voiture | un nom pour le Bluetooth (ce que voit le téléphone), le nom d'appareil Android et le nom d'hôte réseau (root), et le point d'accès Wi-Fi (root, `softap.conf`, pris en compte au prochain démarrage) ; Véhicule → Nom de la voiture | `car/CarName.kt` |
| Caméra de recul | à la marche arrière (MCU / GPIO 2, après l'option « inverser »), deux modes. **Vue LibreHU** (par défaut) : la caméra arrière en Camera2 dans une fenêtre par-dessus tout, sans ivi-services ni DVR (choix de la caméra, id 10 de la config Jancar en automatique ; miroir, retournement, rotation, zoom, décalage, remplissage), lignes de guidage en perspective réglables (repères rouge / orange / jaune / vert) et trajectoire selon l'angle du volant de la box CAN, radars de la box CAN (0x41) dessinés avec bips de plus en plus rapides, son à l'engagement (bip, double bip, carillon ou fichier), musique baissée ou en pause, détection de mouvement et « approche » expérimentale, aperçu dans un quart d'écran pour régler, arrêt du DVR (root) — voir [docs/reverse-view.md](docs/reverse-view.md). **Appli** : ouverture de l'appli choisie (automatique selon le type de caméra de la ROM, `Settings.Global avm_hd2cam` : 1 → `com.baony.avm360`, mise en vue arrière par son broadcast `Voice_Reverse_State` ; sinon `com.jancar.hd2cam`, dont la vue de recul dépend d'ivi-services), fermeture (root) / accueil / rien au retrait. Broadcasts `android.backcar.action.STARTED` / `FINISH` comme ivi-services, délai réglable | `car/ReverseCamera.kt`, `car/ReverseView.kt`, Véhicule → Recul |
| Autochips (ATC) | sans ATC Settings, par un assistant root (`app_process` sur l'APK, appels à `autochips.jar`) : mode du port USB hôte / périphérique (fichier `data_role` de `dual_role_usb` comme Jancar, + metazone 65616 pour le démarrage, adb en mode périphérique), réglages du recul rapide de l'ARM2 (source CVBS / AHD / 360, mode des caméras, miroir, recadrage, netteté, tramage, 360 rapide, écran arrière) puis envoi à l'ARM2 (`BackCar.updateArm2MdpSetting`), logo de démarrage depuis une image (copie de la partition logo d'abord), boutons Engineer Mode / ATC Settings / ATCLogger / YGPS — voir [docs/atc.md](docs/atc.md) | `atc/AtcHelper.kt`, `atc/AtcTools.kt`, Avancé → Autochips |
| Voyants à l'écran | désactivé par défaut : court indicateur par-dessus les applis quand les feux, la marche arrière ou le frein à main changent, et tant que les clignotants clignotent (chaque voyant activable, en haut ou en bas, durée) | `overlay/StatusOsd.kt`, Écran |
| Widget vue arrière | bouton d'écran d'accueil qui ouvre la vue de recul LibreHU en plein écran (sans son ni musique baissée) ou la ferme | `car/ReverseWidget.kt` |
| LED de façade | couleur et mode des LED des boutons par la MCU (`0F 04` : fixe, cycle, cycle avec les feux) et **clignotement à chaque nouvelle notification** (accès aux notifications, accordable en root) ; la MCU n'allume les LED qu'avec les feux | `car/FrontLed.kt`, `car/NotificationBlinkService.kt`, Écran → Lumières |
| Root | état du root et gestionnaire détecté (Magisk, KernelSU, **KernelSU Next**, APatch, SuperSU) ; un refus est revérifié après 30 s (KernelSU autorise depuis son gestionnaire, sans fenêtre) ; Système → Root | `root/RootShell.kt` |
| Contact coupé, veille | délai avant coupure réglable (0..10 s : le + après-contact chute au démarrage du moteur, écran / son / ampli gardés) ; puis au choix écran éteint seulement, **veille** (médias en pause, accueil, mode avion et GPS coupés, `sync`, minuterie MCU `F1` par tranches de 7 h jusqu'à 14 jours, Android en veille ; réveil : PC_READY, écran, radios, reprise de la lecture) ou **extinction** (`F1 0`). Voir [docs/power.md](docs/power.md) | `core/.../unit/HeadUnit.kt`, `power/Standby.kt`, Système |
| Démarrage des applis | applis LibreHU lancées au démarrage d'Android (récepteurs `BOOT_COMPLETED`) activables une à une (root, `pm enable/disable`) ; Système, carte Démarrage | `power/BootApps.kt` |
| Interface | réglages façon Android Auto, rangés en 7 rubriques (barre de gauche) avec leurs pages en pastilles : **Accueil** ; **Véhicule** (véhicule, OBD, CAN) ; **Audio** ; **Bluetooth** ; **Écran** (affichage, dalle tactile, lumières) ; **Système** (root, veille, démarrage des applis, watchdog ; GPS et heure) ; **Avancé** (MCU, outils MCU, diag audio, diagnostic : trafic décodé, journal, envoi de trames, accès au service) ; thème et couleur d'accent du launcher sur tous les éléments | `MainActivity`, `ui/` |

Ce qui manque encore (sources radio / AV de la puce audio, entrées vidéo…) : voir le tableau ci-dessous. Pas encore fait non plus : couche
de compatibilité `com.jancar.services.*`.

## LibreHU vs ivi-services

Comparaison avec `com.jancar.services` (analyse : [docs/ivi-services/](docs/ivi-services/README.md)). Les ✅ côté
LibreHU sont écrits et passent la CI, **pas encore testés sur l'appareil**.

Légende : ✅ fait · 🟡 partiel · ❌ absent · ➖ sans objet sur l'UJC201

| Domaine | Fonction | ivi-services | LibreHU | Où (LibreHU) |
|---|---|---|---|---|
| **MCU** | Liaison série, trames, accusés de réception, renvois | ✅ | ✅ | service (`core/mcu`) |
| | Protocoles d'autres autoradios (profils importables / exportables) | ❌ | ✅ | service, Avancé → MCU |
| | Mise à jour du firmware de la MCU | ✅ | ❌ | — |
| **Véhicule** | Contact (ACC), frein à main, feux, version MCU | ✅ | ✅ | service |
| | Marche arrière, clignotants (GPIO) | ✅ | ✅ | service |
| | Diffusion aux apps | ✅ (sans contrôle d'accès) | ✅ (contrôle d'accès façon SuperSU) | API AIDL + broadcasts |
| **Alimentation** | Contact mis / coupé : sourdine, rétroéclairage, ampli externe | ✅ | ✅ | service |
| | Mise en veille après coupure du contact (fermeture des apps, mode avion, veille MCU `F1`) | ✅ | ✅ (sans fermeture des applis ; non testé) | service, Système |
| | Coupure brève du contact au démarrage du moteur ignorée | 🟡 (minuterie « ACC long off », écran coupé quand même) | ✅ (délai réglable) | service, Système |
| | Choix des applis lancées au démarrage | ❌ | ✅ (root) | service, Système |
| | Reset du hub USB, sourdine au démarrage | ✅ | 🟡 (sourdine seulement) | service |
| | Horloge MCU ↔ Android | ✅ | ✅ | service |
| **Audio (BD37534)** | Volume, sourdine, tonalité, balance/fader, loudness, caisson, source Android / AUX | ✅ | ✅ | service, onglet Audio |
| | Ampli externe (sortie REM) | ✅ | ✅ | service |
| | Choix de la source de la puce (Android, radio, AUX, AV) + volume par source | ✅ | 🟡 (Android / AUX avec Android mixé par-dessus l'AUX, pas de volume par source) | service, onglet Audio |
| | Priorités : appel, navigation, marche arrière, sourdines anti-« pop » | ✅ | 🟡 (focus audio pendant les appels) | module Bluetooth |
| **Radio** | Tuner FM interne MediaTek | ✅ (app Jancar) | ✅ | app [LibreHU FM](https://github.com/LibreHU/LibreHU-FM-App) |
| | Alimentation de l'antenne | ✅ | ✅ | service (API 2) |
| | Tuner DAB | ✅ | ➖ | — |
| **Bluetooth** | Appels, musique, répertoire, appairage, reconnexion | ✅ (`ivi-btservice`) | ✅ | service, onglet Bluetooth |
| **Touches** | Lecture des touches volant / façade / molette | ✅ | ✅ (relayées brutes) | service |
| | Choix de l'action de chaque touche | ✅ (config Jancar) | ✅ | app [LibreHU BtnRemap](https://github.com/LibreHU/LibreHU-BtnRemap-app) |
| | Touches du volant via le boîtier CAN | ✅ (`ivi-canbus`) | ✅ (actions au choix) | service, onglet CAN |
| | Zones tactiles hors écran (touches de façade) | ✅ (`touch_key.xml`) | ✅ (root ; apprentissage par appui de 2 s) | service, onglet Dalle tactile |
| | Télécommande infrarouge | ✅ | ❌ | — |
| | Touche power (verrouillage écran, actions court / long) | ✅ | ❌ | — |
| **Écran** | Luminosité jour / nuit selon les feux | ✅ | ✅ | service, onglet Affichage |
| | Mode sombre Android selon les feux | ❌ | ✅ | service ou launcher |
| | Calibration tactile | ✅ (`pointercal.xml`) | ✅ (root ; peut aussi écrire `pointercal.xml`) | onglet Dalle tactile |
| | Rotation, économiseur d'écran | ✅ | ❌ | — |
| **Caméra / vidéo** | Lancement de la caméra de recul | ✅ (avec l'app Autochips) | ✅ (vue intégrée Camera2 avec lignes, radars, bips et mouvement, ou appli au choix ; avant Android, le recul rapide de l'ARM2 d'Autochips, réglable dans Avancé → Autochips) | service, Véhicule |
| | Entrées AV, blocage vidéo frein desserré | ✅ | ❌ | — |
| **CAN (Hiworld)** | Relais des trames du boîtier | ✅ | ✅ | service (`onCanData`, `sendCanData`) |
| | Décodage (portes, clim, volant…) | ➖ (fait par `ivi-canbus`) | 🟡 (profils de voiture : valeurs affichées, touches volant) | service, onglet CAN |
| **Véhicule (extra)** | OBD-II par ELM327 (valeurs moteur, codes défaut) | ❌ | ✅ | service, onglet OBD + widget |
| | Pression des pneus (TPMS USB) | ❌ (app à part) | ✅ | [LibreHU Launcher](https://github.com/LibreHU/LibreHU-Launcher-App) (+ widget) |
| **Matériel divers** | LED de façade (couleur, mode, clignotement aux notifications) | ✅ | ✅ | service, Écran → Lumières |
| | Ventilateur, G-sensor | ✅ | ❌ | — |
| | Nom de la voiture (Bluetooth, réseau, point d'accès) | ❌ | ✅ (root pour le réseau et le point d'accès) | service, Véhicule |
| | Accès I2C brut pour les apps | ✅ (ouvert à toutes) | ❌ (volontairement) | — |
| **Système** | Réinitialisation usine, reboot, journal logcat | ✅ | 🟡 (journal + trafic MCU) | Avancé → Diagnostic |
| | Heure GPS (+ test du GPS) | ✅ | ✅ (+ synchro MCU ↔ Android réglable) | service, onglet GPS et heure |
| | Fermeture / ouverture d'apps | ✅ | 🟡 (arrêt forcé depuis le launcher) | launcher |
| **Média / projection** | Scanner de fichiers et lecteurs Jancar | ✅ | ➖ (MediaStore / MediaSession d'Android) | — |
| | Coordination CarPlay / Android Auto / HiCar… | ✅ | ❌ | — |
| **Spécifique clients** | Voix aispeech, combiné, TBox, caméra 360°, TV | ✅ | ➖ | — |
| **Interface** | Réglages | apps Jancar séparées | ✅ app unique façon Android Auto (7 rubriques) | service |

Reste à faire pour se passer d'ivi-services, par ordre d'importance :
1. valider la veille sur l'appareil (sortie de suspension, consommation) ;
2. sources radio / AV de la puce audio et priorités (navigation, marche arrière) ;
3. valider sur l'appareil la vue de recul intégrée (id de caméra, sens des radars) et la radio FM sans l'appli Jancar ;
4. entrées AV et blocage vidéo frein desserré ;
5. touche power, rotation, économiseur d'écran ;
6. mise à jour de la MCU, infrarouge, ventilateur.

## Installer et tester

1. Installer l'APK avec [LibreHU Apps (OTA)](https://github.com/LibreHU/LibreHU-OTA-app), ou le récupérer dans la
   release `latest-main` du dépôt (APK *release* signé avec la clé commune des applis LibreHU, `keystore/`) et
   `adb install`. Les anciens APK de debug avaient chacun une clé aléatoire : désinstaller une fois avant.
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
<queries><package android:name="org.librehu.service" /></queries>
```
Accès contrôlé par le service, à la manière de SuperSU : les applis LibreHU (`org.librehu.*`) sont autorisées
automatiquement, quel que soit l'ordre d'installation ; toute autre appli reçoit une `SecurityException` jusqu'à
ce que l'utilisateur l'autorise (notification « … veut utiliser LibreHU-service », ou Avancé → Diagnostic → Accès au service).
La permission `org.librehu.permission.HEADUNIT` reste déclarée pour les anciennes versions des applis.
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
- Contact coupé, veille et extinction : [docs/power.md](docs/power.md)
- Firmware MCU et protocole : [MCU-tools-app/docs/mcu_firmware.md](https://github.com/LibreHU/MCU-tools-app/blob/main/docs/mcu_firmware.md)
- Puce audio (BD37534), AIDL `IAudio` : [MCU-tools-app/docs/ivi_audio.md](https://github.com/LibreHU/MCU-tools-app/blob/main/docs/ivi_audio.md)
