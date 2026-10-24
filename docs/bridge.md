# Appli téléphone LibreHU (pont `bridge/`)

L'appli **LibreHU-bridge** du téléphone ([github.com/LibreHU/LibreHU-bridge-app](https://github.com/LibreHU/LibreHU-bridge-app))
règle l'autoradio et les applis LibreHU et lit ses valeurs (OBD, batterie…), sur le téléphone et dans Android Auto.
**C'est LibreHU-service qui décrit les pages de réglages** : l'appli du téléphone les dessine sans les connaître, donc
un nouveau réglage n'a pas besoin d'une mise à jour du téléphone.

## Liaison

- **Connexions** : 4 au plus en même temps (Wi-Fi et Bluetooth) ; une connexion qui n'a pas fini l'échange de clés
  et l'association en 80 s (confirmation Bluetooth comprise) est fermée, pour qu'aucune ne garde une place.
- **Wi-Fi** : TCP port **47800**, quand le téléphone et l'autoradio sont sur le même réseau (point d'accès du
  téléphone, ou celui de l'autoradio).
- **Bluetooth** : RFCOMM, UUID `6c1b2e6a-8f0e-4b8c-9a51-1ebd2d4c0b01` (téléphone appairé en Bluetooth).
- Interrupteur et association : Bluetooth → Téléphone → carte **Appli téléphone LibreHU**.

## Sécurité (`core/.../bridge/BridgeChannel.kt`, même fichier dans l'appli)

1. Poignée de main : clés éphémères **ECDH P-256** des deux côtés ; l'autoradio signe le résumé (SHA-256) de
   l'échange avec sa clé permanente (**ECDSA P-256**). Le téléphone vérifie cette clé avec l'**empreinte** lue dans
   le QR code (ou gardée à l'association) : personne ne peut se faire passer pour l'autoradio.
2. Clés de session **HKDF-SHA256** (RFC 5869), une par sens ; chaque trame est chiffrée et authentifiée en
   **AES-256-GCM**, nonce = sens + compteur (une trame rejouée, modifiée ou déplacée est refusée).
3. Authentification du téléphone : première fois, **jeton du QR code** (24 octets aléatoires, valable 5 minutes, une
   seule fois) prouvé par HMAC-SHA256 du résumé ; l'autoradio donne alors au téléphone un identifiant et une **clé
   d'accès** de 32 octets, prouvée de la même façon à chaque connexion. Ou, téléphone déjà appairé en Bluetooth,
   **confirmation sur l'autoradio** (notification Autoriser / Refuser, 60 s).
4. Téléphones associés listés dans la carte, **Oublier** retire l'accès. Clé permanente de l'autoradio dans le
   stockage protégé du service (`bridge_identity`).

QR code : `librehu://pair?v=1&fp=<empreinte>&t=<jeton>&n=<nom>&ip=<IPv4,…>&p=47800&bt=<adresse Bluetooth>`.

## API (JSON chiffré)

Requêtes `{"id":n,"m":"<méthode>","p":{…}}`, réponses `{"id":n,"r":…}` ou `{"id":n,"e":"<erreur>"}`, événements
`{"ev":"values","d":…}` (1 par seconde après `subscribe`).

| Méthode | Paramètres | Réponse |
|---|---|---|
| `hello` | — | nom de l'autoradio, niveau d'API, empreinte |
| `pages` | — | liste `{id, title, icon}` (pages du service et des applis LibreHU) |
| `page` | `id` | la page et ses éléments |
| `set` | `page`, `item`, `value` | l'élément mis à jour |
| `action` | `page`, `item` | bouton |
| `values` | — | OBD, valeurs constructeur, batterie, nom |
| `subscribe` | — | active l'événement `values` |
| `key` | `action` (nom de `TouchAction` : `VOLUME_UP`, `NEXT`…) | touche jouée comme une touche de l'autoradio |
| `forget` | — | le téléphone se retire |

**Niveau 2** (`hello` renvoie aussi `bridge: 2`, `features`, `root`, `rootFiles` ; `bridge/BridgeTools.kt`) :

| Méthode | Paramètres | Réponse |
|---|---|---|
| `apps` | — | applis du lanceur : `pkg`, `label`, `system`, `icon` (PNG 96 px en base64) |
| `launch` | `pkg` | appli ouverte sur l'autoradio |
| `reboot` | `mode` : `normal`, `recovery`, `bootloader` (fastboot) | root, 0,5 s après la réponse |
| `system` | — | zones thermiques (`/sys/class/thermal`, SoC et sondes, °C), fréquence de chaque cœur, charge, mémoire, durée de fonctionnement, stockage `/data` et `/sdcard` |
| `gps` | — | satellites visibles et utilisés, C/N0, constellations, fix (âge, précision, vitesse, altitude, temps du premier fix). **Jamais la position** |
| `gps.watch` | `on` | écoute le GPS tant que le téléphone l'affiche (relâché à la déconnexion) |
| `fs.roots` | — | stockage interne, clés USB / cartes SD ; `/` si les fichiers root sont autorisés |
| `fs.list` | `path` | entrées : `name`, `dir`, `size`, `mtime` |
| `fs.read` | `path`, `offset`, `length` (512 Kio au plus) | `data` (base64), `size`, `eof` |
| `fs.write` | `path`, `offset` (0 = nouveau fichier), `data` | `written` |
| `fs.delete`, `fs.mkdir` | `path` | — |
| `fs.rename` | `from`, `to` | — |
| `logs` | `kind` : `logcat`, `kernel`, `librehu` | chemin du fichier écrit dans `/sdcard/LibreHU/logs` (root), à télécharger avec `fs.read` |
| `settings.list` | `ns` : `system`, `secure`, `global` | toutes les clés et leurs valeurs (root) |
| `settings.put` | `ns`, `key`, `value` | directement si permis, sinon en root |
| `settings.delete` | `ns`, `key` | root |

L'événement `values` contient aussi `system` et `gps`.

**Fichiers** : sans réglage, le téléphone ne voit que le stockage interne et les volumes amovibles (chemins absolus,
`..` refusé, liens résolus avant contrôle). L'interrupteur **Tout le système de fichiers pour les téléphones (root)**
(carte Appli téléphone, désactivé par défaut) ouvre tous les dossiers en root ; il ne se règle que sur l'autoradio,
aucune requête ne peut le changer. Les permissions de stockage d'Android 9 sont accordées en root (`pm grant`) à la
première requête de fichiers.

Éléments : `section`, `info` (lecture), `switch`, `slider` (`min`, `max`, `step`, `unit`), `choice` (`options` :
`{v, l}`), `button` (`confirm`). Pages du service : Autoradio (volume média ; luminosité, extinction de l'écran,
écran toujours allumé, taille du texte, animations ; heure et fuseau automatiques, format 24 h ; Wi-Fi, Bluetooth,
localisation, mode avion ; débogage USB ; redémarrage normal, recovery, fastboot), Son LibreHU (menu son, son moteur), Lumières (rétroéclairage), OBD et batterie (alarmes).

**Association depuis le launcher** : `content://org.librehu.service.bridgepair` (`bridge/BridgePairProvider.kt`),
`call()` seulement, réservé aux applis qui ont la permission de signature `org.librehu.permission.BRIDGE`. Le
fournisseur la vérifie lui-même, car Android 9 ne contrôle pas les permissions d'un fournisseur pour `call()`
(`ContentProvider.Transport.call`). Méthodes :
- `pair` : texte du QR code, empreinte et fin de validité ; allume le serveur ;
- `cancel` : ferme le QR code ;
- `answer` : demande Bluetooth (`arg` = id, extra `allow`) ;
- `enable` : extra `on` ;
- `state` : `enabled`, `connected`, `devices`, `requests`, `request_names`, `until`.

L'assistant de premier démarrage de LibreHU Launcher s'en sert pour son QR code d'association et les demandes
Bluetooth.

**Pages des autres applis LibreHU** : un fournisseur de contenu `<paquet>.bridge`, protégé par la permission de
signature `org.librehu.permission.BRIDGE`, répond à `call("pages")` (Bundle `json` = tableau de pages, même format),
`call("set", "<page>/<élément>", {value})` et `call("action", …)` (`bridge/BridgePages.kt`).
