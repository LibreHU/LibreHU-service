# Profils de protocole MCU

Chaque modèle d'autoradio dialogue à sa façon avec sa MCU (le microcontrôleur qui gère le contact, l'alimentation,
les feux, les touches…). LibreHU-service sépare ce protocole du reste dans un **profil JSON**, importable et
exportable depuis l'onglet **MCU** de l'app (« Importer un profil », bouton d'export sur chaque profil).

- Profil intégré : **`jancar-jac-v1`** (Jancar UJC201 / AC8257, carte A0_AN). Le service l'exécute avec son
  implémentation native (`JacProtocol`) ; le même profil exporté en JSON sert de modèle pour en écrire d'autres.
- Profil intégré : **`ksw-px6`** (MCU KSW des écrans Android de BMW, Mercedes-Benz…, service v194), détaillé
  [plus bas](#ksw-ksw-px6). **Pas encore essayé sur un autoradio.**
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
| `inputs` | signaux reçus : `acc`, `handbrake`, `headlight`, `backlight`, `reverse` (booléens : octet `byte`, `mask`, `invert`), `version` (ASCII), `date`, `time` (`fields`), `key` (canal puis valeurs), `can` (octets bruts depuis `byte` ; `"byte": -1` : **copie de la trame entière** telle que reçue, pour le moteur CAN, en plus de ce que les autres entrées en lisent — service v194). `match` filtre sur des octets de données. Le premier qui correspond gagne |
| `outputs` | trames envoyées (`pcReady`, `muteOn`/`Off`, `ampOn`/`Off`, `antennaOn`/`Off`, `date`, `time`, `can`, `queryClock`, `watchdogOff`, `resetSoc`, `sleepTimer`, `beat`) : commande puis données, avec `{yearHi} {yearLo} {month} {day} {hour} {minute} {second} {units} {data}` (`{units}` : durée de veille de la MCU, `F1 {units}` sur Jancar). Les champs d'horloge marchent dans `date` comme dans `time` (une MCU qui règle date et heure d'une seule trame n'a que `time`). Une sortie absente n'est pas envoyée |
| `beatMs` | période (100 à 60 000 ms) de la sortie `beat`, le battement que la MCU attend de la SoC ; 0 ou absent = aucun (service v194) |
| `commands` | trames nommées (`"nom": "commande données"`, comme `outputs`) que l'onglet **Outils MCU** propose en boutons quand le profil en service n'est pas le Jancar (service v194) |
| `board` | GPIO de la SoC (marche arrière, clignotants, rétroéclairage, mute ampli, antenne) ; `null` = non câblé. `audioChip` : `bd37534` ou `null` (puce audio non pilotée) |

Un profil invalide est refusé avec la raison (champ manquant, hexadécimal faux, sortie inconnue…).

Sans entrée `time` ni sortie `queryClock` (horloge de la MCU illisible), le service n'attend plus l'heure de la MCU
avant de lui envoyer celle d'Android (service v194).

## KSW (`ksw-px6`)

MCU des écrans Android KSW posés devant le système d'origine de la voiture (BMW, Mercedes-Benz…). Le protocole vient
de trois sources, recoupées octet par octet :

- **« KSW API – Commands » v2.12** de VincentZ4 (19/06/2020, CC BY-NC ; PX6, Android 9, BMW F1x 2011-2012 CIC
  8.8/10.25") : port `/dev/ttyS3` sur PX6, trame, somme de contrôle, commandes `60` à `79` et leurs exemples encadrés ;
- **McuCommunicator** (<https://github.com/KswCarProject/McuCommunicator>, GPL-3.0, d'après le travail de
  VincentZ4) : battement, événements de la MCU (`A1` : touches, données voiture, clim, frein à main, portes) ;
- le **service central de KSW** tel que KswCarProject l'a publié (<https://github.com/KswCarProject/KswCenterService>,
  lu seulement) : 115200 bauds, ports des unités Snapdragon, battement `68 08 00` chaque seconde, `61 00` envoyé au
  démarrage, contact (`10 xx`, `11 05 xx`), marche arrière (`11 01 xx`), version (`12`, 40 octets), écran
  (`69 12 01` Android, `69 12 02` système de la voiture : le PDF nomme ces deux-là à l'envers), écran allumé / éteint
  (`6C 02 xx`), commande vocale (`69 13 xx 00`).

| | |
|---|---|
| Trame (dans les deux sens) | `F2 00 CMD LEN D0..Dn-1 SUM`, LEN = n, SUM = (octets 1 à Dn-1) xor `FF` ; ex. `F2 00 6A 02 0D 00 86` |
| Port, vitesse | `/dev/ttyS3` (PX6), 115200 ; unités Snapdragon : `/dev/ttyMSM1`, `/dev/ttyHSL1` (MSM8937), `/dev/ttyHS1` (M600, Android 11 et plus) : exporter le profil, changer `port` et `id`, l'importer |
| Démarrage | `61 00` (demande de version) jusqu'à la réponse `12` |
| Battement | `68 08 00` toutes les 1000 ms (`beat`, `beatMs`) |
| Heure | `64 AA MM JJ hh mm ss 00` (année − 2000, 24 h), chaque minute comme les autres MCU |
| Entrées | contact `10 xx` et `11 05 xx`, marche arrière `11 01 xx`, version `12`, frein à main `A1 10` (D1 bit 3) |
| Données voiture | chaque trame `A1` (sous-commande en D0 : `17` touches, `19` données, `1C` clim, `10` frein à main / ceinture / clignotants, `12` portes) est copiée entière au moteur CAN (`"byte": -1`), lue par un profil de voiture KSW (type de boîtier `ksw-mcu`, moteur 2.8 pour les touches) ; réglage CAN « par le MCU » |
| Commandes (onglet Outils MCU) | écran Android / voiture, son de la voiture / musique Android, écran allumé / éteint, commande vocale, lecture en cours, clim + / −, caméra avant (le PDF la dit inutilisée en 2019) |

Laissés de côté exprès : les réglages que la MCU garde (type de voiture, protocole CAN, caméras, ampli… : une mauvaise
valeur peut couper le lien avec la voiture), le mode mise à jour de la MCU et l'extinction (`69 10 01`) ; un profil
importé peut les ajouter. Pas de puce audio ni de GPIO pilotés : la MCU KSW s'en charge.
