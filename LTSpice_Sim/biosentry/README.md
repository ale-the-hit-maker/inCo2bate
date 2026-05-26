# BioSentry IncuSense — LTSpice Test Suite

## Struttura del progetto

```
ltspice_biosentry/
├── netlists/                       # Netlist SPICE (.net)
│   ├── heater_driver.net           # §2.4 — Heater driver Pt (dual-mode)
│   ├── tia_sensor.net              # §2.5 — TIA MCP6001 + sensore MOx
│   ├── gas_flow_controller.net     # §2.6 — Fan PWM driver
│   ├── ethernet_power.net          # §2.7/2.8 — Buck + LDO + carichi Ethernet
│   └── system_integrated.net       # Sistema completo (cross-interference)
├── scripts/
│   └── run_tests.py                # Script Python di test e analisi
├── output/                         # Risultati (grafici, report)
└── README.md
```

## Requisiti

- **LTSpice** (XVII o 24): installato e accessibile da riga di comando
  - Windows: `C:\Program Files\ADI\LTspice\LTspice.exe`
  - Linux (via Wine): `wine ~/.wine/drive_c/.../LTspice.exe`
  - macOS: `/Applications/LTspice.app/Contents/MacOS/LTspice`
- **Python 3.8+** con:
  ```bash
  pip install PyLTSpice matplotlib numpy
  ```

## Uso rapido

```bash
# Tutti i circuiti (con LTSpice installato)
python scripts/run_tests.py

# Singolo circuito
python scripts/run_tests.py --circuit heater
python scripts/run_tests.py --circuit tia
python scripts/run_tests.py --circuit fan
python scripts/run_tests.py --circuit power
python scripts/run_tests.py --circuit system

# Con percorso LTSpice personalizzato
python scripts/run_tests.py --ltspice-path "C:\Program Files\ADI\LTspice\LTspice.exe"

# Modalità teorica (senza LTSpice, solo calcoli analitici + grafici)
python scripts/run_tests.py
```

Se LTSpice non è disponibile, lo script genera automaticamente un'analisi
teorica basata sulle equazioni circuitali e produce comunque grafici e report.

## Descrizione dei sottocircuiti simulati

### 1. Heater Driver (`heater_driver.net`)
Rif.: BioSentry PDF §2.4, Della Ciana 2021 Fig. 3

Due regimi alternati ogni 10ms:
- **Supply** (0–9ms): M1 ON, Q1 modulato da PWM → potenza al heater
- **Measurement** (9–10ms): M1 OFF, Q1 ON → corrente in Rref, si misura R_heater

Parametri simulati: R_heater sweep (10Ω@RT → 24.4Ω@450°C)

### 2. TIA Sensore CO₂ (`tia_sensor.net`)
Rif.: BioSentry PDF §2.5, Della Ciana 2021 Fig. 4

MCP6001 in configurazione trans-impedenza con Rf=22kΩ fisso.
Include circuito di calibrazione baseline (Rf_CAL=470kΩ + 2N7002).

Parametri simulati: Rs_sensor sweep (40kΩ…2MΩ)

### 3. Gas Flow Controller (`gas_flow_controller.net`)
Rif.: BioSentry PDF §2.6

Ventola DC brushless (R+L) pilotata da Si2302ADS con PWM 25kHz.
Diodo flyback 1N5819 Schottky. Rete disaccoppiamento C1+C2.

Parametri simulati: duty cycle sweep (20%…100%)

### 4. Ethernet / Power Module (`ethernet_power.net`)
Rif.: BioSentry PDF §2.7, §2.8

Catena: 12V → TVS → Polyfuse → AP63203 Buck (5V) → AP2112K LDO (3.3V)
Carichi: ESP32 (80mA+burst), W5500 (30mA+130mA burst), heater (PWM 12V)

### 5. Sistema Integrato (`system_integrated.net`)
Tutti i sottocircuiti sugli stessi rail. Verifica che il rumore
del heater (1kHz) e della ventola (25kHz) e dei burst Ethernet (25MHz)
non corrompa la lettura analogica del TIA.

## Criteri di accettazione

| Test | Parametro | Soglia |
|------|-----------|--------|
| Heater | Transizione supply↔meas | < 1 ms |
| Heater | P_avg @250°C | 0.5–1.5 W |
| TIA | V_out @Rs=80kΩ | 0.5–3.0 V |
| TIA | Settling time | < 2 ms |
| TIA | Oscillazioni | < 100 mV pp |
| Fan | V_drain overshoot | < 7 V |
| Fan | V5V ripple | < 200 mV pp |
| Power | V3V3 steady-state | 3.2–3.4 V |
| Power | V3V3 ripple | < 100 mV pp |
| System | ADC noise (cross) | < 50 mV pp |
