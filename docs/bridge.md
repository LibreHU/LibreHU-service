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

Éléments : `section`, `info` (lecture), `switch`, `slider` (`min`, `max`, `step`, `unit`), `choice` (`options` :
`{v, l}`), `button` (`confirm`). Pages du service : Autoradio (volume média, luminosité, Wi-Fi, Bluetooth,
redémarrage), Son LibreHU (menu son, son moteur), Lumières (rétroéclairage), OBD et batterie (alarmes).

**Pages des autres applis LibreHU** : un fournisseur de contenu `<paquet>.bridge`, protégé par la permission de
signature `org.librehu.permission.BRIDGE`, répond à `call("pages")` (Bundle `json` = tableau de pages, même format),
`call("set", "<page>/<élément>", {value})` et `call("action", …)` (`bridge/BridgePages.kt`).
