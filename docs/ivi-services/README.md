# Analyse d'ivi-services (Jancar, AC8257)

Spécification de ce que fait `com.jancar.services`, pour le remplacer par LibreHU-service.
Version analysée : 3.0.0.ac8257.6a605cd0.20250611, UJC201, board id `A0_AN` (puce audio ROHM BD37534).

| Doc | Sujet |
|---|---|
| [01-architecture.md](01-architecture.md) | composants, bus d'événements, démarrage, dépendances |
| [02-hardware.md](02-hardware.md) | GPIO, I2C, MCU, tuner, caméras : ce que pilote la couche plateforme |
| [03-mcu-car.md](03-mcu-car.md) | protocole MCU `JAC_V1`, `CarService`, CAN brut |
| [04-power-acc.md](04-power-acc.md) | ACC, ACC long off, veille, écran |
| [05-audio.md](05-audio.md) | chaîne audio, sources, focus, mute, volume |
| [06-radio.md](06-radio.md) | tuner, RDS |
| [07-avin-camera.md](07-avin-camera.md) | marche arrière, caméras, entrées AV |
| [08-system-keys.md](08-system-keys.md) | système, touches, tactile, **accès I2C brut** |
| [09-other-modules.md](09-other-modules.md) | média, voix, navigation, combiné, DAB, BT, projection |
| [10-config.md](10-config.md) | fichiers de configuration, réglages, propriétés, broadcasts |
| [11-rewrite-plan.md](11-rewrite-plan.md) | périmètre et architecture de LibreHU-service |
| [api.md](api.md) | les 30 interfaces AIDL (≈ 470 méthodes) avec leurs numéros de transaction |

Compléments dans `LibreHU/MCU-tools-app/docs` : `mcu_firmware.md` (firmware MCU et protocole) et
`ivi_audio.md` (puce audio, `IAudio`, registres BD37534).

## Méthode et limites
- Analyse statique : jadx (Java) et `llvm-objdump` (`libJanCarIVI.so`, symboles C++ présents). Rien n'a été
  exécuté sur l'appareil.
- Niveaux : **[A]** lu dans l'APK, **[N]** lu dans la lib native, **[V]** vu dans le dump, **[D]** déduit.
- La datasheet officielle du BD37534 n'a pas pu être consultée (bloquée par le proxy de la session d'analyse).

## Ce qu'il manque
**APK** (`adb shell pm path <paquet>` puis `adb pull`) :
- `com.jancar.canservice` — décodage du CAN (portes, clim, radar, touches CAN) ;
- `com.jancar.settings` — fournisseur de réglages `com.jancar.settings.provider` et ses clés ;
- `com.jancar.steeringwheelkeys` — format de `ivi-studykey.ini` et apprentissage ;
- `com.autochips.backcarapp` et tout `com.autochips.*` (recul rapide, QuickBoot) ;
- l'app radio (`com.jancar.radio…`), le lanceur et l'interface système Jancar (barre de volume, nuit).

**Relevés sur l'appareil** :
```sh
adb shell getprop > getprop.txt                       # dont jancar.radio.id (tuner réel)
adb shell ls -lZ /dev/gpios_ioctl /dev/i2c-* /dev/ttyS* /dev/video* > devices.txt
adb shell cat /proc/bus/input/devices > input.txt
adb shell lsmod > lsmod.txt; adb shell cat /proc/cmdline > cmdline.txt
adb shell dumpsys media.audio_flinger > audioflinger.txt
adb shell settings list global > settings_global.txt
adb shell content query --uri content://com.jancar.settings.provider/settings > jancar_settings.txt
adb logcat -b all -d > logcat_boot.txt                # juste après un démarrage (tags ivi-services)
# avec root : adb shell i2cdetect -y 6 ; noyau (boot.img) ou modules .ko qui fournissent /dev/gpios_ioctl
```
