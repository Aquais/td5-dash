# TD5 Dash

Tableau de bord et outil de diagnostic pour moteur Land Rover TD5, pensé pour
un autoradio Android en 1024×600.

## Installation

Télécharge l'APK depuis la page **Releases**, puis ouvre-le sur l'autoradio.
Il faut autoriser l'installation depuis des sources inconnues.

L'application s'installe **à côté** de TD5 InfoDash : elle a son propre
identifiant, les deux peuvent cohabiter.

## Matériel

Un câble KKL VAG-COM à puce FTDI FT232R, branché sur le port USB de
l'autoradio et sur la prise diagnostic du véhicule.

## Origine et licence

Ce projet dérive de [hairyone/TD5Tester](https://github.com/hairyone/TD5Tester),
sous licence Apache 2.0, et du code source « TDB TD5 V2 » publié par l'auteur
de TD5 InfoDash.

Principales modifications :

- migration AndroidX, Gradle 8, suppression de ButterKnife
- interface redessinée : jauges monochromes qui ne prennent la couleur qu'en
  sortie de plage, hiérarchie, animation, chiffres tabulaires
- détection du signal perdu, pics de session, aide à la lecture par mesure
- correction de la pression de turbo, calculée depuis la pression ambiante
  mesurée plutôt qu'une atmosphère fixe
- correction de l'enfoncement de pédale, affiché en pourcentage
- valeurs négatives de balance injecteur, auparavant écrêtées à zéro
- limites de couple et de fumée remises dans le bon ordre
- codes défaut décodés en liste, confirmation avant effacement
- onglet de tests d'actionneurs, avec confirmation
- seuils de couleur modifiables et persistants
- autorisation USB demandée une seule fois

Licence Apache 2.0, voir [LICENSE](LICENSE).
