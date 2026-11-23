# Protocollo Insta360 Luna Ultra

Appunti presi da `apatassini/Insta360luna` (MIT) e `RigacciOrg/insta360-wifi-api` (GPLv3),
confrontati con le misure sul campo. I numeri di campo e di comando sono riusati come tali;
il
codice dell'app e' scritto qui.

## Trasporto

TCP `192.168.42.1:6666`. Framing **UCD2**, payload **protobuf** (`insta360.messages`).

```
 0   4   'U' 'C' 'D' '2'      magic
 4   1   0x01                 versione
 5   1   0x0c                 flag
 6   1   tipo                 0x01 media, 0x04 comando/risposta, 0x05 stream
 7   1   seq                  contatore a 8 bit
 8   4   len (LE)             lunghezza del corpo
12   len corpo
     4   trailer (LE)         checksum su magic..corpo
```

### Checksum

Variante non standard di CRC-32: polinomio `0x04C11DB7`, init `−1`, e per ogni byte XOR con il
byte **basso** seguito da **quattro** round di tabella sul byte alto. Non e' un CRC-32
standard e non va "corretto": la camera scarta i frame il cui trailer non corrisponde.

### Apertura sessione

Non c'e' un comando di login. Si autorizza inviando un frame di **tipo stream** (`0x05`) a
lunghezza zero con il token costante `f6 cc 4f 09` al posto del trailer, e lo si ripete ogni
**3 s** come keep-alive. La sessione si stabilisce ~1500 ms dopo il primo frame.

Se la connessione cade entro 5000 ms dall'apertura, il motivo e' quasi sempre che la camera
ha **una sola sessione di controllo**: l'app Insta360 ufficiale va chiusa del tutto.

### Comandi

Il corpo di un frame di comando si apre con 9 byte:

```
 0   2   code (LE)
 2   1   direzione            0x02 richiesta, 0x03 risposta
 3   2   requestId (LE)       e' questo che correla richiesta e risposta, non il seq
 5   4   0x00008000 (LE)      costante
 9   ..  messaggio protobuf
```

Tre comportamenti da non dare per scontati:

- la risposta **non** rimanda il codice del comando: porta sempre `200`;
- la camera **non** manda messaggi `Error`: a un codice inesistente, a un payload spazzatura e
  a un comando reale senza argomenti risponde allo stesso modo, `200` con corpo vuoto;
- `TAKE_PICTURE` risponde quando **accetta** lo scatto, non quando il file e' scritto. Il
  verdetto arriva per notifica (8208 con stato occupato, oppure 8201 con un codice di errore).

### Codici

| codice | comando |
|---|---|
| 3 | `TAKE_PICTURE` |
| 4 / 5 | `START_CAPTURE` / `STOP_CAPTURE` |
| 7 / 8 | `SET_OPTIONS` / `GET_OPTIONS` |
| 9 | `SET_PHOTOGRAPHY_OPTIONS` |
| 11 | `GET_FILE_EXTRA` |
| 12 | `DELETE_FILES` |
| 13 | `GET_FILE_LIST` |
| 15 | `GET_CURRENT_CAPTURE_STATUS` |
| 16 | `SET_FILE_EXTRA` (no-op silenzioso su questo firmware) |
| 17 / 18 | `GET` / `SET_TIMELAPSE_OPTIONS` |
| 22 / 23 | `START` / `STOP_TIMELAPSE` |
| 30 | `GET_MINI_THUMBNAIL` (non implementato) |
| `0x00E2` | `GIMBAL_CONTROL` |

Non si sono mai verificati come comandi validi: 6, 12 (nel senso di "non usare"), 24, 32, 33,
34, 40, 56, 57, 85, 112.

### Notifiche

| codice | contenuto |
|---|---|
| 8195 | batteria (campo 2 livello, campo 3 scala) |
| 8198 / 8199 | storage |
| 8201 | cattura interrotta: 1 scheda piena, 2 occupata, 4 scheda lenta, 7 batteria, 9 temperatura |
| 8208 | stato di cattura: campo 1 stato, 2 tempo, 3 numero di scatti |
| 8220 | esposizione |
| `0x206A` | velocita' gimbal |
| 8302 | PTZ (non confermato) |

Stati di cattura: `4` = singolo scatto in corso, `15` = panoramica in corso, `0` = libera.

### Gimbal

`GIMBAL_CONTROL` (`0x00E2`), campo 1 = azione, campo 2 = messaggio annidato con gli assi in
zig-zag:

- `08 01 12 <len> <axes>` = `MOVE`; `12 00` (assi vuoti) = **stop**, inviato 4 volte a 25 ms;
- campo annidato 1 = `device_x` = **verticale**, campo 2 = `device_y` = **orizzontale**,
  range −100…100;
- azioni senza assi: 2 = torna al centro, 3 = toggle selfie.

## HTTP

- File: `http://192.168.42.1<path>`, ad esempio
  `/storage_internal/DCIM/Camera01/IMG_…jpg`.
- Anteprima dal vivo: `POST /osc/commands/execute` con `{"name":"camera.getLivePreview"}`,
  flusso MJPEG da leggere come sequenza `FF D8 … FF D9`.
- `camera.listFiles` va chiamato con l'header `X-XSRF-Protected: 1`.

## Wi-Fi

SSID con prefisso `Luna Ultra`, sottorete `192.168.42.`, gateway `192.168.42.1`. La rete non ha
Internet: i socket vanno bindati sulla `Network` Wi-Fi e la rete va tenuta registrata con una
`NetworkRequest`, altrimenti Android la abbandona appena l'app esce in primo piano.
