# 11. Plan de réécriture (LibreHU-service)

## 11.1 Constat
ivi-services mélange une couche matérielle indispensable (MCU, GPIO, puce audio, tuner) et une montagne de
code client spécifique (projection chinoise, assistant vocal, combiné, TBox, AVM…). Il est exporté sans aucune
protection. Ce qui est réellement nécessaire sur l'UJC201 tient dans une poignée de modules.

## 11.2 Contraintes de déploiement
- **ROM d'origine** : ivi-services est une app système (uid system, persistante). Deux maîtres sur `/dev/ttyS1`
  se voleraient les octets ; il faut donc désactiver ivi-services (root : `pm disable com.jancar.services`).
  Les apps Jancar (réglages, radio, BT, lanceur) en dépendent : prévoir une **couche de compatibilité**.
- **ROM LineageOS** (branche `-LOS`) : pas d'ivi-services. Le service est une app privilégiée (ou système,
  signée avec les clés de la ROM). Il faut :
  - les accès aux périphériques : `/dev/ttyS1` (déjà `666 system`), `/dev/gpios_ioctl`, `/dev/i2c-6` (label
    SELinux `mfi_auth_device`, partagé avec la puce MFi d'Apple), `/sys/.../jancar_board_id` ;
  - des règles SELinux pour ce domaine, à mettre dans le device tree ;
  - le noyau d'origine, qui fournit `/dev/gpios_ioctl` (module Autochips/Jancar).

## 11.3 Périmètre proposé
| Priorité | Module | Contenu |
|---|---|---|
| **P0** | Transport MCU | `JAC_V1` (trame, ACK, renvois), PC_READY, heure, version, mise à jour (bootloader `80`/`83`) |
| **P0** | Véhicule | ACC, frein, feux (MCU) ; marche arrière, clignotants (GPIO) ; diffusion sous forme de broadcasts et de callbacks |
| **P0** | Alimentation | machine ACC / ACC long off / veille (`F1`), rétroéclairage GPIO 5, reset hub USB, mute de démarrage |
| **P0** | Audio | pilote BD37534 (registres connus) : sources, volume avec courbe, mute (puce + GPIO 166), tonalité, fader 4 voies, caisson, loudness ; priorités appel / navigation / recul |
| **P0** | Touches | trames `20` + plages apprises → actions (volume, `MediaSession`, apps) et keycodes Android |
| **P1** | Radio | dépend du tuner (`jancar.radio.id`) : pilote TEF668x libre, ou réutilisation de `libJanCarIVI.so` |
| **P1** | Caméra | signal de recul (broadcast standard) + app Camera2 ; recul rapide conservé (avant Android) |
| **P1** | CAN | multiplexeur des trames `10` vers **plusieurs** clients ; décodage selon le boîtier (à étudier avec canservice) |
| **P1** | Réglages | stockage propre (remplace `com.jancar.settings.provider`) |
| **P1** | Luminosité | jour / nuit selon les feux |
| **P2** | LED de façade, apprentissage du volant (UI), ampli externe, ventilateur | commandes MCU `0F`, `11`/`21`, `44` |
| **P2** | Signaux pour la projection | ACC, recul, nuit, focus audio |
| — | Abandonnés | assistant aispeech, combiné, DAB (sauf module présent), TBox, AVM 360, télécommande TV, variantes client |

## 11.4 Architecture proposée
```
apps (MCU Toolkit, fork V4A, radio, caméra, lanceur…)
   │  SDK LibreHU (AIDL + client)  ── permission signature|privileged
   ▼
LibreHU-service (app privilégiée, Kotlin)
   ├─ McuTransport (JAC_V1)      ├─ VehicleState (ACC, frein, feux, recul…)   ├─ PowerManager
   ├─ AudioDsp (BD37534…)        ├─ Tuner (TEF668x…)                         ├─ KeyMapper
   ├─ CanMux                     ├─ Settings                                 └─ Compat (AIDL com.jancar.services.*)
   ▼
Accès bas niveau : tty (/dev/ttyS1), GPIO (ioctl /dev/gpios_ioctl), I2C (/dev/i2c-6) — petit JNI en C
```
- **Couche de compatibilité** (optionnelle) : réimplémenter, avec les **mêmes descripteurs et numéros de
  transaction**, le sous-ensemble d'`ICar`, `IAudio`, `ISystem` et `IRadio` utilisé par les apps conservées
  ([api.md](api.md)). Cela permet de garder temporairement des apps Jancar.
- **Tests** : la couche protocole MCU et les pilotes de puces se testent hors appareil (trames et registres
  attendus). MCU Toolkit sert d'outil de test sur l'appareil.

## 11.5 Informations manquantes
Voir [README.md](README.md#ce-quil-manque).
