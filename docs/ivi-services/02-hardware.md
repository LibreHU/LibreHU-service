# 2. Couche matérielle (carte A0_AN, `Platform_AutoChips_8257_Base` / `_8257_37534`)

C'est ce que le nouveau service devra piloter directement. Sources : `platform/AC8257/*.java` [A] et
`libJanCarIVI.so` [N].

## 2.1 Identification de la carte [A]
- Board id lu dans `/sys/devices/virtual/mtk-adc-cali/mtk-adc-cali/jancar_board_id` (ex. `A0_AN`).
- `boardId[0..1]` (`A0`, `A1`) et `boardId[3]` → puce audio : `A` BD37534, `B` BU32107, `C` AK7604.
- `boardId[4]` → tuner : `T` → id 6, `W` → id 14, **autre (ici `N`) → -1 = détection automatique** (voir §2.6).
- `isRemove1821` : `AtcMetazone_.readval(65952)` == `0x5A5A5A51` → sens du frein à main inversé.

## 2.2 GPIO [A][N]
Accès par **`/dev/gpios_ioctl`** (module noyau Autochips/Jancar) : `ioctl(fd, cmd, n)` avec `cmd` =
`0x6B01` niveau haut, `0x6B00` niveau bas, `0x6B02` lecture, `0x6B03` passage en entrée. Le nom `GPIOnnn` vaut
simplement le numéro `nnn` (`GPIO::getAC8Index` = `atoi`).

| GPIO | Rôle | Sens / logique |
|---|---|---|
| 2 | **marche arrière (CCD)** | entrée, actif bas |
| 134 | ACC | entrée, actif bas (mais l'ACC est prise sur la MCU, voir §2.3) |
| 5 | rétroéclairage écran | sortie, 1 = allumé |
| 166 | mute ampli | sortie, 1 = muet |
| 167 / 165 | commutateur vidéo SW1 / SW2 | AV 1/11 → (0,0), 31 → (1,0), 32 → (1,1) |
| 7 / 6 | clignotant gauche / droit | entrée, actif bas |
| 110 | alimentation antenne radio | sortie (doublée par la commande MCU `43`) |
| 112 | alimentation / reset tuner | sortie, mis à 1 au démarrage |
| 174 | reset hub USB | impulsion 0 pendant 20 ms puis 1 (à la coupure ACC) |
| 164, 97 | inconnus | mis à 1 au démarrage |

## 2.3 Sources des états véhicule (`get*DetectMode`) [A]
0 = GPIO lu par `CarService`, 1 = trame MCU (protocole `JAC_V1`).

| État | Mode | Source |
|---|---|---|
| ACC | 1 | MCU `00` |
| Marche arrière | 0 | GPIO 2 (+ CAN via broadcast `action_backcar_notification`) |
| Frein à main | 1 | MCU `04` (+ requête `F0 04 00` à chaque ACC) |
| Feux | 1 | MCU `0B` |
| Clignotants | 0 | GPIO 7 / 6 |

## 2.4 Liaison MCU [A]
`/dev/ttyS1`, 115 200 bauds (`[Platform] McuSerialBaudRate`, défaut 115200), protocole id 2 = `JAC_V1`.
Détail : [03-mcu-car.md](03-mcu-car.md) et `MCU-tools-app/docs/mcu_firmware.md`.

## 2.5 I2C [N]
`/dev/i2c-0` à `/dev/i2c-6`. Puce audio **ROHM BD37534 sur `i2c-6`, adresse 0x40** ; tuner sur `i2c-6`
(`mRadioI2CBusIndex = 6`). Registres BD37534 : `MCU-tools-app/docs/ivi_audio.md` §7.

## 2.6 Tuner [A][N]
`RadioDevice.nativeCreate(id, bus 6, resetGpio 112, fmLevelOffset)`. Avec l'id **-1**,
`RadioDevice::getInstance` appelle **`NXPRadio::detect`** (détection d'un tuner NXP sur l'I2C), puis instancie
l'un des pilotes : TEF6638, TEF6686, TEF6692, TEF6856, TDA7786, TDA7708, TSC4745, SI475x, QN8027, CA9636,
MT6631 (ce dernier est vide dans la lib). L'id réel est publié dans **`jancar.radio.id`**. **À relever sur
l'appareil.**

## 2.7 Autres périphériques [A]
| Élément | Valeur |
|---|---|
| Tactile | Goodix gt9xx : `/sys/devices/platform/touch/gt9xx_props`, `/proc/gt9xx_config` |
| Touches | `/dev/input`, périphérique `mtk-tpd` ; télécommande IR `gpio_ir_recv` ; `/dev/ir_tv` |
| G-sensor | `DA228` (mir3da) sur I2C, calibration `persist.sensor.calib` |
| Décodeurs vidéo connus | TW9992, TP2805 (autres cartes) |
| Flux audio Android | navigation = stream 11, TTS = 9, assistant = 14 ; `persist.action.volume.percent` (40) |
| Caméras | HAL Autochips, `vendor/etc/atc_camera_config.xml` : USB id 0, MIPI sub id 2, CVBS id 3, MIPI VC id 8 |
| Recul rapide | géré avant Android ; état `persist.action.fastreverse.state` (défaut `true`) |

## 2.8 Commandes MCU envoyées par la couche plateforme [A]
| Fonction | Trame |
|---|---|
| `setRadioAntennaPower` | GPIO 110 + `43 [0/1]` |
| `setExternalAmpPower`, `setCPUFanPower` | `44 [0/1]` (même commande) |
| `setPowerOffTimerForAccOff(min)` | `F1 [min/60/6]` |
| `queryHandBrakeFromMcu` | `F0 04 00` |
| `PanelLightUtil` | `0F …` (LED de façade) |
| `ScreenRotationUtil` | `45 …` |
