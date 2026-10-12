# Architecture de Verrouillage Temporel (Time-Lock Release) & Sécurité

> **Ce document est une directive opérationnelle pour Antigravity (Tour, Portable ou toute autre instance IA).**

---

## 1. Contexte & Objectif

Les applications de ce projet (`webview_project`) sont installées sur des appareils personnels verrouillés (Pixel 8a, Samsung, etc.) gérés par un système de contrôle de restrictions (Custos / Device Owner / Whitelist).

Pour éviter les modifications impulsives (réactiver des réseaux sociaux, des sites de streaming ou des domaines bloqués sur un coup de tête), le projet utilise une stratégie de **friction psychologique par verrouillage temporel (Time-Lock)** :
1. **Verrouillage cryptographique Android** : Toutes les applications doivent être signées avec une clé privée de Release (`.jks`). Une fois installée sur l'appareil, Android rejette mathématiquement toute mise à jour ne possédant pas rigoureusement cette signature (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`).
2. **Verrouillage temporel GitHub Actions** : La clé de signature n'est **PAS** utilisable instantanément sur le PC. Elle est hébergée dans un environnement protégé GitHub Actions muni d'un **Wait Timer obligatoire** (ex: 24h ou 48h).

---

## 2. Fonctionnement du Système

### A. L'Environnement GitHub Protégé (`production`)
- L'environnement `production` sur GitHub est configuré avec un **Wait Timer** (délai d'attente imposé par l'infrastructure GitHub, par exemple 1 440 minutes = 24 heures).
- Les secrets de signature suivants sont confinés **exclusivement** dans cet environnement :
  - `KEYSTORE_BASE64` : Le fichier Keystore Release encodé en Base64.
  - `KEYSTORE_PASSWORD` : Le mot de passe du Keystore.
  - `KEY_ALIAS` : L'alias de la clé de signature.
  - `KEY_PASSWORD` : Le mot de passe de la clé.

### B. Le Workflow GitHub Actions
- Le fichier `.github/workflows/release.yml` est déclenché lors d'un push de tag ou manuellement.
- Le job de signature déclare : `environment: production`.
- **Comportement garanti** :
  1. Dès le déclenchement, GitHub Actions met le job en pause (`Waiting for environment timer`).
  2. Aucun accès aux secrets n'est accordé tant que le délai (24h/48h) n'est pas écoulé.
  3. Une fois le timer terminé, le robot injecte la clé, compile les APKs Release signés, et publie les artefacts.
  4. L'utilisateur a ainsi 24h à 48h de délai de réflexion pour annuler le job s'il s'agissait d'une décision impulsive.

---

## 3. Directives Strictes pour Antigravity (Tour & PC)

Toute instance d'Antigravity intervenant sur ce dépôt **DOIT** respecter les règles suivantes :

1. **Ne JAMAIS contourner le Time-Lock en local** :
   - Ne pas recréer de clé de signature locale sans délai pour forcer une installation immédiate d'une mise à jour contournant les restrictions.
   - Si une clé `.jks` locale existe encore temporairement, elle ne doit pas servir à créer des APKs de contournement immédiat.
2. **Cycle normal de mise à jour** :
   - Lorsque l'utilisateur demande une modification du code d'une application :
     1. Appliquer les modifications dans le code source.
     2. Tester la compilation en local si nécessaire.
     3. Commiter et pousser sur `origin/main` (`git push`).
     4. Laisser le workflow GitHub Actions exécuter la release et respecter le délai du **Wait Timer**.
3. **Respect du Whitelisting Custos** :
   - Rappel de `AGENTS.md` : Les applications installées sur l'appareil sont soumises à la whitelist de l'appareil. Ne jamais essayer de bypasser ces protections.

---

## 4. Procédure de Déploiement Initial (Rappel de mise en place)

1. **Générer le Keystore Release** :
   ```bash
   keytool -genkeypair -v -keystore release.jks -alias webview-key -keyalg RSA -keysize 4096 -validity 10000
   ```
2. **Encoder en Base64** :
   ```powershell
   [Convert]::ToBase64String([IO.File]::ReadAllBytes("release.jks")) | Set-Content "keystore_base64.txt"
   ```
3. **Configurer GitHub** :
   - Dépôt ➔ `Settings` ➔ `Environments` ➔ `New environment` : `production`.
   - Cocher `Wait timer` et définir la durée (ex: `1440` minutes pour 24h).
   - Dans `Environment secrets`, ajouter `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
4. **Supprimer la clé locale** :
   - Supprimer `release.jks` et `keystore_base64.txt` du PC de travail pour rendre le timer incontournable.
