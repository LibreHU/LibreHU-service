# 9. Autres modules

| Module | Ce qu'il fait [A] | Intérêt pour la réécriture |
|---|---|---|
| **Media** (`MediaService`, 33 méthodes + callbacks) | Scanner de fichiers (USB, SD) avec base `ScanProvider` (`content://com.jancar.services/…`) et tags ID3 ; sessions média Jancar et tierces ; commandes de lecture des lecteurs Jancar (lecture/pause, suivant, zones) ; interdiction de la vidéo frein desserré | Faible : Android a `MediaStore` et `MediaSession`. Garder seulement « commande du lecteur actif » (touches volant → `MediaSession`) |
| **Voice** (`VoiceService`) | Assistant aispeech (`com.aispeech.lyra.daemon`, `com.jancar.voiceassistant`) : ouverture, focus, actions AVM | Aucun (dépend d'un service chinois) |
| **Navigation** (`NavigationService`) | Récupère les infos de guidage des apps Gaode / MX (broadcasts) pour le combiné | Faible |
| **Cluster** (`ClusterService`, `McuCluster`) | Envoie média, guidage, BT, heure au combiné d'instruments via la MCU | Sans objet (pas de combiné connecté sur cette carte) |
| **DAB** (`DABService`, `DABDevice`) | Tuner DAB en SPI (API Silicon Labs / NXP dans la lib native) | Seulement si un module DAB est présent |
| **Bluetooth** (`BluetoothServiceAgent`, `BluetoothCtrl`) | Relais vers `com.jancar.btservice` : décrocher, raccrocher, musique, état d'appel (pour le mute et les priorités) | Moyen : les priorités audio de l'appel sont à garder ; la pile BT reste celle d'Android |
| **Projection** (`CarPlayUtil`, `ACAndroidAutoUtil`, `HiCarUtil`, `ZLinkUtil`, `WeLinkUtil`, `SpeedPlayUtil`, `ElinkUtil`) | Coordination avec les apps de projection : focus audio, marche arrière, ACC, `vendor.iapd.start` (MFi) | Moyen : garder les signaux (ACC, marche arrière, nuit) sous forme de broadcasts standard |
| **TBox, AVM 360, télécommande, écran secondaire** | Projets spécifiques à certains clients | Aucun |
