# LibreHU-service

Service libre de gestion des autoradios Jancar / Autochips AC8257 (UJC201) : MCU, alimentation, puce audio,
radio, touches, caméra. Il vise à remplacer `com.jancar.services` (ivi-services) et à exposer une API propre et
protégée aux apps LibreHU (MCU Toolkit, fork ViPER4Android, radio, caméra…).

État : **spécification**. Le code n'est pas encore commencé.

- Analyse d'ivi-services et plan de réécriture : [docs/ivi-services/](docs/ivi-services/README.md)
- Firmware MCU et protocole : [MCU-tools-app/docs/mcu_firmware.md](https://github.com/LibreHU/MCU-tools-app/blob/main/docs/mcu_firmware.md)
- Puce audio (BD37534), AIDL `IAudio` : [MCU-tools-app/docs/ivi_audio.md](https://github.com/LibreHU/MCU-tools-app/blob/main/docs/ivi_audio.md)
