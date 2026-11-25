# Notices concernant les dépendances tierces

## libusb

Le transport USB natif utilise libusb, bibliothèque distribuée sous licence GNU LGPL v2.1 ou ultérieure. Le système de build récupère une révision épinglée via le projet de build CMake communautaire de libusb :

- Projet de build : https://github.com/libusb/libusb-cmake
- Commit épinglé du projet CMake : `bea6567b63796ab27c1c33257d230284fb2d8316`
- Révision upstream épinglée dans son fichier de verrouillage : `ed4caebb6869f4504d0c5f86abed3ba9381ac4dc`
- Version décrite : `v1.0.30-56-g...`
- Licence et code source upstream : https://github.com/libusb/libusb

Le build Android génère libusb comme bibliothèque partagée et l'APK l'embarque séparément de `libpanasonic_usb.so`. Les notices et la licence LGPL correspondante doivent être conservées avec toute distribution.

## uvcdv-capture

Le dépôt https://github.com/boris-timofeenko/uvcdv-capture (GPL-3.0-or-later) est consulté comme documentation de protocole et référence de diagnostic uniquement. Son code n'est pas intégré dans Panasonic Monitor.
