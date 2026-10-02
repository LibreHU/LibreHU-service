# 7. Entrées AV et caméra de recul

## 7.1 Caméra de recul [A][V]
- Détection : GPIO 2 (actif bas, mode 0) ou CAN (`action_backcar_notification`). Ignorée si
  `[Customer] BackCarFunction` = 1 ; avec AVM 360, attend `isCanDetectCcd`.
- **Recul rapide** : affiché avant le démarrage d'Android (Autochips) ; état dans
  `persist.action.fastreverse.state`. `ICar.disableFastReverse()` / `isInFastReverse()`.
- Ensuite : `autochips.intent.action.BACKCAR_SERVICE` (démarré par `MainService`) et l'app
  **`com.autochips.backcarapp`**. Broadcasts `android.backcar.action.PREPARE_START`, `STARTED`, `FINISH`.
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
