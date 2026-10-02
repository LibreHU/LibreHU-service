# LibreHU-service

Service libre de gestion des autoradios Jancar / Autochips AC8257 (UJC201) : MCU, alimentation, puce audio,
radio, touches, caméra. Il vise à remplacer `com.jancar.services` (ivi-services) et à exposer une API propre
aux apps LibreHU (MCU Toolkit, fork ViPER4Android, radio, caméra…).

État : **premières bases (v0, non testée sur l'appareil)**. Le protocole MCU et le pilote audio sont couverts par
des tests unitaires ; le comportement réel sur l'UJC201 reste à valider.

## Ce qui est implémenté

| Module | Contenu | Où |
|---|---|---|
| Transport MCU | trame `JAC_V1` (`EE FA LEN CMD DATA CS`), ACK `C0`, renvoi 500 ms × 5 pour `1F 08 01 F1 80`, trames longues (CAN, LEN ≥ 0x80) | `core/.../mcu` |
| Véhicule | ACC, frein à main, feux, version MCU (trames MCU) ; marche arrière et clignotants (GPIO 2 / 7 / 6, actifs bas) | `core/.../unit/HeadUnit.kt` |
| Alimentation | `1F 01` au démarrage ; ACC on : démute (MCU `08 00`, GPIO 166, puce), rétroéclairage GPIO 5 après 800 ms, ampli externe `44` ; ACC off : mute, rétroéclairage coupé, `44 00` | idem |
| Horloge | heure Android réglée depuis la RTC de la MCU une fois par démarrage (si `SET_TIME`), puis heure Android envoyée à la MCU chaque minute | idem |
| Audio | pilote **ROHM BD37534** (I2C 6 @0x40) : séquence d'init de Jancar, entrée Android, volume (courbe 0..40), mute doux, graves/médiums/aigus, balance/fader 4 voies, loudness, caisson on/off + niveau ; réglages mémorisés | `core/.../audio/Bd37534.kt` |
| Touches, CAN | trames `20`/`30` et `10` relayées brutes aux clients ; envoi CAN (`10`) | API |
| Antenne radio | demandée par l'app radio (API 2 `setRadioAntenna`) : GPIO 110 + MCU `43`, seulement contact mis | `HeadUnit.kt` |
| API | AIDL `ILibreHuService` + `ILibreHuCallback`, broadcasts `org.librehu.action.VEHICLE_STATE` / `ACC` / `REVERSE` | `app/src/main/aidl` |
| Accès bas niveau | JNI C : tty brut 115200 8N1, `/dev/gpios_ioctl`, I2C (`I2C_SLAVE_FORCE` + `I2C_RDWR`) | `app/src/main/cpp` |
| Diagnostic | écran : état de la liaison, entrées véhicule, réglages audio, trafic MCU | `MainActivity` |

Pas encore fait : correspondance touches → actions, radio (FM MT6631), caméra, multiplexeur/décodage CAN, LED de
façade, veille (`F1`), couche de compatibilité `com.jancar.services.*`, permission `signature|privileged`.

## Installer et tester

1. Récupérer l'APK de l'Action **Build** (artefact `LibreHU-service-debug-…`) et l'installer : `adb install`.
2. **Désactiver ivi-services** : deux programmes sur `/dev/ttyS1` se volent les octets, le service refuse donc de
   démarrer tant qu'il est actif :
   ```
   adb shell pm disable-user --user 0 com.jancar.services
   ```
   Retour arrière : `adb shell pm enable com.jancar.services`. Attention : les apps Jancar (réglages, radio, BT,
   écran de recul…) dépendent d'ivi-services et perdront ces fonctions. Le bouton « Démarrer malgré ivi-services »
   force le démarrage pour un essai rapide, au risque de conflits sur le port série.
3. Ouvrir **LibreHU service** : la liaison doit passer à `RUNNING`, la version MCU s'afficher, et le trafic MCU
   défiler (ACC `00 01`, version `0A`, heure `09`).
4. Pour l'horloge (`SET_TIME`) et un démarrage fiable, installer l'APK comme app privilégiée (`/system/priv-app`,
   avec la liste blanche de permissions) — nécessaire aussi sous LineageOS.

Points à vérifier sur l'appareil (non confirmés par l'analyse statique) : quel fader du BD37534 (1 ou 2) est à
gauche, l'effet réel de `0x2C = 0x00` (coupure caisson), et la logique des GPIO de marche arrière / clignotants.

## Utiliser l'API depuis une app

```xml
<uses-permission android:name="org.librehu.permission.HEADUNIT" />
<queries><package android:name="org.librehu.service" /></queries>
```
```kotlin
bindService(Intent("org.librehu.service.BIND").setPackage("org.librehu.service"), connection, BIND_AUTO_CREATE)
// onServiceConnected : val api = ILibreHuService.Stub.asInterface(binder)
api.setVolume(20); api.setBalanceFade(30, 20); api.registerCallback(callback)
```
Copier les deux fichiers `.aidl` dans l'app cliente (même paquet `org.librehu.service`).

## Construire

- Tout : `./gradlew assembleDebug` (SDK Android + NDK).
- Cœur seul, sans SDK Android : `./gradlew -PcoreOnly :core:test`.

## Documentation

- Analyse d'ivi-services et plan de réécriture : [docs/ivi-services/](docs/ivi-services/README.md)
- Firmware MCU et protocole : [MCU-tools-app/docs/mcu_firmware.md](https://github.com/LibreHU/MCU-tools-app/blob/main/docs/mcu_firmware.md)
- Puce audio (BD37534), AIDL `IAudio` : [MCU-tools-app/docs/ivi_audio.md](https://github.com/LibreHU/MCU-tools-app/blob/main/docs/ivi_audio.md)
