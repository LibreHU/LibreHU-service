# Vue de recul intégrée

`car/ReverseView.kt` (fenêtre, Camera2, mouvement), `car/ReverseGuideView.kt` (lignes, radars), `car/ReverseSounds.kt`
(sons), réglages dans **Véhicule → Recul** (`ui/ReverseScreen.kt`).

## Principe
Quand la marche arrière est engagée (entrée MCU / GPIO, après l'option « inverser »), `ReverseCamera` montre une fenêtre
`TYPE_APPLICATION_OVERLAY` plein écran (permission « afficher par-dessus les autres applis ») avec une `SurfaceView`
alimentée par Camera2 (`car/CameraSurfaceView.kt`) : la caméra dessine dans une `SurfaceTexture` que la vue recopie en
GLES 2 sur son propre fil, avec la même matrice qu'une `TextureView` (proportions, rotation, miroir, retournement, zoom,
décalage), dans une couche à part composée par SurfaceFlinger. Avec une `TextureView`, toute la fenêtre (lignes de
guidage comprises) était recomposée à chaque image de la caméra
([TextureView](https://developer.android.com/reference/android/view/TextureView),
[SurfaceView et GLSurfaceView](https://source.android.com/docs/core/graphics/arch-sv-glsv)). Les caméras en plus ont
chacune la leur, au-dessus de la principale (`setZOrderMediaOverlay`) pour l'incrustation. Ni ivi-services ni l'appli DVR de Jancar (`com.jancar.hd2cam`) ne sont nécessaires. Le recul rapide
de l'ARM2 (avant le démarrage d'Android) reste celui d'Autochips.

Permissions : **Caméra** (bouton « Autoriser la caméra ») et **affichage par-dessus les autres applis**.

## Caméra
- **Format de la caméra** (en tête de la page Recul, pour tous les modes) : CVBS (analogique, PAL / NTSC détecté
  seul), AHD 720p 25 / 30 i/s, AHD 1080p 25 / 30 i/s, comme `BackCarVideoSource` de `ivi-customer.ini` ; écrit dans la
  metazone (root) et appliqué au redémarrage. Un mauvais format donne une image absente, qui défile ou scintille.
- Ids des caméras, d'après les applis Jancar (`BackCarCommon`) : `3` CVBS / AV arrière, `2` AHD arrière, `10` AHD en
  mode DVR double enregistrement (`CameraType=0`), `8` AHD avec la puce 9963, `9` avant (9963), `11` avant / 360,
  `6` / `7` 360 gauche / droite, `0` caméra de façade (`[CameraIndex]` d'`ivi-config.ini`). La liste des réglages
  affiche ce rôle à côté de chaque id, ainsi que les ids que Camera2 accepte sans les lister (0 à 15).
- Automatique : comme Jancar, `3` en premier si le format est CVBS, sinon `2`, `10`, `8` puis `3`, puis les caméras qui
  ne sont pas avant.
- Caméra CVBS (3) : le commutateur vidéo est d'abord mis sur l'entrée de la caméra arrière (GPIO 167 / 165 = 1 / 1
  comme Jancar), l'écran CVBS IN pouvant l'avoir laissé sur une autre entrée ; réglable dans Véhicule → Vidéo, voir
  [video.md](video.md).
- Une caméra n'a qu'un utilisateur : si le DVR enregistre, l'ouverture échoue (« caméra utilisée »). Réglage **Arrêter le
  DVR** (root : `am force-stop com.jancar.hd2cam` avant l'ouverture).
- Image : miroir, retournement, rotation 0 / 90 / 180 / 270, remplir l'écran (recadrage) ou image entière, zoom
  50-300 %, décalages. Taille de flux la plus proche de 1280×720.
- Format de la caméra (CVBS, AHD 720p / 1080p, 25 / 30 i/s) : réglé sous Android, dans la metazone, lu au démarrage
  (Avancé → Autochips, [atc.md](atc.md#format-de-la-caméra)). La ligne d'info montre les images par seconde reçues.

## Caméra USB (UVC)

Une webcam / caméra de recul **USB** (classe vidéo UVC) peut être la caméra principale ou une caméra
supplémentaire. Le pilote `uvcvideo` est intégré aux noyaux LibreHU et Jancar (`CONFIG_USB_VIDEO_CLASS=y`) : la
caméra apparaît en `/dev/videoN`, lue directement en **V4L2** (`cpp/librehu_uvc.c` : tampons mmap, **MJPEG** décodé
par `BitmapFactory`, sinon **YUYV** converti en ARGB) et dessinée dans la vue de recul (`car/UvcCameras.kt`). Elle est
listée « USB · nom (port 1-1:1.0) » : elle est retrouvée par son **port USB**, la garder sur le même. Le nœud est
rendu accessible par le root s'il le faut (`chmod 666`, puis une règle SELinux pour le domaine de l'appli avec
`magiskpolicy`, `supolicy` ou `ksud sepolicy patch`). Pas d'analyse de mouvement sur une caméra USB (un seul flux).
Demande l'autoradio en **USB hôte** (Avancé → Autochips).

## Plusieurs caméras

Carte **Caméras supplémentaires** : jusqu'à 3 caméras de plus (latérales, deuxième caméra arrière, USB, CVBS IN…),
chacune en miroir ou non, et l'affichage :
- **Une à la fois** (par défaut) : un toucher sur l'image passe à la caméra suivante ; une seule caméra ouverte,
  comme d'habitude, donc toujours possible.
- **Côte à côte** : deux moitiés, ou une grille 2 × 2 à partir de trois caméras (la principale en haut à gauche).
- **Incrustée** : la principale en plein écran, les autres en petit en bas à gauche.

Les lignes de guidage, la trajectoire et l'analyse de mouvement restent sur la caméra principale. Ouvrir plusieurs
caméras en même temps dépend du service caméra (HAL) de l'autoradio : si une caméra supplémentaire ne s'ouvre pas,
son erreur s'affiche dans sa case ; choisir alors « Une à la fois ».

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

## Widget d'écran d'accueil (image en direct)
- **Avec LibreHU Launcher : vrai flux.** Le launcher reconnaît le widget (`org.librehu.service/.car.ReverseWidget`)
  et pose par-dessus sa propre `TextureView`. Il se lie à `car/RearPreviewService.kt` (action
  `org.librehu.service.REAR_PREVIEW`, Messenger, applis signées avec la clé LibreHU seulement), demande la taille de
  tampon (`MSG_QUERY` → `MSG_INFO` : taille d'aperçu de la caméra, miroir, rotation, zoom, décalage), règle sa
  `SurfaceTexture` dessus et envoie sa `Surface` (un `Parcelable`, `MSG_ATTACH`). Le service ajoute cette surface
  comme sortie Camera2 : la caméra dessine directement dans la vue du launcher, à sa cadence normale, sans copie
  d'image. Le launcher retire la surface quand le tableau de bord n'est plus affiché (surface détruite) ; la vue
  plein écran la met en pause (`MSG_STATE`), le widget redevient visible dessous pendant ce temps.
- **Autres launchers : images.** Un widget Android n'affiche que des `RemoteViews` : pas de surface vidéo possible. `car/ReverseWidgetLive.kt` lit
  donc de petites images de la caméra (`ImageReader` YUV, la plus petite taille ≥ 400 px de large, réduite à ~400 px),
  les convertit en bitmap (BT.601, miroir / retournement / rotation des réglages) et les envoie au widget
  (`partiallyUpdateAppWidget`), 10 par seconde par défaut (5 à 20).
- Quand : **avec LibreHU Launcher** (par défaut), seulement quand son tableau de bord est affiché (il envoie
  `org.librehu.action.HOME_VISIBLE` au service, extra `visible`), écran allumé ; la caméra est libre le reste du
  temps. **Écran allumé** : pour un autre launcher (qui ne prévient pas), la caméra reste ouverte tant que l'écran
  est allumé. **Désactivée** : simple bouton.
- La vue arrière plein écran reprend la caméra : l'image du widget s'arrête et la caméra est fermée avant que la vue
  ne l'ouvre ; elle reprend à la fermeture. Si une autre appli prend la caméra (DVR Jancar…), nouvel essai toutes
  les 15 s. Le widget garde la dernière image.
- Un toucher ouvre ou ferme toujours la vue arrière plein écran.

## Limites connues
- Widget, autres launchers : les images passent par Binder, quelques images par seconde, pas 30 i/s ; si la caméra
  AHD ne fournit pas de sortie YUV, le widget reste un bouton. Avec LibreHU Launcher, le flux dans une surface d'un
  autre processus est le fonctionnement normal de Camera2 (la caméra dessine toujours dans des surfaces d'applis),
  mais reste à vérifier sur l'unité.
- Non testé sur l'unité au moment de l'écriture : l'id de caméra, le double flux et le sens des radars sont à vérifier.
- Android 9 refuse la caméra aux applis inactives ; le service LibreHU est un service au premier plan, ce qui suffit en
  principe (à confirmer sur l'unité).
