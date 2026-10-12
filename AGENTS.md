# Directives et Règles du Projet (webview_project)

## Déploiement et Whitelist des Applications sur l'Appareil
- **Whitelist stricte active sur l'appareil (Custos Device Owner)** :
  Toute nouvelle application ou tout nouveau package créé/installé depuis ce projet (`webview_project - Dev`) ne fait **pas** partie de la whitelist autorisée de l'appareil par défaut.
- **Comportement attendu à l'installation** :
  Dès qu'une nouvelle application est installée, elle est automatiquement neutralisée par le système (mise en quarantaine, masquée et suspendue au niveau de l'OS). Elle n'est donc **pas utilisable ni désactivable immédiatement**.
- **Règle pour les futurs développements** :
  Ne jamais supposer qu'une application fraîchement installée est directement accessible. Ce n'est qu'ensuite, via l'autorisation explicite dans la whitelist (ou à l'expiration de son délai de quarantaine), que l'application pourra être libérée et lancée.

## Verrouillage Temporel des Mises à Jour (Time-Lock Release)
- **Principe de friction psychologique** :
  Toute mise à jour d'application doit être signée via la clé de release protégée par le **Wait Timer** GitHub Actions (voir détails complets dans [docs/TIME_LOCK_RELEASE.md](docs/TIME_LOCK_RELEASE.md)).
- **Interdiction de contournement local** :
  L'agent IA (Antigravity sur tour ou laptop) ne doit pas chercher à contourner ce délai en créant des APKs de release locaux non temporisés lorsqu'une modification sensible est demandée. Le cycle doit passer par Git et respecter le timer d'attente.
