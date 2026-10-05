# GPS et heure

Onglet **GPS et heure** de l'app LibreHU.

## Test du GPS

Fournisseur de localisation `gps` d'Android (GNSS de la puce MediaTek) : état, satellites visibles / utilisés
(`GnssStatus`, constellation G/R/C/E/J/S et C/N0), position, précision, vitesse, altitude, heure GPS, délai du
premier fix. Demande `ACCESS_FINE_LOCATION` (bouton « Permissions »).

## Sources de l'heure

| Réglage | Effet | Défaut |
|---|---|---|
| Heure Android depuis la MCU | au démarrage de la liaison, heure Android ← RTC de la MCU (`09 00` / `09 01`) | oui |
| Horloge MCU depuis Android | chaque minute, Android → MCU (affichage de veille) | oui |
| Heure depuis le GPS | au démarrage puis toutes les 15 min / 1 h / 4 h / 12 h ; attend un fix 5 min max ; corrige l'âge du fix (`elapsedRealtimeNanos`) ; ne change l'heure que si l'écart dépasse 2 s, puis l'envoie à la MCU | non |

Boutons : synchro GPS immédiate, envoyer à la MCU, relire la MCU (`F0 09 00`), réglages date et heure d'Android.

Régler l'heure d'Android demande `SET_TIME` (installation privilégiée, voir `install/`). Le GPS et la MCU donnent
l'heure UTC / locale, pas le **fuseau horaire** : il se règle dans Android.
