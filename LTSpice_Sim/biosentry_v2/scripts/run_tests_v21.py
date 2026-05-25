#!/usr/bin/env python3
"""
BioSentry IncuSense v2.1 — Test Suite LTSpice + Analisi Teorica
================================================================
Aggiornamenti v2.1:
  - TIA con Vin=1V (partitore 40k/10k da 5V)
  - Cf=22pF (corretto per stabilità)
  - LC filter su alimentazione op-amp
  - Modello ESP32-C3 con tutti i GPIO
  - Analisi risoluzione ADC per range 0-50000 ppm CO2

Uso:
    python run_tests_v21.py                # Tutti i test
    python run_tests_v21.py --circuit tia  # Singolo
"""

import os, sys, argparse, json
from pathlib import Path
from dataclasses import dataclass
from typing import Optional
import numpy as np

try:
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from matplotlib.gridspec import GridSpec
    MPL = True
except ImportError:
    MPL = False

try:
    from PyLTSpice import SimRunner, RawRead
    LTSPICE = True
except ImportError:
    LTSPICE = False

BASE_DIR = Path(__file__).parent.parent
NETLIST_DIR = BASE_DIR / "netlists"
OUTPUT_DIR = BASE_DIR / "output"
OUTPUT_DIR.mkdir(exist_ok=True)


# ══════════════════════════════════════════════════════════════
# Parametri di progetto v2.1
# ══════════════════════════════════════════════════════════════

P = {
    # Alimentazione
    "V12V": 12.0, "V5V": 5.0, "V3V3": 3.3,
    # Heater
    "R0_heater": 10.0, "alpha_Pt": 0.0032, "T_target": 250,
    "heater_duty": 0.10, "f_pwm_heater": 1e3,
    # TIA (AGGIORNATO v2.1)
    "Vin_bias": 1.0,  # era 5V, ora 1V via partitore
    "R_div_a": 40e3, "R_div_b": 10e3,
    "Rf": 22e3, "Cf": 22e-12,  # era 10pF, ora 22pF
    "GBW_MCP6001": 1e6, "Cs_parasitic": 30e-12,
    "Rf_cal": 470e3,
    # ADC
    "ADC_bits": 12, "ADC_Vref": 3.0,
    # Ventola
    "R_motor": 20, "L_motor": 1e-3, "fan_duty": 0.50,
    "f_pwm_fan": 25e3,
    # ESP32
    "I_esp_idle": 20e-3, "I_esp_active": 80e-3,
    "I_esp_spi_burst": 40e-3,
    # W5500
    "I_w5500_idle": 30e-3, "I_w5500_burst": 130e-3,
    # Decoupling
    "C_in_bulk": 100e-6, "R_polyfuse": 1.0,
    "C8_bulk": 470e-6, "ESR_C8": 0.08,
    # LC filter (NUOVO v2.1)
    "L_ferrite": 1e-6, "R_ferrite": 1.0, "C_ferrite": 10e-6,
    # CO2 sensing
    "Rs_aria": 2e6, "Rs_5pct": 40e3,
    "ppm_min": 0, "ppm_max": 50000,
}


# ══════════════════════════════════════════════════════════════
# Analisi Teoriche
# ══════════════════════════════════════════════════════════════

def analyze_tia_v21():
    """Analisi TIA con Vin=1V e Cf=22pF."""
    p = P
    Vin = p["Vin_bias"]
    Rf = p["Rf"]
    Cf = p["Cf"]
    Cs = p["Cs_parasitic"]
    GBW = p["GBW_MCP6001"]

    # Verifica partitore
    Vin_calc = p["V5V"] * p["R_div_b"] / (p["R_div_a"] + p["R_div_b"])
    R_thevenin = p["R_div_a"] * p["R_div_b"] / (p["R_div_a"] + p["R_div_b"])

    # Stabilità
    Cf_min = np.sqrt(Cs / (2 * np.pi * GBW * Rf))
    stability_margin = Cf / Cf_min
    f_c = 1 / (2 * np.pi * Rf * Cf)

    # Sweep Rs
    Rs_values = [40e3, 60e3, 80e3, 100e3, 200e3, 500e3, 1e6, 2e6]
    sweep = {}
    for Rs in Rs_values:
        I_s = Vin / Rs
        V_out = Vin + Rf * I_s  # = Vin + Rf*Vin/Rs = Vin*(1 + Rf/Rs)
        sweep[Rs] = {
            "I_sensor_uA": I_s * 1e6,
            "V_out": V_out,
            "in_adc_range": 0 < V_out < p["ADC_Vref"],
        }

    # Range segnale per CO2
    V_out_aria = Vin + Rf * Vin / p["Rs_aria"]  # ~1.011V
    V_out_5pct = Vin + Rf * Vin / p["Rs_5pct"]  # ~1.55V
    delta_V_signal = V_out_5pct - V_out_aria

    # Risoluzione ADC
    LSB = p["ADC_Vref"] / (2**p["ADC_bits"])
    signal_LSBs = delta_V_signal / LSB
    signal_bits = np.log2(signal_LSBs)
    ppm_per_LSB = p["ppm_max"] / signal_LSBs

    # Calibrazione (Rf // Rf_CAL)
    Rf_par = (Rf * p["Rf_cal"]) / (Rf + p["Rf_cal"])

    return {
        "Vin_calculated": Vin_calc,
        "R_thevenin_divider": R_thevenin,
        "Cf_minimum_pF": Cf_min * 1e12,
        "Cf_used_pF": Cf * 1e12,
        "stability_margin": stability_margin,
        "STABLE": stability_margin > 1.0,
        "f_cutoff_Hz": f_c,
        "V_out_aria": V_out_aria,
        "V_out_5pct_CO2": V_out_5pct,
        "delta_V_signal": delta_V_signal,
        "ADC_LSB_mV": LSB * 1e3,
        "signal_span_LSBs": signal_LSBs,
        "effective_bits_signal": signal_bits,
        "ppm_per_LSB": ppm_per_LSB,
        "Rf_cal_parallel": Rf_par,
        "sensor_sweep": sweep,
    }


def analyze_heater():
    """Analisi heater driver."""
    p = P
    V = p["V12V"]
    R0 = p["R0_heater"]
    alpha = p["alpha_Pt"]
    duty = p["heater_duty"]

    temps = [20, 100, 150, 200, 250, 300, 350, 450]
    data = {}
    for T in temps:
        R_T = R0 * (1 + alpha * T)
        I_peak = V / R_T
        P_inst = V**2 / R_T
        P_avg = P_inst * duty
        data[T] = {"R": R_T, "I_peak": I_peak,
                    "P_inst": P_inst, "P_avg": P_avg}

    return {
        "temperatures": data,
        "cold_start_I_peak": V / R0,
        "cold_start_V_drop_Cin": (V / R0) * (duty / p["f_pwm_heater"]) / p["C_in_bulk"],
        "R_at_target": R0 * (1 + alpha * p["T_target"]),
        "P_avg_at_target": data[p["T_target"]]["P_avg"],
    }


def analyze_fan():
    """Analisi gas flow controller."""
    p = P
    result = {}
    for d in [0.2, 0.5, 0.8, 1.0]:
        I_avg = p["V5V"] * d / p["R_motor"]
        P_avg = p["V5V"] * I_avg
        tau = p["L_motor"] / p["R_motor"]
        result[d] = {"I_avg_mA": I_avg * 1e3, "P_avg_mW": P_avg * 1e3,
                     "tau_us": tau * 1e6}
    return {"duty_sweep": result}


def analyze_power():
    """Analisi alimentazione completa."""
    p = P
    # Correnti 5V
    I_5V_esp = p["I_esp_active"]
    I_5V_fan = p["V5V"] * p["fan_duty"] / p["R_motor"]
    I_5V_divider = p["V5V"] / (p["R_div_a"] + p["R_div_b"])
    I_5V_ldo_in = (p["I_w5500_idle"] + 0.001 + 0.003 + 0.0003) * (3.3 / 5.0) / 0.95
    I_5V_total = I_5V_esp + I_5V_fan + I_5V_divider + I_5V_ldo_in

    # Correnti 12V
    R_T = p["R0_heater"] * (1 + p["alpha_Pt"] * p["T_target"])
    I_12V_heater = p["V12V"] / R_T * p["heater_duty"]
    I_12V_buck = (p["V5V"] * I_5V_total) / (p["V12V"] * 0.85)
    I_12V_total = I_12V_heater + I_12V_buck
    P_total = p["V12V"] * I_12V_total

    # LDO
    I_3V3_total = p["I_w5500_idle"] + 0.001 + 0.003 + 0.0003
    P_ldo = (p["V5V"] - p["V3V3"]) * I_3V3_total
    T_j_ldo = 37 + P_ldo * 125

    return {
        "I_5V_total_mA": I_5V_total * 1e3,
        "I_12V_total_mA": I_12V_total * 1e3,
        "P_total_W": P_total,
        "P_LDO_W": P_ldo,
        "T_junction_LDO_C": T_j_ldo,
        "V_mon_at_12V": p["V12V"] * 22 / (100 + 22),
        "PoE_margin_pct": (1 - P_total / 15.4) * 100,
    }


def analyze_cross_interference():
    """Analisi cross-interference con modello LC filter (v2.1)."""
    p = P

    # --- Rumore heater PWM sul rail 12V ---
    R_T = p["R0_heater"] * (1 + p["alpha_Pt"] * p["T_target"])
    I_heater_peak = p["V12V"] / R_T
    dV_12V = I_heater_peak * p["R_polyfuse"]  # worst case
    # Attenuazione da C_in bulk
    t_on = p["heater_duty"] / p["f_pwm_heater"]
    dV_12V_cap = I_heater_peak * t_on / p["C_in_bulk"]

    # --- Propagazione 12V→5V via buck (PSRR ~40dB @1kHz) ---
    atten_buck_1kHz = 0.01  # -40dB
    dV_5V_from_heater = dV_12V_cap * atten_buck_1kHz

    # --- Rumore ventola PWM sul rail 5V ---
    I_fan_peak = p["V5V"] / p["R_motor"]
    # Il diodo Schottky clamp + C1+C2 limitano il ripple
    dV_5V_from_fan = I_fan_peak * 0.01 / (10e-6)  # ESR × I / C
    # Più realistico: ripple = I * ESR_cap
    dV_5V_from_fan = I_fan_peak * 0.02  # ESR C2

    # --- Propagazione 5V→3.3V via LDO (PSRR ~60dB @1kHz, ~30dB @25kHz) ---
    atten_ldo_1kHz = 0.001  # -60dB
    atten_ldo_25kHz = 0.03  # -30dB (LDO peggiora ad alta freq)
    dV_3V3_from_heater = dV_5V_from_heater * atten_ldo_1kHz
    dV_3V3_from_fan = dV_5V_from_fan * atten_ldo_25kHz

    # --- Burst W5500 diretto su 3.3V ---
    I_burst = p["I_w5500_burst"] - p["I_w5500_idle"]  # 100mA transient
    dV_3V3_from_w5500 = I_burst * p["ESR_C8"]  # 100m × 0.08 = 8mV

    # --- Totale ripple su V3V3 ---
    dV_3V3_total = np.sqrt(dV_3V3_from_heater**2 +
                           dV_3V3_from_fan**2 +
                           dV_3V3_from_w5500**2)

    # --- ATTENUAZIONE LC FILTER (NUOVO v2.1) ---
    # LC filter: L=1µH R=1Ω, C=10µF
    # f_cutoff = 1/(2π√(LC)) = 1/(2π√(1e-6×10e-6)) = 50.3kHz
    # A 1kHz: attenuazione ≈ 1 (passabanda)
    # A 25kHz: attenuazione ~0.5 (-6dB)
    # Ma la ferrite bead ha R=1Ω che con C=10µF forma un filtro RC
    # f_RC = 1/(2π×1×10µ) = 15.9kHz
    # A 25kHz: |H| = 1/√(1+(25/15.9)²) = 0.54
    # A 1kHz: |H| = 1/√(1+(1/15.9)²) ≈ 1.0
    f_lc = 1 / (2 * np.pi * np.sqrt(p["L_ferrite"] * p["C_ferrite"]))
    f_rc = 1 / (2 * np.pi * p["R_ferrite"] * p["C_ferrite"])

    # Attenuazione per ogni sorgente dopo LC filter
    atten_lc_1kHz = 1 / np.sqrt(1 + (1e3 / f_rc)**2)
    atten_lc_25kHz = 1 / np.sqrt(1 + (25e3 / f_rc)**2)
    atten_lc_25MHz = 1 / np.sqrt(1 + (25e6 / f_rc)**2)

    dV_opamp_from_heater = dV_3V3_from_heater * atten_lc_1kHz
    dV_opamp_from_fan = dV_3V3_from_fan * atten_lc_25kHz
    dV_opamp_from_w5500 = dV_3V3_from_w5500 * atten_lc_25MHz

    dV_opamp_total = np.sqrt(dV_opamp_from_heater**2 +
                             dV_opamp_from_fan**2 +
                             dV_opamp_from_w5500**2)

    # --- Impatto sulla lettura TIA ---
    V_tia_nominal = p["Vin_bias"] + p["Rf"] * p["Vin_bias"] / 80e3
    delta_V_signal = (p["Vin_bias"] + p["Rf"] * p["Vin_bias"] / p["Rs_5pct"]) - \
                     (p["Vin_bias"] + p["Rf"] * p["Vin_bias"] / p["Rs_aria"])
    LSB = p["ADC_Vref"] / (2**p["ADC_bits"])

    noise_LSBs = dV_opamp_total / LSB
    noise_bits = np.log2(max(noise_LSBs, 1))
    signal_LSBs = delta_V_signal / LSB
    signal_bits = np.log2(signal_LSBs)
    effective_bits = signal_bits - noise_bits
    effective_levels = 2**effective_bits
    ppm_resolution = p["ppm_max"] / effective_levels

    # --- Miglioramento con oversampling ---
    for N_oversample in [1, 4, 16, 64, 256]:
        gain_bits = np.log2(np.sqrt(N_oversample))
        eff = effective_bits + gain_bits
        lvls = 2**eff
        res = p["ppm_max"] / lvls
        # Troviamo il minimo oversampling per <100ppm
        if res < 100:
            min_oversample = N_oversample
            break
    else:
        min_oversample = 256

    return {
        "noise_sources": {
            "heater_1kHz_on_V12V_mV": dV_12V_cap * 1e3,
            "heater_via_buck_on_V5V_mV": dV_5V_from_heater * 1e3,
            "fan_25kHz_on_V5V_mV": dV_5V_from_fan * 1e3,
            "heater_on_V3V3_mV": dV_3V3_from_heater * 1e3,
            "fan_on_V3V3_mV": dV_3V3_from_fan * 1e3,
            "W5500_burst_on_V3V3_mV": dV_3V3_from_w5500 * 1e3,
            "total_V3V3_ripple_mV": dV_3V3_total * 1e3,
        },
        "lc_filter": {
            "f_cutoff_kHz": f_lc / 1e3,
            "f_rc_kHz": f_rc / 1e3,
            "atten_1kHz_dB": 20 * np.log10(atten_lc_1kHz),
            "atten_25kHz_dB": 20 * np.log10(atten_lc_25kHz),
            "atten_25MHz_dB": 20 * np.log10(atten_lc_25MHz),
        },
        "after_lc_filter": {
            "heater_on_opamp_mV": dV_opamp_from_heater * 1e3,
            "fan_on_opamp_mV": dV_opamp_from_fan * 1e3,
            "W5500_on_opamp_mV": dV_opamp_from_w5500 * 1e3,
            "total_noise_on_opamp_uV": dV_opamp_total * 1e6,
        },
        "adc_impact": {
            "V_tia_nominal_V": V_tia_nominal,
            "delta_V_signal_mV": delta_V_signal * 1e3,
            "ADC_LSB_mV": LSB * 1e3,
            "noise_LSBs": noise_LSBs,
            "noise_bits_lost": noise_bits,
            "signal_bits": signal_bits,
            "effective_bits_raw": effective_bits,
            "effective_levels": effective_levels,
            "ppm_resolution_raw": ppm_resolution,
            "min_oversample_for_100ppm": min_oversample,
        },
        "oversampling_table": {
            N: {
                "gain_bits": np.log2(np.sqrt(N)),
                "eff_bits": effective_bits + np.log2(np.sqrt(N)),
                "ppm_res": p["ppm_max"] / 2**(effective_bits + np.log2(np.sqrt(N))),
            }
            for N in [1, 4, 16, 64, 256]
        },
    }


# ══════════════════════════════════════════════════════════════
# Grafici
# ══════════════════════════════════════════════════════════════

def plot_tia(tia_data, output_dir):
    if not MPL:
        return
    fig = plt.figure(figsize=(16, 12))
    fig.suptitle("BioSentry v2.1 — TIA Sensore CO₂ (Vin=1V, Cf=22pF)", fontsize=14, fontweight="bold")
    gs = GridSpec(2, 2, figure=fig)

    # V_out vs Rs
    ax1 = fig.add_subplot(gs[0, 0])
    Rs_vals = sorted(tia_data["sensor_sweep"].keys())
    V_outs = [tia_data["sensor_sweep"][r]["V_out"] for r in Rs_vals]
    ax1.semilogx([r / 1e3 for r in Rs_vals], V_outs, "o-", color="#534AB7", lw=2)
    ax1.axhline(3.0, color="r", ls="--", label="ADC max (3.0V)")
    ax1.axhline(1.0, color="gray", ls=":", alpha=0.5, label="Vin bias (1.0V)")
    ax1.fill_between([r / 1e3 for r in Rs_vals], 1.0, V_outs, alpha=0.1, color="#534AB7")
    ax1.set_xlabel("R_sensor (kΩ)"); ax1.set_ylabel("V_out TIA (V)")
    ax1.set_title("Risposta TIA (Rf=22kΩ, Vin=1V)")
    ax1.legend(); ax1.grid(True, alpha=0.3)
    ax1.set_ylim(0.5, 3.5)

    # Stabilità
    ax2 = fig.add_subplot(gs[0, 1])
    ax2.axis("off")
    stable = "STABILE" if tia_data["STABLE"] else "INSTABILE"
    color = "#27AE60" if tia_data["STABLE"] else "#E24B4A"
    info = (
        f"══ STABILITA TIA ══\n\n"
        f"Cf minimo:     {tia_data['Cf_minimum_pF']:.1f} pF\n"
        f"Cf utilizzato: {tia_data['Cf_used_pF']:.0f} pF\n"
        f"Margine:       x{tia_data['stability_margin']:.2f}\n"
        f"Stato:         {stable}\n\n"
        f"f_cutoff:      {tia_data['f_cutoff_Hz']/1e3:.1f} kHz\n"
        f"Vin (partitore): {tia_data['Vin_calculated']:.3f} V\n"
        f"Rth partitore:   {tia_data['R_thevenin_divider']/1e3:.1f} kOhm\n"
    )
    ax2.text(0.1, 0.5, info, fontsize=12, family="monospace", va="center",
             bbox=dict(boxstyle="round", facecolor=color, alpha=0.15))

    # Tabella valori
    ax3 = fig.add_subplot(gs[1, :])
    ax3.axis("off")
    header = f"{'Rs (kΩ)':<12}{'I (µA)':<12}{'Vout (V)':<12}{'ΔV da Vin':<12}{'ADC OK?'}\n{'─'*55}\n"
    rows = ""
    for Rs in Rs_vals:
        d = tia_data["sensor_sweep"][Rs]
        dv = d["V_out"] - 1.0
        ok = "OK" if d["in_adc_range"] else "FUORI"
        rows += f"{Rs/1e3:<12.0f}{d['I_sensor_uA']:<12.1f}{d['V_out']:<12.3f}{dv:<12.3f}{ok}\n"
    summary = (
        f"\n{'═'*55}\n"
        f"Range segnale: {tia_data['V_out_aria']:.3f}V — {tia_data['V_out_5pct_CO2']:.3f}V\n"
        f"ΔV totale:     {tia_data['delta_V_signal']*1e3:.1f} mV\n"
        f"ADC LSB:       {tia_data['ADC_LSB_mV']:.3f} mV\n"
        f"Span in LSBs:  {tia_data['signal_span_LSBs']:.0f}\n"
        f"Bit effettivi: {tia_data['effective_bits_signal']:.1f}\n"
        f"Risoluzione:   {tia_data['ppm_per_LSB']:.0f} ppm/LSB\n"
    )
    ax3.text(0.05, 0.5, header + rows + summary, fontsize=10, family="monospace",
             va="center", bbox=dict(boxstyle="round", facecolor="#F0F0F0"))

    fig.tight_layout(rect=[0, 0, 1, 0.95])
    fig.savefig(output_dir / "plot_tia_v21.png", dpi=150)
    plt.close(fig)


def plot_cross_interference(ci_data, output_dir):
    if not MPL:
        return
    fig = plt.figure(figsize=(16, 14))
    fig.suptitle("BioSentry v2.1 — Analisi Cross-Interference (con LC filter)", fontsize=14, fontweight="bold")
    gs = GridSpec(3, 2, figure=fig)

    # Noise budget
    ax1 = fig.add_subplot(gs[0, :])
    ns = ci_data["noise_sources"]
    labels = ["Heater\n(V3V3)", "Fan\n(V3V3)", "W5500\n(V3V3)"]
    values = [ns["heater_on_V3V3_mV"], ns["fan_on_V3V3_mV"], ns["W5500_burst_on_V3V3_mV"]]
    colors = ["#E24B4A", "#E67E22", "#3498DB"]
    bars = ax1.bar(labels, values, color=colors, alpha=0.7, label="Senza LC filter")
    af = ci_data["after_lc_filter"]
    values_lc = [af["heater_on_opamp_mV"], af["fan_on_opamp_mV"], af["W5500_on_opamp_mV"]]
    ax1.bar(labels, values_lc, color=colors, alpha=1.0, width=0.3, label="Con LC filter")
    ax1.set_ylabel("Ripple (mV)")
    ax1.set_title("Noise budget su alimentazione op-amp")
    ax1.legend()
    ax1.grid(True, alpha=0.3, axis="y")

    # LC filter
    ax2 = fig.add_subplot(gs[1, 0])
    lc = ci_data["lc_filter"]
    freqs = [1, 25, 25000]
    attens = [lc["atten_1kHz_dB"], lc["atten_25kHz_dB"], lc["atten_25MHz_dB"]]
    ax2.bar(["1 kHz\n(heater)", "25 kHz\n(fan)", "25 MHz\n(W5500)"], attens, color="#534AB7", alpha=0.7)
    ax2.set_ylabel("Attenuazione (dB)")
    ax2.set_title(f"LC Filter (fc={lc['f_rc_kHz']:.1f} kHz)")
    ax2.grid(True, alpha=0.3, axis="y")

    # Oversampling
    ax3 = fig.add_subplot(gs[1, 1])
    ot = ci_data["oversampling_table"]
    N_vals = sorted(ot.keys())
    ppm_res = [ot[n]["ppm_res"] for n in N_vals]
    ax3.semilogy(range(len(N_vals)), ppm_res, "s-", color="#27AE60", lw=2, markersize=8)
    ax3.set_xticks(range(len(N_vals)))
    ax3.set_xticklabels([str(n) + "x" for n in N_vals])
    ax3.axhline(100, color="r", ls="--", label="Target: 100 ppm")
    ax3.axhline(1000, color="orange", ls=":", label="Limite: 1000 ppm (±0.1%)")
    ax3.set_xlabel("Oversampling factor")
    ax3.set_ylabel("Risoluzione (ppm)")
    ax3.set_title("Risoluzione CO₂ vs Oversampling")
    ax3.legend(); ax3.grid(True, alpha=0.3)

    # Summary
    ax4 = fig.add_subplot(gs[2, :])
    ax4.axis("off")
    ai = ci_data["adc_impact"]
    info = (
        f"══ RIEPILOGO CROSS-INTERFERENCE v2.1 ══\n\n"
        f"Ripple totale V3V3 (senza LC):    {ns['total_V3V3_ripple_mV']:.2f} mV\n"
        f"Rumore su op-amp (con LC filter):  {af['total_noise_on_opamp_uV']:.1f} µV\n\n"
        f"V_TIA nominale (@80kΩ):            {ai['V_tia_nominal_V']:.3f} V\n"
        f"Span segnale (aria→5%CO₂):         {ai['delta_V_signal_mV']:.1f} mV\n"
        f"ADC LSB:                           {ai['ADC_LSB_mV']:.3f} mV\n\n"
        f"Rumore in LSBs:                    {ai['noise_LSBs']:.2f}\n"
        f"Bit persi per rumore:              {ai['noise_bits_lost']:.1f}\n"
        f"Bit segnale effettivi (raw):        {ai['effective_bits_raw']:.1f}\n"
        f"Risoluzione raw:                   {ai['ppm_resolution_raw']:.0f} ppm\n\n"
        f"Oversampling minimo per <100 ppm:   {ai['min_oversample_for_100ppm']}x\n"
    )
    # Valutazione finale
    if ai["ppm_resolution_raw"] < 100:
        verdict = "ECCELLENTE: risoluzione <100 ppm anche senza oversampling"
        vcolor = "#27AE60"
    elif ai["ppm_resolution_raw"] < 1000:
        verdict = f"BUONO: risoluzione {ai['ppm_resolution_raw']:.0f} ppm, oversampling {ai['min_oversample_for_100ppm']}x per <100 ppm"
        vcolor = "#E67E22"
    else:
        verdict = f"INSUFFICIENTE: risoluzione {ai['ppm_resolution_raw']:.0f} ppm, serve oversampling pesante"
        vcolor = "#E24B4A"
    info += f"\n→ {verdict}"
    ax4.text(0.05, 0.5, info, fontsize=11, family="monospace", va="center",
             bbox=dict(boxstyle="round", facecolor=vcolor, alpha=0.1, edgecolor=vcolor))

    fig.tight_layout(rect=[0, 0, 1, 0.95])
    fig.savefig(output_dir / "plot_cross_interference_v21.png", dpi=150)
    plt.close(fig)


def plot_heater(data, output_dir):
    if not MPL:
        return
    fig, axes = plt.subplots(1, 3, figsize=(16, 5))
    fig.suptitle("BioSentry v2.1 — Heater Driver", fontsize=14, fontweight="bold")
    temps = sorted(data["temperatures"].keys())
    R = [data["temperatures"][t]["R"] for t in temps]
    I = [data["temperatures"][t]["I_peak"] for t in temps]
    P = [data["temperatures"][t]["P_avg"] for t in temps]

    axes[0].plot(temps, R, "o-", color="#E24B4A", lw=2)
    axes[0].set_xlabel("T (°C)"); axes[0].set_ylabel("R (Ω)")
    axes[0].set_title("R(T) Platino"); axes[0].grid(True, alpha=0.3)

    axes[1].plot(temps, I, "s-", color="#27AE60", lw=2)
    axes[1].axhline(0.5, color="r", ls="--", label="Polyfuse Ihold")
    axes[1].set_xlabel("T (°C)"); axes[1].set_ylabel("I_peak (A)")
    axes[1].set_title("Corrente di picco"); axes[1].legend(); axes[1].grid(True, alpha=0.3)

    axes[2].bar([str(t) for t in temps], P, color="#185FA5", alpha=0.8)
    axes[2].set_xlabel("T (°C)"); axes[2].set_ylabel("P_avg (W)")
    axes[2].set_title("Potenza media (duty=10%)"); axes[2].grid(True, alpha=0.3, axis="y")

    fig.tight_layout(rect=[0, 0, 1, 0.93])
    fig.savefig(output_dir / "plot_heater_v21.png", dpi=150)
    plt.close(fig)


# ══════════════════════════════════════════════════════════════
# Report
# ══════════════════════════════════════════════════════════════

def generate_report(results, output_dir):
    lines = ["=" * 70, "  BioSentry IncuSense v2.1 — Report Test", "=" * 70, ""]

    def dump(data, indent=0):
        prefix = "  " * indent
        for k, v in data.items():
            if isinstance(v, dict):
                lines.append(f"{prefix}[{k}]")
                dump(v, indent + 1)
            elif isinstance(v, float):
                lines.append(f"{prefix}{k}: {v:.6g}")
            elif isinstance(v, bool):
                lines.append(f"{prefix}{k}: {'YES' if v else 'NO'}")
            else:
                lines.append(f"{prefix}{k}: {v}")

    for section, data in results.items():
        lines.append(f"\n{'─' * 50}")
        lines.append(f"  {section.upper()}")
        lines.append(f"{'─' * 50}")
        dump(data)

    report = "\n".join(lines)
    path = output_dir / "test_report_v21.txt"
    path.write_text(report)
    print(report)
    return path


# ══════════════════════════════════════════════════════════════
# Main
# ══════════════════════════════════════════════════════════════

def main():
    parser = argparse.ArgumentParser(description="BioSentry v2.1 Test Suite")
    parser.add_argument("--circuit", "-c", default="all",
                        choices=["tia", "heater", "fan", "power", "cross", "all"])
    args = parser.parse_args()

    results = {}

    circuits = {
        "heater": ("HEATER_DRIVER", analyze_heater, plot_heater),
        "tia": ("TIA_SENSOR_v2.1", analyze_tia_v21, plot_tia),
        "fan": ("GAS_FLOW_CONTROLLER", analyze_fan, None),
        "power": ("ETHERNET_POWER", analyze_power, None),
        "cross": ("CROSS_INTERFERENCE_v2.1", analyze_cross_interference, plot_cross_interference),
    }

    to_run = list(circuits.keys()) if args.circuit == "all" else [args.circuit]

    for name in to_run:
        section_name, analyzer, plotter = circuits[name]
        print(f"\n{'='*50}")
        print(f"  Analisi: {section_name}")
        print(f"{'='*50}")
        data = analyzer()
        results[section_name] = data
        if plotter and MPL:
            plotter(data, OUTPUT_DIR)
            print(f"  Grafico salvato in output/")

    report_path = generate_report(results, OUTPUT_DIR)
    print(f"\nReport: {report_path}")
    print(f"Output: {OUTPUT_DIR}")


if __name__ == "__main__":
    main()
