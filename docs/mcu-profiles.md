# Profils de protocole MCU

Chaque modèle d'autoradio dialogue à sa façon avec sa MCU (le microcontrôleur qui gère le contact, l'alimentation,
les feux, les touches…). LibreHU-service sépare ce protocole du reste dans un **profil JSON**, importable et
exportable depuis l'onglet **MCU** de l'app (« Importer un profil », bouton d'export sur chaque profil).

- Profil intégré : **`jancar-jac-v1`** (Jancar UJC201 / AC8257, carte A0_AN). Le service l'exécute avec son
  implémentation native (`JacProtocol`) ; le même profil exporté en JSON sert de modèle pour en écrire d'autres.
- Tout autre profil est exécuté par le moteur générique (`ProfileProtocol`). Un test unitaire vérifie que ce moteur,
  avec le profil Jancar, produit et décode exactement les mêmes trames que l'implémentation native.
- Le profil choisi s'applique au redémarrage de la liaison (bouton « Redémarrer la liaison »).

## Format (version 1)

```json
{
  "format": "librehu-mcu-profile", "version": 1,
  "id": "jancar-jac-v1",
  "name": "Jancar JAC_V1 (UJC201, AC8257)",
  "description": "…",
  "serial": { "port": "/dev/ttyS1", "baud": 115200 },
  "frame": {
    "header": "EE FA",
    "lengthOffset": 2, "lengthCounts": "cmd_data", "lengthAdd": 0,
    "cmdOffset": 3,
    "checksum": "sum8", "checksumFrom": 0, "checksumAdjust": 0,
    "maxData": 130
  },
  "ack": { "cmd": "C0", "ackedByte": 0, "needs": ["01", "08", "1F", "80", "F1"], "timeoutMs": 500, "tries": 5 },
  "inputs": [
    { "signal": "acc", "cmd": "00" },
    { "signal": "date", "cmd": "09", "match": { "0": "00" }, "fields": { "yearHi": 1, "yearLo": 2, "month": 3, "day": 4 } }
  ],
  "outputs": {
    "pcReady": "1F 01", "muteOn": "08 01", "muteOff": "08 00",
    "date": "09 00 {yearHi} {yearLo} {month} {day}", "can": "10 {data}"
  },
  "board": { "reverseGpio": 2, "turnLeftGpio": 7, "turnRightGpio": 6, "backlightGpio": 5, "ampMuteGpio": 166,
             "antennaGpio": 110, "audioChip": "bd37534", "audioBus": 6, "audioAddress": "40" }
}
```

| Clé | Sens |
|---|---|
| `id` | identifiant unique (`a-z 0-9 . _ -`) ; importer un profil de même id le remplace |
| `serial` | port série de la MCU et vitesse (9600 à 921600) |
| `frame.header` | octets de synchronisation, en hexadécimal |
| `frame.lengthOffset` / `cmdOffset` | position de l'octet de longueur et de l'octet de commande (après l'en-tête) ; les données suivent le plus grand des deux |
| `frame.lengthCounts` | ce que compte la longueur : `data`, `cmd_data`, `frame` (trame entière) ou `after_length` ; `lengthAdd` est ajouté |
| `frame.checksum` | `none`, `sum8`, `xor8` ou `twos8` (complément à deux), calculé de `checksumFrom` à l'octet avant le contrôle, plus `checksumAdjust` (ex. Hiworld : `sum8`, `checksumFrom` 2, `checksumAdjust` -1) |
| `ack` | trame d'acquittement (`cmd`, octet portant la commande acquittée), commandes à renvoyer tant qu'elles ne sont pas acquittées ; absent = pas d'acquittement |
| `inputs` | signaux reçus : `acc`, `handbrake`, `headlight`, `backlight`, `reverse` (booléens : octet `byte`, `mask`, `invert`), `version` (ASCII), `date`, `time` (`fields`), `key` (canal puis valeurs), `can` (octets bruts). `match` filtre sur des octets de données. Le premier qui correspond gagne |
| `outputs` | trames envoyées (`pcReady`, `muteOn`/`Off`, `ampOn`/`Off`, `antennaOn`/`Off`, `date`, `time`, `can`) : commande puis données, avec `{yearHi} {yearLo} {month} {day} {hour} {minute} {second} {data}`. Une sortie absente n'est pas envoyée |
| `board` | GPIO de la SoC (marche arrière, clignotants, rétroéclairage, mute ampli, antenne) ; `null` = non câblé. `audioChip` : `bd37534` ou `null` (puce audio non pilotée) |

Un profil invalide est refusé avec la raison (champ manquant, hexadécimal faux, sortie inconnue…).
