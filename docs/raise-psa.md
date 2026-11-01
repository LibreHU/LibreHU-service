# CAN : boîtier Raise PSA et Citroën C4 II

Analyse des sources OEM AutoAI/Jancar extraites de l'autoradio, croisée avec des relevés du véhicule de juin
et septembre 2026. Synthèse du 1er novembre 2026.

- **[A]** : analyse statique du logiciel OEM ; décrit ce que fait le décodeur, pas nécessairement ce que le véhicule émet.
- **[V]** : observation sur le véhicule ou confirmation du propriétaire.
- **[M]** : méthode de traitement des mesures ; ne constitue pas une validation physique du capteur.
- **[?]** : point non résolu.

## 1. Véhicule et profil sélectionné [V]

| Champ | Valeur |
|---|---|
| Véhicule réel | Citroën C4 II/B7, berline à hayon, 2012, 1.6 HDi 92 ch, moteur DV6 |
| Marque sélectionnée | `Citroen` |
| Modèle sélectionné | `C4L` |
| Années / configuration | `2010~2012` / `All` |
| Boîtier sélectionné | `Raise`, `canboxId=1`, `subId=1` |
| Modèle du boîtier installé | `E Lpsa` (Raise), confirmé par le propriétaire |
| Protocole déclaré | `睿志诚_标致雪铁龙车系_串口通信协议_拆V2.08.016.pdf` |

Profil relevé le 27 septembre. **C4L est le profil configuré**, pas l'identification de la carrosserie réelle.
Le modèle du boîtier installé est **E Lpsa**, d'après la confirmation du propriétaire. La version du
firmware reste à relever. Le nom du PDF vient de la
configuration ; son contenu n'a pas été vérifié.

## 2. Couches à distinguer [A]

```text
Bus CAN du véhicule → boîtier Raise → MCU / transport série
                  → décodeur AutoAI (com.can.activity) → objets Android / callbacks
```

Les relevés étudiés contiennent des **valeurs déjà décodées et des callbacks Android**, pas une capture des
trames CAN du véhicule. Une commande série Raise comme `0x33` n'est pas un identifiant CAN PSA.

Le fournisseur `content://com.autoai.canbus.provider` permet de retrouver la configuration du boîtier.
Les classes principales de cette recherche sont `RePsaProtocol`, `CanPsaTripCom`, `DashBoardInfo` et
`HvacInfo`, dans les sources OEM de `com.can.activity`.

Cette unité utilise Raise PSA ; le profil Hiworld Renault documenté dans
[LibreHU, 12-canbus.md](https://github.com/LibreHU/LibreHU-service/blob/main/docs/ivi-services/12-canbus.md)
concerne un autre boîtier et un autre véhicule. Ses commandes, offsets et règles de checksum ne sont pas
transposables sans vérification.

## 3. Ordinateur de bord : commandes `0x33`, `0x34`, `0x35` [A]

Source : `com/autoai/canbus/psa/protocol/RePsaProtocol.java`, méthode `parsePsaTripComputer`.
Les index ci-dessous sont ceux de **`bArr` dans cette méthode**, avec la commande à l'index 2 ; ce ne sont
pas des offsets de charge utile CAN. L'en-tête complet et le checksum ne sont pas établis ici.

| CMD | Index dans `bArr` | Décodage OEM | Destination / sens |
|---|---|---|---|
| `0x33` | 3–4 | entier gros-boutiste ÷ 10 | `mFuelConsumption` → `instFuelCont`, consommation instantanée |
| `0x33` | 5–6 | entier gros-boutiste | `mResidualOilMileage` → `cruisingRange`, autonomie en km |
| `0x33` | 7–8 | entier gros-boutiste | `mObjectiveTomileage`, distance à destination |
| `0x33` | 9, 10, 11 | trois octets non signés | heures, minutes, secondes du compteur Start & Stop affiché par l'OEM |
| `0x33` | 12–13, si présents | entier gros-boutiste ÷ 100 | `mInstantaneousSpeed`, vitesse de la page OEM |
| `0x34` | 3–4 | entier gros-boutiste ÷ 10 | `mFuelAverage1` → `avgFuelCont`, moyenne du trajet 1 |
| `0x34` | 5–6 | entier gros-boutiste | `mSpeedAverage1`, vitesse moyenne du trajet 1 |
| `0x34` | 7–8 | entier gros-boutiste | `mAccumulatMileage1` → `odo`, distance du trajet 1 |
| `0x35` | 3–4 | entier gros-boutiste ÷ 10 | `mFuelAverage2`, moyenne du trajet 2 |
| `0x35` | 5–6 | entier gros-boutiste | `mSpeedAverage2` |
| `0x35` | 7–8 | entier gros-boutiste | `mAccumulatMileage2` |

Le helper `byteToInt` renvoie `-1` pour `FF FF`. Après division par 10, le résultat devient **`-0.1`** :
c'est une valeur indisponible, pas une consommation négative ni un zéro.

Deux pièges du contrat OEM :

- **`odo` reçoit le partiel 1**, pas le kilométrage total du véhicule.
- Si les octets 12–13 manquent, cette méthode utilise deux zéros pour la vitesse instantanée. Un zéro à
  cet endroit ne prouve donc pas que le véhicule est arrêté. Cette vitesse de page n'est pas une preuve
  de disponibilité de la vitesse dans chaque callback `DashBoardInfo`.

## 4. Consommation instantanée : L/100 km, sans seconde conversion [A]

Le décodeur divise la valeur par 10 et l'affecte directement à `instFuelCont`, **sans utiliser la vitesse**.
Son consommateur `CanPsaTripCom` affiche cette même valeur avec la ressource
`base_res_fuelcon_unit_l_per_100km` et masque les valeurs négatives. Les deux côtés du contrat OEM
concordent donc sur une valeur déjà exprimée en **L/100 km**.

La conversion expérimentale depuis un débit supposé en L/h doit être écartée pour ce profil :

```text
Valeur reçue : 6 L/100 km
Conversion supplémentaire erronée : 100 × 6 / vitesse
À 50 km/h → 12 ; à 80 km/h → 7,5 ; à 100 km/h → 6
```

Un essai seulement à 100 km/h ne permet pas de distinguer les deux interprétations.

**Limite :** le contrat logiciel est étayé ; la précision physique du carburant ne l'est pas encore.
La radio d'origine, qui affichait les consommations instantanée et moyenne, a été retirée [V].
L'autonomie correspond au combiné d'après le propriétaire [V], mais cela ne valide pas les autres champs.

Une sélection `km/L`, `L/100 km`, `mpg` existe dans les ressources OEM [A]. Son effet sur la donnée série
n'a pas été démontré ; le rendu `CanPsaTripCom` étudié utilise L/100 km [?].

## 5. Ce que montrent les captures de septembre [V]

Comptages reproduits à partir des trois journaux comportant des valeurs numériques :

| Session | Événements de trajet enregistrés | Partiel 1 / moyenne | Instantané non négatif | `-0.1` | GPS individuel |
|---|---:|---|---|---:|---|
| 20 septembre | 1 562 | toujours `9999` / `0.0` | 1 542 valeurs, 0,0–16,6 | 20 | aucun ; 102 résumés de vitesse |
| 25 septembre, 09:42 | 80 | toujours `9999` / `0.0` | 75 valeurs, 0,4–11,6 | 5 | aucun |
| 27 septembre | 990 | toujours `9999` / `0.0` | 882 valeurs, 0,0–15,1 | 108 | 123 échantillons, couverture partielle |

Soit **2 632 événements** avec un partiel figé à `9999` et une moyenne à `0.0`.
Les journaux signalent respectivement 550, 136 et 605 callbacks omis par le filtre d'enregistrement :
ces fichiers ne sont pas un flux exhaustif.

Le 27 septembre, la vitesse du callback est explicitement inconnue. Dans les deux autres fichiers, ce
champ n'était pas enregistré : son absence ne prouve pas une absence de signal dans le véhicule.

Pour le 27 septembre, l'appariement exploratoire au GPS le plus proche à ±1 s donne 336 paires, utilisant
87 échantillons GPS distincts. Parmi les 311 paires à au moins 30 km/h :

- 10 présentent encore `instFuel=-0.1` : **indisponible ne signifie pas arrêté** ;
- 129 indiquent une position décodée `P` : **ce champ ne permet pas de filtrer le mouvement** dans cette capture.

Aucune paire ne satisfait la vérification complète de l'horodatage et de la qualité GPS utilisée pour les
captures plus récentes. Aucun de ces fichiers ne mesure indépendamment les litres consommés.

Les hypothèses « compteur OEM arrivé à sa limite », « sentinelle » et « problème de décodage » restent
ouvertes pour `9999`. La répétition du nombre ne permet pas de choisir entre elles [?].

## 6. Moyenne : estimation pondérée par la distance [M]

La moyenne OEM figée à `0.0` n'est pas une mesure exploitable dans ces captures. La méthode retenue pour
une estimation distincte consiste à intégrer la consommation instantanée déjà en L/100 km et une vitesse
récente, plutôt qu'à faire la moyenne arithmétique des valeurs affichées.

Pour deux échantillons valides séparés de `Δt` secondes, avec `v` en km/h et `c` en L/100 km :

```text
Δd [km] = (v₀ + v₁) / 2 × Δt / 3600
ΔL [L]  = (v₀ × c₀ + v₁ × c₁) / 2 × Δt / (100 × 3600)
C_moy [L/100 km] = 100 × ΣΔL / ΣΔd
```

Exemple : 1 s à 36 km/h et 4 L/100 km, puis un intervalle séparé de 1 s à 108 km/h et 8 L/100 km.
Les distances couvertes sont 0,01 et 0,03 km, les volumes estimés 0,0004 et 0,0024 L.
La moyenne vaut **7 L/100 km**, contre 6 pour une moyenne arithmétique incorrecte.

Règles de calcul :

- Garder uniquement une consommation finie, non négative, dont l'unité est identifiée. Un zéro valide
  reste zéro ; `-0.1` et les autres valeurs invalides interrompent l'intégration.
- Utiliser une vitesse récente. Le traitement actuel privilégie une vitesse véhicule disponible ; un
  secours GPS exige un échantillon datant d'au plus 2 s. Ce seuil d'âge seul ne certifie pas la précision GPS.
- Intégrer seulement deux échantillons successifs séparés de **1 à 5 000 ms**, sur une horloge monotone.
  Une donnée absente, une source différente ou un trou plus long coupe l'intervalle ; aucun raccord fictif.
- Exclure les intervalles de distance nulle. Après une reprise, conserver le cumul sans intégrer le temps
  passé sans mesures ; une ancienne moyenne doit être distinguée d'une moyenne alimentée récemment.

**C'est une estimation des intervalles couverts en mouvement**, pas la consommation complète du trajet :
elle ne mesure pas le carburant brûlé au ralenti et ne reconstruit pas les périodes manquantes. Elle
hérite aussi de toute erreur de consommation ou de vitesse. Les trois captures ci-dessus ne fournissent
pas assez d'intervalles couverts pour en tirer une moyenne physique fiable.

## 7. Température extérieure : trois représentations distinctes [A, V]

| Couche | Représentation | Traitement |
|---|---|---|
| Commande Raise `0x36`, `RePsaProtocol.parseOutTempInfo` | index 3 : bit 7 = signe, bits 0–6 = valeur absolue | `T = ±(octet & 0x7F)` ; ce n'est pas un complément à deux |
| Objet Android `DashBoardInfo` | `setAmbientTemp(T)` stocke `T + 40` ; le getter renvoie le champ stocké | soustraire 40 **au champ stocké**, puis valider |
| Objet Android `HvacInfo` | `parseOutTempInfo` transmet aussi la température Celsius à `setOutsideTemp` | ne pas lui appliquer automatiquement le décalage de `DashBoardInfo` |

Le setter `DashBoardInfo.setAmbientTemp` borne d'abord l'entrée entre −85 et 175 °C, puis ajoute 40.
Ce décalage appartient à l'objet Android : **ce n'est pas la formule de la commande Raise `0x36`**.

Dans le relevé du 12 juin, `ambient=76.0` devient ainsi **36 °C**. Le propriétaire indiquait environ
31 °C dehors ; l'écart restant n'est pas étalonné. Une seule comparaison ne suffit pas à établir la
précision du capteur ni à expliquer cet écart.

La lecture parallèle `ICar.outsideTempRaw=255` était indisponible dans cette session ; elle possède son
propre format et ne doit pas être décodée comme `DashBoardInfo`. La température du SoC n'est pas une
température extérieure.

## 8. Climatisation Raise : champs utiles [A]

Source : `RePsaProtocol.parseAirInfo`, commande `0x21`. Même convention d'index `bArr` que plus haut.

| Index | Lecture dans le décodeur OEM |
|---|---|
| 3 | bits 7 alimentation, 6 AC, 5 recyclage, 4 recyclage auto, 3 `AutoLight1`, 2 dual, 1 `AutoLight3`, 0 dégivrage arrière |
| 4 | bits 7–5 : distribution d'air ; si tous nuls, mode OEM 16. Nibble bas : ventilation ; zéro active `AutoLight2`, sinon niveau = nibble − 1 |
| 5, 6 | consignes gauche/droite = octet non signé × 0,5 |
| 7 | bit 7 dégivrage avant, bit 3 AC maximum |
| 8 | bit 7 alimentation de la ventilation arrière |
| 9 | bits 7–6 : intensité (`windIntensity`) |
| 10 | bits 7–5 : distribution d'air côté droit |

Le constructeur OEM fixe une plage avant de **14 à 32 °C**, l'unité avant à 0 et un niveau maximal de
ventilation de 8. Les états `LO` / `HI` doivent rester des limites : les déterminer par rapport à la
plage OEM avant toute conversion ou calibration, sans les transformer en températures ordinaires.
Les métadonnées de plage/unité droite peuvent être absentes ; le consommateur PSA utilise alors les
métadonnées avant communes.

Ces champs décrivent le décodeur ; ils ne prouvent pas que toutes les fonctions sont présentes sur la C4.
Lors du trajet du 17 octobre, le propriétaire n'utilisait pas la climatisation et `ac=0` est resté constant
[V]. Une variation de ventilation ou d'alimentation ne prouve donc pas l'activation du compresseur.

## 9. Fraîcheur des données et limites du décodeur [?]

- `RePsaProtocol.handlePacket` n'a pas été correctement reconstruit par JADX. Le parsing des champs est
  lisible, mais la chaîne complète de publication des callbacks n'est pas certifiée par cette source.
- La variante `RePsaProtocol_KeepOriginalDisplay` partage un objet `MiscInfo` entre plusieurs commandes.
  Un nouveau callback Dashboard peut donc transporter des champs déjà mémorisés ; il reste à établir
  quand la valeur de carburant elle-même a été renouvelée.
- Une limite de **5 s depuis la réception** évite d'utiliser indéfiniment une ancienne livraison. Elle
  ne mesure pas l'âge de génération de la donnée dans l'ECU.
- Une valeur identique répétée peut être normale. Son temps sans changement et le nombre de répétitions
  sont des indices à enregistrer, pas une preuve de gel du signal.
- Une connexion au service Android, un callback reçu et une mesure valide sont trois états distincts.

## 10. Vérifications restant à faire sur la voiture

1. Relever la version du firmware du boîtier **E Lpsa**, puis confirmer le profil sélectionné.
2. Observer le partiel 1 au combiné avant et après sa remise à zéro ; vérifier si `9999` évolue et si
   la moyenne OEM recommence à varier.
3. Capturer simultanément consommation, vitesse et GPS, à plusieurs vitesses stables au-dessus de
   30 km/h, avec démarrage, arrêt et pertes de livraison. Conserver les valeurs invalides et les trous.
4. Déterminer si chaque callback Dashboard correspond à du carburant nouveau ou à un état partagé.
5. Tester les changements `L/100 km`, `km/L`, `mpg` sans supposer que la donnée série change d'unité.
6. Contrôler la température extérieure à plusieurs températures et les états de climatisation par
   rapport aux commandes physiques ; distinguer consigne, température mesurée et état du compresseur.
7. Comparer le cumul estimé à des pleins complets et à une distance d'odomètre, en déclarant la couverture
   réelle. Cela évalue une consommation cumulée, pas la précision de chaque instantané.

## Sources et portée

Les résultats propres à cette C4 proviennent des sources OEM et des relevés décrits ci-dessus. Les
références suivantes servent à situer la recherche ; elles ne remplacent pas une capture Raise PSA :

- [LibreHU — CAN et véhicule](https://github.com/LibreHU/LibreHU-service/blob/main/docs/ivi-services/12-canbus.md)
  et [profils de voiture](https://github.com/LibreHU/LibreHU-service/blob/main/docs/can-vehicles.md) :
  organisation de la documentation et distinction bus CAN / protocole du boîtier.
- Sources OEM : `com/autoai/canbus/psa/protocol/RePsaProtocol.java` (`byteToInt`,
  `parsePsaTripComputer`, `parseOutTempInfo`, `parseAirInfo`),
  `com/autoai/canbus/psa/ui/CanPsaTripCom.java` et `com/autoai/canbus/DashBoardInfo.java`.
  Les numéros de ligne et les variantes peuvent différer selon la version de l'APK.

Les relevés complets ne sont pas joints : cette note ne contient ni coordonnées GPS, ni identifiants
personnels, ni détails du logiciel utilisé pour l'observation.
