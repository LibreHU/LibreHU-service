# Bluetooth (`bt/BluetoothModule`)

Équivalent LibreHU de **`ivi-btservice`** (`com.jancar.btservice`, service `IBluetooth`) et de la partie service
d'**`ivi-bt`** (`com.jancar.bluetooth`, l'app téléphone). État : **écrit d'après l'analyse, pas encore testé sur
l'appareil.**

## Ce que fait la pile Jancar (analyse de `ivi-btservice` 3.0.0.ac8257, `ivi-bt`)

- Pas de module Bluetooth propriétaire : la puce combo **MT6631** est pilotée par la **pile Bluetooth d'Android 9**
  (`com.android.bluetooth`), compilée par Autochips avec les **profils voiture** : HFP client, A2DP sink, AVRCP
  controller, PBAP client. Confirmé par le logcat de l'unité (`HfpClientConnService`, `PbapClientService`,
  `com.autochips.bluetooth.HeadsetClientProfile`).
- `ivi-btservice` n'est qu'une façade (`IBluetooth`, 75 méthodes) au-dessus de ces profils :
  - appels (`CallUtil`) : `BluetoothHeadsetClient` (méthodes cachées `dial`, `acceptCall`, `rejectCall`,
    `terminateCall`, `holdCall`, `sendDTMF`, `connectAudio`/`disconnectAudio`, `getCurrentCalls`), intents
    `…headsetclient.profile.action.AG_CALL_CHANGED` / `AG_EVENT` / `AUDIO_STATE_CHANGED` ;
  - musique (`A2dpUtil`) : `MediaBrowser` vers `com.android.bluetooth/.avrcpcontroller.BluetoothMediaBrowserService` ;
  - répertoire et journal (`ECardUtil`) : le client PBAP les télécharge dans les contacts et le journal d'appels
    d'Android, le service les relit ;
  - sonnerie locale (`RingUtil`), décrochage automatique après 5 s, reconnexion du dernier téléphone ;
  - `ivi-services` (`BluetoothServiceAgent`) relaie l'état d'appel pour les priorités audio.
- Réglages de l'unité : `persist.bluetooth.maxhfpdev = 1`, `persist.bluetooth.atc.inbandringtone = disable`
  (d'où la sonnerie jouée par l'autoradio), `persist.bluetooth.pbap.cch = true`.

## Ce que fait LibreHU-service

| Fonction | Implémentation | Fichier |
|---|---|---|
| Profils | `BluetoothAdapter.getProfileProxy` (HFP client 16, A2DP sink 11, PBAP client 17) + méthodes cachées par réflexion | `CarProfiles.kt` |
| API cachées | exemption `VMRuntime.setHiddenApiExemptions` (Android 9/10) | `HiddenApi.kt` |
| Appels | liste des appels, appeler / rappeler, décrocher (attente → mise en garde), refuser, raccrocher, basculer, terminer et prendre l'autre, DTMF, son voiture/téléphone, micro coupé, assistant vocal du téléphone | `BluetoothModule.kt`, règles dans `core/.../bt/Phone.kt` |
| Indicateurs | batterie, réseau, opérateur, itinérance (événements AG) | `BluetoothModule.kt` |
| Sonnerie et focus | sonnerie par défaut d'Android pendant un appel entrant ; focus audio transitoire pendant la sonnerie et l'appel (radio et lecteurs en pause) | `BtRinger.kt` |
| Musique | métadonnées, lecture/pause/suivant/précédent via le `MediaBrowserService` Bluetooth | `BtMedia.kt` |
| Répertoire | nombre, pages triées, recherche, journal (tous / reçus / émis / manqués), nom d'un numéro, nouveau téléchargement (reconnexion PBAP) | `BtPhonebook.kt` |
| Appareils | marche/arrêt, nom, code PIN, visibilité, recherche, appairage / oubli, connexion d'un téléphone (HFP + A2DP, puis PBAP), confirmation d'appairage automatique, accès au répertoire autorisé d'office | `BluetoothModule.kt` |
| Reconnexion | derniers téléphones connectés (5), essais à 1, 4, 10, 20, 30, 60 s puis toutes les 2 min (20 essais) ; jamais un téléphone déconnecté à la main | `core/.../bt/AutoConnect.kt` |

### Cohabitation avec Jancar
Tant que `com.jancar.btservice` est activé, le module est **passif** (`BtStatus.activeMode = false`) : état, appels
et commandes fonctionnent, mais il ne reconnecte pas, ne sonne pas et ne répond pas aux appairages (btservice le
fait déjà, les deux se gêneraient). Pour le rendre actif :
```
adb shell pm disable-user --user 0 com.jancar.btservice
adb shell pm disable-user --user 0 com.jancar.bluetooth
```
Retour : `adb shell pm enable com.jancar.btservice` (et `com.jancar.bluetooth`).

## Installation
1. App privilégiée (pour `BLUETOOTH_PRIVILEGED` : réponse aux demandes d'appairage, accès au répertoire) :
   ```
   adb root && adb remount
   adb shell mkdir -p /system/priv-app/LibreHU-service
   adb push app-debug.apk /system/priv-app/LibreHU-service/LibreHU-service.apk
   adb push install/privapp-permissions-org.librehu.service.xml /system/etc/permissions/
   adb reboot
   ```
2. Permissions d'exécution (répertoire, journal, recherche d'appareils) : bouton **Permissions** de l'écran
   Bluetooth, ou :
   ```
   adb shell pm grant org.librehu.service android.permission.READ_CONTACTS
   adb shell pm grant org.librehu.service android.permission.READ_CALL_LOG
   adb shell pm grant org.librehu.service android.permission.ACCESS_FINE_LOCATION
   ```
3. Si l'exemption d'API cachées échoue (message `Hidden API exemption failed` dans le logcat, tag `LibreHU-BT`) :
   `adb shell settings put global hidden_api_policy_p_apps 1`.

Test : app **LibreHU service** → **Bluetooth**.

## API (`ILibreHuBluetooth`, API 3)
```kotlin
val bt = ILibreHuService.Stub.asInterface(binder).bluetooth
bt.registerCallback(object : ILibreHuBluetoothCallback.Stub() { … onCallsChanged(calls) … })
bt.dial("0612345678"); bt.answer(); bt.hangup(); bt.mediaNext()
val contacts = bt.getContacts(0, 100)
```
Copier dans l'app cliente les `.aidl` de `org/librehu/service/bt/` et `bt/BtParcels.kt` (même paquet).
Correspondance avec `IBluetooth` de Jancar : `callPhone` → `dial`, `listenPhone` → `answer`, `rejectPhone` →
`reject`, `hangPhone` → `hangup`, `threePartyCallCtrl` → `swapCalls` / `endAndAccept` / `reject`, `transferCall` →
`setAudioInCar`, `requestDTMF` → `sendDtmf`, `muteMic` → `setMicMuted`, `openMotePhoneVoice` →
`startVoiceAssistant`, `playBtMusic`… → `media*`, `getBtMusicId3Info` → `getMedia`, `getPhoneContacts` →
`getContacts`, `getAllCallRecord`/`getMissedCallRecord`… → `getCallLog`, `linkDevice` → `connect`,
`unlinkDevice` → `disconnect`, `deleteDevice` → `unpair`, `searchNewDevice` → `startDiscovery`,
`modifyModuleName` / `modifyModulePIN` → `setName` / `setPin`, `setAutoLink` → `setAutoConnect`.

## Limites connues
- Pas de fusion d'appels en conférence (l'API `BluetoothHeadsetClient` d'Android 9 ne l'expose pas).
- Un seul téléphone pour les appels (`maxhfpdev = 1`), comme le firmware Jancar.
- Le volume d'appel passe par la puce audio comme le reste (pas de priorité dédiée sur le BD37534 pour l'instant).
- Pas de SMS (MAP client) ni de lecture de la bibliothèque musicale du téléphone (navigation AVRCP).

## Musique : « lecture sur le téléphone mise en pause aussitôt »

Android 9 (`A2dpSinkStreamHandler`) renvoie **pause** au téléphone quand il commence à jouer alors que la voiture n'a
pas le **focus audio** (SRC_STR_START / SRC_PLAY sans focus). La session média Bluetooth prend ce focus sur
`prepare()`, pas sur `play()`. LibreHU appelle donc `prepare()` à la connexion A2DP (si aucune autre appli ne joue,
FM par exemple) et avant chaque `play()`.

Sur l'UJC201, la pile Bluetooth d'Autochips met aussi en pause le téléphone 0,4 s après le début de la lecture tant
qu'aucun lecteur ne s'est déclaré « au premier plan ». LibreHU appelle donc `setPlayerState` du contrôleur AVRCP
d'Autochips, comme `A2dpUtil.setPlayerState` de Jancar (`AvrcpPlayerState.kt`).

## Lecture / pause : « il faut appuyer deux fois »

Certains lecteurs (YouTube, Tidal) renvoient leur nouvel état AVRCP en retard ou pas du tout : un 2ᵉ appui sur
lecture/pause renvoyait alors la même commande. Le service (`BtMedia.playPause`) et le launcher retiennent le dernier
basculement pendant 4 s : si l'état annoncé n'a pas changé depuis, ils considèrent que le lecteur est dans l'état
demandé et envoient la commande inverse.

## Déconnexions / reconnexions en boucle

La reconnexion automatique n'essaie plus un autre téléphone tant qu'une connexion est en cours, considère un
téléphone connecté en musique seule comme connecté, et ne déconnecte les autres téléphones que sur un choix explicite
(« Connecter »). Si `com.jancar.btservice` est encore activé, il gère lui aussi le Bluetooth : le désactiver avec
ivi-services (`adb shell pm disable-user --user 0 com.jancar.btservice`), sinon le module reste passif.
