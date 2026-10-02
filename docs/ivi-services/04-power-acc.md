# 4. Alimentation, ACC, écran (`IVICore`, `SystemService`)

La MCU gère l'alimentation physique (machine d'état dans mcu_firmware.md §5). ivi-services réagit à l'ACC
envoyée par la MCU [A].

## 4.1 ACC présente (`IVICore.onEventAcc(true)`)
1. `Settings.Global accStatus = 1`, broadcast **`com.jancar.services.action.acc`** (extra booléen `acc`).
2. Lance l'app `[Customer] SelfStartAppPackageName` si elle est définie.
3. Rétroéclairage allumé 800 ms plus tard (GPIO 5), si l'ACC est toujours présente.
4. Arrête les minuteries de veille, désactive le mode avion, active le GPS.
5. Déverrouille l'écran si `[AccOpenScreen]` le demande.
6. `setAccReadyState(true)` : mode LED de façade → MCU, antenne radio, requête frein à main.
7. Allume l'ampli externe si autorisé. **Si on sort d'un ACC long off** : démute, `wakeUp`, rallume BT, MCU
   (`CarCtrl.powerOn`), média et antenne, puis radio et puce audio après 2,1 s (tuner externe) et charge rapide.
   Sinon (coupure courte) : mute bref de 100 ms, et rallumage de la radio si elle était coupée.
8. Reprise de la lecture (`MediaCtrl.resume`), projection (SpeedPlay).

## 4.2 ACC coupée, puis « ACC long off »
- Coupure : broadcast `acc` = false, mute interne, rétroéclairage éteint, puis minuterie « ACC long off »
  (`getAccToShakeTime`, allongée juste après le démarrage par `getBootCompleteAccToShakeTime`). Si l'ACC
  revient avant, rien d'autre n'est coupé.
- **ACC long off** (`onAccLongOff`, déclenché par minuterie) :
  - retour à l'accueil si l'app de premier plan est dans `[MemoryToHome]` ;
  - `AudioCtrl.powerOff`, `MediaCtrl.powerOff` et `stop` ; mode avion activé ; GPS coupé ;
  - radio coupée, antenne mise à jour, **ampli externe coupé** (`44 00`), charge rapide coupée ;
  - fermeture des apps tierces et de l'AV, arrêt de CarPlay, HiCar, ZLink et WeLink ;
  - broadcast **`com.jancar.services.action.acc_long_off`** ;
  - `SmartAccOffTimer` (réglage « Smart ACC off ») : 0 → veille profonde (vide sur AC8257), 1 → éteindre,
    autre valeur n → `F1 [n/60/6]` (veille de n minutes, MCU) ;
  - envoie `01 [2, 0, 13]` (`CarCtrl.powerOff(2)`) — **ignoré par ce firmware MCU** (mcu_firmware.md §5) ;
  - après `getGotoSleepDelay()` : `SystemCtrl.gotoSleep()` (`PowerManager.goToSleep`) ; si « éteindre »,
    envoie aussi `F1 0`.
- La MCU coupe ensuite le SoC (15 s / 40 s) ou le maintient en suspension selon la durée `F1` (mcu_firmware.md §5).
- Reset du hub USB (GPIO 174) quand `setAccReadyState(false)`.

## 4.3 Écran [A]
- Rétroéclairage : GPIO 5 (`setBackLight`), refusé tant que l'ACC n'est pas « prête » (demande l'ACC à la MCU).
- Verrouillage écran (touche power) : `closeBackLight(action)` avec les actions `IVIKey.PowerAction` (éteindre,
  éteindre et mettre en pause, couper le son, etc.). Une marche arrière déverrouille temporairement.
- Luminosité : `ScreenBrightnessUtil`, valeurs jour/nuit commutées par les feux (`IVICar.HeadLight`),
  plage `[Brightness]`.
- Rotation : `ScreenRotationUtil` (`persist.dis.force_direct`, `jancar.roll.state`, commande MCU `45`).
- Économiseur d'écran : `[ScreenProtection]`.
- Reset usine : `recoverySystem(clearINand)` → `MASTER_CLEAR`.

## 4.4 Propriétés et réglages
| Clé | Usage |
|---|---|
| `Settings.Global accStatus` | état ACC |
| `service.has_enter_acc_long_off` | drapeau ACC long off |
| `sys.jancar.ccd` | marche arrière (AVM 360) |
| `autochips.intent.action.QB_POWEROFF` | extinction QuickBoot (autres plateformes) |
| `JACSettings.Global.KEY_EXTERNAL_AMP_SWITCH` | ampli externe autorisé |
