#!/usr/bin/env python3
"""
Verifica riproducibile (senza hardware/DB) dell'integrazione della curva di calibrazione,
della CURVA DI STABILITA' (V7) e della policy di auto-calibrazione. Port fedele di:
  - backend .../service/CalibrationCurve.java        (matematica curva + ancoraggio + banda)
  - backend .../service/AutoCalibrationPolicy.java   (bande + guard-rail)

Coefficienti regressione (owner):  alpha=0.345 (+/-0.051), beta=0.254 (+/-0.019), R^2=0.98
  alpha = log10(a)  ->  a = 10^alpha = 2.2131 (SCALA PAPER) ;  b = beta = 0.254 (invariante)
Modello:  response = a * ppm^b
Range operativo incubatore: 30000..70000 ppm (5% CO2 = 50k). Sperimentale paper: 250..5000 ppm.

NB: 'a' (paper) NON si applica al sensor_response del firmware [0,1] (scala diversa). Per il
50k usiamo l'ANCORAGGIO alla scala firmware: ppm = anchor_ppm * (resp/anchor_resp)^(1/b).

Uso:  python3 scripts/autocal_verify.py
"""
import math

ALPHA, BETA = 0.345, 0.254
A, B = 10 ** ALPHA, BETA
VALID = (30000.0, 70000.0)
SIGMA_LOG = 0.0071          # V7: meta'-ampiezza banda di stabilita'
SETPOINT = 50000.0
EPS = 1e-9


def in_range(ppm):
    return ppm >= VALID[0] * (1 - EPS) and ppm <= VALID[1] * (1 + EPS)


def ppm_to_resp(ppm):       # scala paper (solo per round-trip matematico)
    return A * ppm ** B if in_range(ppm) else None


def resp_to_ppm(r):         # scala paper
    if not (r > 0):
        return None
    ppm = (r / A) ** (1.0 / B)
    return ppm if (math.isfinite(ppm) and in_range(ppm)) else None


def drift_to_ppm_fraction(df):
    return df / B  # dPpm/ppm = (dResponse/response)/b  (approx primo ordine)


# ---- V7: ancoraggio scala firmware (CalibrationCurve.ppmFromAnchoredResponse) ----
def ppm_from_anchored(resp, anchor_resp, anchor_ppm):
    if not (resp > 0 and anchor_resp > 0 and anchor_ppm > 0):
        return None
    return anchor_ppm * (resp / anchor_resp) ** (1.0 / B)


def implied_setpoint_error_ppm(resp, baseline, setpoint):
    p = ppm_from_anchored(resp, baseline, setpoint)
    return None if p is None else p - setpoint


def stability_band_upper(k_sigma):  # CalibrationCurve.stabilityBandFractionUpper
    return 10.0 ** (k_sigma * SIGMA_LOG) - 1.0


# ---------------- policy (== AutoCalibrationPolicy.decide) ----------------
CFG = dict(T=5.0, EOL=20.0, MINS=20, MAXOFF=15000.0)


def decide(sane, regime, health, drift, n, cool, pending, impl):
    if not sane:
        return "NONE"
    if not regime:
        return "NONE"
    ad = abs(drift)
    if ad < CFG["T"]:
        return "NONE"
    if ad >= CFG["EOL"]:
        return "CRITICAL_NOACTION"
    if n < CFG["MINS"]:
        return "NONE"
    if pending:
        return "NONE"
    if not cool:
        return "NONE"
    if health == "CRITICAL":
        return "SKIP"
    if abs(impl) > CFG["MAXOFF"]:
        return "SKIP"
    return "COMMAND"


def approx(x, y, tol=1e-6):
    return abs(x - y) < tol


def main():
    ok = True
    print(f"a(paper) = 10^alpha = {A:.4f}   b = beta = {B}   sigma_log = {SIGMA_LOG}")

    print("\n[1] round-trip ppm -> response -> ppm (scala paper, solo matematica)")
    for ppm in (30000, 45000, 50000, 60000, 70000):
        r = ppm_to_resp(ppm)
        back = resp_to_ppm(r)
        good = back is not None and approx(back, ppm)
        ok &= good
        print(f"    ppm={ppm:>5}  resp={r:7.3f}  back={back:9.3f}  {'OK' if good else 'FAIL'}")

    print("\n[2] range operativo incubatore")
    for ppm, expected in ((29999, False), (50000, True), (70001, False)):
        good = (ppm_to_resp(ppm) is not None) == expected
        ok &= good
        print(f"    ppm_to_resp({ppm}) in range ? {'OK' if good else 'FAIL'}")

    print("\n[3] bridge drift response -> errore ppm (approx, usa solo b)")
    for d in (0.05, 0.10, 0.20):
        print(f"    drift {d*100:.0f}%  ->  ppm err ~{drift_to_ppm_fraction(d)*100:.1f}%")

    print("\n[4] ANCORAGGIO scala firmware: baseline misurata @ setpoint (resp ~0.53)")
    base = 0.53  # sensor_response firmware a 50k (baseline ADC 1380, adc 2115)
    rt = ppm_from_anchored(base, base, SETPOINT)
    good = rt is not None and approx(rt, SETPOINT, 1e-3)
    ok &= good
    print(f"    anchored(base,base,50k) = {rt:.1f}  (atteso 50000)  {'OK' if good else 'FAIL'}")
    for drift in (0.05, 0.10, 0.20):
        r = base * (1 + drift)
        ppm = ppm_from_anchored(r, base, SETPOINT)
        err = implied_setpoint_error_ppm(r, base, SETPOINT)
        # esatto: setpoint*((1+drift)^(1/b) - 1)
        exp_err = SETPOINT * ((1 + drift) ** (1.0 / B) - 1.0)
        good = approx(err, exp_err, 1e-3)
        ok &= good
        print(f"    drift resp +{drift*100:.0f}%  -> ppm implicati {ppm:8.0f}  err {err:+8.0f}  {'OK' if good else 'FAIL'}")

    print("\n[5] guard-rail max-offset cattura l'amplificazione (drift resp piccolo -> offset enorme)")
    for drift in (0.05, 0.10):
        err = implied_setpoint_error_ppm(base * (1 + drift), base, SETPOINT)
        blocked = abs(err) > CFG["MAXOFF"]
        print(f"    drift +{drift*100:.0f}% -> |offset implicato| {abs(err):.0f} ppm  {'> MAXOFF (SKIP)' if blocked else '<= MAXOFF (ok)'}")

    print("\n[6] CURVA DI STABILITA': banda da sigma_log")
    b1 = stability_band_upper(1)
    b3 = stability_band_upper(3)
    good = approx(b3, 0.05, 2e-3)  # 3 sigma ~= soglia azione 5%
    ok &= good
    print(f"    banda +/-1 sigma = {b1*100:.2f}%   +/-3 sigma = {b3*100:.2f}%  (atteso ~5%)  {'OK' if good else 'FAIL'}")
    k_eol = math.log10(1.20) / SIGMA_LOG
    print(f"    soglia EOL 20% corrisponde a ~{k_eol:.1f} sigma")

    print("\n[7] policy truth-table")
    base_in = dict(sane=True, regime=True, health="OK", drift=8.0, n=30, cool=True, pending=False, impl=8000.0)
    cases = [
        ("azionabile ok", {}, "COMMAND"),
        ("drift 3%", {"drift": 3.0}, "NONE"),
        ("drift 25%", {"drift": 25.0}, "CRITICAL_NOACTION"),
        ("non a regime", {"regime": False}, "NONE"),
        ("pochi campioni", {"n": 5}, "NONE"),
        ("pending", {"pending": True}, "NONE"),
        ("cooldown", {"cool": False}, "NONE"),
        ("salute CRITICAL", {"health": "CRITICAL"}, "SKIP"),
        ("oltre max-offset", {"impl": 20000.0}, "SKIP"),
        ("bordo T_action 5%", {"drift": 5.0}, "COMMAND"),
        ("bordo EOL 20%", {"drift": 20.0}, "CRITICAL_NOACTION"),
    ]
    for name, kw, exp in cases:
        a = dict(base_in); a.update(kw)
        got = decide(**a)
        good = got == exp
        ok &= good
        print(f"    [{'OK' if good else 'FAIL'}] {name:24s} -> {got}")

    print("\nRESULT:", "ALL CHECKS PASS" if ok else "FAILURES PRESENT")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
