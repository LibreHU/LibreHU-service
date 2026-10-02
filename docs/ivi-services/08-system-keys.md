# 8. Système, touches, tactile, accès I2C

## 8.1 `SystemService` [A]
- Rétroéclairage et écran : `ISystem` 10..13 (`closeBackLight(action)`, `openBackLight`…), verrouillage par la
  touche power, économiseur d'écran, `requestScreenOperate`.
- Luminosité : `setScreenBrightness(id, v)` (jour/nuit), `[Brightness]`.
- Apps : fermer une app ou toutes, ouvrir l'app mémorisée ou la navigation ; `checkHandBreakApps` ferme les apps
  vidéo de `[HandBrakeLimit]` quand le frein est desserré.
- Reset usine (`recoverySystem`), reboot, journal logcat vers fichier.
- GPS : `registerGpsLocationInfoListener`, heure GPS (`GpsTimeUtil`).
- LED de façade (`PanelLightUtil`) : couleurs, auto, clignotement → MCU `0F 04 …` (`ISystem.onCMDAppToService`
  cmd 1 / 2).
- Ventilateur CPU / ampli externe : `onCMDAppToService` cmd 3 → MCU `44`.
- G-sensor : cmd 4 / 5 (calibration, paramètres).

## 8.2 Touches [A]
- **Sources** : trames MCU `20` (volant KEY1/KEY2, façade, molette) ; tactile hors écran (zones de touches
  `/jancar/config/touch_key.xml`) ; IR (`gpio_ir_recv`) ; touches CAN (canservice).
- **Apprentissage du volant** : `StudyKeyManager` compare la mesure (3 valeurs de résistance) aux plages de
  `/jancar/config/ivi-studykey.ini` (écrites par l'app `com.jancar.steeringwheelkeys`) → nom de touche.
- **Codes** : `IVIKey.Key` (HOME 8, MODE 6, VOL+/−, NEXT/PREV, ANSWER 7, HANG_UP 34, FM 30, AM 31, NAVI,
  MUTE, EQ 1003, power…) avec un type (appui court, long, relâché).
- **`KeyCodeUtil.onKeyEvent`** : action interne (volume → puce, radio, média, BT, ouverture d'app depuis
  `[ModeControl]`, `[RemoteControl]`…) ou **keycode Android injecté** (`Instrumentation.sendKeyDownUpSync`,
  ex. 85 lecture/pause). Antirebond : `[KeyAntiShake]`.
- La touche power (`PowerUtil`) déclenche l'action courte ou longue configurée.

## 8.3 Tactile [A]
`TouchEventUtil` : calibration et rotation du tactile Goodix (`/jancar/config/pointercal.xml`,
`gt9xx_props`), touches tactiles hors écran, mode apprentissage (`setTouchLearnStatus`).

## 8.4 Accès I2C brut via `ISystem.CmdApp2Serv` (transaction 38) [A]
`CmdApp2Serv(cmd = 6, ints, datas, strs)` → `SystemService.func_i2c_operation`. Le tableau `datas` est
**renvoyé dans la réponse** (lecture possible).

| `ints` | Opération | `datas` |
|---|---|---|
| `[0, bus, adresse]` | ouvrir | sortie : `datas[0]` = identifiant |
| `[1, bus, adresse]` | fermer | — |
| `[2, bus, adresse, registre, drapeaux]` | écrire | entrée : octets à écrire |
| `[3, bus, adresse, registre, drapeaux, longueur]` | lire | sortie : octets lus |

`drapeaux` = 1 dans les appels internes (pilote BD37534). Ivi-services tourne en uid system : **n'importe
quelle app peut ainsi lire et écrire sur n'importe quel bus I2C, sans root.**
