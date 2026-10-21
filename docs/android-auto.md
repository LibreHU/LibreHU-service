# Android Auto (Open Headunit)

LibreHU-service pilote [Open Headunit](https://github.com/andreknieriem/headunit-revived) (recepteur Android Auto
libre, AGPL-3.0, en appli Android) par son interface d'automatisation (`contract/README.md` d'Open Headunit), et son
fork **Open Headunit LibreHU** (`org.librehu.headunit`, signe avec la cle LibreHU, voir `README_LIBREHU.md` du fork).
Code : `projection/AndroidAuto.kt`, onglet **Android Auto**.

## Ce qui est fait
- **Appli detectee** : le fork en priorite, sinon Open Headunit (`com.andrerinas.headunitrevived`).
- **Session** : diffusion `com.andrerinas.headunitrevived.SESSION_STATE` (extras `state` = connecting / connected /
  projecting / disconnected / failed, `transport` = usb / wifi / self, `reason`), affichee dans l'onglet et sur le
  tableau de bord de LibreHU Launcher.
- **Commandes** (diffusion explicite vers `AutomationReceiver`, ou `LibreHuAutomationReceiver` pour le fork) :
  ouvrir (ou `ACTION_RAISE_PROJECTION` pendant une session), `ACTION_CHECK_USB`, `ACTION_DISCONNECT`,
  `ACTION_RESTART_AUDIO`.
- **Jour / nuit selon les phares** (active par defaut) : a chaque changement des phares et a chaque nouvelle session,
  `ACTION_SET_NIGHT_MODE`. Fork LibreHU : `state=car` + `headlight=on/off`, son mode « Signal des phares (ILL+) »
  (mode nuit d'Android Auto, et theme de l'appli s'il ne suit pas le launcher) suit alors les phares, visible dans ses
  reglages. Open Headunit d'origine : `state` = night / day. Desactive = `auto` (lever / coucher du soleil).
  Au debut d'une session, l'etat est envoye a `connected`, a `projecting` puis encore 1,5 s et 5 s apres (avec l'etat
  de la voiture) : envoye une seule fois, trop tot, des phares deja allumes au demarrage etaient perdus et Android
  Auto restait en jour jusqu'a les eteindre et rallumer.
- **Reglages d'Open Headunit dans LibreHU-service** : `ACTION_GET_SETTINGS` (diffusion ordonnee, reponse JSON) puis
  edition par type (interrupteur, nombre, texte) et `ACTION_SET_SETTINGS` d'une seule cle (format
  `open-headunit-settings` version 1). Avec Open Headunit d'origine il faut activer « Autoriser la configuration
  externe » dans ses reglages ; le fork l'accepte des applis LibreHU (permission signature
  `org.librehu.headunit.permission.CONFIGURE`, demandee par LibreHU-service). Les identifiants Wi-Fi ne sont jamais
  exportes par Open Headunit.

## Pont voiture (fork seulement)
Diffusions vers `org.librehu.headunit`, acceptees seulement d'une appli qui a la permission signature
`org.librehu.headunit.permission.CONFIGURE` (LibreHU-service) ; cote fork : `librehu/LibreHuCar.kt`.
- **Infos de la voiture** (`org.librehu.headunit.action.CAR`, interrupteur « Infos de la voiture vers Android Auto ») :
  frein a main, marche arriere, phares, clignotants (MCU, seulement MCU en ligne) ; vitesse, regime, carburant,
  temperature exterieure (adaptateur OBD connecte). Envoyees a chaque changement (l'OBD environ une fois par seconde)
  et a chaque nouvelle session. Le fork les annonce au telephone comme capteurs Android Auto (`PARKING_BRAKE`,
  `CAR_SPEED`, `RPM`, `FUEL_LEVEL`, `ENVIRONMENT`, `LIGHT`, `GEAR`), chacun coupable dans Android Auto → Reglages →
  **Voiture et touches (LibreHU)** ; le **frein a main** peut ne plus etre transmis du tout (le telephone se fie
  alors a sa propre estimation). Le rapport n'est connu que pour la marche arriere (R / D) : coupe par defaut.
- **Touches** (`org.librehu.headunit.action.KEY`, diffusion ordonnee, interrupteur « Touches vers Android Auto ») :
  pendant une session, lecture / pause, piste suivante / precedente, et les touches « Code touche » media, appel,
  raccrocher, recherche, Assistant vocal (nouvelle action **Assistant vocal** des touches tactiles et du volant) vont
  a Android Auto, meme s'il n'est pas a l'ecran. Le fork repond s'il a pris la touche ; sinon (pas de session, option
  coupee d'un cote ou de l'autre) la touche garde son action habituelle.
- **Retour apres la marche arriere** : si une appli de camera de recul (hd2cam, AVM) a pris l'ecran pendant une
  projection, `ACTION_RAISE_PROJECTION` a sa fermeture. La vue arriere integree est une surcouche : rien a faire.
- **Exporter le journal** : `ACTION_EXPORT_LOG` avec un chemin dans `Download/LibreHU/` (l'appli Android Auto a
  besoin de son autorisation de stockage).

## Deja couvert ailleurs
- Port USB en mode hote : Avance → Autochips.
- Touches au volant et tactiles avec Open Headunit d'origine : touches media, recues par sa session media pendant la
  lecture.
- Vue arriere : fenetre par-dessus tout, donc par-dessus Android Auto.
- Gain du micro : onglet Audio (HAL audio, donc aussi pour Android Auto).

## Limites
- Si Open Headunit LibreHU est installe apres LibreHU-service, Android 9 n'accorde la permission signature qu'a la
  mise a jour suivante du service (ou a sa reinstallation).
- Le sans-fil depend d'Android Auto sur le telephone (voir le README d'Open Headunit) ; le filaire USB est le plus
  fiable.
