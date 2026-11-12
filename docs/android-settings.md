# Entrées LibreHU dans les Paramètres d'Android (ROM LineageOS)

Sous la ROM LineageOS 16.0, LibreHU-service ajoute ses entrées aux **Paramètres d'Android**. Chaque entrée ouvre la
page du service correspondante, avec son interface habituelle ; le bouton retour ramène aux Paramètres. Code :
`app/.../systemsettings/SystemSettings.kt` et les alias `.systemsettings.*` de `MainActivity` dans le manifeste.

| Rubrique d'Android | Entrée | Résumé | Page du service ouverte |
|---|---|---|---|
| Accueil des Paramètres (au-dessus de Système) | LibreHU | Véhicule, audio, écran, Bluetooth (fixe) | Accueil |
| Affichage | Écran et lumières | en direct : luminosité et/ou mode sombre selon les feux | Affichage |
| Son | Audio de l'autoradio | Tonalité, balance, sorties, source (fixe) | Audio |
| Appareils connectés | Bluetooth voiture | en direct : « *téléphone* connecté », aucun téléphone connecté, Bluetooth désactivé | Bluetooth |
| Appareils connectés | Android Auto | en direct : session d'Open Headunit (connexion, à l'écran…) ou appli absente | Android Auto |
| Système | Autoradio | en direct : état de la liaison MCU | Système |

## Quand elles apparaissent

- **Appli système seulement.** Les Paramètres d'Android 9 n'acceptent les entrées que des applis système
  (`if (!resolved.system) continue; // only system ones` dans `TileUtils`). Sous LineageOS, le service est dans
  `/system/priv-app` : les entrées apparaissent. Il le reste après une mise à jour par OTA (appli système mise à jour).
  Sous la ROM stock, le service est une appli utilisateur : **rien ne change**.
- **Elles suivent le service.** Service désinstallé ou désactivé : les entrées disparaissent d'elles-mêmes, car les
  Paramètres les relisent auprès d'Android à chaque ouverture.
- **Langue.** Titres et résumés suivent la langue d'Android (français, anglais).

## Comment ça marche

- Chaque entrée est un `activity-alias` de `MainActivity` qui répond à l'action
  `com.android.settings.action.EXTRA_SETTINGS`. Les métadonnées donnent la rubrique (`com.android.settings.category` :
  `…ia.homepage`, `…ia.display`, `…ia.sound`, `…ia.connect`, `…ia.system`), le titre, l'icône et le résumé.
  `org.librehu.service.TAB` désigne la page du service que l'entrée ouvre. Les alias n'ont ni libellé ni icône
  propres : dans les applis récentes, la tâche garde le nom et l'icône de LibreHU-service.
- **Résumés en direct** (`com.android.settings.summary_uri`, `content://org.librehu.service.systemsettings/<méthode>`) :
  à chaque affichage de la page, les Paramètres appellent `call()` du fournisseur `SummaryProvider` et affichent la clé
  `com.android.settings.summary` du résultat.
- **Accueil : résumé fixe.** Sur leur accueil, les Paramètres d'Android 9 n'affichent un résumé en direct que pour
  leurs propres entrées. Le chargeur des résumés s'arrête sur les autres applis (`// Not within Settings, can't load
  Summary directly. // TODO: Load summary indirectly.` dans `SummaryLoader`). L'entrée LibreHU de l'accueil a donc un
  résumé fixe.
- **Position.** Sur l'accueil, les Paramètres donnent la priorité 0 aux entrées des autres applis et trient par
  priorité décroissante : « LibreHU » tombe juste au-dessus de « Système ». Dans les rubriques, l'entrée va en bas de la
  page. Affichage, Son et Système replient leurs dernières lignes sous « Options avancées » (« Advanced » en anglais ;
  4, 9 et 4 lignes visibles) : l'entrée y est, après dépliage, et son titre figure dans le résumé du bouton.
- **Confidentialité.** Le résumé Bluetooth contient le nom du téléphone. Le fournisseur ne répond qu'à l'uid système
  (les Paramètres tournent en `android.uid.system`), à root et au service lui-même. Les autres reçoivent `null`, jamais
  d'exception : les Paramètres lisent les résumés sur un fil à eux sans attraper d'exception.

## Test

`.github/workflows/settings-entries-test.yml` (`tools/settings_entries_test.py`) teste sur un émulateur Android 9.
Le service y est installé dans `/system/app`, comme dans la ROM. Le test cherche chaque entrée dans sa page des
Paramètres, avec son résumé. Il l'ouvre, vérifie la page du service affichée, puis le retour aux Paramètres. Il appelle
aussi le fournisseur en root (réponse) et en shell (`null`), puis refait l'accueil et Affichage en français. Captures
d'écran et vidages d'interface sont dans l'artefact `settings-entries-test`. L'émulateur n'a pas de Bluetooth : sa
page « Appareils connectés » plante (bug d'Android 9 lui-même, `LocalBluetoothManager` nul dans
`BluetoothDeviceUpdater`). Pour Bluetooth voiture et Android Auto, le test vérifie donc le résumé auprès du fournisseur
et la page ouverte par l'entrée, sans passer par cette page. L'autoradio a le Bluetooth.

## Sources (code d'Android 9 / LineageOS 16.0)

- [TileUtils.java](https://github.com/LineageOS/android_frameworks_base/blob/lineage-16.0/packages/SettingsLib/src/com/android/settingslib/drawer/TileUtils.java)
  : découverte des entrées, applis système seulement, métadonnées, appel du fournisseur de résumé.
- [CategoryKey.java](https://github.com/LineageOS/android_frameworks_base/blob/lineage-16.0/packages/SettingsLib/src/com/android/settingslib/drawer/CategoryKey.java)
  : clés des rubriques.
- [DashboardAdapter.java](https://github.com/LineageOS/android_packages_apps_Settings/blob/lineage-16.0/src/com/android/settings/dashboard/DashboardAdapter.java)
  : entrée de l'accueil (icône ronde, couleur `com.android.settings.bg.hint`, résumé fixe).
- [DashboardFeatureProviderImpl.java](https://github.com/LineageOS/android_packages_apps_Settings/blob/lineage-16.0/src/com/android/settings/dashboard/DashboardFeatureProviderImpl.java)
  : entrées des rubriques, résumé en direct par `summary_uri`, ouverture.
- [SummaryLoader.java](https://github.com/LineageOS/android_packages_apps_Settings/blob/lineage-16.0/src/com/android/settings/dashboard/SummaryLoader.java)
  : résumés de l'accueil, propres aux Paramètres.
