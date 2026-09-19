# Carnet pour Android

Un bloc-notes simple, en français, pour retrouver ses idées et leurs anciennes versions.

## Installer

**[Télécharger Carnet.apk](https://github.com/nicolasght/carnet-android/releases/latest/download/Carnet.apk)** · [Toutes les versions](https://github.com/nicolasght/carnet-android/releases)

1. Ouvrez ce lien sur votre téléphone Android.
2. Téléchargez et ouvrez `Carnet.apk`.
3. Si Android le demande, autorisez l’installation depuis votre navigateur ou votre gestionnaire de fichiers, puis installez Carnet.

Android 8.0 ou plus récent. L’application fonctionne sans compte et sans connexion Internet. L’APK de la page Releases est signé avec la clé privée du projet, conservée hors du dépôt. Gardez cette version pour recevoir les prochaines mises à jour sans désinstaller.

<p>
  <img src="docs/notes.png" width="240" alt="Accueil de Carnet : recherche, favoris et notes" />
  <img src="docs/editor.png" width="240" alt="Édition d’une note avec liste et accès à l’historique" />
  <img src="docs/dark.png" width="240" alt="Carnet en mode sombre" />
</p>

Captures du véritable rendu Android produites par les tests. Les notes d’exemple ne sont pas ajoutées à l’application installée.

## Au quotidien

- **Nouvelle note** : écrivez un titre et votre texte. L’enregistrement se fait après 650 ms de pause et lorsque l’application passe en arrière-plan.
- **Recherche** : retrouve un mot dans les titres et le contenu. Les favoris apparaissent en premier.
- **Historique** : chaque enregistrement différent devient une version datée. Consultez une version, puis restaurez-la si besoin. La version remplacée reste disponible. Les versions ne sont pas supprimées automatiquement.
- **Liste** : placez le curseur sur une ligne et touchez « ☐ Liste » pour ajouter une case ou la cocher/décocher.
- **Menu d’une note** : favoris, partage de texte, duplication et mise à la corbeille.
- **Corbeille** : restaurez une note, ou supprimez-la définitivement avec confirmation. Cette suppression efface aussi son historique.
- **Apparence** : thème du téléphone, clair ou sombre.

## Sauvegarder et changer de téléphone

Dans le menu `⋯` de l’accueil, choisissez **Exporter une sauvegarde**. Le fichier JSON inclut toutes les notes, les favoris, la corbeille et les historiques. Conservez-le sur un support sûr. Sur l’autre téléphone, utilisez **Importer une sauvegarde**. Les notes sont ajoutées comme nouvelles copies, sans remplacer les notes existantes ; importer deux fois crée donc des doublons.

Les données restent dans le stockage privé de l’application. Aucun accès réseau, aucune publicité et aucune collecte de données. Il n’y a pas de synchronisation automatique ni de verrouillage par mot de passe intégré. Une désinstallation ou un effacement des données Android supprime les notes : exportez auparavant. Le fichier exporté n’est pas chiffré. L’import accepte les sauvegardes Carnet jusqu’à 16 Mo, 10 000 notes et 100 000 versions.

## Développement

Application Android native en Java, interface et base SQLite fournies par Android ; aucune bibliothèque d’exécution supplémentaire. Les bibliothèques JUnit et Robolectric servent uniquement aux tests.

Prérequis : JDK 17, Android SDK 35, Build Tools 35.0.0. Définissez `ANDROID_HOME` ou le chemin `sdk.dir` dans un fichier local `local.properties`.

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Sous Windows : `gradlew.bat`. Les tests couvrent les versions, la restauration, la corbeille, la recherche littérale, les sauvegardes, l’annulation transactionnelle d’un import invalide, la persistance, la fermeture pendant la frappe et Android 8.

Pour produire un APK de distribution signé, définissez `CARNET_KEYSTORE` et `CARNET_STORE_PASSWORD` dans votre environnement, avec l’alias de clé `carnet`, puis lancez `./gradlew assembleRelease`. Ne publiez jamais la clé ni son mot de passe. Les APK debug fournis par l’intégration continue servent aux essais ; ils ne remplacent pas une version de distribution signée.

Le dépôt contient l’intégralité des sources et les vérifications GitHub Actions.
