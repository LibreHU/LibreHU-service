# OBD-II par ELM327 (`obd/ObdManager`)

Lecture du calculateur moteur par un adaptateur **ELM327** (prise OBD-II), en **Bluetooth** (profil série SPP) ou en
**USB** (convertisseurs FTDI, CH340, PL2303, CP210x via usb-serial-for-android). Réglages et valeurs : onglet **OBD**.

- Initialisation : `ATZ`, `ATE0`, `ATL0`, `ATS0`, `ATH0`, `ATAT1`, `ATSP0` (protocole automatique), puis `0100`,
  `0120`, `0140` pour connaître les valeurs gérées par la voiture (fiche technique ELM327, Elm Electronics).
- Valeurs (mode 01, formules SAE J1979) : régime, vitesse, liquide de refroidissement, air d'admission, charge,
  papillon, carburant, tension du calculateur, huile, air extérieur, débit d'air, pression d'admission,
  consommation… Les rapides (régime, vitesse…) à chaque tour, les lentes tous les 5 tours. Plus `ATRV` : tension
  de la batterie mesurée par l'adaptateur, même moteur coupé.
- Codes défaut : lecture (mode 03) et effacement (mode 04, avec confirmation).
- **Widget « OBD »** : 4 valeurs au choix, thème clair / sombre de LibreHU Launcher, rafraîchi chaque seconde ;
  à ajouter dans un emplacement de widget du launcher. Appui : ouvre l'onglet OBD.
- Autres apps : `ILibreHuService.getObdValues()` (API 4) et le broadcast `org.librehu.action.OBD` (≈ 1 par
  seconde, extras = nom de la valeur → double, ex. `RPM`, `SPEED`, `COOLANT_TEMP`, `BATTERY`).

Clio 3 : prise OBD sous le volant ; l'EOBD est obligatoire depuis 2001 (essence) et 2004 (diesel), le protocole est
détecté automatiquement. Les valeurs non gérées par le calculateur apparaissent grisées.
