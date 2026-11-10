# Diagnostic du service : blocages, journaux, rapport (v198)

Ce qui peut figer LibreHU-service, ce qui le relance tout seul, et ce qu'il garde pour comprendre après coup. Suite du
blocage signalé le 10/11/2026 (v151 à v196, corrigé en v197 : voir
[power.md](power.md#service-fig%C3%A9--watchdog-du-lien-mcu-1011-powerlinkwatchdogkt)).

## Ce qui pouvait bloquer, et ce qui a changé

Audit de tout le service (10/11/2026) : chaque verrou, chaque appel qui attend (root, disque, binder, port série) et
le fil où il tourne. Les fils qui comptent : `headunit` (trames MCU, volume, feux, touches, données CAN),
`mcu-rx` / `mcu-tx` (port de la MCU), `main` (écrans, panneaux par-dessus les applis, appuis longs du volant, caméra de
recul), `can-box` (messages du profil au boîtier CAN).

| Risque | Effet | Depuis v198 |
|---|---|---|
| Appel vers l'extérieur sous un verrou (CAN : `CanBoxLink`, `CanMonitor`, `CanBoxTalk`) | deux fils qui s'attendent pour toujours : le blocage du 10/11 | aucun appel sortant sous ces verrous (v197) ; un test le vérifie |
| Une tâche du fil `headunit` qui lève une erreur | perdue sans trace (l'exécuteur garde l'erreur dans un `Future` que personne ne lit) ; une tâche périodique (GPIO de marche arrière, clignotants, heure, battement KSW) arrêtée pour toujours | erreur écrite dans le journal du service (`core/.../unit/LoggedExecutor.kt`), tâches périodiques qui continuent |
| Une erreur chez un destinataire des nouvelles de la MCU | les suivants ne recevaient rien (caméra de recul, applis, diffusion de l'état) | chaque destinataire à part : l'erreur est écrite, les autres passent |
| Journal de veille écrit (`fsync`) sur le fil `headunit` à chaque changement de contact | jusqu'à plusieurs secondes d'attente du disque, avec la MCU qui attend son PC_READY au réveil (10 s au plus) | écrit par un fil à lui, dans l'ordre ; attente seulement là où le processus ou le SoC peut s'arrêter (plantage, watchdog, extinction, écran éteint) |
| Ouverture du port série sans `O_NONBLOCK` | `open()` attend la porteuse pour toujours si `CLOCAL` a été retiré par un autre programme | ouverture non bloquante, puis lecture et écriture comme avant |
| Fermeture du port MCU pendant une lecture | le numéro du descripteur fermé peut aller au fichier suivant ouvert : les octets de la MCU iraient dans ce fichier | les fils de la MCU s'arrêtent d'abord (200 ms au plus), le port ensuite ; un test le vérifie |
| Fermeture du port avec des octets non envoyés | `close()` attend jusqu'à 30 s (`closing_wait` du noyau) si l'UART n'envoie plus | 100 ms au plus pour les dernières trames, puis le reste est jeté |
| Retrait d'un paquet de profils CAN sur le fil `main` | relecture de centaines de profils : écran figé un moment | hors du fil `main` |
| Lignes du journal et du trafic MCU écrites par plusieurs fils | une ligne pouvait en écraser une autre | mises à jour atomiques |

Vérifié sans problème : appels root (tous hors des fils critiques, avec délai : 10 s par défaut), file d'attente des
rappels aux applis (`oneway` : jamais d'attente), lecture du port (délai de 200 ms, `EINTR` traité), verrous restants
(aucun appel sortant, ou appels bornés hors des fils critiques).

## Le watchdog

Fils surveillés et seuil : voir [power.md](power.md#service-fig%C3%A9--watchdog-du-lien-mcu-1011-powerlinkwatchdogkt).
Depuis v198, il surveille aussi le fil `main` (un battement posté à chaque contrôle) et le fil `can-box` quand il
tourne. Après une relance, une **notification** le dit (« LibreHU service relancé », avec l'heure et la cause) ; si 3
relances en 10 min n'ont pas suffi, une notification « LibreHU service bloqué » ouvre la fiche de l'appli pour forcer
l'arrêt. Canal « Surveillance du service », sans son.

## Ce que le service garde

| Quoi | Où | Contenu |
|---|---|---|
| Journal du service (nouveau) | stockage protégé de l'appli : `service.log`, `service.1.log` (512 Ko chacun) | chaque ligne du journal du service (lien, MCU, CAN, veille, watchdog, erreurs des fils), horodatée, gardée après un redémarrage ou un démarrage à froid, alors que le tampon de logcat se remplit en quelques minutes |
| Journal de veille | `standby.log`, `standby.1.log` (256 Ko chacun) | veille et réveil, plantages, rapports du watchdog (pile de chaque fil surveillé ou bloqué), démarrage précédent |
| Export CAN | à la demande | chaque message du boîtier depuis le démarrage, et ce que l'autoradio lui a envoyé |
| Trafic MCU | mémoire | les 2 000 dernières trames reçues et envoyées |

## Les lire

**LibreHU Helper**, onglet Rapport, section **LibreHU-service** (cochée par défaut) : tout ce qui précède, plus la pile
de chaque fil du service, sans root. Avec root, en plus : les fils vus du dehors (`debuggerd -b`, et `debuggerd -j`
sous Android 10 ou plus ; Android 9 n'a pas `-j`), où chacun attend dans le noyau, la fiche du service dans le
gestionnaire d'activités (relances, plantages), les traces d'ANR et les entrées dropbox du service. **Service figé :
faire le rapport pendant qu'il est figé, avant de forcer l'arrêt.**

**adb**, sans root : le shell a le droit de lire le fournisseur du service (`diag/DiagnosticsProvider.kt`,
`org.librehu.service.diagnostics`) :

```
adb shell content read --uri content://org.librehu.service.diagnostics/report
adb shell content read --uri content://org.librehu.service.diagnostics/service-log
adb shell content read --uri content://org.librehu.service.diagnostics/standby-journal
adb shell content read --uri content://org.librehu.service.diagnostics/can
```

Lecture seule. Accès comme pour l'API : applis LibreHU, système, shell, applis autorisées par l'utilisateur
(LibreHU-service → Accès). Chaque élément est écrit par un fil à lui, avec des attentes bornées : un fil `headunit` ou
`main` figé ne l'empêche pas de répondre.

## Tests

Tests du cœur (`./gradlew -PcoreOnly :core:test`) : l'interblocage du 10/11 reproduit (`CanBoxLinkThreadsTest` : avec
l'ancien code, « deadlock: box thread BLOCKED, head unit thread BLOCKED »), la fermeture du port après ses fils
(`McuTransportTest` : échoue avec l'ancien ordre), les erreurs des tâches (`LoggedExecutorTest`), le watchdog
(`WatchdogTest`). Sur émulateur Android 9 : workflow **Diagnostics test (Android 9)** (lancé à la main,
`tools/diagnostics_test.py`) : build de débogage avec l'ABI de l'émulateur (`-PextraAbi=x86_64`, l'APK publié est arm64
seulement), chaque élément lu par adb, puis le rapport de LibreHU Helper (son propre test), qui doit contenir la section
LibreHU-service.

Sources : [`Watchdog.java` d'Android 9](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/pie-release/services/core/java/com/android/server/Watchdog.java)
(60 s, piles puis fin du processus) ;
[`ScheduledExecutorService`](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/ScheduledExecutorService.html)
(« Subsequent executions are suppressed ») ;
[`debuggerd.cpp` d'Android 9](https://android.googlesource.com/platform/system/core/+/refs/heads/pie-release/debuggerd/debuggerd.cpp)
(« usage: debuggerd [-b] PID », pas de `-j`) ;
[CERT LCK07-J](https://wiki.sei.cmu.edu/confluence/spaces/java/pages/88487703/LCK07-J.+Avoid+deadlock+by+requesting+and+releasing+locks+in+the+same+order).
