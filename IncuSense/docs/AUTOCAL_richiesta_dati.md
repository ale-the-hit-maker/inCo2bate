# Richiesta dati — Caratterizzazione sensore CO₂ Na:In₂O₃

Ciao! Per implementare l'auto-calibrazione sulla piattaforma mi servono due serie di misure
sul sensore. Condizioni comuni a tutte: heater a 250 °C a regime, ambiente tipo incubatore
(~37 °C, RH 90–95%), e **la concentrazione di CO₂ verificata sempre con uno strumento di
riferimento certificato indipendente** (es. NDIR), mai col sensore stesso.

## Misura 1 — Curva di risposta (response vs concentrazione)

Esporre il sensore a concentrazioni note crescenti e poi decrescenti:

**400 (aria) – 1.000 – 5.000 – 10.000 – 20.000 – 30.000 – 40.000 – 50.000 – 60.000 ppm**

Importante: ci serve il range alto (30.000–50.000 ppm, le condizioni reali dell'incubatore),
non solo quello basso già usato in pubblicazione.

Per ogni concentrazione:
- attendere il plateau (lettura stabile),
- registrare almeno 30 campioni consecutivi (meglio: 5 minuti a 1 Hz),
- 3 ripetizioni, sia in salita che in discesa di concentrazione.

Se possibile su 2–3 esemplari di sensore, per stimare la variabilità tra unità.

## Misura 2 — Stabilità nel tempo (invecchiamento)

Tenere il sensore sempre acceso nelle condizioni operative e, **una volta al giorno**
(minimo 2 volte/settimana), misurare la risposta in due punti fissi:
- aria pulita (≈400 ppm)
- 50.000 ppm (setpoint incubatore)

Durata: **almeno 7 settimane**. Annotare le ore cumulative di funzionamento del sensore.

## Formato dati

CSV, una riga per campione, con queste colonne:

`timestamp; sensor_id; ppm_riferimento; raw_adc; sensor_response; heater_temp_C; temp_ambiente_C; umidita_pct; direzione(su/giu); replica`

Per la Misura 2 aggiungere: `ore_operative_cumulative`.

## A cosa servono

- **Misura 1** → fit della curva di calibrazione (response = a·ppm^b) che la piattaforma usa
  per convertire la risposta del sensore in ppm.
- **Misura 2** → velocità del drift naturale del sensore: è il dato che permette al software
  di distinguere un sensore che invecchia (variazione lenta → auto-correzione) da un calo
  reale di CO₂ nell'incubatore (variazione rapida → allarme).

## Se il banco gas completo non è disponibile

Versione minima accettabile:
- Misura 1 ridotta a 3 punti: aria (400), un punto intermedio (~20.000), setpoint (50.000);
- Misura 2 ridotta a 1 lettura/settimana per almeno 4 settimane.

Grazie!
