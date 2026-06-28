# Changelog

## [1.0.0] - 2026-06-23

### Added
- **Project Structure**: Initialized `VeryStupidSimplePodcast` with Android CLI (API 26 to 36).
- **Gradle**: Configured `libs.versions.toml` and `build.gradle.kts` for Room, Retrofit, Jsoup, Media3, and WorkManager.
- **Database (Room)**: 
  - `PodcastDatabase` avec les tables `subscriptions` et `episodes`.
  - Maintien des épisodes lors du désabonnement (pas de CASCADE).
- **Network & Parsing**: 
  - `ApplePodcastApi` (Retrofit) pour les requêtes Apple Podcasts.
  - Parsing Jsoup pour `yodio.ca/search` et récupération des flux XML RSS (index 0 uniquement).
- **Background Jobs**:
  - `FeedRefreshWorker` s'exécutant pour rafraîchir les flux RSS.
- **Audio Service**:
  - `PodcastMediaSessionService` utilisant ExoPlayer et MediaSession.
  - Limite de cache fixée strictement à 250MB.
  - Logique `AUDIO_BECOMING_NOISY` ajustée (ignore la déconnexion).
  - Coroutine Heartbeat sauvegardant la progression toutes les 15 secondes.
- **UI (Jetpack Compose)**:
  - `MainActivity` avec blocage de permission `POST_NOTIFICATIONS`.
  - `MainFeedScreen` : Liste chronologique stricte.
  - `SearchScreen` : Recherches parallèles avec indicateur de chargement.
  - `EpisodeCard` : Texte uniquement, 4 lignes, couleur grisée si non abonné.
  - `MiniPlayer` : Contrôles persistants (-10s, Play/Pause, +10s).
- **Migration API Xdio**:
  - Remplacement du scraping Jsoup de `yodio.ca` par des appels REST à l'API `api.xdio.ca/v2/search/multi-search`.
  - Sécurisation du token de l'API via `local.properties` et `BuildConfig`.

### Fixed
- **Lecture et Reprise (Playback Resume)** :
  - Résolution d'un problème où la reprise d'un épisode en pause recommençait du début (0s) au lieu d'utiliser le temps restant sauvegardé. Correction faite en remplaçant la séquence `setMediaItem` + `seekTo` (sujet à des conditions de concurrence asynchrones dans Media3) par l'appel atomique `setMediaItem(mediaItem, startPositionMs)`.
- **API Xdio (Épisodes récents)**: 
  - Résolution d'un crash de parsing silencieux (`v2/rss/show/{id}` retourne un objet enveloppant les épisodes sous la clé `items`, classés du plus récent au plus ancien, au lieu d'un tableau direct). Modélisation corrigée (`XdioShowFeedResponse`) et index 0 lu correctement.
- **Background Jobs (WorkManager)**: 
  - `FeedRefreshWorker` n'était jamais programmé. Ajout de `PeriodicWorkRequestBuilder` (1 heure) avec contraintes (`NetworkType.CONNECTED`, `RequiresBatteryNotLow`) dans `MainActivity`.
- **Database (Room)**:
  - Ajout de `deleteEpisodesBySubscriptionId` dans `Daos.kt` et modification de `PodcastViewModel.unsubscribePodcast` pour supprimer immédiatement et manuellement les épisodes du fil de l'utilisateur lorsqu'il se désabonne.
- **UI & Playback (Media3)**: 
  - Liaison manquante corrigée. Instanciation du `MediaController` dans le `PodcastViewModel` pour lier l'interface (`EpisodeCard`, `MiniPlayer`) au service `PodcastMediaSessionService` existant.
  - Ajout de `getEpisodeById` dans la base de données (Room) pour restaurer l'état local via l'identifiant lu depuis `Media3`.

### Changed & Improved (UI/UX)
- **Recherche** : Refonte de `searchPodcasts` avec tri intelligent priorisant les correspondances exactes du titre (exact match), puis `startsWith`, puis `contains`, avec bris d'égalité par longueur du titre.
- **Design** : Palette `DeepCharcoal` assouplie (vert Spotify `#1DB954`). Ajout de la mention "Restant" pour la clarté. Les cartes d'épisodes écoutés à >95% (Completed) utilisent une teinte de fond plus sombre (`surface` vs `surfaceVariant`).
- **MiniPlayer** : Remplacement des boutons textes par des icônes de la librairie `material-icons-extended`. Ajout d'une barre de progression (`LinearProgressIndicator`) fluide mise à jour en temps réel (boucle de 1 seconde).
- **Menu Contextuel** : Ajout d'un système de Long Press (`Modifier.combinedClickable`) sur les `EpisodeCard` pour offrir un menu `DropdownMenu` ("Rétablir au Début", "Marquer Comme Entendu").

### Fixed (Stability)
- **True Duration (Media3)** : Résolution du "syndrome de durée estimée". Les flux RSS fournissant des durées approximatives, l'application mettait le temps restant dans le négatif. Désormais, l'application lit la vraie durée via `ExoPlayer` dès le premier tamponnage et la sauvegarde dans la DB `updateProgressAndDuration`.
- **Plantages de Fin de Lecture (Crashes)** : 
  - Bug Compose `LinearProgressIndicator` : Plantait si `progressRatio` recevait `NaN` ou des valeurs infinies. Corrigé avec `coerceIn(0f,1f)` et `.isNaN()`.
  - Bug Threading `ExoPlayer` : Crash `IllegalStateException` car le heartbeat lisait `currentPosition` depuis un fil de travail `Dispatchers.IO`. Minuteur rapatrié sur `Dispatchers.Main`.
  - Bug Sauvegarde de Fin : `PodcastMediaSessionService` tentait de sauvegarder la progression finale *après* avoir changé l'identifiant pour `null` lors de la fin de la piste. Corrigé en sauvegardant l'état précédent en premier.
- **Diagnostics** : Implémentation d'un `UncaughtExceptionHandler` local à l'application stockant les stacktraces de crashs dans les `SharedPreferences` pour un affichage immédiat au prochain lancement.

### Added (YouTube Integration)
- **NewPipeExtractor (v0.26.3)**: Intégration pour la résolution des chaînes YouTube (URLs ou `@handles`) et l'extraction JIT (Just-in-Time) des flux audio.
- **Extraction JIT (Just-in-Time)**: Récupération de l'URL audio `.m4a` directe au moment de la lecture, avec Spinner d'attente sur le `MiniPlayer`.
- **Anti-Bot Bypass**: Ajout d'un `User-Agent` Chrome 120, de cookies `SOCS`/`CONSENT` et de localisation `en-US` dans le `SimpleDownloader` pour contourner les vérifications YouTube.
- **Parsing RSS YouTube** : Configuration de Jsoup en mode XML pour gérer les namespaces (`yt:videoId`) et filtrage automatique des "Shorts" via requêtes HEAD (vérification de la redirection 303).

### Changed (UI Updates)
- **SearchScreen** : Ajout d'un champ dédié à la résolution d'URL et `@handle` YouTube.
- **MainFeedScreen & EpisodeCard** : Ajout d'une étiquette visuelle discrète (Tag Pill rectangulaire et transparent) pour identifier la source d'un coup d'œil (`YTB`, `OHD`, `POD`).
- **Drawer / Subscriptions** : Tri alphabétique des abonnements de A à Z et intégration des étiquettes de source adaptatives.
