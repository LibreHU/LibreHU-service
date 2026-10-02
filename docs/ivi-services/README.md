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
| [12-canbus.md](12-canbus.md) | app CAN, véhicule configuré (Renault Clio 3 / Hiworld), trames du boîtier |
| [api.md](api.md) | les 30 interfaces AIDL (≈ 470 méthodes) avec leurs numéros de transaction |

Compléments dans `LibreHU/MCU-tools-app/docs` : `mcu_firmware.md` (firmware MCU et protocole) et
`ivi_audio.md` (puce audio, `IAudio`, registres BD37534).

## Méthode et limites
- Analyse statique : jadx (Java) et `llvm-objdump` (`libJanCarIVI.so`, symboles C++ présents). Rien n'a été
  exécuté sur l'appareil.
- Niveaux : **[A]** lu dans l'APK, **[N]** lu dans la lib native, **[V]** vu dans le dump, **[D]** déduit.
- La datasheet officielle du BD37534 n'a pas pu être consultée (bloquée par le proxy de la session d'analyse).

## Ce qu'il manque
Reçus : `ivi-services`, `ivi-audio-settings`, `ivi-bt`, `ivi-btservice`, `ivi-canbus`, `ivi-radio`,
`ivi-settings`, `ivi-input` (app AV `com.jancar.avin`), dump de configuration, relevés `get.zip`.

Encore utiles :
- **capture CAN** sur la voiture (MCU Toolkit, « Capture CAN ») : contact, moteur, chaque commande au volant ;
- `com.autoai.canbus.provider` : `adb shell content query --uri content://com.autoai.canbus.provider/...` (tables à découvrir) ;
- noyau (`boot.img`) ou source du pilote de `/dev/gpios_ioctl` (pour LineageOS) ;
- `com.autochips.*` (recul rapide, QuickBoot), le lanceur et l'interface système Jancar ;
- avec root : `i2cdetect -y 6`.
