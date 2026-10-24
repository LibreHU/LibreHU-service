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
| `gps` | — | satellites visibles et utilisés, C/N0, constellations, signal de chaque satellite (`sats` : `c`, `svid`, `cn0`, `used`), fix (âge, précision, vitesse, altitude, temps du premier fix). **Jamais la position** |
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

Éléments : `section`, `info` (lecture), `switch`, `slider` (`min`, `max`, `step`, `unit`, `display` = la valeur
écrite comme sur l'autoradio : « +4 dB », « 2.5 s »), `choice` (`options` : `{v, l}`), `text` (`max` caractères),
`button` (`confirm`, `hint` = le texte de la confirmation). Une page est construite à la demande (la liste `pages`
ne contient que `id`, `title`, `icon`) ; l'appli la redemande entière après chaque changement, pour que les options
qui dépendent d'une autre (mode, interrupteur) apparaissent ou disparaissent.

**Pages du service** (`bridge/BridgeSettings.kt`) : mêmes libellés (mêmes chaînes, donc mêmes langues) et mêmes
réglages que les écrans de l'autoradio : chaque élément lit et modifie ce que l'écran lit et modifie.

| Page | Contenu |
|---|---|
| Autoradio | volume média ; luminosité, extinction, écran toujours allumé, taille du texte, animations ; heure, fuseau, 24 h ; Wi-Fi, Bluetooth, localisation, mode avion ; adb ; redémarrages |
| Audio | volume, muet, source (Android / AUX), mixage AUX, micro (gain, ECNR), graves / médiums / aigus, loudness, balance / fader, caisson et niveau, ampli externe, panneau de volume (style, bord, décalage, marge, taille, opacité, durée, chiffre, toucher), menu son (canaux, retour, durée, test) |
| Véhicule | nom de la voiture, fiche (marque, modèle, année, couleur, plaque, énergie, côté conducteur), entrées inversées, clignotants GPIO, touches volant du boîtier CAN (activation, ignorer après contact, appui long), touches tactiles |
| Recul | activation, mode, sortie, délai, test ; caméra, miroir, retournement, rotation, remplissage, zoom, décalages, DVR, délai de fermeture, infos ; caméras en plus (miroir, disposition) ; widget ; lignes de guidage (toutes) ; son d'enclenchement, volume, médias ; radar, détection de mouvement, approche |
| Vidéo | positions CVBS IN 1 / 2, frein à main, caméra, commutation au recul, sortie vidéo |
| OBD | activation, état, adaptateur (Bluetooth / USB, appareil, débit), reconnexion, valeurs lues, widget (emplacements), codes défaut (lire, effacer), voiture ; batterie (alarmes, seuils, son, correction) ; son moteur |
| Bluetooth | activation, reconnexion, réponse auto, nom, visible ; appairage iPhone ; partage de connexion iPhone |
| Android Auto / CarPlay | intégration (nuit aux phares, données voiture, touches, retour après recul) et **tous les réglages d'Open Headunit** (lus et écrits par ses commandes d'automatisation, chacun selon son type) ; CarPlay (nuit, ouverture sur l'iPhone, basse latence) |
| Écran | mode sombre, luminosité jour / nuit et aux phares, voyants à l'écran, panneau de luminosité |
| Lumières | rétroéclairage des boutons (PWM jour / nuit, sorties séparées) |
| Système | veille (délai, mode, durée, radios, médias), applis au démarrage, démarrage anticipé, watchdog, redémarrage du SoC, extinction sûre, démarrage forcé de la MCU, relance du lien |
| GPS et heure | heure depuis le GPS (intervalle, synchro), depuis / vers la MCU |
| Tweaks | compilation native (auto, tout compiler), préchargement Zygote, réglages noyau (au démarrage, usine, gouverneur et fréquences par cluster, GPU, E/S, swappiness, TCP) |

Restent sur l'autoradio seulement : le format de la caméra (écrit dans la metazone), les couleurs des LED de façade
(sujet en attente), la MCU et ses profils, les diagnostics (outils MCU, diag audio, Autochips, trafic), les
sauvegardes, les fichiers DDT4ALL et les zones tactiles (dessinées sur l'écran).

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
Chaque appli le construit avec le même petit kit (`bridge/BridgeKit.kt`, copié dans chaque appli : pages,
interrupteurs, curseurs, choix, textes, boutons, et la vérification de la permission dans `call()`, qu'Android 9 ne
fait pas). Mêmes libellés et mêmes réglages que les écrans de chaque appli :

| Appli | Pages |
|---|---|
| Launcher | Apparence (thème, style moderne / rétro, police, couleur dont RGB, icônes), Fond d'écran (animations, spectre, internet), Accueil (pages, taille et disposition des widgets, conduite à droite, barre et ses éléments), Tiroir d'applis, Horloge de veille, Verrouillage, SOS, Conduite (météo, limites de vitesse), Pneus (TPMS) |
| Radio FM | la radio (marche, fréquence, stations, recherche, balayage) ; RDS, chemin audio, logos (serveur, pays, bibliothèque) |
| Téléphone (Dialer) | appels (ouverture, fenêtre d'appel entrant, bulle), apparence, widgets |
| Widgets | chaque place des widgets Voyants et Infos, noms, temps de trajet, thème, démo |
| BtnRemap | chaque bouton appris : nom, appui court et long (et l'appli à ouvrir), boutons ignorés au démarrage |
| EasySource | source jouée, sources (marche, ordre), délai, ouverture de l'appli, reprise au démarrage |
| Disc | lecture, pistes ; lecture auto, anti-saut, MusicBrainz, extraction (format, mode sûr, essais, noms, dossiers, tags) |
| LibreHU Apps (OTA) | les applis (version installée et disponible, vérifier, installer, mettre à jour, tout mettre à jour) ; édition, vérifications, installations, /system |
| Helper | sections du rapport, anonymisation, root, rapport écrit dans le dossier de l'appli |
| ViPER4Android | interrupteur général, puis une page par effet avec **tous** ses paramètres, tirés de la liste des effets de l'appli (chaque paramètre avec sa plage ; un nouveau paramètre apparaît de lui-même) ; l'état est enregistré comme par l'appli et le service ViPER le réapplique |
| CarPlay | image (échelle, i/s, HEVC, taille d'écran, barres, conduite à droite), son (tampon, micro, canaux), CarPlay sans fil et point d'accès, puce MFi, identité de la voiture, démarrage, journaux |

Restent sur l'autoradio : les fichiers choisis sur place (images et vidéos du fond d'écran, préréglages et fichiers DDC
/ convolution de ViPER, dossier d'extraction du Disc), les secrets (jeton GitHub, jeton MFi distant, mot de passe du
point d'accès CarPlay) et ce qui se dessine à l'écran (zone sûre de CarPlay).
