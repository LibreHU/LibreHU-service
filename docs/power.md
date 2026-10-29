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
mode avion et GPS coupés si la veille le fait (le pilote GPS garde un wakelock), alarme `ELAPSED_REALTIME_WAKEUP`
60 s plus tard par un `PendingIntent` (qui survit à un redémarrage du processus) ; le journal donne le temps
réellement suspendu et un verdict (« suspended and woke up », « only part of the time », « did NOT suspend » avec les
wakelocks). Filets de sécurité : un shell root rallume l'écran 20 s après l'alarme quoi qu'il arrive, et un service
relancé pendant le test rallume l'écran à son démarrage (`TEST interrupted`).

Premier essai (28/10, version `74f6f7e`) : l'écran ne s'est jamais rallumé. Le processus du service a redémarré 2 s
après l'extinction de l'écran et l'alarme d'alors (`OnAlarmListener`) est partie avec lui ; seule une coupure du
contact a rendu la main (démarrage à froid). Cause du redémarrage à trouver (dropbox, voir ci-dessous). Un service
relancé pendant une vraie veille reste maintenant « en veille » : le retour du contact rallume l'écran.

À chaque démarrage du processus, le journal note aussi s'il s'agit d'un redémarrage sans boot, les entrées du
**dropbox** d'Android depuis peu avant le boot (plantages Java / natifs, ANR, watchdog, `SYSTEM_RESTART`,
`SYSTEM_LAST_KMSG`, avec le début de chacune), les rapports d'exception MediaTek (`/data/aee_exp`), `tombstones`,
`anr`, et `pstore` (`dmesg-ramoops` après une panique : `console-ramoops` ne garde que les 0,15 premières secondes
sur ce noyau). Un plantage Java du service est écrit dans le journal avant qu'Android ne tue le processus.

### Protocole d'essai

1. Système → Veille : mode **Veille**, durée **7 h**, journal **effacé**.
2. Contact mis, **Tester la suspension** : attendre 60 s sans toucher l'écran. Verdict dans le journal.
3. Contact coupé, attendre **2 min**, contact remis : démarrage instantané ou à froid ? Le journal dit si
   la préparation a tenu dans les 15 s, si le SoC s'est suspendu et comment il s'est réveillé.
4. Même essai avec **30 min** de coupure, puis une nuit (consommation : tension batterie avant / après).
5. Mode **Extinction** : contact coupé 1 min, remis : la ligne `Previous standby … never woke up` est attendue.
6. **Exporter** le journal (et Diagnostic → Exporter pour le trafic MCU) et le joindre au ticket.

### QuickBoot d'Autochips : chaque extinction d'écran tue les applis (28/10, trouvé)

`logcat` du test du 28/10 : à l'extinction de l'écran, le framework modifié (`PowerManagerNotifier` : « Hello QB
OFF », `setSuspendState 2`) diffuse `autochips.intent.action.QB_POWEROFF` ; Bluetooth, Wi-Fi, CarPlay et caméras se
coupent, et **QuickBootManager** (`com.autochips.quickbootmanager`, `doQuickBootPowerOff`) fait `forceStopPackage` de
toute appli absente de sa liste blanche : LibreHU-service, launcher, téléphone, BtnRemap, ViPER, Open Headunit…
(« Force stopping org.librehu.service … from pid 924 »). Une appli arrêtée de force perd ses alarmes et ne redémarre
pas seule : personne ne renvoie PC_READY au réveil, la MCU recoupe le SoC ; contact mis, rien ne réveille le SoC
suspendu (il faut couper le contact plus de 40 s pour un démarrage à froid).

Liste blanche (analyse de l'APK, `Utils.readList`) : **`/vendor/etc/qb_list.xml`** (`Environment.getVendorDirectory()`),
éléments `<app_whitelist name="paquet"/>` (jamais arrêtés : `containsKey` avant `forceStopPackage`) et
`<app_yellowlist name="paquet"/>`, lue une fois au démarrage de QuickBootManager ; propriété
`sys.qb.startapp_onresume` (appli relancée au réveil). Le zip **`qb-whitelist`** (LibreHU-flashablesZips) y ajoute
les applis LibreHU et ViPER4Android ; le journal dit à chaque démarrage si le service y figure.

En attendant (et en plus) : **gardien** (`power/QuickBootGuard.kt`), shell root hors du groupe de processus de l'appli,
lancé juste avant l'extinction de l'écran en veille, qui relance le service s'il n'existe plus ; au réveil, le service
relance l'accueil, les applis LibreHU (leurs récepteurs de démarrage) et ViPER4Android. Le **test de suspension contact
mis est désactivé** : avec le contact mis, rien ne réveillerait le SoC.

**Le réveil refusé (29/10, logcat du test A)** : 1 s après l'arrêt forcé, Android relance le service (« Scheduling
restart of crashed service ») ; le service reçoit bien le contact (ACC on) et envoie `input keyevent KEYCODE_WAKEUP`
en root, mais le PowerManagerService modifié par Autochips répond **« implicit intent to wakeup, reject! »** : après
un QuickBoot power off, il refuse les réveils ordinaires, l'écran reste éteint, le SoC se suspend et adb disparaît
(l'USB est coupé). Côté ROM stock, `SystemUtil.wakeUp()` d'ivi-services est **vide** : le stock ne rallume pas l'écran
lui-même, le réveil vient du matériel (impulsion de la MCU au retour du contact, après `F1`). Le service essaie
maintenant dans l'ordre `KEYCODE_WAKEUP`, la touche power (`KEYCODE_POWER`, autre raison de réveil), puis la touche
power écrite sur le périphérique du clavier `mtk-kpd` (`sendevent`, comme la vraie touche), en vérifiant
`mWakefulness` de `dumpsys power` après chaque essai ; le journal dit lequel a marché. La règle exacte du refus est
dans `services.jar` de la ROM (non public) : à lire pour confirmer.

**Le SoC ne se suspend pas : verrou d'Open Headunit (29/10, journal de veille)** : liste blanche QuickBoot en place
(« this service is in app_whitelist »), veille à t+6,5 s, `F1 1` envoyé, écran éteint à t+8 s, puis 30 s plus tard
« Still running, SoC suspended 0 s » : `mWakefulness=Asleep` mais `PARTIAL_WAKE_LOCK 'HeadunitRevived::BootAutoStart'`
(uid d'Open Headunit, `org.librehu.headunit`) tenu depuis le démarrage, donc `PowerManagerService.WakeLocks` actif dans
le noyau. Démarrage à froid environ 40 à 50 s après la coupure du contact, deux fois (28/10 16:08, 29/10 12:35) : la MCU
coupe le SoC qui ne s'est pas mis en veille. Ce verrou est pris pour 10 min à chaque démarrage ou rallumage par Open
Headunit (`AapService.acquireBootWakeLock`, contre les ROM qui tuent les applis contact coupé) ; avant la liste
blanche, QuickBoot tuait l'appli et le verrou partait avec. Correctifs : le fork Open Headunit relâche ce verrou à
l'extinction de l'écran sur un autoradio LibreHU, et la veille arrête (`am force-stop`, comme QuickBoot) toute appli
qui tient encore un verrou 3 s après l'extinction de l'écran, en l'écrivant dans le journal ; elle est relancée au
réveil.

### Reboot spontané écran éteint (28/10, analysé)

Écran Android éteint, contact mis, SoC resté réveillé : environ 5 à 6 min plus tard, **reboot par le hang_detect de
MediaTek** (« hang_detect thread counts down -1:10 », puis « exception reboot » ; dropbox `SYSTEM_LAST_KMSG`). Avant,
le thread noyau `fast_disp_composer` (affichage rapide Autochips, `primary_display.c`) écrit « wait VSYNC timeout on
scenario primary_disp » toutes les 100 ms : l'écran est coupé et il attend toujours sa synchro. Le hang_detect se
déclenche quand le watchdog d'Android cesse de le relancer (Android bloqué) ; la cause exacte du blocage n'est pas
dans la fin du journal conservée. ivi-services ne coupait que le rétroéclairage au contact coupé et n'éteignait
l'écran d'Android qu'à l'« ACC long off », juste avant la suspension ou la coupure par la MCU.

En conséquence :
- veille : si le SoC ne s'est pas suspendu **90 s** après l'extinction de l'écran, le service note les wakelocks et
  le `dmesg`, envoie `F1 0` et éteint Android proprement (`svc power shutdown`) plutôt que d'attendre ce reboot ;
- test de suspension : 30 s, shell root de secours sorti du groupe de processus de l'appli (`/acct/cgroup.procs`),
  car un arrêt forcé du paquet tue tout le groupe (« removeLocked: Shouldn't for UID=1000 » d'`AlarmManager` à
  l'extinction de l'écran : un composant de la ROM force l'arrêt des applis système, à identifier par `logcat`) ;
- au démarrage, le journal lit le `SYSTEM_LAST_KMSG` du dropbox et dit comment la session précédente a fini
  (hang_detect, panique, reboot, extinction, coupure d'alimentation) et combien de « wait VSYNC timeout » il y a eu.

Les `system_app_crash` du dropbox à ces moments sont **Google Play Services** (`IllegalArgumentException` en boucle
dans son chargeur de modules), pas LibreHU-service.

Piste noyau (non faite) : dans `fast_disp_composer_thread`, ne pas attendre la VSYNC quand l'affichage est en veille
(`pgc->state == DISP_SLEPT` → `msleep(100)`), ce qui supprime le flot d'erreurs ; sans garantie sur le blocage.

## À vérifier sur l'appareil

- le SoC entre-t-il vraiment en suspension (aucun wakelock qui la bloque) et en sort-il proprement ?
- consommation en veille (à mesurer sur la batterie) ;
- la MCU garde-t-elle `F1` d'un démarrage à l'autre (onglet Outils MCU, trafic) ;
- le délai de démarrage moteur suffit-il (journal : « ACC back before the off delay: ignored »).
