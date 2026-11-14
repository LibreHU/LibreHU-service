# Internet du téléphone par le câble, avec Android Auto filaire (v201)

Android Auto filaire et l'internet du téléphone **sur le même câble USB**, sans root sur le téléphone : par le
débogage USB. Option de LibreHU-service, onglet **Système → Réseau**, carte « Internet du téléphone par le câble »
(désactivée par défaut). Code : `app/.../net/cable/`, `core/.../adb/`, `core/.../relay/`.

## Pourquoi pas le partage de connexion USB

Android Auto filaire met l'USB du téléphone en mode accessoire (`startAccessoryMode()` de `UsbDeviceManager`) et
Android n'active qu'une fonction USB à la fois (`UsbManager.areSettableFunctions` : une seule, sauf RNDIS + NCM ;
l'accessoire n'est pas réglable). Le partage USB (RNDIS / NCM) coupe donc Android Auto, et l'inverse. Avec le débogage
USB actif, le téléphone en mode accessoire montre une **deuxième interface : adb** (0x18d1:0x2d01, doc AOA). Open
Headunit n'utilise que la première (interface 0) ; LibreHU-service prend celle d'adb.

## Comment ça marche

1. **USB** : le service obtient le droit sur l'appareil sans la fenêtre d'Android (aide root `UsbGrant`,
   `IUsbManager.grantDevicePermission`), sinon la fenêtre s'affiche sur l'autoradio ; il prend l'interface adb
   (classe 0xff, sous-classe 0x42, protocole 1).
2. **adb** : l'autoradio est l'hôte adb (`AdbConnection`) : CNXN, puis AUTH avec sa clé RSA 2048
   (`files/adbkey`, stockage protégé). Première fois : le téléphone demande « Autoriser le débogage USB ? » →
   cocher **« Toujours autoriser sur cet ordinateur »** (nom affiché `librehu@<modèle>`).
3. **Relais sur le téléphone** : l'APK de LibreHU-service lui-même est copié dans `/data/local/tmp/librehu-relay.apk`
   (seulement quand il a changé, comparaison SHA-256) puis lancé en utilisateur shell :
   `CLASSPATH=/data/local/tmp/librehu-relay.apk app_process / org.librehu.core.relay.PhoneRelay`. C'est le relais de
   [gnirehtet](https://github.com/Genymobile/gnirehtet) (Genymobile, Apache-2.0), rôles inversés : le téléphone ouvre
   les connexions TCP et UDP vers internet. Il écoute sur 127.0.0.1 (port libre), annonce son port et un **secret de
   16 octets** sur sa sortie standard (seul le flux adb la voit) ; le client doit envoyer ce secret d'abord. Il s'arrête
   quand le câble est débranché (fin de son entrée standard) ou quand l'autoradio le quitte. Journal :
   `/data/local/tmp/librehu-relay.log`.
4. **VPN de l'autoradio** (`CableVpnService`) : 10.0.0.2/32, route 0.0.0.0/0, DNS 8.8.8.8, MTU 16384 (réglages de
   l'appli gnirehtet) ; chaque paquet IPv4 des applis part dans le flux adb vers le relais, les réponses reviennent.
   Open Headunit et CarPlay restent hors du VPN. Accord VPN d'Android : donné par root (`appops … ACTIVATE_VPN
   allow`), sinon la fenêtre d'Android en activant l'option.

## Ce qu'il faut

- Téléphone : **Options pour les développeurs → Débogage USB** activé (sinon la carte affiche « sans débogage USB »).
- Autoradio : LibreHU-service v201 ou plus, option activée ; root conseillé (droit USB et accord VPN sans fenêtre).
- Android Auto filaire comme d'habitude (Open Headunit).

## Sécurité

Le débogage USB donne à l'ordinateur autorisé (ici l'autoradio) un accès adb au téléphone (shell, installation
d'applis…). À n'activer qu'avec son propre téléphone et son propre autoradio. Le relais n'écoute que sur 127.0.0.1 et
refuse tout client sans le secret. Android peut retirer l'autorisation d'un ordinateur resté 7 jours sans connexion
(Android 11 et plus) : il suffit de réaccepter.

## Limites

- IPv4 seulement (comme gnirehtet) ; pas d'ICMP (ping).
- Le VPN prend tout le trafic de l'autoradio tant que le téléphone est branché (Wi-Fi compris).
- Un autre VPN sur l'autoradio remplace celui-ci (Android n'en garde qu'un).

## Tests

- `./gradlew -PcoreOnly :core:test` : la clé contre les outils d'adb (`AdbKeyTest` : clé publique identique à celle
  d'`adb keygen`, signature identique à celle d'OpenSSL `RSA_sign(NID_sha1)`), la connexion contre un petit adbd
  (`AdbConnectionTest` : clé connue, nouvelle clé, contrôle de flux, `sync:`), le relais avec de vrais paquets UDP et
  TCP (`PhoneRelayTest`), le découpage des paquets (`Ipv4FramerTest`).
- Workflow **Internet over the cable test (Android 14 phone)** : un émulateur Android 14 joue le téléphone ; l'APK de
  release (R8) est poussé, le relais lancé par `app_process`, puis DNS et HTTP à travers lui (`PhoneProbe.kt`,
  `tools/phone_internet_test.sh`). La partie USB et le VPN de l'autoradio demandent l'appareil réel.

## Variante avec un téléphone rooté (non faite)

Possible mais expérimentale : un module Magisk / KernelSU sur le téléphone recomposerait la fonction USB en
« accessoire + NCM » dès le passage en Android Auto (configfs, à la main : la HAL USB refuse cette combinaison, par
exemple `validateAndSetVidPid` des Pixel → « Combination not supported »), puis ferait le NAT et le DHCP lui-même
(Android ne voit qu'un accessoire et ne partage rien). Plus rapide (pas d'adb ni de VPN), mais propre à chaque
téléphone et il faut déverrouiller le bootloader.

## Sources

[protocol.md](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/docs/dev/protocol.md),
[sync.md](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/docs/dev/sync.md),
[zero_length_packet.md](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/docs/dev/zero_length_packet.md),
[android_pubkey.cpp](https://android.googlesource.com/platform/system/core/+/refs/heads/main/libcrypto_utils/android_pubkey.cpp),
[auth.cpp d'adb](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/client/auth.cpp),
[doc AOA](https://source.android.com/docs/core/interaction/accessories/aoa) (0x2D01 = accessoire + ADB),
[UsbManager.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/hardware/usb/UsbManager.java),
[socket_spec.cpp](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/socket_spec.cpp)
(adbd refuse les connexions TCP vers l'extérieur : d'où le relais),
[gnirehtet](https://github.com/Genymobile/gnirehtet),
[UsbGadget.cpp des Pixel (gs101)](https://android.googlesource.com/device/google/gs101/+/refs/heads/main/usb/gadget/UsbGadget.cpp).

## Réglages IP des interfaces filaires (14/11/2026)

Onglet Système → Réseau, bouton « Réglages IP » sous chaque interface `eth*` / `usb*` que le service Ethernet
d'Android gère : DHCP (par défaut) ou IPv4 statique (adresse, longueur de préfixe, passerelle et DNS facultatifs).

- Appliqué par le service Ethernet d'Android (`IEthernetManager.setConfiguration`, API cachée d'Android 9) depuis un
  petit programme lancé en root (`net/EthernetHelper.kt`, `app_process`) : l'uid 0 passe la vérification
  `CONNECTIVITY_INTERNAL` sans restriction d'API cachée. Android garde le réglage (`/data/misc/ethernet/ipconfig.txt`)
  et relance l'interface avec.
- Un profil par type d'appareil (`net/EthernetIp.kt`) : `rndis` (tout téléphone Android en partage de connexion USB :
  l'adresse MAC change à chaque fois), `iphone` (partage de connexion iPhone, ipheth), `mac:<adresse>` (chaque
  adaptateur). Les noms d'interface ne sont pas stables (un iPhone et un adaptateur se partagent eth0 / eth1 selon
  l'ordre de branchement) : le profil est réappliqué 6 s après chaque branchement USB et 30 s après le démarrage du
  service, seulement si le réglage d'Android diffère.
- Validation (`core/.../net/IpSettings.kt`, tests `IpSettingsTest`) : adresse et passerelle IPv4, préfixe de 1 à 32,
  passerelle dans le réseau de l'adresse, 2 DNS au plus.

