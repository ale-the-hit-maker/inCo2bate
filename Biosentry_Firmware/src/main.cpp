#include <Arduino.h>
#include <SPI.h>

// ===================================================================
// MAPPATURA PIN HARDWARE (Derivata dallo schema LTspice v2.1)
// ===================================================================

// --- Input Analogici ---
#define PIN_TIA_ADC      0   // Lettura tensione dal sensore TIA (GPIO0)
#define PIN_VMON_ADC     2   // NUOVO: Monitoraggio 12V (GPIO2) - Boot-safe HIGH

// --- Output PWM e Digitali (Controllo Potenza) ---
#define PIN_FAN_PWM      1   // Comando ventola DC (GPIO1)
#define PIN_HEATER_PWM   3   // Comando base Q1 riscaldatore (GPIO3 - Modifica ECO v2.1)
#define PIN_HEATER_REGIME 10 // NUOVO: Comando gate M1 (GPIO10) - Selettore Supply/Measurement

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

void Task_ThermalControl(void *pvParameters) {
    // Mini-setup specifico del task termico
    // (In un caso reale qui andrebbe l'inizializzazione del PID)
    
    while (1) { // Ciclo infinito indipendente
        
        // 1. Regime "Supply" (9 ms) - M1 ON (bypassa Rref), Q1 in PWM
        digitalWrite(PIN_HEATER_REGIME, HIGH);
        analogWrite(PIN_HEATER_PWM, 25); // Esempio: 10% duty cycle (circa 25/255)
        vTaskDelay(pdMS_TO_TICKS(9)); 

        // 2. Regime "Measurement" (1 ms) - M1 OFF, Q1 saturato per la misura
        digitalWrite(PIN_HEATER_REGIME, LOW);
        digitalWrite(PIN_HEATER_PWM, HIGH); // Q1 full ON (percorso V12V -> R_heater -> Rref -> Q1)
        vTaskDelay(pdMS_TO_TICKS(1)); 
    }
}

void Task_Sensing(void *pvParameters) {
    while (1) {
        // Oversampling ADC: Leggi 64 volte e fai la media
        long sum_tia = 0;
        long sum_vmon = 0;
        
        for(int i = 0; i < 64; i++) {
            sum_tia += analogRead(PIN_TIA_ADC);
            sum_vmon += analogRead(PIN_VMON_ADC);
        }
        
        // (Qui andrebbe il calcolo delle medie e conversione ppm CO2)
        
        vTaskDelay(pdMS_TO_TICKS(1000)); // Aspetta 1 secondo (1Hz)
    }
}

void Task_NetworkAndFS(void *pvParameters) {
    while (1) {
        // Se c'è rete -> Manda MQTT via W5500
        // Se non c'è rete -> Scrivi JSON su LittleFS (backlog)
        vTaskDelay(pdMS_TO_TICKS(5000)); // Aspetta 5 secondi
    }
}

void setup() {
    // Inizializzazione della porta seriale per il debug (log a schermo)
    Serial.begin(115200);
    Serial.println("Avvio BioSentry v2.1...");

    // 1. DICHIARIAMO LA DIREZIONE DEI PIN AL MICROCONTROLLORE
    pinMode(PIN_TIA_ADC, INPUT);       
    pinMode(PIN_VMON_ADC, INPUT);      // Aggiunto GPIO2
    
    pinMode(PIN_FAN_PWM, OUTPUT);      
    pinMode(PIN_HEATER_PWM, OUTPUT);   
    pinMode(PIN_HEATER_REGIME, OUTPUT); // Aggiunto GPIO10

    // 2. STATO DI SICUREZZA INIZIALE (Spento a freddo)
    digitalWrite(PIN_FAN_PWM, LOW);
    digitalWrite(PIN_HEATER_PWM, LOW); 
    digitalWrite(PIN_HEATER_REGIME, LOW); // M1 OFF (Sicurezza hardware ora riflessa nel sw)

    // CREAZIONE DEI TASK FREE RTOS (Allocazione Dinamica confermata per test su Wokwi)

    // 1. Task del Riscaldatore (Priorità Altissima = 3)
    xTaskCreate(Task_ThermalControl, "Thermal_PID", 4096, NULL, 3, NULL);

    // 2. Task Lettura Sensore (Priorità Media = 2)
    xTaskCreate(Task_Sensing, "Sensore_TIA", 4096, NULL, 2, NULL);

    // 3. Task Rete ed Emergenza (Priorità Bassa = 1)
    xTaskCreate(Task_NetworkAndFS, "Rete_LittleFS", 8192, NULL, 1, NULL);
}

void loop() {
    // Il loop è vuoto. FreeRTOS gestisce tutto nei task paralleli.
    vTaskDelete(NULL);
}


// ===================================================================

// ALGORITMO OPERATIVO MICROPROCESSSORE (ESP32 + FreeRTOS)

// 1. Inizializzazione hardware (pinMode, Serial, etc)

// 2. Boot del sistema operativo FreeRTOS e creazione dei Task paralleli

// 3. Ogni Task è un ciclo infinito che esegue una funzione specifica (PID, Sensing, Rete)

// ...