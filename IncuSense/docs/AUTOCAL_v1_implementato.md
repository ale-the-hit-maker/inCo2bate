# Auto-calibrazione v1 — Implementazione + integrazione curva Na:In₂O₃

> Stato: **IMPLEMENTATO** (loop chiuso) + **coefficienti curva popolati**.
> Disattivato di default (`incusense.autocal.enabled=false`): nessun comando viene inviato ai
> nodi finché non viene abilitato esplicitamente. Realizza la proposta in `AUTOCAL_v1_design.md`.

## 1. La conoscenza integrata (curva di calibrazione)

Regressione lineare (log-log) fornita dall'owner:

```
alpha = 0.345 ± 0.051   beta = 0.254 ± 0.019   R² = 0.98
mappatura: alpha = log10(a)  →  a = 10^alpha = 2.2131 ;  b = beta = 0.254
modello:   response = a · ppm^b      (⇔  log10(response) = alpha + beta·log10(ppm))
```

Popolata in `drift_reference_curves` (riga seed `NaIn2O3_CO2`) dalla migrazione **`V5__autocal.sql`**,
con `r2`, le incertezze, e `valid_ppm = [250, 5000]`.

### Nota critica sulle unità (perché `a` NON si applica al response del firmware)
- Il `sensor_response` del firmware è `|I_air − I| / I_air ∈ [0,1]`.
- La *response* del paper su cui sono fittati α/β è un rapporto di conduttanza (~4..13).
- **`b` (esponente) e la dispersione log-log sono invarianti di scala** → usabili comunque.
- **`a` (intercetta) dipende dalla scala** → la piattaforma **non** converte il `sensor_response`
  grezzo in ppm con `a`. `CalibrationCurve.responseToPpm()` vale solo per input sulla scala della curva.
- **Range:** la curva è valida 250–5000 ppm; l'incubatore opera a 30k–50k ppm → **non estrapolabile**.
  `responseToPpm`/`ppmToResponse` ritornano vuoto fuori range (niente stime assurde al setpoint).

### Come α/β entrano davvero nel meccanismo
Il loop usa `b` per tradurre il **drift frazionale del response** (auto-riferito, adimensionale)
nell'**errore frazionale di ppm**:
`dPpm/ppm = (dResponse/response) / b` → guida il guard-rail sull'ampiezza della correzione.
Con `b=0.254` e setpoint 50k il fattore amplifica (drift 5% ⇒ ~9 800 ppm): `max-offset-ppm`
è tarato di conseguenza (default 15 000) — **da validare con i dataset reali** (`AUTOCAL_dataset_spec.md`).

## 2. Il loop (closed-loop, self-referenced)

`telemetry → DriftMonitoringService.update() → AutoCalibrationService.evaluate()`:
EWMA del `sensor_response` **solo a regime** (gating §5.0) → bande **OK / ACTIONABLE / CRITICAL**
→ guard-rail → pubblica **`OFFSET_CAL`** con `offset_ppm` = setpoint (miglior stima della
concentrazione vera, mantenuta dall'incubatore) → il nodo ricava il delta e risponde **ACK** →
verifica post-cal su `co2_ppm` vs setpoint → **VERIFIED / FAILED**. Tutto tracciato in
`calibration_events` (PENDING/ACKED/VERIFIED/FAILED/SKIPPED).

## 3. File toccati

Nuovi: `model/CalibrationEvent`, `service/CalibrationCurve`, `service/AutoCalibrationPolicy`,
`service/AutoCalibrationService`, `service/CommandTransport`(+`MqttCommandTransport`),
`controller/AutoCalibrationController`, `db/migration/V5__autocal.sql`,
test `CalibrationCurveTest`/`AutoCalibrationPolicyTest`, `scripts/autocal_verify.py`,
`scripts/autocal_e2e_test.ps1`.
Modificati: `config/MqttConfig` (outbound `.../commands` + sub `.../ack`),
`service/MqttIngestionService` (routing ACK), `service/DriftMonitoringService` (invoca l'autocal),
`dto/Dtos` (+`CalibrationEventResponse`), `repository/Repositories` (+`CalibrationEventRepository`),
`application.yml` (`incusense.autocal.*`, `mqtt.ack-topic`).

**Firmware: nessuna modifica** (comandi/ACK già supportati) → **nessun ri-flash** nel happy path.

## 4. Contratto MQTT (additivo, retrocompatibile)
- `incusense/labs/{lab}/hubs/{hub}/commands` (backend→nodo): `{"command":"OFFSET_CAL","offset_ppm":<setpoint>,"event_id":<id>}`
- `incusense/labs/{lab}/hubs/{hub}/ack` (nodo→backend): `{"hub_id","command","status":"OK|ERROR","event_id","ts"}`

## 5. Abilitazione e test
1. Abilitare: `AUTOCAL_ENABLED=true` (env del servizio `backend` in `docker-compose.yml`), riavviare.
2. Assegnare la curva all'hub e fissare l'install baseline: `POST /api/hubs/{hub}/health/assign-curve`.
3. Verifica matematica/logica (offline): `python3 scripts/autocal_verify.py`.
4. Unit test JVM: `mvn -f backend/pom.xml test` (CalibrationCurveTest, AutoCalibrationPolicyTest).
5. E2E loop: `powershell -File scripts/autocal_e2e_test.ps1` (broker su; simula comando/ACK/verifica).
6. API: `GET /api/calibration-events`, `POST /api/hubs/{hub}/autocal/trigger`.

## 6. Limiti dichiarati (verifica residua a carico dell'owner)
- **Non compilato in questa sessione** (toolchain JDK/Maven non disponibile qui): la matematica e la
  policy sono state verificate via port Python (tutti i check passano); restano da eseguire da parte
  tua `mvn test`, l'avvio del backend e l'E2E sul broker.
- I coefficienti sono fittati su 250–5000 ppm: **non danno una ppm assoluta al setpoint incubatore**.
  Il loop usa perciò il setpoint come riferimento (auto-riferito). Per una conversione assoluta nel
  range operativo serve il **Dataset A** (`AUTOCAL_dataset_spec.md`).
- `MAX_DRIFT_RATE` (discriminazione drift↔guasto, Dataset B) e la taratura fine di `max-offset-ppm`,
  `T_action`, `min-samples` richiedono i dati di invecchiamento reali.
- **Firmware (osservazione di contratto, non modificato):** `sensor_response` è troncato a `[0,1]`;
  a CO₂ elevata la response fisica reale può superare 1 e saturare. Va valutato sull'hardware reale
  prima di affidarvi conversioni assolute basate sul response (eventuale fix = contract change + ri-flash).
- Gating "a regime" v1: co2≈setpoint + heater≈250 °C. La varianza di finestra e il check "nessun
  alert attivo" (design §5.0) sono semplificati: rifinibili senza cambi di contratto.
