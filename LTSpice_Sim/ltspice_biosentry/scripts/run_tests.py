#!/usr/bin/env python3
"""
BioSentry IncuSense — Test Suite LTSpice via PyLTSpice
======================================================
Esegue simulazioni SPICE dei 4 sottocircuiti + sistema integrato,
analizza i risultati e genera report con grafici.

Requisiti:
    pip install PyLTSpice matplotlib numpy
    LTSpice installato (Windows: default path, Linux: wine + LTSpice)

Uso:
    python run_tests.py                    # Esegue tutti i test
    python run_tests.py --circuit heater   # Singolo sottocircuito
    python run_tests.py --plot-only        # Solo grafici da .raw esistenti
"""

import os
import sys
import argparse
import logging
from pathlib import Path
from dataclasses import dataclass, field
from typing import Optional

import numpy as np

# --- Tentativo import PyLTSpice ---
try:
    from PyLTSpice import SimRunner, SpiceEditor, RawRead
    PYLTSPICE_AVAILABLE = True
except ImportError:
    PYLTSPICE_AVAILABLE = False
    print("[WARN] PyLTSpice non installato. Modalità dry-run attiva.")
    print("       pip install PyLTSpice")

try:
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from matplotlib.gridspec import GridSpec
    MATPLOTLIB_AVAILABLE = True
except ImportError:
    MATPLOTLIB_AVAILABLE = False
    print("[WARN] matplotlib non installato. Grafici disabilitati.")

# --- Configurazione ---
logging.basicConfig(level=logging.INFO, format="%(levelname)s | %(message)s")
log = logging.getLogger("biosentry_test")

BASE_DIR = Path(__file__).parent.parent  # ltspice_biosentry/
NETLIST_DIR = BASE_DIR / "netlists"
OUTPUT_DIR = BASE_DIR / "output"
OUTPUT_DIR.mkdir(exist_ok=True)


# ══════════════════════════════════════════════════════════════
# Definizione criteri di accettazione (pass/fail)
# ══════════════════════════════════════════════════════════════

@dataclass
class TestCriteria:
    """Soglie di accettazione per ogni test."""
    name: str
    parameter: str
    min_val: Optional[float] = None
    max_val: Optional[float] = None
    unit: str = ""

    def check(self, value: float) -> bool:
        ok = True
        if self.min_val is not None and value < self.min_val:
            ok = False
        if self.max_val is not None and value > self.max_val:
            ok = False
        return ok

    def report(self, value: float) -> str:
        status = "PASS ✅" if self.check(value) else "FAIL 🔴"
        bounds = ""
        if self.min_val is not None:
            bounds += f"min={self.min_val}{self.unit} "
        if self.max_val is not None:
            bounds += f"max={self.max_val}{self.unit}"
        return f"  {status} {self.parameter}: {value:.4g} {self.unit} [{bounds.strip()}]"


# Criteri per ogni sottocircuito
CRITERIA = {
    "heater_driver": [
        TestCriteria("Heater", "Transition time supply->meas", max_val=1e-3, unit="s"),
        TestCriteria("Heater", "P_heater @R=18Ohm (250°C)", min_val=0.5, max_val=1.5, unit="W"),
        TestCriteria("Heater", "I_heater_cold_peak @R=10Ohm", max_val=1.5, unit="A"),
    ],
    "tia_sensor": [
        TestCriteria("TIA", "V_out @Rs=80k (5%CO2)", min_val=0.5, max_val=3.0, unit="V"),
        TestCriteria("TIA", "V_out @Rs=2M (aria)", min_val=0.01, max_val=0.5, unit="V"),
        TestCriteria("TIA", "Settling time", max_val=2e-3, unit="s"),
        TestCriteria("TIA", "Stability (no oscillation)", max_val=0.1, unit="V_pp"),
    ],
    "gas_flow": [
        TestCriteria("Fan", "V_drain overshoot", max_val=7.0, unit="V"),
        TestCriteria("Fan", "V5V ripple", max_val=0.2, unit="V_pp"),
        TestCriteria("Fan", "I_flyback peak", max_val=0.5, unit="A"),
    ],
    "ethernet_power": [
        TestCriteria("Power", "V5V steady-state", min_val=4.7, max_val=5.3, unit="V"),
        TestCriteria("Power", "V3V3 steady-state", min_val=3.2, max_val=3.4, unit="V"),
        TestCriteria("Power", "V3V3 ripple", max_val=0.1, unit="V_pp"),
        TestCriteria("Power", "V3V3 drop during W5500 burst", min_val=3.0, unit="V"),
    ],
    "system_integrated": [
        TestCriteria("System", "ADC_OUT ripple (cross-noise)", max_val=0.05, unit="V_pp"),
        TestCriteria("System", "V3V3 ripple (all loads)", max_val=0.15, unit="V_pp"),
        TestCriteria("System", "Total power from 12V", max_val=5.0, unit="W"),
    ],
}


# ══════════════════════════════════════════════════════════════
# Classe principale di test
# ══════════════════════════════════════════════════════════════

class BioSentryTestRunner:
    """Esegue simulazioni LTSpice e analizza i risultati."""

    def __init__(self, ltspice_path: Optional[str] = None):
        self.results = {}
        self.runner = None

        if PYLTSPICE_AVAILABLE:
            if ltspice_path:
                self.runner = SimRunner(output_folder=str(OUTPUT_DIR),
                                       simulator=ltspice_path)
            else:
                try:
                    self.runner = SimRunner(output_folder=str(OUTPUT_DIR))
                except Exception as e:
                    log.warning(f"SimRunner init fallito: {e}")
                    log.warning("Specificare --ltspice-path con il percorso di LTSpice")

    # ──────────────────────────────────────────────────────
    # Esecuzione simulazione
    # ──────────────────────────────────────────────────────

    def run_simulation(self, netlist_name: str) -> Optional[Path]:
        """Esegue una simulazione e ritorna il path del file .raw."""
        netlist_path = NETLIST_DIR / f"{netlist_name}.net"
        if not netlist_path.exists():
            log.error(f"Netlist non trovato: {netlist_path}")
            return None

        log.info(f"{'=' * 60}")
        log.info(f"Simulazione: {netlist_name}")
        log.info(f"{'=' * 60}")

        if self.runner is None:
            log.warning("SimRunner non disponibile. Generando solo analisi teorica.")
            return self._generate_theoretical_results(netlist_name)

        try:
            # Copia netlist nella cartella di output per LTSpice
            import shutil
            dest = OUTPUT_DIR / f"{netlist_name}.net"
            shutil.copy2(netlist_path, dest)

            # Esegui simulazione
            self.runner.run(str(dest))
            self.runner.wait_completion()

            # Ricerca intelligente del file .raw generato
            raw_candidates = list(OUTPUT_DIR.glob(f"{netlist_name}*.raw"))

            # --- FIX BUG LTSPICE .STEP ---
            # Se la netlist usa .step, LTspice ignora la cartella di output e salva in CWD
            if not raw_candidates:
                cwd_candidates = list(Path.cwd().glob(f"{netlist_name}*.raw"))
                for rogue_file in cwd_candidates:
                    import shutil
                    dest_file = OUTPUT_DIR / rogue_file.name
                    shutil.move(str(rogue_file), str(dest_file))
                    raw_candidates.append(dest_file)

                # Sposta anche i .log orfani per pulizia
                for rogue_log in Path.cwd().glob(f"{netlist_name}*.log"):
                    shutil.move(str(rogue_log), str(OUTPUT_DIR / rogue_log.name))
            # ------------------------------

            # FILTRO: Escludiamo i finti .raw generati dai log (bug LTspice 24)
            valid_raws = [f for f in raw_candidates if
                          not f.name.endswith('.log.raw') and not f.name.endswith('.op.raw')]

            if valid_raws:
                # Prende il file più recente tra quelli validi
                raw_file = max(valid_raws, key=os.path.getmtime)
                log.info(f"Simulazione completata con successo: {raw_file}")
                return raw_file
            else:
                log.error(f"Errore: Il file .raw per {netlist_name} non è stato generato.")
                return None

        except Exception as e:
            log.error(f"Errore critico durante la simulazione: {e}")
            return self._generate_theoretical_results(netlist_name)

    # ──────────────────────────────────────────────────────
    # Analisi risultati da file .raw
    # ──────────────────────────────────────────────────────

    def analyze_raw(self, raw_path: Path, circuit_name: str) -> dict:
        """Legge un file .raw di LTSpice e estrae le metriche."""
        if not PYLTSPICE_AVAILABLE or raw_path is None:
            return {}

        try:
            raw = RawRead(str(raw_path))
            time = np.array(raw.get_trace("time").get_wave())
            traces = {}

            for trace_name in raw.get_trace_names():
                try:
                    traces[trace_name] = np.array(
                        raw.get_trace(trace_name).get_wave()
                    )
                except Exception:
                    pass

            return self._extract_metrics(time, traces, circuit_name)

        except Exception as e:
            log.error(f"Errore lettura .raw: {e}")
            return {}

    def _extract_metrics(self, time, traces, circuit_name):
        """Estrae metriche specifiche per ogni circuito."""
        metrics = {}

        if circuit_name == "heater_driver":
            metrics = self._analyze_heater(time, traces)
        elif circuit_name == "tia_sensor":
            metrics = self._analyze_tia(time, traces)
        elif circuit_name == "gas_flow_controller":
            metrics = self._analyze_fan(time, traces)
        elif circuit_name == "ethernet_power":
            metrics = self._analyze_power(time, traces)
        elif circuit_name == "system_integrated":
            metrics = self._analyze_system(time, traces)

        return metrics

    # ──────────────────────────────────────────────────────
    # Analisi specifiche per circuito
    # ──────────────────────────────────────────────────────

    def _analyze_heater(self, t, traces):
        """Analisi heater driver: potenza, transizione, corrente."""
        m = {}
        # Finestra supply: 0-9ms di ogni ciclo
        mask_supply = (t % 10e-3) < 9e-3
        mask_meas = (t % 10e-3) >= 9e-3

        if "V(v12v_prot)" in traces and "V(heat_minus)" in traces:
            v_heater = traces["V(v12v_prot)"] - traces["V(heat_minus)"]
            m["V_heater_supply_avg"] = np.mean(v_heater[mask_supply & (t > 1e-3)])

        if "I(R_HEATER)" in traces:
            i_heat = np.abs(traces["I(R_HEATER)"])
            m["I_heater_peak"] = np.max(i_heat)
            m["I_heater_avg_supply"] = np.mean(i_heat[mask_supply & (t > 1e-3)])

            if "V(v12v_prot)" in traces and "V(heat_minus)" in traces:
                p = v_heater * i_heat
                m["P_heater_avg"] = np.mean(p[t > 1e-3])

        if "V(q1_emitter)" in traces:
            v_rref = traces["V(q1_emitter)"]
            m["V_rref_meas_avg"] = np.mean(v_rref[mask_meas & (t > 9e-3)])

        return m

    def _analyze_tia(self, t, traces):
        """Analisi TIA: uscita, settling, stabilità."""
        m = {}
        # Finestra dopo settling (op-amp acceso dopo 1ms)
        mask_settled = (t > 5e-3) & (t < 12e-3)
        mask_cal = (t > 22e-3) & (t < 25e-3)

        if "V(tia_out)" in traces:
            v_tia = traces["V(tia_out)"]
            m["V_tia_settled"] = np.mean(v_tia[mask_settled])
            m["V_tia_ripple"] = np.ptp(v_tia[mask_settled])

            # Settling time: tempo per raggiungere 99% del valore finale
            final_val = m["V_tia_settled"]
            if abs(final_val) > 0.001:
                settled_idx = np.where(
                    np.abs(v_tia - final_val) < 0.01 * abs(final_val)
                )[0]
                if len(settled_idx) > 0:
                    m["settling_time"] = t[settled_idx[0]]

        if "V(gpio2_adc)" in traces:
            v_adc = traces["V(gpio2_adc)"]
            m["V_adc_settled"] = np.mean(v_adc[mask_settled])

        if "V(tia_out)" in traces and mask_cal.any():
            v_tia_cal = traces["V(tia_out)"]
            m["V_tia_cal"] = np.mean(v_tia_cal[mask_cal])

        return m

    def _analyze_fan(self, t, traces):
        """Analisi fan: ripple 5V, overshoot drain, corrente flyback."""
        m = {}
        mask_ss = t > 1e-3  # dopo transitorio iniziale

        if "V(fan_drain)" in traces:
            v_drain = traces["V(fan_drain)"]
            m["V_drain_peak"] = np.max(v_drain[mask_ss])
            m["V_drain_min"] = np.min(v_drain[mask_ss])

        if "V(v5v)" in traces:
            v5 = traces["V(v5v)"]
            m["V5V_ripple"] = np.ptp(v5[mask_ss])
            m["V5V_avg"] = np.mean(v5[mask_ss])

        if "I(D_FLYBACK)" in traces:
            i_fly = np.abs(traces["I(D_FLYBACK)"])
            m["I_flyback_peak"] = np.max(i_fly[mask_ss])

        if "I(R_MOTOR)" in traces:
            i_motor = np.abs(traces["I(R_MOTOR)"])
            m["I_fan_avg"] = np.mean(i_motor[mask_ss])

        return m

    def _analyze_power(self, t, traces):
        """Analisi power module: stabilità rail, drop durante burst."""
        m = {}
        mask_ss = t > 20e-3

        for rail, key in [("V(v5v_out)", "V5V"), ("V(v3v3)", "V3V3"),
                          ("V(v12v_prot)", "V12V")]:
            if rail in traces:
                v = traces[rail]
                m[f"{key}_avg"] = np.mean(v[mask_ss])
                m[f"{key}_ripple"] = np.ptp(v[mask_ss])
                m[f"{key}_min"] = np.min(v[mask_ss])

        if "V(gpio5_adc)" in traces:
            m["V_mon_avg"] = np.mean(traces["V(gpio5_adc)"][mask_ss])

        return m

    def _analyze_system(self, t, traces):
        """Analisi sistema integrato: cross-interference su TIA."""
        m = {}
        mask_ss = t > 20e-3

        if "V(adc_out)" in traces:
            v_adc = traces["V(adc_out)"]
            m["ADC_OUT_avg"] = np.mean(v_adc[mask_ss])
            m["ADC_OUT_ripple"] = np.ptp(v_adc[mask_ss])
            m["ADC_OUT_rms_noise"] = np.std(v_adc[mask_ss])

        for rail, key in [("V(v3v3)", "V3V3"), ("V(v5v_out)", "V5V"),
                          ("V(v12v_prot)", "V12V")]:
            if rail in traces:
                v = traces[rail]
                m[f"{key}_ripple"] = np.ptp(v[mask_ss])

        if "I(V_12V)" in traces:
            m["I_total_avg"] = np.mean(np.abs(traces["I(V_12V)"][mask_ss]))
            m["P_total"] = 12 * m["I_total_avg"]

        return m

    # ──────────────────────────────────────────────────────
    # Calcoli teorici (fallback senza LTSpice)
    # ──────────────────────────────────────────────────────

    def _generate_theoretical_results(self, circuit_name: str) -> None:
        """Calcola valori teorici quando LTSpice non è disponibile."""
        log.info("Calcolo valori teorici (LTSpice non disponibile)...")

        if circuit_name == "heater_driver":
            self.results[circuit_name] = self._theoretical_heater()
        elif circuit_name == "tia_sensor":
            self.results[circuit_name] = self._theoretical_tia()
        elif circuit_name == "gas_flow_controller":
            self.results[circuit_name] = self._theoretical_fan()
        elif circuit_name == "ethernet_power":
            self.results[circuit_name] = self._theoretical_power()
        elif circuit_name == "system_integrated":
            self.results[circuit_name] = self._theoretical_system()

        return None

    def _theoretical_heater(self) -> dict:
        Vcc = 12.0
        R0 = 10.0
        alpha = 0.0032
        duty = 0.10
        temps = [20, 100, 200, 250, 350, 450]
        results = {"temperatures": {}}
        for T in temps:
            R_T = R0 * (1 + alpha * T)
            I_peak = Vcc / R_T
            P_inst = Vcc**2 / R_T
            P_avg = P_inst * duty
            results["temperatures"][T] = {
                "R_heater": R_T, "I_peak": I_peak,
                "P_instantaneous": P_inst, "P_average": P_avg
            }
        results["cold_start_I_peak"] = Vcc / R0  # 1.2A
        results["transition_time_est"] = 60e-6    # da Della Ciana 2021
        return results

    def _theoretical_tia(self) -> dict:
        Vbias = 5.0
        Rf = 22e3
        results = {"sensor_sweep": {}}
        for Rs in [40e3, 80e3, 100e3, 500e3, 2e6]:
            I_sensor = Vbias / Rs
            V_out = Rf * I_sensor
            results["sensor_sweep"][Rs] = {
                "I_sensor_nA": I_sensor * 1e9,
                "V_out": V_out,
                "in_adc_range": 0 < V_out < 3.0
            }
        # Stabilità: Cf critico
        Cs = 30e-12
        GBW = 1e6
        Cf_min = np.sqrt(Cs / (2 * np.pi * GBW * Rf))
        results["Cf_minimum_pF"] = Cf_min * 1e12
        results["Cf_used_pF"] = 10.0
        results["stability_margin"] = 10e-12 / Cf_min
        # Calibrazione
        Rf_cal_parallel = (Rf * 470e3) / (Rf + 470e3)
        results["Rf_cal_parallel"] = Rf_cal_parallel
        return results

    def _theoretical_fan(self) -> dict:
        V5 = 5.0
        R_motor = 20.0
        L_motor = 1e-3
        results = {"duty_sweep": {}}
        for duty in [0.2, 0.5, 0.8, 1.0]:
            I_avg = V5 * duty / R_motor
            P_avg = V5 * I_avg
            tau = L_motor / R_motor
            results["duty_sweep"][duty] = {
                "I_avg": I_avg, "P_avg": P_avg,
                "tau_LR": tau, "V_flyback_est": V5 + 0.3
            }
        return results

    def _theoretical_power(self) -> dict:
        results = {}
        # Buck: Vin=12, Vout=5, eta=0.85
        I_5V_total = 0.08 + 0.03 + 0.125 + 0.0001  # ESP+W5500+fan+bias
        I_12V_buck = (5 * I_5V_total) / (12 * 0.85)
        I_12V_heater = 12 / 10 * 0.10  # duty 10%
        I_12V_total = I_12V_buck + I_12V_heater
        P_total = 12 * I_12V_total
        results["I_5V_total"] = I_5V_total
        results["I_12V_from_buck"] = I_12V_buck
        results["I_12V_heater_avg"] = I_12V_heater
        results["I_12V_total"] = I_12V_total
        results["P_total_W"] = P_total
        # LDO dissipation
        I_3V3 = 0.08 + 0.03 + 0.0001  # ESP(3.3V) + W5500 + OA
        P_ldo = (5 - 3.3) * I_3V3
        results["P_LDO_W"] = P_ldo
        results["T_junction_LDO"] = 37 + P_ldo * 125  # Tambient + P*theta_JA
        # V_mon divider
        results["V_mon_at_12V"] = 12 * 22 / (100 + 22)
        return results

    def _theoretical_system(self) -> dict:
        """Stima cross-interference teorica."""
        results = {}
        # Rumore heater PWM su rail 12V
        # dV = I_peak * R_polyfuse * (1-exp(-t/tau))
        I_peak = 1.2  # cold start
        R_poly = 1.0
        C_bulk = 100e-6
        dV_12 = I_peak * R_poly  # worst case istantaneo
        tau_12 = R_poly * C_bulk
        results["heater_V12_drop_peak"] = dV_12
        results["heater_V12_tau"] = tau_12

        # Propagazione sul 5V via buck (attenuazione ~40dB)
        atten_buck = 0.01  # -40dB
        dV_5V = dV_12 * atten_buck
        results["heater_to_V5V_ripple"] = dV_5V

        # Propagazione sul 3.3V via LDO (PSRR ~60dB @1kHz)
        atten_ldo = 0.001  # -60dB
        dV_3V3 = dV_5V * atten_ldo + 100e-3 * 0.08  # W5500 burst * ESR(C8)
        results["total_V3V3_ripple_est"] = dV_3V3

        # Errore sul TIA
        # V_tia nominale @80k = 22k*5/80k = 1.375V
        V_tia_nom = 22e3 * 5 / 80e3
        results["V_tia_nominal"] = V_tia_nom
        results["noise_to_signal_ratio"] = dV_3V3 / V_tia_nom
        results["ADC_bits_lost"] = np.log2(1 + dV_3V3 / (3.3 / 4096))

        return results

    # ──────────────────────────────────────────────────────
    # Generazione grafici
    # ──────────────────────────────────────────────────────

    def plot_results(self, circuit_name: str, raw_path: Optional[Path] = None):
        """Genera grafici per un circuito. Usa dati .raw o teorici."""
        if not MATPLOTLIB_AVAILABLE:
            log.warning("matplotlib non disponibile, skip grafici")
            return

        fig = plt.figure(figsize=(14, 10))
        fig.suptitle(f"BioSentry IncuSense — {circuit_name.replace('_', ' ').title()}",
                     fontsize=14, fontweight="bold")

        # ======= PLOT DELLE ONDE REALI =======
        if raw_path and raw_path.exists():
            try:
                raw = RawRead(str(raw_path))
                t = np.array(raw.get_trace("time").get_wave())

                ax = fig.add_subplot(111)
                tracce_disegnate = 0

                for name in raw.get_trace_names():
                    # Disegniamo fino a 5 tracce di tensione principali per non fare confusione
                    if name.startswith("V(") and tracce_disegnate < 5:
                        onda = np.array(raw.get_trace(name).get_wave())
                        # Ignoriamo le tracce completamente a zero
                        if np.ptp(onda) > 1e-6 or np.mean(onda) > 1e-6:
                            ax.plot(t * 1000, onda, label=name, linewidth=1.5)
                            tracce_disegnate += 1

                ax.set_xlabel("Time (ms)")
                ax.set_ylabel("Voltage (V)")
                ax.set_title("Forme d'onda estratte dal file .raw di LTspice")
                ax.legend(loc="upper right")
                ax.grid(True, alpha=0.3)

            except Exception as e:
                log.error(f"Errore durante la generazione del grafico dal .raw: {e}")

        # ======= FALLBACK TEORICO =======
        elif circuit_name in self.results and isinstance(self.results[circuit_name], dict):
            self._plot_theoretical(fig, circuit_name, self.results[circuit_name])

        out_path = OUTPUT_DIR / f"plot_{circuit_name}.png"
        fig.tight_layout(rect=[0, 0, 1, 0.95])
        fig.savefig(out_path, dpi=150)
        plt.close(fig)
        log.info(f"Grafico salvato: {out_path}")

    def _plot_theoretical(self, fig, name, data):
        """Genera grafici da calcoli teorici."""
        if name == "heater_driver" and "temperatures" in data:
            gs = GridSpec(2, 2, figure=fig)

            ax1 = fig.add_subplot(gs[0, 0])
            temps = list(data["temperatures"].keys())
            R_vals = [data["temperatures"][t]["R_heater"] for t in temps]
            ax1.plot(temps, R_vals, "o-", color="#E24B4A", linewidth=2)
            ax1.set_xlabel("Temperatura (°C)")
            ax1.set_ylabel("R_heater (Ω)")
            ax1.set_title("R(T) Platino PTC")
            ax1.grid(True, alpha=0.3)

            ax2 = fig.add_subplot(gs[0, 1])
            P_avg = [data["temperatures"][t]["P_average"] for t in temps]
            ax2.bar([str(t) for t in temps], P_avg, color="#185FA5", alpha=0.8)
            ax2.set_xlabel("Temperatura (°C)")
            ax2.set_ylabel("P_avg (W)")
            ax2.set_title("Potenza media (duty=10%)")
            ax2.grid(True, alpha=0.3, axis="y")

            ax3 = fig.add_subplot(gs[1, 0])
            I_peaks = [data["temperatures"][t]["I_peak"] for t in temps]
            ax3.plot(temps, I_peaks, "s-", color="#27AE60", linewidth=2)
            ax3.axhline(y=0.5, color="r", linestyle="--", label="Polyfuse Ihold")
            ax3.set_xlabel("Temperatura (°C)")
            ax3.set_ylabel("I_peak (A)")
            ax3.set_title("Corrente di picco per impulso PWM")
            ax3.legend()
            ax3.grid(True, alpha=0.3)

            ax4 = fig.add_subplot(gs[1, 1])
            ax4.axis("off")
            info = (
                f"Cold start I_peak: {data['cold_start_I_peak']:.1f} A\n"
                f"Transition time: {data['transition_time_est']*1e6:.0f} µs\n"
                f"R₀ (RT): 10 Ω\n"
                f"R(250°C): {data['temperatures'][250]['R_heater']:.1f} Ω\n"
                f"P_avg(250°C): {data['temperatures'][250]['P_average']:.2f} W"
            )
            ax4.text(0.1, 0.5, info, fontsize=12, family="monospace",
                     verticalalignment="center",
                     bbox=dict(boxstyle="round", facecolor="#F0F0F0"))

        elif name == "tia_sensor" and "sensor_sweep" in data:
            gs = GridSpec(2, 2, figure=fig)

            ax1 = fig.add_subplot(gs[0, 0])
            Rs_vals = sorted(data["sensor_sweep"].keys())
            V_outs = [data["sensor_sweep"][r]["V_out"] for r in Rs_vals]
            ax1.semilogx([r/1e3 for r in Rs_vals], V_outs, "o-",
                         color="#534AB7", linewidth=2)
            ax1.axhline(y=3.0, color="r", linestyle="--", label="ADC max (3.0V)")
            ax1.axhline(y=0, color="gray", linestyle=":", alpha=0.5)
            ax1.set_xlabel("R_sensor (kΩ)")
            ax1.set_ylabel("V_out TIA (V)")
            ax1.set_title("Risposta TIA vs R_sensor (Rf=22kΩ)")
            ax1.legend()
            ax1.grid(True, alpha=0.3)

            ax2 = fig.add_subplot(gs[0, 1])
            I_nA = [data["sensor_sweep"][r]["I_sensor_nA"] for r in Rs_vals]
            ax2.semilogx([r/1e3 for r in Rs_vals], I_nA, "s-",
                         color="#E67E22", linewidth=2)
            ax2.set_xlabel("R_sensor (kΩ)")
            ax2.set_ylabel("I_sensor (nA)")
            ax2.set_title("Corrente nel sensore vs R_sensor")
            ax2.grid(True, alpha=0.3)

            ax3 = fig.add_subplot(gs[1, :])
            ax3.axis("off")
            info = (
                f"Rf = 22 kΩ | Cf = 10 pF | Vbias = 5 V\n"
                f"Cf minimo per stabilità: {data['Cf_minimum_pF']:.2f} pF\n"
                f"Margine di stabilità: ×{data['stability_margin']:.1f}\n"
                f"Rf//Rf_CAL (calibrazione): {data['Rf_cal_parallel']:.0f} Ω\n\n"
                f"{'Rs (kΩ)':<12} {'I (nA)':<12} {'Vout (V)':<12} {'In range?'}\n"
                f"{'-'*50}\n"
            )
            for Rs in Rs_vals:
                d = data["sensor_sweep"][Rs]
                info += (f"{Rs/1e3:<12.0f} {d['I_sensor_nA']:<12.1f} "
                         f"{d['V_out']:<12.3f} {'✅' if d['in_adc_range'] else '🔴'}\n")
            ax3.text(0.05, 0.5, info, fontsize=10, family="monospace",
                     verticalalignment="center",
                     bbox=dict(boxstyle="round", facecolor="#F0F0F0"))

        elif name == "system_integrated":
            gs = GridSpec(1, 1, figure=fig)
            ax = fig.add_subplot(gs[0, 0])
            ax.axis("off")
            info = (
                "══ ANALISI CROSS-INTERFERENCE ══\n\n"
                f"Heater PWM → V12V drop peak: {data.get('heater_V12_drop_peak', 0):.2f} V\n"
                f"Heater → V5V ripple (via buck): {data.get('heater_to_V5V_ripple', 0)*1e3:.2f} mV\n"
                f"Totale V3V3 ripple stimato: {data.get('total_V3V3_ripple_est', 0)*1e3:.2f} mV\n\n"
                f"V_TIA nominale (@80kΩ): {data.get('V_tia_nominal', 0):.3f} V\n"
                f"Rapporto rumore/segnale: {data.get('noise_to_signal_ratio', 0)*100:.3f} %\n"
                f"Bit ADC persi per rumore: {data.get('ADC_bits_lost', 0):.1f}\n\n"
                "══ CONCLUSIONE ══\n"
            )
            nsr = data.get("noise_to_signal_ratio", 0)
            if nsr < 0.01:
                info += "✅ Cross-interference TRASCURABILE (<1% del segnale)\n"
                info += "   I condensatori di bulk e la separazione dei rail\n"
                info += "   sono sufficienti per l'applicazione."
            elif nsr < 0.05:
                info += "⚠️ Cross-interference ACCETTABILE (1-5% del segnale)\n"
                info += "   Verificare con simulazione completa."
            else:
                info += "🔴 Cross-interference CRITICA (>5% del segnale)\n"
                info += "   Necessario migliorare il decoupling."
            ax.text(0.05, 0.5, info, fontsize=11, family="monospace",
                    verticalalignment="center",
                    bbox=dict(boxstyle="round", facecolor="#F8F9FA", edgecolor="#CCC"))

    # ──────────────────────────────────────────────────────
    # Report testuale
    # ──────────────────────────────────────────────────────

    def generate_report(self):
        """Genera report di tutti i test eseguiti."""
        report_lines = [
            "=" * 70,
            "  BioSentry IncuSense — Report Simulazione LTSpice",
            "=" * 70, ""
        ]

        for circuit_name, data in self.results.items():
            report_lines.append(f"\n{'─' * 50}")
            report_lines.append(f"  {circuit_name.upper().replace('_', ' ')}")
            report_lines.append(f"{'─' * 50}")

            if isinstance(data, dict):
                for key, val in data.items():
                    if isinstance(val, dict):
                        report_lines.append(f"\n  [{key}]")
                        for k2, v2 in val.items():
                            if isinstance(v2, dict):
                                report_lines.append(f"    {k2}:")
                                for k3, v3 in v2.items():
                                    report_lines.append(f"      {k3}: {v3}")
                            else:
                                report_lines.append(f"    {k2}: {v2}")
                    else:
                        if isinstance(val, float):
                            report_lines.append(f"  {key}: {val:.6g}")
                        else:
                            report_lines.append(f"  {key}: {val}")

        report = "\n".join(report_lines)
        report_path = OUTPUT_DIR / "test_report.txt"
        report_path.write_text(report, encoding="utf-8")
        log.info(f"\nReport salvato: {report_path}")
        print(report)

    # ──────────────────────────────────────────────────────
    # Esecuzione completa
    # ──────────────────────────────────────────────────────

    def run_all(self, circuits: Optional[list] = None):
        """Esegue tutti i test (o un sottoinsieme)."""
        all_circuits = [
            "heater_driver",
            "tia_sensor",
            "gas_flow_controller",
            "ethernet_power",
            "system_integrated",
        ]

        if circuits:
            all_circuits = [c for c in all_circuits if c in circuits]

        for circuit_name in all_circuits:
            raw_path = self.run_simulation(circuit_name)

            if raw_path and raw_path.exists():
                metrics = self.analyze_raw(raw_path, circuit_name)
                self.results[circuit_name] = metrics
            elif circuit_name not in self.results:
                # Fallback a calcoli teorici
                self._generate_theoretical_results(circuit_name)

            self.plot_results(circuit_name, raw_path)

        self.generate_report()


# ══════════════════════════════════════════════════════════════
# Entry point
# ══════════════════════════════════════════════════════════════

def main():
    parser = argparse.ArgumentParser(
        description="BioSentry IncuSense — Test Suite LTSpice"
    )
    parser.add_argument(
        "--circuit", "-c", type=str, default=None,
        choices=["heater", "tia", "fan", "power", "system", "all"],
        help="Circuito da testare (default: all)"
    )
    parser.add_argument(
        "--ltspice-path", type=str, default=None,
        help="Percorso eseguibile LTSpice"
    )
    parser.add_argument(
        "--plot-only", action="store_true",
        help="Genera solo grafici da file .raw esistenti"
    )
    args = parser.parse_args()

    # Mappa nomi brevi a nomi file
    circuit_map = {
        "heater": "heater_driver",
        "tia": "tia_sensor",
        "fan": "gas_flow_controller",
        "power": "ethernet_power",
        "system": "system_integrated",
    }

    circuits = None
    if args.circuit and args.circuit != "all":
        circuits = [circuit_map[args.circuit]]

    runner = BioSentryTestRunner(ltspice_path=args.ltspice_path)
    runner.run_all(circuits=circuits)

    log.info("\n" + "=" * 50)
    log.info("Test completati. File generati in: output/")
    log.info("=" * 50)


if __name__ == "__main__":
    main()
