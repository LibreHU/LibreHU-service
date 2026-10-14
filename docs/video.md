# Entrées CVBS, sortie vidéo et sortie audio

`car/CvbsInput.kt` (commutateur, réglages, sortie vidéo), `car/CvbsActivity.kt` (écran plein écran « CVBS IN »),
réglages dans **Véhicule → Vidéo** (`ui/VideoScreen.kt`).

Le faisceau RCA de l'autoradio a **CVBS IN 1**, **CVBS IN 2** (vidéo seule, pas de son), **VIDEO OUT** et
**AUDIO OUT**. Ce qui suit vient de la ROM Jancar d'origine (ivi-services, `com.jancar.avin`, ivi-settings,
ATC Settings d'Autochips) ; rien n'a encore été vérifié sur l'autoradio.

## Entrées CVBS : un seul décodeur, un commutateur
- Un seul décodeur CVBS, vu par Android comme la **caméra « 3 »** (`[CameraIndex]` d'`ivi-config.ini` : `AV=3`,
  `REAR=3`).
- Devant lui, un **commutateur vidéo analogique** piloté par deux GPIO du SoC
  (`Platform_AutoChips_8257_Base.platformSelectInput`, GPIO 167 = SW1, GPIO 165 = SW2) :

| SW1 / SW2 | Usage chez Jancar (`IVIAVIn.Id`) |
|---|---|
| 0 / 0 | AV (1) et TV (11) : l'entrée AV de l'appli `com.jancar.avin` |
| 1 / 0 | caméra avant (FRONT, 31) |
| 1 / 1 | caméra arrière CVBS (REAR, 32) |
| 0 / 1 | jamais utilisée par Jancar |

- L'appli AV de Jancar appelle `AVInManager.setAndroidCameraOpenPrepared(1)` (ivi-services → `selectInput` → les deux
  GPIO), puis ouvre la caméra 3. Si la caméra de recul est CVBS (`BackCarVideoSource=0`), elle arrête son aperçu en
  marche arrière, met le commutateur sur REAR (`setAndroidCameraOpenPrepared(32)`) et reprend ensuite : le recul et
  l'entrée AV partagent le même décodeur.
- Le son : `IVIAVIn.Id.getAudioChannel(AV) = 25`, entrée 0 du BD37534 (la même que l'AUX). Les RCA CVBS IN de cet
  autoradio n'ont pas de son : rien n'est commuté côté audio.
- Quelle RCA est sur quelle position n'est écrit nulle part dans la ROM. Par défaut LibreHU suppose **IN 1 = AV (0/0)**
  et **IN 2 = caméra avant (1/0)** ; le bouton **Tester les positions** montre les quatre positions en direct pour le
  vérifier, puis on choisit la position de chaque entrée.

## Écran CVBS IN
- Entrée **CVBS IN** du tiroir d'applis (icône thémée par LibreHU Launcher), ou boutons de la page Vidéo.
- Pastilles CVBS IN 1 / CVBS IN 2 (ou les quatre positions en mode test), plein écran / 4:3, réglages, fermer.
- Taille d'image : 720×576 (PAL) ou la plus proche ; Jancar choisit 720×480 / 720×576 selon le type CVBS détecté.
- **Seulement frein à main serré** (par défaut, comme `isMediaVideoPermit` de Jancar) ; ignoré si le lien MCU est
  coupé (frein inconnu).
- **Marche arrière** : la caméra est fermée tout de suite pour la vue de recul, puis rouverte sur l'entrée choisie.
- En quittant l'écran, le commutateur revient sur la position de la caméra arrière (le widget de recul la retrouve).

## Caméra de recul CVBS
Réglage **Commuter sur l'entrée de la caméra arrière avant la vue de recul** (par défaut : oui, position 1/1) : avant
d'ouvrir la caméra 3, la vue de recul et le widget en direct mettent le commutateur sur cette position, comme Jancar.
Sans ivi-services, rien d'autre ne le faisait. Le widget ne commute pas pendant que l'écran CVBS IN est ouvert.

## VIDEO OUT
- **Activation** : « Écran arrière » d'ATC Settings (`AtcDisplaySettingsUtils.setRearDisplay`, metazone **65550**),
  lu au démarrage ; ATC Settings affiche « redémarrage nécessaire ». Le compositeur (`hwcomposer.ac8257.so`) gère un
  écran externe en miroir et publie `hwc.external_display_enable` / `hwc.external_display_open` (affichés sur la page).
- **Réglages de Jancar** (ivi-settings, `RearOutputActivity`, « Rear output settings ») : format `hwc.rear_disp_fmt`
  (`P` PAL / `N` NTSC), bords `hwc.rear_disp_lb_y_top`, `_y_bottom`, `_x_left`, `_x_right` (0 à 50), gardés dans
  `/jancar/config/jac_rear_output.json` ; puis `Settings.Global key_rear_display` et le service
  `com.jancar.usbdisplay/.service.CaptureService` (action `change_rear_display`, extra `change`), arrêté puis relancé.
- LibreHU fait la même chose en root (**Appliquer maintenant**, et **Appliquer à chaque démarrage** car les propriétés
  `hwc.*` ne survivent pas à un redémarrage). L'APK `ivi-usbdisplay` n'a pas pu être décompilé (absent de la collecte) :
  qui lit `hwc.rear_disp_*` n'est pas vérifié (pas le compositeur, dont les chaînes ne les citent pas).

## AUDIO OUT
Sortie ligne du BD37534 (même son que les haut-parleurs, après le volume). Aucun réglage propre dans la ROM Jancar
(seulement « Sortie audio numérique », SPDIF, sans rapport). Pour savoir si elle porte l'arrière ou le caisson : musique,
puis fader avant / arrière ou sortie caisson activée / coupée (Audio, ou l'écran Audio véhicule de ViPER4Android).

## À vérifier sur l'autoradio
- position de chaque RCA (bouton Tester) et image sur la position 0/1 ;
- que l'écriture des GPIO 167 / 165 est acceptée par `/dev/gpios_ioctl` (message « écriture GPIO refusée » sinon) ;
- VIDEO OUT : image sur un écran branché après « Écran arrière » + redémarrage, effet du format et des bords.
