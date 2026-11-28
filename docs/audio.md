# Audio: the ROHM BD37534 sound processor

*Français plus bas.*

LibreHU-service drives the BD37534 of the A0 / A1 / B0 / C0 / C1 / D0 "…A" boards over I2C (`/dev/i2c-6`,
address 0x40), with its own driver (`core/.../audio/Bd37534.kt`, native I2C in `app/src/main/cpp/librehu_hw.c`);
Jancar's `libJanCarIVI.so` is not used. Only one program may write to the chip: it cannot be read back (the
datasheet documents write transfers only, slave address 80h with R/W = 0), so the service keeps the only record of
its state, and it does not start while Jancar's `com.jancar.services` is enabled.

## What the chip does, what Jancar used, what LibreHU drives (API 10)

| Feature (datasheet) | Jancar firmware | LibreHU |
|---|---|---|
| Subwoofer low-pass 55 / 85 / 120 / 160 Hz or off (0x02) | 120 Hz, fixed | chosen in the Audio tab (default 120 Hz) |
| Subwoofer phase 0° / 180° (0x02 bit 7) | function in the library, never called | 0° / 180° |
| Subwoofer output: low-pass, copy of the front or of the rear (0x02 bits 5..4) | low-pass | the three |
| Level of each output FL, FR, RL, RR, sub, +15..-79 dB (0x28..0x2C) | balance / fader only | balance / fader + own level of each speaker (-20..+6 dB) |
| Bass / middle / treble ±20 dB in 1 dB steps (0x51, 0x54, 0x57) | used | 1 dB steps |
| Centre frequency and Q: bass 60/80/100/120 Hz Q 0.5-2; middle 0.5/1/1.5/2.5 kHz Q 0.75-1.5; treble 7.5/10/12.5/15 kHz Q 0.75/1.25 (0x41, 0x44, 0x47) | 80 Hz Q 1, 1.5 kHz Q 1, 12.5 kHz Q 0.75 at start-up; setters never called | chosen per band (defaults = Jancar's) |
| Loudness 0..+20 dB, centre 250/400/800 Hz, high cut 1..4 (0x03, 0x75) | gain only (800 Hz) | the three |
| Input gain 0..+20 dB (0x06) | +4 dB (A0), +5 dB (A1) | 0..+20 dB, 0 by default |
| Volume +15..-79 dB (0x20) | 41-step curve, top +5 dB | same curve + a maximum step the controls reach |
| Mixing input, soft mute, pop-free switching | used | used |
| Level meter (analogue pin 17 LOUT) | not used | not reachable over I2C |

Not in the chip: delay per speaker, high-pass on the front / rear outputs, equaliser per output.

"Tone managed by ViPER4Android" keeps the chip's bass, middle, treble and loudness flat (the values are kept) so
the sound is not equalised twice.

## API 10

`ILibreHuService.getAudioAdvanced()` / `setAudioAdvanced(Bundle)`: keys `bassDb`, `middleDb`, `trebleDb`,
`bassFreq`, `bassQ`, `middleFreq`, `middleQ`, `trebleFreq`, `trebleQ` (Q ×100), `loudness`, `loudnessFreq`,
`loudnessHicut`, `subCutoff` (0 = off), `subPhase180`, `subOutput` (0 low-pass, 1 front, 2 rear), `trims` (int[4]),
`maxVolume`, `toneByViper`, `supported`. `getTone` / `setTone` keep the 2 dB steps of API 1-9.

## Other chips

BU32107 and AK7604 boards: not driven yet (no tester). The board is identified by the kernel node
`/sys/devices/virtual/mtk-adc-cali/mtk-adc-cali/jancar_board_id` (kernels `stable-4.9.337` and `rc-4.19.325`); a
chip other than the BD37534 receives nothing.

---

# Audio : la puce son ROHM BD37534

LibreHU-service pilote la BD37534 des cartes « …A » (bus I2C 6, adresse 0x40) avec son propre pilote, sans la
bibliothèque `libJanCarIVI.so` de Jancar. La puce ne se relit pas : un seul programme doit l'écrire (le service),
et il ne démarre pas tant que `com.jancar.services` est actif.

Ajouté par rapport au logiciel Jancar (onglet Audio, appli téléphone, API 10) : coupure du caisson 55 / 85 / 120 /
160 Hz ou sans filtre, phase 0° / 180°, sortie caisson filtrée ou copie de l'avant / de l'arrière, niveau de chaque
haut-parleur en plus de la balance et du fader, fréquence centrale et largeur (Q) des graves / médiums / aigus avec
des pas de 1 dB, fréquence et coupe-aigus du loudness, gain d'entrée jusqu'à +20 dB, volume maximal, et
« Tonalité gérée par ViPER4Android » (tonalité et loudness de la puce à plat). Absent de la puce : retard par
haut-parleur, passe-haut des sorties avant / arrière.
