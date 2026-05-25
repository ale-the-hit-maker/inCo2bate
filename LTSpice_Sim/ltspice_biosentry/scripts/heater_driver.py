import os
from PyLTSpice import SimRunner, SpiceEditor, RawRead

# 1. Inizializza il runner e punta al tuo file .net
LTC = SimRunner()
FILE_HOME_DIR = r"D:\USERDATA\Documents\LTspice"
netlist_file = FILE_HOME_DIR+r"\heater_driver.net"

# 2. Crea un editor per modificare la netlist al volo (opzionale)
editor = SpiceEditor(netlist_file)

# 3. Lancia la simulazione in background
print("Avvio simulazione in background...")
LTC.run(editor)
LTC.wait_completion()
print("Simulazione completata!")

# 4. Leggi i risultati dal file .raw generato
raw_file = "../../heater_driver_sim/heater_driver_1.raw"  # Il nome generato di default
if os.path.exists(raw_file):
    dati = RawRead(raw_file)

    # Estrai l'asse del tempo e la corrente dell'Heater
    tempo = dati.get_trace('time').get_time_axis()
    corrente = dati.get_trace('I(R_Heater)').get_wave()

    # Stampa l'ultimo valore per controllare
    print(f"La corrente finale è: {corrente[-1]} Ampere")
else:
    print("Errore: file .raw non trovato.")





