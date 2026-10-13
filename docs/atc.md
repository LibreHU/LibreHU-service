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
| 65605 | mode caméra : 0 auto, 1 NTSC, 2 PAL, 3 720p25, 4 720p30, 5 1080p25, 6 1080p30 (ATC Settings ne propose que 4 et 6) |
| 65606 | caméra 360 : 0 auto, 1 NTSC, 2 PAL, 3 720p25, 4 720p30, 5 1080p25, 6 1080p30 |
| 65607 | source du recul : 0 débogage, 1 CVBS, 2 MIPI, 3 360 / AVM (**l'AHD de l'UJC201 passe par 3**, comme l'écrit Jancar) |
| 66018 | valeur capteur de Jancar : 0 AHD 720p25, 1 1080p25, 2 720p30, 3 1080p30 |
| 65608 / 65611 | 360 rapide / 360 sur CVBS |
| 65616 | port USB 0 : 2 hôte, 1 périphérique |

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
