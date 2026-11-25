# Notices concernant les dépendances tierces

## libusb

Le transport USB natif utilise libusb, bibliothèque distribuée sous licence GNU LGPL v2.1 ou ultérieure. Le système de build récupère une révision épinglée via le projet de build CMake communautaire de libusb :

- Projet de build : https://github.com/libusb/libusb-cmake
- Commit épinglé du projet CMake : `bea6567b63796ab27c1c33257d230284fb2d8316`
- Révision upstream épinglée dans son fichier de verrouillage : `ed4caebb6869f4504d0c5f86abed3ba9381ac4dc`
- Licence et code source upstream : https://github.com/libusb/libusb

Le build Android génère libusb comme bibliothèque partagée. Les notices et la licence LGPL correspondante doivent être conservées avec toute distribution.

## FFmpeg 9.1

Le décodeur vidéo utilise les bibliothèques partagées FFmpeg 9.1, distribuées sous LGPL 2.1 ou ultérieure, avec uniquement les composants nécessaires à DV, MJPEG et conversion de pixels.

- Version : FFmpeg 9.1, publiée le 20 novembre 2026
- Commit exact : `74e4393595cf2b5857f0da925c4e0dd74ea306e5` (tag signé `n9.1`)
- Source : https://github.com/FFmpeg/FFmpeg/tree/74e4393595cf2b5857f0da925c4e0dd74ea306e5
- Licence : https://github.com/FFmpeg/FFmpeg/blob/n9.1/COPYING.LGPLv2.1
- Configuration : bibliothèques partagées, `avcodec`, `avutil`, `swscale`, décodeurs `dvvideo` et `mjpeg` uniquement ; réseau, programmes, documentation, composants GPL et non-free désactivés.

Le build n'active ni `--enable-gpl` ni `--enable-nonfree`. Les bibliothèques FFmpeg sont fournies séparément de `libpanasonic_usb.so`, afin de conserver le lien dynamique requis par la LGPL. Toute distribution doit inclure les notices et la licence FFmpeg ; les sources exactes et les instructions de reconstruction doivent rester accessibles.

Le script `scripts/build-ffmpeg-android.sh` reconstruit la révision épinglée pour Android arm64-v8a.

## uvcdv-capture

Le dépôt https://github.com/boris-timofeenko/uvcdv-capture (GPL-3.0-or-later) est consulté comme documentation de protocole et référence de diagnostic uniquement. Son code n'est pas intégré dans Panasonic Monitor.
