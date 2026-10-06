# Contact coupé, veille et extinction (`power/Standby.kt`)

Réglages : onglet **MCU → Coupure du contact et veille**. Rien n'est encore testé sur l'UJC201.

## Ce que fait la MCU (firmware 2024.08.09)

D'après l'analyse du firmware ([MCU-tools-app/docs/mcu_firmware.md §5](https://github.com/LibreHU/MCU-tools-app/blob/main/docs/mcu_firmware.md)) :

- le fil ACC est filtré **1 s**, puis la MCU envoie `00 00` (contact coupé) ;
- si le contact revient, `00 01` ; sinon elle coupe le SoC **15 s** après (40 s s'il ne tourne pas) ;
- avec une durée de veille active (`F1 n`, n × **420 min**), elle garde le SoC en suspension, se réveille par sa RTC,
  décompte, et coupe tout si la batterie passe sous le seuil bas (9 V par défaut) ;
- au retour du contact, elle réveille le SoC (impulsion) et attend `1F 01` (PC_READY) **10 s** au plus.

ivi-services envoyait `F1 [minutes / 60 / 6]` (il comptait des tranches de 6 h) avec `global_smartaccoff = 2880`
(48 h), soit `F1 8` = 56 h réelles.

## Délai avant coupure (démarrage du moteur)

Le + après-contact chute pendant que le démarreur tourne. Une coupure de plus d'1 s arrive jusqu'au service : sans
délai, il éteignait l'écran, le son et l'ampli. Le service attend maintenant le délai réglé (3 s par défaut, 0..10 s)
avant de couper l'écran, le son et l'ampli (`HeadUnit.onAccInput`) ; si le contact revient avant, rien n'est coupé.

Deux états sont exposés aux applis :

| État | API | Suit |
|---|---|---|
| contact (`acc`) | `FLAG_ACC`, extra `acc`, `org.librehu.action.ACC` | le fil ACC tel que la MCU l'annonce, tout de suite |
| alimenté (`powered`) | `FLAG_POWERED` (bit 7), extra `powered` | le contact, mais ne passe à « non » qu'après le délai : écran, son, ampli et veille suivent celui-ci |
10 s au plus : la préparation de la veille doit tenir avant la coupure du SoC par la MCU (15 s).

## Modes

| Mode | Après le délai, puis 1,5 s si le contact est toujours coupé |
|---|---|
| Écran éteint seulement (défaut) | rien de plus : la MCU coupe le SoC d'elle-même, démarrage à froid au contact suivant (sauf durée `F1` déjà mémorisée par la MCU) |
| Veille | médias en pause (repris au réveil s'ils jouaient), écran d'accueil, mode avion et GPS coupés (root), `sync`, `F1 n` (7 h à 14 jours), écran éteint (`PowerManager.goToSleep` en appli privilégiée, sinon touche `SLEEP` en root) |
| Extinction | médias en pause, `sync`, `F1 0` : la MCU coupe le SoC |

Réveil (contact revenu, ou suspension détectée par un saut de l'horloge `elapsedRealtime` par rapport à `uptimeMillis`) :
PC_READY renvoyé (sinon la MCU recoupe le SoC au bout de 10 s), écran rallumé, mode avion / GPS rétablis s'ils
avaient été coupés par le service, reprise de la lecture 4 s plus tard.

## À vérifier sur l'appareil

- le SoC entre-t-il vraiment en suspension (aucun wakelock qui la bloque) et en sort-il proprement ?
- consommation en veille (à mesurer sur la batterie) ;
- la MCU garde-t-elle `F1` d'un démarrage à l'autre (onglet Outils MCU, trafic) ;
- le délai de démarrage moteur suffit-il (journal : « ACC back before the off delay: ignored »).
