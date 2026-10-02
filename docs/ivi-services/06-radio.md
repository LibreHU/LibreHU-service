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

## 6.3 Sur cette unité : FM interne MediaTek [V][A]
Relevés : `jancar.radio.id = -1`, `fmradio.driver.enable = 1`, module noyau **`fmradio_drv`** chargé (puce combo
MediaTek MT6631 : Wi-Fi, BT, GPS, FM). Aucun tuner NXP n'est détecté.
- L'app radio (`com.jancar.radio`, `ivi-radio.apk`) passe en mode « radio interne » (`bInnerRadio`) et pilote
  **directement `/dev/fm`** avec la JNI MediaTek standard `libfmjni.so` (`FmNative` : `openDev`, `powerUp`,
  `tune`, `autoScan`, `seek`, RDS `getPs` / `getLrText` / `getPTY`, TA/TP, AF…). Le logcat montre les
  ioctl `0xF50A`, `0xF513` sur `/dev/fm`.
- Le son FM est routé par le matériel MediaTek. L'app joue un `AudioTrack` silencieux pour garder la sortie
  audio active. Côté puce BD37534, la radio passe donc par l'entrée Android.
- `RadioService` d'ivi-services tourne quand même (pilote MT6631 vide dans la lib native).
- Pour la réécriture : reprendre la JNI `libfmjni` (même interface que l'app FM Radio open source de MediaTek)
  ou les ioctl de `/dev/fm`. Pas besoin de pilote I2C de tuner.

## 6.4 Autres cartes
Sur les cartes avec tuner externe, le pilote est **dans la lib native** (pilotes NXP TEF66xx, propriétaires, avec
patch de firmware à charger). Options : réutiliser
`libJanCarIVI.so` par JNI (pas libre, mais disponible), ou écrire un pilote TEF668x (des pilotes libres existent
pour les TEF668x, par exemple [PE5PVB/TEF6686_ESP32](https://github.com/PE5PVB/TEF6686_ESP32)).
