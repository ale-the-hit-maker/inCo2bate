# Auto-calibrazione semi-real-time — Design v1 (proposta)

> Stato: **PROPOSTA — non ancora implementata.** Documento della Fase 2 (progettazione).
> Decisioni prese con l'owner:
> 1. Correzione **closed-loop al nodo** (la piattaforma invia comandi, il firmware corregge).
> 2. Riferimento: **baseline auto-riferita** (drift relativo all'install baseline).
> 3. Autonomia: **automatica con guard-rail** (oltre soglia → solo alert).

---

## 1. Obiettivo

Chiudere l'anello di auto-calibrazione: la piattaforma confronta le misure ricevute con
il comportamento di riferimento memorizzato, rileva un drift "azionabile" e **comanda al
nodo** una correzione (offset / re-zero). Il nodo applica e conferma con ACK; la piattaforma
verifica l'esito. Tutto in modo semi-real-time (valutazione continua, comando *throttled*).

## 2. Stato attuale — cosa c'è e cosa manca

| Pezzo | Stato | Dove |
|---|---|---|
| Firmware sottoscritto a `.../commands`, gestisce `ZERO_CAL` / `OFFSET_CAL` | ✅ presente | `main.cpp` `mqttCallback` |
| Firmware pubblica ACK su `.../ack` | ✅ presente | `main.cpp` `publishAck` |
| Telemetria con `raw_adc`, `sensor_response` per il drift | ✅ presente | `main.cpp` / `V4__drift.sql` |
| Tabella curve di riferimento + `sensor_health` | ✅ presente (coeff. seed `null`) | `DriftReferenceCurve`, `V4__drift.sql` |
| Rilevazione drift (vs install baseline) + alert | ✅ presente | `DriftMonitoringService` |
| **Backend pubblica su `commands`** | ❌ **manca** (MqttConfig è solo inbound) | `MqttConfig` |
| **Backend sottoscritto a `ack`** | ❌ **manca** (solo telemetry+status) | `MqttConfig` / `MqttIngestionService` |
| **Decisione drift → azione correttiva** | ❌ **manca** (oggi solo alert) | — |
| **Persistenza/lifecycle eventi di calibrazione** | ❌ **manca** | — |
| Calibrazione lato server (offset manuale) | ⚠️ esiste ma **scollegata** dal drift | `CalibrationService` |

**Conclusione:** il loop è predisposto ~70%; serve aggiungere il lato *outbound* del backend,
la logica di decisione con guard-rail, e la persistenza degli eventi. Nel percorso "happy path"
**il firmware non va modificato → niente ri-flash del parco nodi.**

## 3. Flusso target

```
telemetry ──> MqttIngestionService ──> DriftMonitoringService.update()
                                            │  (calcola observedDriftPct vs installResponse)
                                            ▼
                                   AutoCalibrationService.evaluate()
                                     • EWMA/mediana su finestra (anti-rumore)
                                     • banda: OK | ACTIONABLE | CRITICAL
                                     • guard-rail (max offset, rate, cooldown, salute)
                                            │ se ACTIONABLE e dentro guard-rail
                                            ▼
                                   publish OFFSET_CAL/ZERO_CAL  ──MQTT──> nodo
                                   (persist CalibrationEvent = PENDING)
                                            ▼
   nodo applica correzione, risponde ──ACK──> backend (sub .../ack)
                                            ▼
                                   CalibrationEvent = ACKED
                                            ▼
                            verifica post-cal su N campioni successivi
                                   (drift rientra in banda?) → VERIFIED | FAILED
```

## 4. Contratto firmware ↔ piattaforma (formalizzazione — §6)

Additivo e **retrocompatibile**. Topic già definiti nel firmware:

- `incusense/labs/{labId}/hubs/{hubKey}/commands` (backend → nodo)
- `incusense/labs/{labId}/hubs/{hubKey}/ack` (nodo → backend)

**Comando OFFSET_CAL** (backend pubblica):
```json
{ "command": "OFFSET_CAL", "offset_ppm": <additive_offset_ppm>, "offset_mode": "absolute_ppm", "event_id": <long> }
```
**Comando ZERO_CAL**:
```json
{ "command": "ZERO_CAL", "event_id": <long> }
```
**ACK** (nodo pubblica):
```json
{ "hub_id": "...", "command": "OFFSET_CAL", "status": "OK|ERROR", "event_id": <long>, "ts": <epoch> }
```

### ⚠️ Nota di semantica critica (da non sbagliare)
`OFFSET_CAL` interpreta `offset_ppm` come **offset additivo assoluto in ppm**: la piattaforma
calcola il delta, il firmware lo sostituisce all'offset precedente e lo applica alle letture
successive. Il campo `offset_mode="absolute_ppm"` rende esplicita la semantica del contratto.

`ZERO_CAL` invece ri-campiona la baseline in aria: **fisicamente valido solo se il sensore è
realmente in aria pulita** in quell'istante (raro in un incubatore a setpoint CO₂). Da usare
solo in finestre di riferimento certe.

> Se in futuro si vuole un offset additivo "puro" (delta esplicito), serve cambiare la semantica
> del firmware → **contract change + ri-flash di tutti i nodi** (vedi §7 istruzioni). Rimandato.

## 5. Algoritmo di decisione (auto con guard-rail)

### 5.0 Gating a regime (PRE-CONDIZIONE — risolve il confondimento drift ↔ concentrazione reale)

`sensor_response` dipende dalla concentrazione vera: un calo reale di CO₂ (guasto, porta
aperta, rampa di riempimento 30k→50k ppm) sarebbe indistinguibile da un drift se il
calcolo avvenisse su ogni campione. **Un campione contribuisce alla stima del drift solo
se il sistema è a regime**, cioè se valgono tutte:

- `|co2_ppm − setpoint_hub| ≤ SETPOINT_TOL_PPM` (setpoint configurato per hub, nuovo campo);
- varianza/escursione di `co2_ppm` nella finestra ≤ `STABILITY_TOL` (esclude rampe e transitori);
- `heater_temp` entro tolleranza dal target 250 °C;
- nessun alert CO₂ attivo per l'hub (un'anomalia di processo **sospende** l'autocal, mai la innesca).

Anche `installResponse` deve essere catturata **a regime, allo stesso setpoint** (oggi
`DriftMonitoringService` la cattura al primo campione qualsiasi: da correggere).

**Separazione delle scale temporali:** drift MOX = giorni/settimane; guasti/rampe = minuti/ore.
Variazione della risposta-a-regime più rapida di `MAX_DRIFT_RATE_PCT_PER_DAY` → non è drift:
alert di processo e nessuna correzione.

**Limite residuo dichiarato:** una perdita *lenta* di CO₂ alla stessa scala temporale del drift
non è distinguibile con un solo sensore. Mitigazioni future: eventi di riferimento ZERO/SPAN
periodici o ridondanza multi-sensore.

### 5.1 Calcolo e bande

- **Baseline osservata:** EWMA di `sensor_response` calcolata **solo sui campioni a regime** (§5.0).
- **Drift:** `driftPct = (resp_ewma − installResponse) / installResponse · 100` (già in `SensorHealth`).
- **Bande:**
  - `|drift| < T_action` → **OK**, nessuna azione.
  - `T_action ≤ |drift| < EOL_DRIFT_PCT (20%)` → **ACTIONABLE** → calcola correzione e, se dentro i guard-rail, invia comando.
  - `|drift| ≥ EOL_DRIFT_PCT` → **CRITICAL** → **nessuna auto-correzione**, solo alert predittivo (come oggi) + richiesta intervento.
- **Guard-rail (tutti devono passare):**
  - `|correzione| ≤ MAX_OFFSET_PPM`
  - rate di correzione ≤ `MAX_OFFSET_PPM_PER_DAY` (anti-rincorsa del rumore)
  - `cooldown ≥ MIN_HOURS_BETWEEN_CMD` dall'ultimo comando per quell'hub
  - almeno `MIN_SAMPLES` campioni validi nella finestra
  - `health_status != CRITICAL`
  - input sano (campi non null, entro range fisici)
  - isteresi: non ri-correggere finché il drift non rientra/riesce dalla banda.
- **Se un guard-rail blocca:** evento `SKIPPED` + alert (suggerisci ZERO_CAL manuale).

## 6. Change-set minimale e reversibile

**Backend + firmware (contratto OFFSET_CAL esplicito):**
1. `MqttConfig`: aggiungere un **outbound flow** (`MqttPahoMessageHandler` + canale) verso `commands`; estendere l'adapter inbound per sottoscrivere anche `.../ack`.
2. `AutoCalibrationService` (nuovo): EWMA, bande, guard-rail, mapping drift→offset additivo ppm, cooldown, lifecycle eventi. Invocato da `DriftMonitoringService.update()` nella banda ACTIONABLE.
3. `MqttIngestionService` / nuovo handler: gestione topic `.../ack` → aggiorna `CalibrationEvent`.
4. Nuova entità + tabella `calibration_events` (id, hub, command, requested_offset_ppm, status `PENDING|ACKED|VERIFIED|FAILED|SKIPPED`, created_at, acked_at, verified_at) → **migrazione `V5__autocal.sql`**.
5. DTO + endpoint controller: lista eventi calibrazione, trigger manuale, tuning soglie.
6. `application.yml` + `docker-compose.yml`: proprietà `incusense.autocal.*` (soglie, cooldown, max offset, abilitazione).

**Firmware:** supporta `OFFSET_CAL` come offset additivo assoluto, persiste
`cal_offset_ppm` / `cal_air_baseline` su LittleFS e ignora `event_id` gia' applicati.

## 7. Impatto su contratto e parco nodi (§6/§7)

- Modifiche al contratto MQTT: **additive e retrocompatibili** (il firmware già le supporta).
- **Migrazione DB `V5`**: tabella nuova, nessun impatto sui dati esistenti → *richiede conferma (stop-and-ask)*.
- **Ri-flash richiesto** per i nodi che devono usare la nuova semantica `offset_ppm`
  additiva e la persistenza dello stato di calibrazione.

## 8. Verifica (Fase 4)

- **Unit test** guard-rail (soglie, cooldown, isteresi, input malformati).
- **E2E senza hardware:** pubblicare su mosquitto una serie di telemetrie con drift crescente →
  verificare che il backend pubblichi `OFFSET_CAL` con `event_id`; pubblicare un ACK simulato →
  verificare transizione `PENDING→ACKED→VERIFIED`. Riusare `scripts/e2e_test.ps1` + broker locale.
- **Regressione:** payload "vecchio firmware" senza `sensor_response` continua a essere ingerito senza errori (oggi già gestito).

## 9. Cosa serve da te prima di implementare

1. **OK alla migrazione DB `V5__autocal.sql`** (tabella `calibration_events`).
2. Valori iniziali guard-rail: `T_action` (es. 5%), `MAX_OFFSET_PPM` (es. 1000 ppm), `MAX_OFFSET_PPM_PER_DAY`, `MIN_HOURS_BETWEEN_CMD` (es. 12 h), `MIN_SAMPLES`.
3. Confermi che nell'happy path vogliamo **OFFSET_CAL** (semantica offset additivo assoluto) e teniamo **ZERO_CAL** solo per finestre di aria pulita certe?
4. L'hardening firmware (persistenza stato + idempotenza) lo pianifichiamo **ora** (un ri-flash unico) o **dopo**?
