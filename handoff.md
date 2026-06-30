# Handoff - VeryStupidSimplePodcast

## État actuel du projet (Juin 2026)
L'application est une application Android "podcast" robuste et hautement personnalisée, codée entièrement via Jetpack Compose.
- **Backend/Data** : La base de données Room et le `PodcastRepository` sont très stables. Les recherches se font sur l'API Apple et Xdio.
- **Xdio Integration** : L'API Xdio fonctionne. La découverte d'épisodes en arrière-plan (par le `FeedRefreshWorker`) télécharge l'épisode et émet désormais une notification Push native.
- **YouTube Integration** : NewPipeExtractor permet le téléchargement JIT des fichiers `.m4a` sans proxy. Le bypass "Anti-Bot" est en place.
- **UI** : 
  - La navigation s'effectue via un `HorizontalPager` par glissement (Abonnements / Flux / Recherche).
  - La palette de couleurs est "DeepCharcoal" avec un vert de type Spotify (`#1DB954`).
  - Le lecteur audio persistant (`MiniPlayer`) inclut une barre de progression fluide.
- **Audio** : Media3/ExoPlayer gère la lecture en arrière-plan avec un cache de 250MB et une mise à jour précise du "True Duration" lors du streaming.

## Ce qui a été accompli aujourd'hui
1. Migration de l'interface d'un "Menu Hamburger" vers un `HorizontalPager` à 3 pages (Swipe).
2. Colorisation dynamique de la durée restante dans les `EpisodeCard` (Vierge = original, Commencé = jaune doux, Complété = vert primaire).
3. Intégration de la bibliothèque `NewPipeExtractor` pour extraire l'audio des vidéos YouTube.
4. Ajout d'une fonctionnalité de "Push Notifications" dans `FeedRefreshWorker` (lors de la découverte de nouveaux épisodes).
5. Nettoyage et mise à jour de la documentation (`README.md`, `architecture.md`, `changelog.md`).
6. Création d'une proposition d'icône d'application.
7. Résolution du bogue de reprise de lecture (playback resume) via l'appel atomique `setMediaItem(mediaItem, startPositionMs)`.
8. Résolution du plantage `Service not registered` lors de la destruction du `MediaController` en utilisant le contexte d'application globale (`applicationContext`).
9. Correction du filtrage des YouTube Shorts qui contournaient les requêtes de détection en injectant les cookies de contournement de la barrière de consentement de Google.
10. Correction du bogue qui réinitialisait la progression de l'ancien épisode lors d'un changement de média (en interceptant `onPositionDiscontinuity` pour extraire `oldPosition.mediaItem?.mediaId` et sa position finale `oldPosition.positionMs`).
11. Optimisation du rafraîchissement des flux en arrière-plan via des coroutines parallèles (`async`/`awaitAll`).
12. Amélioration de la détection des épisodes pour scanner les 15 dernières publications (au lieu de seulement l'index 0) et ainsi éviter les épisodes manqués.
13. Ajout d'une pré-vérification Regex (`#Shorts`) pour réduire les requêtes réseau HEAD sur YouTube.
14. Liaison d'un `PendingIntent` à la notification pour ouvrir l'application sur clic.
15. Intégration d'un dialogue au démarrage pour désactiver l'optimisation de batterie agressive (Samsung S23 Ultra) afin d'éviter le blocage de WorkManager.
16. Mise à jour de la politique de planification en `ExistingPeriodicWorkPolicy.UPDATE` et suppression de la contrainte restrictive de batterie faible.
17. Ajout d'une détection et exclusion automatique des vidéos en direct (Live / Direct), à venir (Upcoming Events), ET des rediffusions (VOD Replays) sur YouTube pour éviter de les proposer dans le flux.

## Prochaines étapes suggérées
- Créer l'icône de lancement de l'application (Launcher Icon) dans Android Studio en important l'image générée.
- Valider le comportement exact des `HorizontalPager` lors de l'ouverture (peut-être forcer l'ouverture sur la page centrale "Flux").
- Implémenter l'écoute hors-ligne (téléchargement explicite) ou améliorer la gestion de cache hors ligne.

## Bogues Connus
- **Optimisation de `isYouTubeLive`** : Déplacement de la vérification en base de données **avant** les appels réseau coûteux (HEAD pour Shorts, GET ~800KB pour Live). Les vidéos déjà importées ne déclenchent plus de requêtes inutiles, réduisant la consommation de données par refresh de ~60 MB à quasi-zéro.
- **Tolérance de trous dans le scan RSS** : Remplacement du `break` brutal par un compteur de 3 épisodes consécutifs connus avant d'arrêter. Les vidéos nouvelles intercalées entre des Shorts/Lives filtrés et des épisodes existants ne seront plus ignorées silencieusement.
- **Parsing ISO 8601 pour YouTube** : Ajout du format `yyyy-MM-dd'T'HH:mm:ssXXX` en tête de la chaîne de parsing des dates. Les épisodes YouTube avaient tous `System.currentTimeMillis()` comme date, cassant le tri chronologique du flux.
- **Uniformisation skip +10s/-10s** : Le bouton avance rapide faisait 30 secondes malgré un label "10s". Uniformisé à 10s dans les deux directions.
