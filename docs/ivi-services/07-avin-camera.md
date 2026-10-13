# 7. Entrées AV et caméra de recul

## 7.1 Caméra de recul [A][V]
- Détection : GPIO 2 (actif bas, mode 0) ou CAN (`action_backcar_notification`). Ignorée si
  `[Customer] BackCarFunction` = 1 ; avec AVM 360, attend `isCanDetectCcd`.
- **Recul rapide** : affiché avant le démarrage d'Android (Autochips) ; état dans
  `persist.action.fastreverse.state`. `ICar.disableFastReverse()` / `isInFastReverse()`.
- Ensuite : `MainService` démarre `com.autochips.camera/.CameraService` et `com.jancar.backcar/.BackCarService`
  (action `autochips.intent.action.BACKCAR_SERVICE`) **s'ils existent : ni l'un ni l'autre n'est dans la ROM UJC201**
  (dump stock, system et vendor). Sur AC8257, `exitFastReverse()` ne fait rien. A la marche arriere, `CarService`
  envoie seulement `android.backcar.action.STARTED` / `FINISH` et `onCcdChanged` : **ivi-services n'ouvre aucune
  appli camera**.
- **Vue arriere avec lignes de guidage = `com.baony.avm360`** (`vendor/app/ivi-avm360`, v2.22.141 ; les variantes
  `ivi-avm360-bus` / `-dvr` 9.24 ont le meme paquet, Android garde la plus recente). Elle se met en vue arriere sur le
  broadcast **`Voice_Reverse_State`** (extra int `Signal_State` 1 / 0, `CanBusBroadcastReceiver` → `CanBusHandler.onReverse`
  → passe au premier plan) ; recepteur dynamique enregistre par son `Application` : le processus doit tourner (service
  exporte `com.baony.ui.service.AVMCanBusService`). Autres signaux : `Voice_Acc_State`, `Voice_Speed_State`,
  `Voice_LRReverse_State`, `Voice_Camera_State`. Activite principale `com.baony.ui.activity.AVMBVActivity`.
- `com.jancar.hd2cam` est le **DVR** (camera embarquee, service `jancar.hd2cam.intent.DVR`), pas la vue de recul.
- Recul natif Autochips : `vendor.autochips.hardware.backcar@1.0-service` (`backcar_daemon`, `on fastserver`),
  `fastdisplay@2.0`, `avmhal@1.0`, `/system/bin/atcavm_server`.
- Caméras exposées par le **HAL caméra Autochips** (`vendor/etc/atc_camera_config.xml`) : USB id 0, MIPI « sub »
  id 2, CVBS id 3, MIPI VC id 8 ; sources dans `atc_camera_source_config.xml` ; AVM 360 dans `/avm/*.xml`.
  Une app peut donc ouvrir la caméra avec l'API Camera2 standard.
- Effets de la marche arrière dans ivi-services : volume CCD, déverrouillage de l'écran, `sys.jancar.ccd` (AVM),
  callback `onCcdChanged`, alimentation caméra (`setCcdPower`, coupée 2 s après la fin).

## 7.2 Entrées AV (`AVInService`) [A]
- Ports (`AVInPort`) ouverts par les apps (`IAVIn.open(avId, callback, package)`). Choix de la vidéo :
  GPIO 167 / 165 (AV 1/11 → 00, 31 → 10, 32 → 11). Choix du son : entrée AUX/AV de la puce audio.
- Autorisation vidéo selon le frein à main (`isVideoPermit`, `[HandBrakeLimit]`).
- Réglages d'image (luminosité, contraste…) via des paramètres `IAVIn`.
- Sur cette carte : pas de décodeur vidéo I2C (`platformHasVideoSignal` renvoie toujours vrai). La vidéo passe
  par le HAL caméra (`openAndroidCamera`).
- Fonctions TV/IR (`controlTV`, `sendTouch`) : boîtiers TV externes, sans intérêt ici.
