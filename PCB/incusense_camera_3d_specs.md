# IncuSense — Specifiche Camera di Flusso 3D

**Versione:** 1.0 — basata sull'analisi architetturale definitiva (architettura B: PCB come coperchio, getto impingente bottom-to-top)
**Data:** 2025-05-29

---

## 1. Principio di funzionamento

L'aria dell'incubatore entra dal basso attraverso un filtro PTFE idrofobico e un ugello che la proietta
come **getto verticale ascendente** (bottom-to-top) sul coperchio forato del sensore CO₂ TO-39, che
pende dal PCB con la faccia sensibile rivolta verso il basso. L'impatto è **ortogonale** alla mesh forata.
Dopo l'impingement l'aria si espande radialmente, esce da una bocca laterale e viene aspirata dalla
ventola (a valle = configurazione pull). Il PCB funge da coperchio della camera: **tutta l'elettronica**
(TIA, ESP32, alimentazione) sta sul **lato asciutto superiore**; solo lo zoccolo TO-39 e l'SHT41
penetrano verso il basso nella camera umida.

```
      [ ELETTRONICA — lato asciutto ]
      ════════════ PCB ═══════════════  ← coperchio
          ↓ TIA     ↓ CO₂ (TO-39)
                    ||  ← leads
                   [■■] ← cap forato rivolto in basso
                   |  |
         [SHT41]   |  | ← can riscaldato 150–300°C
                   ↑  ↑  ← getto impingente
                   ↑  ↑
      ─────────────┼──┼──── ← bottom cap camera (nozzle Ø4mm)
      ─── PTFE filter ──── ← ingresso dal basso
               aria incubatore
      ←──── outlet laterale → ventola (pull) → scarico
```

---

## 2. Geometria della camera

### 2.1 Dimensioni interne principali

| Parametro | Valore | Note |
|---|---|---|
| Forma | Cilindro verticale | Con raccordi toroidali sugli spigoli interni |
| Diametro interno | **22 mm** | Sufficiente per TO-39 (Ø9.14mm) + SHT41 (9mm offset) + margine |
| Altezza interna | **32 mm** | Dalla faccia inferiore del PCB al bottom cap interno |
| Volume camera | **≈ 12.1 cm³** | π × 11² × 32 mm |
| Spessore parete | **1.5 mm** | Minimo per stampa FDM; aumentare a 2mm se SLA |
| Diametro esterno | **25 mm** | Parete + diametro interno |

### 2.2 Posizione dei sensori nella camera

| Sensore | Distanza dal PCB (verso il basso) | Offset radiale | Note |
|---|---|---|---|
| TO-39 cap (faccia sensibile) | **10 mm** | 0 mm (asse centrale) | Socket Ø10mm su PCB bottom face |
| SHT41 — Opzione A (prototipo) | **1 mm** (su PCB bottom face) | **9 mm** dall'asse CO₂ | Direttamente sul PCB underside; ±2°C errore T accettabile |
| SHT41 — Opzione B (produzione) | **30 mm** (nel dotto inlet) | 0–4 mm | Nel dotto di ingresso, pre-riscaldamento; ±0.2°C |

**Raccomandazione per prototipo:** Opzione A. SHT41 sul PCB underside a 9mm dal CO₂, con slot termico
da 0.8mm tra le due isole. L'errore di temperatura (~1-2°C per riscaldamento del can) è accettabile
per il monitoraggio dell'incubatore; il CO₂ è la misura critica.

**Per produzione:** Opzione B. SHT41 nel dotto di ingresso (pre-filtro), 4-pin JST-SH al PCB.

### 2.3 Interfaccia PCB (top / coperchio)

| Parametro | Valore | Note |
|---|---|---|
| Gola O-ring | ID 20 mm, OD 23 mm, profondità 1.2 mm | Per O-ring Ø1.5 mm sezione (EPDM o silicone) |
| Foro clearance TO-39 nel PCB | **Ø 10 mm** | Il can TO-39 (Ø9.14mm) deve passare con clearance |
| Fori di fissaggio | 3× M2.5 su bolt circle Ø28mm a 120° | Oppure 3× snap-fit tab (più semplice da stampare) |
| Accesso SHT41 (opt. A) | Nessuno — SHT41 sul PCB, esposto al lato camera | — |
| Accesso SHT41 (opt. B) | 4-pin SMD connector (JST-SH 1mm) sul PCB underside | J_SHT41 da aggiungere |

### 2.4 Ugello di ingresso (bottom cap)

| Parametro | Valore | Note |
|---|---|---|
| Diametro gola ugello | **4 mm** | Crea il getto impingente |
| Tipo | Orifizio cilindrico (sharp-edge) | Spessore 2mm — sufficiente per il getto |
| Distanza ugello → cap TO-39 | **22 mm** | H/D = 22/4 = 5.5 ✓ (ottimale per impingement: 4–8) |
| Raccordo di ingresso all'ugello | Svasatura da Ø8mm → Ø4mm su 4mm | Riduce separazione del getto |
| Seat filtro PTFE | Luer-Lock femmina o push-fit Ø13mm | Sotto l'ugello; per disco filtro 13mm |

### 2.5 Bocca di uscita (outlet laterale)

| Parametro | Valore | Note |
|---|---|---|
| Diametro | **6 mm** | Velocità uscita ≈ 0.88 m/s @ 1.5 L/min |
| Posizione verticale | 4 mm dal bordo superiore (sotto PCB) | Zona post-impingement radiale |
| Posizione angolare | 90° o 180° rispetto all'asse di montaggio preferito | Da definire con il layout meccanico finale |
| Adattatore ventola | Flangia 30×30mm con 4× M2 sul quadrato 24mm | Standard per ventola 25–30mm |

---

## 3. Calcoli fluidici

### 3.1 Portata nominale e volume morto

```
Q_nom  = 1.5 L/min  = 25,000 mm³/s  = 25 cm³/s   (punto di progetto)
Q_min  = 1.0 L/min  (flusso costante a duty ridotto)
Q_max  = 2.5 L/min  (duty massimo ventola)

V_camera = 12,100 mm³ = 12.1 cm³

Tempo di ricambio:
  t_ricambio = V / Q = 12,100 / 25,000 = 0.48 s @ 1.5 L/min
             = 0.73 s @ 1.0 L/min

→ L'aria della camera viene ricambiata ~2 volte al secondo al punto nominale.
  Il tempo di risposta del sensore MOx (decine di secondi) NON è limitato
  dal ricambio d'aria: il collo di bottiglia è il sensore stesso. ✓
```

### 3.2 Getto impingente (nozzle Ø4mm)

```
v_getto = Q / A_nozzle = 25,000 / (π × 4) = 1,990 mm/s ≈ 2.0 m/s

Numero di Reynolds al nozzle (aria 37°C, ν = 1.61×10⁻⁵ m²/s):
  Re = v × D / ν = 2.0 × 0.004 / 1.61e-5 = 497 → laminare ✓ (Re ≪ 2300)

Rapporto standoff:
  H = 22 mm (nozzle → cap TO-39)
  D = 4 mm  (diametro nozzle)
  H/D = 5.5  → ottimale per impingement uniforme (range 4–8) ✓

Velocità in camera (zona annulare tra can e parete):
  A_annulus = π/4 × (22² - 9.14²) = π/4 × (484 - 83.5) = 314 mm²
  v_annulus = 25,000 / 314 ≈ 80 mm/s = 0.08 m/s → molto lenta, laminare ✓

Velocità uscita laterale (bocca Ø6mm):
  v_outlet = 25,000 / (π × 9) = 884 mm/s ≈ 0.88 m/s
```

### 3.3 Stima perdita di carico

La perdita di carico totale determina se la ventola è adeguata.

```
ΔP_filtro  : dipende dalla membrana PTFE — vedi sezione 6.
             Per membrana 1.0 µm, Ø13mm eff. area ≈ 1.0 cm²:
             Stima 20–200 Pa @ 1.5 L/min (da verificare con datasheet)

ΔP_nozzle  : Bernoulli sharp-edge orifice:
             ΔP = ρ/2 × v² / Cd²  (Cd ≈ 0.61)
             = 1.13/2 × (2.0)² / 0.61² = 3.04 Pa  (trascurabile)

ΔP_camera  : perdite distribuite, laminare → trascurabile

ΔP_TOTALE  ≈ 20–200 Pa (dominato dal filtro PTFE)

Verifica ventola: le ventole brushless 25–30mm a 5V forniscono tipicamente
  30–80 Pa di pressione statica a portata zero. Al punto nominale 1.5 L/min
  la pressione disponibile è ~15–50 Pa.

⚠️ AZIONE RICHIESTA: verificare ΔP_filtro con il datasheet della membrana
  PTFE scelta (Pall Acro 50 o equivalente) a 1.5 L/min.
  Se ΔP_filtro > 30 Pa: aumentare l'area effettiva del filtro (Ø13mm → Ø25mm)
  o aumentare la porosità a 1.0 µm.
```

---

## 4. Specifiche PCB (layout — lato camera)

### 4.1 Slot termico intorno al sensore CO₂

Lo slot interrompe la conduzione termica dal can (150–300°C) verso il TIA e l'SHT41.

| Parametro | Valore | Note |
|---|---|---|
| Larghezza slot | **0.8 mm** | Minimo fresatura standard (end-mill Ø0.8mm) |
| Forma | U rovesciata o anello parziale | Lasciare ponti di 0.8mm ai 4 lead del TO-39 per supporto meccanico |
| Raggio del percorso | 6 mm dal centro del foro TO-39 | Isola l'anello di rame attorno al socket |
| Strati interessati | Tutti (L1–L4) | Fresare passante, non solo surface |
| Numero di ponti | 4 | Uno per lead, larghezza 0.8 mm ciascuno |

Riferimento: tecnica documentata per MOx con micro-heater su PCB
(PMC10532496: weak thermal coupling package TO ↔ PCB con lead lunghi;
lo slot aumenta ulteriormente l'isolamento).

### 4.2 Guard ring sul nodo ELEC_B

Il nodo ad alta impedenza (100 MΩ) del TIA è sul lato asciutto (top face).
Proteggerlo con un anello di guardia collegato a un potenziale stabile (ad es. V3V3/2 o output TIA):

| Parametro | Valore |
|---|---|
| Layer | L1 (top face, dove sta il TIA) |
| Larghezza | 0.5 mm |
| Clearance dal nodo ELEC_B | 0.3 mm |
| Connessione | Buffer dedicato (o semplicemente GND analogico come approssimazione) |

### 4.3 Fori nel PCB per la camera

| Foro | Diametro | Posizione | Scopo |
|---|---|---|---|
| TO-39 clearance | **Ø 10.0 mm** | Centro zona analogica | Passa il can del sensore |
| M2.5 fissaggio camera | Ø 2.7 mm (3 pz.) | Bolt circle Ø28mm @ 120° | Viti o inserti |
| SHT41 opt. B port | Ø 3 mm (opz.) | Allineato al dotto inlet | Passaggio connettore se SHT41 nel dotto |

---

## 5. Specifiche ventola

| Parametro | Valore | Fonte |
|---|---|---|
| Formato | 25×25×10 mm o 30×30×10 mm | Hardware.tex (Sunon MF25101V1 o equiv.) |
| Tensione | 5V DC | Rail V5V_OUT del sistema |
| Corrente | 40–100 mA | A piena velocità |
| Portata | 1–3 L/min (da verificare con camera) | |
| Protezione | IP44 o superiore | Ambiente 95% RH |
| Controllo | PWM 25kHz da GPIO1 (Si2302 driver) | Già in firmware |
| Connettore | **J_FAN** — 2-pin JST-SH 1mm | Aggiunto allo script |
| Posizione | Flangia sulla bocca outlet laterale | Configurazione pull (a valle) |
| Orientamento | Asse ventola allineato alla bocca outlet | Aspira fuori dalla camera |

**Duty nominale:** impostare con firmware a valore fisso che garantisca Q ≈ 1.5 L/min.
Caratterizzare con misura gravimetrica o anemometro a filo caldo durante validazione.

---

## 6. Specifiche filtro PTFE

| Parametro | Valore |
|---|---|
| Tipo | Membrana PTFE idrofobica |
| Porosità raccomandata | **1.0 µm** (meno ΔP di 0.2 µm, blocca comunque goccioline) |
| Diametro efficace minimo | **13 mm** (disco syringe filter) — verificare ΔP |
| Diametro preferito | **25 mm** (riduce ΔP, più sicuro per la ventola) |
| Fornitori di riferimento | Pall Acro 50, Millipore Millex, Advantec, Sterlitech |
| Housing | Seat Luer-Lock femmina nel bottom cap camera |
| Posizione | Prima del nozzle, nell'inlet inferiore |
| Sostituzione | Accessibile dal basso senza smontare il modulo |

---

## 7. Materiale e note di fabbricazione

### 7.1 Materiale raccomandato

| Materiale | Tg | Pro | Contro | Raccomandazione |
|---|---|---|---|---|
| **PC (policarbonato)** | 147°C | Resistente al calore, trasparente, stampabile FDM/SLA | Richiede FDM con nozzle >260°C o SLA con resina engineering | ✅ Prima scelta per produzione |
| **ABS** | 100–105°C | Stampabile con printer standard, buona resistenza | Può deformarsi se la camera si scalda eccessivamente | ✅ OK per prototipo se slot + airflow abbassano T parete |
| **PETG** | 80–85°C | Facile da stampare | Tg troppo bassa se zona del sensore supera 80°C in condizioni peggiori | ⚠️ Solo se confermato termicamente |
| PTFE | 327°C | Chimicamente inerte, bassa ΔP | Non stampabile FDM standard | Per produzione high-end |

**Raccomandazione prototipo:** ABS con slot termico + airflow forzato che limita la temperatura della
parete a ~50–60°C (ben al di sotto di Tg ABS). Verificare con termometro IR durante funzionamento.

### 7.2 Note FDM

- Strati: orientare la camera con l'asse verticale allineato alla direzione Z → pareti lisce all'interno
- Layer height: 0.15–0.20 mm per buona tenuta
- Riempimento: 25–30% sufficienti (non è pezzo strutturale)
- Sealing: applicare PTFE tape o silicone RTV all'interfaccia PCB-camera se l'O-ring non garantisce tenuta
- Finish: levigare leggermente l'interno della camera per ridurre rugosità (riduce zone di stagnazione)

---

## 8. Modifiche BOM (componenti aggiuntivi della camera)

| Riferimento | Descrizione | Quantità | Note |
|---|---|---|---|
| `J_FAN` | Connettore JST-SH 1mm 2-pin (SMD o TH) | 1 | Per ventola brushless |
| `J_SHT41` | Connettore JST-SH 1mm 4-pin (opt. B only) | 1 | Se SHT41 nel dotto inlet |
| `FILTER_PTFE` | Membrana PTFE 1.0µm, Ø13 o 25mm | 2 (1 spare) | Pall Acro / equiv. |
| `ORING_1` | O-ring Ø20×1.5mm (sezione), silicone | 2 (1 spare) | Tenuta PCB-camera |
| `M2.5x6` | Viti M2.5 × 6mm testa cilindrica | 3 | Fissaggio camera |
| `CAMERA_3D` | Camera di flusso 3D, PC o ABS | 1 | Produzione interna |
| `STANDOFF_TO39` | Standoff TO-39 socket (se necessario) | 1 | Aumenta gap aria tra can e PCB |

---

## 9. Decisioni aperte / verifiche richieste

| # | Questione | Azione richiesta | Priorità |
|---|---|---|---|
| 1 | ΔP filtro PTFE a 1.5 L/min | Misurare o richiedere curva dal datasheet — verificare che la ventola scelta eroghi pressione sufficiente | 🔴 Alta |
| 2 | Posizione SHT41 — Opzione A vs B | Decisione di progetto: accettare ±2°C (prototipo) o aggiungere dotto inlet (produzione) | ⚠️ Media |
| 3 | Temperatura parete camera vicino TO-39 | Misurare con termometro IR a regime — se > Tg_ABS → passare a PC | ⚠️ Media |
| 4 | Duty PWM ventola per Q = 1.5 L/min | Caratterizzare portata vs duty durante testing | ⚠️ Media |
| 5 | Closed-loop heater (temperatura Pt) | Verificare che il firmware implementi controllo PID/bangbang sulla temperatura Pt per compensare il raffreddamento del can da parte del flusso d'aria | ⚠️ Media |
| 6 | Tenuta O-ring all'interfaccia PCB-camera | Test di tenuta con fumogeno o tracciante a 95% RH per escludere perdite di gas | ⚠️ Media |
