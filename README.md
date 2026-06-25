# VeryStupidSimplePodcast

Une application Android de baladodiffusion (podcast) conçue avec un objectif clair : être **stupidement simple**, élégante et incroyablement stable.

Construite de zéro avec les technologies modernes d'Android, elle offre une expérience d'écoute sans distraction, enveloppée dans un magnifique mode sombre (Dark Mode) aux accents verts subtils inspirés des meilleurs lecteurs multimédias.

---

## ✨ Fonctionnalités Principales

### 🎧 Expérience d'Écoute Premium (Media3 & ExoPlayer)
- **Lecture en Arrière-Plan :** Continuez d'écouter vos balados même avec l'écran éteint ou en utilisant d'autres applications.
- **Barre de Progression en Direct :** Le mini-lecteur omniprésent affiche une barre de progression fluide et un compte à rebours précis à la seconde près.
- **Durée Véritable (True Duration) :** Les flux RSS mentent souvent sur la durée réelle des épisodes. L'application contourne ce problème en lisant directement la durée du fichier audio téléchargé, garantissant qu'il n'y ait aucun bogue de "temps négatif" à la fin de la lecture.
- **Sauvegarde Intelligente de la Progression :** Vous avez dû quitter l'application ? La progression exacte est enregistrée en arrière-plan. Quand vous revenez, reprenez exactement là où vous vous étiez arrêté.

### 🔍 Recherche Intelligente et Multi-Sources
- Recherche instantanée interrogeant simultanément l'API publique d'**Apple Podcasts** et le réseau personnalisé **Xdio**.
- **Intégration YouTube Native :** Un module sur mesure propulsé par `NewPipeExtractor` permet d'ajouter n'importe quelle chaîne YouTube (via URL complète ou `@handle`) et de l'écouter comme un balado audio traditionnel, avec filtrage automatique des "Shorts" et contournement des protections anti-bots.
- **Identification Visuelle :** Un système d'étiquettes discrètes (Tags) affiche la source d'un coup d'œil directement dans vos listes (`YTB` pour YouTube, `OHD` pour Xdio/Ohdio, `POD` pour les flux RSS réguliers).
- **Algorithme de Pertinence :** Si vous cherchez un nom de balado exact (ex: "Tellement Hockey"), l'algorithme le place tout en haut de la liste, avant les mots-clés flous.

### 🗃️ Base de Données Native & Mode Hors-Ligne (Room)
- Toute votre librairie (abonnements, épisodes) est stockée localement dans une base de données SQLite robuste via Room. 
- **Auto-Nettoyage :** Lorsque vous vous désabonnez d'un balado, la base de données supprime instantanément tous les épisodes liés et purge les épisodes "orphelins" au démarrage.

### 🪄 Gestion Automatisée en Arrière-Plan (WorkManager)
- Plus besoin d'actualiser manuellement. Un gestionnaire de tâches invisible (Worker) s'exécute périodiquement pour récupérer les nouveaux épisodes de tous vos abonnements. 
- **Éco-Énergétique :** La tâche ne s'exécute que si vous êtes connecté à Internet et que votre batterie n'est pas faible.

### 👆 Interface Intuitive (Jetpack Compose)
- **Code de Couleur Intelligent :** Les épisodes que vous avez écoutés à plus de 95 % sont automatiquement assombris ("Completed") pour vous laisser vous concentrer sur vos nouveautés.
- **Menu Contextuel (Long Press) :** Restez appuyé sur n'importe quel épisode pour ouvrir un menu rapide permettant de :
  1. **Rétablir au Début** (remettre le compteur à zéro).
  2. **Marquer Comme Entendu** (classer instantanément l'épisode comme complété).

---

## 🛠️ Stack Technique

- **Langage :** Kotlin
- **Interface Utilisateur :** Jetpack Compose (Material Design 3)
- **Architecture :** MVVM (Model-View-ViewModel) + StateFlows
- **Moteur Audio :** AndroidX Media3 (MediaSessionService & ExoPlayer)
- **Extraction YouTube :** NewPipeExtractor (Extraction audio Just-In-Time)
- **Base de Données :** Room (SQLite)
- **Réseau :** Retrofit, GSON, Jsoup (pour l'analyse HTML et RSS XML)
- **Tâches en Arrière-plan :** WorkManager

---

## 🚀 Installation & Build

Cette application utilise des variables d'environnement locales pour protéger les clés d'API (comme l'API Xdio). 

1. Clonez ce dépôt.
2. À la racine du projet, créez (ou modifiez) un fichier nommé `local.properties`.
3. Ajoutez-y vos clés (ces variables seront lues par Gradle) :
   ```properties
   XDIO_API_URL=https://votre-url-api.com
   XDIO_API_TOKEN=votre-token-secret
   ```
4. Ouvrez le projet dans **Android Studio**.
5. Cliquez sur **Sync Project with Gradle Files**.
6. Compilez et lancez l'application sur un émulateur ou un appareil physique.

---

## 📜 Philosophie de Conception

La règle d'or de ce projet a été **"Le Backend avant le Frontend"**. L'intégration de Media3, la base de données Room et les requêtes réseaux complexes ont été solidifiées et stabilisées avant même de penser à animer un bouton. Cela a permis de créer une application Android où l'interface n'est pas qu'une coquille vide, mais plutôt le reflet direct d'un moteur performant.

---

## 🙏 Crédits et Remerciements

Un merci tout particulier et particulièrement chaleureux à **milhouse1337** pour la création de la plateforme **Xdio** et pour son intégration impeccable via API. Sans cette infrastructure robuste, la recherche et l'intégration des flux personnalisés n'auraient jamais été aussi fluides et efficaces. Ton travail est grandement apprécié ! 🙌

---

**Développé par Sébastien Bédard**
