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

Les broadcasts (`org.librehu.action.VEHICLE_STATE`, `ACC`) partent sous la permission de signature
`org.librehu.permission.VEHICLE` : seules les applis signées avec la clé LibreHU les reçoivent.

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

## Journal de veille (`power/StandbyJournal.kt`)

Onglet **Système → Veille → Journal de veille**. Chaque étape est écrite dans
`/data/user_de/0/org.librehu.service/files/standby.log` (plus `standby.1.log`, 256 Kio chacun) et forcée sur le
stockage à chaque ligne (`fsync`) : elle survit à la coupure du SoC par la MCU. Chaque ligne porte l'heure, `up=`
(uptime, arrêté pendant la suspension) et `boot=` (temps depuis le démarrage, qui continue).

| Ligne | Sens |
|---|---|
| `Ignition off (MCU)` / `Ignition on (MCU)` | le fil ACC tel que la MCU l'annonce, tout de suite : départ du minutage `t+` |
| `Powered off after the engine-start delay (t+3.0 s)` | fin du délai de démarrage moteur |
| `Standby: SLEEP …`, `Media paused…`, `Radios off`, `MCU sleep timer F1 n sent`, `Screen off … preparation done` | chaque étape avec son `t+` ; `LATE` si la préparation finit après les 15 s de la MCU |
| `Still running N s after standby, SoC suspended X s` | le SoC ne dort pas (30 s, puis chaque minute pendant 10 min, puis toutes les 10 min) ; au premier, les wakelocks |
| `SoC resumed after N s suspended: PC_READY` | sortie de suspension (saut `elapsedRealtime` / `uptimeMillis`), suivie des lignes `dmesg` du noyau |
| `Wake up (ignition on) after N s of standby, SoC suspended X s of it` | réveil complet |
| `Previous standby (…) never woke up` | au démarrage suivant : la veille n'a pas été suivie d'un réveil, la MCU a coupé le SoC (ou il a redémarré) |
| `Service start`, `Boot reason`, `Previous kernel log (pstore)` | à chaque démarrage du service : raison du démarrage et fin du journal noyau précédent (root) |

Instantanés root : `dumpsys power` (wakelocks Android), `/sys/power/wake_lock`, sources de réveil actives
(`/sys/kernel/debug/wakeup_sources`, `active_since > 0`), `dmesg` filtré (`PM:`, suspend, resume, Freezing, wakeup,
spm), `/sys/power/wakeup_count`, `suspend_stats`.

**Tester la suspension** (contact mis) : écran éteint comme en veille, **sans** prévenir la MCU (ni `F1`, ni radios),
alarme `ELAPSED_REALTIME_WAKEUP` 60 s plus tard ; le journal donne le temps réellement suspendu et un verdict
(« suspended and woke up », « only part of the time », « did NOT suspend » avec les wakelocks).

### Protocole d'essai

1. Système → Veille : mode **Veille**, durée **7 h**, journal **effacé**.
2. Contact mis, **Tester la suspension** : attendre 60 s sans toucher l'écran. Verdict dans le journal.
3. Contact coupé, attendre **2 min**, contact remis : démarrage instantané ou à froid ? Le journal dit si
   la préparation a tenu dans les 15 s, si le SoC s'est suspendu et comment il s'est réveillé.
4. Même essai avec **30 min** de coupure, puis une nuit (consommation : tension batterie avant / après).
5. Mode **Extinction** : contact coupé 1 min, remis : la ligne `Previous standby … never woke up` est attendue.
6. **Exporter** le journal (et Diagnostic → Exporter pour le trafic MCU) et le joindre au ticket.

## À vérifier sur l'appareil

- le SoC entre-t-il vraiment en suspension (aucun wakelock qui la bloque) et en sort-il proprement ?
- consommation en veille (à mesurer sur la batterie) ;
- la MCU garde-t-elle `F1` d'un démarrage à l'autre (onglet Outils MCU, trafic) ;
- le délai de démarrage moteur suffit-il (journal : « ACC back before the off delay: ignored »).
