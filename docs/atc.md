# Fonctions Autochips (ATC)

Page **Avancé → Autochips** (`ui/AtcScreen.kt`), code `atc/AtcTools.kt` et `atc/AtcHelper.kt`. Reprend l'essentiel
d'ATC Settings (`com.android.settings/com.autochips.atcsettings.mainui.MainActivity`, voir
[android_device_alps_ac8257_demo/docs/reglages_caches.md](https://github.com/Dok-T/android_device_alps_ac8257_demo/blob/ccr-eca2d7b0-vz26un/docs/reglages_caches.md)).

## Assistant root
Les classes Autochips sont dans `/system/framework/autochips.jar` (dans le `BOOTCLASSPATH`), mais la metazone
(`/dev/mtz`) et la partition logo ne sont pas accessibles depuis le domaine SELinux d'une appli. LibreHU lance donc
`su -c "CLASSPATH=<apk LibreHU> app_process /system/bin org.librehu.service.atc.AtcHelper <commande>"` :

| Commande | Appel |
|---|---|
| `get <adresse>…` | `AtcMetazone.readval(adresse, int[4])` |
| `set <adresse>=<valeur>…` | `AtcMetazone.writeval`, puis `AtcMetazone.flush(true)` |
| `arm2` | `new BackCar().updateArm2MdpSetting()` (« generate MDP ») |
| `logo <fichier>` | `AtcLogo.writelogo(2, 0, taille, données)` |

## Adresses metazone utilisées
| Adresse | Valeur |
|---|---|
| 65550 | écran arrière (0 / 1) |
| 65588 / 65600 | miroir du recul rapide CVBS / AHD (1 gauche-droite + 2 haut-bas) |
| 65594..65597 / 65601..65604 | recadrage x, y, largeur, hauteur (CVBS / AHD) |
| 65598 / 65599 | netteté / tramage (0 / 1) |
| 65605 | caméra AHD : 4 = 720p, 6 = 1080p |
| 65606 | caméra 360 : 0 auto, 1 NTSC, 2 PAL, 3 720p25, 4 720p30, 5 1080p25, 6 1080p30 |
| 65607 | source du recul : 0 débogage, 1 CVBS, 2 MIPI (AHD), 3 360 |
| 65608 / 65611 | 360 rapide / 360 sur CVBS |
| 65616 | port USB 0 : 2 hôte, 1 périphérique |

## USB
Comme ATC Settings : écriture de 65616 puis rôle du port avec `dumpsys usb set-port-roles <id> source host|device`
(commande d'AOSP Android 9, ids lus dans `dumpsys usb ports`). En périphérique, `adb_enabled` passe à 1. Si le port
n'a pas de bascule de rôle déclarée au framework, la valeur de la metazone s'applique au prochain démarrage.

## Logo de démarrage
L'image est ajustée à la taille réelle de l'écran (fond noir), convertie en BGRA, ligne du haut en premier, alpha
0xFF : le format qu'ATC Settings produit depuis un BMP. **Avant toute écriture**, la partition `logo` est copiée
(`dd`) dans `/sdcard/LibreHU/logo-<date>.img` ; sans copie, rien n'est écrit. La partition contient aussi la
description de démarrage de la dalle (`logo.mrf`) : seule l'API d'Autochips l'écrit. Restauration :
`dd if=/sdcard/LibreHU/logo-<date>.img of=/dev/block/by-name/logo` (root) ou `fastboot flash logo logo-<date>.img`.

## Limites
Non testé sur l'unité au moment de l'écriture (signatures lues dans le code décompilé de MtkSettings et ivi-settings).
