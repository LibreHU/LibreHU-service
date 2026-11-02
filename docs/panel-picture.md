# Image de la dalle (remplace `com.jancar.tftdisplay`)

Analyse statique de l'APK `ivi-tftdisplay` d'un UJC201 (`com.jancar.tftdisplay` 1.0 du 08/05/2024, UID système, une
activité cachée, ni service ni lancement au démarrage) : manifeste et code Dalvik lus, rien n'a été exécuté.

## Ce que fait l'appli de Jancar sur l'AC8257
`TFTDisplayManager` prend `Platform8257TFTDisplay` quand `ro.mediatek.platform` contient `8257` :

| Réglage | Appel |
|---|---|
| luminosité de l'image, contraste, teinte, saturation, netteté | `Filter.nativeSet…AdjIndex(i)`, `nativeGet…AdjIndex()`, `nativeGet…AdjRange()` |
| tons peau, herbe, ciel (teinte H, saturation S) | `Filter.nativeSetSkinToneH/SIndex`, `GrassTone…`, `SkyTone…` (mêmes lectures) |
| AVDD | MCU : `CarManager.sendPassthroughData(0x0F, [2, v])`, trame `0F 02 v` (0..100) |
| VCOM | MCU : trame `0F 03 v` (0..100) |
| rétroéclairage | `SystemManager.setScreenBrightness` de Jancar |

`Filter` (`com.mediatek.galleryfeature.pq.filter`) charge la `libPQjni.so` de la ROM, la bibliothèque du moteur de
qualité d'image (PQ) de la galerie MediaTek ; MiraVision passe par le même moteur. L'appli garde son choix dans
`/jancar/config/displayparams.json` (préréglages `type1`, `type2`… et celui choisi), AVDD / VCOM dans
`/jancar/config/screen_voltage.json`, et `luminosité,contraste,saturation` dans la propriété `persist.sys.rgb.normal`.

## Ce que fait LibreHU service
- **Affichage → Image de la dalle** : un curseur par réglage, sur la plage donnée par le moteur (index 0 à plage − 1) ;
  clés `brightness`, `contrast`, `hue`, `saturation`, `sharpness`, `skin_h`, `skin_s`, `grass_h`, `grass_s`, `sky_h`,
  `sky_s` (`display/PanelPicture.kt`).
- Réglé par l'assistant root : `AtcHelper pq [clé=index]…` sous `app_process`, puis sous `app_process32` si la ROM n'a
  la bibliothèque qu'en 32 bits (retenu ensuite). La classe `Filter` du service
  (`app/src/main/java/com/mediatek/galleryfeature/pq/filter/Filter.java`) se lie seulement à `libPQjni.so` : même nom
  de classe et mêmes 39 méthodes natives (`RegisterNatives` échoue s'il en manque une), aucun code MediaTek.
- Les valeurs changées ici sont remises 15 s après le démarrage du service et après une vraie veille ; celles lues la
  première fois sont gardées (**Valeurs d'origine** y revient et arrête la remise au démarrage).
- Bouton **MiraVision** quand la ROM l'a (`com.mediatek.miravision.ui`).

Lecture à la main (root ; vieux `su` d'AOSP : `su 0 sh -c` au lieu de `su -c`) :

```sh
adb shell 'su -c "CLASSPATH=$(pm path org.librehu.service | cut -d: -f2) app_process /system/bin org.librehu.service.atc.AtcHelper pq"'
```

Réponse : `brightness=<index>/<plage>`, … ; `pq contrast=5` règle d'abord (`set.contrast=true`).

## Pas repris : AVDD et VCOM
Dans le firmware MCU de cette carte, `0F 02` / `0F 03` sont les deux sorties PWM de TIM1 (PA8, PB14 ; 5 à 100 %,
60 et 50 par défaut, gardées dans la flash du MCU et non relisibles : `LibreHU/MCU-tools-app`, `docs/mcu_firmware.md`
§6.4) que le service règle déjà comme **rétroéclairage blanc des touches** (`car/FrontLed.kt`). L'appli de Jancar,
commune à plusieurs cartes, y envoie les tensions de la dalle. Avant d'y toucher, vérifier sur l'autoradio ce que ces
deux sorties alimentent.

## À vérifier sur l'autoradio
- `libPQjni.so` chargée par l'assistant root (sinon l'écran affiche l'erreur et propose MiraVision s'il est là) ;
- la plage de chaque réglage et le sens de l'index (plage = nombre de crans supposé) ;
- si le moteur garde lui-même les réglages au redémarrage (le service les remet de toute façon).
