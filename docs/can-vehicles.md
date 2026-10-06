# Profils de voiture pour le décodage CAN

## Pourquoi un profil par voiture
Le boîtier CAN (Hiworld sur l'UJC201) est branché sur le CAN-H / CAN-L de la voiture. Il **ne relaie pas les trames
du bus** : son firmware, réglé pour un modèle de voiture, les traduit en ses propres messages
`5A A5 LEN CMD D0..Dn-1 CS`, que le MCU recopie dans ses trames `10` (voir
[ivi-services/12-canbus.md](ivi-services/12-canbus.md)). Le sens de chaque commande (`0x11` état, `0x12` portes…)
dépend du protocole du boîtier pour la marque (Renault : LNP002). Le profil de voiture dit comment lire ces
messages ; on le choisit dans l'onglet **CAN** et il s'applique tout de suite.

Profils intégrés :
- `renault-clio3-hiworld` : Clio 3 2005-2014, protocole Hiworld LNP002 (état ACC / feux / marche AR / frein,
  touche volant, angle de volant, portes, température extérieure, radar, version du boîtier) ;
- `hiworld-generic` : aucun décodage, messages bruts, pour étudier une nouvelle voiture.

## Et les dictionnaires CAN de CliOS ?
[CliOS](https://github.com/Tanchouteur/CliOS) lit le bus **directement** (SocketCAN) : ses dictionnaires
(`data/can/can_moteur_clio3.json`) sont indexés par identifiant CAN (`0x181` régime, `0x354` vitesse,
`0x60D` portes / feux…). Ces trames n'arrivent pas jusqu'à l'autoradio par le boîtier Hiworld, donc un
dictionnaire CliOS ne peut pas être importé tel quel (l'import le refuse avec ce message). En revanche le
**format des signaux est le même** : un signal de CliOS se recopie dans un profil LibreHU, sous la commande Hiworld
qui porte la même information. Pour les données moteur (régime, vitesse, température…), utiliser l'OBD
([obd.md](obd.md)).

## Format
```json
{
  "format": "librehu-can-vehicle", "version": 1,
  "id": "ma-voiture", "name": "Ma voiture (Hiworld XXX)", "description": "…",
  "box": "hiworld",
  "messages": {
    "0x11": { "name": "State", "signals": {
      "status":   { "start_byte": 0, "bits": { "ACC": 0, "lights": 1 } },
      "key":      { "start_byte": 2, "mask": "0x1F", "values": { "1": "volume +" } },
      "steering": { "start_byte": 6, "size": 2, "sign_magnitude": true, "factor": 0.1, "unit": "°" }
    } }
  }
}
```
Clés des messages : commande Hiworld en hexa. Champs d'un signal, comme dans CliOS (`src/signal_processor.py`) :

| Champ | Défaut | Rôle |
|---|---|---|
| `start_byte` | 0 | premier octet, **compté depuis D0** (le décodeur d'ivi-canbus compte depuis `5A` : retirer 4) |
| `size` | 1 | nombre d'octets (1..8) |
| `endian` | `big` | `big` ou `little` |
| `mask`, `shift` | — , 0 | masque hexa puis décalage à droite |
| `signed`, `bit_length` | false, size × 8 | entier signé sur `bit_length` bits |
| `sign_magnitude` | false | bit de poids fort = signe moins, le reste = valeur (angle de volant Hiworld) |
| `factor`, `offset` | 1, 0 | valeur = brut × factor + offset |
| `min_value`, `max_value` | — | valeur ignorée hors bornes |
| `bits` | — | drapeaux nommés : numéro de bit (0 = poids faible) dans l'entier lu |
| `unit`, `label` | — | affichage |
| `values` | — | noms des codes (`"1": "volume +"`) |
| `ascii` | false | texte de `start_byte` à la fin du message |

Pour une nouvelle voiture : choisir `hiworld-generic`, capturer (onglet CAN, export) en actionnant chaque commande,
repérer les octets qui changent (en orange), écrire le profil, l'importer.
