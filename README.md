# Pocket Scanner — Android

Application Android de scan de documents créée dans ce dépôt.

## Fonctions

- prise de photo avec l'appareil photo du téléphone ;
- import d'une image depuis la galerie / les fichiers ;
- rognage manuel directement dans l'application ;
- ajout de plusieurs pages dans une même session ;
- génération d'un PDF multipage ;
- partage du PDF avec les applications Android compatibles (notes, Drive, messagerie, e-mail, etc.) ;
- fonctionnement hors ligne pour tout le flux de scan ;
- détection de l'état de la connexion Internet, sans rendre Internet obligatoire.

## Vie privée et mode hors ligne

Les images scannées et les PDF sont traités localement sur le téléphone. La permission INTERNET est présente et l'application affiche si Android considère la connexion comme réellement validée, mais aucune connexion réseau n'est nécessaire pour prendre une photo, importer, rogner, créer ou partager un PDF localement.

## Stack

Android natif / Java 17, AndroidX, uCrop 2.2.11 pour le rognage, PdfDocument Android pour la création des PDF, et FileProvider + feuille de partage Android pour envoyer les PDF vers d'autres applications.

## Ouvrir le projet

1. Cloner ce dépôt.
2. Ouvrir le dossier racine dans Android Studio.
3. Utiliser JDK 17.
4. Laisser Gradle télécharger les dépendances lors de la première synchronisation.
5. Lancer l'application sur un téléphone Android ou un émulateur.

Le projet cible Android API 36 et utilise Android Gradle Plugin 8.10.1 avec Gradle 8.11.1.

## Générer un APK

Avec Gradle 8.11.1 installé, lancer : gradle :app:assembleDebug

L'APK est créé dans : app/build/outputs/apk/debug/app-debug.apk

Une GitHub Action dans .github/workflows/android.yml compile également l'APK debug à chaque push / pull request et le publie comme artefact du workflow.

## Utilisation

1. Appuyer sur Prendre une photo ou Importer une photo.
2. Appuyer sur Rogner l'image pour ajuster les bords.
3. Appuyer sur Ajouter comme page si le document comporte plusieurs pages.
4. Répéter pour les autres pages.
5. Appuyer sur Créer le PDF.
6. Appuyer sur Partager le PDF et choisir l'application cible.

## Publication Play Store

Le dépôt contient une base fonctionnelle de l'application, mais une publication Play Store nécessite encore au minimum : icône définitive, captures d'écran, fiche Store, politique de confidentialité, signature de release / keystore et génération d'un Android App Bundle (.aab).
