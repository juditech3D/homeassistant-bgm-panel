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

Aucun tuner radio : les applications de radio préinstallées passent par le réseau.

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

- **Les capteurs de proximité et de luminosité sont déclarés par le système mais
  physiquement absents** de cet exemplaire : le réveil par approche ne peut pas
  fonctionner. L'option existe, au cas où d'autres panneaux en soient pourvus.
- **L'appui long sur le bouton est indétectable** : le matériel émet une impulsion de
  ~130 µs, pas un maintien.
- Les bornes `IO` et `OFF/ON` ne sont pas identifiées.
- Sonnette câblée, assistant vocal et zoom caméra n'ont pas tous été validés à la main.

---

## Licence et crédits

Code sous licence **MIT**, voir [LICENSE](LICENSE).

Les icônes sont les **[Material Design Icons](https://pictogrammers.com/library/mdi/)**
(paquet `@mdi/font`), sous licence Apache 2.0 — la police et sa table de points de code
sont embarquées dans `app/src/main/assets/`, aucun accès réseau n'a lieu à l'exécution.
Voir [NOTICE](NOTICE).

Home Assistant est une marque de l'Open Home Foundation. Ce projet n'y est pas affilié.
