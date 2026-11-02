# Fonctions Autochips (ATC)

Page **Avancé → Autochips** (`ui/AtcScreen.kt`), code `atc/AtcTools.kt` et `atc/AtcHelper.kt`. Reprend l'essentiel
d'ATC Settings (`com.android.settings/com.autochips.atcsettings.mainui.MainActivity`, voir
[android_kernel_autochips_ac8257_4.9/docs/ujc201/reglages_caches.md](https://github.com/LibreHU/android_kernel_autochips_ac8257_4.9/blob/reverse-engineering/docs/ujc201/reglages_caches.md)).

## Assistant root
Les classes Autochips sont dans `/system/framework/autochips.jar` (dans le `BOOTCLASSPATH`), mais la metazone
(`/dev/mtz`) et la partition logo ne sont pas accessibles depuis le domaine SELinux d'une appli. LibreHU lance donc
`su -c "CLASSPATH=<apk LibreHU> app_process /system/bin org.librehu.service.atc.AtcHelper <commande>"` :

| Commande | Appel |
|---|---|
| `get <adresse>…` | `AtcMetazone.readval(adresse, int[4])` |
| `set <adresse>=<valeur>…` | `AtcMetazone.writeval`, puis `AtcMetazone.flush(true)` |
| `arm2` | `new BackCar().updateArm2MdpSetting()` (« generate MDP ») |
| `arm2ready` | `getArm2Status(int[])`, puis si ≠ 2 `sendReadyToArm2()` et attente du statut 2 : `before=`, `ready=`, `after=` |
| `logo <fichier>` | `AtcLogo.writelogo(2, 0, taille, données)` |

Si `su -c` est refusé avec « invalid uid/gid », le `su` est le vieux `su` d'Android
([celui d'AOSP](https://android.googlesource.com/platform/system/extras/+/refs/heads/main/su/su.cpp) :
`su [UID [COMMANDE…]]`, sans option `-c`), comme `/system/xbin/su` sur l'autoradio de Reo Au In : `RootShell` relance
alors la commande en `su 0 sh -c "…"` et garde cette forme (correctif de Reo Au In, `3ae4fef`, versionCode 134).
La demande groupée de `RootRequestReceiver` passe aussi par `RootShell` depuis la versionCode 136 ; hors de `RootShell`,
seul `TouchBoost` lance `su`, sans argument (commandes sur l'entrée standard), ce que les deux syntaxes acceptent.
Les autres applis LibreHU de l'autoradio, sauf MCU Toolkit, font de même (`Su.kt` : `su -v` reconnaît ce `su`). Avec KernelSU Next (noyau
LibreHU), le noyau ne détourne `/system/bin/su` que pour les applis autorisées dans son gestionnaire (liste
Superuser, [`sucompat.c`](https://github.com/KernelSU-Next/KernelSU-Next/blob/legacy/kernel/feature/sucompat.c)) :
sans autorisation, `su` est cherché dans le `PATH` et c'est celui de la ROM qui répond.

## Passage de main de l'ARM2 au démarrage (hd2cam retiré : vidéo matérielle verte)
Rapport de Reo Au In (01/11/2026 ; UJC201, noyau LibreHU, ROM allégée : `com.jancar.services` retiré ; essais du
31/10).

**Symptôme** : sans `com.jancar.hd2cam` (l'appli caméra / DVR de Jancar), toute vidéo **décodée par le matériel**
(`OMX.MTK.VIDEO.DECODER.AVC` : Android Auto par Open Head Unit, VLC) est **verte unie**, alors que le décodeur annonce
24 à 31 images par seconde ; le décodage logiciel s'affiche bien.

**Cause** : au démarrage, hd2cam appelle `com.autochips.backcar.BackCar.sendReadyToArm2()`. Le pilote `backcar` du
noyau (`drivers/misc/autochips/backcar/backcar.c`, `IOCTL_FSC_NOTIFY_APP_READY`) envoie alors `MSG_ANDROID_APP_READY`
à l'ARM2, qui rend l'affichage à Android : statut ARM2 2, propriété `runtime.arm2.finish=y`. Sans cet appel, l'ARM2
reste en place (statut 0) et le pilote d'affichage lui garde les couches 1 et 2 du mélangeur matériel OVL0 :
`dispsys/ddp_ovl.c` (« arm2 not exist, so don't operate ovl0 layer 1,2 ») n'active que les couches 0 et 3, et
`ovl_layer_num` en annonce 1 au lieu de 4 tant que `get_arm2_backcar_status()` vaut 0. La vidéo matérielle, placée
par le compositeur sur l'une de ces couches, ne sort jamais : vert (image YUV à zéro).

Constaté sur le même micrologiciel, seul hd2cam changeant :

| Situation | Statut ARM2 | `runtime.arm2.finish` | Vidéo |
|---|---|---|---|
| hd2cam installé | 2 | `y` | normale |
| hd2cam retiré (TWRP) | 0 | absente | verte |
| même démarrage, un appel `sendReadyToArm2()` (root, `app_process`) | 0 → 2 | `y` | normale aussitôt, sans redémarrer |
| appli de test au démarrage (`BOOT_COMPLETED`, UID système) | 0 → 2 en 1 s environ | `y` | normale |

**Correctif (LibreHU service versionCode 134 et plus : `cbd9d12`, puis `3ae4fef` de Reo Au In)** : la commande
`arm2ready` de l'assistant root lit `getArm2Status(int[])` ; si le statut n'est pas 2, elle envoie `sendReadyToArm2()`
une fois puis attend le statut 2 (20 s). `atc/Arm2Handover.kt` la lance **au démarrage du service et après une vraie veille** : rien si l'ARM2 a déjà
rendu la main (hd2cam encore installé), **pas pendant une marche arrière** (l'ARM2 montre alors la caméra ; les caméras
d'Android n'arrivent qu'après sa sortie), 3 essais à 10 s d'intervalle, chaque étape dans le journal du service
(« ARM2: handed over to Android (status 0 -> 2) »). Les versionCode 130 à 133 ne rendaient pas la main : quel que
soit le `su`, `NullPointerException` (`Looper.mQueue`) dans le constructeur de `BackCar`, qui crée un `Handler` alors
qu'`app_process` ne prépare pas de `Looper`, et sur l'autoradio de Reo Au In, `su -c` refusé par le `su` de la ROM
(voir [Assistant root](#assistant-root)). L'assistant appelle maintenant `Looper.prepare()` avant de créer `BackCar`
(`arm2ready` et `arm2`). Correctifs et essai sur l'autoradio : Reo Au In (« ARM2: handed over to Android
(status 0 -> 2): hardware video and cameras free », `runtime.arm2.finish=y`). **hd2cam peut donc être retiré**, avec
le root ; sans root, le garder. Une mise en veille libère probablement aussi l'ARM2 (`backcar_suspend` force son arrêt puis envoie
`APP_READY`) : non vérifié.

## Adresses metazone utilisées
| Adresse | Valeur |
|---|---|
| 65550 | second écran LVDS d'Autochips (0 / 1), voir plus bas : **pas VIDEO OUT** |
| 65588 / 65600 | miroir du recul rapide CVBS / AHD (1 gauche-droite + 2 haut-bas) |
| 65594..65597 / 65601..65604 | recadrage x, y, largeur, hauteur (CVBS / AHD) |
| 65598 / 65599 | netteté / tramage (0 / 1) |
| 65605 | mode caméra : 0 auto, 1 NTSC, 2 PAL, 3 720p25, 4 720p30, 5 1080p25, 6 1080p30 (ATC Settings ne propose que 4 et 6) |
| 65606 | caméra 360 : 0 auto, 1 NTSC, 2 PAL, 3 720p25, 4 720p30, 5 1080p25, 6 1080p30 |
| 65607 | source du recul : 0 débogage, 1 CVBS, 2 MIPI, 3 360 / AVM (**l'AHD de l'UJC201 passe par 3**, comme l'écrit Jancar) |
| 66018 | valeur capteur de Jancar : 0 AHD 720p25, 1 1080p25, 2 720p30, 3 1080p30 |
| 65608 / 65611 | 360 rapide / 360 sur CVBS |
| 65616 | port USB 0 : 2 hôte, 1 périphérique |

## Second écran LVDS (65550, `METAZONE_OPEN_EXT_DISP_INDEX` = 0x1000E)
Lu au démarrage par le chargeur (`/proc/pl_lk` : `g_open_ext_disp:1`) et par le noyau (`lcm_driver_common.c`
`lcm_get_matezone`, `primary_display.c`). Sans second écran câblé (`ext(0, 0)` dans `pl_lk`), il donne des bandes
à l'écran dès le logo (29/10, interrupteur pris pour VIDEO OUT ; disparues une fois remis à 0 puis redémarré).
Dans la ROM stock (dump `jancar` V1.151R6), seul ATC Settings l'écrit (`AtcDisplaySettingsUtils.setRearDisplay`,
sur un geste, interrupteur caché sauf `hwc.external_display_enable`) ; VIDEO OUT (`com.jancar.usbdisplay`) ne le
lit ni ne l'écrit, et `RearDisplay` d'`ivi-settings.ini` est le réglage de VIDEO OUT (`key_rear_display`), pas
celui-ci. Le service demande confirmation avant de l'activer et, 30 s après le démarrage, écrit dans le journal de
veille s'il est à 1 alors que le chargeur ne trouve pas de second écran. Remise à 0 : l'interrupteur, ou depuis un
PC (PowerShell : apostrophes extérieures simples), puis couper le contact 1 min ou redémarrer :

```
adb shell 'su -c "CLASSPATH=$(pm path org.librehu.service | cut -d: -f2) app_process /system/bin org.librehu.service.atc.AtcHelper get 65550"'
adb shell 'su -c "CLASSPATH=$(pm path org.librehu.service | cut -d: -f2) app_process /system/bin org.librehu.service.atc.AtcHelper set 65550=0"'
adb shell "su -c 'cat /proc/pl_lk'" | grep -E "g_open_ext_disp|multidisp"
```

`set` répond `65550=0` puis `ok` ; après le redémarrage, `pl_lk` doit dire `g_open_ext_disp:0`.

## Format de la caméra
Préréglages repris de Jancar (`ImportSingleConfigUtil.importBackCarVideoSource` d'ivi-settings, index =
`BackCarVideoSource` de `ivi-customer.ini`, `1` = AHD 720p 25 i/s sur l'UJC201 d'origine) :

| Format | 65607 | 66018 | 65605 et 65606 | recadrage AHD 65601..65604 |
|---|---|---|---|---|
| CVBS | 1 | — | — | — |
| AHD 720p 25 i/s | 3 | 0 | 3 | 12, 0, 1268, 720 |
| AHD 720p 30 i/s | 3 | 2 | 4 | 12, 0, 1268, 720 |
| AHD 1080p 25 i/s | 3 | 1 | 5 | 0, 0, 0, 0 |
| AHD 1080p 30 i/s | 3 | 3 | 6 | 0, 0, 0, 0 |

Puis `flush` et **redémarrage** (Jancar redémarre aussi) : le format est lu au démarrage (chargeur, ARM2, pilote
caméra), il vaut pour le recul rapide comme pour la vue Camera2 de LibreHU. Les valeurs restent dans la metazone :
sans Jancar, le dernier format réglé reste actif. Aucun mode 60 i/s dans les réglages de Jancar ni d'Autochips. La
vue LibreHU affiche les images par seconde réellement reçues (réglage « Angle du volant et caméra sur l'image »).

## USB
Le rôle du port OTG est changé tout de suite par le fichier `data_role` de la classe noyau `dual_role_usb`
(`/sys/class/dual_role_usb/dual-role-usb20/data_role`, « host » / « device »), ce qu'écrivent les réglages Jancar
(`GlobalPresenter.setADBEnable` d'ivi-settings ; `/sys/devices/platform/mt_usb/musb-hdrc.0.auto/dual_role_usb/otg/data_role`
sur AC8227L). `dumpsys usb set-port-roles` ne fait rien ici : aucun port n'est déclaré au framework par une HAL USB.
La metazone 65616 est aussi écrite (2 hôte, 1 périphérique) pour le démarrage suivant, comme ATC Settings. En
périphérique, `adb_enabled` passe à 1. L'état affiché est celui du fichier `data_role`.

**Transfert de fichiers avec un ordinateur (MTP)**, réglage du même écran, **désactivé par défaut** (`MtpMode.kt`) :
- désactivé : MTP est retiré des fonctions USB, les courantes (`svc usb setFunctions`) et celles qu'Android applique à
  chaque déverrouillage d'écran (`svc usb setScreenUnlockedFunctions`, gardées par UsbDeviceManager d'un démarrage à
  l'autre) ; le port en périphérique ne propose qu'adb et la recharge, aucun serveur MTP ne tourne ;
- activé : MTP dans ces deux réglages, et le serveur MTP de MediaProvider (`MtpService`) démarré en root à chaque
  branchement. Sous Android 9, c'est le `MtpReceiver` de MediaProvider qui le démarre quand l'USB passe en connecté +
  configuré avec MTP ; la ROM Jancar livre MediaProvider sans ce récepteur, le serveur ne tournait donc jamais :
  Windows chargeait son pilote MTP, n'avait pas de réponse et abandonnait au bout de 30 s (`MTP_REQ_CANCEL` dans le
  journal du noyau). Le serveur s'arrête tout seul au débranchement (erreur de lecture sur `/dev/mtp_usb`), d'où un
  démarrage à chaque branchement ; rien n'est démarré sur une ROM qui a son propre récepteur.
- Windows qui affichait « Code 28 » (pilote absent, interface vue en classe `ff`) : il avait gardé en cache qu'à un
  premier branchement l'autoradio n'avait pas répondu à la requête des descripteurs Microsoft (`0xEE`). Effacer
  `HKLM\SYSTEM\CurrentControlSet\Control\usbflags\2BE182570223` (valeur `osvc` à `0000`) en administrateur, désinstaller
  le périphérique, rebrancher.

## Outils
Engineer Mode (`com.mediatek.engineermode/.EngineerMode`), ATC Settings
(`com.android.settings/com.autochips.atcsettings.mainui.MainActivity`), ATCLogger (`com.autochips.atclogger/.MainActivity`)
et **YGPS** (`com.mediatek.ygps/.YgpsActivity`, test GPS MediaTek : satellites, signal, démarrages à froid / à chaud,
journal NMEA). Ouverts en root (`am start`) s'ils ne sont pas exportés.

## Logo de démarrage
L'image est ajustée à la taille réelle de l'écran (fond noir), convertie en BGRA, ligne du haut en premier, alpha
0xFF : le format qu'ATC Settings produit depuis un BMP. **Avant toute écriture**, la partition `logo` est copiée
(`dd`) dans `/sdcard/LibreHU/logo-<date>.img` ; sans copie, rien n'est écrit. La partition contient aussi la
description de démarrage de la dalle (`logo.mrf`) : seule l'API d'Autochips l'écrit. Restauration :
`dd if=/sdcard/LibreHU/logo-<date>.img of=/dev/block/by-name/logo` (root) ou `fastboot flash logo logo-<date>.img`.

## Limites
Non testé sur l'unité au moment de l'écriture (signatures lues dans le code décompilé de MtkSettings et ivi-settings).
