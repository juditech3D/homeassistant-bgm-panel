# HA Panel — application native pour panneau tactile 7"

Application Android qui pilote Home Assistant depuis un panneau tactile mural à bouton
rotatif, **sans aucun WebView**.

**État : fonctionnel et validé sur le matériel.** Connexion, découverte des entités,
navigation et réglage au bouton rotatif, affichage sur l'écran rond, anneau lumineux.

---

## 1. Pourquoi cette application existe

L'application officielle **Home Assistant Companion plantait systématiquement** sur ce
panneau, à l'écran de connexion. Le diagnostic a montré que la panne n'a rien à voir
avec Home Assistant :

Le moteur WebView installé (`com.google.android.webview` 138.0.7204.181, posé
manuellement dans `/data/app`) souffre de deux défauts rédhibitoires sur cet appareil :

1. Sa classe principale référence **`android.webkit.PacProcessor`**, une API introduite
   dans **Android 13 (API 33)**. Le panneau tourne sous **Android 8.1 (API 27)** : la
   classe n'existe pas, le moteur ne se charge jamais.
   ```
   java.lang.ClassNotFoundException: Didn't find class "android.webkit.PacProcessor"
   ```
2. L'APK ne contient que des bibliothèques **32 bits** (`armeabi-v7a`), alors que les
   applications tournent en 64 bits — d'où `libwebviewchromium64.relro: No such file`.

L'erreur finalement affichée à l'écran (`Cannot call WebViewCachedFlags.init more than
once`) est **trompeuse** : c'est la conséquence du premier échec, pas la cause.

**Conséquence : toute application utilisant un WebView plante sur ce panneau**, pas
seulement Home Assistant. Vérifié avec le navigateur de test Chromium
(`org.chromium.webview_shell`), qui plante à l'identique. Fully Kiosk Browser est donc
également inutilisable.

Cette application n'utilise aucun WebView : elle parle directement à Home Assistant en
WebSocket. Elle est immunisée au problème par construction.

### Réparer le WebView, est-ce possible ?

En théorie oui : installer un « Android System WebView » **arm64-v8a** compatible
Android 8.0+, en `.apk` simple (pas `.apkm` ni `.xapk`), dans une version assez ancienne
pour ne pas dépendre de `PacProcessor` — autour des versions 95 à 105.

En pratique, **c'est probablement une impasse** : un moteur aussi ancien risque de mal
afficher, voire pas du tout, l'interface moderne de Home Assistant. On réparerait la
page de connexion sans réparer le tableau de bord.

Attention : ne comptez pas sur `/system/app/webview/webview.apk` (117 Mo) pour revenir
en arrière — il n'est enregistré comme **aucun paquet**, le désinstaller ne restaurerait
rien.

---

## 2. Le matériel

Panneau de **sonorisation multiroom** (Background Music) avec amplificateur intégré,
base Tuya, détourné en terminal domotique. Le bouton rotatif est à l'origine un
potentiomètre de volume.

| | |
|---|---|
| Carte | Rockchip **PX30**, arm64-v8a, 2 Go RAM |
| Système | **Android 8.1.0 (API 27)**, SELinux **permissive**, root disponible |
| Écran principal | 1024 × 600, densité 160, tactile Goodix |
| Écran du bouton | **GC9A01 rond, 240 × 240, RGB565**, sur SPI |
| Audio | codec RK809 : 1 sortie amplifiée + 2 micros + **haut-parleur intégré**. Aucun tuner radio : les applications de radio présentes sont purement logicielles |
| Réseau | Ethernet `192.168.1.50`, Wi-Fi `192.168.1.51` |
| Stockage | 4 Go de partition `/data`, **2,6 Go libres**. Lecteur de carte SD présent (contrôleur `dwmmc_rockchip`) mais **aucune carte insérée** |
| Autres | récepteur infrarouge **déclaré mais muet** (voir plus bas), caméra frontale |

### La fiche du constructeur

L'application d'origine porte une page *À propos* — `com.sznaner.settings`,
`AboutProductActivity` — qui donne enfin une désignation à ce matériel :

![Fiche de spécifications](images/specifications.png)

```
Model No.: F7
CPU: ARM Quad-Core Cortex-A35 1.5 GHz
Display: IPS LCD 1024*600
Memory Storage: RAM 2GB  ROM 8GB
Amplifier: 2*(10-25W) (MAX)
Output Impedance: 4-8 ohm
Power Supply: AC 94V-250V
```

Deux remarques.

**« F7 » n'est pas une référence de vente.** C'est la désignation du fabricant
d'origine, que les boutiques remplacent par la leur ; elle sert à reconnaître le
matériel une fois le panneau en main, pas à le commander.

**Le stockage annoncé diffère de celui mesuré.** La fiche dit 8 Go de ROM, là où
`/data` n'offre que 4 Go : le reste part dans les partitions système, comme
toujours. C'est la valeur mesurée qui compte pour savoir ce qu'on peut installer.

### Bornier externe

```
SPK R+ | SPK R- | SPK L+ | SPK L- | OUT R | OUT L | GND | AUX L | AUX R
      | DB | GND | IO | OFF/ON | 485 A | 485 B
```

| Borne | Rôle | Statut |
|---|---|---|
| `SPK R±` / `SPK L±` | sorties **amplifiées** pour enceintes passives | non câblé |
| `OUT R` / `OUT L` | sorties **ligne** vers un ampli ou des enceintes actives | non câblé |
| `AUX L` / `AUX R` | **entrée** ligne (source externe) | non câblé |
| `DB` | **sonnette** — contact sec vers `GND` | ✅ lu par l'application |
| `GND` | masse commune des entrées voisines | — |
| `IO` | entrée/sortie générique, usage non déterminé | à élucider |
| `OFF/ON` | entrée de mise en marche / veille externe | à élucider |
| `485 A` / `485 B` | **bus RS485** | non câblé |

**Aucune borne de relais** n'est exposée, alors que le pilote en déclare quatre.

Un **haut-parleur est intégré** au panneau : c'est sur lui que sort le son tant que rien
n'est câblé, vérifié à l'oreille avec un carillon envoyé en DLNA.

`IO` et `OFF/ON` restent à identifier. Les candidats côté logiciel sont `/proc/vendor/aux_mode`
et `/proc/vendor/power_on`, ainsi que le GPIO `vendor_aux` (115). Pour trancher il
faudrait relever l'état de ces fichiers pendant qu'on ponte la borne à `GND` — opération
à faire sur le matériel, sans risque puisqu'il s'agit d'entrées à contact sec.

### Bouton rotatif

Le keylayout générique d'Android mappe déjà les scancodes du pilote `knod-aispeech`.
**Aucun root ni accès à `/dev/input` n'est nécessaire** — les touches arrivent comme des
touches Android ordinaires :

| Geste | Scancode Linux | Touche Android |
|---|---|---|
| Rotation **droite** | 467 (`KEY_FN_F2`) | `KEYCODE_F2` (132) |
| Rotation **gauche** | 466 (`KEY_FN_F1`) | `KEYCODE_F1` (131) |
| **Appui** | **468 (`KEY_FN_F3`)** | `KEYCODE_F3` (133) |
| Appui (observé une fois) | 473 (`KEY_FN_F8`) | `KEYCODE_F8` (138) |

> ⚠️ **Ce mappage a coûté deux erreurs successives, à ne pas refaire.**
>
> La gauche émet **F1**, pas F3 — l'erreur initiale venait d'une attribution des sens
> d'après un tour rapide, et rendait toute une direction inopérante.
>
> L'appui émet **F3**, pas F8. `F3` avait d'abord été pris pour une variante de la
> rotation à gauche : conséquence, appuyer **baissait la luminosité** au lieu de basculer
> l'entité. Mesuré six fois sur six avec la journalisation intégrée. `F8` n'est apparu
> qu'une seule fois, lors des tout premiers relevés ; il reste traité comme un appui par
> sécurité, puisqu'il ne peut rien déclencher d'autre.
>
> **Méthode fiable pour vérifier**, bien meilleure qu'une capture `getevent` minutée :
> l'application journalise chaque touche de l'encodeur avec l'action qui en découle.
> ```
> adb -s 192.168.1.50:5555 logcat -c
> # manipuler le bouton sur le panneau, sans contrainte de temps
> adb -s 192.168.1.50:5555 logcat -d -s HaPanelKeys
> ```

### Deux limites matérielles à connaître

**L'appui long est impossible.** L'appui produit une impulsion DOWN+UP d'environ
**130 µs**, pas un maintien. Toute la logique d'interaction doit s'en passer.

**L'encodeur perd des crans.** Cinq crans physiques lents ne produisent qu'environ
**deux impulsions**, symétriquement dans les deux sens, et **le premier cran depuis
l'arrêt n'en produit aucune** — la position de repos tombe entre deux transitions
électriques. C'est le pilote `knod-aispeech`, rien ne peut être corrigé depuis l'app.

L'application compense par une **accélération** : en dessous de 200 ms entre deux
impulsions, le pas est multiplié par 4. Un tour rapide réel donne des écarts de 120 à
195 ms, l'accélération se déclenche donc bien. Rotation lente pour le réglage fin,
rotation rapide pour parcourir toute la plage.

Pour supprimer réellement ces pertes il faudrait court-circuiter le pilote et décoder
soi-même les voies A/B de l'encodeur sur les GPIO 112 et 114, ce qui exigerait le root
et une scrutation permanente. Non fait.

### ⚠️ Les capteurs de proximité et de luminosité ne répondent pas

La puce est bien **prévue par la carte** : une **WH7714UC**, capteur combiné
luminosité + proximité, déclarée dans l'arbre matériel à l'adresse i2c `0x38` du bus
`i2c-1`, avec `status = okay`.

```
/sys/bus/i2c/devices/1-0038/name    →  ls_wh7714uc   (luminosité)
/sys/bus/i2c/devices/1-0038-1/name  →  ps_wh7714uc   (proximité)
```

**Mais elle ne répond pas sur le bus.** La sonde du pilote Rockchip échoue au démarrage,
toutes les lectures renvoyant des zéros :

```
sensors 1-0038: sensor_chip_init:ls_wh7714uc:devid=0x0
sensors 1-0038: lsensor_active:fail to read sensor status
        buffer, [0]: 0x00, [1]: 0x00
        lsensor_active:reg=0x0, reg_ctrl=0x0, enable=0
sensors 1-0038: lsensor_active:fail to active sensor
sensors: probe of 1-0038 failed with error -2
```

Aucun pilote n'est donc rattaché au nœud (`/sys/bus/i2c/devices/1-0038-1/` ne contient ni
`driver`, ni `enable`, ni `data`), et **aucun périphérique d'entrée correspondant
n'apparaît** dans `/proc/bus/input/devices` — où l'on ne trouve que le bouton rotatif,
l'anneau, la touche d'alimentation, le tactile Goodix, le récepteur infrarouge et les
touches ADC.

> **Piège à connaître.** `dumpsys sensorservice` annonce pourtant
> « Total 3 h/w sensors, 3 running » et liste un `Proximity sensor` marqué `wakeUp` ainsi
> qu'un `Light sensor`. C'est la couche HAL de Rockchip qui les déclare **d'après sa
> configuration**, sans vérifier qu'un pilote noyau s'est lié. N'importe quelle
> application d'information sur les capteurs les affichera donc comme présents. Ils ne
> renvoient jamais la moindre valeur.

Côté Android, cela se traduit par :

```
SensorsHal: Couldn't open /dev/lightsensor (No such file or directory)
SensorsHal: Couldn't open /dev/psensor (No such file or directory)
SensorService: Error activating sensor 3 (Function not implemented)
```

**Pourquoi la puce ne répond pas**, cela n'a pas pu être tranché à distance. Deux
hypothèses : le composant n'est pas monté sur cette variante de carte — l'arbre matériel
est partagé entre plusieurs modèles — ou bien il l'est mais reste non alimenté. Le nœud
de l'arbre est d'ailleurs incomplet, le pilote s'en plaint :

```
of_get_named_gpiod_flags: can't parse 'irq-gpio'   property of node '/i2c@ff190000/light@38[0]'
of_get_named_gpiod_flags: can't parse 'reset-gpio' property of node '/i2c@ff190000/light@38[0]'
of_get_named_gpiod_flags: can't parse 'power-gpio' property of node '/i2c@ff190000/light@38[0]'
```

Pas de ligne d'interruption déclarée, ce qui serait de toute façon rédhibitoire pour un
capteur de proximité censé réveiller l'écran. Rien non plus dans `/proc/vendor/` qui
permettrait de l'alimenter à la main.

> **Le récepteur infrarouge est dans le même cas.** Le pilote `gpio_ir_recv` est chargé,
> `event4` existe, `rc0` annonce une douzaine de protocoles — et pourtant, après des
> dizaines d'appuis sur une télécommande à moins d'un mètre, tous protocoles activés :
>
> ```
> 65:   0   0   0   0   gpio0  17  Edge   gpio-ir-recv-irq
> ```
>
> **Zéro interruption depuis le démarrage.** La broche n'a jamais vu le moindre front, là
> où le tactile en compte 6129 et le bouton rotatif 142. Aucune photodiode ne répond.
>
> `/proc/interrupts` est d'ailleurs le bon outil pour ce genre de question : il ne dépend
> ni d'un pilote correctement lié, ni des tampons de `getevent`, qui ne vide sa sortie
> qu'à la sortie du processus — un `getevent -c 6` tué par un `timeout` perd tout ce
> qu'il avait capturé, et fait passer un périphérique vivant pour muet.

**Un motif se dégage** : l'arbre matériel de ce panneau est partagé avec des variantes
mieux dotées, et il déclare plusieurs composants qui ne sont pas montés — capteur de
luminosité, capteur de proximité, récepteur infrarouge. À chaque fois, le logiciel
annonce le matériel et le matériel ne répond pas. Avant de bâtir quoi que ce soit sur un
périphérique de ce panneau, vérifier son compteur dans `/proc/interrupts`.

### La preuve par l'outillage du constructeur

Le panneau embarque `com.sznaner.testdemo`, **l'application de test de production**. Elle
parcourt le matériel test après test, et la liste de ses activités dit tout de ce que la
carte possède réellement :

```
knobActivity          RelayTestingActivity      SerialPortActivity
TouchActivity         Touch2Activity            ScreenScribingActivity
WiFiActivity          EthernetActivity          BluetoothActivity
MusicActivity         SoundRecordingActivity    MemoryActivity
MiguActivity          DeviceInformationActivity
```

**Ni proximité, ni luminosité, ni infrarouge, ni caméra.** Le constructeur teste l'écran,
le tactile, le bouton rotatif, les réseaux, le port série, les relais, le micro et la
mémoire — et rien d'autre. Un capteur monté serait testé : c'est l'objet même de cette
application.

L'application générique de Rockchip, `com.DeviceTest`, est présente elle aussi et
propose bien un `LightsensorTestActivity` — mais son champ `Light:` **reste vide**. Elle
liste aussi GPS, boussole, gyroscope et radio FM, qui n'existent pas davantage : c'est une
application universelle, dont la liste ne dit rien de la carte.

> Ce que l'on voit en façade et qui ressemble à une LED émettrice accompagnée d'une
> photodiode est vraisemblablement une **fenêtre moulée dans la face avant**, partagée
> avec les variantes qui, elles, embarquent le composant. Le logement existe, le composant
> n'est pas posé. `RelayTestingActivity` et `SerialPortActivity` confirment en revanche
> que les relais et le port série, eux, sont bien réels.

**Conséquences** : le réveil de l'écran par approche est impossible, et les capteurs
`panneau_luminosite` et `panneau_presence` ne peuvent rien publier. L'application le
détecte au démarrage, le journalise une fois et se désinscrit, plutôt que de publier des
valeurs fantômes.

**Contournement** : un détecteur de présence externe — mmWave Zigbee, ESPHome — piloté
par une automatisation Home Assistant qui bascule
`input_boolean.panneau_ecran_principal`. L'application sait déjà réagir à cette entité,
il n'y a rien à ajouter côté panneau.

### Écran rond du bouton

Ce n'est **pas** un écran Android : le système ne connaît qu'un seul display (le
1024 × 600). Celui-ci est un framebuffer brut.

- Nœud : `/dev/graphics/fb0`, permissions `crwxrwxrwx` → **accessible sans root**
- Format : 240 × 240, RGB565, stride 480, soit **115 200 octets par trame**
- **La dalle est montée en miroir horizontal** : un rouge dessiné en haut à gauche
  ressort en haut à droite. Le rendu applique donc une symétrie sur l'axe vertical.
- SELinux refuse l'écriture (`avc: denied { write } for name="fb0"`) mais le mode
  permissif laisse passer. **Si SELinux repassait en enforcing, l'écran rond cesserait
  de fonctionner.**

> ⚠️ **L'écran rond doit être alimenté par `/proc/vendor/lcd_ctrl`, remis à `off` à
> chaque redémarrage du panneau.** C'était `com.sznaner.bgmz9` qui l'activait au boot,
> et cette application a été désactivée. Symptôme trompeur : les écritures dans le
> framebuffer réussissent sans la moindre erreur, mais la dalle reste noire.
>
> `MainActivity` appelle donc `VendorHw.setKnobScreenPower(true)` à son démarrage. En cas
> de doute :
> ```
> adb shell cat /proc/vendor/lcd_ctrl          # doit répondre "on"
> adb shell cat /sys/kernel/debug/gpio | grep lcd_ctrl   # gpio-110 doit être "out hi"
> ```
> Malgré son nom, `lcd_ctrl` ne concerne **pas** l'écran principal, qui a son propre
> rétroéclairage PWM dans `/sys/class/backlight/backlight/`.

### Anneau lumineux

Piloté par **un seul GPIO** (`gpio-13`, `led_ctrl_en`), via `/proc/vendor/led_ctrl` :

- `off` → **rouge** (état de repos)
- `on` → **blanc** (état actif)

**Ce n'est pas un RGB adressable.** Aucune couleur intermédiaire n'est possible : le
montage est vraisemblablement un rouge alimenté en permanence, plus une ligne qui ajoute
le vert et le bleu. Vérifié en comparant `/sys/kernel/debug/gpio` entre les deux états,
et confirmé par l'application de test usine du fabricant (`spc_testdemo`), qui ne connaît
elle non plus que `on`/`off`.

### API constructeur `/proc/vendor/`

Tous ces fichiers sont en `rw-rw-rw-`, donc utilisables **sans root** :

| Fichier | Rôle |
|---|---|
| `led_ctrl` | anneau du bouton (`on` = blanc, `off` = rouge) |
| `lcd_ctrl` | **alimentation de l'écran rond du bouton** — pas l'écran principal |
| `touch_ctrl` | dalle tactile de l'écran principal |
| `horn_mode`, `mute_mode`, `mute_manual` | buzzer et sourdine de l'ampli |
| `485_tx_mode` | sens du bus RS485 |
| `aux_mode`, `power_on` | entrée auxiliaire, alimentation |
| `relay_first` … `relay_forth` | 4 relais — **voir avertissement ci-dessous** |

> ⚠️ **Les relais ne sont pas exposés dans l'interface, délibérément.** Aucune borne de
> relais n'apparaît sur le bornier de cet exemplaire ; ils proviennent probablement d'un
> pilote partagé avec d'autres modèles de la gamme. Ne les activer qu'après avoir vérifié
> à quoi ils sont câblés — s'ils commandent du 230 V réel, un essai à l'aveugle peut
> avoir des conséquences physiques.

### Carte des GPIO

Relevée avec `adb shell cat /sys/kernel/debug/gpio` (nécessite root).

| GPIO | Fonction |
|---|---|
| 13 | `led_ctrl_en` — **anneau du bouton** |
| 113 / 117 / 102 / 97 | relais 1 / 2 / 3 / 4 |
| 112 / 114 / 106 | encodeur du bouton : voies A / B, bouton C |
| 99 / 100 | écran rond `fb_gc9a01` |
| 0 | `485_tx_en` |
| 104 | `zigbee_reset` |
| 107 / 109 | buzzer, sourdine |
| 110 | rétroéclairage écran principal |
| 96 / 98 / 101 / 103 | `gpio-k1`..`k4` — **ne correspondent à aucun bouton physique** sur cet exemplaire |

---

## 3. Modifications apportées au panneau

Ces changements sont **en dehors du code** de l'application. Il faut les connaître : sans
eux l'application ne fonctionne pas correctement.

### `com.sznaner.bgmz9` désactivé — indispensable

Cette application système posait en permanence une fenêtre `SYSTEM_ALERT_WINDOW` qui
**captait le focus clavier**. Résultat : notre activité était bien au premier plan
(`mFocusedApp`), mais toutes les touches du bouton partaient dans la fenêtre du
constructeur (`mCurrentFocus`). Une seule touche sur quatre atteignait l'application.

```bash
adb -s 192.168.1.50:5555 shell pm disable com.sznaner.bgmz9
```

**Ce qui est perdu** : la passerelle **Zigbee/Tuya** du panneau, le dialogue de volume,
la notification de sonnette, et l'horloge d'origine de l'écran rond (reprise par notre
application).

**Pour revenir en arrière :**
```bash
adb -s 192.168.1.50:5555 shell pm enable com.sznaner.bgmz9
```

### `com.sznaner.volumedialog` désactivé — indispensable aussi

Même piège, découvert plus tard. Cette application affiche l'indicateur de volume du
constructeur et **capte le focus clavier pendant 2 à 4 secondes** à chaque changement de
volume. Symptôme : en tournant le bouton sur la carte de volume, **un seul cran était pris
en compte**, les suivants partaient dans sa fenêtre.

```bash
adb -s 192.168.1.50:5555 shell pm disable com.sznaner.volumedialog
```

Vérifié après désactivation : 5 crans donnent bien 5 pas, et `mCurrentFocus` reste sur
l'application. Réversible par `pm enable`.

> **Leçon à retenir.** Ce panneau compte plusieurs applications constructeur qui posent
> des fenêtres en surimpression et volent le focus clavier. Devant tout symptôme du type
> « le bouton ne répond qu'une fois sur N », le premier réflexe doit être :
> ```bash
> adb shell "dumpsys window | grep mCurrentFocus"
> ```
> avant et après l'action. Deux occurrences déjà : `bgmz9` et `volumedialog`.

### Lancement automatique au démarrage — deux mécanismes

L'application déclare un `BootReceiver` sur `BOOT_COMPLETED`, et il fonctionne. Mais cela
ne suffisait pas : **`com.seaky.nspanelpro.tools` lançait Chrome une demi-seconde après**
et passait devant.

Cet outil dispose précisément d'une fonction « lancer une application au démarrage ». On
l'a donc pointé sur le tableau de bord plutôt que de faire coexister deux mécanismes
concurrents :

```
/data/data/com.seaky.nspanelpro.tools/shared_prefs/com.seaky.nspanelpro.tools_preferences.xml
  <string name="launch-app">com.judit.hapanel</string>     (était com.android.chrome)
```

Chrome reste installé et lançable à la main ; seul son démarrage automatique a changé.
Pour revenir en arrière, rouvrir NSPanel Tools et remettre Chrome dans « Launch app ».

**Validé par deux redémarrages complets** : au second, l'application est au premier plan,
connectée, sans aucune intervention.

### ADB sur le réseau

```bash
adb -s <série> tcpip 5555
adb connect 192.168.1.50:5555
```

**Ne survit pas à un redémarrage** : à refaire en USB après chaque reboot. Cela ouvre un
port ADB **sans authentification** sur le réseau local — à réserver aux périodes de
développement.

---

## 4. Configuration de la connexion

> ⚠️ **Utiliser le nom DNS, pas l'adresse IP.**

Le serveur Home Assistant de cette installation :

- écoute en **HTTPS uniquement** sur le port 8123 (le HTTP en clair est refusé, ce qui
  provoque l'erreur `unexpected end of stream`) ;
- présente un certificat **Let's Encrypt** au nom `maison.duckdns.org` ;
- et ce nom **résout vers `192.168.1.10`**, c'est-à-dire l'adresse locale.

En utilisant le nom plutôt que l'IP, on obtient donc un **certificat valide** *et* un
**trafic qui ne sort pas du réseau local** — sans aucun contournement TLS. Avec l'IP
brute, la vérification du nom échouerait et il faudrait désactiver des contrôles de
sécurité.

| Réglage | Valeur |
|---|---|
| Adresse | `maison.duckdns.org` |
| Port | `8123` |
| HTTPS / WSS | **coché** |

### Le jeton

Créé dans Home Assistant : **votre nom** (en bas à gauche) → onglet **Sécurité** →
**Créer un jeton**. Il donne un accès complet à l'installation, à traiter comme un
mot de passe.

Un jeton fait environ 180 caractères — le saisir au doigt est pénible. Pour l'envoyer
depuis le presse-papier, placer le curseur dans le champ sur le panneau puis, **depuis
votre propre terminal** :

```bash
adb -s 192.168.1.50:5555 shell input text "VOTRE_JETON"
```

La frappe simulée prend une dizaine de secondes. Ne pas toucher le panneau pendant, sous
peine de perdre le focus du champ.

---

## 5. Compiler et installer

Aucun Android Studio n'est nécessaire, et le projet n'a **pas de wrapper Gradle** : on
appelle Gradle directement.

### Outils installés sur le PC

| Outil | Emplacement |
|---|---|
| JDK 17 | `C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot` |
| SDK Android | `C:\Android` (cmdline-tools, platform-tools, android-34, build-tools 34.0.0) |
| Gradle 8.7 | `C:\Gradle\gradle-8.7` |
| ADB | `C:\platform-tools\adb.exe` |

Les licences du SDK sont acceptées via les fichiers de `C:\Android\licenses\`.

### Compiler

```powershell
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot'
$env:ANDROID_HOME='C:\Android'
cd 'C:\Users\judit\Application HA panel 7p'
& 'C:\Gradle\gradle-8.7\bin\gradle.bat' assembleDebug --no-daemon
```

### Construire la release signée

```powershell
& 'C:\Gradle\gradle-8.7\bin\gradle.bat' assembleRelease --no-daemon
```

Le trousseau est dans `keystore/hapanel.jks`, les identifiants dans
`keystore.properties` — **ni l'un ni l'autre ne sont versionnés** (voir `.gitignore`).
Sans `keystore.properties`, le build de release se fait sans signature.

> ⚠️ **Sauvegarder `keystore/hapanel.jks` ailleurs que sur cette machine.** Le perdre
> interdit toute mise à jour par-dessus l'installation existante : Android refuse une
> mise à jour signée par une autre clé, il faudrait désinstaller et donc ressaisir le
> jeton.

Signature vérifiée avec `apksigner` : schéma **v2**, un signataire, `CN=HA Panel`. Le
v1 (JAR signing) est inutile ici, le v2 étant reconnu depuis l'API 24.

Lint refuse par défaut un build de release dont le `targetSdk` est inférieur à 33, au nom
des exigences du Play Store. La règle `ExpiredTargetSdkVersion` est désactivée dans
`app/build.gradle.kts` : cette application n'y sera jamais publiée.

**Passer du debug à la release** impose une désinstallation, les signatures différant.
Pour ne pas perdre le jeton :

```bash
adb shell "cp /data/data/com.judit.hapanel/shared_prefs/hapanel.xml /data/local/tmp/sauvegarde.xml"
adb uninstall com.judit.hapanel
adb install app-release.apk
# rétablir, en corrigeant le propriétaire : l'uid change à la réinstallation
adb shell "UID=\$(stat -c %u /data/data/com.judit.hapanel); mkdir -p /data/data/com.judit.hapanel/shared_prefs; cp /data/local/tmp/sauvegarde.xml /data/data/com.judit.hapanel/shared_prefs/hapanel.xml; chown \$UID:\$UID /data/data/com.judit.hapanel/shared_prefs /data/data/com.judit.hapanel/shared_prefs/hapanel.xml; chmod 660 /data/data/com.judit.hapanel/shared_prefs/hapanel.xml"
```

### Installer

```bash
C:\platform-tools\adb.exe connect 192.168.1.50:5555
C:\platform-tools\adb.exe -s 192.168.1.50:5555 install -r "app\build\outputs\apk\debug\app-debug.apk"
C:\platform-tools\adb.exe -s 192.168.1.50:5555 shell am start -n com.judit.hapanel/.MainActivity
```

> ⚠️ **Encodage.** Le démon du compilateur Kotlin tourne dans sa propre JVM et relit les
> sources avec l'encodage par défaut de Windows (cp1252) : `·` ressort en `Â·`, `°` en
> `Â°`. D'où `kotlin.daemon.jvmargs=-Dfile.encoding=UTF-8` dans `gradle.properties` —
> celui d'`org.gradle.jvmargs` ne suffit pas — et `options.encoding = "UTF-8"` sur les
> tâches `JavaCompile`. Les fichiers source sont bien en UTF-8, ce n'est pas eux qu'il
> faut corriger.

> `targetSdk` est volontairement fixé à **27**. En dessous de 28, Android autorise le
> trafic HTTP en clair par défaut — utile si l'on bascule un jour sur un serveur local
> sans TLS. Ne pas le relever sans ajouter une `network_security_config`.

---

## 6. Architecture du code

```
app/src/main/java/com/judit/hapanel/
├── HaClient.kt             WebSocket Home Assistant + REST (capteurs, liste d'entités)
├── Entity.kt               Modèle d'entité, valeurs normalisées, libellés
├── MdiIcons.kt             Icônes Material Design, celles de Home Assistant
├── MainActivity.kt         Tableau de bord + logique du bouton rotatif
├── TileAdapter.kt          Grille de tuiles (6 colonnes)
├── EntityPickerActivity.kt Sélecteur d'entités à cases à cocher, avec recherche
├── KnobScreen.kt           Rendu sur l'écran rond via /dev/graphics/fb0
├── VendorHw.kt             API /proc/vendor/ (anneau, buzzer, rétroéclairage, relais)
├── PanelSensors.kt         Publication luminosité et présence vers Home Assistant
├── Prefs.kt                Réglages persistants
└── SetupActivity.kt        Écran de configuration, point d'entrée
```

### Le protocole Home Assistant

`HaClient` ouvre un WebSocket sur `/api/websocket`, répond à `auth_required` avec le
jeton, puis émet `get_states` et `subscribe_events` sur `state_changed`. Les actions
passent par `call_service`. Reconnexion automatique avec un délai qui double à chaque
échec, plafonné à 30 secondes — sauf sur `auth_invalid`, où l'on arrête (inutile
d'insister avec un jeton refusé).

### La logique du bouton

| Geste | Effet |
|---|---|
| **Toucher une tuile** | la sélectionne — **sans** l'allumer ni l'éteindre |
| **Tourner le bouton** | règle directement la valeur de l'entité sélectionnée, par pas de 5 % |
| **Appuyer sur le bouton** | allume ou éteint l'entité sélectionnée |

Le réglage s'adapte au type d'entité : luminosité pour une `light`, consigne pour un
`climate`, volume pour un `media_player`, position pour un `cover`, vitesse pour un `fan`.

Sur une entité qui n'a rien à régler (un simple `switch`), la rotation **déplace la
sélection** : cela évite que le bouton soit inerte, et permet de naviguer sans toucher
l'écran.

L'anneau passe au **blanc** pendant un réglage et revient au **rouge** 2,5 secondes après
la dernière rotation. Retour automatique à l'horloge sur l'écran rond après **10 secondes**
d'inactivité. Les envois vers Home Assistant sont temporisés à **250 ms** pour ne pas
saturer le serveur pendant qu'on tourne.

### Pourquoi la bascule passe par les services `toggle` du serveur

La bascule n'envoie **jamais** `turn_on` ou `turn_off` choisis par l'application : elle
appelle `homeassistant.toggle` (ou `cover.toggle`, `climate.toggle`), et laisse le
serveur décider du sens.

C'est une correction d'un vrai bug, pas une préférence de style. En décidant côté
application à partir de l'état mémorisé, deux appuis rapprochés envoyaient deux fois la
même commande : l'entité était déjà éteinte, on redemandait « éteins », rien ne se
passait. Le symptôme était un appui sur deux sans effet. Le serveur, lui, connaît l'état
réel au moment où il traite l'ordre.

Dans le même esprit, un réglage encore en attente au moment d'un appui est **abandonné**,
pas confirmé : l'envoyer allumerait l'entité juste avant qu'on ne demande de la basculer.
La temporisation n'étant que de 250 ms, la valeur réglée a de toute façon déjà été
transmise pendant la rotation.

Validé par six bascules consécutives rapprochées : six réussites sur six.

### Le sélecteur d'entités

Accessible de deux façons : le bouton **« ENTITÉS »** directement sur le tableau de bord
(pour ajouter ou retirer des entités au quotidien), ou depuis l'écran de configuration.

Il récupère la liste complète en REST (`GET /api/states`) — pas besoin de WebSocket ici —
et présente chaque entité avec une case à cocher, sa pièce, son nom, son `entity_id` et
son état. Un champ de recherche filtre sur le nom ou l'identifiant, ce qui est
indispensable : cette installation en expose **environ 1600**.

**Filtre par pièce.** Les pièces (« areas ») ne sont pas exposées par `/api/states` :
elles vivent dans le registre, accessible seulement en WebSocket. Plutôt que d'ouvrir une
seconde connexion temps réel juste pour ça, on interroge le **moteur de gabarits** de
Home Assistant en un seul appel REST :

```
POST /api/template
{"template": "{% for s in states %}{{ s.entity_id }}|{{ area_name(s.entity_id) or '' }}\n{% endfor %}"}
```

Le menu déroulant propose « Toutes les pièces », chaque pièce, puis « Sans pièce ». Le
bouton **« Cocher la liste »** sélectionne tout ce qui est affiché — pratique pour
prendre une pièce entière d'un geste. Si l'appel échoue ou qu'aucune pièce n'est définie,
le filtre est simplement désactivé et le reste fonctionne.

Au retour du sélecteur, le tableau de bord réapplique la sélection sans redemander la
liste au serveur.

La sélection est enregistrée dans `Prefs.pinned` sous forme d'`entity_id` séparés par des
virgules, **dans l'ordre d'affichage** de la liste plutôt que dans l'ordre de cochage —
c'est plus prévisible pour retrouver ses tuiles. Une sélection vide réactive la découverte
automatique.

---

## 7. Le matériel du panneau dans Home Assistant

`PanelHardware` expose l'électronique propre au panneau : avertisseur, sourdine, écran du
bouton, anneau lumineux, relais, bus RS485, et l'entrée sonnette.

### Commander le panneau depuis Home Assistant

L'application **surveille des entités `input_boolean`** et recopie leur état sur le
matériel. Ce choix évite d'ouvrir un port sur le panneau et de dépendre d'un courtier
MQTT : on réutilise la connexion WebSocket déjà établie.

Créer ces auxiliaires dans Home Assistant (Paramètres → Appareils et services →
Auxiliaires → Bouton à bascule). Seuls ceux qui existent sont pris en compte, inutile de
tous les créer.

| Auxiliaire à créer | Effet |
|---|---|
| `input_boolean.panneau_avertisseur` | avertisseur sonore |
| `input_boolean.panneau_sourdine` | sourdine de l'amplificateur |
| `input_boolean.panneau_ecran_bouton` | alimentation de l'écran rond |
| `input_boolean.panneau_anneau` | anneau du bouton : allumé = blanc, éteint = rouge |
| `input_boolean.panneau_relais_1` … `_4` | les quatre relais |

L'état est appliqué à la connexion **et** à chaque changement, de sorte que le panneau
se réaligne tout seul après un redémarrage.

### Ce que le panneau publie

Republié en capteurs toutes les 30 secondes, et uniquement sur changement :
`sensor.panneau_avertisseur`, `panneau_sourdine`, `panneau_anneau`,
`panneau_ecran_bouton`, `panneau_rs485`, `panneau_relais_1` à `_4`, plus
`panneau_luminosite` et `panneau_presence` venant des capteurs embarqués.

### La sonnette (borne DB)

L'entrée sonnette est le périphérique `gpio_ring` sur `/dev/input/event1` — « ring » au
sens de *sonner*, **rien à voir avec l'anneau lumineux**, confusion facile à faire.

Elle émet `KEY_DOLLAR` (code 439), que le keylayout du système **ne mappe pas** : Android
ne le transmet jamais à une application sous forme de touche. L'application lit donc le
périphérique directement, ce qui impose d'élargir les droits du nœud :

```
su 0 sh -c 'chmod 666 /dev/input/event1'
```

L'application le fait elle-même au démarrage — le `su` de ce panneau accepte la forme
`su 0 <commande>`, et fonctionne depuis le contexte d'une application ordinaire. Si le
root venait à manquer, la sonnette est simplement désactivée et tout le reste continue.

Un coup de sonnette met `sensor.panneau_sonnette` à `on` pendant 3 secondes puis le
remet à `off`, de sorte qu'un second coup produise bien une nouvelle transition sur
laquelle déclencher une automatisation.

### Les liaisons série et le bus RS485

Le panneau expose six ports série, dont trois sont câblés à quelque chose d'utile.

| Port | Propriétaire | Rôle |
|---|---|---|
| `/dev/ttyS0`, `ttyS1` | `bluetooth:net_bt` | module Bluetooth |
| `/dev/ttyS2` | *libre* | **coordinateur Zigbee** intégré |
| `/dev/ttyS3` | `system:system` | non identifié |
| `/dev/ttyS4`, `ttyS5` | `root:root` | non identifié |

**Le coordinateur Zigbee est désormais libre.** Il était tenu par `com.sznaner.bgmz9`,
la passerelle Tuya, qu'on a désactivée pour libérer le bouton rotatif. Plus aucun
processus ne détient ce port.

C'est une possibilité sérieuse : brancher **Zigbee2MQTT** dessus, soit directement sur le
panneau, soit en exposant le port sur le réseau (`ser2net`, ou l'outil
`com.seaky.nspanelpro.tools` déjà installé qui sait faire tourner Zigbee2MQTT).
Le panneau deviendrait alors la passerelle Zigbee de la maison, pilotée par Home
Assistant — sans dongle USB supplémentaire. Le protocole du coordinateur reste à
identifier (Texas Instruments Z-Stack très probablement, vu la présence de
`ZigbeeZStackFragment` dans l'outil communautaire).

**Le bus RS485** sort sur les bornes `485 A` / `485 B`. Le sens est commandé par
`/proc/vendor/485_tx_mode` (`send` / `receive`), et le pilote expose en plus `en_485`,
`read_485` et `write_485` dans le répertoire du rétroéclairage — rangement inattendu,
mais c'est bien là.

Usages possibles du RS485 : automates et variateurs industriels, centrales d'alarme,
compteurs d'énergie et onduleurs solaires en **Modbus RTU**, éclairage **DMX512**, ou
dialogue avec d'autres panneaux de la même gamme. Home Assistant sait parler Modbus, il
suffirait de relayer le port. Rien n'est câblé pour l'instant.

### L'audio du panneau

Le panneau est à l'origine un appareil de sonorisation : amplificateur intégré, sorties
haut-parleurs, entrée AUX. `AudioController` en pilote le volume et commande les
applications de lecture présentes sur l'appareil — **Spotify** et **Pandora** y sont
préinstallées, toutes deux avec une session média active.

| Auxiliaire à créer | Effet |
|---|---|
| `input_number.panneau_volume` | volume 0 à 100 |
| `input_boolean.panneau_lecture` | lecture / pause |
| `input_text.panneau_lancer_app` | lance un lecteur par son nom de paquet, par ex. `com.spotify.music` |

Capteurs publiés : `sensor.panneau_volume`, `panneau_lecture`, `panneau_titre`
(« Titre — Artiste »), `panneau_lecteur` (le paquet qui joue).

L'échelle matérielle du volume compte **21 crans** (0 à 20) ; l'application travaille en
pourcentage et convertit.

#### L'autorisation d'accès aux sessions média

Android réserve `MediaSessionManager.getActiveSessions()` aux applications système ou aux
écouteurs de notifications. D'où [`MediaAccessService`](app/src/main/java/com/judit/hapanel/MediaAccessService.kt),
un service **entièrement vide** qui n'existe que pour obtenir cet accès.

L'autorisation s'accorde une fois pour toutes :

```bash
adb shell cmd notification allow_listener com.judit.hapanel/com.judit.hapanel.MediaAccessService
```

ou à la main dans Paramètres → Applications → Accès aux notifications. Sans elle, le
volume reste commandable mais le titre en cours et les commandes de transport sont
indisponibles — l'application le gère sans planter.

Vérifier que le service est bien lié :
```bash
adb shell "dumpsys notification | grep -i hapanel"
```

### Veille de l'écran et réveil

La veille est gérée par l'application, **pas par Android**. Raison : le tableau de bord
maintient `FLAG_KEEP_SCREEN_ON`, indispensable pour rester affiché, ce qui neutralise
précisément la veille du système. `ScreenManager` éteint donc le rétroéclairage en sysfs
sans endormir Android : l'écran s'éteint vraiment, mais continue de recevoir les
touchers — le réveil au doigt est immédiat, sans délai de rallumage.

```
/sys/class/backlight/backlight/bl_power     0 = allumé, 4 = éteint
/sys/class/backlight/backlight/brightness   0 à max_brightness (255)
```

> ⚠️ **`bl_power` ne suffit pas sur ce panneau.** Le pilote accepte la consigne
> `FB_BLANK_POWERDOWN` et n'en fait rien : mesuré sur l'appareil, `bl_power` à 4 pendant
> que `brightness` et `actual_brightness` tenaient 255. La dalle restait donc éclairée à
> fond derrière une image noire — invisible en plein jour, très visible dans une pièce
> sombre, et une usure pour rien.
>
> C'est **`brightness` à 0** qui éteint réellement, et le pilote l'honore à toutes les
> valeurs, 0 compris. L'application écrit donc les deux : `bl_power` par principe — sur
> un panneau dont le pilote l'honore, il coupe l'alimentation du rétroeclairage plutôt
> que d'en mettre la modulation à zéro — puis `brightness` à 0, qui fait le travail.
>
> Au réveil, l'ordre compte : `bl_power` d'abord, la luminosité ensuite, faute de quoi le
> rallumage écraserait la valeur à peine écrite.

> **À ne pas confondre avec `/proc/vendor/lcd_ctrl`**, qui commande l'écran rond du
> bouton rotatif, pas l'écran principal.

`bl_power` appartient à root et `brightness` à system : l'application élargit leurs
droits au démarrage via `su 0 sh -c 'chmod 666 …'`. **À refaire à chaque démarrage**, les
permissions sysfs étant réinitialisées au redémarrage du panneau. Sans root, repli sur la
luminosité de fenêtre, qui ne permet que d'assombrir.

**Sources de réveil** : toucher l'écran, tourner ou appuyer sur le bouton, et le capteur
de proximité quand quelqu'un s'approche. Le premier geste sur un écran endormi ne sert
**qu'à réveiller** — il serait déroutant d'éteindre une lampe en voulant rallumer le
panneau.

Réglages dans l'écran de configuration : délai de veille en secondes (0 = jamais),
luminosité en pourcentage, et réveil par proximité. Commandable aussi depuis Home
Assistant via `input_boolean.panneau_ecran_principal` et
`input_number.panneau_luminosite_ecran`.

Validé : endormissement après le délai, réveil au toucher, réveil au bouton,
rendormissement.

### La caméra à la sonnerie

Quand on sonne, le panneau **réveille l'écran et affiche la caméra choisie** en plein
écran, par-dessus tout — écran de veille compris. Un toucher la referme, sinon elle
disparaît au bout du délai réglé (30 s par défaut).

#### ⚠️ Passer par go2rtc, pas par l'API de Home Assistant

**L'API caméra de Home Assistant ne fonctionne pas avec ces caméras.** Elles n'exposent
que du flux vidéo — leur entité annonce `supported_features: 2` — et l'interface web les
affiche en HLS. Mais la route qui fournit une **image fixe** échoue :

```
/api/camera_proxy_stream/<entité>  → 200 puis 0 image, flux fermé aussitôt
/api/camera_proxy/<entité>         → 500
```

Vérifié sur plusieurs caméras, avec l'en-tête `Bearer` **et** avec le jeton signé de
l'attribut `access_token` : même résultat. Ce n'est pas un problème d'authentification,
et l'erreur est bien côté serveur.

**La solution est go2rtc**, qui accompagne Frigate et tourne en général sur le serveur
Home Assistant, port 1984. Il sert des images JPEG légères, sans authentification :

```
/api/streams                 liste des flux déclarés
/api/frame.jpeg?src=<flux>   une image JPEG — c'est ce qu'on utilise
```

Renseigner son adresse dans les réglages. Le sélecteur liste alors ses flux, les entités
Home Assistant restant proposées en dessous pour les installations où elles fonctionnent.
Les valeurs go2rtc sont stockées préfixées de `go2rtc:`.

Lire du HLS imposerait ExoPlayer, une bibliothèque lourde, et le décodage vidéo sur ce
PX30 est très incertain. Le JPEG rafraîchi reste parfaitement lisible pour voir qui sonne.

#### Le choix de la caméra

**La caméra se choisit dans une liste**, pas en saisissant un identifiant : les réglages
interrogent go2rtc et Home Assistant à leur ouverture et proposent tout ce qu'ils
trouvent, avec des noms lisibles. Personne ne connaît ses identifiants par cœur, et une
faute de frappe ne se verrait qu'au moment où quelqu'un sonne.

La liste est chargée en tâche de fond : les réglages restent utilisables même serveur
injoignable, la valeur déjà enregistrée s'affichant en attendant.

#### Le chemin Home Assistant, conservé en secours

Pour les installations où l'API caméra fonctionne, le code tente d'abord le flux MJPEG
puis se rabat sur l'image fixe. Le multipart est analysé à la main plutôt qu'avec une
bibliothèque vidéo : une centaine de lignes, aucune dépendance, aucun décodeur matériel
sollicité. On s'appuie sur le `Content-Length` de chaque partie plutôt que de chercher la
frontière dans les octets, un JPEG pouvant contenir n'importe quelle séquence.

Deux délais de lecture distincts, et c'est important : **court pour le flux** (6 s), afin
qu'un flux muet cède vite la place aux images, **long pour les instantanés** (25 s), une
caméra RTSP pouvant mettre plusieurs secondes à en produire un.

#### Cadrage, zoom et déplacement

Une image **plus haute que large** est calée sur la largeur de l'écran puis **défilée au
doigt** : l'afficher en entier la réduirait à une bande illisible. Une caméra à double
optique fait ici 2304 × 2592. Les autres sont montrées en entier, quitte à laisser des
bandes — sur une caméra de sonnette, rogner ferait perdre le visage.

**Pincer pour zoomer** (jusqu'à 5×, autour des doigts et non du centre), **glisser pour
déplacer**, **appuyer pour fermer**. Le clic n'est reconnu que si le doigt n'a pas bougé
de plus de quelques pixels, sans quoi tout déplacement refermerait la caméra. Le cadrage
repart à zéro à chaque sonnerie.

> ⚠️ **Toujours décoder les images à l'échelle de l'écran.** Ce n'est pas une
> optimisation. Une caméra de 2304 × 2592 pèse **23 Mo** décodée en ARGB, et l'on décode
> une image toutes les 700 ms : sur ce panneau de 2 Go, cela provoque un
> `OutOfMemoryError` en quelques images. Or `OutOfMemoryError` est une **`Error`, pas une
> `Exception`** — il traversait les `catch (e: Exception)` et tuait le fil de lecture en
> silence, laissant l'écran sur « Connexion à la caméra… » indéfiniment. Symptôme
> parfaitement trompeur : la caméra semblait « ne pas marcher » alors que l'image
> arrivait bien.
>
> Corrigé par `inSampleSize` calculé sur la taille de la dalle, `RGB_565`, et un `catch`
> explicite de `OutOfMemoryError`. La même image tombe alors sous le mégaoctet.

Déclenché par la sonnette physique comme par `input_button.panneau_carillon`.

**Bouton de test.** Les réglages comportent un bouton *« Tester la sonnerie complète »* :
il enregistre, revient au tableau de bord et rejoue la séquence entière — carillon puis
caméra — exactement comme un vrai coup de sonnette. Indispensable tant qu'aucune sonnette
n'est câblée à la borne `DB`, et pratique pour régler le volume du carillon ou vérifier
qu'on a visé la bonne caméra.

Le test passe par le tableau de bord plutôt que d'agir depuis l'écran de configuration :
la caméra s'affiche par-dessus le tableau de bord, ce qui serait impossible autrement.

### Assistant vocal et mode privé

Le panneau ne fait que **capturer le micro et jouer la réponse** : transcription,
interprétation et synthèse vocale restent sur le serveur, via le pipeline **Assist** de
Home Assistant. Ce matériel n'a ni la puissance ni la mémoire pour un moteur local, et
rien ne part chez un tiers.

**Déclenchement à la demande, jamais de mot d'éveil.** Le micro ne s'ouvre que sur un
appui sur la carte « Assistant vocal », et se referme seul — à la fin de la phrase
détectée par le serveur, ou au bout de 15 secondes quoi qu'il arrive. C'est le choix le
plus simple, le plus fiable sur ce matériel, et de loin le plus respectueux de la vie
privée.

Protocole, pour mémoire : `assist_pipeline/run` sur le WebSocket, le serveur répond par
un événement `run-start` portant un `stt_binary_handler_id`, puis l'audio part en trames
**binaires préfixées de cet octet**. Une trame ne contenant que l'octet signale la fin de
la parole. Les événements `stt-end`, `intent-end` et `tts-end` rapportent
respectivement ce qui a été entendu, la réponse, et l'URL à jouer. Format attendu :
**16 kHz mono 16 bits**.

**Le mode privé** est une carte du tableau de bord, à côté du volume : un toucher coupe
le micro, l'icône passe au micro barré et le libellé à « COUPÉ ». Tant qu'il est coupé,
l'assistant est entièrement inopérant — le micro n'est jamais ouvert, pas même une
seconde. Couper interrompt aussi une écoute en cours, et non pas seulement la suivante.

Commandable depuis Home Assistant par `input_boolean.panneau_micro`, et l'état est
republié en `sensor.panneau_micro`.

La permission micro n'est demandée que si l'assistant est activé dans les réglages :
inutile d'inquiéter qui ne s'en servira pas. Pour l'accorder sans passer par la boîte de
dialogue :
```bash
adb shell pm grant com.judit.hapanel android.permission.RECORD_AUDIO
```

### Les réglages

Quatre sections repliables : **Connexion au serveur**, **Entités affichées**, **Écran et
veille**, **Audio, sonnette et assistant**.

La connexion est **repliée d'office** dès que le panneau est configuré. Ce n'est pas
cosmétique : ces champs ne servent plus une fois le panneau en service, et une fausse
manœuvre au doigt sur l'adresse ou le jeton le déconnecterait — la saisie tactile est
peu précise sur cette dalle.

Deux détails qui manquaient et qui bloquaient réellement l'usage : un bouton **Retour**,
le panneau n'ayant aucun bouton matériel, et la **fermeture du clavier en touchant le
fond**, sans quoi il masquait la moitié de l'écran.

> ⚠️ **Le champ du jeton est masqué** (`inputType="textPassword"`). Il s'affichait en
> clair auparavant : visible de quiconque passe devant le panneau, et capturé par toute
> copie d'écran. Ne pas revenir en arrière.

### Le carillon de la sonnette

Le carillon peut venir de **deux sources** :

- un fichier déposé dans `/sdcard/HAPanel/carillons` (wav, mp3, ogg, m4a, aac, flac)
- le **carillon synthétisé par l'application**, choisi par défaut

Ce dernier mérite une explication : il est fabriqué au premier usage puis gardé en cache,
plutôt qu'embarqué dans l'APK. Deux notes descendantes (mi puis do) avec décroissance
exponentielle et quelques harmoniques — c'est ce qui donne le timbre métallique d'un
carillon plutôt qu'un bip électronique. Ainsi il y a toujours quelque chose à entendre
dès l'installation, sans fichier binaire dans le dépôt ni téléchargement.

Pour ajouter les siens :
```bash
adb -s 192.168.1.50:5555 push mes-carillons/*.mp3 /sdcard/HAPanel/carillons/
```

Le choix se fait dans l'écran de configuration, avec un bouton **« Essayer »** pour
l'entendre avant de valider. Une case permet de désactiver le carillon sur la sonnette
physique, par exemple pour ne garder que le déclenchement depuis Home Assistant.

**Trois déclencheurs**, indépendants :

1. La **sonnette physique** sur la borne `DB`
2. `input_button.panneau_carillon` — la forme juste pour une action
3. `input_boolean.panneau_carillon` — pour qui préfère un interrupteur

Les deux entités Home Assistant réagissent au **changement d'état**, jamais à l'état
lui-même : sans cette précaution, la republication des états à chaque reconnexion au
serveur relancerait un carillon. C'est ce qui permet de déclencher depuis n'importe quoi
— un bouton du tableau de bord, un capteur ESPHome, une automatisation.

### Écran de veille

Deux étapes distinctes, réglables séparément dans l'écran de configuration, toutes deux
comptées depuis la dernière interaction :

1. **Écran de veille** (60 s par défaut) — l'écran reste allumé et affiche un fond
2. **Extinction totale** (120 s par défaut) — le rétroéclairage s'éteint

Mettre 0 désactive l'étape correspondante. Pour n'avoir que le fond sans extinction :
extinction à 0. Pour n'avoir que l'extinction : écran de veille à 0.

**Deux modes de fond**, au choix dans les réglages :

- **Fond animé** — particules lumineuses dérivant sur un dégradé sombre, dessinées au
  Canvas. Volontairement sobre : 45 particules, halos par dégradé radial et **aucun
  flou**, un `BlurMaskFilter` effondrant la fluidité sur ce PX30. Aucune ressource
  externe, rien à installer.
- **Diaporama photos** — lit un dossier du panneau, par défaut `/sdcard/HAPanel/fonds`.
  Effet **Ken Burns** (zoom et translation lents, direction tirée au sort à chaque photo)
  et fondu enchaîné de 1,5 s. Une photo toutes les 20 s.

Pour déposer des photos :
```bash
adb -s 192.168.1.50:5555 push mes-photos/*.jpg /sdcard/HAPanel/fonds/
```
Formats acceptés : jpg, jpeg, png, webp. Les images sont **décodées à l'échelle** avec
`inSampleSize` et en `RGB_565` — une photo d'appareil moderne dépasserait les 50 Mo une
fois décompressée, ce que ce panneau ne supporterait pas. Si le dossier est vide, repli
automatique sur le fond animé.

Tout geste — toucher, rotation, appui — fait revenir au tableau de bord sans rien
déclencher d'autre.

> **Pourquoi pas WallPanel ?** [lovelace-wallpanel](https://github.com/j-a-n/lovelace-wallpanel)
> est la référence pour les panneaux Home Assistant, mais c'est un module **Lovelace qui
> s'exécute dans le navigateur** : inutilisable ici, le WebView du panneau étant hors
> service. Ses idées ont été reprises et réimplémentées en natif.

### La carte de volume du panneau

Une tuile **locale** — elle n'existe pas dans Home Assistant — est placée en tête du
tableau de bord : « Volume du panneau ». Le bouton rotatif y règle directement le volume
de l'amplificateur intégré, et l'appui bascule la sourdine. C'est la fonction d'origine
de ce bouton.

Un **bip est émis à chaque cran**, au volume qu'on vient de régler : un pourcentage à
l'écran ne dit rien de la puissance réellement entendue.

Techniquement, l'entité porte le domaine réservé `panneau` (`panneau.volume`) et est
reconstruite à chaque rafraîchissement depuis l'état du matériel. Pendant un réglage, une
valeur cible est figée — même principe que le `pendingValue` des lampes — parce que
`AudioManager` met un instant à refléter une écriture et qu'une relecture immédiate
renverrait une valeur périmée.

### Le panneau comme lecteur réseau (DLNA)

Home Assistant possède une intégration **DLNA Digital Media Renderer** native. Le
paquet `dlna` de l'application implémente un récepteur conforme : le panneau est
**découvert tout seul** et apparaît comme une entité `media_player`. On peut alors lui
envoyer de la musique, de la synthèse vocale, ou l'inclure dans un groupe de lecteurs —
sans rien installer d'autre sur le panneau.

```
dlna/
├── DlnaRendererService.kt  service de premier plan, orchestration, verrous
├── SsdpResponder.kt        découverte : réponses M-SEARCH et annonces NOTIFY
├── UpnpServer.kt           serveur HTTP et traitement des commandes SOAP
├── UpnpXml.kt              descriptions du périphérique et des services
└── RendererPlayer.kt       lecture du flux (MediaPlayer)
```

Trois services UPnP sont exposés : **AVTransport** (transport), **RenderingControl**
(volume, sourdine), **ConnectionManager** (négociation des formats). Formats acceptés :
MP3, AAC, FLAC, WAV, OGG, L16 — tous décodés nativement par Android 8.1.

#### Points qui ont demandé attention

- **Le verrou multicast est indispensable.** Sans `WifiManager.createMulticastLock`,
  Android filtre le trafic multicast et les recherches SSDP n'arrivent jamais : le
  panneau reste invisible, sans le moindre message d'erreur.
- **L'interface réseau doit être choisie explicitement.** Le panneau a Ethernet et Wi-Fi
  actifs simultanément ; sans cela le multicast part sur la mauvaise carte.
- **L'identifiant UUID est persisté** dans les préférences. Sans cela, Home Assistant
  verrait un nouveau lecteur à chaque redémarrage et accumulerait les doublons.
- **Les abonnements GENA sont acceptés mais aucun événement n'est émis.** Les contrôleurs
  interrogent l'état en secours, ce qui suffit et évite d'écrire toute la machinerie
  d'événements. À revoir si Home Assistant tardait à refléter les changements.
- **Service de premier plan**, la lecture devant survivre au passage du tableau de bord
  en arrière-plan, avec verrou de veille partiel pendant la lecture.

#### Vérifier que ça marche

```bash
# la description doit répondre
curl http://192.168.1.50:<port>/description.xml
# le port est journalisé au démarrage
adb logcat -d -s DlnaRenderer UpnpServer
```

Validé de bout en bout : découverte SSDP depuis le PC, description servie, envoi d'un
flux par SOAP, et transitions `TRANSITIONING → PLAYING → STOPPED` cohérentes avec la
durée du fichier.

> ⚠️ **Les relais.** Le bornier externe de cet exemplaire n'expose aucune borne de
> relais ; ils viennent vraisemblablement d'un pilote partagé avec d'autres modèles. Ils
> restent commandables, mais vérifier à quoi ils sont câblés avant de les actionner.

## 8. Activer les fonctionnalités

Tous les panneaux n'ont pas le même matériel, ni les mêmes usages. La première section
des réglages, **Fonctionnalités**, permet d'activer ou de désactiver chaque fonction
optionnelle indépendamment.

| Fonction | Ce qu'elle suppose |
|---|---|
| Écran rond du bouton rotatif | un afficheur dans le bouton, sur `/dev/graphics/fb0` |
| Carte de volume de l'amplificateur | un amplificateur intégré |
| Matériel constructeur | l'API `/proc/vendor/` : anneau, avertisseur, relais, RS485 |
| Sonnette et carillon | une sonnette câblée sur la borne `DB`, et le root pour la lire |
| Lecteur réseau DLNA | rien de particulier, mais tout le monde n'en veut pas |
| Assistant vocal | un micro fonctionnel et un pipeline Assist configuré |
| Publier les capteurs | des capteurs réellement présents |

**Une fonction désactivée est entièrement inactive** : rien n'est lu, rien n'est écrit,
rien n'est publié vers Home Assistant. Ce n'est pas qu'un masquage de l'interface — c'est
ce qui permet d'installer l'application sur un panneau d'un autre modèle sans qu'elle
tente d'écrire dans un `/proc/vendor` inexistant ou d'ouvrir un micro absent.

C'est le prérequis à toute diffusion du projet : sans cela, la première personne à
l'installer sur un matériel légèrement différent aurait une application qui journalise
des erreurs en boucle.

## 9. Limites connues

- **Pas de couleur sur l'anneau** — limite matérielle, deux états seulement.
- **Pas d'appui long** — limite matérielle, l'encodeur envoie des impulsions.
- **L'écran rond dépend de SELinux permissive.** Si le mode passait en enforcing, il
  faudrait signer l'application au niveau système ou passer par root.
- **Zigbee/Tuya hors service** tant que `bgmz9` est désactivé.
- **Pas de gestion du HTTPS avec certificat auto-signé** : la configuration actuelle
  fonctionne parce que le certificat est valide et que le nom résout en local.
- **Découverte automatique plafonnée à 48 entités**, triées par domaine puis par nom.
  Au-delà, renseigner explicitement la liste dans les réglages.

---

## 10. Dépannage

| Symptôme | Cause probable | Vérification |
|---|---|---|
| `unexpected end of stream` | on parle HTTP à un serveur HTTPS | cocher HTTPS dans les réglages |
| Erreur de certificat | l'adresse est une IP, pas le nom du certificat | utiliser `maison.duckdns.org` |
| `Déconnecté : jeton refusé` | jeton expiré ou révoqué | en recréer un dans Home Assistant |
| Le bouton ne répond pas | une app pose une fenêtre qui capte le focus | `adb shell dumpsys window \| grep mCurrentFocus` — doit afficher `com.judit.hapanel` |
| La rotation ne règle rien | l'entité sélectionnée n'a pas de valeur réglable | c'est le comportement attendu : la rotation déplace alors la sélection |
| Un appui sur deux sans effet | régression : la bascule décide du sens depuis l'état mémorisé au lieu d'utiliser `homeassistant.toggle` | vérifier `HaClient.toggle` |
| L'écran rond reste noir | écriture sur `fb0` refusée | `adb shell getenforce` doit répondre `Permissive` |
| L'écran rond est inversé | symétrie sur le mauvais axe | constante `MIRROR_HORIZONTAL` dans `KnobScreen.kt` |
| Aucune tuile affichée | liste d'entités épinglées erronée | vider le champ « Entités à afficher » pour repasser en automatique |

### Commandes utiles

```bash
# Touches reçues par l'application (trace de débogage)
adb -s 192.168.1.50:5555 logcat -s HaPanelKeys

# Qui détient le focus clavier
adb -s 192.168.1.50:5555 shell "dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'"

# État de l'anneau lumineux
adb -s 192.168.1.50:5555 shell cat /proc/vendor/led_ctrl

# Simuler le bouton : 467 = droite, 468 = gauche, 473 = appui
adb -s 192.168.1.50:5555 shell "sendevent /dev/input/event0 1 467 1; sendevent /dev/input/event0 0 0 0; sendevent /dev/input/event0 1 467 0; sendevent /dev/input/event0 0 0 0"
```

---

## 11. À faire

- [x] ~~Sélecteur d'entités à cases à cocher~~ — fait
- [x] ~~Filtre par pièce dans le sélecteur~~ — fait
- [x] ~~Bouton d'accès direct aux entités depuis le tableau de bord~~ — fait
- [x] ~~Retirer la trace de débogage du focus clavier~~ — fait
- [x] ~~Icônes sur les tuiles~~ — fait, avec les icônes de Home Assistant
- [x] ~~Lancement automatique au démarrage~~ — fait et validé par deux redémarrages
- [x] ~~Build de release signé~~ — fait, trousseau dans `keystore/`
- [x] ~~Exploiter le reste du matériel : avertisseur, sonnette, relais, RS485~~ — fait
- [x] ~~Audio : volume, transport, titre en cours, lancement d'un lecteur~~ — fait
- [x] ~~Récepteur DLNA pour recevoir de l'audio depuis Home Assistant~~ — fait et validé
- [x] ~~Veille de l'écran~~ — fait et validé. **Le réveil par proximité est impossible**,
      le capteur n'existe pas sur ce matériel (voir section 2).
- [x] ~~Carte de volume réglable au bouton, avec bip de contrôle~~ — fait

### Reste à faire

- [x] ~~Fond d'écran personnalisable~~ — fait : fond animé et diaporama photos
- [x] ~~Carillons au choix~~ — fait, dossier + carillon synthétisé par défaut
- [x] ~~Déclencher le carillon depuis Home Assistant~~ — fait, `input_button` ou
      `input_boolean`
- [ ] **Vérifier le carillon à la sonnette physique** — non validé : l'injection
      d'événements sur `/dev/input/event1` ne fonctionne pas, il faut appuyer sur une
      vraie sonnette câblée à la borne `DB`
- [ ] **Afficher une caméra Frigate à la sonnerie** — la caméra étant choisie d'avance
      dans les réglages. Attention : le flux devra être lu en natif, pas en WebView.
      Une piste : le flux RTSP ou le JPEG rafraîchi de l'API Frigate.
- [x] ~~Assistant vocal Home Assistant~~ — fait, déclenchement à la demande
- [x] ~~Carte « mode privé »~~ — fait
- [ ] **Valider l'assistant vocal à la voix** — non testé : il faut parler devant le
      panneau et vérifier que Home Assistant répond. Le pipeline Assist doit être
      configuré côté serveur.
- [x] ~~Activation par fonctionnalité dans les réglages~~ — fait, voir section 8
- [ ] **Publier le projet sur GitHub**, pour le partager à d'autres possesseurs du même
      écran. Voir la liste des outils externes ci-dessous : elle doit figurer dans le
      dépôt, sans quoi personne ne pourra reconstruire l'application.
- [ ] **Câbler et vérifier les sorties audio externes** — `SPK` et `OUT R/L` ne sont
      reliées à rien pour l'instant, seul le haut-parleur interne a été testé
- [ ] **Identifier les bornes `IO` et `OFF/ON`** (voir section 2)
- [ ] **Vérifier la fiabilité de la rotation** sur le volume : l'injection d'événements
      par `sendevent` perd des impulsions (1 à 3 sur 5 selon l'espacement), mais la
      rotation physique semble fiable. À confirmer à la main avant de conclure à un
      défaut.
- [x] ~~Caméra Frigate à la sonnerie~~ — fait
- [x] ~~Valider la caméra~~ — fait via go2rtc, image reçue et affichée

### Outils externes nécessaires pour reconstruire le projet

À installer sur une machine neuve. Aucun n'est versionné, tous sont librement
téléchargeables.

| Outil | Version utilisée | Où |
|---|---|---|
| JDK | 17 (Microsoft OpenJDK) | `winget install Microsoft.OpenJDK.17` |
| Android SDK | cmdline-tools + platform-tools + `platforms;android-34` + `build-tools;34.0.0` | `dl.google.com/android/repository/commandlinetools-win-*.zip` |
| Gradle | 8.7 | `services.gradle.org/distributions/gradle-8.7-bin.zip` |
| ADB | fourni par platform-tools | — |

**Ressources embarquées dans le dépôt** : la police Material Design Icons et sa table de
points de code, dans `app/src/main/assets/` (1,5 Mo au total). Elles proviennent du
paquet `@mdi/font`, licence Apache 2.0 — à créditer si le projet est publié.

**À ne jamais versionner** : `keystore/` et `keystore.properties` (déjà dans le
`.gitignore`), ainsi que `local.properties` qui contient un chemin absolu propre à la
machine.

### Les icônes

Ce sont les **Material Design Icons**, exactement celles de l'interface web de Home
Assistant. La police (1,3 Mo) et la table nom → point de code (7448 icônes, 170 ko) sont
embarquées dans `app/src/main/assets/` : **aucun accès réseau à l'exécution**.

Une icône est rendue comme un caractère de cette police, dans un `TextView`. Les points
de code MDI se situent au-delà du plan multilingue de base (`U+F0335` pour
`mdi:lightbulb`), ils exigent donc une paire de substitution — d'où `Character.toChars`
et non un simple `Char`.

**Choix de l'icône**, dans `MdiIcons.iconName` : Home Assistant expose l'icône dans
l'attribut `icon` de l'entité, mais **seulement si elle a été personnalisée**. Pour
toutes les autres, l'interface web calcule un défaut côté navigateur à partir du domaine
et de la classe d'appareil. `MdiIcons` reproduit cette logique, y compris les variantes
selon l'état : `lightbulb` allumée contre `lightbulb-outline` éteinte, `door-open` contre
`door-closed`, etc. L'icône prend la couleur d'accentuation quand l'entité est active.

Pour régénérer la table après une mise à jour de MDI, le script se trouve dans
l'historique : il extrait les paires `.mdi-nom::before { content: "\Fxxxx" }` du CSS du
paquet `@mdi/font`.

Les icônes du panneau lui-même ont été écartées : `com.sznaner.bgmz9` en contient environ
724 dans ses `mipmap`, mais nommées en abréviations pinyin (`a86m_device_dj` pour les
lampes, `_kg` interrupteurs, `_cl` volets, `_cz` prises), chaque correspondance étant à
deviner, avec le style du constructeur, et ce sont ses ressources.
- [ ] Faire de l'application le lanceur par défaut, à la place de `l.l`
- [ ] Exploiter le reste du matériel : buzzer, sonnette câblée sur la borne `DB`, bus
      RS485, amplificateur audio
- [ ] Signature de release et build `assembleRelease` (actuellement en debug)
- [ ] Gérer le cas où Home Assistant redémarre : la reconnexion fonctionne, mais un
      message plus explicite à l'écran serait souhaitable


---

## 12. Réseau, Bluetooth et mise à jour

Relevés sur l'appareil le 2026-09-19.

### Interfaces

`eth0` (RJ45, liaison normale), `wlan0` (pilote `bcmdhd`), `lo`, `sit0`. Le panneau était
câblé en Ethernet, le Wi-Fi activé mais non associé. La détection d'Ethernet se fait en
lisant directement les interfaces : `ConnectivityManager` ne renseigne pas toujours
`TRANSPORT_ETHERNET` sur ces images Rockchip, alors que `eth0` porte bel et bien son
adresse.

> Android 8 ne rend les résultats de balayage Wi-Fi qu'avec `ACCESS_FINE_LOCATION`
> **et** la localisation activée dans le système. Faute de quoi la liste revient vide,
> sans erreur — un symptôme parfaitement trompeur. L'application détecte le cas et propose
> d'activer la localisation par `su`.

L'API employée (`WifiConfiguration`, `addNetwork`, `enableNetwork`) est dépréciée depuis
Android 10, mais son remplaçant `WifiNetworkSuggestion` n'existe qu'à partir de l'API 29 :
sur Android 8.1 c'est la seule voie, et elle fonctionne.

### Profils Bluetooth disponibles

`dumpsys package com.android.bluetooth` révèle **les deux rôles A2DP** :

| Service | Rôle |
|---|---|
| `a2dp.A2dpService` | émission — vers une enceinte ou un casque |
| `a2dpsink.A2dpSinkService` | **réception** — depuis un téléphone |
| `a2dpsink.mbs.A2dpMediaBrowserService` | métadonnées et commandes de lecture |
| `avrcpcontroller.AvrcpControllerService` | télécommande AVRCP |
| `hfpclient.HeadsetClientService` | mains libres |

La réception est donc possible, ce qui n'est pas le cas de tous les appareils Android.
`BluetoothProfile.A2DP_SINK` vaut 11 et reste masqué dans le SDK public : le mandataire
s'obtient par `getProfileProxy(context, listener, 11)`, et `connect`/`disconnect`
s'appellent par réflexion. Android 8.1 étant antérieur au filtrage des API masquées
(API 28), l'appel passe sans contournement.

### ✅ Une radio Zigbee Silicon Labs, sur `/dev/ttyS3`

**Correction d'une conclusion antérieure erronée.** Il avait d'abord été écrit que ce
panneau n'avait pas de Zigbee, au motif qu'aucun pilote `zigbee`, `cc2652`, `ezsp` ou
`efr32` n'apparaît au démarrage du noyau et qu'aucun nœud de l'arbre matériel n'y
correspond. **Le raisonnement était faux** : un coprocesseur Zigbee relié en UART n'a
besoin ni de pilote noyau dédié, ni de nœud spécifique. Il se pilote entièrement depuis
l'espace utilisateur, à travers un `/dev/ttyS*` ordinaire. Chercher un pilote était
chercher au mauvais endroit.

La preuve tient en deux lignes, obtenues directement depuis le shell :

```
$ printf 'À8¼~' > /dev/ttyS3     # trame ASH RST
$ xxd < /dev/ttyS3
00000000: 1ac1 020b 0a52 7e                        .....R~
```

`1A C0 38 BC 7E` est la trame **RST** du protocole ASH de Silicon Labs, et
`1A C1 02 0B 0A 52 7E` la réponse **RSTACK** correspondante :

| Octet | Sens |
|---|---|
| `1A` | CANCEL |
| `C1` | trame de type RSTACK |
| `02` | version 2 du protocole ASH |
| `0B` | code de réinitialisation : reset logiciel |
| `0A 52` | contrôle d'intégrité |
| `7E` | fin de trame |

C'est la signature d'un **NCP EmberZNet** (EFR32 ou EM35x). Le test d'usine du
constructeur fait exactement la même chose : son activité `SerialPortActivity`,
intitulée « 网关测试 » (test de passerelle), envoie `1ac038bc7e` — chaîne présente
dans son code — et affiche la réponse reçue, qui elle **ne figure nulle part dans
l'APK** : elle vient donc bien du matériel.

> `com.sznaner.gateway`, présente sur le panneau, ne contient en revanche **aucune pile
> Zigbee** : c'est une passerelle Tuya. Ne pas s'y fier pour conclure quoi que ce soit.

**Ce que cela ouvre.** Zigbee2MQTT prend en charge les adaptateurs EZSP (pilote `ember`),
tout comme l'intégration ZHA de Home Assistant. Le coprocesseur étant sur le panneau et
Zigbee2MQTT tournant sur le serveur, il faut un pont série vers le réseau — un petit
serveur TCP sur le panneau qui relaie `/dev/ttyS3`, côté serveur une adresse
`tcp://<ip-du-panneau>:<port>`. C'est peu de code et l'application est bien placée pour
l'héberger.

### Répartition des ports série

| Port | Rôle | Vérification |
|---|---|---|
| `/dev/ttyS0`, `/dev/ttyS1` | pile Bluetooth | propriétaire `bluetooth:net_bt` |
| `/dev/ttyS2` | **RS485** du bornier (`485 A` / `485 B`) | s'ouvre, reste muet — rien n'est câblé |
| `/dev/ttyS3` | **coprocesseur Zigbee** | répond à l'ASH RST |
| `/dev/ttyS5` | désactivé | erreur d'E/S à l'ouverture |

Le sens de transmission du RS485 se commande par `/proc/vendor/485_tx_mode`, qui vaut
`receive` ou `transmit`.

### Les quatre relais répondent

Testés par l'activité `RelayTestingActivity` du constructeur et vérifiés dans
`/proc/vendor/` : les quatre interrupteurs à l'écran correspondent, de gauche à droite, à
`relay_first`, `relay_second`, `relay_third`, `relay_forth`. Le pilotage fonctionne dans
les deux sens, l'état se relit. **Aucune borne de relais n'est exposée sur le bornier de
cet exemplaire**, ils ne commandent donc rien d'extérieur.

Ce qui reste ouvert côté matériel : trois contrôleurs USB hôte (`DWC OTG`, `EHCI`, `OHCI`)
et `CONFIG_USB_ACM=y` dans le noyau — une clé Zigbee en **CDC-ACM** (ConBee II,
zig-a-zig-ah!) serait donc reconnue. En revanche `CONFIG_USB_SERIAL_CP210X` et
`CONFIG_USB_SERIAL_FTDI_SIO` sont désactivés : les clés à base de CP210x ou FTDI, dont le
Sonoff ZBDongle-P, ne le seraient pas sans recompiler le noyau.

### Ports série

```
/dev/ttyS0  bluetooth:net_bt    pile Bluetooth
/dev/ttyS1  bluetooth:net_bt    pile Bluetooth
/dev/ttyS2  system:system       libre
/dev/ttyS3  system:system       libre
/dev/ttyS4  root:root
/dev/ttyS5  root:root
```

Aucun processus ne les tient ouverts. `ttyS2` et `ttyS3` restent les candidats pour le bus
RS485 du bornier.

### ADB par le réseau

`service.adb.tcp.port` valait 5555 mais **`persist.adb.tcp.port` était vide** : l'accès
réseau ne survivait donc pas à un redémarrage. Sur un panneau encastré, c'est le genre
d'oubli qui oblige à le démonter. Rendu permanent par :

```bash
adb shell su 0 setprop persist.adb.tcp.port 5555
```

ADB conserve son autorisation par clé RSA : seul un ordinateur déjà accepté peut se
connecter.

### Mise à jour intégrée

Voir `Updater.kt` et `UpdateFlow.kt`. Deux sources : publications GitHub
(`compte/depot`, via `api.github.com/repos/…/releases/latest`) ou fichier JSON à une URL
libre. L'installation passe par `su 0 pm install -r`, qui **conserve les préférences**,
donc le jeton Home Assistant ; à défaut de root, l'installateur système prend le relais
via un `FileProvider`, avec confirmation à l'écran.

Rien ne s'installe sans accord explicite : une application qui se remplace seule pendant
qu'on s'en sert serait déroutante, et une version défectueuse installée sans qu'on l'ait
voulu obligerait à démonter le panneau.
