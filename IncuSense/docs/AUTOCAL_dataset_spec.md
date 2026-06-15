# Dataset per la curva di riferimento — Specifica di acquisizione

> Complemento di `AUTOCAL_v1_design.md` (§5.0 e §2). Definisce **quali dati sperimentali
> servono** per validare e rifinire `drift_reference_curves` e per
> permettere alla piattaforma di distinguere un **calo reale di CO₂** nell'incubatore da un
> **drift del sensore** Na:In₂O₃.

---

## 0. Perché serve un dataset (e perché due)

La curva memorizzata nel DB è `response = a · ppm^b` (power-law, seed in `V4__drift.sql`).
Da sola, la curva risolve metà del problema:

- **Curva di risposta (Dataset A)** → dice "a concentrazione X il sensore *sano* risponde Y".
  Permette di convertire la risposta misurata in ppm stimati e di calcolare l'offset di
  correzione in ppm in modo fondato (oggi sarebbe una stima euristica).
- **Curva di invecchiamento (Dataset B)** → dice "un sensore che invecchia degrada di Z %/settimana,
  in questa direzione". È **questa** la firma che discrimina: un calo reale di CO₂ si muove
  *lungo* la curva di risposta con la dinamica del processo (minuti/ore); il drift *deforma*
  la curva con la dinamica dell'invecchiamento (giorni/settimane), con velocità e segno noti.

Regola di discriminazione che ne deriva, implementabile in `AutoCalibrationService`:

```
variazione risposta a regime  >  MAX_DRIFT_RATE  (da Dataset B)   →  anomalia di processo (alert, NO autocal)
variazione risposta a regime  ≤  MAX_DRIFT_RATE  e segno coerente →  candidato drift (autocal con guard-rail)
```

**Stato seed attuale:** il DB distingue `experimental_ppm: [250, 5000]` (range dei paper)
da `valid_ppm: [30000, 70000]` (range operativo incubatore). Il dataset va comunque acquisito
nel range operativo reale, setpoint incluso, per validare l'intercetta assoluta a 50.000 ppm.

---

## 1. Dataset A — Curva di risposta statica (response vs ppm)

**Scopo:** fittare `a`, `b` e quantificare l'incertezza della curva.

**Setup richiesto**
- Camera/incubatore con concentrazione CO₂ controllabile e **verificata da uno strumento di
  riferimento certificato indipendente** (es. NDIR calibrato): la colonna `ppm_ref` deve venire
  dallo strumento di riferimento, non dal sensore stesso.
- Sensore nelle condizioni operative reali: heater a 250 °C a regime, ambiente da incubatore
  (≈37 °C, RH ≈ 90–95%). La RH è importante: il paper caratterizza a RH diverse, e l'umidità
  altissima dell'incubatore può spostare la curva.
- Firmware in modalità reale (non mock): `raw_adc` e `sensor_response` letti dal TIA.

**Piano dei punti**
- Almeno **8 concentrazioni**, spaziate in scala logaritmica, che coprano da aria pulita al
  fuori-scala alto: es. `400 (aria) · 1.000 · 5.000 · 10.000 · 20.000 · 30.000 · 40.000 · 50.000 · 60.000 ppm`.
  Devono essere inclusi: il **setpoint** (50.000), la zona della **rampa** (30.000–50.000) e
  l'**aria pulita** (definisce la baseline di `sensor_response = 0`).
- Per ogni punto: attendere il **plateau** (steady-state del sensore E della camera), poi
  registrare **≥ 30 campioni consecutivi stabili** (1 Hz → 30 s, meglio 5 min).
- **3 repliche** per punto, sia in **salita** che in **discesa** di concentrazione → quantifica
  ripetibilità e isteresi.
- Se disponibili più esemplari di sensore: ripetere su **≥ 3 unità** → separa la variabilità
  di lotto dalla curva nominale (decide se serve una curva per-unità o basta quella di tipo).

**Formato dati (CSV, una riga per campione)**

| colonna | tipo | note |
|---|---|---|
| `ts` | ISO-8601 | timestamp |
| `sensor_id` | string | esemplare fisico |
| `ppm_ref` | float | dallo strumento certificato |
| `raw_adc` | int | 0–4095 |
| `sensor_response` | float | \|I_air − I\| / I_air |
| `heater_temp` | float | °C, deve essere ≈250 |
| `env_temp` | float | °C |
| `env_hum` | float | %RH |
| `direction` | enum | `up` / `down` |
| `replica` | int | 1–3 |

**Elaborazione → cosa entra nel DB**
- Fit log-log: `log(response) = log(a) + b·log(ppm)` → `a`, `b`, R², deviazione standard dei
  residui `sigma_log`.
- `sigma_log` non è un extra: definisce la **banda di tolleranza** (`SETPOINT_TOL`,
  soglia OK/ACTIONABLE) — quanto scarto è "fisiologico" prima di chiamarlo anomalia.
- `points_json` risultante:

```json
{
  "model": "power_law",
  "a": <fit>, "b": <fit>, "r2": <fit>, "sigma_log": <fit>,
  "valid_ppm": [30000, 70000],
  "ref_temp_c": 250,
  "ref_env": {"temp_c": 37, "rh_pct": 95},
  "hysteresis_pct": <max scarto up/down>,
  "unit_spread_pct": <se multi-unità>
}
```

---

## 2. Dataset B — Curva di invecchiamento (drift nel tempo)

**Scopo:** misurare la **velocità e il segno** del drift naturale → da qui escono
`MAX_DRIFT_RATE_PCT_PER_DAY` (il guard-rail che discrimina drift da guasto) e la validazione
della soglia EOL al 20%.

**Procedura**
- Stesso sensore, **condizioni fisse e note**: misura periodica della risposta in **2 punti di
  riferimento** — aria pulita (400 ppm) e setpoint (50.000 ppm) — verificati dallo strumento
  certificato.
- Cadenza: **1 misura/giorno** (minimo: 2/settimana), ciascuna = plateau + ≥30 campioni come nel Dataset A.
- Durata minima: **≥ 7 giorni** per confrontarsi con la stabilità documentata nei paper Na:In₂O₃;
  raccomandata **≥ 4–7 settimane** per stimare un rate di invecchiamento utile alla manutenzione
  predittiva. Più lungo = stima del rate più affidabile.
- Il sensore tra una misura e l'altra deve restare **operativo nelle condizioni reali**
  (heater acceso, ambiente incubatore): il drift dipende dalle ore di funzionamento a caldo,
  non dal tempo di calendario.

**Formato dati:** stesso CSV del Dataset A + colonna `operating_hours` (ore cumulative col
heater a regime — il firmware non le traccia, va tenuto un log esterno o stimato dal log di alimentazione).

**Elaborazione → cosa entra nel DB / config**
- Drift rate: pendenza di `response@riferimento` vs `operating_hours`, separata per i 2 punti:
  - drift uguale in % su entrambi i punti → degrado del coefficiente `a` (sensibilità) → correggibile con offset/gain;
  - drift solo sulla baseline in aria → spostamento dello zero → caso da `ZERO_CAL`.
- `MAX_DRIFT_RATE_PCT_PER_DAY` = rate osservato × margine (es. 3×): tutto ciò che cambia più
  in fretta **non è drift** → alert di processo.
- Verifica che a EOL_DRIFT_PCT=20% il sensore sia effettivamente fuori specifica (o correggi la soglia).

---

## 3. Dataset C (opzionale) — Sensibilità a T/umidità

Solo se i residui del Dataset A correlano con `env_temp`/`env_hum`: misure a setpoint fisso
variando T (35–39 °C) e RH (85–98%) → fitta `K_TEMP`, `K_HUM` oggi a 0 in
`DriftMonitoringService`. In un incubatore ben controllato può restare a 0.

---

## 4. Versione minima praticabile (se non hai un banco gas completo)

Se il banco a 8 concentrazioni non è disponibile subito:

1. **3 punti** con strumento certificato dentro l'incubatore reale: aria (400), un punto medio
   (es. 20.000 raggiunto durante la rampa, letto dal riferimento), setpoint (50.000).
   Bastano per un fit power-law grezzo (2 gradi di libertà + 1 di verifica).
2. **Dataset B ridotto**: 1 lettura/settimana al setpoint contro il riferimento, per ≥ 4 settimane.

Precisione inferiore, ma sufficiente per attivare il gating §5.0 e i guard-rail con margini
prudenti; la curva si raffina quando arriva il dataset completo.

---

## 5. Checklist di qualità del dataset

- [ ] `ppm_ref` sempre da strumento indipendente certificato (mai dal sensore in prova)
- [ ] Heater a 250 °C ± tolleranza durante ogni campione (scartare i transitori di riscaldamento)
- [ ] Range copre il setpoint reale dell'incubatore (50.000 ppm), non solo il range del paper
- [ ] Plateau verificato prima di campionare (derivata ~0 su finestra di 1 min)
- [ ] Salita E discesa (isteresi quantificata)
- [ ] RH e T ambiente registrate per ogni campione
- [ ] Dataset B: ore operative cumulative tracciate
- [ ] Nessun campione con alert/anomalie hardware nel log
