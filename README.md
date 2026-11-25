# Panasonic Monitor

Application Android dédiée au Panasonic NV-GS140 et à son utilisation comme source vidéo USB sur un smartphone Android.

## Étape 1 — Diagnostic USB

Cette première version est un APK de diagnostic. Elle :
- détecte les périphériques USB visibles par Android ;
- demande les autorisations Android nécessaires ;
- décrit les interfaces, alt-settings et endpoints visibles ;
- lit les descripteurs USB bruts avec `UsbDeviceConnection.getRawDescriptors()` ;
- recherche les descripteurs UVC `VS_FORMAT_DV` (`0x0C`) et `VS_FORMAT_MJPEG` (`0x06`) ;
- signale les endpoints vidéo isochrones ;
- partage le rapport texte pour analyser les différents modes du NV-GS140.

**Cette version ne capture pas encore d'image et ne contrôle pas les commandes de transport MiniDV.** La présence de `VS_FORMAT_DV` dans un descripteur indique que le format est annoncé, mais ne prouve ni que le flux fonctionne, ni que le téléphone peut le recevoir.

### Test sur le Galaxy Z Flip 6

1. Installe l'APK `app-debug.apk` généré par le workflow GitHub Actions.
2. Allume le NV-GS140 et branche-le au téléphone avec un câble USB de données et un adaptateur USB-C OTG. Alimente le caméscope par son alimentation secteur si possible.
3. Lance Panasonic Monitor et touche **Analyser les appareils USB**.
4. Accepte la demande d'accès USB. Android peut également demander la permission **Caméra** pour les appareils USB de classe vidéo : elle est exigée par Android pour cette autorisation USB et l'application n'ouvre pas la caméra du téléphone.
5. Partage le rapport.
6. Répète le test dans chacun des modes USB disponibles sur le caméscope (notamment Webcam et Motion DV, lorsque le menu les propose). Débranche/rebranche après chaque changement de mode si le périphérique n'est pas ré-énuméré.

### Lecture du résultat

- `VS_FORMAT_DV : OUI` : un descripteur UVC de format DV a été trouvé dans la configuration USB lue. C'est un indice favorable, pas encore une preuve de capture exploitable.
- `VS_FORMAT_MJPEG : OUI` : un descripteur UVC MJPEG a été trouvé.
- `Endpoint vidéo isochrone : OUI` : le périphérique annonce un endpoint isochrone pour le flux vidéo. L'API USB Java standard d'Android ne sait pas effectuer ces transferts ; l'étape 2 devra donc étudier un transport natif.
- Aucun périphérique détecté : vérifier OTG, câble de données, alimentation et mode USB du caméscope.

## Architecture et sécurité

- Android natif Java, sans dépendance de capture webcam tierce dans l'étape 1.
- Aucune permission réseau.
- Aucun accès à la caméra du téléphone.
- Aucun flux vidéo ou audio n'est enregistré.
- Les descripteurs sont analysés de manière défensive et un rapport peut être partagé.

## Compilation

Le workflow `.github/workflows/android.yml` compile l'APK et publie `app-debug.apk` comme artefact GitHub Actions. Le projet utilise Android Gradle Plugin 8.13.2, Gradle 8.13, JDK 17 et compileSdk 35.

Compilation locale avec Gradle 8.13 et Android SDK 35 :

```bash
gradle :app:testDebugUnitTest :app:assembleDebug
```

APK : `app/build/outputs/apk/debug/app-debug.apk`

## Feuille de route

1. Diagnostic USB et identification des modes annoncés par le NV-GS140.
2. Étude et prototype du transport vidéo, notamment UVC-DV et transferts isochrones natifs.
3. Décodage, affichage en direct et synchronisation audio/vidéo.
4. Enregistrement des flux originaux et des copies MP4, puis gestion des fichiers.
5. Tests sur le matériel réel, stabilité et optimisation.

## Matériel cible

- Téléphone de test : Samsung Galaxy Z Flip 6.
- Caméscope : Panasonic NV-GS140 (MiniDV).
- Liaison : USB-C OTG vers USB mini-B.
- La prise en charge réelle de `VS_FORMAT_DV` par le NV-GS140 reste à confirmer par un rapport obtenu sur le matériel.
