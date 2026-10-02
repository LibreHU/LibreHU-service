# 10. Configuration, réglages, interfaces système

## 10.1 Fichiers lus [A][V]
| Fichier | Contenu |
|---|---|
| `/jancar/config/ivi-config.ini` | configuration principale ; sections utilisées : `Audio`, `Audio_A0_AN` (pré-volumes de cette carte), `AudioSettings`, `AslSpeedDelta`, `Brightness`, `CcdVolume`, `DateTime`, `HandBrakeLimit`, `KeyAntiShake`, `MemoryToHome`, `ModeControl`, `Platform` (`BoardId`, `McuSerialBaudRate`), `Power` (seuils de tension), `Radio`, `RemoteControl`, `ScreenProtection`, `SystemUI`, `Volume`, `McuUpdateDate`, `ANS_PAMUTE`… |
| `/jancar/config/ivi-customer.ini` | options client (`PanelConfigID`, `SelfStartAppPackageName`, `BackCarFunction`, volumes de sécurité…) |
| `/jancar/config/ivi-settings.ini` | valeurs par défaut des réglages |
| `/jancar/config/ivi-studykey.ini` | touches volant apprises |
| `/jancar/config/ivi-radio-cfg.ini` | zones et bandes radio |
| `/jancar/config/ivi-launcher.json`, `appthemes.xml` | lanceur et thèmes |
| `/jancar/config/touch_key.xml`, `pointercal.xml` | touches tactiles et calibration |
| `/jancar/mcu/jacmcu.bin` | firmware MCU reflashé automatiquement |
| `/data/rr_data/ExpertAudioEffect` | effets audio « expert » |

Des copies d'origine existent dans `/system/etc/` (`ivi-config.ini`, `ivi-customer.ini`, `ivi-settings.ini`,
`jac_config.json`), recopiées dans `/jancar/config/` par le script `jaccopyfile` (`init.jancar.rc`) [V].

## 10.2 Réglages utilisateur [A]
- Fournisseur **`content://com.jancar.settings.provider/settings`** (app `com.jancar.settings`), lu et écrit par
  `SettingModel` (clés `Global` : `CCDCamera`, `CCDCameraLine`, `CCDMirror`, `Brake`, `FloatMenu`, réglage
  « Smart ACC off »…), avec écoute des changements.
- `Settings.Global` : `accStatus`, `global_mcudatadebug` (journal des trames MCU), `KEY_EXTERNAL_AMP_SWITCH`…

Schéma relevé sur l'unité [V] : table `settings` (`_id`, `name`, `value`), noms préfixés `global_` ou `network_`.
Exemples : `global_brake = DISABLE`, `global_ccdcamera = true`, `global_backlight = 70`,
`global_smartaccoff = 2880` (minutes), `global_deepsleep = ENABLE`, `global_ledlight = 50331648`,
`global_defaultnavipackage`, `global_mcudatadebug = false`, `global_password = 1234`.

## 10.3 Propriétés système [A]
| Propriété | Rôle |
|---|---|
| `persist.sys.jancar.deviceid`, `jancar.device.id` | identifiant appareil / board id |
| `jancar.radio.id` | tuner réellement détecté |
| `persist.action.volume.percent` | niveau fixe du flux média Android (%) |
| `persist.action.fastreverse.state` | recul rapide actif |
| `sys.jancar.ccd` | marche arrière (AVM) |
| `jancar.audio.channel.state`, `jancar.audio.allstop.state`, `jancar.audio.btcallstate` | état audio pour les autres apps |
| `persist.jancar.canversion` | version du boîtier CAN |
| `persist.dis.force_direct`, `jancar.roll.state`, `persist.sf.hwrotation` | rotation |
| `vendor.iapd.start` | démon CarPlay MFi |
| `service.has_enter_acc_long_off` | ACC long off |

## 10.4 Broadcasts émis ou écoutés [A]
| Action | Sens | Sens métier |
|---|---|---|
| `com.jancar.services.action.acc` (extra `acc`) | émis | ACC présente / coupée |
| `com.jancar.services.action.acc_long_off` | émis | coupure longue, avant la veille |
| `android.backcar.action.PREPARE_START` / `STARTED` / `FINISH` | reçus | caméra de recul |
| `action_backcar_notification` (extra `backcar`) | reçu | marche arrière via CAN (canservice) |
| `com.jancar.services.action.canbus_show_screen` / `canbus_hide_screen` | reçus | écran piloté par le CAN |
| `com.jancar.carplay_connected` / `carplay_disconnect` | reçus | CarPlay |
| `autochips.intent.action.QB_POWEROFF` | émis | extinction QuickBoot (autres plateformes) |
