# 3. MCU et véhicule (`CarService`, protocole `JAC_V1`)

Le firmware MCU et le format des trames sont documentés dans `LibreHU/MCU-tools-app/docs/mcu_firmware.md`.
Ici : ce qu'ivi-services en fait [A].

## 3.1 Pile série
`BaseProtocol` : 3 threads (lecture, traitement, écriture) et files de commandes. `JAC_V1/Protocol` : trame
`EE FA LEN CMD DATA CS`. Renvoi automatique si l'ACK `C0` manque pour les commandes marquées `needAck`.
Le journal des trames (tag `JLOG`) s'active avec le réglage `global_mcudatadebug`.

## 3.2 Trames MCU → SoC traitées
| Cmd | Traitement |
|---|---|
| `00` | ACC (si mode 1) → `setAccReadyState(true)`, `IVICar.Acc` |
| `04` | frein à main (inversé si `isRemove1821`) → `IVICar.Handbrake` |
| `09` | date/heure : allume l'ampli externe selon `KEY_EXTERNAL_AMP_SWITCH`, puis règle l'heure Android une fois par démarrage si `[DateTime] SyncMcu` |
| `0A` | version MCU → `IVICar.EventMcuVersion`, envoi du type de molette (`0F …`) et des seuils de tension (`[Power] SHUTDOWN_HIGH_VOLTAGE` / `SHUTDOWN_LOW_VOLTAGE` → `0F 0A n` / `0F 0B n`) |
| `0B` | feux → `IVICar.HeadLight` (luminosité jour/nuit) |
| `0D` | rétroéclairage (journalisé seulement) |
| `10` | **CAN brut** → `IVICar.EventPassthroughData` → `IPassthroughDataCallback` (un seul client : `com.jancar.canservice`, relancé s'il meurt) |
| `1F` | « ready info » (champs de bits) : b0.6 frein, b0.4 feux, b0.3 rétroéclairage, b0.2 mute IO, b0.0 tension basse → `IVICar.PowerWave` ; b1.6 inutilisé |
| `20` | touche (volant / façade / molette) → `StudyKeyManager` (plages apprises) ou `IVICar.StudyKeyItem` |
| `30` | touche en mode apprentissage |
| `52` | rotation de l'écran → `ScreenRotationUtil` |

Le firmware de cette carte n'envoie jamais `1F` ni `52` (voir mcu_firmware.md §6.5) : ces branches servent à
d'autres MCU.

## 3.3 Trames SoC → MCU
| Méthode | Trame |
|---|---|
| `sendPcReady` | `1F 01`, puis contrôle de version |
| `setMute` | `08 [0/1]` |
| `sendSystemTime` | `09 [0, aa/100, aa%100, mois, jour]` et `09 [1, h, m, s]` (chaque minute, une fois l'heure MCU reçue) |
| `sendPowerOff(type)` | `01 [type, 0, 13 si type=2]` ; type 4 → `0E` (reset SoC) |
| `setAccSleepTime(min)` | `F1 [min/60/6]` |
| `getAccState` | `F0 00 00` |
| `sendPassthroughData(id, data)` | trame brute (AIDL `ICar` 20) |
| `setCmdParam` | idem (AIDL `ICar` 34) |
| `setCarADKey(ch, key)` | 161 = touches normales, sinon touches brutes vers `onADKeyChanged` |

## 3.4 Données véhicule évoluées
`ICar` / `ICarCallback` exposent climatisation, portes, radar, pneus, trajets, VIN, entretien, flux d'énergie…
Sur cette carte, **rien de tout cela n'est décodé par ivi-services** : ces événements viennent du protocole
`CIS_V2` (autre type de MCU) ou de **`com.jancar.canservice`**, qui décode les trames `10` selon
`/jancar/config/can_config.json` (Raise, Hiworld, Simple…). Pour réécrire cette partie, il faut cet APK.

## 3.5 Autres fonctions de `CarService`
- Marche arrière : GPIO 2 (mode 0), ou CAN via le broadcast `action_backcar_notification` (extra `backcar`)
  envoyé par canservice. Broadcasts `android.backcar.action.STARTED` / `FINISH` reçus de l'app caméra.
- Heartbeat MCU (`sendHeartbeat`, `pauseHeartbeat`) : sans effet sur ce firmware (pas de chien de garde côté
  SoC, voir mcu_firmware.md).
- Mise à jour MCU (`upgradeMcu`) : protocole bootloader `80` / `83` (mcu_firmware.md §11).
- Relaie vers la MCU l'état média, le volume, le mute, le BT et l'heure (utile seulement pour un combiné ou un
  écran secondaire).
- `StudyKeyManager` : associe les valeurs ADC des touches volant (`/jancar/config/ivi-studykey.ini`) à des
  actions → [08-system-keys.md](08-system-keys.md).
