# 1. Architecture d'ivi-services

Analyse statique (jadx + `llvm-objdump` sur `libJanCarIVI.so`) de `com.jancar.services`
3.0.0.ac8257.6a605cd0.20250611, extrait d'un UJC201 (`ro.build.display.id=UJC201-V1.1.35R6-250718_0429`,
board id `A0_AN`). Niveaux de confiance : **[A]** lu dans l'APK, **[N]** lu dans la lib native,
**[V]** vu dans le dump de l'appareil, **[D]** déduit.

## 1.1 Paquet
- `com.jancar.services`, **`android:sharedUserId="android.uid.system"`** (signé avec la clé plateforme), lib
  native unique `lib/arm64-v8a/libJanCarIVI.so` [A].
- Permissions notables : `REBOOT`, `DEVICE_POWER`, `FORCE_STOP_PACKAGES`, `SET_TIME`, `SET_TIME_ZONE`,
  `WRITE_SETTINGS`, `INTERACT_ACROSS_USERS`, `MOUNT_UNMOUNT_FILESYSTEMS` [A].
- Code Java ≈ 1 400 classes, dont un SDK partagé (`com.jancar.sdk.*`, aussi embarqué dans les apps Jancar).

## 1.2 Composants exportés [A]
| Composant | Action | Rôle |
|---|---|---|
| `MainService` | `com.jancar.services.action.main` | démarre les autres services, `persist.sys.jancar.deviceid`, lance le service caméra Autochips |
| `AudioService` | `…action.audio` | puce audio (BD37534…), volumes, mute, sources, focus |
| `CarService` | `…action.car` | MCU (`/dev/ttyS1`), ACC/frein/feux/marche arrière, touches, CAN brut |
| `RadioService` | `…action.radio` | tuner FM/AM (I2C), RDS |
| `AVInService` | `…action.avin` | entrées vidéo AV, commutation vidéo |
| `SystemService` | `…action.system` | rétroéclairage, luminosité, écran, apps, reset usine, **accès I2C brut** |
| `MediaService` | `…action.media` | scanner média, sessions, commande des lecteurs Jancar |
| `VoiceService` | `…action.voice` | assistant vocal (aispeech) |
| `NavigationService` | `…action.navigation` | infos de guidage (Gaode/MX) vers combiné |
| `ClusterService` | `…action.cluster` | infos vers combiné d'instruments (via MCU) |
| `DABService` | `…action.dab` | tuner DAB (SPI) |
| `SystemAccessibilityService` | (accessibilité) | clics automatiques (Waze, voix) |
| `BootCompletedReceiver` | `BOOT_COMPLETED` | démarre le démon vocal aispeech |
| `ScanProvider` | authority `com.jancar.services` | base des médias scannés |

**Aucun service n'exige de permission ni ne vérifie l'appelant** : toute app peut piloter la MCU, la puce audio,
l'I2C, l'écran… (API complète : [api.md](api.md)).

## 1.3 Organisation interne [A]
- **Bus d'événements** : greenrobot EventBus. Deux bus : `EventBus.getDefault()` et `IVICoreEventBus`. Les
  protocoles (MCU, CAN) publient des objets (`IVICar.Acc`, `IVICar.Handbrake`, `IVIAudio.EventVolumeChanged`…),
  les services s'y abonnent (`@Subscribe`) et relaient vers les callbacks AIDL des clients.
- **`IVICore`** : machine d'état centrale (ACC, ACC long off, verrouillage écran, autorisation de démute,
  autorisation vidéo selon frein à main, focus audio, touches).
- **`Platform`** : couche matérielle. `Platform.createConfig()` appelle `identifyBoardTypeAC8257()` qui choisit
  une sous-classe selon le board id ; pour `A0_AN` : **`Platform_AutoChips_8257_37534`** (hérite de
  `Platform_AutoChips_8257_Base`). Voir [02-hardware.md](02-hardware.md).
- **JNI** (`com.jancar.services.jni.*` → `libJanCarIVI.so`) : `GPIO`, `I2C`, `SPI`, `SerialPort`, `AudioDevice`
  (pilotes de puces audio), `RadioDevice` (pilotes de tuners), `DABDevice`, `IRTV`, `NecIR`, `KeyCodeFinder`.
- **`*Ctrl`** (`AudioCtrl`, `CarCtrl`, `RadioCtrl`, `SystemCtrl`, `MediaCtrl`…) : façades statiques qui postent
  des événements, utilisées partout.

## 1.4 Démarrage [A]
1. `BOOT_COMPLETED` → `MainService.onCreate()` démarre les 10 services et `autochips.intent.action.BACKCAR_SERVICE`.
2. `CarService` ouvre la MCU, envoie `1F 01` (PC_READY) puis vérifie la version MCU (reflash automatique de
   `/jancar/mcu/jacmcu.bin` si la version est vide ou sans `-`).
3. `Platform.init()` : mute, création de la puce audio sur I2C 6 (`powerOn`), source PC, démarrage `iapd`
   (CarPlay MFi), utilitaires de projection (CarPlay, Android Auto, HiCar, ZLink, WeLink, SpeedPlay).
4. L'ACC reçue de la MCU déclenche la suite (rétroéclairage, démute…) : [04-power-acc.md](04-power-acc.md).

## 1.5 Dépendances vers d'autres apps [A]
| App | Utilisation |
|---|---|
| `com.jancar.settings` | **fournisseur de réglages** `content://com.jancar.settings.provider/settings` (lu via `SettingModel`) ; écrans de réglage |
| `com.jancar.canservice` | **décodage CAN** : reçoit les trames `10` de la MCU via `IPassthroughDataCallback` ; ivi-services ne décode pas le CAN sur cette carte |
| `com.jancar.bluetooth` / `com.jancar.btservice` | téléphone/musique BT (pile Android), contacts `content://com.jancar.bluetooth/…` |
| `com.autochips.backcarapp` | affichage caméra de recul |
| `com.jancar.steeringwheelkeys` | apprentissage des touches volant |
| radio (`content://com.jancar.radio_v2.provider`) | app radio |
| `com.aispeech.lyra.daemon`, `com.jancar.voiceassistant` | voix |
| `com.zjinnova.zlink`, `com.suding.speedplay`, CarPlay/HiCar/WeLink | projection téléphone |
