#!/usr/bin/env python3
"""
spice_to_kicad.py
Converte netlists SPICE (LTspice) → netlist legacy KiCad 8 (.net)
Progetto: BioSentry IncuSense v2.1

Output: importabile in Pcbnew via File → Import Netlist → KiCad Legacy.
I footprint sono lasciati vuoti: assegnarli in seguito (vedi sezione WORKFLOW).

Uso raccomandato (sub-netlists prima, system_final per ultimo):
    python spice_to_kicad.py \
        heater_driver_v21.net tia_sensor_v21.net \
        gas_flow_controller.net esp32_mcu_v21.net \
        ethernet_power.net system_final_v21.net \
        -o incusense_kicad.net

WORKFLOW post-conversione (come assegnare i footprint):
    Metodo A — CvPcb integrato (rapido per ≤20 tipi):
      Pcbnew → Tools → Assign Footprints
      Filtra per tipo (R, C…) e assegna package a ogni ref.

    Metodo B — Script pcbnew batch (raccomandato):
      Genera uno script Python pcbnew che assegna footprint in massa
      in base a valore/tipo (es. R ≤10k → 0402, R >10k → 0402,
      C 100n → 0402_X7R, C 100u → 1210_elco, …).
      Chiedi: "genera pcbnew_assign_footprints.py per IncuSense"

    Metodo C — Gold standard (nessun workaround):
      Ridisegna il circuito in Eeschema, assegna footprint lì,
      poi usa Pcbnew → "Update PCB from Schematic".

NOTA CONNETTORI:
    I connettori non compaiono in SPICE e devono essere aggiunti
    manualmente in Pcbnew (come footprint standalone) o in Eeschema.
    Vedi sezione "Connettori mancanti" nel report di output.
"""

import argparse
import os
import re
from collections import defaultdict
from datetime import datetime
from typing import Optional

# ─────────────────────────────────────────────────────────────────────────────
# MAPPATURA TIPI SPICE → KICAD
# (tipo_kicad, [nomi_pin nell'ordine SPICE])
# ─────────────────────────────────────────────────────────────────────────────
PIN_MAP = {
    'R': ('R', ['1', '2']),        # node+ node-
    'C': ('C', ['1', '2']),        # node+ node-
    'L': ('L', ['1', '2']),        # node+ node-
    'D': ('D', ['A', 'K']),        # anode cathode
    'Q': ('Q', ['C', 'B', 'E']),   # collector base emitter
    'M': ('M', ['D', 'G', 'S']),   # drain gate source (bulk ignorato)
}

# Suffissi di sistema aggiunti da system_final_v21.net per evitare conflitti.
# NOTA: NON includere _FAN — è parte di nomi legittimi (C1_FAN, M_FAN, etc.)
_SYS_SUFFIXES = ('_SYS', '_SYS2')

# ─────────────────────────────────────────────────────────────────────────────
# ELEMENTI DA ESCLUDERE (non fisici, modelli di simulazione)
# ─────────────────────────────────────────────────────────────────────────────

# Circuito equivalente comportamentale MCP6001 (modello SPICE, non PCB)
_OPA_INTERNALS = {
    'R_OA', 'C_OA', 'R_INP', 'R_INN', 'C_INN', 'R_OUT', 'R_VIN_REF',
}

# Diodi di clamp interni al modello op-amp
_CLAMP_INTERNALS = {'D_CLAMP_H', 'D_CLAMP_L'}

# Impedanze parassite dei convertitori (DCR induttore, Rout LDO, Rc cavo)
_CONVERTER_PARASITICS = {
    'R_BUCK_SER', 'R_LDO_OUT', 'R_SOURCE', 'R_BUCK_OUT',
    'L_BUCK_OUT', 'R_POE_CABLE',
}

# Modello del motore ventola (bobina + resistenza interna: esterni al PCB)
_MOTOR_MODEL = {'R_MOTOR', 'L_MOTOR'}

# Impedenza ingresso ADC del MCU (modello, non componente discreto)
_ADC_MODELS = {'R_ADC0_Z', 'R_ADC2_Z', 'C_ADC0_Z', 'C_ADC2_Z'}

# Sorgenti di carico comportamentali (simulate le correnti dei sotto-sistemi)
_LOAD_MODELS = {
    'R_LOAD_OPA', 'R_LOAD_BIAS', 'R_LOAD_SHT41', 'R_LOAD_ESP',
}

# Capacità parassita sensore TO-39 (non componente PCB separato)
_SENSOR_PARASITICS = {'C_SENSOR'}

# Modello heater switch semplificato in ethernet_power (R parametrica + S_IDEAL)
_HEATER_LOAD_MODEL = {'R_HEATER_LOAD'}

EXCLUDE_REFS: set = (
    _OPA_INTERNALS | _CLAMP_INTERNALS | _CONVERTER_PARASITICS |
    _MOTOR_MODEL | _ADC_MODELS | _LOAD_MODELS |
    _SENSOR_PARASITICS | _HEATER_LOAD_MODEL
)

# ─────────────────────────────────────────────────────────────────────────────
# ELEMENTI FISICAMENTE ESTERNI AL PCB
# Si collegano tramite connettore J1 (zoccolo TO-39)
# ─────────────────────────────────────────────────────────────────────────────
EXTERNAL_REFS: set = {'R_HEATER', 'R_SENSOR'}

# ─────────────────────────────────────────────────────────────────────────────
# WHITELIST system_final_v21.net
#
# system_final usa nomi diversi dai sub-netlists per gli stessi componenti
# (es. R_Q1_BASE vs R_BASE, C_IN_BULK vs C_IN).
# Strategia: da system_final accettiamo SOLO i componenti che NON compaiono
# in nessun sub-netlist — cioè quelli qui elencati.
# Tutto il resto da system_final viene saltato.
# ─────────────────────────────────────────────────────────────────────────────
SYSTEM_FINAL_WHITELIST: set = {
    'L_FERRITE',         # Ferrite bead 1µH sul rail V3V3→V3V3_ANALOG
    'C_FERRITE',         # 10µF filtro analogico
    'C_FERRITE_BYPASS',  # 100nF filtro analogico
    'R_M1_PD',           # Pull-down 10kΩ gate M1 (boot-safe)
}


# ─────────────────────────────────────────────────────────────────────────────
# UTILITÀ
# ─────────────────────────────────────────────────────────────────────────────

_RE_SPICE_VAL = re.compile(
    r'^([+-]?[\d.]+(?:[eE][+-]?\d+)?)([fpnumkMGT])?$'
)
_SPICE_MULT = {
    'f': 1e-15, 'p': 1e-12, 'n': 1e-9, 'u': 1e-6,
    'm': 1e-3,  'k': 1e3,   'M': 1e6,  'G': 1e9, 'T': 1e12,
}


def to_float(s: str) -> Optional[float]:
    """Converte '22k', '100u', '1MEG' in float. None se non parsabile."""
    s = re.sub(r'(?i)meg', 'M', s.strip())
    m = _RE_SPICE_VAL.match(s)
    if not m:
        return None
    return float(m.group(1)) * _SPICE_MULT.get(m.group(2) or '', 1.0)


def strip_sys(ref: str) -> str:
    """Rimuove i suffissi _SYS/_SYS2 aggiunti da system_final per evitare
    conflitti di nomi. NON rimuove _FAN (è parte di nomi reali: C1_FAN, etc.)"""
    upper = ref.upper()
    for s in _SYS_SUFFIXES:
        if upper.endswith(s):
            return ref[: -len(s)]
    return ref


def normalize_net(net: str, aliases: dict) -> str:
    """Risolve alias SPICE. Normalizza '0' (nodo GND SPICE) in 'GND'."""
    net = aliases.get(net, net)
    return 'GND' if net == '0' else net


# ─────────────────────────────────────────────────────────────────────────────
# STRUTTURA COMPONENTE
# ─────────────────────────────────────────────────────────────────────────────

class Component:
    __slots__ = ('ref', 'ktype', 'value', 'model', 'nodes',
                 'pin_names', 'source_file', 'is_external', 'warn')

    def __init__(self, ref, ktype, value, model, nodes, pin_names, source_file):
        self.ref         = ref
        self.ktype       = ktype
        self.value       = value
        self.model       = model
        self.nodes       = nodes
        self.pin_names   = pin_names
        self.source_file = source_file
        self.is_external = False
        self.warn        = ''

    @property
    def display_value(self) -> str:
        v = self.value if self.value else self.model
        return v.replace('"', "'")

    @property
    def description(self) -> str:
        parts = [f"{self.ktype} — {self.source_file}"]
        if self.is_external:
            parts.append("ESTERNO AL PCB: via J1 (TO-39). Non piazzare sul PCB.")
        if self.warn:
            parts.append(self.warn)
        return ' | '.join(parts)


# ─────────────────────────────────────────────────────────────────────────────
# PARSER
# ─────────────────────────────────────────────────────────────────────────────

class SpiceParser:
    def __init__(self):
        self.components: dict  = {}   # ref → Component
        self.params:     dict  = {}   # nome → valore stringa
        self.aliases:    dict  = {}   # alias_net → net_canonico

    # ── Pass 1: raccoglie .param e .alias ────────────────────

    def _collect_directives(self, lines: list):
        for line in lines:
            low = line.lower()
            if low.startswith('.param'):
                for token in line[6:].split():
                    if '=' in token:
                        k, v = token.split('=', 1)
                        self.params[k.strip()] = v.strip()
            elif low.startswith('.alias'):
                parts = line.split()
                if len(parts) >= 3:
                    self.aliases[parts[1]] = parts[2]

    # ── Risolve {param} nei valori ───────────────────────────

    def _resolve(self, val: str) -> str:
        return re.sub(
            r'\{([^}]+)\}',
            lambda m: self.params.get(m.group(1).strip(), m.group(0)),
            val,
        )

    # ── Pass 2: analizza una riga componente ─────────────────

    def _parse_line(self, line: str, filepath: str, is_system_final: bool):
        tokens = line.split()
        if not tokens:
            return

        raw_ref = tokens[0]
        prefix  = raw_ref[0].upper()

        if prefix not in PIN_MAP:
            return  # sorgente, behavioral, switch… → salta

        ktype, base_pins = PIN_MAP[prefix]
        ref = strip_sys(raw_ref)

        # Escludi modelli interni noti
        if ref in EXCLUDE_REFS:
            return

        # Da system_final: accetta SOLO i componenti in whitelist
        if is_system_final and ref not in SYSTEM_FINAL_WHITELIST:
            return

        # Deduplicazione: se già presente, salta
        if ref in self.components:
            return

        # ── Estrai nodi, valore e modello ──
        try:
            if prefix in ('R', 'C', 'L'):
                if len(tokens) < 4:
                    return
                nodes_raw = [tokens[1], tokens[2]]
                value     = self._resolve(tokens[3])
                model     = ''
                pin_names = list(base_pins)

            elif prefix == 'D':
                if len(tokens) < 4:
                    return
                nodes_raw = [tokens[1], tokens[2]]
                value     = ''
                model     = tokens[3]
                pin_names = list(base_pins)

            elif prefix == 'Q':
                # Q name Collector Base Emitter model
                if len(tokens) < 5:
                    return
                nodes_raw = [tokens[1], tokens[2], tokens[3]]
                value     = ''
                model     = tokens[4]
                pin_names = list(base_pins)

            elif prefix == 'M':
                # M name Drain Gate Source Bulk model [params…]
                # tokens: [ref, D, G, S, B, model, W=…, L=…]
                if len(tokens) < 6:
                    return
                nodes_raw = [tokens[1], tokens[2], tokens[3]]
                model     = tokens[5]   # tokens[4] = bulk (ignorato)
                value     = ''
                pin_names = list(base_pins)

            else:
                return

        except IndexError:
            return

        # Normalizza net names (risolvi alias, 0→GND)
        nodes = [normalize_net(n, self.aliases) for n in nodes_raw]

        # Salta se i nodi contengono reti interne del modello (_INT)
        if any('_INT' in n for n in nodes):
            return

        # ── Crea componente ──
        comp = Component(
            ref=ref, ktype=ktype, value=value, model=model,
            nodes=nodes, pin_names=pin_names,
            source_file=os.path.basename(filepath),
        )

        # Marca elementi esterni al PCB
        if ref in EXTERNAL_REFS:
            comp.is_external = True

        # Flag per valori anomali (residui di modello non in EXCLUDE_REFS)
        if value and not comp.is_external:
            fval = to_float(value)
            if fval is not None:
                if prefix == 'R' and fval >= 1e6:
                    comp.warn = f'R={value} (>1MΩ) — verificare se modello SPICE'
                elif prefix == 'R' and fval < 0.1:
                    comp.warn = f'R={value} (<100mΩ) — verificare se modello SPICE'
                elif prefix == 'C' and fval > 0.01:
                    comp.warn = f'C={value} (>10mF) — verificare se modello SPICE'

        self.components[ref] = comp

    # ── Parsing di un intero file ─────────────────────────────

    def parse_file(self, filepath: str):
        fname           = os.path.basename(filepath)
        is_system_final = 'system_final' in fname.lower()

        with open(filepath, 'r', encoding='utf-8', errors='replace') as f:
            raw = f.readlines()

        # Ricostruisce righe SPICE con continuazione '+' in colonna 0
        lines: list = []
        buf = ''
        for r in raw:
            s = r.rstrip()
            if s.startswith('+'):
                buf += ' ' + s[1:].strip()
            else:
                if buf:
                    lines.append(buf)
                buf = s
        if buf:
            lines.append(buf)

        # Pass 1: .param e .alias
        self._collect_directives(lines)

        # Pass 2: componenti (salta commenti * e direttive .)
        for line in lines:
            if not line or line.startswith('*') or line.startswith('.'):
                continue
            self._parse_line(line, filepath, is_system_final)

    # ── Costruisce mappa net → [(ref, pin)] ──────────────────

    def build_nets(self) -> dict:
        nets: dict = defaultdict(list)
        for comp in self.components.values():
            for pin, net in zip(comp.pin_names, comp.nodes):
                nets[net].append((comp.ref, pin))
        # Mantieni solo net con ≥2 connessioni (rimuove dangling nodes)
        return {n: sorted(v) for n, v in nets.items() if len(v) > 1}


# ─────────────────────────────────────────────────────────────────────────────
# GENERATORE NETLIST KICAD LEGACY
# ─────────────────────────────────────────────────────────────────────────────

def _ts(i: int) -> str:
    return f"{i:08X}"


def generate_netlist(
    parser:       'SpiceParser',
    nets:         dict,
    output_path:  str,
    source_files: list,
) -> int:
    comps      = sorted(parser.components.values(), key=lambda c: (c.ktype, c.ref))
    nets_items = sorted(nets.items())
    sources    = ', '.join(os.path.basename(f) for f in source_files)

    out = [
        '(export (version "E")',
        '  (design',
        '    (source "BioSentry_IncuSense_v2.1")',
        f'    (date "{datetime.now().strftime("%Y-%m-%d %H:%M")}")',
        '    (tool "spice_to_kicad.py — IncuSense")',
        f'    (comment1 "Sorgenti: {sources}")',
        '    (comment2 "Footprint VUOTI — assegnare via CvPcb o pcbnew_assign_footprints.py")',
        '    (comment3 "Pin: R/C/L=1,2 | D=A,K | BJT=C,B,E | MOSFET=D,G,S")',
        '    (comment4 "Connettori mancanti: J_PWR J_ETH J1 J_FAN J_UART H_ESP32 H_W5500 — aggiungere manualmente")',
        '    (comment5 "Layout: J1+SHT41 su lato INFERIORE PCB (camera); TIA+ESP32+power su lato SUPERIORE (asciutto)")',
        '  )',
        '  (components',
    ]

    for i, comp in enumerate(comps):
        out += [
            f'    (comp (ref "{comp.ref}")',
            f'      (value "{comp.display_value}")',
            f'      (footprint "")',
            f'      (description "{comp.description}")',
            f'      (fields)',
            f'      (libsource (lib "Device") (part "{comp.ktype}") (description ""))',
            f'      (sheetpath (names "/") (tstamps "/{_ts(i)}"))',
            f'      (tstamp "{_ts(i + 0x2000)}"))',
        ]

    out += ['  )', '  (libparts)', '  (libraries)', '  (nets']

    for code, (net_name, nodes) in enumerate(nets_items, 1):
        out.append(f'    (net (code "{code}") (name "{net_name}")')
        for ref, pin in nodes:
            out.append(f'      (node (ref "{ref}") (pin "{pin}"))')
        out.append('    )')

    out += ['  )', ')']

    with open(output_path, 'w', encoding='utf-8') as f:
        f.write('\n'.join(out) + '\n')

    return len(comps)


# ─────────────────────────────────────────────────────────────────────────────
# REPORT
# ─────────────────────────────────────────────────────────────────────────────

def print_report(parser: 'SpiceParser', nets: dict, output_path: str, n_total: int):
    comps    = parser.components
    W        = 64
    by_type  = defaultdict(list)
    external = []
    warnings = []

    for ref, c in comps.items():
        by_type[c.ktype].append(ref)
        if c.is_external:
            external.append(ref)
        if c.warn:
            warnings.append((ref, c.warn))

    labels = {
        'R': 'Resistori',
        'C': 'Condensatori',
        'L': 'Induttori / Ferrite',
        'D': 'Diodi',
        'Q': 'BJT',
        'M': 'MOSFET',
    }

    print('\n' + '═' * W)
    print('  REPORT — BioSentry IncuSense → KiCad Netlist')
    print('═' * W)
    print(f'\n  ✅ Componenti PCB trovati: {n_total}')

    for ktype in sorted(by_type):
        refs  = sorted(by_type[ktype])
        label = labels.get(ktype, ktype)
        n     = len(refs)
        line  = ', '.join(refs)
        # Stampa con wrapping se troppo lungo
        prefix_str = f'    {label:22s} ({n:2d}):  '
        indent     = ' ' * len(prefix_str)
        while len(line) > 44:
            cut = line[:44].rfind(',')
            if cut < 0:
                cut = 44
            print(prefix_str + line[:cut + 1])
            prefix_str = indent
            line = line[cut + 2:].lstrip()
        print(prefix_str + line)

    print(f'\n  ✅ Net (≥ 2 nodi): {len(nets)}')
    print('\n  Net critiche del progetto:')

    critical = {
        'GND':              'Piano di massa',
        'V12V_PROT':        'Rail 12V protetto (TVS+Polyfuse)',
        'V5V_OUT':          'Rail 5V Buck AP63203',
        'V3V3':             'Rail 3.3V LDO AP2112K',
        'V3V3_ANALOG_MID':  'Rail 3.3V analogico (filtrato ferrite)',
        'HEAT_MINUS':       'Nodo switching heater 1.2A PWM',
        'TIA_OUT':          'Uscita TIA → GPIO0 ADC',
        'ELEC_B':           'Ingresso inv. TIA (100MΩ, rumore critico)',
        'REF_NODE':         'Nodo riferimento heater (Rref)',
    }
    for net, desc in critical.items():
        n     = nets.get(net, [])
        stato = f'{len(n):2d} nodi' if n else '⚠  non trovata'
        print(f'    {net:26s}  {stato:10s}  {desc}')

    if external:
        print(f'\n  ⚠  Elementi ESTERNI al PCB (collegati via J1):')
        for ref in sorted(external):
            c = comps[ref]
            print(f'     {ref:15s} ({c.display_value}) → '
                  'da sostituire con simbolo connettore in Eeschema')

    if warnings:
        print(f'\n  ⚠  Flag di verifica (valori anomali):')
        for ref, msg in sorted(warnings):
            if ref not in external:
                print(f'     {ref:20s}  {msg}')

    print(f'\n  ⚠  Connettori mancanti (non simulati in SPICE):')
    connectors = [
        ('J_PWR',   '12V DC input (dal PoE splitter PoE)'),
        ('J_ETH',   'RJ45 Ethernet con magnetics integrati'),
        ('J1',      'TO-39 socket sensore CO₂ — lato inferiore PCB (PCB come coperchio)'),
        ('J_FAN',   '2-pin JST-SH, ventola brushless nella camera di flusso'),
        ('J_UART',  'Debug UART Tag-Connect 1.27mm'),
        ('H_ESP32', 'Header 2.54mm ESP32-C3-MINI-1'),
        ('H_W5500', 'Header 2.54mm W5500 Lite'),
    ]
    for j, desc in connectors:
        print(f'     {j:8s}  {desc}')
    print('     → Pcbnew: Place → Add Footprint (standalone, senza nets)')
    print('       oppure aggiungerli in Eeschema prima di "Update PCB"')

    print(f'\n  ✅ Output: {output_path}')
    print('  → Pcbnew: File → Import Netlist → KiCad → seleziona il file')
    print('  → Poi: Tools → Assign Footprints (CvPcb) per ogni componente')
    print()
    print('═' * W + '\n')


# ─────────────────────────────────────────────────────────────────────────────
# MAIN
# ─────────────────────────────────────────────────────────────────────────────

def main() -> int:
    ap = argparse.ArgumentParser(
        description='Converte netlists SPICE LTspice → formato legacy KiCad 8.',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=(
            'Ordine file: sub-netlists prima, system_final.net per ultimo.\n'
            'system_final ha regole speciali (whitelist): solo L_FERRITE,\n'
            'C_FERRITE, C_FERRITE_BYPASS e R_M1_PD vengono aggiunti da esso.\n\n'
            'Esempio completo:\n'
            '  python spice_to_kicad.py \\\n'
            '    heater_driver_v21.net tia_sensor_v21.net \\\n'
            '    gas_flow_controller.net esp32_mcu_v21.net \\\n'
            '    ethernet_power.net system_final_v21.net \\\n'
            '    -o incusense_kicad.net'
        ),
    )
    ap.add_argument('inputs', nargs='+', metavar='FILE.net',
                    help='File .net SPICE di input')
    ap.add_argument('-o', '--output', default='incusense_kicad.net',
                    help='File di output (default: incusense_kicad.net)')
    args = ap.parse_args()

    # Garantisce che system_final sia parsato per ultimo
    inputs = sorted(
        args.inputs,
        key=lambda p: 1 if 'system_final' in os.path.basename(p).lower() else 0,
    )

    parser = SpiceParser()
    found:  list = []

    print(f'\n  Parsing {len(inputs)} file(s)…')
    for fpath in inputs:
        if not os.path.isfile(fpath):
            print(f'  ⚠  {fpath}: non trovato, saltato')
            continue
        print(f'    → {os.path.basename(fpath)}')
        parser.parse_file(fpath)
        found.append(fpath)

    if not found:
        print('  Errore: nessun file trovato.')
        return 1

    nets    = parser.build_nets()
    n_comps = generate_netlist(parser, nets, args.output, found)
    print_report(parser, nets, args.output, n_comps)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
