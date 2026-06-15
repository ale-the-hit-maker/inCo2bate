# IncuSense — Piattaforma di monitoraggio incubatori CO₂

IncuSense è la piattaforma a microservizi che ingerisce, elabora, archivia e
visualizza la telemetria dei nodi IoT **BioSentry** (MCU ESP32) montati sugli
incubatori da laboratorio. Misura CO₂, temperatura/umidità ambiente, temperatura
dell'heater e tensione di rail; genera alert con notifica email; e monitora il
**drift dei sensori** per la manutenzione predittiva.

> Firmware del nodo: repository **Biosentry_Firmware** (separato).
> Contratto dati firmware ↔ piattaforma e roadmap auto-calibrazione: vedi cartella `docs/`.

---

## Architettura

```
 BioSentry (ESP32)                IncuSense (Docker Compose)
 ┌───────────────┐   MQTT    ┌──────────────────────────────────────────┐
 │ sensori CO₂   │  telemetry│  Mosquitto ──▶ Backend Spring Boot        │
 │ heater PID    ├──────────▶│                 │  ingest → calibrazione   │
 │ SHT41 T/RH    │  1883     │                 │  alert + email           │
 │ backlog LittleFS          │                 │  drift monitoring        │
 └───────────────┘           │                 ▼                          │
                             │           TimescaleDB (hypertable)         │
                             │                 │  REST /api + WebSocket    │
                             │                 ▼                          │
                             │            Frontend (Nginx) :3000          │
                             └──────────────────────────────────────────┘
```

Servizi (`docker-compose.yml`):

| Servizio | Immagine | Porta host | Ruolo |
|---|---|---|---|
| `timescaledb` | timescale/timescaledb pg16 | 5432 | Storage serie temporali |
| `mosquitto` | eclipse-mosquitto 2.0 | 1883 / 9001 | Broker MQTT |
| `backend` | build `./backend` (Spring Boot, Java 21) | 8080 | Ingestione, API, alert, drift |
| `frontend` | nginx:alpine | **3000** | Unica UI web |

> **Nota:** dalla versione corrente esiste **un solo frontend**, servito su
> `http://localhost:3000`. Il vecchio frontend e la porta 3001 sono stati rimossi.

---

## Prerequisiti

- Docker Desktop (con Docker Compose v2)
- Porte libere su localhost: 3000, 8080, 1883, 5432

---

## Avvio rapido

Da PowerShell nella cartella `IncuSense`:

```powershell
docker compose up --build -d
```

Poi apri **http://localhost:3000**.

Credenziali di default (configurabili via variabili d'ambiente):

| Utente | Password | Lab | Note |
|---|---|---|---|
| `operator` | `operator123` | `lab_alpha` | Utente di laboratorio (lab del firmware di default) |
| `admin` | `admin123` | `lab_system` | Amministratore |

La registrazione self-service è disattivata di default (`REGISTRATION_ENABLED=false`).

Per fermare tutto:

```powershell
docker compose down
```

---

## Configurazione email (alert reali)

Gli alert vengono sempre registrati e mostrati in dashboard. Per inviare anche
**email reali** serve configurare l'SMTP:

1. Copia `.env.example` in `.env`.
2. Compila i parametri SMTP (esempio Gmail con *App Password* nel file).
3. Riavvia il backend: `docker compose up -d backend`.
4. In **Contacts** aggiungi un contatto email con il `Min Level` desiderato
   (INFO / WARN / CRITICAL).

Se `SMTP_HOST` è vuoto, l'invio è disabilitato ma gli alert restano visibili e
loggati (`docker compose logs backend`).

---

## Modalità Demo / Pitch Day

Modulo **separato** dal funzionamento normale, pensato per dimostrazioni dal vivo.
Permette di selezionare uno scenario che fa scattare **davvero** la regola di alert
corrispondente (e l'email reale), iniettando una telemetria sintetica **nel
medesimo percorso di ingestione** di un nodo fisico. Non altera né disattiva nulla.

**Uso:**

1. Assicurati che l'SMTP sia configurato e che esista un contatto email (vedi sopra).
2. Login → voce **Demo** nella barra in alto (`http://localhost:3000/demo.html`).
3. Scegli il nodo (o lascia "Nodo auto") e attiva uno scenario:

| Scenario | Livello | Effetto |
|---|---|---|
| Condizioni nominali | OK | Telemetria nel range: nessun alert (ripristino visivo) |
| Crollo CO₂ | WARN | CO₂ < 30.000 ppm |
| Eccesso CO₂ | WARN | CO₂ > 70.000 ppm |
| Sovratemperatura | WARN | Temp ambiente > 38,5 °C |
| Umidità bassa | INFO | Umidità < 80% |
| Guasto alimentazione 12V | CRITICAL | Rail 12V fuori tolleranza → email critica |
| Drift sensore (reale) | WARN | Inietta una lettura con risposta derivata (~12%): muove il drift meter, aggiorna lo stato salute (DEGRADING) e attiva la manutenzione predittiva |

Il **Registro attivazioni** mostra in tempo reale cosa è stato iniettato; la
dashboard e l'email si aggiornano come in produzione.

Disattivazione (es. in produzione): imposta `DEMO_ENABLED=false` nel `.env` e
riavvia il backend. Gli endpoint `/api/demo/*` risponderanno `403`.

> La demo gira **interamente lato piattaforma**: non richiede ri-flash del firmware.
> Una simulazione pilotata dal nodo stesso sarà possibile quando verrà aggiunto il
> canale comandi *outbound* descritto in `docs/AUTOCAL_v1_design.md`.

---

## Monitoraggio drift & manutenzione predittiva

La sezione **Sensor Health & Drift** della dashboard mostra:

- stato di salute del sensore (OK / DEGRADING / CRITICAL),
- drift osservato rispetto alla baseline d'installazione,
- ore di funzionamento e proiezione di fine vita (EOL),
- una **barra di drift** 0 → 20% con zona d'azione (5%) e soglia EOL (20%),
- il **feed eventi drift & calibrazione** (alert `SENSOR_DRIFT` ed eventi di
  correzione automatica).

L'auto-calibrazione semi-real-time (closed-loop verso il nodo) è **implementata e
attiva** (`AUTOCAL_ENABLED=true`), con guard-rail di sicurezza: nessuna correzione
senza curva assegnata/fittata + baseline a regime, e SKIP se la variazione è troppo
rapida (anomalia/guasto). La *curva di stabilità* e la logica di individuazione del
drift sono descritte in `docs/DRIFT_detection_finale.md`; design e dataset in
`docs/AUTOCAL_v1_design.md` e `docs/AUTOCAL_dataset_spec.md`.

---

## Reset dei dati di misurazione

**Soft reset** (azzera misurazioni, alert e stato drift; conserva utenti, lab, hub,
contatti e curve di riferimento):

```powershell
.\scripts\reset_measurements.ps1
```

Lo script esegue `TRUNCATE measurements; TRUNCATE alerts; DELETE FROM sensor_health;`,
riavvia il backend (svuota le cache in memoria, es. cooldown alert) e attende
l'health check.

**Reset totale** (ricrea il database da zero, *cancella anche utenti e lab*):

```powershell
docker compose down -v
docker compose up --build -d
```

> `down -v` rimuove i volumi Docker, incluso `timescale_data`. Le migrazioni Flyway
> (`backend/src/main/resources/db/migration`) ricreano lo schema e i seed al riavvio.

---

## Struttura del progetto

```
IncuSense/
├─ backend/                 Spring Boot (ingestione MQTT, REST, alert, drift)
│  └─ src/main/java/com/incusense/
│     ├─ controller/        REST API (+ DemoController per il pitch)
│     ├─ service/           Ingestione, Alert, Email, DriftMonitoring, Calibration
│     ├─ model/ repository/ Entità JPA e repository
│     └─ resources/db/migration/  Migrazioni Flyway V1..V4
├─ frontend/                Unica UI (Nginx :3000) — login, dashboard, contacts, demo
├─ mosquitto/               Config broker MQTT
├─ nginx/                   Reverse proxy (/api, /ws, /actuator)
├─ scripts/                 reset_measurements.ps1, test e2e, test email
├─ docs/                    Design auto-calibrazione e spec dataset
├─ docker-compose.yml
└─ .env.example
```

---

## API principali (autenticate via JWT, header `Authorization: Bearer …`)

| Metodo | Endpoint | Descrizione |
|---|---|---|
| POST | `/api/auth/login` | Login, ritorna JWT + labId |
| GET | `/api/measurements/latest` | Ultime misure del lab |
| GET | `/api/measurements/history?days=N` | Storico aggregato |
| GET | `/api/alerts` | Alert recenti |
| GET | `/api/hubs/{hubKey}/health` | Stato salute/drift di un sensore |
| GET/POST | `/api/notification-contacts` | Gestione contatti email |
| GET/POST | `/api/demo/scenarios` · `/api/demo/scenario` | Modalità demo (se abilitata) |

WebSocket/STOMP su `/ws`: topic `/topic/measurements/{labId}`, `/topic/alerts/{labId}`,
`/topic/health/{labId}`.

---

## Test automatici

**Test complessivo di sistema** (build + avvio + verifica end-to-end di frontend,
login, demo mode, percorso MQTT del firmware, pipeline email e — opzionale — reset):

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\full_system_test.ps1
# varianti:
#   -SkipBuild      usa lo stack già avviato (più veloce)
#   -IncludeReset   testa anche reset_measurements.ps1 (azzera i dati!)
```

Altri script: `scripts/e2e_test.ps1` (percorso firmware→piattaforma→DB; richiede
`REGISTRATION_ENABLED=true`), `scripts/email_test.ps1` (verifica SMTP).

---

## Troubleshooting

- **La dashboard non si aggiorna:** controlla `docker compose logs backend`; il badge
  in alto a destra mostra LIVE/OFFLINE (fallback automatico al polling REST ogni 5s).
- **Email non arrivano:** verifica `.env` (SMTP), che esista un contatto con `Min Level`
  compatibile col livello dell'alert, e i log `[ALERT-EMAIL]`.
- **`docker compose up` fallisce sul backend:** spesso è il DB non ancora pronto;
  il backend ha `depends_on` con health check, riprova dopo qualche secondo.
- **Porta 3000 occupata:** chiudi l'altro processo o cambia il mapping in `docker-compose.yml`.
