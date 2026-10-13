# Vue de recul intégrée

`car/ReverseView.kt` (fenêtre, Camera2, mouvement), `car/ReverseGuideView.kt` (lignes, radars), `car/ReverseSounds.kt`
(sons), réglages dans **Véhicule → Recul** (`ui/ReverseScreen.kt`).

## Principe
Quand la marche arrière est engagée (entrée MCU / GPIO, après l'option « inverser »), `ReverseCamera` montre une fenêtre
`TYPE_APPLICATION_OVERLAY` plein écran (permission « afficher par-dessus les autres applis ») avec un `TextureView`
alimenté par Camera2. Ni ivi-services ni l'appli DVR de Jancar (`com.jancar.hd2cam`) ne sont nécessaires. Le recul rapide
de l'ARM2 (avant le démarrage d'Android) reste celui d'Autochips.

Permissions : **Caméra** (bouton « Autoriser la caméra ») et **affichage par-dessus les autres applis**.

## Caméra
- Automatique : id `10` (caméra AHD arrière de `ivi-config.ini`, celle du DVR), puis `2`, `8`, `3`, puis les caméras
  qui ne sont pas avant. La liste des réglages montre aussi les ids que Camera2 accepte sans les lister (0 à 15).
- Une caméra n'a qu'un utilisateur : si le DVR enregistre, l'ouverture échoue (« caméra utilisée »). Réglage **Arrêter le
  DVR** (root : `am force-stop com.jancar.hd2cam` avant l'ouverture).
- Image : miroir, retournement, rotation 0 / 90 / 180 / 270, remplir l'écran (recadrage) ou image entière, zoom
  50-300 %, décalages. Taille de flux la plus proche de 1280×720.
- Format de la caméra (CVBS, AHD 720p / 1080p, 25 / 30 i/s) : réglé sous Android, dans la metazone, lu au démarrage
  (Avancé → Autochips, [atc.md](atc.md#format-de-la-caméra)). La ligne d'info montre les images par seconde reçues.

## Lignes de guidage
Un rectangle au sol derrière la voiture (largeur de la voiture × longueur choisie) dessiné en perspective : on place ses
4 coins à l'écran (centre, hauteur et largeur du côté proche et du côté éloigné), ce qui donne l'homographie sol → écran
(`Matrix.setPolyToPoly`). Repères rouge / orange / jaune / vert aux distances choisies.

**Calibrage** : ouvrir l'**Aperçu** (quart haut droit de l'écran, mêmes proportions que l'écran), garer la voiture face à
un mur ou poser deux repères au sol (pare-chocs + longueur choisie, ex. 3 m), ajuster les curseurs.

**Trajectoire** : avec l'angle du volant de la box CAN (`11.steering_angle`, Hiworld 0x11), angle roue = angle volant
/ démultiplication, rayon de l'essieu arrière = empattement / tan(angle roue), arcs pour les deux côtés. Approximation
(caméra supposée sur l'essieu, pas de dérive) : guide visuel, pas une mesure. « Volant inversé » si les lignes partent
du mauvais côté.

## Radars, mouvement, sons
- **Radars** : `41.rear_left`, `41.rear_center_left`, `41.rear_center_right`, `41.rear_right` (Hiworld 0x41, 0 à 4),
  dessinés en arcs derrière une voiture, bips de plus en plus rapides (continu au niveau 4). « Niveaux inversés » si le
  niveau le plus loin s'allume sans obstacle (l'ordre des valeurs de la box n'est pas vérifié sur une Clio).
- **Mouvement** : flux YUV réduit (`ImageReader`), luminance moyenne de 32×18 cases comparée à l'image précédente
  (médiane retirée pour l'exposition), seuil selon la sensibilité ; les cases sous la limite de zone sont surveillées,
  en rouge à l'écran, bip d'alerte (au plus toutes les 1,5 s). Ignoré si plus de la moitié de l'image change (voiture
  qui roule). Si la caméra refuse deux flux, la vue continue sans détection.
- **Approche (expérimental, désactivé par défaut)** : le point le plus bas en mouvement qui descend dans l'image
  (≥ 6 % en 1,2 s) donne des bips plus rapides. Pas de mesure de distance : les radars sont plus fiables.
- **Sons** : à l'engagement (aucun, bip, double bip, carillon, fichier audio choisi), volume ; musique inchangée,
  baissée (focus audio « duck ») ou en pause pendant le recul.

## Limites connues
- Non testé sur l'unité au moment de l'écriture : l'id de caméra, le double flux et le sens des radars sont à vérifier.
- Android 9 refuse la caméra aux applis inactives ; le service LibreHU est un service au premier plan, ce qui suffit en
  principe (à confirmer sur l'unité).
