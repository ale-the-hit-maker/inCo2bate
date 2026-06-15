# Individuazione del drift e curva di stabilità — Decisione finale (V7)

> Stato: **IMPLEMENTATO**. Definisce come la piattaforma individua il drift del sensore
> Na:In₂O₃ a regime di incubatore (≈50.000 ppm), come è costruita la *curva di stabilità*,
> e come l'auto-calibrazione closed-loop agisce in sicurezza.
> Riferimenti codice: `service/CalibrationCurve.java`, `service/DriftMonitoringService.java`,
> `service/AutoCalibrationService.java`, `service/AutoCalibrationPolicy.java`,
> migrazione `db/migration/V7__incubator_stability_curve.sql`. Verifica: `scripts/autocal_verify.py`.

---

## 1. Il problema in una riga

I coefficienti α/β della curva allometrica `response = a·ppm^b` sono fittati su **250–5.000 ppm**
e sulla *response del paper* (rapporto di conduttanza ~4..13). L'incubatore lavora a **50.000 ppm**
e il firmware produce un `sensor_response = |I_air−I|/I_air ∈ [0,1]`, una **scala diversa**. Bisogna
quindi usare la curva senza estrapolare ciecamente né l'intercetta né il range.

## 2. Cosa è invariante e cosa no (il punto critico)

- **`b` (esponente) e la dispersione log (`sigma_log`)** sono **invarianti di scala**: una
  rinormalizzazione moltiplicativa del response sposta solo l'intercetta. → usabili a 50.000 ppm.
- **`a` (intercetta)** è legata alla scala del paper: **non** si applica al `sensor_response` [0,1].

Conseguenza pratica: la conversione assoluta response→ppm si fa **per ancoraggio**, non con `a`:

```
ppm = anchor_ppm · (response / anchor_response)^(1/b)
```

dove `anchor_response` è un valore **misurato** sulla stessa scala del response (firmware), a una
concentrazione nota `anchor_ppm`. Non compare mai `a`. (`CalibrationCurve.ppmFromAnchoredResponse`).

## 3. La curva di stabilità (definizione operativa)

La curva di stabilità **non** è una predizione assoluta del response a 50k. È una **banda di
tolleranza auto-riferita** attorno alla baseline *misurata* del sensore al setpoint:

- **Ancora di default** = `install_response`, la baseline catturata **a regime** al setpoint.
  Con questa ancora, la curva assoluta **coincide matematicamente** con la banda auto-riferita.
- **Ampiezza della banda** = `sigma_log` (dispersione log dei residui del fit). La banda ±k·σ è
  `10^(k·sigma_log) − 1` in frazione di response (`CalibrationCurve.stabilityBandFractionUpper`).
  `sigma_log = 0.0071` è scelto perché **±3σ ≈ 5%** coincide con la soglia d'azione (da validare con
  i residui reali del Dataset A).
- **Ancora certificata (upgrade)**: se si inserisce in `anchor.anchor_response` una lettura presa
  con strumento certificato a 50k, la curva diventa **assoluta vera** (intercetta `a_fw` fondata) e
  permette anche di scoprire un sensore già sbagliato all'installazione. Senza, resta auto-riferita
  (sufficiente per il drift nel tempo). Vedi `docs/AUTOCAL_dataset_spec.md` §4 (versione minima: aria/medio/setpoint).

### Bande (sul |drift| del response a regime, vs baseline)

| Banda | Condizione | Azione |
|---|---|---|
| **STABILE** | `|drift| < 5%` (≈ ±3σ) | nessuna |
| **DRIFTING** | `5% ≤ |drift| < 20%` | correzione (se guard-rail OK) o advisory |
| **EOL** | `|drift| ≥ 20%` | nessuna correzione → **alert sostituzione** |

## 4. Individuazione "intelligente" — come si distingue il drift da un calo reale di CO₂

Un calo reale di CO₂ e il drift del sensore muovono entrambi il `sensor_response`. Si separano su
**tre assi**, tutti applicati prima di agire:

1. **Gating a regime** (`AutoCalibrationService.isAtRegime`): un campione contribuisce alla stima
   del drift solo se `|co2 − setpoint| ≤ tol` e `|heater − 250 °C| ≤ tol`. Rampe e cali bruschi
   escono dal gating → non confondono il drift.
2. **Scala temporale / rate-of-change** (`isRateWithinDriftRegime`): il drift è invecchiamento
   (giorni/settimane). Se il rate medio supera `max-drift-rate-pct-per-day` (default 3 %/giorno) →
   **anomalia/guasto, non drift** → nessuna auto-correzione (evento `SKIPPED`). Fail-open sotto 1 h
   operativa per non bloccare a inizio vita.
3. **EWMA a regime** (anti-rumore): la decisione usa la media esponenziale del response, non il
   singolo campione.

**Limite dichiarato (onestà):** un calo *lento* di CO₂ alla stessa scala temporale del drift non è
distinguibile con un solo sensore. Mitigazioni future: riferimenti ZERO/SPAN periodici o ridondanza
multi-sensore (Dataset B). Questo non è un bug: è un limite fisico del singolo sensore.

## 5. Range di CO₂ accettabili per incubatore (coltura cellulare)

Standard mammifero: **5% CO₂ = 50.000 ppm** (pH fisiologico 7,2–7,4 con terreni a bicarbonato).
Codificati in `V7 → incubator_co2`:

| Banda | ppm | % CO₂ | Significato |
|---|---|---|---|
| Setpoint | 50.000 | 5,0% | target |
| Qualità | 45.000–55.000 | 4,5–5,5% | pH ottimale per la maggior parte dei terreni |
| Operativa / allarme | 30.000–70.000 | 3–7% | fuori → alert (allineata ad `AlertService`) |
| Tollerata | 40.000–100.000 | 4–10% | dipende dal terreno |

## 6. Closed-loop in sicurezza (posture scelta: attivo)

L'auto-calibrazione è **attiva** (`AUTOCAL_ENABLED=true`). Quando è in banda DRIFTING e supera i
guard-rail, pubblica `OFFSET_CAL` (offset additivo assoluto in ppm) verso il nodo, che lo applica,
ACK, e la piattaforma verifica. **Nuovi guard-rail di sicurezza** (oltre a max-offset, cooldown,
min-samples, isteresi, salute):

- **Curva non assegnata/fittata → nessun comando** (solo advisory): senza `b`/ancoraggio la
  correzione non è fondata.
- **Rate troppo rapido → SKIP**: probabile guasto, non drift.
- **Offset stimato (ancorato) oltre `max-offset-ppm` → SKIP**.

Il firmware supporta già `OFFSET_CAL` additivo (persistito su LittleFS, idempotente su `event_id`,
clamp 15.000 ppm): **nessun ri-flash** per i nodi già aggiornati. Nodi su firmware vecchio: ri-flash.

## 7. I numeri tornano (verifica offline, `scripts/autocal_verify.py`)

Con baseline misurata `≈0.53` al setpoint (ADC 1380→2115) e `b=0.254`:

| Drift response | ppm implicati | Errore vs setpoint | Esito guard-rail (max 15k) |
|---|---|---|---|
| +5% | 60.589 | +10.589 ppm | comando consentito |
| +10% | 72.767 | +22.767 ppm | **SKIP** (oltre max-offset) |
| +20% | 102.496 | +52.496 ppm | **SKIP** + banda → vicino EOL |

Banda di stabilità: **±3σ = 5,03%** (≈ soglia d'azione); EOL 20% ≈ 11,2σ. Tutti i check PASS.

L'amplificazione (`dPpm/ppm = drift/b`, con `1/b ≈ 3,94`) è la ragione per cui il guard-rail
`max-offset-ppm` è essenziale: un drift response modesto implica una correzione ppm grande.

## 8. Cosa resta a tuo carico (verifica residua)

- **Dataset A** (response vs ppm certificato nel range incubatore) → valida `sigma_log` reale e,
  se vuoi, popola `anchor_response` certificato (curva assoluta vera).
- **Dataset B** (aging) → valida `max-drift-rate-pct-per-day` e la soglia EOL 20%.
- **Saturazione a 50k**: confermare su hardware che `sensor_response` non saturi (il firmware lo
  clampa a [0,1]); se satura, `b` va rifittato localmente. Finché non confermato, l'entità
  dell'offset è una stima fisicamente ragionevole, non una verità misurata.
