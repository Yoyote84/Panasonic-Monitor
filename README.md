# Panasonic Monitor

Application Android destinée au Panasonic NV-GS140 et à son utilisation comme source vidéo USB sur un smartphone Android.

## Les deux modes sont sélectionnables

L'utilisateur choisit explicitement le mode correspondant au réglage USB du caméscope :

| Mode | Identifiant observé sur le NV-GS140 | Usage |
|---|---:|---|
| Webcam | `04DA:231A` | Flux webcam USB du caméscope |
| Monitor DV / Motion DV | `04DA:2318` | Flux UVC-DV annoncé par le caméscope |

L'application ne modifie pas le mode du caméscope. Il faut choisir le même mode dans le menu du NV-GS140 et dans Panasonic Monitor. Si le PID connecté ne correspond pas au mode choisi, l'application affiche un avertissement et n'essaie pas de lancer le moteur incompatible.

## Étape 1 — Diagnostic USB

La première version a identifié les périphériques USB, les interfaces et les endpoints, puis lu les descripteurs USB bruts. Sur le NV-GS140 en mode Motion DV, le rapport réel de l'utilisateur a confirmé :

- VID:PID `04DA:2318`
- interface UVC VideoControl ;
- interface UVC VideoStreaming ;
- descripteur `VS_FORMAT_DV` (`0x0C`) ;
- endpoint vidéo isochrone IN `0x81`, alt-setting 1.

Le mode Webcam a été observé sous VID:PID `04DA:231A`, avec interface vidéo UVC et endpoint isochrone. Le rapport de ses descripteurs complets reste à confirmer.

## Étape 2 — Transport USB natif

La version 0.2.0 ajoute :
- sélecteur Webcam / Monitor DV, mémorisé entre les lancements ;
- identification du mode par les VID/PID constatés sur le NV-GS140 ;
- bibliothèque native Android (NDK / C++) ;
- intégration libusb par `UsbDeviceConnection.getFileDescriptor()` et `libusb_wrap_sys_device()` après l'autorisation USB Android ;
- recherche de `VS_FORMAT_DV` pour Monitor DV et `VS_FORMAT_MJPEG` pour Webcam ;
- négociation UVC PROBE/COMMIT ;
- sélection de l'alt-setting vidéo isochrone ;
- échantillonnage des transferts isochrones pendant cinq secondes avec comptage des paquets, octets et erreurs.

**Cette étape ne décode pas encore l'image, n'affiche pas la vidéo et n'enregistre aucun fichier.** Elle vérifie que le téléphone peut ouvrir le périphérique, négocier le flux UVC et recevoir des données isochrones. Ces résultats doivent encore être testés sur le NV-GS140 physique.

### Test sur le Galaxy Z Flip 6

1. Installe l'APK debug de cette étape.
2. Place le NV-GS140 dans le mode Webcam ou Motion DV.
3. Débranche/rebranche le câble USB après tout changement de mode.
4. Dans Panasonic Monitor, sélectionne exactement le même mode.
5. Touche **Détecter et analyser le mode USB** et accepte les permissions Android.
6. Touche **Tester le transport natif (5 secondes)**.
7. Pour Motion DV, commence avec le caméscope en mode VCR/lecture et une cassette réellement en lecture. Un flux DV peut ne pas arriver si le transport de bande n'est pas lancé.
8. Partage le rapport complet. Recommence dans l'autre mode pour vérifier les deux moteurs.

### Comment interpréter le test natif

- `UVC PROBE négocié` et `UVC COMMIT accepté` : le caméscope a accepté les paramètres de flux.
- `Paquets avec données > 0` et `Payload UVC > 0` : Android a reçu des données vidéo isochrones.
- `Flags d'erreur UVC` : paquets marqués en erreur par le périphérique ; cela n'est pas à lui seul une mesure de corruption d'image.
- Aucun payload : vérifier le mode caméra/lecture, l'alimentation et le câble. Cela ne prouve pas à lui seul que le format est incompatible.

## Compilation

Le workflow GitHub Actions compile les tests Java et l'APK arm64 avec JDK 17, Android SDK 35, NDK 27.2.12479018, CMake 3.22.1 et Gradle 8.13.

Compilation locale :

```bash
gradle :app:testDebugUnitTest :app:assembleDebug
```

APK : `app/build/outputs/apk/debug/app-debug.apk`

## Architecture

- Android Java : permissions, détection, choix du moteur et interface.
- NDK C++ : transport UVC natif.
- libusb : contrôle UVC et transferts isochrones.
- La version 0.2.0 n'intègre pas le code GPL de `uvcdv-capture`; ce projet est seulement une référence de protocole. Voir [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Feuille de route en 5 étapes

1. Diagnostic USB et identification des modes annoncés par le NV-GS140 — réalisé et testé.
2. Transport natif UVC-DV / Webcam et échantillonnage isochrone — prototype Android ajouté, validation matérielle à effectuer.
3. Décodage, aperçu en direct et synchronisation audio/vidéo.
4. Enregistrement DV original, MP4 et bibliothèque de médias.
5. Stabilité, reconnexion, optimisation, tests finaux et APK final.
