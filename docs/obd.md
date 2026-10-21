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
- **Widgets « Compteurs OBD » (analogique) et « Combiné OBD numérique »** : tableau de bord façon Android Auto.
  Analogique : compteur de vitesse et compte-tours à aiguille (arc de 240°, zone rouge), jusqu'à 3 mini-cadrans.
  Numérique : vitesse en grand chiffre, barre de régime segmentée qui rougit près de la zone rouge, 4 tuiles.
  Les valeurs secondaires reprennent celles choisies pour le widget « OBD » (sinon température d'eau, batterie,
  carburant, charge). Couleur d'accent et clair / sombre du launcher ; s'adapte à la taille (4×2 conseillé).
  Image redessinée à chaque mise à jour OBD (environ 1 s) : pas d'animation fluide de l'aiguille.
- Autres apps : `ILibreHuService.getObdValues()` (API 4) et le broadcast `org.librehu.action.OBD` (≈ 1 par
  seconde, extras = nom de la valeur → double, ex. `RPM`, `SPEED`, `COOLANT_TEMP`, `BATTERY` ; plus
  `BATTERY_PERCENT` (int, −1 moteur tournant) et `BATTERY_STATUS`, voir ci-dessous). API 8 :
  `getObdBattery()` (batterie) et `getObdCarValues()` (valeurs constructeur choisies : clé `<calculateur>/<valeur>`
  → Bundle `name`, `ecu`, `text`, `unit`, `number`, `error`), utilisés par l'écran OBD plein écran du launcher.

## Batterie de la voiture (`obd/BatteryWatch`, `core/.../CarBattery.kt`)

Depuis la tension de la prise OBD (`ATRV`, tous les 5 tours) :
- **Moteur arrêté** : charge estimée par la tension au repos, tableau de la Battery Council International
  (12,6 V = 100 %, 12,4 V = 75 %, 12,2 V = 50 %, 12,0 V = 25 %, 11,7 V = 0 %, interpolé). Approximatif : autoradio
  allumé la batterie débite un peu (lecture un peu basse), juste après avoir roulé elle garde une charge de surface
  (lecture un peu haute).
- **Moteur tournant** (régime > 300 tr/min s'il est lu, sinon tension ≥ seuil de charge) : **en charge**
  (alternateur, d'ordinaire 13,5 à 14,7 V), **pas de charge** (sous le seuil, 13,0 V par défaut) ou **surtension**
  (au-dessus de 15,0 V par défaut).
- **Alarmes** (notification, avec ou sans son) : batterie faible moteur arrêté (sous 12,0 V pendant 30 s), défaut
  de charge (60 s), surtension (10 s) ; chacune une fois, puis de nouveau seulement après être revenue à la normale
  (0,2 V au-dessus du seuil pour la batterie faible). Seuils, son et **correction de la tension lue** (±0,5 V : un
  ELM327 peut lire 0,1 à 0,3 V à côté) dans la carte **Batterie de la voiture** de l'onglet OBD. Les voitures à
  alternateur piloté descendent parfois exprès vers 12,5 V moteur tournant : baisser le seuil ou couper l'alarme.
- Charge et état : AIDL `getObdBattery()` (`voltage`, `percent`, `status` = `ENGINE_OFF`, `CHARGING`,
  `NOT_CHARGING`, `OVERVOLTAGE`) et extras du broadcast ; affichés par le launcher (écran de verrouillage, horloge
  de veille, écran OBD).

Clio 3 : prise OBD sous le volant ; l'EOBD est obligatoire depuis 2001 (essence) et 2004 (diesel), le protocole est
détecté automatiquement. Les valeurs non gérées par le calculateur apparaissent grisées.

## Voiture : valeurs constructeur (Renault et autres, fichiers ddt4all)

L'OBD standard (mode 01) ne donne que les valeurs légales anti-pollution. Les calculateurs Renault (UCH, tableau de
bord, injection, ABS…) en donnent beaucoup plus par les requêtes de diagnostic constructeur, décrites dans les
fichiers de calculateur de **ddt4all** (github.com/cedricp/ddt4all, GPL-3.0). LibreHU les lit avec le même ELM327.

**Rien n'est embarqué** : les descriptions viennent de vos propres fichiers. ddt4all lit la base de données XML
DDT2000 et la convertit en JSON (`ecu.zip`, dossier `json/`) ; un fichier JSON = un calculateur. LibreHU ne fournit
ni n'invente aucun identifiant. Seule la liste des **noms de véhicules** de ddt4all (code de projet → nom, p. ex.
`X85` → Renault Clio III, de `resources/projects.json`) est reprise, pour afficher un nom au lieu d'un code
(`core/.../Ddt4allProjects.kt`).

### Base complète : `ecu.zip` de ddt4all (carte **Base ECU ddt4all**)

1. **Importer ecu.zip** : le zip produit par ddt4all (`zipConvertXML`, `core/parameters/helpers.py`) est copié dans
   le stockage du service (`no_backup`, hors de la sauvegarde des réglages : il est gros et revient de son propre
   fichier) et son index `db.json` est lu : pour chaque fichier de calculateur, adresse, groupe (UCH, Injection…),
   protocole, projets (véhicules) et identifications.
2. **Chercher la voiture** par nom ou code ; seuls les véhicules ayant des calculateurs **CAN** sont listés.
3. Pour chaque calculateur (adresse) du véhicule, **un fichier** à garder (ils diffèrent par version logicielle) ou
   « Ignorer ». **Identifier sur la voiture** (adaptateur connecté) lit l'identification de chaque calculateur comme
   le scanner de ddt4all (`ecu_scanner.py`) : requête `2180` (anciens calculateurs Renault : version diag, fournisseur,
   logiciel, version), sinon `22F1A0` / `22F18A` / `22F194` / `22F195` (UDS) ; le fichier correspondant est choisi
   (correspondance exacte `checkWith`, sinon version la plus proche à fournisseur et logiciel égaux,
   `checkApproximate`). Ce sont des **requêtes de lecture** ; LibreHU n'ouvre **pas** de session (ddt4all envoie
   `10C0` / `1003` avant) : un calculateur qui ne répond qu'en session est à choisir à la main.
4. **Créer la voiture** : les fichiers choisis sont convertis comme un import de fichier de calculateur (mêmes règles
   de sécurité) en une voiture `ddt4all-<code>`, sélectionnée ; on choisit ensuite ses valeurs comme d'habitude.

Carte **Voiture (valeurs constructeur)** de l'onglet OBD :
- **Importer une voiture** : un fichier de calculateur ddt4all (devient une voiture nommée d'après le calculateur)
  ou un fichier voiture LibreHU (plusieurs calculateurs, à partager). **Ajouter un calculateur** : ajoute ceux du
  fichier à la voiture choisie (même nom = remplacé). **Exporter** : fichier voiture LibreHU de la voiture.
- **Valeurs de la voiture** : recherche par nom (calculateur ou valeur), un appui = lue / plus lue. Les valeurs
  choisies sont lues un tour sur deux, après les valeurs standard, et apparaissent en tuiles. Les nombres partent
  aussi dans le broadcast `org.librehu.action.OBD` (extras `car:<calculateur>/<valeur>` → double, et
  `cartext:<calculateur>/<valeur>` → texte affiché avec son unité).

### Sécurité : lecture seule

À l'import, seules les requêtes **21** (ReadDataByLocalIdentifier) et **22** (ReadDataByIdentifier), sans
paramètre, sont gardées ; un fichier LibreHU contenant autre chose est refusé. Aucune écriture, aucun reset, aucun
test d'actionneur, aucune ouverture de session de diagnostic n'est jamais envoyé. Les commandes adaptateur d'un
fichier voiture (`elm_init`) doivent être des commandes `AT` et celles qui écrivent la mémoire de l'adaptateur
(`ATPP`, `ATSD`…) sont refusées.

Limites : calculateurs **CAN** seulement (pas de K-Line / KWP2000 par l'ELM327 ici). Certaines valeurs ne répondent
qu'en session étendue : elles restent à « — » avec le code de refus (NRC) affiché, LibreHU n'ouvre pas de session.

### Adressage (comme ddt4all)

Pour chaque calculateur (`elm.py`, `set_can_addr`) : `ATSH <send_id>` (29 bits : `ATCP` + `ATSH`), contrôle de flux
vers le calculateur `ATFCSH <send_id>`, `ATFCSD300000`, `ATFCSM1`, filtre de réception `ATCRA <recv_id>` ; puis la
requête, avec le formatage CAN de l'ELM327 (réponses multi-trames réassemblées : ligne de longueur puis `0:`, `1:`…).
Ensuite retour à l'OBD standard : `ATCRA`, `ATFCSM0`, `ATSH7DF` (ou `ATCP18` + `ATSHDB33F1` en 29 bits).

### Calcul des valeurs (comme ddt4all)

D'après `ecu_data.py` (`getHexValue`, `getDisplayValue`) et `data_item.py` :
- `firstbyte` compte depuis 1 sur toute la réponse (octet de service `61` / `62` compris), `bitoffset` depuis le bit
  de poids fort de cet octet, sur `bitscount` bits (8 par défaut) ; `endian: Little` = octets inversés.
- `signed` : complément à deux sur 8 ou 16 bits (`bytescount` 1 ou 2).
- `scaled` : `(brut × step + offset) / divideby`, décimales selon `format` (`0.0` = 1) ; sinon libellé de `lists`
  ou valeur brute en hexadécimal ; `bytesascii` : texte (VIN…).

Les tests `ObdCarTest` (module core) vérifient ce calcul ; les valeurs attendues ont été comparées avec le code
Python de ddt4all lancé sur les mêmes octets.

### Fichier voiture LibreHU (`librehu-obd-car`)

```json
{
  "format": "librehu-obd-car", "version": 1,
  "id": "clio3-k4j", "name": "Renault Clio III 1.4 16V",
  "elm_init": ["ATSP6"],
  "pids": ["RPM", "SPEED", "COOLANT_TEMP"],
  "ecus": [{
    "name": "Injection", "send_id": "7E0", "recv_id": "7E8",
    "requests": [{
      "name": "…", "send": "21xx", "reply": "61xx", "min_bytes": 0,
      "items": [{"name": "…", "firstbyte": 3, "bitoffset": 0, "bits": 16, "scaled": true,
                 "step": 0.1, "offset": 0, "divideby": 1, "format": "0.0", "unit": "°C",
                 "lists": {"0": "…"}}]
    }]
  }]
}
```

- `elm_init` : commandes `AT` envoyées à la connexion, après l'initialisation (ex. `ATSP6` = CAN 11 bits 500 kbit/s).
- `pids` : valeurs OBD standard à lire avec cette voiture (vide = garder votre choix).
- Les champs des valeurs reprennent ceux de ddt4all (`bits` = `bitscount`, `bytes` = `bytescount`, `ascii` =
  `bytesascii`, `endian` = `little` ou absent).
- Les identifiants ci-dessus ne sont qu'un exemple de forme : prenez ceux de vos fichiers ddt4all.

## Son moteur simulé (`car/EngineSound.kt`)

Comme l'appli Potenza Drive (iPhone + ELM327) : un son de moteur **synthétisé** suit le régime et l'accélérateur
(ou la charge) lus par l'adaptateur, sur la sortie média, mélangé à la musique (pas de demande de focus audio).
Désactivé par défaut, carte **Son moteur (simulé)** de l'onglet OBD ; le régime doit être parmi les valeurs lues.

- Une impulsion par allumage (fréquence = régime / 60 × cylindres / 2, moteur 4 temps), allumage irrégulier pour le
  8 en V (glouglou), deux résonances d'échappement (filtres passe-bande) et un silencieux (passe-bas qui s'ouvre
  avec la charge), bruit ; formules de filtres du « RBJ Audio EQ Cookbook ».
- Moteurs : 4 cylindres, 6 en V, 8 en V, 12 cylindres ; volume ; turbo (sifflement selon la charge, soupape de
  décharge quand l'accélérateur se ferme d'un coup) ; pétarades au lever de pied ; régime maximal joué.
- L'OBD ne donne que quelques valeurs par seconde : le régime glisse vers la dernière valeur (≈ 80 ms). Fondu à
  l'arrêt quand l'adaptateur ne répond plus. Bouton **Essai** : montée en régime simulée de 8 s, sans la voiture.
