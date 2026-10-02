# 6. Radio (`RadioService`, `RadioDevice`, `RdsManager`)

## 6.1 Tuner [A][N]
- `RadioDevice(id, bus I2C 6, GPIO reset 112, fmLevelOffset)` ; alimentation GPIO 112, antenne GPIO 110 + MCU `43`.
- Id `-1` (board id `?…N`) → `NXPRadio::detect` puis instanciation du bon pilote natif (TEF6638, TEF6686,
  TEF6692, TEF6856, TDA7786, TDA7708, TSC4745, SI475x, QN8027, CA9636…). L'id réel est publié dans la
  propriété **`jancar.radio.id`** et renvoyé par `IRadio.getId()`.
- Fonctions natives : fréquence, recherche (`StartScan` / `ScanAt` / `EndScan`), niveau et stéréo, conditions de
  recherche FM/AM, sensibilité, LOC, gain de sortie, RDS (`GetRds`), AF (`SetAfFreq`, `AfJump`, `GetAfPi`),
  plage de bande (`SetRadioRange`), mute.

## 6.2 Service [A]
- Bandes et pas : `RadioBand`, zone selon `[Radio]` d'`ivi-config.ini` et `/jancar/config/ivi-radio-cfg.ini`.
- Mémoires et dernière fréquence : préférences internes, plus le fournisseur `com.jancar.radio_v2.provider` de l'app radio.
- Thread radio : recherche, mesure du signal, RDS.
- RDS en Java (`RdsManager`) : PS, RT, PTY, TA/TP, AF ; fenêtre TA (`PopTAWindowManager`) ; focus audio `RADIO_TA`.
- ACC : extinction lors de l'ACC long off, rallumage 2,1 s après le réveil (tuner externe).
- AIDL `IRadio` (37 méthodes) et `IRadioCallback` (30) : [api.md](api.md).

## 6.3 À reprendre
Le pilote du tuner est **dans la lib native** (pilotes NXP TEF66xx, propriétaires, avec patch de firmware à
charger). Il faut d'abord connaître le tuner réel (`getprop jancar.radio.id`). Options : réutiliser
`libJanCarIVI.so` par JNI (pas libre, mais disponible), ou écrire un pilote TEF668x (des pilotes libres existent
pour les TEF668x, par exemple [PE5PVB/TEF6686_ESP32](https://github.com/PE5PVB/TEF6686_ESP32)).
