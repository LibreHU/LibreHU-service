# 12. CAN : app `ivi-canbus` (`com.can.activity`) et véhicule de l'utilisateur

Analyse statique de `ivi-canbus.apk` (`com.can.activity` 1.0.3227, uid system) [A], croisée avec le dump et les
relevés de l'appareil (`can_config.json`, `getprop`, logcat) [V].

## 12.1 Architecture [A]
- Framework tiers **« AutoAI »** : un décodeur par marque et par fabricant de boîtier CAN (Raise/RZC,
  Hiworld, Simple, Oudi, Binary, Bagoo, XBS…), ≈ 4 000 classes, base des véhicules dans `assets/canbus.db`
  (tables `canbox`, `autobrand`, `models_<marque>`), protocoles dans `assets/protocol/<boîtier>/<marque>.xml`.
- **Transport sur AC8257 : via ivi-services**, pas en série directe. L'app s'abonne aux trames `10` de la MCU
  (`ICar.registerPassthroughDataCallback`, transaction 7) et envoie avec `ICar.sendPassthroughData`
  (transaction 20). La MCU recopie les octets sur son USART1, relié au boîtier CAN (mcu_firmware.md §10).
- **Vitesse du boîtier** : `ICar` → MCU `0F 00 idx` (idx dans 9600, 19200, **38400**, 57600, 115200, 230400,
  460800, 921600).
- **Sorties** :
  - touches au volant → code `IVIKey` → `ICar.sendKeyToMcu(code)` (transaction 54) → `IVICore.postKey` ;
  - marche arrière CAN → broadcast `action_backcar_notification` ;
  - données (portes, clim, radar, ordinateur de bord…) → ses propres écrans et popups, et le fournisseur
    `content://com.autoai.canbus.provider` (exporté) ;
  - diffusion des touches par le broadcast `ACTION_KEY_EVENT` (extra `keyEvent`).
- Choix du véhicule : `/jancar/config/can_config.json` (`strCanIds`), recopié dans des propriétés
  `persist.*` ; icônes dans `persist.sys.canbus_icons`.

## 12.2 Véhicule configuré sur cette unité [V]
`strCanIds = [4, 1, 1, 22, 14, 0]` = boîtier, config, année, modèle, série :

| Champ | Valeur | Signification (`canbus.db`) |
|---|---|---|
| boîtier 4 | `hiworld` (canbox_mainid 2) | boîtier **Hiworld** (威驰) |
| série 14 | `renault` | **Renault** |
| modèle 22 | `CLIO3`, années `2005~2014`, config `全系` (toutes) | **Clio 3** |
| protocole | `LNP002_雷诺全兼容 V2.1.2_2022.10.26` | Hiworld « Renault full compatible » |

Confirmé par le logcat : `HdRenaultProtocolLNP002.buildCarTypePacket: seriesID = 0x0e, modelID = 0x16,
configID = 0x01, buildCarType = 0x00`. Version du boîtier : `persist.jancar.canversion = H1N5LNF63C-250906`.

## 12.3 Trame Hiworld [A]
```
5A A5 LEN CMD D0 .. Dn-1 CS        LEN = n        CS = ((LEN + CMD + D0 + .. + Dn-1) & 0xFF) - 1
ACK : 5A A5 01 FF <cmd> CS
```
Dans le décodeur, les index de champ sont comptés **depuis le début de la trame** (D0 = octet 4).

## 12.4 Trames reçues du boîtier (Renault LNP002) [A]
| CMD | Longueur min | Contenu |
|---|---|---|
| `0x11` | 10 | octet 4 : bit0 ACC, bit1 feux (ILL), bit2 marche arrière, bit3 frein de parking, bit7 mute SOS ; octet 6 & 0x1F : **touche volant**, octet 7 : état de la touche ; octets 10-11 : angle de volant (signé, gros-boutiste, ÷10, ±540°) |
| `0x12` | 10 | octet 6 : portes (bit7 AVG, bit6 AVD, bit5 ARG, bit4 ARD, bit3 coffre) |
| `0x13` | 10 | octets 12-13 : inclinaison / dévers (ordinateur de bord) |
| `0x14` | 14 | conso moyenne (÷10), vitesse moyenne (÷10), kilométrage total (24 bits ÷10), temps de conduite, carburant, kilométrage « vert » |
| `0x21` | 2 | touches de façade |
| `0x22` | 2 | molette (volume) |
| `0x23` | 2 | sélection du panneau de clim |
| `0x31` | 12 | **clim** : AC, recyclage, dégivrage AV/AR, mode de soufflage, vitesse (max 8), températures G/D (×0,5 °C ; 254 = LO, 255 = HI ; 16,5..30,5), **température extérieure** (octet 15 × 0,5 − 40) |
| `0x41` | 12 | **radar** AR (G, CG, CD, D) puis AV (4 capteurs), valeur 0..4 inversée (4 − v), octet 12 : affichage |
| `0x48` | 19 | pression des pneus (TPMS) |
| `0x61`, `0x62` | 12 | réglages véhicule (`HdCarSet`), 0x62 : échéance d'entretien |
| `0xE8` | 0 | vue panoramique (AVM), si le véhicule la supporte |
| `0xF0` | 17 | version du boîtier (17 octets ASCII) |

**Touches volant** (octet 6 & 0x1F) : 1 volume +, 2 volume −, 3 mute (décroche / raccroche pendant un appel),
4 navigation, 8 droite, 9 gauche, 10 mode, 13 précédent (long : recherche), 14 suivant (long : recherche),
15 OK, 16 téléphone (décrocher / raccrocher), 37 répétition, 49 accueil. Les codes 7 et 24 sont mal décompilés.

## 12.5 Trames envoyées au boîtier [A]
- Type de véhicule : `buildCarTypePacket` (type 0x00 pour la Clio 3 d'après le logcat ; la trame est probablement
  `CMD 0x24`, comme pour les autres séries, mais cette branche est mal décompilée), répété au démarrage.
- `buildTxPacket(7, v)` → `CMD 0xF2 [0x10, v]`.
- Commandes de clim et réglages (`buildHvacPackets`, `buildCarsetPackets`), infos média, téléphone et heure vers
  l'écran d'origine (`buildMediaInfoPackets`, `buildTimePacket`).

## 12.6 Ce qu'il reste à vérifier sur la voiture
On ne sait pas encore quelles trames la Clio 3 et ce boîtier envoient vraiment (clim, radar et TPMS sont peu
probables sur une Clio 3). **Capture à faire** avec MCU Toolkit (« Capture CAN », qui prend la place de
canbus le temps de la capture), contact mis puis moteur tournant, en appuyant sur chaque commande au volant.

## 12.7 Pour la réécriture
- Remplacer seulement le décodeur utile : trame Hiworld + `0x11` (ACC / marche arrière / feux / touches),
  `0x12` (portes), `0x14` (ordinateur de bord), `0x31` (température extérieure, clim si présente),
  `0xF0` (version).
- Garder le multiplexage : plusieurs clients doivent pouvoir recevoir les trames `10` (aujourd'hui, un seul).
