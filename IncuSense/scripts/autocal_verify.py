#!/usr/bin/env python3
"""
Verifica riproducibile (senza hardware/DB) dell'integrazione della curva di calibrazione
e della policy di auto-calibrazione. E' il "port" fedele di:
  - backend .../service/CalibrationCurve.java        (matematica della curva)
  - backend .../service/AutoCalibrationPolicy.java   (bande + guard-rail)

Coefficienti regressione (owner):  alpha=0.345 (+/-0.051), beta=0.254 (+/-0.019), R^2=0.98
Mappatura scelta:  alpha = log10(a)  ->  a = 10^alpha = 2.2131 ;  b = beta = 0.254
Modello:  response = a * ppm^b
Range operativo piattaforma: 30000..70000 ppm (incubatore 5% CO2 = 50k).
Range sperimentale paper: 250..5000 ppm, mantenuto come metadata.

Uso:  python3 scripts/autocal_verify.py
"""
import math

ALPHA, BETA = 0.345, 0.254
A, B = 10 ** ALPHA, BETA
VALID = (30000.0, 70000.0)
EPS = 1e-9


def in_range(ppm):
    return ppm >= VALID[0] * (1 - EPS) and ppm <= VALID[1] * (1 + EPS)


def ppm_to_resp(ppm):
    return A * ppm ** B if in_range(ppm) else None


def resp_to_ppm(r):
    if not (r > 0):
        return None
    ppm = (r / A) ** (1.0 / B)
    return ppm if (math.isfinite(ppm) and in_range(ppm)) else None


def drift_to_ppm_fraction(df):
    return df / B  # dPpm/ppm = (dResponse/response)/b


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


def main():
    ok = True
    print(f"a = 10^alpha = {A:.4f}   b = beta = {B}")

    print("\n[1] round-trip ppm -> response -> ppm")
    for ppm in (30000, 45000, 50000, 60000, 70000):
        r = ppm_to_resp(ppm)
        back = resp_to_ppm(r)
        good = back is not None and abs(back - ppm) < 1e-6
        ok &= good
        print(f"    ppm={ppm:>5}  resp={r:7.3f}  back={back:9.3f}  {'OK' if good else 'FAIL'}")

    print("\n[2] range operativo incubatore")
    for ppm, expected in ((29999, False), (50000, True), (70001, False)):
        got = ppm_to_resp(ppm) is not None
        good = got == expected
        ok &= good
        print(f"    ppm_to_resp({ppm}) in range ? {'OK' if good else 'FAIL'}")

    print("\n[3] bridge drift response -> errore ppm (usa solo b)")
    for d in (0.05, 0.10, 0.20):
        print(f"    drift {d*100:.0f}%  ->  ppm err {drift_to_ppm_fraction(d)*100:.1f}%")

    print("\n[4] policy truth-table")
    base = dict(sane=True, regime=True, health="OK", drift=8.0, n=30, cool=True, pending=False, impl=8000.0)
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
        a = dict(base); a.update(kw)
        got = decide(**a)
        good = got == exp
        ok &= good
        print(f"    [{'OK' if good else 'FAIL'}] {name:24s} -> {got}")

    print("\nRESULT:", "ALL CHECKS PASS" if ok else "FAILURES PRESENT")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
