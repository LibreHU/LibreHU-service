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
L'APK `ivi-usbdisplay` (`com.jancar.usbdisplay`, fourni depuis l'autoradio) montre que VIDEO OUT n'est **pas** une
sortie vidéo du SoC : c'est une **puce d'affichage USB MacroSilicon** (sortie CVBS) alimentée par une capture d'écran.
- Puces reconnues (`res/xml/device_filter.xml`, `USBVendorId`) : USB `534d:6021` et `345f:9132` (sortie CVBS,
  S-Vidéo ou HDMI selon la puce ; CVBS : `720x480P_60HZ` « NTSC » ou `720x576P_50HZ` « PAL »).
- Activation : `Settings.Global key_rear_display = 1`. Le `MainService` de l'appli (persistante) lance alors
  `CaptureService` à chaque démarrage, et son `UsbReceiver` le fait quand la puce apparaît.
- `CaptureService` attend la sortie de l'ARM2 (`BackCar.getArm2Status() == 2`), cherche la puce (USBMonitor), ouvre
  `MediaProjectionActivity` (capture d'écran, accordée d'office à cette appli système qui a `CAPTURE_VIDEO_OUTPUT`),
  puis envoie les images en USB bulk. `change_rear_display` (extra `change`) démarre ou arrête l'envoi ;
  `runtime.reardisplay.status` vaut 54 pendant l'envoi, 55 à l'arrêt.
- Format, taille, décalages et mise à l'échelle : son écran `SettingActivity` (exporté), gardés dans
  `/jancar/config/usb_display.json`.
- Ni l'« écran arrière » d'Autochips (metazone 65550), ni `hwc.rear_disp_*` (ivi-settings) ne sont lus par cette
  appli : la première version de la page Vidéo, qui les réglait, n'avait donc aucun effet.

Page Vidéo, carte VIDEO OUT : présence de l'appli Jancar, puce vue ou non par Android (liste des périphériques USB),
état de l'envoi, interrupteur `key_rear_display` (root) + lancement du service de capture, boutons **Réglages de la
sortie** (l'écran de Jancar), **Rechercher la puce** (`com.jancar.usbdisplay.USB_REFRESH`) et **Actualiser**. Si la
puce n'apparaît pas : le port USB doit être en mode hôte (Avancé → Autochips), et un écran branché sur VIDEO OUT.

## AUDIO OUT
Sortie ligne du BD37534 (même son que les haut-parleurs, après le volume). Aucun réglage propre dans la ROM Jancar
(seulement « Sortie audio numérique », SPDIF, sans rapport). Pour savoir si elle porte l'arrière ou le caisson : musique,
puis fader avant / arrière ou sortie caisson activée / coupée (Audio, ou l'écran Audio véhicule de ViPER4Android).

## À vérifier sur l'autoradio
- position de chaque RCA (bouton Tester) et image sur la position 0/1 ;
- que l'écriture des GPIO 167 / 165 est acceptée par `/dev/gpios_ioctl` (message « écriture GPIO refusée » sinon) ;
- VIDEO OUT : puce USB vue (`534d:6021` / `345f:9132`), image sur un écran branché après l'interrupteur.
