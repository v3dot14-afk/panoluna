# PanoLuna

App Android che fa da **Panoshooter** per la **Insta360 Luna Ultra**: spara 24 fotogrammi
allineati sul gimbal e li scarica sul telefono, pronti per essere cuciti in PTGui e pubblicati
con pano2vr.

```
android/app            sorgenti, risorse e manifest
android/build.sh       compila l'APK senza Gradle né Android SDK
android/test.sh        verifica i pezzi di logica pura (codec, piano, export)
templates/             i template pronti da copiare in PTGui e pano2vr
dist/                  l'APK firmato e il lavoro di esempio a 24 scatti
```

## Cosa fa

**Schermata 1 — connessione.** Cerca la rete Wi-Fi della camera (`Luna Ultra*`), chiede la
password, la ricorda. In Impostazioni c'è il pulsante che **elimina rete e password ricordate**.
L'indirizzo della camera è `192.168.42.1` (porta `6666`), modificabile.

**Schermata 2 — il mirino.** Anteprima dal vivo dalla camera, levetta circolare per il gimbal,
batteria e stato. Al centro i quattro comandi della panoramica — **tondi, con una sola
pictogramma, senza scritte**: avvia, pausa, riprendi, stop. In basso la barra con le quattro
sezioni: panoramica, fotocamera, galleria, impostazioni.

## Compilare

Non serve Android Studio: non c'è un Android SDK raggiungibile, e la catena è la stessa che usa
Gradle, messa in fila a mano.

```bash
bash android/build.sh      # produce ../dist/PanoLuna.apk
bash android/test.sh       # verifica codec UCD2, protobuf, piano, export
```

Gli strumenti in `android/tools/` arrivano da npm e PyPI:

| strumento | fonte |
|---|---|
| `aapt2` | npm `aaptjs3` (binario nativo Linux) |
| `android.jar` | npm `@drxiaozhi/minapk` (stub della piattaforma) |
| `d8.jar`, `apksigner.jar`, `ecj-3.45.0.jar` | npm `@drxiaozhi/minapk` |
| JDK 17 | pypi `jdk4py` |

`build.sh` li scarica al primo avvio se mancano.

## Protocollo

Controllo su `192.168.42.1:6666`, framing **UCD2**, payload **protobuf**
(`insta360.messages`). Le scelte non ovvie:

- **La sessione non si apre con un comando.** Si autorizza con un frame di tipo stream a
  lunghezza zero e token `f6 cc 4f 09`, ripetuto ogni 3 s come keep-alive.
- **Il trailer non è un CRC-32 standard.** Polinomio `0x04C11DB7`, init `−1`, per ogni byte XOR
  con il byte basso e quattro round di tabella sul byte alto. Non "correggerlo".
- **Risposta ≠ comando.** La camera risponde sempre `200`: a correlare richiesta e risposta è
  il `requestId`, non il codice né il seq.
- **Una sola connessione di controllo.** Se l'app Insta360 ufficiale è attiva, è lei ad avere la
  sessione: chiuderla completamente.
- **`TAKE_PICTURE` risponde quando accetta, non quando scrive la foto.** Per questo il fluspo
  degli scatti aspetta che lo stato torni libero prima di passare al punto successivo.

I riferimenti usati sono nel file `docs/PROTOCOLLO.md`.

## Il flusso di scatto

Per ogni punto del piano: `driveTo` → 350 ms di assestamento → `takePicture` → attesa che lo
stato di scatto torni libero → attesa configurabile (default 6 s). Le file partono dal basso e
il senso si alterna, così il gimbal fa sempre un passo invece di tornare a capo.

Il piano predefinito è **4 file × 6 colonne = 24 scatti**, tilt −52°…+52°, sovrapposizione 30%.

## Template di stitch

`PanoActivity` esporta il lavoro completo in una cartella:

| file | serve a |
|---|---|
| `*.pts` | **PTGui** — progetto con yaw/pitch già impostati per indice, lente rettilinea `f0`, campo orizzontale misurato 77,7°, uscita 2:1 |
| `*.papywizard.xml` | **PTGui** (File → Importa Papywizard) e **pano2vr Pro** |
| `*.p2vr` | **pano2vr** — progetto già puntato all'equirettangolare, tile 512 px multirisoluzione |
| `*.csv` | la tabella degli angoli, per chi la vuole in Excel |

Le quattro griglie (12, 18, 24, 36 scatti) sono già generate in `templates/`.

### Installarli

- **PTGui**: copiare i `.pts` in
  `%APPDATA%\PTGui\Templates\` (Windows), `~/Library/Application Support/PTGui/` (macOS),
  `$HOME/.PTGui/` (Linux). Poi *File → Applica template*.
- **pano2vr**: il `.p2vr` si apre direttamente; il Papywizard XML si importa da
  *File → Importa Papywizard* (versione Pro).

### Nota sulla risoluzione

Il template punta a un'uscita di **27654 × 13827** (2:1), il massimo misurato con 24 scatti a
1x. PTGui limita il JPEG a **25000 px per lato**: se *Create Panorama* rifiuta, ridurre a
24000 × 12000 o cambiare il formato in TIFF. In alternativa *Set optimum size* ricalcola la
dimensione esatta da questo progetto.

## Numeri misurati sulla Luna Ultra

| grandezza | valore |
|---|---|
| campo orizzontale 1x (4:3) | 77,07–77,67° (catalogo 81,74°) |
| campo verticale corrispondente | ~62,3° |
| fuoco equivalente 35 mm | ~21,5 mm |
| offset dello zero del tilt | +6,9° |
| fattore angolare | ×1,235 |
| JPEG 37 MP | ~7040 × 5288 |
| corsa pan | −57° … +235° |

Il pan non è osservabile dalla camera (non c'è un feedback di posizione): il planner lo
**ricostruisce** integrando la velocità nel tempo, e l'app lo corregge con il fattore misurato.
Il tilt ha invece un offset e un fattore noti.
