# Dalle tactile : calibration et touches de façade

Onglet **Dalle tactile** de l'app LibreHU. Les deux fonctions demandent le **root** (`su`) : le nœud de calibration
et le périphérique d'entrée brut ne sont pas accessibles à une app normale.

## Matériel

- Dalle Goodix GT9xx, périphérique d'entrée `mtk-tpd` (`/dev/input/event2` sur l'UJC201, retrouvé par nom dans
  `/proc/bus/input/devices`).
- Matrice appliquée par le pilote : `/sys/devices/platform/touch/gt9xx_props`, texte `A B C D E F Div` :
  `x' = (A·x + B·y + C) / Div`, `y' = (D·x + E·y + F) / Div`.
- Identifiant de dalle : 19 premiers caractères de `/proc/gt9xx_config`.
- ivi-services relit `/jancar/config/pointercal.xml` à chaque démarrage (`<node touch="ID"><item id="A">…`) et
  écrit la matrice dans le pilote ; l'appli d'usine de Jancar (`TouchCheckActivity`) écrit d'abord l'identité
  `1 0 0 0 1 0 1` puis le résultat avec `Div = 65536`.

## Calibration

1. 5 cibles (4 coins à 10 % des bords + centre), comme l'outil Jancar.
2. Pour chaque appui : coordonnées **pilote** (lues sur le périphérique brut) et coordonnées **écran** vues par
   Android (`MotionEvent`).
3. `TouchCalibration.solve` mesure la correspondance pilote → écran d'Android (rotation `persist.sf.hwrotation`
   comprise), retrouve les valeurs brutes de la dalle via la matrice en vigueur, et calcule la nouvelle matrice par
   moindres carrés. L'erreur maximale en pixels est affichée.
4. **Essai 20 s** : la matrice est appliquée, des points montrent où arrivent les appuis ; sans « Garder », retour
   automatique à l'ancienne (une calibration ratée ne peut pas rendre l'écran inutilisable).
5. « Garder » : matrice mémorisée par LibreHU et réappliquée à chaque démarrage du service ; sauvegarde de la
   matrice d'origine (bouton « Restaurer la précédente ») ; option pour écrire aussi `pointercal.xml`.

Option « Partir d'une matrice neutre » : si la calibration actuelle est trop fausse pour toucher les coins.

## Touches de façade

Les boutons sérigraphiés autour de l'écran sont des zones de la dalle **hors de l'affichage** : Android ignore ces
appuis, il faut lire `/dev/input/eventN` directement (`su -c cat`). Chaque zone : position brute, rayon, action
d'appui court, action d'appui long **ou** répétition tant qu'on appuie (volume).

Actions : accueil, retour, applis récentes, volume ± / muet (puce audio via le service, sinon volume média
d'Android), lecture / pause, suivant, précédent, éteindre l'écran, ouvrir une appli, code de touche Android
quelconque. Retour / récentes / écran / code de touche passent par `input keyevent` en root (`INJECT_EVENTS` est
une permission système).

Pour ajouter une touche : activer la lecture, appuyer sur le bouton de façade, « Ajouter le dernier appui », choisir
l'action, « Tester ».

**Conflit** : ivi-services gère aussi ces zones (`/jancar/config/touch_key.xml`). Tant qu'il tourne, laisser l'option
désactivée ou vider ses zones, sinon chaque appui déclenche deux actions.
