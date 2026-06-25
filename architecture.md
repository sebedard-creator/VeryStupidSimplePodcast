# Architecture du Projet: VeryStupidSimplePodcast

## Philosophie Globale
- **Backend en Premier**: La stabilisation totale de la logique des données et du réseau précède toujours les modifications de l'interface utilisateur.
- **Aucune dépendance externe complexe (Modules)**: L'architecture est monolithique (un seul module `app`) pour conserver la simplicité maximale et faciliter la contribution.

## Couches de l'application
### Data Layer (Réseau et BDD)
- **Room Database**: Gère les abonnements (`Subscription`) et les épisodes (`Episode`). La suppression d'un abonnement supprime **immédiatement** les épisodes associés de la base de données (nettoyage manuel de la table `episodes`).
- **Apple Podcast API**: Appels REST officiels via Retrofit.
- **Xdio API**: Appels REST sécurisés via Retrofit (`v2/search/multi-search` et `v2/rss/show/{id}`) à `api.xdio.ca`. Les identifiants secrets sont stockés dans `local.properties` et injectés via `BuildConfig`. Le flux RSS retourne un objet JSON (encapsulant la liste dans `items`) classé chronologiquement du plus récent au plus ancien.
- **Parsing RSS (Jsoup)**: Extraction exclusive de l'`index 0` (l'épisode le plus récent) du flux RSS XML d'Apple et de YouTube. Le flux YouTube est parsé en mode "XML strict" pour gérer correctement les espaces de noms (`yt:videoId`).
- **Extraction YouTube (NewPipeExtractor v0.26.3)**: Intégration de la librairie NewPipe pour la résolution de chaînes (`@handle` vers `UC...` via `ChannelExtractor`) et l'extraction Just-in-Time (JIT) de l'audio haute qualité (`.m4a` via `StreamExtractor`). Le téléchargement réseau de NewPipe est sécurisé par un `User-Agent` Chrome et des cookies spécifiques (`SOCS`/`CONSENT`) pour contourner les mesures anti-bots.
- **WorkManager**: `FeedRefreshWorker` s'occupe des synchronisations en arrière-plan. Il est formellement programmé dans `MainActivity` pour tourner chaque heure, avec des contraintes strictes de réseau (connecté) et de batterie (non faible).

### Audio Layer (ExoPlayer)
- **PodcastMediaSessionService**: Service au premier plan (Foreground Service) lié à Media3.
- **PlayerCacheManager**: Cache strict de 250MB. Pas de téléchargement manuel; l'écoute se fait via streaming avec cache géré par éviction (LRU).
- **Comportement Audio**: L'application ignore la déconnexion des écouteurs (`setHandleAudioBecomingNoisy(false)`) et la vitesse de lecture est fixée à 1.0x sans option de modification.
- **Synchronisation d'État & Stabilité** :
  - **Heartbeat & True Duration** : Un *heartbeat* (lancé sur le `Dispatchers.Main` pour éviter les `IllegalStateException` d'ExoPlayer) enregistre la progression et la "durée réelle" de l'audio (`updateProgressAndDuration` via `Dispatchers.IO`) chaque 15 secondes. Cela corrige les erreurs de durée approximative des flux RSS.
  - **Fin de Lecture** : Lorsqu'un épisode se termine, la progression finale (100%) est sauvegardée *avant* que l'identifiant du média actif ne soit réinitialisé, garantissant l'intégrité des données.
  - **Extraction JIT**: Pour les sources YouTube, l'URL de base est interceptée. Le `PodcastViewModel` extrait de manière asynchrone l'URL audio directe (`content`) depuis la page vidéo avant d'ordonner la lecture à ExoPlayer. Un indicateur visuel (Spinner) fait patienter l'interface pendant l'opération.

### UI Layer (Jetpack Compose)
- **Modèle MVVM**: `PodcastViewModel` gère les états (`StateFlow`), incluant le `MediaController` de Media3 pour piloter le lecteur audio depuis l'interface et une coroutine de mise à jour fluide de la progression en direct.
- **Diagnostics Intégrés** : Un `UncaughtExceptionHandler` localisé dans `MainActivity` capture les plantages, les stocke dans les `SharedPreferences` et affiche la trace de l'erreur (stacktrace) au prochain démarrage pour un débogage sans accès aux outils développeurs.
- **Permissions**: L'accès à `MainFeedScreen` ou `SearchScreen` est bloqué tant que la permission `POST_NOTIFICATIONS` n'est pas accordée.
- **Affichage & Design**: Palette `DeepCharcoal` avec accents verts (`#1DB954`). Les épisodes écoutés à plus de 95% sont visuellement assombris (Completed).
- **Interactions**: Les `EpisodeCard` supportent le "Long Press" (via `Modifier.combinedClickable`) pour faire apparaître un menu contextuel de gestion ("Rétablir au Début", "Marquer Comme Entendu"). Un `MiniPlayer` dynamique (avec barre de progression `LinearProgressIndicator` sécurisée contre les `NaN`) persiste à l'écran.
