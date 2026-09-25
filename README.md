# Pocket Scanner — Android

Pocket Scanner est une application Android de scan de documents pensée pour fonctionner localement et hors ligne.

## Version 2

Cette version ajoute :

- détection automatique approximative des bords d'une feuille après photo ou import ;
- proposition de rognage avec trois choix : accepter, rogner manuellement ou garder l'original ;
- indication du ratio détecté et reconnaissance approximative A4 / Lettre ;
- import de plusieurs images en une seule fois puis création d'un PDF multipage ;
- application en lot des rognages automatiques détectés ;
- bibliothèque PDF intégrée directement dans l'application ;
- historique trié des PDF créés, avec date et taille ;
- ouverture, partage et suppression des PDF depuis l'historique ;
- sélection et partage de plusieurs PDF en une seule action ;
- interface entièrement retravaillée avec cartes, hiérarchie visuelle, états et aperçu plus lisible ;
- traitement local des images, du rognage et des PDF ;
- connexion Internet détectée et disponible, mais jamais obligatoire pour scanner.

## Détection automatique

Pocket Scanner utilise un détecteur local léger fondé sur les gradients visuels de l'image. Il recherche quatre bords dominants et construit un cadrage rectangulaire approximatif.

Ce système est volontairement hors ligne et sans serveur. Sur une photo très inclinée, un fond complexe ou une feuille peu contrastée, l'application peut ne pas proposer de cadrage ; le rognage manuel uCrop reste alors disponible.

La détection actuelle propose un rectangle de rognage et non une correction complète de perspective à quatre coins.

## Stockage

Les PDF sont enregistrés dans le dossier de documents privé de l'application, sous Documents/Scans. Ils sont listés dans la section Bibliothèque PDF de l'application et peuvent être partagés vers les applications Android compatibles.

Les fichiers restent dans la bibliothèque lorsqu'une nouvelle session de scan est démarrée.

## Utilisation

1. Prenez une photo ou importez une image.
2. Pocket Scanner analyse les bords localement.
3. Acceptez le rognage proposé, rognez vous-même ou gardez l'original.
4. Ajoutez d'autres pages si nécessaire.
5. Appuyez sur Créer et enregistrer le PDF.
6. Retrouvez le document dans la bibliothèque.
7. Sélectionnez plusieurs PDF pour les partager ensemble si besoin.

Pour un lot d'images, utilisez Importer plusieurs images d'un coup, sélectionnez les photos puis choisissez d'appliquer les cadrages automatiques ou de conserver les originaux.

## Build

- Android natif / Java 17
- minSdk 23
- targetSdk / compileSdk 36
- Android Gradle Plugin 8.10.1
- Gradle 8.11.1
- uCrop 2.2.11

GitHub Actions compile automatiquement un APK debug avec la commande gradle :app:assembleDebug --stacktrace.

L'APK est publié comme artefact du workflow sous le nom pocket-scanner-debug.

## Publication Play Store

Avant une publication Play Store, prévoir au minimum : icône définitive, screenshots, fiche Store, politique de confidentialité, signature de release / keystore, tests sur plusieurs appareils et génération d'un Android App Bundle signé.
