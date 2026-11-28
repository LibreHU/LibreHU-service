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
`maxVolume`, `toneByViper`, `supported` (a driver runs), `driver` ("BD37534", "BU32107" or ""), BU32107 boards:
`bu32107Driver` (experimental driver on; setting it restarts the hardware), `bu32107` (`Bu32107Settings.encode()`),
`testNoise` (0 off, 1 pink, 2 white, not stored); AK7604 boards: `ak7604Driver`, `ak7604` (`Ak7604Settings.encode()`). `getTone` / `setTone` keep the 2 dB steps of API 1-9.

## Other chips

The board is identified by the kernel node `/sys/devices/virtual/mtk-adc-cali/mtk-adc-cali/jancar_board_id`
(kernels `stable-4.9.337` and `rc-4.19.325`); each chip gets its own driver, never another chip's commands.

### ROHM BU32107 (experimental, off by default)

`core/.../audio/Bu32107.kt`, written from the ROHM datasheet **BU32107EFV-M Rev.001 (07.Apr.2017)**. Jancar's
`libJanCarIVI.so` (`AudioBU32107`, `BU32107_Init`) was read statically (disassembly, never run) only for the
board wiring. Not tested on a real board: the driver runs only once "Experimental BU32107 driver" is turned on in the
Audio page (`PREF_BU32107`; the service restarts its hardware).

- **Bus**: `/dev/i2c-6`, slave 80h (7-bit 0x40), 16-bit select address, address auto-increment (new native
  `i2cWriteBlock`). Android's audio arrives **digitally** (I2S 16 bits on the D-1 pins, Digital Input1); radio and AUX
  through the ADC (Single4 / Single5, input gain 0..36 dB); the subwoofer leaves on the SL and SR faders.
- **Jancar's init checked against the datasheet**: every value is legal and in the datasheet's order (System Reset
  `FEFE=81`, Initial Setup with RAM clear `0001=CC` then `0C`, Test A..F = `00 C0 00 C0 34 05` as required, fader
  inputs `0102=20` meeting the digital-mixing rule, fixed bits kept). Its encoders are right (fader `80h | 32-dB`,
  -inf at -80 dB and below; digital attenuator `64-2·dB`, capped at -79 dB though the chip reaches -95.5 dB). Jancar
  runs the EQ and the front high-pass with its own biquad coefficients ("Direct Coef Set"); LibreHU uses the chip's
  Table mode everywhere, so no coefficient can be wrong.
- **Driven** (Jancar used only part of it): 13-band graphic EQ ±24 dB (2 dB steps, Q 4.7 or 2.2, rear same or
  separate, EQ headroom pre/post scaler), 3-band tone ±12 dB with bass 40/63/100/160 Hz, middle 400/630/1k/1.6k,
  treble 2.5k/4k/6.3k/10k, loudness 0..15 dB with its two corners and high boost, front and rear high-pass
  25..250 Hz 2nd/4th order and phase, subwoofer low-pass 25..250 Hz 2nd/4th order and phase, subsonic high-pass
  20..200 Hz, P2Bass front / rear (0..12 dB, 54..214 Hz), **time alignment** of FL, FR, RL, RR and subwoofer
  (0..21.3 ms, 1/48 ms steps), volume on the digital attenuators (0.5 dB), balance / fader / speaker levels / sub level
  on the analog faders (positive levels by the digital boost), Android over AUX (D-1 to Input3, DAC digital mixing),
  pink / white **test noise** of the chip (REW, RTA).
- **Not driven**: spectrum analyser (needs I2C reads), BEEP generator, 16-band EQ mode, S/PDIF output settings,
  direct coefficients.

### AKM AK7604 (experimental, off by default)

`core/.../audio/Ak7604.kt`, rebuilt from Jancar's `Ak7604_*` functions in `libJanCarIVI.so` (read statically, never
run). The AK7604 is a programmable DSP: the service loads **Jancar's own DSP program** into it, shipped in
`app/src/main/assets/ak7604/` with the project owner's permission (provenance and SHA-256 in its README; the service
refuses other files). AKM publishes only a product brief of the chip; Jancar's commands match the AK7755 datasheet of
the same family: control registers `C0h 00h reg val` / `40h 00h reg`, program `B8h` (40-bit words) and coefficients
`B4h` (24-bit) while the DSP is held in reset, 50 words per transfer, then the chip's CRC (`72h`, CRC-16/XMODEM)
compared, coefficients while running `80h..8Fh` + `A4h`.

- **Init** (Jancar's): reset pin (GPIO 14) high / low / high, id register C0h = 04h, DSP stopped (02h bit 7,
  A3h bits 2..0), 164 control registers, program, coefficients, DSP started, speaker high-pass and P2Bass flat,
  subwoofer pre-gain -10 dB; muted.
- **Driven**: master volume (CRAM C8h, 24-bit Q3.21 gain), input mixer (ADC 18h/1Ah, I2S 19h/1Bh: Android at -6 dB
  as Jancar, AUX through the analog selector 8Dh), mute (89h), the four speaker levels (DAh, DDh, EAh, EDh: balance,
  fader, speaker levels), subwoofer level (11Ch), low-pass (115h, phase 180° by inverting the numerator) and subsonic
  high-pass (110h), high-pass of the front (E3h) and rear (F3h) speakers, P2Bass low shelf front (DEh) / rear (EEh).
  Filters are Jancar's RBJ biquads at 48 kHz, written b2, b1, -a2, -a1, b0 in Q2.22.
- **Not driven yet**: Jancar's band EQ (`Ak7604_SetEq`, up to 36 bands through `ComperFilter_GetDataHexN`), tone,
  loudness and time alignment (`_SetDelayCmd`): their coefficient formats and address tables are not decoded.
  `Ak7604_DelayDefault` is skipped: the downloaded coefficients keep the program's defaults.
- Not tested on a board: the driver runs only once "Experimental AK7604 driver" is on (`PREF_AK7604`).

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

## Autres puces

### ROHM BU32107 (expérimental, éteint par défaut)

Pilote écrit d'après la fiche ROHM BU32107EFV-M Rev.001 ; la bibliothèque Jancar n'a servi qu'au câblage de la carte
(lecture statique). Pas testé sur une vraie carte : il ne tourne qu'avec « Pilote BU32107 expérimental » activé
(onglet Audio). L'init de Jancar est conforme à la fiche (valeurs légales, ordre reset → effacement RAM → réglages →
faders). LibreHU pilote en plus : égaliseur 13 bandes ±24 dB, fréquences de tonalité, passe-haut avant / arrière,
passe-bas et subsonique du caisson (2e/4e ordre, phase), P2Bass, alignement temporel par haut-parleur, bruit rose /
blanc de test. Non pilotés : analyseur de spectre, bip, mode EQ 16 bandes, sortie S/PDIF.

### AKM AK7604 (expérimental, éteint par défaut)

Pilote refait d'après les fonctions `Ak7604_*` de Jancar (lecture statique). Le service charge dans la puce le
**programme DSP de Jancar** (`app/src/main/assets/ak7604/`, publié avec l'accord du propriétaire du projet, empreintes
vérifiées), puis vérifie le CRC de la puce. Pilotés : volume, sourdine, source, balance / fader / niveau des
haut-parleurs, niveau, passe-bas (phase) et subsonique du caisson, passe-haut avant / arrière, P2Bass avant / arrière.
Pas encore : égaliseur à bandes, tonalité, loudness et alignement temporel de Jancar (formats non décodés). Pas testé
sur une carte : ne tourne qu'avec « Pilote AK7604 expérimental » activé.

