# Profils de voiture pour le décodage CAN

## Pourquoi un profil par voiture
Le boîtier CAN (Hiworld sur l'UJC201 de l'auteur ; Raise, Simple, Oudi… sur d'autres) est branché sur le CAN-H /
CAN-L de la voiture. Il **ne relaie pas les trames du bus** : son firmware, réglé pour un modèle de voiture, les traduit
en ses propres messages (Hiworld `5A A5 LEN CMD D0..Dn-1 CS`, Raise `2E CMD LEN D0..Dn-1 CS`…), que le MCU recopie
dans ses trames `10` (voir [ivi-services/12-canbus.md](ivi-services/12-canbus.md) et
[Familles de boîtiers](#familles-de-boîtiers)). Le sens de chaque commande (`0x11` état, `0x12` portes…)
dépend du protocole du boîtier pour la marque (Renault : LNP002). Le profil de voiture dit comment lire ces
messages ; on le choisit dans l'onglet **CAN** et il s'applique tout de suite.

Profils intégrés :
- `renault-clio3-hiworld` : Clio 3 2005-2014, protocole Hiworld LNP002 (état ACC / feux / marche AR / frein,
  touche volant, angle de volant, portes, température extérieure, radar, version du boîtier) ;
- `hiworld-generic` : aucun décodage, messages bruts, pour étudier une nouvelle voiture.

## Familles de boîtiers

Le profil nomme son boîtier (`"box"`) : une **famille** (trame, somme de contrôle, accusé) ou le **fabricant**, qui
renvoie à la famille de la plupart de ses protocoles. Relevé dans l'appli Hiworld « Car Info » 1.0.3991
(`com.can.activity`, lue sans être exécutée : classes de base `HdProtocol`, `HdAA55Protocol`, `ReBaseProtocol`,
`ReFDProtocol`, `ReFD2Protocol`, `BnrProtocol`, `LzProtocol`, `KcyProtocol` ; fabricants de la table `canbox` de
`assets/canbus.db`). `core/.../can/CanBox.kt`, testé (`CanBoxFamiliesTest`).

| `box` | Trame | Somme | L'autoradio répond | Fabricants (`box` accepté aussi) |
|---|---|---|---|---|
| `hiworld` | `5A A5 LEN CMD D… CS` | (LEN + CMD + ΣD) & 0xFF − 1 | `5A A5 01 FF <cmd> CS` à chaque message | `hiworld`, `wy` |
| `hiworld-aa55` | `AA 55 LEN CMD D… CS` | idem | rien | — |
| `raise` | `2E CMD LEN D… CS` | (CMD + LEN + ΣD) XOR 0xFF | l'octet `FF` à chaque message (le boîtier répond aussi par un octet : FF, F0, F3, FC) | `raise`, `simple`, `bagoo`, `binary`, `oudi`, `luzheng`, `keyichuang`, `xinbas`, `dogen`, `hcy`, `bsj`, `cyt`, `autoai`, `rcw`, `hechi`, `xfy`, `hangdao` |
| `raise-fd` | `FD LEN CMD D… CS` (LEN = n + 3) | (LEN + CMD + ΣD) & 0xFF | rien | — |
| `raise-fd2` | `FD LEN CMD D… CSH CSL` (LEN = n + 4) | somme sur 16 bits | rien | — |

Non pris en charge : Klyde (`55 AA 20 11 …`), Gobetter, et la variante CYT `55` à longueur fixe (quelques modèles).
Dans « Car Info », Raise et Hiworld couvrent l'essentiel du catalogue (6 352 entrées, 129 marques : Raise 2 298,
Hiworld 1 633, Oudi 792, Simple 777, Binary 265, Luzheng 191, Daojun 144…) ; **la trame est lue et acquittée** pour
tous ces boîtiers, mais **le décodage d'une voiture demande son profil** (seule la Clio 3 + Hiworld est intégrée).

`"baud"` (facultatif) : vitesse de la liaison série du boîtier, réglée sur la MCU au départ du dialogue
(`0F 00 <index>`, comme l'appli d'origine) : 9600, 19200, **38400** (le plus courant), 57600, 115200, 230400, 460800.

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
`link` (facultatif) : ce que l'autoradio envoie au boîtier, voir [Dialogue avec le boîtier](#dialogue-avec-le-boîtier-link).
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

## Dialogue avec le boîtier (`link`)

Un boîtier Hiworld attend que l'autoradio lui réponde : sans réponse, il envoie ses messages **un par seconde en
boucle** et **aucune touche du volant** (journaux CAN d'une Clio 3 des 02 et 04/11/2026 : 14 types de messages en
tour de ~17 s, octets de touche toujours à 0). Le service fait donc comme l'appli d'origine du boîtier
(« Car Info » `com.can.activity` 1.0.3991, analysée sans être exécutée) :

- **accusé de chaque message** reçu avec une somme juste : `5A A5 01 FF <cmd> CS` (`HdProtocol.acknowledge`,
  envoyé par `JancarRx.onHandlerOkData`), sauf pour les accusés du boîtier lui-même (commandes `0xFF` et `0xFE`) ;
- **messages du profil** : pour la Clio 3, le **type de voiture** `5A A5 02 24 00 31 56`
  (`HdRenaultProtocolLNP002.buildCarTypePacket` = commande `0x24`, `[code voiture, 0x31]`, code 0 pour la Clio 3 dans
  `assets/protocol/renault.xml` et `persist.sys.can_carcode`), 1 s après le départ puis toutes les 30 s
  (`CanBusService`, boîtier Hiworld), et de nouveau quand le boîtier reparle après un silence de plus de 5 s.

Envoi par la trame MCU `10`, que la MCU recopie telle quelle sur la liaison série du boîtier
(`core/.../can/CanBox.kt`, `can/CanBoxTalk.kt`). Seulement quand le service tient la MCU (jamais avec ivi-services,
qui le fait déjà). Onglet CAN, carte **Dialogue avec le boîtier** : interrupteur, accusés envoyés, messages du
profil envoyés (octets, heure), commandes accusées par le boîtier. Journal `LibreHU-CAN` : « box link … »,
« → box: … », « box acknowledged 0x24 ».

Dans un profil :
```json
"link": {
  "ack": true,
  "send": [ { "name": "car type", "cmd": "0x24", "data": "00 31", "first_ms": 1000, "every_ms": 30000 } ]
}
```
`ack` (vrai par défaut) : accuser chaque message comme la famille du boîtier le fait. `send` : messages envoyés
`first_ms` après le départ (0 à 60 000), puis toutes les `every_ms` (0 = une fois, sinon 500 ms au moins), 64 octets
de données au plus, mis en trame par la famille du boîtier (`box`).

## Touches du volant

Le profil dit où le boîtier met les touches du volant :
```json
"wheel_keys": { "cmd": "0x11", "code_byte": 2, "state_byte": 3, "pressed": [1], "event_byte": 1, "event_mask": "0x08",
                "names": { "1": "volume +", "2": "volume -" } }
```
Message `cmd`, code à `code_byte` (masqué par `code_mask`), appuyé tant que l'octet `state_byte` vaut une des valeurs
de `pressed` (sans `state_byte` : tant que le code n'est pas 0), et seulement si l'octet `event_byte` a un bit de
`event_mask` ; octets comptés depuis D0 ; `names` : noms affichés. Exemple Raise courant :
`{ "cmd": "0x20", "code_byte": 0, "state_byte": 1, "pressed": [1, 2] }`. Un profil Hiworld sans `wheel_keys` mais avec
un signal `wheel_key` dans `0x11` garde la lecture LNP002 : D1 bit 3 = événement touche, D2 = code, D3 = 1 à
l'appui (`core/.../can/HiworldWheelKeys.kt`). Chaque code reçoit une action comme les touches de façade (onglet CAN,
carte Touches du volant). Codes par défaut, d'après `HdRenaultProtocolLNP002` de l'appli Hiworld : 1 volume +,
2 volume −, 3 sourdine, 5 / 13 précédent, 6 / 14 suivant, 18 sourdine. Les codes 4 et 17 arrivent aussi d'une
commande de Clio 3 sans sens connu : rien tant qu'aucune action n'est choisie. La carte affiche le code de la
dernière touche et la **trame brute** (D1 D2 D3) pour identifier un bouton.

- **Octet D2 entier** : l'appli Hiworld (et ce service avant) ne gardait que D2 & 0x1F ; deux boutons dont les codes
  ne diffèrent qu'au-dessus de 0x1F (SRC+ / SRC− signalés identiques sur Clio 3) donnaient le même code. Le code est
  maintenant l'octet entier ; un code au-dessus de 0x1F sans action propre garde celle de ses 5 bits bas (réglages
  d'avant).
- **Appui long** : le boîtier répète l'appui toutes les ~380 ms tant que la touche est tenue. Une touche avec un appui
  long choisi : appui court au relâchement, appui long après 2 répétitions (~0,8 s). Sans appui long : chaque
  répétition refait l'appui court (volume tenu).
- **Touches ignorées au démarrage** (5 s par défaut, 0 à 15 s) : au démarrage du service et au réveil de l'écran
  (contact mis), les touches sont ignorées ; la commande de Clio 3 envoie seule un appui SRC à la mise du contact.
- **Sélecteur de LibreHU EasySource affiché** : ▶▶ / ◀◀ / ▶‖ (touches média, qu'Android donne aux sessions média et
  pas à la fenêtre) lui sont envoyés : la molette fait défiler les sources et valide.

Actif par défaut quand ivi-services est désactivé (sinon `ivi-canbus` agit aussi et chaque appui compte double).
Un bouton qui ne change pas « dernière touche » n'est pas transmis par le boîtier : rien à faire côté Android.
