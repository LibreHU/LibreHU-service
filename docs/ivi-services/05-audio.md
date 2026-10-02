# 5. Audio (`AudioService`, `AudioFocusManager`, `AudioDevice`)

La puce, l'AIDL `IAudio` et les registres du BD37534 sont détaillés dans
`LibreHU/MCU-tools-app/docs/ivi_audio.md` (sections 2, 3 et 7). Ici, le modèle de fonctionnement à reproduire [A].

## 5.1 Chaîne
```
Android (AudioFlinger, flux média à niveau fixe) ─┐
Radio (tuner I2C) ─────────────────────────────────┼─► BD37534 (i2c-6 @0x40) : sélecteur d'entrée, gain d'entrée,
AUX / AV ──────────────────────────────────────────┘    volume principal, tonalité, fader 4 voies + caisson ─► ampli (mute GPIO 166)
```
- **Le volume de l'utilisateur (`VOLUME_MASTER`, 0..40) est appliqué dans la puce** (registre 0x20) selon la
  courbe `[Audio] BD37534VolumeCurve` (défaut : −79 … +5 dB sur 41 crans). Le flux média Android reste à un
  niveau fixe (`persist.action.volume.percent`, 40 %).
- Entrées physiques (`Platform_AutoChips_8257_37534`) : PC/Android = 11 (gain 0 dB), radio = 3, AUX/AV = 0
  (+5 dB). Avec un tuner « interne », la radio passe par l'entrée Android.

**Android ne sort qu'en stéréo** (`dumpsys media.audio_flinger` : 2 canaux FL/FR, 48 kHz, 16 bits) [V]. La
séparation avant/arrière et le caisson n'existent que dans le BD37534.

## 5.2 Sources (« canaux ») [A]
`IVIAudio.Channel` : PC (1), musique (3), vidéo (4), radio (11), radio TA (12), AUX (23), AV (25), BT A2DP (33),
BT appel (31), BT sonnerie (32), CarPlay / Android Auto (51..64)…
`platformSwitchAudioTo(canal)` : mute bref anti-pop, choix de l'entrée de la puce (radio → 3, AUX → 0, AV → 0,
sinon PC 11 avec sortie numérique), mixeur activé pour AV/AUX/radio, puis pré-volume de la source
(`[Audio] *_PreVolume`, en dB).

## 5.3 Focus et priorités [A]
`AudioFocusManager` demande le focus Android pour le compte des sources Jancar (média, BT, voix, navigation,
TA radio, son AV temporaire) et publie `jancar.audio.channel.state` / `jancar.audio.allstop.state`.
Appel BT → changement de source et volume dédié (`VOLUME_BLUETOOTH`) ; navigation → flux 11, volume
`VOLUME_NAVIGATION` ; marche arrière → volume CCD (`[CcdVolume]`, `VOLUME_MEDIA_CCD` en %).

## 5.4 Mute [A]
Plusieurs couches combinées : `setStreamMute` Android (flux 3 et 11), soft-mute de la puce, mute de la sortie
caisson (param 52) et **GPIO 166** (ampli). La commande MCU `08` (PA0) n'est envoyée que dans quelques cas
(`CarService`), pas dans le chemin de mute normal de cette carte. Mutes « internes »
temporisés (`requestInternalShortMute(ms)`) lors des changements de source, de l'ACC et des réglages.
Les réglages de la puce sont refusés si l'ACC est absente ; le niveau du caisson est refusé pendant un mute.

## 5.5 Volume [A]
- Volume de sécurité au démarrage : `[Audio] MaxSafeVolume` / `MinSafeVolume` (40 / 0).
- ASL (volume asservi à la vitesse) : `[AslSpeedDelta]`, vitesse fournie par `IVICar.RealTimeInfo` (CAN).
- Barre de volume (`showVolumeBar`) : affichée par l'interface système via `IAudioCallback.onVolumeBar`.
- Ampli d'origine (CAN) : paramètres `CAR_AMP_*` (101..108) relayés à la MCU / canservice.

## 5.6 Accès direct aux registres
`ISystem.CmdApp2Serv(6, …)` permet d'écrire et lire **n'importe quel registre I2C via ivi-services, sans
root** ([08-system-keys.md](08-system-keys.md) §8.4). C'est le seul moyen, avec la ROM d'origine, de régler ce
qu'`IAudio` n'expose pas : niveau de chaque haut-parleur (0x28..0x2B), fréquence de coupure du caisson
(0x02), phase.
