# Appels entrants sur les cartes avec modem 4G MediaTek

**Expérimental, non testé avec une SIM au moment de l'écriture (23/11/2026).**

## Le problème
Le RIL MediaTek du vendor (`mtkfusionrild`, `libmtk-ril.so`) ne laisse pas sonner un appel entrant de lui-même. Sur
`+EAIC` (indication d'appel entrant) il envoie `incomingCallIndication` au client inscrit par
`vendor.mediatek.hardware.radio@3.0::IRadio.setResponseFunctionsMtk`, puis attend `setCallIndication(serial, mode,
callId, seqNo)` ; mode 0 = laisser passer l'appel (`AT+EAIC=0,…`), ensuite `RING` et l'état d'appel habituels
arrivent à Android. Sur le firmware d'origine, ce client est le framework téléphonie de MediaTek. La téléphonie AOSP
de LineageOS n'inscrit que les rappels `android.hardware.radio` : le RIL journalise
`incomingCallIndicationInd: radioService[0]->mRadioIndicationMtk == NULL` et l'appel ne sonne jamais.

## Ce que fait LibreHU
`ril/MtkCallHelper` (lancé en root par app_process, gardé en vie par `ril/MtkIncomingCalls`) est ce client, et rien
d'autre : il s'inscrit sur les instances `slot1` et `slot2`, répond `setCallIndication(…, 0, callId, seqNo)` à chaque
appel entrant hors IMS, et ignore toutes les autres indications et réponses MediaTek. Interrupteur : onglet
Téléphone, carte « Modem 4G (MediaTek) » (activé par défaut ; sans effet sur une carte sans modem).

Écrit à partir des binaires du vendor stock (UJC201 vendor 260729, désassemblés le 23/11/2026), sans code MediaTek ni
tiers :

| Élément | Source | Valeur |
|---|---|---|
| `setResponseFunctionsMtk` | `vendor.mediatek.hardware.radio@3.0.so`, `BpHwRadio::_hidl_setResponseFunctionsMtk` | transaction 143, deux binders (réponse, indication), aller-retour |
| `setCallIndication` | idem, `_hidl_setCallIndication` | transaction 196, quatre int32, oneway |
| `incomingCallIndication` | idem, `BpHwRadioIndication::_hidl_incomingCallIndication` | transaction 58, int32 type + `IncomingCallNotification` (7 chaînes, 0x70 octets) |
| Champs | `librilfusion.so`, `radio::incomingCallIndicationInd` | chaîne i = élément i de `+EAIC` |
| Sens des éléments | `libmtk-ril.so`, `RtcCallController::handleIncomingCall` | 0 = id d'appel, 3 = mode (≥ 20 : IMS), 4 = numéro de séquence |
| Mode 0 = accepter | `libmtk-ril.so`, `RtcCallController::autoAcceptIncoming` | requête {0, callId, seqNo} |

Les classes HIDL Java d'Android (`android.os.HwBinder`, `HwParcel`…) sont des API système : le module `hidlstub`
donne leurs signatures à la compilation (compileOnly), le `framework.jar` de l'appareil les fournit à l'exécution.

## Limites
- VoLTE / IMS : non (pas de pile IMS sous LineageOS) ; les appels passent en 2G / 3G tant que l'opérateur les garde.
- Si le RIL redémarre, l'assistant s'arrête (`died slotN`) et le service le relance après 5 s.

## Vérifier sur une carte avec modem
```
adb logcat -s LibreHU-ril
adb shell su -c 'logcat -b radio -d | grep -i -E "EAIC|incomingCallIndication|setCallIndication"'
```
`registered slot1` doit apparaître au démarrage ; à un appel entrant, `call slot1 <id> <seq>` puis la sonnerie.
