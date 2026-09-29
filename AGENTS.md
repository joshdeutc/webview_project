# Directives et Règles du Projet (webview_project)

## Déploiement et Whitelist des Applications sur l'Appareil
- **Whitelist stricte active sur l'appareil (Custos Device Owner)** :
  Toute nouvelle application ou tout nouveau package créé/installé depuis ce projet (`webview_project - Dev`) ne fait **pas** partie de la whitelist autorisée de l'appareil par défaut.
- **Comportement attendu à l'installation** :
  Dès qu'une nouvelle application est installée, elle est automatiquement neutralisée par le système (mise en quarantaine, masquée et suspendue au niveau de l'OS). Elle n'est donc **pas utilisable ni désactivable immédiatement**.
- **Règle pour les futurs développements** :
  Ne jamais supposer qu'une application fraîchement installée est directement accessible. Ce n'est qu'ensuite, via l'autorisation explicite dans la whitelist (ou à l'expiration de son délai de quarantaine), que l'application pourra être libérée et lancée.
