# Mirador – sécurité Android

Appli Android open source qui protège contre les liens piégés, les arnaques par SMS et messageries, et les logiciels espions (stalkerware). Tout est analysé sur le téléphone, sans compte ni serveur.

## Fonctions
| Module | Ce qu'il fait |
|---|---|
| **Contrôle des liens** | Mirador se déclare navigateur : chaque lien touché (SMS, mail, WhatsApp…) affiche d'abord un verdict Sûr / Prudence / Danger, puis s'ouvre dans votre vrai navigateur si vous le voulez. Imitation de marques, punycode, « @ » piège, IP, raccourcisseurs, extensions à risque, mots d'hameçonnage, APK, http. « Voir la vraie destination » suit les redirections sans ouvrir la page. |
| **Google Safe Browsing** | (optionnel) compare chaque lien à la base Google des sites d'arnaque/malware. Clé API gratuite à saisir dans Réglages. |
| **Liste de blocage** | Domaines bloqués + blocage des téléchargements .apk. |
| **QR code** | Scanne un QR code et vérifie le lien avant de l'ouvrir. |
| **Anti-spy** | Scan des applis : accessibilité, admin, notifications, clavier, appli cachée, hors Play Store, micro/SMS/appels/localisation. |
| **Surveillance auto** | Toutes les ~15 min (même après redémarrage) : alerte à chaque nouvelle appli installée et à chaque nouvel accès sensible accordé ; scan complet quotidien. |
| **SMS d'arnaque** | Alerte à la réception d'un SMS suspect (faux colis, amende, Ameli, CPF, « coucou maman »…) + analyse des 300 derniers SMS. |
| **Filtre DNS** | VPN local qui bloque pubs, traqueurs, malware et sites de logiciels espions pour toutes les applis (liste StevenBlack téléchargeable, ~80 000 domaines). |
| **Micro & caméra** | Journal de chaque activation, avec l'appli probable ; alerte si ça arrive écran éteint. |
| **Audit** | Verrouillage, débogage USB, options développeur, root, correctif de sécurité, services d'accessibilité. |
| **Mode sombre** | Automatique, selon le thème du téléphone. |
| **Base de logiciels espions** | Compare chaque appli (nom + certificat) à la base publique Echap (mSpy, FlexiSpy, TheTruthSpy…). Ses sites sont aussi bloqués par le filtre DNS. |
| **Listes anti-phishing** | OpenPhish + URLhaus, gratuites et sans clé, mises à jour chaque jour, vérifiées sur le téléphone. |
| **Données envoyées** | Applis qui envoient beaucoup de données en arrière-plan ou la nuit (alerte quotidienne). |
| **Code PIN** | Code ou empreinte pour ouvrir Mirador et changer les protections. |
| **Presse-papiers** | Propose de vérifier un lien copié à l'ouverture de l'appli. |
| **Permissions** | Qui a accès au micro, à la localisation, aux SMS… + accès spéciaux. |
| **Qui est sur mon Wi-Fi ?** | Liste les appareils connectés au réseau (box, téléphones, TV, imprimantes, objets connectés) via mDNS, UPnP et sondage des ports ; signale les caméras, les nouveaux appareils et les services risqués (Telnet, FTP). |
| **Wi-Fi** | Alerte si le Wi-Fi est ouvert, en WEP ou public (Android 12+). |
| **Mot de passe piraté** | Have I Been Pwned, sans envoyer le mot de passe. |
| **Raccourcis** | Boutons « Scan anti-spy » et « Filtre DNS » dans les réglages rapides + widget. |
| **Rapport** | Rapport complet en texte à partager. |
| **Traqueurs Bluetooth** | AirTag, SmartTag, Tile, Chipolo, Google Localiser ; alerte si l'un vous suit plus de 30 min. |
| **Messageries** | Arnaques dans WhatsApp, Messenger, Telegram, Signal, Gmail… (via les notifications). |
| **Intrus** | Photo (caméra avant) après 2 mauvais codes ; journal des échecs de déverrouillage du téléphone. |
| **Renvoi d'appels** | Détection du renvoi permanent + codes opérateur (*#21#, ##002#…). |
| **Signature changée** | Alerte si une appli est remplacée par une version signée différemment. |
| **Analyse d'APK** | Vérifie un .apk avant installation (aussi via « Ouvrir avec Mirador »). |
| **Guide** | « Je pense être espionné(e) » : étapes, preuves, comptes, contacts (3919, Echap…). |
| **Score** | Score de sécurité sur 100 avec les actions pour l'améliorer. |
| **Résumé hebdo** | Notification récapitulative chaque semaine. |
| **Sauvegarde** | Export / import des réglages (JSON). |

## Compiler et installer
1. Android Studio → *File > Open* → dossier du projet (`mirador-security`) → attendre la synchro Gradle.
2. Téléphone branché → ▶ **Run** (ou *Build > Build APK(s)*).
3. Dans l'appli : **Activer (navigateur par défaut)**, accepter les notifications, puis activer les modules voulus.

## Permissions demandées (et pourquoi)
- Notifications : alertes. SMS (réception/lecture) : détection d'arnaques, uniquement si vous l'activez.
- Accès aux données d'utilisation : savoir quelle appli utilise micro/caméra (optionnel).
- VPN : filtre DNS (le trafic ne sort pas vers un serveur Mirador ; DNS transmis à 1.1.1.1).

## Limites
- L'analyse des liens est heuristique : un site d'arnaque neuf peut passer (Safe Browsing réduit ce risque).
- Le filtre DNS est contourné si « DNS privé » est en mode strict ou si Chrome utilise son « DNS sécurisé ».
- Un seul VPN actif à la fois.
- Sur certaines marques (Xiaomi, Huawei, Samsung…), autorisez Mirador à tourner en arrière-plan (Paramètres > Batterie) pour que la surveillance ne soit pas coupée.
- `QUERY_ALL_PACKAGES`, SMS et `PACKAGE_USAGE_STATS` sont faits pour un usage perso ; le Play Store les restreint.

## Sécurité et vie privée
- **Code PIN** : stocké sous forme d'empreinte PBKDF2-SHA256 (60 000 itérations, sel aléatoire). Après 5 erreurs, attente de 30 s qui double à chaque nouvelle erreur (max 1 h), même si l'appli est fermée.
- **Applis récentes** : les écrans protégés ne laissent pas d'aperçu dans la liste des applis récentes.
- **Aucune sauvegarde** : ni sauvegarde cloud, ni transfert vers un autre téléphone (historique, journaux, photos d'intrus, code).
- **Réseau** : https obligatoire pour les services utilisés (Google Safe Browsing, Have I Been Pwned, listes de menaces). Le http n'est permis que pour suivre les redirections d'un lien http, sans jamais contacter le réseau local ni les adresses privées.
- **Limites connues** :
  - le filtre DNS transmet les requêtes non bloquées à 1.1.1.1 en DNS classique (non chiffré) ;
  - avec Google Safe Browsing activé, chaque lien vérifié est envoyé à Google ;
  - les listes de menaces sont téléchargées en https mais sans signature vérifiée ;
  - une sauvegarde des réglages exportée contient la clé Safe Browsing : ne la partagez pas.
## Licence
Mirador est distribué sous licence **GNU GPL v3** (voir [LICENSE](LICENSE)) : vous pouvez l'utiliser, le modifier et le redistribuer, à condition que toute version modifiée distribuée reste open source sous la même licence.
