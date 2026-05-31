# inCo2bate — Piattaforma IncuSense & Firmware BioSentry

Sistema IoT distribuito per il monitoraggio di incubatori a CO₂.

- **`IncuSense/`** — piattaforma: backend Spring Boot + frontend statico + Mosquitto (MQTT) + TimescaleDB, orchestrati con Docker Compose.
- **`Biosentry_Firmware/`** — firmware della sensing unit ESP32‑C3 (PlatformIO / Arduino), sensore CO₂ chemoresistivo Na:In₂O₃ a 250 °C.

Flusso dati: **Sensing unit → Wi‑Fi/Ethernet → broker MQTT → backend (ingest + calibrazione + alert + drift) → TimescaleDB → REST/WebSocket → dashboard web**.

---

## 1. Prerequisiti

| Strumento | Versione usata | Per |
|-----------|----------------|-----|
| Docker Desktop + Docker Compose | 29.x / v2 | Esecuzione dell'intera piattaforma |
| Java | 21 (solo se compili il backend fuori da Docker) | Backend |
| PlatformIO Core | 6.1.x | Build/flash firmware |
| (opzionale) Wokwi for VS Code | — | Simulazione firmware |

> **Nota Windows:** il BuildKit di Docker Desktop qui ha mostrato instabilità di trasporto. Compila con il **builder classico**:
> ```powershell
> $env:DOCKER_BUILDKIT=0 ; $env:COMPOSE_DOCKER_CLI_BUILD=0
> ```

---

## 2. Avvio rapido (piattaforma)

```powershell
cd IncuSense
$env:DOCKER_BUILDKIT=0 ; $env:COMPOSE_DOCKER_CLI_BUILD=0
docker compose up --build -d
```

Attendi che `docker compose ps` mostri `backend` e `timescaledb` come **healthy**, poi apri la dashboard:

| Servizio | URL / Porta | Note |
|----------|-------------|------|
| Frontend (Nginx) | http://localhost:3000 | esposto solo su `127.0.0.1` |
| Backend REST/WS | http://localhost:8080 | `/actuator/health` → `{"status":"UP"}` |
| TimescaleDB (PostgreSQL 16) | `localhost:5432` | database `incusense` |
| Mosquitto MQTT | `localhost:1883` (MQTT), `9001` (WebSocket) | accesso anonimo consentito |

Stop / reset:
```powershell
docker compose down            # ferma, mantiene i dati
docker compose down -v         # ferma e CANCELLA il volume del database
```

---

## 3. Come accedere per testare l'app  👤

> 🔒 **In questa versione del prototipo la registrazione di nuovi utenti è disattivata.** Si accede **solo** con gli account pre‑registrati qui sotto (creati automaticamente al primo avvio). La registrazione si potrà riabilitare in seguito impostando `REGISTRATION_ENABLED=true` (vedi §5).

Al **primo avvio** (database vuoto) il sistema crea automaticamente due account e i relativi laboratori.

### 3.1 Credenziali pronte all'uso

| Account | Username | Password | Lab | Cosa vede |
|---------|----------|----------|-----|-----------|
| **Operatore** 👉 *usa questo per il test* | `operator` | `operator123` | `lab_alpha` | **I dati della sensing unit di default** (il firmware pubblica su `lab_alpha`) |
| **Amministratore** | `admin` | `admin123` | `lab_system` | Un lab di sistema, **vuoto** (nessun dato del firmware) |

> ⚠️ Ogni utente vede **solo** i dati del proprio laboratorio (isolamento multi‑tenant). Il firmware di default pubblica su **`lab_alpha`**, quindi per vedere le misure **accedi come `operator`**. L'`admin` sta in `lab_system` e non vede quei dati.

### 3.2 Procedura per vedere subito i dati del firmware

1. Apri **http://localhost:3000**.
2. Accedi con **`operator` / `operator123`**.
3. Sei nella dashboard del lab `lab_alpha`: appena la sensing unit pubblica (o lanci un test), vedrai metriche, tabella misure, alert e stato di salute del sensore.

Per usare un **laboratorio tuo** con un device dedicato: riabilita la registrazione (`REGISTRATION_ENABLED=true`) e segui §6.2, **oppure** aggiungi un utente seed in `config/BootstrapConfig.java`.

### 3.3 Da dove vengono questi valori (riferimenti nel codice)

| Elemento | Valore | Origine |
|----------|--------|---------|
| Utente operatore (bootstrap) | `operator` / `operator123`, ruolo `LAB_USER`, lab `lab_alpha` | `config/BootstrapConfig.java` (solo se `users` è vuota). Override: `INCUSENSE_OPERATOR_USERNAME` / `INCUSENSE_OPERATOR_PASSWORD`. |
| Utente admin (bootstrap) | `admin` / `admin123`, ruolo `ADMIN`, lab `lab_system` | `config/BootstrapConfig.java`. Override: `INCUSENSE_ADMIN_USERNAME` / `INCUSENSE_ADMIN_PASSWORD`. |
| Lab di default / firmware | slug **`lab_alpha`** | seed in Flyway `V2__multitenant.sql`. |
| Lab di sistema (admin) | slug **`lab_system`** | creato da `BootstrapConfig`. |
| Registrazione self‑service | **disattivata** (`POST /api/auth/register` → 403) | flag `incusense.security.registration-enabled` (default `false`). |

---

## 4. Identificatori built‑in del firmware & contratto MQTT

### 4.1 Identità del firmware (compile‑time, in `Biosentry_Firmware/src/main.cpp`)

| Macro | Default | Sovrascrivibile |
|-------|---------|-----------------|
| `MQTT_LAB_ID` | `lab_alpha` | **Sì** — build flag `-D MQTT_LAB_ID=\"lab_xxxxxxxx\"` |
| `MQTT_INC_ID` | `inc_01` | modifica sorgente |
| `MQTT_NODE_ID` | `node_a` | modifica sorgente |
| `MQTT_HUB_ID` (derivato) | `lab_alpha_inc_01_node_a` | = `{MQTT_LAB_ID}_{MQTT_INC_ID}_{MQTT_NODE_ID}` |
| `MQTT_DEVICE_ID` | `lab_alpha_inc_01_node_a-co2` | — |
| `MQTT_BROKER_HOST` | `192.168.1.100` | build flag `-D MQTT_BROKER_HOST=\"...\"` |
| `MQTT_BROKER_PORT` | `1883` | — |

### 4.2 Topic MQTT (gerarchici per tenant)

```
incusense/labs/{labId}/hubs/{hubKey}/telemetry   (device → piattaforma)
incusense/labs/{labId}/hubs/{hubKey}/status      (device → piattaforma, incl. LWT "offline")
incusense/labs/{labId}/hubs/{hubKey}/commands    (piattaforma → device: ZERO_CAL / OFFSET_CAL)
incusense/labs/{labId}/hubs/{hubKey}/ack         (device → piattaforma)
```

Il backend sottoscrive `incusense/labs/+/hubs/+/telemetry` e `.../status`.
La telemetria con un `labId` non presente nella tabella `labs` viene **scartata** (anti‑spoofing).

### 4.3 Payload JSON di telemetria (esatto, da `publishTelemetry()`)
```json
{ "ts": 1700000000, "co2_ppm": 50000.0, "heater_temp": 250.14,
  "env_temp": 37.25, "env_hum": 95.30, "rail_12v": 12.05,
  "raw_adc": 1800, "sensor_response": 0.1810 }
```
`raw_adc` e `sensor_response` alimentano il motore di monitoraggio del drift. I nomi dei campi non vanno cambiati (il backend li mappa via `@JsonProperty`).

---

## 5. Configurazione (variabili d'ambiente)

Impostate sul servizio `backend` in `IncuSense/docker-compose.yml` (default mostrati).

| Variabile | Default | Scopo |
|-----------|---------|-------|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://timescaledb:5432/incusense` | Connessione DB |
| `SPRING_DATASOURCE_USERNAME` / `PASSWORD` | `incusense_user` / `incusense_secret` | Credenziali DB (anche sul servizio `timescaledb`) |
| `MQTT_BROKER_URL` | `tcp://mosquitto:1883` | Broker |
| `MQTT_CLIENT_ID` | `incusense-backend-01` | Client id MQTT |
| `MQTT_TOPIC` | `incusense/labs/+/hubs/+/telemetry` | Sottoscrizione telemetria |
| `MQTT_STATUS_TOPIC` | `incusense/labs/+/hubs/+/status` | Sottoscrizione status |
| `JWT_SECRET` | `incusense-dev-secret-key-change-in-prod-min-256bit` | **Cambiare in produzione** |
| `JWT_EXPIRATION_MS` | `86400000` (24 h) | Durata token |
| `INCUSENSE_ADMIN_USERNAME` / `INCUSENSE_ADMIN_PASSWORD` | `admin` / `admin123` | Admin di bootstrap (**cambiare in produzione**) |
| `INCUSENSE_OPERATOR_USERNAME` / `INCUSENSE_OPERATOR_PASSWORD` | `operator` / `operator123` | Operatore di bootstrap (lab `lab_alpha`) |
| `REGISTRATION_ENABLED` | `false` | Registrazione self‑service; `true` per riattivare `POST /api/auth/register` |
| `SMTP_HOST` | *(vuoto)* | Email di alert; **vuoto = email disattivate** (gli alert vengono comunque salvati e loggati) |
| `SMTP_PORT` / `SMTP_USERNAME` / `SMTP_PASSWORD` | `587` / vuoto / vuoto | Autenticazione SMTP |
| `ALERT_FROM` | `incusense@localhost` | Indirizzo mittente |
| `ALERT_COOLDOWN` | `300` | Finestra anti‑ripetizione alert (s) |
| `DRIFT_EOL_DAYS` | `7` | Giorni‑a‑fine‑vita che fanno scattare un alert `DEGRADING` |

---

## 6. Firmware (BioSentry)

### 6.1 Ambienti di build PlatformIO (`Biosentry_Firmware/platformio.ini`)

| Ambiente | Flag | Connettività | Uso |
|----------|------|--------------|-----|
| `esp32-c3-devkitm-1` | *(nessuna)* | **Ethernet W5500 reale** | Hardware di produzione |
| `esp32-c3-devkitm-1-sim` | `WOKWI_SIM` | Mock Ethernet (nessun traffico reale) | Test logica su Wokwi |
| `esp32-c3-devkitm-1-wifi-sim` | `WOKWI_WIFI_SIM` | **Wi‑Fi reale** via Wokwi (`Wokwi-GUEST`, aperta) | Test MQTT end‑to‑end su Wokwi |

```bash
cd Biosentry_Firmware
pio run -e esp32-c3-devkitm-1-wifi-sim                 # build
pio run -e esp32-c3-devkitm-1-wifi-sim -t upload       # build + flash
```

`wokwi.toml` punta ai binari della build `-wifi-sim`. La build Wi‑Fi si connette all'SSID **`Wokwi-GUEST`** (password vuota) e sincronizza l'orologio via NTP.

### 6.2 Provisioning di un device verso un laboratorio

> Di default la sensing unit usa `lab_alpha` (visibile con l'account `operator`). Per puntarla a un **altro** lab, prima procurati lo slug di quel lab: riattiva la registrazione (`REGISTRATION_ENABLED=true`) e crea/recupera il lab via `GET /api/lab`, oppure aggiungi un utente seed in `BootstrapConfig`.

1. Ottieni l'**id lab** di destinazione (es. `lab_3f9a1c2e`).
2. Compila/flasha con quello slug:
   ```bash
   # aggiungi a platformio.ini build_flags, oppure passa via PLATFORMIO_BUILD_FLAGS:
   #   -D MQTT_LAB_ID=\"lab_3f9a1c2e\"
   pio run -e esp32-c3-devkitm-1-wifi-sim
   ```
3. Il device pubblicherà su `incusense/labs/lab_3f9a1c2e/hubs/lab_3f9a1c2e_inc_01_node_a/telemetry`, e gli utenti di quel lab vedranno i dati.

### 6.3 Collegare la simulazione Wokwi alla piattaforma locale

Nella build `-wifi-sim` il broker MQTT è impostato su **`host.wokwi.internal`**: è l'hostname speciale con cui la simulazione Wokwi raggiunge **il tuo PC** (dove gira Mosquitto in Docker, porta `1883`). Funziona con l'**estensione Wokwi for VS Code** (la simulazione web su wokwi.com non può raggiungere il tuo `localhost`).

Procedura:
1. Avvia la piattaforma: `cd IncuSense ; docker compose up -d` (verifica `mosquitto` attivo con `docker compose ps`).
2. Compila il firmware: `cd Biosentry_Firmware ; pio run -e esp32-c3-devkitm-1-wifi-sim`.
3. Avvia il simulatore (VS Code: `F1` → **Wokwi: Start Simulator**).
4. Nel monitor seriale dovresti vedere, nell'ordine:
   ```
   [NET] Wi-Fi connected. IP=10.x.x.x broker=host.wokwi.internal:1883
   [MQTT] Connected to host.wokwi.internal:1883. Sub commands: OK
   [MQTT] Published telemetry -> incusense/labs/lab_alpha/hubs/lab_alpha_inc_01_node_a/telemetry: {...}
   ```
5. Accedi alla dashboard come **`operator` / `operator123`**: vedrai metriche, tabella misure e stato di salute aggiornarsi in tempo reale.

> **Se vedi `[MQTT] Connect failed. state=-2`** (TCP fallito): il broker non è raggiungibile. Controlla che (a) `docker compose ps` mostri `mosquitto` su, (b) la build usi `host.wokwi.internal` (ricompila dopo aver modificato `platformio.ini`), (c) stai usando l'estensione Wokwi di VS Code. Finché è offline, la unit accumula i dati in un backlog locale (`[BACKLOG] appended…`) e li invia tutti alla prima connessione riuscita.
>
> **Nota utile per il test email:** all'avvio la CO₂ simulata parte sotto i 30000 ppm, quindi i primi campioni generano un alert **WARN `CO2_RANGE`** → ottimo per innescare l'email (vedi §8.3).

---

## 7. API REST (tutte sotto `/api`, JWT Bearer salvo dove indicato)

| Metodo & path | Auth | Descrizione |
|---------------|------|-------------|
| `POST /api/auth/register` | pubblico | **Disabilitato (→ 403)** salvo `REGISTRATION_ENABLED=true`. Crea utente + lab (o unisci a `existingLabId`) |
| `POST /api/auth/login` | pubblico | Ritorna token + `labId` |
| `GET /api/lab` | sì | Lab dell'utente (slug + nome) |
| `GET /api/hubs` | sì | Hub del proprio lab |
| `GET /api/measurements/latest?limit=` | sì | Ultime misure (per lab) |
| `GET /api/hubs/{hubKey}/measurements?limit=` | sì | Storico per hub (403 se l'hub non è nel tuo lab) |
| `GET /api/measurements/history` | sì | Aggregati giornalieri su 6 mesi (`time_bucket` TimescaleDB) |
| `GET /api/alerts` | sì | Alert recenti (per lab) |
| `GET/POST /api/calibration/{hubKey}` | sì | Offset di calibrazione |
| `GET /api/notification-contacts` + `POST` / `PUT {id}` / `DELETE {id}` | sì | Contatti email per gli alert (per lab) |
| `GET /api/hubs/{hubKey}/health` | sì | Stato drift/salute del sensore |
| `GET /api/drift-curves` + `POST` | sì | Curve di riferimento del drift |
| `POST /api/hubs/{hubKey}/health/assign-curve` | sì | Assegna una curva + baseline d'installazione |
| WebSocket `/ws` (STOMP/SockJS) | handshake pubblico | Topic: `/topic/measurements/{labId}`, `/topic/alerts/{labId}`, `/topic/health/{labId}` |

---

## 8. Notifiche email (alert) — setup SMTP

Gli alert (soglie superate o drift del sensore) possono essere inviati via email ai **contatti** registrati nel lab. Soglie in `service/AlertService.java`:

| ruleKey | Condizione | Livello |
|---------|------------|---------|
| `CO2_RANGE` | co2_ppm `< 30000` o `> 70000` | WARN |
| `ENV_TEMP` | env_temp `< 35.5` o `> 38.5` | WARN |
| `ENV_HUM` | env_hum `< 80` o `> 99.5` | INFO |
| `RAIL_12V` | rail_12v `< 11.5` o `> 12.6` | CRITICAL |
| `SENSOR_DRIFT` | predittivo (drift ≥ 20 % o EOL previsto ≤ `DRIFT_EOL_DAYS`) | WARN / CRITICAL |

Cooldown per `(hub, ruleKey)`: `ALERT_COOLDOWN` secondi (default 300); gli alert di drift usano 24 h.

### 8.1 Consegna REALE con Gmail + App Password (modalità predefinita)

Il file **`IncuSense/.env`** è già la sede della configurazione email (un modello è in `.env.example`; Compose lo legge in automatico, ed è escluso da Git). Procedura completa:

1. **Genera una App Password Google** (NON la password normale dell'account):
   - attiva la **verifica in due passaggi** su https://myaccount.google.com/security
   - vai su https://myaccount.google.com/apppasswords
   - quando ti chiede il **nome dell'app**, scrivi un'etichetta qualsiasi a tua scelta (es. **`IncuSense`**) — è solo un promemoria, non ha effetti tecnici
   - ottieni **16 caratteri** (es. `abcd efgh ijkl mnop`)

2. **Compila `IncuSense/.env`** (incolla la password **senza spazi**):
   ```
   SMTP_HOST=smtp.gmail.com
   SMTP_PORT=587
   SMTP_USERNAME=tuo.indirizzo@gmail.com
   SMTP_PASSWORD=abcdefghijklmnop
   ALERT_FROM=tuo.indirizzo@gmail.com
   ```

3. **Avvia in modalità Gmail** (senza l'override Mailpit) — Compose rilegge `.env`:
   ```powershell
   cd IncuSense
   docker compose up -d backend
   ```

4. **Aggiungi il destinatario:** apri http://localhost:3000 → login **`operator` / `operator123`** → pagina **Contacts** (`/contacts.html`) → aggiungi la **tua email**, *Min level* = WARN, *Enabled* → Add. (Può essere lo stesso indirizzo del mittente: invii a te stesso.)

5. **Prova subito** generando un alert (telemetria con `rail_12v` fuori soglia, su un hub nuovo per evitare il cooldown):
   ```powershell
   $ts=[DateTimeOffset]::UtcNow.ToUnixTimeSeconds(); $hub="lab_alpha_test_$ts"
   '{"ts":'+$ts+',"co2_ppm":50000,"heater_temp":250,"env_temp":37,"env_hum":95,"rail_12v":10.9,"raw_adc":1800,"sensor_response":0.18}' | Set-Content -NoNewline "$env:TEMP\p.json" -Encoding ascii
   docker cp "$env:TEMP\p.json" incusense-mqtt:/tmp/p.json
   docker exec incusense-mqtt mosquitto_pub -h localhost -t "incusense/labs/lab_alpha/hubs/$hub/telemetry" -f /tmp/p.json
   ```
   → entro pochi secondi ricevi l'email con oggetto `[IncuSense][CRITICAL] lab_alpha_test_...` (controlla anche **Spam** la prima volta).

6. **Diagnostica invio:**
   ```powershell
   docker compose logs backend | Select-String "ALERT-EMAIL"
   ```
   - `[ALERT-EMAIL] sent to …` → inviata ✅
   - `… Username and Password not accepted` → App Password errata/mancante
   - `(disabled, no SMTP configured)` → `SMTP_PASSWORD` vuota o backend non riavviato dopo l'edit

> Se `SMTP_HOST` è vuoto, l'invio email è semplicemente disattivato (gli alert restano comunque salvati nel DB e visibili in dashboard).

### 8.2 (Opzionale) Test EMAIL locale senza casella reale con Mailpit

Per **verificare la pipeline** senza inviare a una casella vera, è incluso **Mailpit** (cattura‑email con interfaccia web):

```powershell
cd IncuSense
$env:DOCKER_BUILDKIT=0
docker compose -f docker-compose.yml -f docker-compose.mailtest.yml up -d
```

- Interfaccia web delle email catturate: **http://localhost:8025**
- Verifica automatica (login operator, crea contatto, genera alert CRITICAL, controlla la cattura):
  ```powershell
  powershell -ExecutionPolicy Bypass -File .\scripts\email_test.ps1
  ```
  Esito atteso: **`[PASS] email recapitata`** con oggetto `[IncuSense][CRITICAL] ...`.

Per tornare alla consegna reale via Gmail: `docker compose up -d` **senza** l'override (`-f docker-compose.mailtest.yml`).

### 8.3 Come testare l'invio di un alert via email (a piattaforma avviata)

**Precondizioni:**
- piattaforma avviata in modalità Gmail (§8.1) con `.env` compilato e `SMTP_PASSWORD` valida;
- almeno un **contatto** in `/contacts.html` (accedi come `operator`) con la tua email e *Min level* ≤ al livello dell'alert (INFO copre tutto);
- ricorda il **cooldown**: lo stesso alert sullo stesso hub non si ripete entro `ALERT_COOLDOWN` (default 5 min) → per riprovare subito usa un **hub nuovo**.

**Metodo A — comando manuale (rapido, senza firmware).** Forza un alert CRITICAL `RAIL_12V` pubblicando una telemetria "di guasto" su un hub nuovo del lab `lab_alpha`:
```powershell
$ts=[DateTimeOffset]::UtcNow.ToUnixTimeSeconds(); $hub="lab_alpha_test_$ts"
'{"ts":'+$ts+',"co2_ppm":50000,"heater_temp":250,"env_temp":37,"env_hum":95,"rail_12v":10.9,"raw_adc":1800,"sensor_response":0.18}' | Set-Content -NoNewline "$env:TEMP\p.json" -Encoding ascii
docker cp "$env:TEMP\p.json" incusense-mqtt:/tmp/p.json
docker exec incusense-mqtt mosquitto_pub -h localhost -t "incusense/labs/lab_alpha/hubs/$hub/telemetry" -f /tmp/p.json
```

**Metodo B — dal firmware in Wokwi.** Avvia la simulazione (§6.3): i primi campioni hanno CO₂ < 30000 ppm → scatta un alert **WARN `CO2_RANGE`** che genera l'email (purché il contatto abbia *Min level* INFO o WARN).

**Verifica dell'esito** (nei log del backend):
```powershell
docker compose logs backend | Select-String "ALERT-EMAIL"
```
- `[ALERT-EMAIL] sent to <tua-mail> …` → inviata ✅ (controlla inbox, e **Spam** la prima volta)
- `… Username and Password not accepted` → App Password errata/mancante in `.env`
- `(disabled, no SMTP configured)` → `SMTP_PASSWORD` vuota o backend non riavviato dopo l'edit

---

## 9. Database

- **TimescaleDB** (PostgreSQL 16). Lo schema è gestito da **Flyway** (`backend/src/main/resources/db/migration/`); il backend gira con `spring.jpa.hibernate.ddl-auto: validate` — le entità devono combaciare esattamente con lo schema.
  - `V1__init_schema.sql` — `sensing_hubs`, `measurements` (hypertable).
  - `V2__multitenant.sql` — `labs`, `users`, `sensing_hubs.lab_id`, seed di `lab_alpha`.
  - `V3__alerting.sql` — `notification_contacts`, `alerts`.
  - `V4__drift.sql` — `measurements.raw_adc/sensor_response`, `drift_reference_curves`, `sensor_health`, seed della curva Na:In₂O₃ (`sensor_type = NaIn2O3_CO2`, coefficienti `a/b` null finché non carichi un dataset reale).
- Ispezione:
  ```powershell
  docker exec incusense-db psql -U incusense_user -d incusense -c "SELECT lab_id, display_name FROM labs;"
  docker exec incusense-db psql -U incusense_user -d incusense -c "SELECT count(*) FROM measurements;"
  ```

---

## 10. Test automatici

Test end‑to‑end che replica il publish MQTT esatto del firmware e verifica ingest → metriche → DB → alert → drift → isolamento tenant. Usa gli account pre‑registrati (`operator` per i dati di `lab_alpha`, `admin` per la verifica di isolamento) e controlla anche che la registrazione self‑service sia disabilitata (→ 403):

```powershell
cd IncuSense
docker compose up -d
# Gira sulla rete Docker (aggira il port proxy instabile dell'host):
powershell -ExecutionPolicy Bypass -File .\scripts\e2e_test_net.ps1
```
Esito atteso: **12 passed, 0 failed**.

Verifica build firmware:
```bash
cd Biosentry_Firmware && pio run -e esp32-c3-devkitm-1-wifi-sim
```

---

## 11. Checklist hardening per la produzione

- [ ] Cambiare `JWT_SECRET` (≥ 256 bit casuali) e `INCUSENSE_ADMIN_PASSWORD`.
- [ ] Cambiare le credenziali DB (`SPRING_DATASOURCE_*` + env del servizio `timescaledb`).
- [ ] Mettere in sicurezza Mosquitto (`allow_anonymous false`, auth/TLS) — ora anonimo per sviluppo.
- [ ] Configurare un `SMTP_*` reale per gli alert via email.
- [ ] Restringere il CORS (`SecurityConfig` ora consente tutti gli origin).
- [ ] Servire frontend/backend dietro TLS.
- [ ] Non committare il file `.env` (contiene la password SMTP).

---

## 12. Troubleshooting

- **Backend bloccato “unhealthy” / `/actuator/health` non risponde** — accadeva solo con SMTP mal configurato; l'health indicator della mail è disattivato (`management.health.mail.enabled: false`), quindi un `SMTP_HOST` vuoto va bene (l'email semplicemente non viene inviata).
- **`docker compose build` fallisce con BuildKit `rpc error … EOF`** — usa il builder classico (`$env:DOCKER_BUILDKIT=0`).
- **Docker Desktop `input/output error` / corruzione containerd** — resetta il distro dati: ferma Docker, `wsl --shutdown`, `wsl --unregister docker-desktop`, riavvia Docker Desktop (gli altri distro WSL restano intatti; le immagini su disco dati sopravvivono).
- **POST su `localhost:8080` dall'host che vanno in timeout/abort a intermittenza** — instabilità del port‑proxy di Docker Desktop; usa `scripts/e2e_test_net.ps1` che parla col backend sulla rete dei container.
- **Loggato come admin ma non vedo dati** — l'admin è in `lab_system`; il firmware di default pubblica su `lab_alpha`. Vedi §3.
- **Non ricevo le email** — verifica: (a) `SMTP_*` impostati nel `.env` e backend riavviato; (b) per Gmail stai usando una **App Password**, non la password dell'account; (c) hai aggiunto un contatto in `/contacts.html` con `min level` ≤ al livello dell'alert; (d) il cooldown (`ALERT_COOLDOWN`, default 5 min) non sta sopprimendo ripetizioni dello stesso alert.
