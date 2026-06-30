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
### Optimized & Improved
- **Gestion de l'Optimisation de Batterie Android** :
  - Ajout de la permission `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` et affichage d'une boîte de dialogue au lancement si l'optimisation est active, permettant de whitelister facilement l'application pour empêcher One UI (Samsung S23 Ultra) d'interrompre les tâches de fond.
  - Allègement des contraintes de `WorkManager` : suppression de la restriction `.setRequiresBatteryNotLow(true)` qui bloquait les tâches de fond même avec une batterie moyenne en mode économie.
  - Mise à jour de la politique de planification en `ExistingPeriodicWorkPolicy.UPDATE` (au lieu de `KEEP`) pour forcer Android à appliquer les nouvelles contraintes et configurations du worker dès la mise à jour.
- **Rafraîchissement en Parallèle des Flux** : Remplacement de la boucle séquentielle de rafraîchissement par des coroutines parallèles (`async`/`awaitAll`), accélérant drastiquement le rafraîchissement de tous les flux en tâche de fond.
- **Récupération Exhaustive des Nouveautés** : Modification du traitement RSS/API pour ne plus s'arrêter au tout premier élément (index 0). L'application parcourt désormais les 15 derniers épisodes et importe *tous* ceux absents de la base de données jusqu'à rencontrer un doublon.
- **Filtrage des YouTube Shorts Allégé** : Ajout d'une pré-vérification par expression régulière recherchant `#Shorts` ou `#short` dans les titres pour éviter de lancer inutilement des requêtes réseau HEAD.
- **Filtrage des Directs YouTube (Live Streams & VODs)** :
  - Ajout d'un système de détection et d'exclusion des vidéos en direct (Live en cours ou événements programmés à venir) lors de la synchronisation. L'application télécharge de manière ciblée la page de lecture et recherche les signatures spécifiques `"isLive":true` ou `"isUpcoming":true` pour ignorer ces flux incompatibles avec un format balado.
  - **Mise à jour VODs** : Augmentation de la limite de lecture HTML à 2 millions de caractères et ajout du tag `isLiveBroadcast` pour exclure également les rediffusions (replays/VODs) de diffusions en direct terminées.
- **Ergonomie des Notifications** : Liaison d'un `PendingIntent` (FLAG_IMMUTABLE) à la notification système pour ouvrir automatiquement la page d'accueil de l'application lors d'un clic.

### Fixed
- **Correction du Suivi de Progression sur Transition (Reset Progress Bug)** :
  - Résolution du problème où le démarrage d'un nouvel épisode réinitialisait la progression de l'épisode précédent à 0 ("non lu"). La sauvegarde effectuée sur la transition lisait l'état du lecteur *après* le chargement de la nouvelle piste (qui était à 0), mais l'appliquait à l'ID de l'ancienne. Corrigé en écoutant `onPositionDiscontinuity` et en récupérant l'historique exact de position de l'ancienne piste via `oldPosition.mediaItem?.mediaId` et `oldPosition.positionMs` avant sa destruction (correction syntaxique d'accès au `mediaId` via `mediaItem`).
- **Bypass de Filtrage des Shorts YouTube** :
  - Résolution d'un bogue où certains "Shorts" YouTube contournaient le filtre et étaient proposés comme épisodes. La requête HEAD permettant de détecter les Shorts était bloquée par la barrière de consentement de Google/YouTube sur les appareils mobiles, retournant un code d'erreur `302` ou `403` au lieu de `200`. Corrigé en injectant les en-têtes de contournement anti-bot (`User-Agent` complet, `Accept-Language` et les cookies `SOCS`/`CONSENT`) identiques à notre téléchargeur principal.
- **Lecture et Reprise (Playback Resume)** :
  - Résolution d'un problème où la reprise d'un épisode en pause recommençait du début (0s) au lieu d'utiliser le temps restant sauvegardé. Correction faite en remplaçant la séquence `setMediaItem` + `seekTo` (sujet à des conditions de concurrence asynchrones dans Media3) par l'appel atomique `setMediaItem(mediaItem, startPositionMs)`.
- **Crash au Fermeture (Service Not Registered)** :
  - Résolution du plantage `IllegalArgumentException: Service not registered` provoqué par la libération du `MediaController` lors de la fermeture de l'application ou d'une rotation d'écran. Corrigé en initialisant le `MediaController` avec le `applicationContext` au lieu du contexte d'Activity (`MainActivity`), garantissant que la liaison survit aux cycles de vie de l'Activity et se détruit proprement.
- **API Xdio (Épisodes récents)**: 
  - Résolution d'un crash de parsing silencieux (`v2/rss/show/{id}` retourne un objet enveloppant les épisodes sous la clé `items`, classés du plus récent au plus ancien, au lieu d'un tableau direct). Modélisation corrigée (`XdioShowFeedResponse`) et index 0 lu correctement.
- **Background Jobs (WorkManager)**: 
  - `FeedRefreshWorker` n'était jamais programmé. Ajout de `PeriodicWorkRequestBuilder` (1 heure) avec contraintes (`NetworkType.CONNECTED`, `RequiresBatteryNotLow`) dans `MainActivity`.
- **Database (Room)**:
  - Ajout de `deleteEpisodesBySubscriptionId` dans `Daos.kt` et modification de `PodcastViewModel.unsubscribePodcast` pour supprimer immédiatement et manuellement les épisodes du fil de l'utilisateur lorsqu'il se désabonne.
- **Optimisation du Scan YouTube (Audit Point #4)** :
  - Déplacement de la vérification en base de données AVANT les appels réseau (`isYouTubeShort`, `isYouTubeLive`). Réduit la consommation de données par refresh de ~60 MB à quasi-zéro pour les flux déjà synchronisés.
- **Tolérance de Trous dans le Scan RSS (Audit Point #5)** :
  - Le `break` immédiat au premier épisode connu est remplacé par un compteur de 3 épisodes consécutifs connus, tolérant les trous créés par les Shorts et Lives filtrés.
- **Parsing des Dates YouTube (Audit Point #8)** :
  - Les dates ISO 8601 (`2026-06-30T15:00:00+00:00`) n'étaient pas parsées, forçant tous les épisodes YouTube à avoir comme date le moment du refresh. Ajout du format ISO 8601 en tête de la chaîne de parsing.
- **Uniformisation Skip ±10s (Audit Point #13)** :
  - Le bouton avance rapide faisait 30 000 ms malgré un `contentDescription` de "+10s". Uniformisé à 10 000 ms dans les deux directions.
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
