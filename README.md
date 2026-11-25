# Panasonic Monitor

Application Android dédiée au Panasonic NV-GS140 et à son utilisation comme source vidéo USB sur un smartphone Android.

## Objectif immédiat — Étape 1

Créer un APK de diagnostic USB qui :
- détecte les périphériques USB connectés au téléphone ;
- demande les autorisations Android nécessaires ;
- lit les descripteurs USB bruts et décrit interfaces et points de terminaison ;
- recherche les descripteurs UVC `VS_FORMAT_DV` (`0x0C`) et `VS_FORMAT_MJPEG` (`0x06`) ;
- exporte un rapport texte partageable pour analyser le NV-GS140 dans ses différents modes USB.

Cette étape ne prétend pas encore lire la vidéo. Elle doit établir les capacités réellement exposées par l'exemplaire du caméscope testé.

## Feuille de route

1. Diagnostic USB et identification des modes annoncés par le NV-GS140.
2. Étude et prototype du transport vidéo, notamment UVC-DV et transferts isochrones natifs.
3. Décodage, affichage en direct et synchronisation audio/vidéo.
4. Enregistrement des flux originaux et des copies MP4, puis gestion des fichiers.
5. Tests sur le matériel réel, stabilité et optimisation.

## Appareil cible

- Téléphone de test : Samsung Galaxy Z Flip 6 (Android).
- Caméscope : Panasonic NV-GS140 (MiniDV).
- Connexion : USB-C OTG vers USB mini-B, avec alimentation externe du caméscope recommandée.

## État

Le dépôt démarre avec l'étape 1. La prise en charge réelle de `VS_FORMAT_DV` par le NV-GS140 reste à confirmer par le rapport USB du matériel.
