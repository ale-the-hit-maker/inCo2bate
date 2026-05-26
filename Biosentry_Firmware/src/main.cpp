#include <Arduino.h>

// ===================================================================
// MAPPATURA PIN HARDWARE (Derivata dallo schema LTspice v2.1)
// ===================================================================

// --- Input Analogici ---
#define PIN_TIA_ADC      0   // Lettura tensione dal sensore TIA (GPIO0)

// --- Output PWM (Controllo Potenza) ---
#define PIN_FAN_PWM      1   // Comando ventola DC (GPIO1)
#define PIN_HEATER_PWM   3   // Comando riscaldatore (GPIO3 - Modifica ECO v2.1)

// --- Bus SPI (W5500 Ethernet) ---
#define PIN_SPI_SCK      4
#define PIN_SPI_CS       5
#define PIN_SPI_MISO     6
#define PIN_SPI_MOSI     7

// --- Bus I2C (SHT41) ---
#define PIN_I2C_SDA      8
#define PIN_I2C_SCL      9


// --- DICHIARAZIONE DELLE FUNZIONI DEI TASK ---
void Task_ThermalControl(void *pvParameters);
void Task_Sensing(void *pvParameters);
void Task_NetworkAndFS(void *pvParameters);

void setup() {
    Serial.begin(115200);
    // Configurazione hardware pin...

    // CHIEDIAMO A FREERTOS DI CREARE I NOSTRI TASK PARALLELI
    
    // 1. Task del Riscaldatore (Priorità Altissima = 3)
    xTaskCreate(
        Task_ThermalControl,   // Nome della funzione da eseguire
        "Thermal_PID",         // Nome per il debug
        4096,                  // Memoria RAM dedicata (Stack)
        NULL,                  // Nessun parametro passato
        3,                     // Priorità Alta (Niente deve fermarlo!)
        NULL                   // Nessun handle necessario
    );

    // 2. Task Lettura Sensore (Priorità Media = 2)
    xTaskCreate(Task_Sensing, "Sensore_TIA", 4096, NULL, 2, NULL);

    // 3. Task Rete ed Emergenza (Priorità Bassa = 1)
    xTaskCreate(Task_NetworkAndFS, "Rete_LittleFS", 8192, NULL, 1, NULL);
}

void loop() {
    // QUESTO RIMANE VUOTO! Non lo usiamo.
    // L'ESP32 dedicherà tutta la potenza ai Task creati sopra.
    vTaskDelete(NULL); // Elimina questo loop per risparmiare RAM
}

// --- IMPLEMENTAZIONE DEI TASK (Ognuno è un mini-programma infinito) ---

void Task_ThermalControl(void *pvParameters) {
    // Mini-setup specifico del task termico...
    
    while (1) { // Ciclo infinito indipendente
        // Calcola PID
        // Aggiorna PWM
        vTaskDelay(pdMS_TO_TICKS(10)); // Aspetta 10ms (100Hz). 
        // Durante questa attesa, FreeRTOS dà la CPU agli altri Task!
    }
}

void Task_Sensing(void *pvParameters) {
    while (1) {
        // Leggi ADC 64 volte e fai la media
        // Calcola ppm CO2
        vTaskDelay(pdMS_TO_TICKS(1000)); // Aspetta 1 secondo (1Hz)
    }
}

void Task_NetworkAndFS(void *pvParameters) {
    while (1) {
        // Se c'è rete -> Manda MQTT
        // Se non c'è rete -> Scrivi JSON su LittleFS
        vTaskDelay(pdMS_TO_TICKS(5000)); // Aspetta 5 secondi
    }
}

void setup() {
    // Inizializzazione della porta seriale per il debug (log a schermo)
    Serial.begin(115200);
    Serial.println("Avvio BioSentry v2.1...");

    // 1. DICHIARIAMO LA DIREZIONE DEI PIN AL MICROCONTROLLORE
    pinMode(PIN_TIA_ADC, INPUT);       // L'ADC è un ingresso (legge)
    pinMode(PIN_FAN_PWM, OUTPUT);      // La ventola è un'uscita (comanda)
    pinMode(PIN_HEATER_PWM, OUTPUT);   // Il riscaldatore è un'uscita (comanda)

    // 2. STATO DI SICUREZZA INIZIALE (Spento a freddo)
    digitalWrite(PIN_FAN_PWM, LOW);
    digitalWrite(PIN_HEATER_PWM, LOW); 
}

void loop() {
    // Qui andrà la logica del tuo programma (PID, lettura, Ethernet)
}





// ===================================================================

// ALGORITMO OPERATIVO MICROPROCESSSORE (ESP32 + FreeRTOS)

// 1. Inizializzazione hardware (pinMode, Serial, etc)

// 2. Boot del sistema operativo FreeRTOS e creazione dei Task paralleli

// 3. Ogni Task è un ciclo infinito che esegue una funzione specifica (PID, Sensing, Rete)

// ...


