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
- **Jour / nuit selon les phares** (active par defaut) : `ACTION_SET_NIGHT_MODE` `state` = night / day a chaque
  changement des phares et a chaque nouvelle session ; desactive = `auto` (reglage d'Open Headunit).
- **Reglages d'Open Headunit dans LibreHU-service** : `ACTION_GET_SETTINGS` (diffusion ordonnee, reponse JSON) puis
  edition par type (interrupteur, nombre, texte) et `ACTION_SET_SETTINGS` d'une seule cle (format
  `open-headunit-settings` version 1). Avec Open Headunit d'origine il faut activer « Autoriser la configuration
  externe » dans ses reglages ; le fork l'accepte des applis LibreHU (permission signature
  `org.librehu.headunit.permission.CONFIGURE`, demandee par LibreHU-service). Les identifiants Wi-Fi ne sont jamais
  exportes par Open Headunit.

## Deja couvert ailleurs
- Port USB en mode hote : Avance → Autochips.
- Touches au volant et tactiles : touches media, recues par la session media d'Open Headunit pendant la lecture.
- Vue arriere : fenetre par-dessus tout, donc par-dessus Android Auto.
- Gain du micro : onglet Audio (HAL audio, donc aussi pour Android Auto).

## Limites
- Si Open Headunit LibreHU est installe apres LibreHU-service, Android 9 n'accorde la permission signature qu'a la
  mise a jour suivante du service (ou a sa reinstallation).
- Le sans-fil depend d'Android Auto sur le telephone (voir le README d'Open Headunit) ; le filaire USB est le plus
  fiable.
