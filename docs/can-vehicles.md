# Profils de voiture pour le décodage CAN

## Pourquoi un profil par voiture
Le boîtier CAN (Hiworld sur l'UJC201 de l'auteur ; Raise, Simple, Oudi… sur d'autres) est branché sur le CAN-H /
CAN-L de la voiture. Il **ne relaie pas les trames du bus** : son firmware, réglé pour un modèle de voiture, les traduit
en ses propres messages (Hiworld `5A A5 LEN CMD D0..Dn-1 CS`, Raise `2E CMD LEN D0..Dn-1 CS`…), que le MCU recopie
dans ses trames `10` (voir [ivi-services/12-canbus.md](ivi-services/12-canbus.md) et
[Familles de boîtiers](#familles-de-boîtiers)). Le sens de chaque commande (`0x11` état, `0x12` portes…)
dépend du protocole du boîtier pour la marque (Renault : LNP002). Le profil de voiture dit comment lire ces
messages ; on le choisit dans l'onglet **CAN** et il s'applique tout de suite.

**Depuis le service v184 (moteur 2.6.0), aucun profil de voiture n'est intégré au service.** Les voitures viennent en
**paquets par marque** de <https://github.com/LibreHU/LibreHU-car-profiles>, téléchargés par le service lui-même
(onglet CAN, carte « Profils voiture à télécharger » : index, empreinte SHA-256, installation tout ou rien) ou par
LibreHU Apps (onglet « Voitures »). Le service n'a plus qu'**un profil générique par type de boîtier**
(`hiworld-generic`, `hiworld-aa55-generic`, `raise-generic`, `raise-fd-generic`, `raise-fd2-generic` : messages bruts,
aucun décodage), qui sert de **repli** et de **description du type de boîtier** (voir
[Types de boîtiers](#familles-de-boîtiers)). Une unité qui avait choisi une voiture intégrée jusqu'à la v183 passe sur
le profil générique de son boîtier, et le service télécharge tout de suite le paquet de sa marque (nouvel essai après
30 s, 2 min et 10 min si le réseau n'est pas encore là ; bouton « Télécharger son paquet » sinon) : la voiture et le
type choisi reviennent seuls.

Profils publiés (paquets de LibreHU-car-profiles ; intégrés au service jusqu'à la v183) :
- `renault-clio3-hiworld` : **Renault, Dacia, LADA, Samsung, Nissan, Mercedes (Hiworld LNP002)**, le protocole
  « Renault / LADA / Samsung tout compatible » de Hiworld (état ACC / feux / marche AR / frein, touche volant, angle de
  volant, portes, ordinateur de bord, température extérieure, radar, pneus, version du boîtier). Il sert **94 voitures
  (55 modèles)** : choisir la sienne dans l'onglet CAN (« Voiture : … », recherche) ; par défaut la Clio 3. Seul le
  **type de voiture** envoyé au boîtier change (`0x24 [code, 0x31]`, 25 codes) : codes relevés dans « Car Info »
  1.0.3991 (`assets/protocol/*.xml` et `HdRenaultProtocolLNP002.buildCarTypePacket`, relu en instructions brutes). Le
  décodage des messages est celui de la Clio 3 (même classe de décodage) : à confirmer sur les autres modèles ;
- `hyundai-kia-hiworld` : **Hyundai, Kia (Hiworld HYP001)**, 152 voitures (`HdKoreaProtocol`) : état ACC / feux /
  marche AR / P, touches et angle du volant, portes et capot, température extérieure, régime / vitesse / kilométrage,
  radar, version. **Pas d'accusé** (ce décodeur n'en envoie pas : `acknowledge()` = null) ; type de voiture
  `0x24 [code, 0x09]` ;
- `toyota-hiworld` : **Toyota, Lexus ES (Hiworld TYP001)**, 142 voitures (`HdToyotaProtocol`) : message `0x11` découpé
  (état D0, touche D2 / D3, portes D4, volant D6-D7), radar, version ; accusés ; type `0x24 [code, 0x01]` ;
- `honda-hiworld` : **Honda, Acura (Hiworld HDP004)**, 79 voitures (`HdHondaDAProtocol`) : `0x11` découpé (feux / marche
  AR / frein D0, vitesse D1, touche D2 / D3, volant D6-D7), portes, radar, version ; accusés ; type `0x24 [code, 0x02]`.
  Pas encore listées (type de voiture calculé par des règles par modèle non relues) : Accord 2018, Odyssey 2004-2008,
  Crosstour, Civic 2016-, CR-V 2017 ;
- `vw-mqb-hiworld` (lot 2, v158) : **Volkswagen, Skoda (Hiworld VWP004, MQB)**, 29 voitures (`HdMQB7Protocol`) :
  `0x11` état (contact, feux, marche AR, frein, radar), touche D2 / D3 (1 à l'appui), volant D6-D7 (petit-boutiste,
  signé) ; `0x12` portes D2, consommation instantanée D3, alertes D6 (batterie faible, huile, ceinture, lave-glace),
  carburant D7, batterie D8 (volts entiers) ; `0x19` **huile D6 − 40 °C, eau D7 − 40 °C, régime D8-D9** ; `0x36`
  clignotants ; `0x41` radar (brut ÷ 15). **Aucun type de voiture** (pas de `buildCarTypePacket`) : la voiture choisie
  ne fait que la nommer ; accusés Hiworld ;
- `vw-pq-hiworld` (lot 2, v159) : **Volkswagen, Skoda, anciens Mercedes (Hiworld VWP003, PQ)**, 42 voitures
  (`HdMQB6Protocol`, `HdProtocolVwp003` pour Mercedes, mêmes messages) : `0x72` état D0 (contact, feux, marche AR, frein, clé, radar, Bluetooth), vitesse D1, touche D2
  (**sans octet d'état** : appuyée tant que le code n'est pas 0), luminosité D3, volant D4 / D5 (un octet par côté,
  0-140 ; v164 : un seul signal `steering` par formule `D4 == 0 ? -D5 : D4`, × 540 / 140, rôle `steering.angle`), radar D6-D13 (brut ÷ 16) ; `0x73` température extérieure D6 (÷ 2 − 40 °C), portes D7 ; `0x12` huile D2 (°C,
  255 = absent), consommation D3, rapport D5, alertes D6, carburant D7, batterie D8 ; `0x13` **kilométrage D0-D2,
  vitesse D3-D4 ÷ 10, régime D8-D9** ; `0x18` feux et clignotants ; `0x42` radar latéral. Pas de type de voiture,
  accusés Hiworld. La clim (`0x73` D0-D5) n'est pas encore décodée. **Choix du décodeur** : l'appli Hiworld le
  choisit par modèle et année (`VolkswagenHdManager`), pas par le fichier de protocole du catalogue ; 9 VW listées
  VWP004 (Jetta 2011-, Santana 2013-, Scirocco, Touran 2010-2014, Bora 2013-2018, Lavida, Trek : plateformes PQ) sont
  décodées en VWP003, elles sont donc ici (v160). À l'inverse, `SkodaHdManager` prend VWP004 pour toutes les Skoda,
  même les PQ que le catalogue liste en VWP003 (Octavia II, Superb II, Yeti, Rapid) : laissées ici, essayer
  `vw-mqb-hiworld` si rien n'est décodé ;
- `vw-mqb-raise` (lot 2, v161) : **Volkswagen, Skoda (Raise / Oudi, MQB)**, 61 voitures (`ReMQB7Protocol`), premier
  profil Raise (`2E CMD LEN D… CS`) : bonjour `0x81 01` une fois au départ (`buildConnectPacket`), accusé `FF` de chaque
  message, pas de type de voiture. `0x20` touche D0 / état D1 (1 à l'appui) ; `0x24` portes D0, feux / frein / marche AR
  D1 ; `0x27` température extérieure D1-D2 (petit-boutiste, signé, ÷ 10 ; D0 bit 0 = °F) ; `0x29` volant D0-D1
  (petit-boutiste, signé) ; `0x22` / `0x23` radar arrière / avant (brut) ; `0x32` / `0x33` radar latéral ; `0x30`
  version. Voitures : celles que l'appli décode avec ce décodeur (`VolkswagenReManager`, `VolkswagenOudiManager`,
  `SkodaReManager`, `SkodaOudiManager`, par modèle et année), boîtiers Raise et Oudi réunis. Pas de régime ni de
  température moteur dans ce protocole ; les messages multiplexés (`0x40`, `0x50` : autonomie, consommations, TPMS
  `0x66`) ne sont pas décodés. TWRP lit aussi ce boîtier (trame, accusés `FF`, touches) ;
- `vw-pq-raise` (lot 2, v162) : **Volkswagen, Skoda (Raise / Oudi, PQ)**, 49 voitures (`RePQProtocol`), même liaison
  que `vw-mqb-raise` (bonjour `0x81 01`, accusés `FF`) : `0x20` touche D0 / état D1 ; `0x16` vitesse D0-D1
  (petit-boutiste ÷ 16 km/h) ; `0x24` feux / frein / marche AR (D1 quand le message a 2 octets, D0 sinon : seul le cas
  à 2 octets est décodé) ; `0x26` volant D0-D1 (petit-boutiste, signé) ; radars `0x22`, `0x23`, `0x32`, `0x33` (brut,
  0 = rien) ; `0x30` version. Pas de portes ni de données moteur ici (`0x41` multiplexé non décodé) ;
- `psa-raise` (lot 3, v173) : **Peugeot, Citroën, DS (Raise PSA, écran d'origine retiré)**, 97 voitures
  (`RePsaProtocol` ; Oudi pour quelques Citroën) : trame **FD** (somme 8 bits), **19 200 bit/s**, pas d'accusé ; bonjour
  `0x81 01`, puis le **type de voiture `0xA7 [a, b, 00]` chaque seconde** (comme l'appli, `getCarTypeIntervalTime`
  1 000 ms), 17 types, calculés en rejouant `buildCarTypePacket` (non décompilé : listage `jadx -m fallback`) sur chaque
  voiture ; aucun envoyé avant d'avoir choisi sa voiture. `0x02` touche D0 (appuyée tant que ≠ 0 ; 60 codes, table
  `TranslateKey` relue au listage brut) ; `0x29` volant (petit-boutiste signé × 540 / 5 450) ; `0x36` température
  extérieure (signe + valeur) ; `0x38` portes D0, veilleuses / P / marche AR D3 ; `0x33` conso instantanée, autonomie,
  distance restante ; `0x34` / `0x35` trajets 1 et 2 ; `0x30` / `0x32` radar (0-4, 5 = rien) ; `0x7F` version. Voitures :
  celles que choisissent `PeugeotRaiseManager`, `CitroenRaiseManager`, `DSRaiseManager`, `CitroenOudiManager` ;
- `psa-raise-keep` (lot 3, v173) : **Peugeot, Citroën (Raise PSA, petit écran d'origine gardé)**, 14 voitures
  (`RePsaProtocol_KeepOriginalDisplay`) : mêmes messages, radar en `0x08`, type `0xA7 [05, finition, 00]`, sa propre
  table de touches ;
- `hiworld-generic` : aucun décodage, messages bruts, pour étudier une nouvelle voiture.

Ces trois profils (lot 1) n'ont **pas de voiture par défaut** : rien n'est envoyé au boîtier tant que la voiture n'est
pas choisie dans l'onglet CAN (un boîtier ne doit pas recevoir le type d'une autre voiture). **Pas encore essayés sur une
voiture** : retours et captures bienvenus. Ces profils sont des fichiers JSON de LibreHU-car-profiles
(`brands/<marque>/`) ; le service en garde une copie pour ses tests seulement (`core/src/test/resources/.../cars/`).
Les profils génériques intégrés sont dans `core/src/main/resources/org/librehu/core/can/boxes/`.

**Voitures décodées** (résumé ; liste par marque et modèle, tenue à jour seule depuis les profils de la dernière
version publiée : <https://librehu.github.io/docs.html#can-cars>) :

| Protocole (profil) | Marques | Voitures | Depuis (service) |
|---|---|---|---|
| Hiworld LNP002 (`renault-clio3-hiworld`) | Renault, Renault Samsung, LADA, Nissan, Mercedes-Benz (Smart) | 94 | v147 ; Clio 3 : v23 |
| Hiworld HYP001 (`hyundai-kia-hiworld`) | Hyundai, Kia | 152 | v148 |
| Hiworld TYP001 (`toyota-hiworld`) | Toyota, Lexus (ES) | 142 | v148 |
| Hiworld HDP004 (`honda-hiworld`) | Honda, Acura | 79 | v148 |
| Hiworld VWP004 (`vw-mqb-hiworld`) | Volkswagen, Skoda (MQB) | 29 | v158 |
| Hiworld VWP003 (`vw-pq-hiworld`) | Volkswagen, Skoda (PQ), Mercedes-Benz (anciens) | 42 | v159 ; 9 VW : v160 |
| Raise / Oudi MQB (`vw-mqb-raise`) | Volkswagen, Skoda (MQB) | 61 | v161 |
| Raise / Oudi PQ (`vw-pq-raise`) | Volkswagen, Skoda (PQ) | 49 | v162 |
| Raise PSA (`psa-raise`) | Peugeot, Citroën, DS | 97 | v173 |
| Raise PSA, écran gardé (`psa-raise-keep`) | Peugeot, Citroën | 14 | v173 |

Clio 3 : profil sélectionnable en v17 (06/10/2026), décodage LNP002 complet en v23 ; touches du volant envoyées par
un boîtier Hiworld seulement avec le dialogue (v144). Seule la Clio 3 est essayée sur une voiture.

**Types de voiture** : relevés dans « Car Info » 1.0.3991 sans l'exécuter : la méthode `buildCarTypePacket` de chaque
décodeur, listée en instructions brutes (`jadx -m fallback`), est **rejouée** sur chaque voiture du catalogue
(`assets/protocol/*.xml` : identifiants de série / modèle / année / finition, code voiture) par un petit interpréteur qui
ne suit que des entiers (affectations, comparaisons, sauts) ; validé sur LNP002 contre un calcul fait à la main
(58 800 cas, aucun écart). Couverture : le dialogue (trame lue et acquittée) vaut pour 1 542 modèles du catalogue sur
1 546 ; le décodage intégré pour 190 (12 %), lot par lot.

## Familles de boîtiers

**Les types de boîtiers sont des données (moteur 2.6.0, service v184)**, plus du code : chacun est décrit par **son
profil générique**, un profil normal avec `"box_type"` (nom affiché, fabricants qui l'utilisent) et sa `"framing"`
(voir le format plus bas). Le service en a cinq intégrés (le tableau ci-dessous) ; le paquet `boxes` de
LibreHU-car-profiles (dossier `boxes/`) en ajoute et remplace les intégrés de même `id`, sans nouveau service :
**un nouveau boîtier = un fichier JSON**. Il est installé avant les paquets des marques (leurs fiches peuvent nommer un
type qu'il apporte).

```json
{ "format": "librehu-can-vehicle", "version": 2, "engine": ">=2.6", "id": "raise-generic",
  "name": "Raise (2E): any car (raw messages)", "box": "raise",
  "box_type": { "name": "Raise (2E)", "aliases": ["simple", "bagoo", "oudi"] },
  "framing": { "head": "2E", "order": "cmd,len", "checksum": { "algo": "sum", "over": "cmd,len,data", "xor": "0xFF" },
               "ack": { "raw": "FF" }, "box_answers": ["FF", "F0", "F3", "FC"] },
  "link": { "ack": true, "send": [ { "name": "connect", "cmd": "0x81", "data": "01", "first_ms": 500 } ] },
  "messages": {} }
```

Le profil d'une voiture nomme son boîtier (`"box"`) : un **type** (trame, somme de contrôle, accusé) ou le
**fabricant**, qui renvoie au type de la plupart de ses protocoles ; il peut aussi décrire sa propre `framing`. Relevé dans l'appli Hiworld « Car Info » 1.0.3991
(`com.can.activity`, lue sans être exécutée : classes de base `HdProtocol`, `HdAA55Protocol`, `ReBaseProtocol`,
`ReFDProtocol`, `ReFD2Protocol`, `BnrProtocol`, `LzProtocol`, `KcyProtocol` ; fabricants de la table `canbox` de
`assets/canbus.db`). Registre : `core/.../can/CanBox.kt` (`CanBoxFamilies`) ; testé (`CanBoxTypesTest` : mêmes octets
que les trames codées avant la 2.6, accusés, type apporté par un paquet).

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
tous ces boîtiers, mais **le décodage d'une voiture demande son profil** (intégrés : Hiworld LNP002, HYP001, TYP001,
HDP004, 467 voitures ; voir plus haut).

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
`"data": ""` dans `link.send` : données données par la voiture choisie, rien n'est envoyé avant.
`variants` (facultatif) : les voitures d'un même protocole, `[{ "name": "…", "send": { "<nom d'un message de link.send>": "<octets>" } }]` ;
la voiture choisie remplace les données des messages de ce nom (le type de voiture), aussi dans le fichier de TWRP.
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

### Format 2 (service v163)

Le moteur ne connaît aucune voiture : tout est dans le profil. Le format 2 ajoute ce dont les décodeurs de l'appli
Hiworld ont besoin et ce qui rend un profil vérifiable ; un profil `"version": 1` reste lu tel quel.

```json
{
  "format": "librehu-can-vehicle", "version": 2, "id": "…", "box": "raise",
  "messages": {
    "0x24": { "name": "Doors", "signals": {
      "doors": { "start_byte": 0, "bits": { "front left": { "bit": 6, "role": "door.front_left" }, "trunk": 3 } },
      "state": { "start_byte": 1, "bits": { "reverse": { "bit": 0, "role": "gear.reverse" } }, "when": { "len": 2 } }
    } },
    "0x50": { "name": "Driving data", "mux": 0, "signals": {},
      "cases": {
        "0x10": { "name": "Range", "signals": { "range": { "start_byte": 2, "size": 2, "endian": "little", "unit": "km", "role": "fuel.range" } } },
        "0x30": { "name": "Average consumption", "signals": { "avg": { "start_byte": 2, "size": 2, "endian": "little", "factor": 0.1, "role": "fuel.average" } } }
      } },
    "0x12": { "signals": {
      "oil":  { "start_byte": 2, "invalid": ["0xFF"], "unit": "°C", "role": "engine.oil_temp", "label": { "en": "oil", "fr": "huile" } },
      "mode": { "start_byte": 4, "start_bit": 4, "bit_length": 3 }
    } }
  },
  "examples": [ { "cmd": "0x50", "data": "10 00 2C 01", "expect": { "fuel.range": 300, "avg": null } } ]
}
```

| Nouveau | Rôle |
|---|---|
| `role` (signal, ou bit sous forme `{ "bit": n, "role": … }`) | ce que la valeur veut dire pour LibreHU, quelle que soit la voiture (liste ci-dessous) ; `x.<nom>` pour une valeur propre au profil |
| `mux` + `cases` (message) | message multiplexé : l'octet `mux` (depuis D0) donne le code, chaque code a ses signaux (Raise `0x40`, `0x50`, TPMS `0x66`…) |
| `when` (signal) | décodé seulement si `byte` (masqué par `mask`) vaut une des valeurs de `is`, et/ou si le message a `len` octets (ou `min_len`..`max_len`) |
| `invalid` | valeurs brutes « pas de valeur » (souvent `0xFF`) : ignorées |
| `label` objet | libellé par langue (`en`, `fr`) ; sans libellé, celui du rôle |
| `start_bit` + `bit_length` | champ de bits à la façon DBC (bit 0 = poids faible des octets lus), au lieu de `mask` / `shift` |
| `examples` | auto-tests du profil : message + valeurs attendues (par nom de signal, de bit ou par rôle ; `null` = aucune valeur). Les tests du service vérifient ceux de chaque profil intégré |

**Rôles** (`core/src/main/kotlin/org/librehu/core/can/CanRoles.kt`, 105 rôles) : alignés sur la **COVESA Vehicle Signal
Specification 4.2** (<https://github.com/COVESA/vehicle_signal_specification>, export `vss_rel_4.2.csv`) quand elle a
l'équivalent, avec son unité et son sens (angle du volant : positif à gauche ; portes : chemins d'une voiture conduite
à gauche) : `vehicle.speed` → `Vehicle.Speed`, `engine.rpm` → `Vehicle.Powertrain.CombustionEngine.Speed`,
`door.front_left` → `Vehicle.Cabin.Door.Row1.DriverSide.IsOpen`, `hvac.temp_left` → `…HVAC.Station.Row1.Driver.Temperature`,
`tpms.front_left.pressure` → `…Axle.Row1.Wheel.Left.Tire.Pressure`… Sans équivalent VSS : radars (`radar.rear.left`… en
**% de proximité**, 100 = contact), `steering.position` (-1..1), `lights.on` (mode nuit), `box.version`.
**Angle du volant** : en degrés comme l'appli Hiworld (`SteerWheelInfo.setEps` : brut × 540 / maximum du décodeur) ; son
sens n'est pas encore vérifié sur une voiture (réglage « inverser » de la caméra de recul).

**Formules, héritage, retouches par voiture (service v164)**

| Nouveau | Rôle |
|---|---|
| `expr` (signal) | formule qui donne le nombre à la place des octets lus ; `factor`, `offset`, `invalid`, `values`, `bits` s'appliquent ensuite. Ex. VWP003 : `"expr": "D4 == 0 ? -D5 : D4", "factor": 3.857143` |
| `when` texte | condition en formule : `"when": "len == 2 && bit(D0, 1)"` (l'objet `{ "byte", "is", "len" }` reste valable) |
| `extends` (profil) | part d'un autre profil (intégré, ou importé avant) et le complète : objets fusionnés clé par clé (messages, signaux…), le reste remplacé, `null` retire une clé |
| `patch` (voiture de `variants`) | retouche du profil pour cette voiture seulement, fusionnée de la même façon (un autre facteur d'angle, un message en plus, une autre vitesse série) ; `id`, `variants`, `extends` ne changent pas |

Formules (`CanExpr`, jamais du code : lues une fois, calculées à chaque message, 600 caractères au plus) : syntaxe C,
`+ - * / %`, `& | ^ ~ << >>`, `== != < <= > >=`, `&& || !`, `c ? a : b` ; noms `D0`..`D63` (octets, 0 au-delà de la fin),
`len`, `raw` (les octets du signal), la valeur d'un signal décodé avant dans le même message (son nom) ; fonctions
`min max abs round floor ceil clamp`, `u16be u16le s16be s16le u24be u24le u32be u32le s8` (octets depuis D`i`), `bit`,
`bits(x, début, longueur)`, `signed(x, bits)`, `bcd`, `role("…")` (dernière valeur d'un rôle, venue de n'importe quel
message : unité réglée ailleurs…), `has("…")`. Un calcul impossible (division par zéro, rôle sans valeur) ne donne
aucune valeur.

**Trame décrite dans le profil (service v165)** : pour un fabricant de boîtier sans famille dans le code,
`"framing"` décrit sa trame et remplace la famille de `box` :

```json
"framing": { "head": "2E", "order": "cmd,len", "len_offset": 0,
  "checksum": { "algo": "sum", "over": "cmd,len,data", "size": 1, "add": 0, "xor": "0xFF" },
  "ack": { "raw": "FF" }, "no_ack_for": [], "box_answers": ["FF", "F0", "F3", "FC"], "baud": 38400 }
```

`head` 1 à 4 octets ; `order` `len,cmd` ou `cmd,len` ; LEN = nombre d'octets de données + `len_offset` ; somme `sum`
(additionnée) ou `xor` sur `over` (`head`, `len`, `cmd`, `data`), sur `size` octets (gros-boutiste), puis + `add`, puis
xor `xor` ; accusé `ack.cmd` (données : la commande reçue, tramé pareil, comme Hiworld) ou `ack.raw` (octets bruts, comme
Raise), sauf pour `no_ack_for` ; `box_answers` : octets seuls que le boîtier envoie entre ses messages. Vérifié : mêmes
octets que les familles Hiworld, Raise et Raise FD 16 bits écrites dans le code (tests `CanFramingTest`).

**Messages construits et commandes (service v166)** : dans `link.send`, des `data` avec des champs entre accolades
sont construits à chaque envoi depuis ce que sait l'autoradio ; `"on": "change"` les envoie aussi dès qu'ils changent
(500 ms au moins entre deux) ; `link.commands` = messages envoyés à la demande avec une valeur.

```json
"link": { "send": [ { "name": "time", "cmd": "0xCB", "data": "{year2} {month} {day} {hour} {minute}", "first_ms": 1000, "every_ms": 60000, "on": "change" } ],
          "commands": { "temp left": { "cmd": "0x8A", "data": "01 {value * 2}" } } }
```

Champs : `{formule}` = un octet (formule sur l'état : `{hour}`, `{minute + 1}`, `{value * 2}` dans une commande),
`{formule:2be}` / `{formule:2le}` = sur 2 à 4 octets ; `{text:title:12}` = texte ASCII sur 12 octets complété d'espaces
(`{text:title:12:00}` : de 0x00, caractère hors ASCII → `?`), `{utf16le:title:12}` / `{utf16be:…}` = 12 caractères UTF-16.
Une valeur inconnue : pas de message. **État fourni aujourd'hui** : l'horloge (`year` sur 4 chiffres, `year2`, `month`,
`day`, `hour`, `minute`, `second`, `weekday` 1 = lundi, `hour12`, `pm`) ; les textes (média, téléphone) viendront avec
les envois du lot « envois vers la voiture ». TWRP n'envoie pas les messages construits (commentaire dans son fichier).

**Plusieurs sources de touches (service v167)** : `"keys"` liste d'autres sources que `wheel_keys` (touches média,
façade, molettes), même champs plus `code_base` (par défaut 256, 512… : leurs codes = base + code, jamais ceux du volant)
et, pour une **molette**, `steps_byte` (crans tournés dans le message, signés sauf `"steps_signed": false`) : chaque cran
= un appui du code base + 2 × molette (vers le haut) ou base + 2 × molette + 1 (vers le bas), 10 crans au plus par
message ; noms « volume + » / « volume − ». Ces codes s'affectent à une action comme les touches du volant.

```json
"keys": [ { "cmd": "0x2F", "code_byte": 0, "state_byte": 1, "pressed": [1], "names": { "1": "play / pause" } },
          { "cmd": "0x22", "code_byte": 0, "steps_byte": 1, "names": { "1": "volume", "2": "tune" } } ]
```

**Schéma JSON** : [`can-vehicle.schema.json`](can-vehicle.schema.json) (JSON Schema 2020-12) décrit tout le format 2 :
un éditeur (VS Code…) complète et vérifie un profil qui commence par
`"$schema": "https://raw.githubusercontent.com/LibreHU/LibreHU-service/main/docs/can-vehicle.schema.json"` (le service ignore
cette clé). Les 8
profils intégrés y sont conformes (vérifié avec `jsonschema` 4.26). Le service, lui, vérifie en plus les rôles, les
formules et les `examples` à l'import.

**Version du moteur (service v169)** : le moteur des données voiture suit le [Semantic Versioning 2.0.0](https://semver.org/)
(`CanEngine.VERSION`, aujourd'hui **2.5.0**) : MAJEURE si un profil de l'ancienne majeure pouvait être lu autrement
(les anciens formats restent lus), MINEURE quand le format gagne quelque chose, CORRECTIF pour une correction. Un profil
dit le moteur qu'il demande, `"engine": ">=2.1"` (cette version ou plus récente) ou `"^2.1"` (dans la majeure 2) ; un
service trop ancien le refuse avec un message clair (« mettre à jour LibreHU service ») au lieu de le lire à moitié.
L'export écrit le moteur minimum calculé d'après ce que le profil utilise (`CanEngine.requiredBy`).

| Moteur | Apport | Service |
|---|---|---|
| 1.0.0 | format 1 (messages, signaux, link, variants, wheel_keys) | v17-v162 |
| 2.0.0 | format 2 : rôles VSS, mux / cases, when, invalid, labels, start_bit, examples | v163 |
| 2.1.0 | formules, extends, patch par voiture | v164 |
| 2.2.0 | trame décrite dans le profil | v165 |
| 2.3.0 | messages construits, on change, commandes | v166 |
| 2.4.0 | plusieurs sources de touches, molettes | v167 |
| 2.5.0 | champ `engine`, sources combinées (boîtier + OBD) | v169 |

**Boîtier CAN et sonde OBD en même temps (service v169)** : `CarData` (cœur) et `CarDataHub` (service) réunissent les
valeurs par rôle de toutes les sources : le boîtier CAN (profil choisi) et l'adaptateur OBD-II ELM327 (USB ou Bluetooth,
onglet OBD). Pour un rôle donné par les deux, **le boîtier passe en premier** ; l'adaptateur complète ce que le boîtier
ne donne pas (régime, température d'eau sur la Clio 3…) ; une source qui s'arrête retire ses valeurs, l'autre reste.
Valeurs OBD (mode 01, SAE J1979) → rôles : vitesse, régime, eau, huile, carburant %, température extérieure, charge,
pression d'admission, débit d'air, papillon, température d'admission, avance, temps moteur, débit de carburant, pression
atmosphérique, distances voyant / depuis effacement (VSS `Vehicle.OBD.*` ou `CombustionEngine.*`), tension (PID 0x42,
sinon la mesure `ATRV` de l'adaptateur). Les nouvelles fonctions (écran Voiture…) lisent ce hub.

**`librehu-car-tool.jar`** (service v170 : le moteur sur un ordinateur, Java 17 ; publié avec chaque version du service :
<https://github.com/LibreHU/LibreHU-service/releases/download/latest-main/librehu-car-tool.jar>) :
`java -jar librehu-car-tool.jar check <fichier ou dossier>` lit chaque profil (`extends` compris), lance ses `examples`
et vérifie son `"engine"` ; `java -jar librehu-car-tool.jar decode <profil.json> <export CAN.txt>` décode un export de
l'onglet CAN sans la voiture. Le dépôt des fiches (<https://github.com/LibreHU/LibreHU-car-profiles>) s'en sert à
chaque changement.

**Paquets par marque (service v172)** : les fiches vivent dans <https://github.com/LibreHU/LibreHU-car-profiles>
(`brands/<marque>/`, guide `PORTING.md`, fiche modèle `template/new-car.json`). Sa CI (ou `tools/build_packs.py`)
publie `dist/index.json` et un paquet par marque (`"format": "librehu-car-pack"` : `id`, `revision` = nombre de
commits du dossier de la marque, `engine` = le plus récent demandé par ses fiches, `profiles`), avec taille et SHA-256
dans l'index. Le service installe un paquet **tout ou rien** (`CanPacks.read` : empreinte, moteur, chaque fiche lue et ses
`examples` passés) dans `files/can-packs/<marque>.json` ; une fiche d'un paquet remplace la fiche intégrée de même
`id` (le paquet `boxes` remplace ainsi les profils génériques). Le service télécharge lui-même les paquets depuis la
v184 (`can/CarPackIndex`, carte de l'onglet CAN : mêmes vérifications que LibreHU Apps, paquet `boxes` d'abord). Pour
LibreHU Apps, récepteur
`can/CarPackReceiver` (permission signature `org.librehu.permission.HEADUNIT`, diffusions ordonnées) :
`org.librehu.service.QUERY_CAR_PACKS` → `{"engine": "2.5.0", "packs": {"renault": 1, …}, "sha256": {"renault": "…", …}}`
(révision et SHA-256 de chaque paquet installé ; empreintes publiées aussi dans `dist/SHA256SUMS`) ;
`org.librehu.service.INSTALL_CAR_PACK` (extra `pack` = les octets du paquet, ou data = URI du fichier avec lecture accordée ; extra `sha256`) → OK + révision, ou
annulé + raison ; `org.librehu.service.REMOVE_CAR_PACK` (extra `id`). `librehu-car-tool.jar pack <marque>.json
[sha256]` fait la même vérification sur un ordinateur.

**Liaison avec le boîtier (service v175)** : carte de l'onglet CAN (`can/CanBoxPorts`). **MCU** (par défaut, cet
autoradio : trames `10` du MCU Jancar, vitesse par `0F 00`) ; **adaptateur USB-série** (usb-serial-for-android, à choisir
dans la liste : jamais la sonde OBD par erreur ; permission USB demandée à l'écran) ; **Bluetooth** (SPP, appareil appairé) ;
**port série** du SoC (`/dev/ttyS…`, `ttyMT…` : autres autoradios, autres MCU ; le root règle la vitesse par `stty` et
ouvre le port). Le profil ne change pas : mêmes octets, même trame, même dialogue ; la vitesse du profil est donnée au
port ; une coupure est retentée toutes les 5 s. Hors MCU, les trames `10` du MCU ne sont plus lues comme boîtier.

La caméra de recul lit maintenant `steering.angle` et `radar.rear.*` de **n'importe quel profil** (avant : clés du profil
Clio 3 seulement). Les 8 profils intégrés ont leurs rôles et leurs `examples`. Correction au passage : l'angle du volant
du profil Honda (`HdHondaDAProtocol.parseWheelInfo` : bit 7 à 1 = positif, ÷ 10, × 540 / 520) était lu en entier signé.

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
- **source audio et Bluetooth** (profil LNP002, depuis v152) : comme `CanBusService.sendMediaInfo` pour un
  protocole qui les gère (`isSupportMediaInfo`), la source `0xE1` (`getSource` : ici AUX, `[0x0A, 12 espaces]`,
  1,5 s après le départ puis toutes les 10 s) et l'état Bluetooth `0xCD` (`getBtInfo` : `6` = non connecté, une fois).
  Hypothèse à vérifier : l'écran de la Renault ne transmet les touches du satellite qu'à une « radio » qui lui
  envoie sa source.
- **demande de version** (profil LNP002, depuis v154, test) : la requête Hiworld `0x6A [5, 1, cmd]`
  (`HdProtocol.txRequestCmdId`, `getInquiryInfoPackets`) pour `0xF0`, 3 s après le départ puis toutes les 30 s. Un
  message `F0` aussitôt après chaque demande (hors de son tour d'environ 17 s) montre que le boîtier reçoit
  l'autoradio ; sinon, rien n'arrive jusqu'à lui.
  **Résultat sur la Clio 3 (04/11/2026, v154)** : le boîtier répond à chaque demande en ~90 ms : il **reçoit**
  l'autoradio. Il n'envoie **jamais d'accusé** (`FF`) : ce boîtier LNP002 n'en envoie pas, l'absence de `FF` ne prouve
  rien. Le message `26` est le type de voiture que garde le boîtier, octets inversés (`26: 31 00` pour `[00, 31]` ;
  un type Honda `[40, 02]` envoyé par erreur → `26: 02 40` aussitôt, et `11` D9 = `40`). Touches toujours à 0 : le
  boîtier ne reçoit pas le satellite. Sur la Clio 3 à afficheur déporté, la commande sous volant passe par
  l'afficheur, relié en CAN au connecteur jaune (C1) de la radio (forum Planète Renault) ; le message `14`
  (ordinateur de bord) reste à `FF` : piste, le boîtier n'est pas sur le bus de l'afficheur.
  **Cause trouvée** : l'afficheur (l'horloge du tableau de bord) était débranché, sa place servant à essayer un
  lecteur CD. Sur la Clio 3, la commande sous volant passe par lui : **afficheur débranché = aucune touche** (et
  ordinateur de bord vide). Il peut rester branché ailleurs, caché, s'il faut sa place.
  Depuis v155, la carte *Dialogue avec le boîtier* et l'en-tête de l'export comptent les **demandes auxquelles le
  boîtier a répondu** (message demandé reçu moins de 400 ms après la demande) : « 11 sur 11 (il entend l'autoradio) » ou
  « aucune (rien ne lui arrive) », au lieu d'attendre un accusé que certains boîtiers n'envoient jamais.

Envoi par la trame MCU `10`, que la MCU recopie telle quelle sur la liaison série du boîtier
(`core/.../can/CanBox.kt`, `can/CanBoxTalk.kt`). Seulement quand le service tient la MCU (jamais avec ivi-services,
qui le fait déjà). Onglet CAN, carte **Dialogue avec le boîtier** : interrupteur, accusés envoyés, messages du
profil envoyés (octets, heure), commandes accusées par le boîtier. Journal `LibreHU-CAN` : « box link … »,
« → box: … », « box acknowledged 0x24 ».
**Export CAN** (onglet CAN → exporter) : une ligne d'en-tête « Dialogue with the box » (en marche ou non, accusés
envoyés, messages du profil avec leurs octets et leur heure, commandes accusées par le boîtier) et, entre les messages
du boîtier, chaque envoi de l'autoradio marqué `→` (accusés, type de voiture, vitesse `MCU 0F 00 xx`). Un boîtier
Hiworld accuse le type de voiture par `FF: 24` ; s'il n'apparaît jamais, les envois n'atteignent pas le boîtier. La
vitesse du boîtier est envoyée au départ du dialogue puis 5 s plus tard (l'appli d'origine rouvre le port 5 à 10 s
après sa connexion).

**TWRP** (recovery LibreHU, dépôt `android_device_alps_ac8257_demo`, `tools/touchfix`) fait de même pour lire les
touches du volant hors d'Android, sans réglage à lui : le service écrit **`/sdcard/TWRP/ujc201_can.conf`** d'après le
profil choisi, à chaque changement (`core/.../can/TwrpCanConf.kt`, en root si la permission de stockage manque ; carte
**TWRP** de l'onglet CAN : ce qui est envoyé, bouton « Réécrire pour TWRP »). Lignes : la trame de la famille (`frame
<en-tête>`, `order lc|cl`, `lenoff <n>`, `sum <sum|xor> <1|2> <add> <xor> <champs hlcd>`, `ackcmd <cmd>` ou `ackraw
<octets>`, `noack <cmds>`, `baud <bit/s>` ; les familles intégrées Hiworld `5A A5` / `AA 55`, Raise `2E`, Raise `FD`
sont ramenées à cette description, `TwrpCanConf.framing`), `ack 1`, `send <premier ms> <période ms> <octets>` (trame
déjà faite, 32 octets au plus), `keys <cmd> <octet code> <octet état> <valeur appui> <octet événement> <masque> [<masque
du code>]` (hex, `FF` = aucun), et `key <code> <action> <nom>` pour chaque touche nommée du profil qui a une action dans
TWRP (`TwrpCanConf.action` : volume +/- → volume, mute / OK / décrocher → valider, précédent / suivant → haut / bas,
gauche / droite, mode / raccrocher → retour, navigation → accueil). TWRP jusqu'à la build 13 ne lit que la trame
Hiworld.

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

## Brouillons tirés de l'appli Car Info (outil en cours)

Le dépôt des fiches a un outil, [`tools/carinfo/`](https://github.com/LibreHU/LibreHU-car-profiles/tree/main/tools/carinfo),
qui lit le listage jadx « fallback » de l'appli Car Info d'origine (jamais exécutée) et rejoue son code dans un petit
interpréteur : quel décodeur pour quelle voiture, quels messages, quels octets, quelle formule. État au 06/11/2026 :
décodeur trouvé pour 6 658 des 6 970 entrées du catalogue (532 décodeurs), mêmes résultats que les analyses à la main
pour PSA (111 / 111) et Volkswagen (174 / 174) ; sondage : 517 des 532 décodeurs donnent des messages (4 526). Pas
encore fait : l'écriture des fiches (famille de boîtier ou
`framing`, rôles, touches, type de voiture, accusés) et leur vérification par `librehu-car-tool.jar check`. Les fiches
qui en sortiront seront marquées **non testées** tant que personne ne les a essayées sur la voiture.
