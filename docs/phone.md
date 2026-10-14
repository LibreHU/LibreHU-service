# Téléphone : infos de l'iPhone et du téléphone Bluetooth

`phone/PhoneInfo.kt`, `phone/PhoneWidget.kt`, page **Bluetooth → Téléphone** (`ui/PhoneScreen.kt`), widget
« Téléphone ».

## Téléphone Bluetooth (Android et iPhone)
Ce que donne le mains libres (HFP, indicateurs de la passerelle) : nom, adresse, batterie en 5 paliers (affichée
« environ x % »), signal sur 5, opérateur, itinérance. Le Bluetooth n'a ni température ni cycles de batterie. Pour un
téléphone Android, il n'y a pas d'autre source sans appli compagnon sur le téléphone ou débogage USB (ADB).

## iPhone (CarPlay filaire, appli CarPlay LibreHU)
L'appli `org.librehu.carplay` lit l'iPhone pendant qu'il est branché et envoie `org.librehu.carplay.PHONE_INFO` au
seul paquet `org.librehu.service` (nom et adresse Bluetooth sont personnels) :
- **Lockdown** (la session déjà appairée pour CarPlay filaire, comme libimobiledevice) : `GetValue` → nom
  (`DeviceName`), modèle (`ProductType`, ex. `iPhone15,2`), iOS (`ProductVersion`, `BuildVersion`), adresse Bluetooth ;
  domaine `com.apple.mobile.battery` → niveau, en charge, alimentation externe ;
- **diagnostics d'iOS** : service `com.apple.mobile.diagnostics_relay`, requête `IORegistry` sur la classe
  `IOPMPowerSource` (ce que fait pymobiledevice3 `diagnostics battery`) → cycles (`CycleCount`), température
  (`Temperature`, centièmes de °C), capacité d'origine (`DesignCapacity`) et maximale (`AppleRawMaxCapacity`), tension,
  courant ; l'« état de la batterie » affiché est max / origine (estimation, iOS a son propre calcul) ;
- **iAP2** : `DeviceInformationUpdate` (nom), `PowerUpdate` (chargeur externe, état de charge, niveau),
  `DeviceTransportIdentifier` (adresse Bluetooth).
Relu toutes les minutes (première lecture 10 s après la mise en route de CarPlay). Le dernier relevé est gardé et
affiché « vu le … » une fois l'iPhone débranché. Ce que l'iPhone refuse n'est pas affiché.

## iPhone branché : Bluetooth et appairage
Réglage **Activer le Bluetooth et appairer l'iPhone** (par défaut : oui) : à l'arrivée d'un appareil USB Apple
(`05ac`), le Bluetooth est activé ; dès que l'appli CarPlay a donné l'adresse Bluetooth de l'iPhone, l'autoradio
demande l'appairage (`createBond`) s'ils ne sont pas encore appairés : accepter sur l'iPhone. L'appairage « hors bande »
d'iAP2 (sans confirmation) n'est pas fait : il demande l'échange de clés de lien avec la pile Bluetooth d'Android.

## Widget
L'iPhone branché (batterie, ⚡ en charge, température, cycles, état) ou, sinon, le téléphone Bluetooth (batterie,
signal, opérateur) ; un toucher ouvre la page Téléphone.

## À vérifier sur l'autoradio
- que l'iPhone accepte `diagnostics_relay` par la session de CarPlay (sinon : seulement nom, modèle, iOS, batterie) ;
- l'échelle du niveau de batterie iAP2 (0..65535 ou pourcentage, les deux sont gérés).
