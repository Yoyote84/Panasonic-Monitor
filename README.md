# Panasonic Monitor

Application Android destinée au Panasonic NV-GS140 et à son utilisation comme source vidéo USB sur un smartphone Android.

## Les deux moteurs sont sélectionnables indépendamment du PID USB

L'utilisateur choisit explicitement le moteur logiciel souhaité :

| Choix dans Panasonic Monitor | Descripteur UVC exigé | Usage |
|---|---|---|
| Webcam | `VS_FORMAT_MJPEG` (`0x06`) | Flux webcam Motion-JPEG |
| Monitor DV / Motion DV | `VS_FORMAT_DV` (`0x0C`) | Flux DV natif UVC |

Le VID/PID reste affiché dans le rapport pour le diagnostic, mais **n'est pas utilisé pour déduire ou verrouiller le mode**. C'est le descripteur de format UVC réellement annoncé qui détermine si le moteur choisi est compatible. Le choix dans l'application reste indépendant de la personnalité USB du caméscope, sans tenter de forcer un format que le périphérique n'annonce pas.

## Étape 1 — Diagnostic USB

La première version a identifié les périphériques USB, les interfaces et les endpoints, puis lu les descripteurs USB bruts. Les rapports matériels fournis par l'utilisateur ont montré des interfaces UVC VideoControl / VideoStreaming et un endpoint vidéo isochrone IN. Le rapport Motion DV a en particulier confirmé le descripteur `VS_FORMAT_DV` (`0x0C`) sur le NV-GS140.

Les identifiants produits Panasonic `04DA:231A` et `04DA:2318` ont été observés pendant les essais. Ils sont conservés comme informations de diagnostic uniquement : aucun moteur n'est lié à l'un de ces PID.

Le format MJPEG du mode Webcam doit être confirmé à partir de ses descripteurs complets ; la présence du format DV ne doit pas être déduite de l'identifiant produit.

## Étape 2 — Transport USB natif

La version 0.2.1 ajoute ou corrige :

- sélecteur logiciel Webcam / Monitor DV, mémorisé entre les lancements ;
- sélection de moteur fondée sur `VS_FORMAT_MJPEG` ou `VS_FORMAT_DV`, sans verrouillage sur le PID ;
- affichage séparé du VID/PID détecté et du format UVC demandé ;
- analyse du descripteur UVC de streaming et, en secours non ambigu, des descripteurs de configuration ;
- vérification qu'un endpoint isochrone vidéo est bien un endpoint IN ;
- bibliothèque native Android (NDK / C++) ;
- intégration libusb par `UsbDeviceConnection.getFileDescriptor()` et `libusb_wrap_sys_device()` après l'autorisation USB Android ;
- négociation UVC PROBE/COMMIT ;
- vérification de l'index de format retourné par PROBE ;
- sélection de l'alt-setting isochrone qui offre la plus petite capacité suffisante au payload UVC annoncé, avec avertissement si aucun alt-setting ne suffit ;
- échantillonnage des transferts isochrones pendant cinq secondes avec comptage des paquets, octets et erreurs.
- diagnostic de l'état d'un éventuel pilote noyau et détachement automatique ciblé de la seule interface VideoStreaming pendant sa réservation, avec restauration à la libération ; les interfaces VideoControl et audio ne sont pas détachées ;
- dimensionnement adaptatif des URB isochrones sous un plafond prudent de 15 Kio par transfert pour éviter les limites USBFS/mémoire observées sur Android récent.

**Cette étape ne décode pas encore l'image, n'affiche pas la vidéo et n'enregistre aucun fichier.** Elle vérifie que le téléphone peut ouvrir le périphérique, négocier le flux UVC et recevoir des données isochrones. Les transferts doivent être validés sur le NV-GS140 physique : une compilation réussie ne remplace pas le test matériel.

#### État de validation matériel — 25 novembre 2026

- **Monitor DV : transport positif.** Sur Galaxy Z Flip 6 + NV-GS140, le caméscope a annoncé `VS_FORMAT_DV`, accepté PROBE/COMMIT et livré 17 823 840 octets de payload UVC (37 133 paquets avec données) pendant l'échantillonnage de cinq secondes. Les erreurs de paquets et de soumission étaient toutes deux à zéro. Les 7 200 flags UVC `ERR` sont comptés à part et ne sont pas assimilés à des erreurs USB de transfert.
- **Nettoyage libusb : correction logicielle poussée.** Le FD fourni à `libusb_wrap_sys_device()` est désormais gardé ouvert jusqu'après `libusb_close()`, et l'interface est libérée avant la fermeture du handle. Ce correctif doit encore être confirmé par un nouveau test sur le caméscope, notamment l'absence de l'erreur de libération observée précédemment.
- **Webcam MJPEG : validation matérielle manquante.** Les descripteurs MJPEG sont annoncés, mais il faut encore obtenir un payload UVC non nul sur ce mode avec la nouvelle version.

**Conclusion : l'étape 2 est avancée et le transport DV est validé, mais elle n'est pas encore déclarée complète.** Il manque la confirmation du nettoyage après le correctif et un échantillon positif du moteur Webcam MJPEG.

### Rapport Webcam matériel — 25 novembre 2026, 14:48

- Descripteurs : MJPEG annoncé, endpoint isochrone IN 0x81 présent, alt-setting 1.
- Autorisation USB Android : accordée.
- Pilote noyau VideoStreaming : déclaré inactif.
- Réservation : `USBDEVFS_CLAIMINTERFACE errno=16 (EBUSY)`, puis `libusb_claim_interface=-6 (LIBUSB_ERROR_BUSY)`.
- PROBE/COMMIT et transfert isochrone : non atteints.

**Diagnostic :** l'échec intervient à la réservation de l'interface VideoStreaming, pas dans la sélection MJPEG. EBUSY alors que le pilote noyau est déclaré inactif suggère une interface déjà revendiquée via un autre handle/processus ou un état usbfs résiduel. Le propriétaire ne peut pas être identifié par ce rapport. Ne pas forcer un reset global du périphérique, car cela pourrait interrompre les interfaces audio ou un autre propriétaire.

### Test sur le Galaxy Z Flip 6

1. Installe l'APK debug de cette étape.
2. Place le NV-GS140 dans la personnalité USB que tu souhaites examiner.
3. Débranche/rebranche le câble USB après tout changement de mode sur le caméscope.
4. Dans Panasonic Monitor, choisis le moteur : Webcam ou Monitor DV.
5. Touche **Détecter et analyser le mode USB** et accepte les permissions Android.
6. Vérifie que le rapport annonce le format exigé : MJPEG pour Webcam ou DV pour Monitor DV. Le VID/PID reste informatif.
7. Touche **Tester le transport natif (5 secondes)**.
8. Pour Monitor DV, commence en mode VCR/lecture avec une cassette réellement en lecture. Un flux DV peut ne pas arriver si le transport de bande n'est pas lancé.
9. Partage le rapport complet. Répète le test avec l'autre moteur.
10. Après chaque test, vérifie que le rapport ne contient plus d'erreur `libusb_release_interface`, puis débranche/rebranche le caméscope et relance un test pour confirmer que l'interface est bien rendue et récupérable.

### Comment interpréter le test natif

- `UVC PROBE négocié` et `UVC COMMIT : accepté` : le périphérique a accepté les paramètres proposés.
- `Paquets avec données > 0` et `Payload UVC > 0` : Android a reçu des données dans les transferts isochrones.
- `FORMAT UVC REQUIS NON ANNONCÉ` : le périphérique est détecté, mais ne présente pas le format exigé par le moteur ; le test est bloqué pour éviter une négociation incohérente.
- `Flags d'erreur UVC` : paquets marqués en erreur par le périphérique ; ce compteur n'est pas à lui seul une mesure de corruption d'image.
- Aucun payload : vérifier la personnalité USB, le mode caméra/lecture, l'alimentation et le câble. Cela ne prouve pas à lui seul que le format est incompatible.

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
- libusb : contrôles UVC et transferts isochrones.
- Le code GPL de `uvcdv-capture` n'est pas copié dans cette application ; ce projet est uniquement une référence de protocole. Voir [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Feuille de route en 5 étapes

1. Diagnostic USB et identification des formats annoncés par le NV-GS140 — réalisé avec les rapports de test.
2. Transport natif UVC-DV / Webcam et échantillonnage isochrone — transport DV validé sur matériel ; correction du nettoyage libusb poussée ; validation Webcam MJPEG et nouveau test de libération/reconnexion encore requis.
3. Décodage, aperçu en direct et synchronisation audio/vidéo.
4. Enregistrement DV original, MP4 et bibliothèque de médias.
5. Stabilité, reconnexion, optimisation, tests finaux et APK final.
