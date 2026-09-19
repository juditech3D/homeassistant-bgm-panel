# HA Panel

Application Android **native** qui transforme un panneau mural tactile 7 pouces
« background music » à bouton rotatif en terminal Home Assistant.

Elle existe parce que le WebView de ces panneaux est cassé au point que l'application
officielle *Home Assistant Companion* ne démarre pas. HA Panel n'utilise **aucun
WebView** : elle parle directement à Home Assistant en WebSocket et dessine son interface
en vues Android classiques.

> Projet personnel publié pour ceux qui possèdent le même écran. Fourni tel quel, sans
> garantie. Tout ce qui est décrit ici a été relevé sur **un seul exemplaire** de panneau.

---

## Ce que ça fait

- **Tableau de bord tactile** : tuiles d'entités Home Assistant, avec les vraies icônes
  Material Design Icons de l'interface web.
- **Bouton rotatif** : tourner règle la valeur de l'entité sélectionnée (luminosité,
  température, volume…), appuyer l'allume ou l'éteint, toucher une tuile la sélectionne.
- **Écran rond du bouton** : horloge, puis retour visuel du réglage en cours (arc de
  progression, anneau coloré selon le mode).
- **Sonnette** : la borne `DB` du bornier déclenche un carillon et l'affichage d'une
  caméra pendant quelques secondes. Le carillon est aussi déclenchable depuis Home
  Assistant.
- **Caméras** : image plein écran avec zoom à deux doigts et déplacement, via **go2rtc**
  (celui de Frigate) ou via Home Assistant.
- **Assistant vocal** : pipeline Assist de Home Assistant, avec un **mode privé** qui
  coupe complètement les micros.
- **Écran de veille** : diaporama de photos ou fond animé, puis extinction totale, avec
  deux délais réglables.
- **Lecteur réseau DLNA** : le panneau apparaît comme lecteur média, Home Assistant peut
  lui envoyer de l'audio (annonces, TTS, musique) sur son haut-parleur ou ses sorties
  amplifiées.
- **Capteurs publiés** vers Home Assistant : volume, état du micro, sonnette, avertisseur.
- **Wi-Fi et Bluetooth** réglables depuis le panneau : recherche des réseaux, saisie de la
  clé avec possibilité de l'afficher, et audio Bluetooth **dans les deux sens** — recevoir
  la musique d'un téléphone, ou diffuser vers une enceinte.
- **Vue caméras** : une grille de vignettes rafraîchies en continu, l'appui ouvre le
  plein écran avec zoom et déplacement. Chaque carte prend la forme de sa caméra — une
  caméra à deux optiques, qui filme en portrait, obtient une carte portrait.
- **Carte musique multiroom** : une colonne dédiée à droite — pochette, titre, commandes
  de lecture, volume, et des pastilles pour passer d'une pièce à l'autre.
- **Bandeau d'accueil** : salutation selon l'heure, horloge, date et météo reprise de
  Home Assistant.
- **Pont Zigbee** : le panneau embarque un coprocesseur Zigbee EmberZNet sur son port
  série ; l'application l'expose sur le réseau pour que **Zigbee2MQTT ou ZHA** s'en
  servent depuis le serveur Home Assistant.
- **Mise à jour par le réseau** : le panneau va chercher lui-même sa nouvelle version, ce
  qui évite de le démonter de sa boîte d'encastrement.
- **Chaque fonction s'active ou se désactive** séparément dans les réglages : un panneau
  dépourvu d'un matériel n'essaie jamais de s'en servir.

---

## Quel écran est compatible

Ces panneaux sont des **centrales de sonorisation multiroom (« background music host »)**
à base Tuya, vendues sans marque stable : le même matériel réapparaît sous des dizaines de
noms de boutique. Il n'y a donc pas de référence unique à citer — le système lui-même se
déclare `px30_evb`, la carte de développement générique de Rockchip, sans nom
constructeur.

**La seule façon fiable de savoir, c'est de vérifier.** Branchez le panneau en USB ou
activez l'ADB réseau, puis :

```bash
adb shell getprop ro.product.model
```

Les valeurs attendues : `px30_evb` pour le modèle, `8.1.0` pour la version d'Android,
`1024x600` pour `wm size`. Complétez avec `ls /proc/vendor/` (l'API constructeur : anneau,
relais, RS485) et `pm list packages | grep sznaner` (les applications d'origine).

### Le profil visé

| | |
|---|---|
| Carte | Rockchip **PX30**, arm64-v8a, 2 Go RAM |
| Système | **Android 8.1** (API 27), SELinux permissive, root disponible |
| Écran principal | **1024 × 600**, tactile Goodix |
| Écran du bouton | rond **GC9A01, 240 × 240**, RGB565, en SPI, sur `/dev/graphics/fb0` |
| Audio | codec RK809, amplificateur intégré, haut-parleur interne, 2 micros |
| Réseau | Ethernet **et** Wi-Fi |
| Bornier | `SPK L/R±`, `OUT L/R`, `AUX L/R`, `DB`, `GND`, `IO`, `OFF/ON`, `485 A/B` |
| **Zigbee** | coprocesseur **Silicon Labs EmberZNet** sur `/dev/ttyS3` — répond à l'ASH RST |
| Ports série | `ttyS0`/`ttyS1` Bluetooth, `ttyS2` RS485 du bornier, `ttyS3` Zigbee |

Aucun tuner radio : les applications de radio préinstallées passent par le réseau.

> **La radio Zigbee se cherche au bon endroit.** Aucun pilote noyau, aucun nœud de
> l'arbre matériel ne la mentionne — un coprocesseur Zigbee relié en UART n'en a pas
> besoin, il se pilote depuis l'espace utilisateur à travers un `/dev/ttyS*` ordinaire.
> Pour vérifier sur votre panneau, envoyez-lui la trame de réinitialisation ASH :
>
> ```bash
> adb shell su 0 sh -c 'printf "À8¼~" > /dev/ttyS3; xxd < /dev/ttyS3'
> ```
>
> Une réponse `1ac1 020b 0a52 7e` signe un NCP **EmberZNet**. Zigbee2MQTT le prend en
> charge par son pilote `ember`, tout comme l'intégration ZHA — il reste à relayer le port
> série sur le réseau pour que le serveur y accède.

### Ce que ça donne si votre panneau diffère

- **Pas d'écran rond dans le bouton** → désactivez la fonction, tout le reste marche.
- **Pas de `/proc/vendor/`** → désactivez « matériel constructeur » (anneau, avertisseur,
  relais, RS485).
- **Un autre processeur, un autre Android** → le tableau de bord, le bouton et Home
  Assistant devraient fonctionner ; le pilotage bas niveau de l'écran rond et du matériel
  constructeur, non.

### Où trouver ce type d'écran

Ces liens sont donnés **à titre d'exemple de la famille de produits**, aucun n'a été
vérifié comme strictement identique à l'exemplaire qui a servi au développement.
Cherchez « background music host », « smart home control panel amplifier »,
« Tuya music panel 7 inch » avec un bouton rotatif.

- [Jianshu — panneau 7" background music, ampli mural intégré](https://familyluxy.com/products/jianshu-tuya-smart-home-control-panel-7-background-music-host-zigbee-hub-built-in-wall-amplifier-diy-apps-home-assistant-alexa)
- [uemontech — panneau à bouton rotatif, Android 8.1, RS485, ampli 2×25 W](https://www.uemontech.com/products/6inch-smart-home-control-panel-centre-with-knob-key.html)
- [uemontech — variante 7", Android 8.1, RS485, relais](https://www.uemontech.com/en/products/7inch-Smart-home-automation-control-panel-screen.html)
- [Amazon — « Touch Screen in Wall Amplifier Audio 7" Smart Home Background Music »](https://www.amazon.com/Touch-Screen-Amplifier-Background-Stereo/dp/B0CRD1JHDD)
- [Guide Alibaba sur ces centrales de sonorisation](https://electronics.alibaba.com/buyingguides/smart-home-background-music-control-system-guide)

---

## Compatibilité Home Assistant

### Versions

| | |
|---|---|
| Développé et testé sur | Home Assistant **2025.x** |
| Minimum raisonnable | **2022.4** — le filtre par pièce emploie la fonction de modèle `area_name()`, apparue à cette version |
| Assistant vocal | **2023.5** minimum — la commande WebSocket `assist_pipeline/run` n'existe pas avant |

Sans l'assistant vocal et sans le filtre par pièce, une installation plus ancienne
fonctionnerait, l'API WebSocket employée étant stable de longue date.

### Ce qui est requis côté serveur

- Un **jeton d'accès longue durée** (profil → onglet Sécurité). C'est tout.
- Aucun module complémentaire, aucune intégration, aucune modification de
  `configuration.yaml` pour l'usage de base.

### Ce que l'application utilise

| API | Usage |
|---|---|
| WebSocket `auth`, `get_states`, `subscribe_events` | connexion, liste des entités, suivi d'état en temps réel |
| WebSocket `call_service` | toute commande envoyée (via les services `toggle`, jamais `turn_on`/`turn_off` choisis localement) |
| WebSocket `assist_pipeline/run` | assistant vocal |
| REST `/api/template` | récupération des pièces pour le filtre du sélecteur d'entités |
| REST `/api/states/sensor.*` | publication des capteurs du panneau |
| REST `/api/camera_proxy` | caméras, **chemin de secours seulement** |

### Ce que le panneau expose dans Home Assistant

Créés automatiquement si « publier les capteurs » est actif :

| Entité | Contenu |
|---|---|
| `sensor.panneau_volume` | volume de l'amplificateur, 0–100 |
| `sensor.panneau_micro` | `on` / `off` — permet de vérifier le mode privé à distance |
| `sensor.panneau_sonnette` | dernier appui sur la borne `DB` |
| `sensor.panneau_avertisseur` | état de l'avertisseur sonore |

### Ce que Home Assistant peut commander sur le panneau

Le carillon est déclenchable depuis Home Assistant, et le panneau s'annonce en **DLNA**
comme lecteur média : une fois découvert, il accepte `media_player.play_media`, le TTS et
le réglage de volume comme n'importe quelle enceinte réseau.

### Caméras : passer par go2rtc

**Beaucoup de caméras n'exposent aucune image fixe via Home Assistant.** Quand leur entité
n'annonce que le flux vidéo, `/api/camera_proxy/<entité>` répond **500** — ce n'est pas un
problème d'authentification, l'interface web les affiche en HLS, ce que le panneau ne sait
pas décoder de façon économe.

La bonne source est **go2rtc**, qui accompagne Frigate et sert des JPEG légers sur
`/api/frame.jpeg?src=<flux>`. Renseignez son adresse dans les réglages (par exemple
`http://192.168.1.10:1984`) : l'application liste alors les flux disponibles et vous les
choisissez dans une liste, sans taper d'identifiant d'entité.

---

## Outils nécessaires pour compiler

Ni Android Studio ni wrapper Gradle : on appelle Gradle directement. Les quatre outils
ci-dessous sont libres de téléchargement, aucun n'est inclus dans le dépôt.

| Outil | Version | Où l'obtenir |
|---|---|---|
| **JDK 17** | 17 (Microsoft OpenJDK) | `winget install Microsoft.OpenJDK.17`, ou [adoptium.net](https://adoptium.net/) |
| **Android SDK** | `platforms;android-34`, `build-tools;34.0.0`, `platform-tools` | [cmdline-tools](https://developer.android.com/studio#command-line-tools-only), puis `sdkmanager` |
| **Gradle** | 8.7 | [services.gradle.org](https://services.gradle.org/distributions/gradle-8.7-bin.zip) |
| **ADB** | fourni par `platform-tools` | — |

Installation du SDK une fois les cmdline-tools dépliés :

```bash
sdkmanager --licenses
```

puis `sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"`.

### Compiler

Créez `local.properties` à la racine (il n'est pas versionné, il contient un chemin propre
à votre machine) avec une seule ligne : `sdk.dir=C:\\Android`. Puis :

```powershell
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot'; $env:ANDROID_HOME='C:\Android'; & 'C:\Gradle\gradle-8.7\bin\gradle.bat' assembleDebug --no-daemon
```

L'APK sort dans `app/build/outputs/apk/debug/app-debug.apk`.

### Installer

```bash
adb connect 192.168.1.50:5555
```

puis `adb -s 192.168.1.50:5555 install -r app/build/outputs/apk/debug/app-debug.apk` et
`adb -s 192.168.1.50:5555 shell am start -n com.judit.hapanel/.MainActivity`.

### Build signé

Créez `keystore.properties` à la racine — **jamais versionné**, voir `.gitignore` — avec
`storeFile`, `storePassword`, `keyAlias` et `keyPassword`. Le trousseau se fabrique ainsi :

```bash
keytool -genkeypair -v -keystore keystore/hapanel.jks -alias hapanel -keyalg RSA -keysize 4096 -validity 10000
```

Ensuite `gradle assembleRelease --no-daemon`. Sans `keystore.properties`, `assembleRelease`
produit simplement un APK non signé.

> **Sauvegardez le trousseau ailleurs que sur la machine de compilation.** Le perdre
> interdit toute mise à jour par-dessus l'installation existante : Android refuse une mise
> à jour signée d'une autre clé, il faudrait désinstaller et tout ressaisir.

### Deux pièges de compilation

- **Encodage.** Le compilateur Kotlin tourne dans son propre démon, avec sa propre JVM :
  sous Windows il relit les sources en cp1252 et le point médian ressort en « Â· ». D'où
  `kotlin.daemon.jvmargs=-Dfile.encoding=UTF-8` dans `gradle.properties` — celui
  d'`org.gradle.jvmargs` ne suffit pas.
- **`targetSdk` est volontairement figé à 27.** En dessous de 28, Android autorise le HTTP
  en clair par défaut, ce qui permet de viser un Home Assistant local sans TLS. Ne le
  relevez pas sans ajouter une `network_security_config`.

---

## Réseau : Ethernet, Wi-Fi, Bluetooth

Le panneau se règle depuis **Réglages → Réseau → Wi-Fi et Bluetooth…**.

### Wi-Fi

Il n'y a aucune raison de s'en occuper tant que le RJ45 répond : l'écran l'annonce et
laisse le Wi-Fi de côté. **C'est seulement quand aucune liaison filaire n'est détectée que
cet écran s'ouvre de lui-même au démarrage** — encastré dans une boîte électrique, le
panneau n'aurait sinon plus aucun moyen de revenir sur le réseau.

La recherche liste les réseaux avec leur réception et leur protection, signale ceux déjà
enregistrés, et la clé peut être **affichée pendant la saisie** : au doigt, une clé WPA de
vingt caractères se tape mal en aveugle. Un appui long sur un réseau enregistré l'oublie.

> Android 8 ne rend les résultats de balayage qu'aux applications qui détiennent
> l'autorisation de localisation, **et seulement si la localisation est activée** dans le
> système. Sur un panneau mural sans GPS elle est souvent éteinte, et la recherche
> renverrait alors une liste vide sans la moindre erreur. L'écran le détecte, le dit, et
> propose de l'activer.

### Bluetooth audio, dans les deux sens

L'image Android de ce panneau déclare **les deux rôles A2DP** — vérifié sur l'appareil :
`com.android.bluetooth` expose `a2dp.A2dpService` comme `a2dpsink.A2dpSinkService`. Le
sens se choisit donc explicitement :

| Sens | Ce qui se passe |
|---|---|
| **Entrée** | Le panneau se rend visible ; un téléphone vient s'y connecter et sa musique sort sur le haut-parleur interne et les bornes d'enceintes. |
| **Sortie** | Le panneau cherche les enceintes et casques alentour et s'y connecte. Son propre haut-parleur se tait. |

Les deux rôles coexistent dans Android, mais pas sur le même flux : appairer une enceinte
pendant qu'un téléphone diffuse coupe le son sans explication. D'où un réglage plutôt
qu'un mélange. Un appui sur un appareil l'appaire ou s'y connecte, un appui long propose
de le déconnecter ou de l'oublier.

---

## Zigbee : utiliser la radio du panneau depuis Home Assistant

Ce panneau embarque un **coprocesseur Zigbee Silicon Labs EmberZNet** sur `/dev/ttyS3`.
Il ne se signale ni par un pilote noyau ni par un nœud de l'arbre matériel — un NCP relié
en UART n'en a pas besoin — d'où la facilité avec laquelle on le manque.

**Vérifier que le vôtre en a un** : Réglages → Zigbee → « Chercher la radio Zigbee ». Ou
en ligne de commande :

```bash
adb shell su 0 sh -c 'busybox stty -F /dev/ttyS3 115200 raw -echo -crtscts; printf "À8¼~" > /dev/ttyS3; timeout 2 head -c 8 < /dev/ttyS3 | xxd'
```

Une réponse `1ac1 020b 0a52 7e` est la trame **RSTACK** du protocole ASH : c'est un NCP
EmberZNet.

### Le pont

Zigbee2MQTT et ZHA tournent sur le serveur Home Assistant, la radio est sur le panneau.
L'application comble la distance : cochez **« Exposer la radio Zigbee sur le réseau »**
dans Réglages → Zigbee, et le panneau écoute sur le port TCP indiqué (8888 par défaut),
relayant octet pour octet vers le port série.

Un **seul client à la fois** : un coordinateur Zigbee ne se partage pas, deux clients
entrelaceraient leurs trames. Une seconde connexion est refusée.

### Côté serveur

**Zigbee2MQTT** — dans `configuration.yaml` :

```yaml
serial:
  port: tcp://192.168.1.196:8888
  adapter: ember
```

**ZHA** — à l'ajout de l'intégration, choisir la saisie manuelle et donner
`socket://192.168.1.196:8888`, type de radio **EZSP**.

> **Donnez une adresse IP fixe au panneau** avant de faire cela, par réservation DHCP sur
> sa MAC. Un coordinateur Zigbee qui change d'adresse, c'est tout le réseau Zigbee qui
> tombe.

> ⚠️ **Le réseau Zigbee vit dans le coprocesseur**, pas dans le panneau : ses clés et
> sa table d'appairages sont en mémoire non volatile sur la puce. Une réinstallation de
> l'application n'y touche pas. En revanche, ne faites pas dialoguer deux logiciels
> différents avec lui — Zigbee2MQTT **et** ZHA par exemple : le second reformerait le
> réseau et vous perdriez tous vos appairages.

---

## Mettre à jour sans démonter le panneau

Ces panneaux s'encastrent dans une boîte électrique : rebrancher un câble USB à chaque
correction n'est pas tenable. Trois chemins, du plus commode au moins commode.

### 1. La mise à jour intégrée

Dans **Réglages → Réseau**, indiquez une source :

- `compte/depot` — les **publications GitHub** du projet. La version est lue dans
  l'étiquette (`v0.3`), l'APK dans les fichiers joints.
- une **URL** vers un fichier JSON, à déposer où l'on veut — le dossier `www/` de Home
  Assistant le sert déjà :
  `{"versionName": "0.3", "url": "http://…/hapanel.apk", "notes": "…"}`

Le panneau vérifie au démarrage si l'option est cochée, et **n'installe jamais rien sans
accord** : il propose, on accepte. Sur un panneau rooté — ce qui est le cas de série —
l'installation passe par `pm install -r`, donc **sans toucher l'écran et sans perdre les
réglages**, jeton compris. Sans root, l'installateur d'Android prend le relais et demande
confirmation à l'écran. Dans les deux cas, le tableau de bord **revient de lui-même**
une fois la mise à jour posée, sur `ACTION_MY_PACKAGE_REPLACED`.

> Chaîne vérifiée de bout en bout sur le panneau : détection de la version publiée,
> proposition, téléchargement, installation silencieuse, retour automatique à l'écran,
> jeton Home Assistant intact après quatre mises à jour successives.
>
> Un piège au passage, pour qui voudrait s'en inspirer : **aucun shell lancé par
> l'application ne survit à sa propre mise à jour**, même détaché par `nohup`.
> `pm install -r` confie l'APK au service système puis tue le processus, et le shell
> meurt avec lui — l'installation est déjà acquise, mais tout ce qui devait suivre est
> perdu, en silence. `MY_PACKAGE_REPLACED` est le seul signal fiable, puisqu'il arrive
> dans un processus neuf.

### 2. ADB par le réseau

Aucun câble USB n'est nécessaire : `adb connect <ip-du-panneau>:5555` suffit, en Ethernet
comme en Wi-Fi. Attention, **le port ne survit pas forcément à un redémarrage** : sur ce
panneau, `service.adb.tcp.port` était bien à 5555 mais `persist.adb.tcp.port` était vide.
Pour le rendre permanent :

```bash
adb shell su 0 setprop persist.adb.tcp.port 5555
```

ADB conserve son autorisation par clé RSA : seul un ordinateur déjà accepté peut se
connecter. C'est néanmoins un port ouvert en permanence sur le réseau local — à mettre en
regard du fait que, sans lui, un redémarrage malheureux oblige à démonter le panneau.

### 3. Le câble USB

Le dernier recours, celui qu'on veut éviter.

---

## Préparer le panneau

Deux applications d'origine doivent être désactivées, sans quoi l'application ne peut pas
fonctionner correctement :

```bash
adb shell pm disable-user --user 0 com.sznaner.bgmz9
```

et de même pour `com.sznaner.volumedialog`.

- `bgmz9` est l'interface d'origine : elle reprend la main et éteint l'écran rond.
- `volumedialog` **vole le focus clavier pendant plusieurs secondes** à chaque changement
  de volume — symptôme : le bouton rotatif ne compte qu'un cran sur cinq.

Le détail, ainsi que le lancement automatique au démarrage, est dans
[la référence technique](docs/reference-technique.md).

---

## Configurer

Au premier lancement, l'écran de réglages demande l'adresse du serveur, le port, HTTPS ou
non, et le jeton.

> **Le jeton est long et le clavier tactile est pénible.** Saisissez-le depuis votre PC
> avec `adb shell input text "…"`. Le champ est masqué à l'affichage : ne faites pas de
> capture d'écran de cette page.

**Si votre serveur est en HTTPS avec un certificat Let's Encrypt, utilisez le nom du
certificat, pas l'adresse IP** — même si ce nom résout vers une adresse locale. Une IP
provoque une erreur de correspondance de nom.

---

## Documentation détaillée

[**docs/reference-technique.md**](docs/reference-technique.md) — une centaine de pages sur
le matériel et les choix d'implémentation : brochage du bornier, mappage des touches du
bouton, API `/proc/vendor/`, carte des GPIO, écran rond, DLNA, assistant vocal, carillons,
écran de veille, dépannage, et le détail des pièges rencontrés.

---

## Limites connues

- **Les capteurs de proximité et de luminosité ne répondent pas.** La puce est pourtant
  prévue par la carte — une WH7714UC déclarée à l'adresse i2c `0x38` — mais elle
  n'acquitte rien sur le bus : la sonde du pilote échoue au démarrage, toutes les lectures
  renvoyant des zéros. Le réveil par approche ne peut donc pas fonctionner.
  ⚠️ `dumpsys sensorservice` les annonce quand même, et n'importe quelle application
  d'information sur les capteurs les affichera comme présents : c'est la couche HAL de
  Rockchip qui les déclare d'après sa configuration, sans vérifier qu'un pilote s'est lié.
  Voir [la référence technique](docs/reference-technique.md) pour le détail.
- **L'appui long sur le bouton est indétectable** : le matériel émet une impulsion de
  ~130 µs, pas un maintien.
- Les bornes `IO` et `OFF/ON` ne sont pas identifiées.
- Les bornes de **relais** ne sont pas exposées sur le bornier, bien que les quatre
  relais répondent en `/proc/vendor/` : ils ne commandent rien d'extérieur.
- Sonnette câblée, assistant vocal et zoom caméra n'ont pas tous été validés à la main.

---

## Licence et crédits

Code sous licence **MIT**, voir [LICENSE](LICENSE).

Les icônes sont les **[Material Design Icons](https://pictogrammers.com/library/mdi/)**
(paquet `@mdi/font`), sous licence Apache 2.0 — la police et sa table de points de code
sont embarquées dans `app/src/main/assets/`, aucun accès réseau n'a lieu à l'exécution.
Voir [NOTICE](NOTICE).

Home Assistant est une marque de l'Open Home Foundation. Ce projet n'y est pas affilié.
