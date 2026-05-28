#include <Arduino.h>
#include <SPI.h>
#include <Wire.h>
#include <Adafruit_SHT4x.h>
#include <Ethernet.h>
#include <PubSubClient.h>
#include <ArduinoJson.h>
#include <LittleFS.h>
#include "esp_partition.h"

// ===================================================================
// Hardware pin mapping  (INVARIATO)
// ===================================================================
#define PIN_TIA_ADC        0
#define PIN_VMON_ADC       2

#define PIN_FAN_PWM        1
#define PIN_HEATER_PWM     3
#define PIN_HEATER_REGIME  10

#define LEDC_HEATER_CHAN   0
#define LEDC_HEATER_FREQ   1000
#define LEDC_HEATER_RES    8

#define LEDC_FAN_CHAN      1
#define LEDC_FAN_FREQ      25000
#define LEDC_FAN_RES       8

#define PIN_SPI_SCK        4
#define PIN_SPI_CS         5
#define PIN_SPI_MISO       6
#define PIN_SPI_MOSI       7

#define PIN_I2C_SDA        8
#define PIN_I2C_SCL        9

// ===================================================================
// [ADATTATO] Identità del modulo e contratto MQTT IncuSense
//
// L'hub_id è derivato a compile-time dai tre identificatori esistenti.
// Il backend si aspetta i topic nella forma:
//   incusense/hubs/{hub_id}/telemetry   <- pubblica misure
//   incusense/hubs/{hub_id}/status      <- pubblica heartbeat / LWT
//   incusense/hubs/{hub_id}/commands    <- sottoscrive comandi
//   incusense/hubs/{hub_id}/ack         <- pubblica ACK calibrazione
//
// Il device_id identifica il sensore CO2 specifico all'interno dell'hub.
// ===================================================================
#ifndef MQTT_BROKER_HOST
#define MQTT_BROKER_HOST   "192.168.1.100"
#endif

#define MQTT_BROKER_PORT   1883

#define MQTT_LAB_ID        "lab_alpha"
#define MQTT_INC_ID        "inc_01"
#define MQTT_NODE_ID       "node_a"

// hub_id univoco del modulo (usato come campo JSON e come segmento di topic)
#define MQTT_HUB_ID    MQTT_LAB_ID "_" MQTT_INC_ID "_" MQTT_NODE_ID
// device_id del sensore CO2 (un hub può avere più sensing unit in futuro)
#define MQTT_DEVICE_ID MQTT_HUB_ID "-co2"

// [ADATTATO] Topic compile-time — non più buildMqttTopic() a runtime
#define TOPIC_MEASUREMENTS "incusense/hubs/" MQTT_HUB_ID "/telemetry"
#define TOPIC_STATUS       "incusense/hubs/" MQTT_HUB_ID "/status"
#define TOPIC_COMMANDS     "incusense/hubs/" MQTT_HUB_ID "/commands"
#define TOPIC_ACK          "incusense/hubs/" MQTT_HUB_ID "/ack"

// Payload LWT pubblicato dal broker se il dispositivo si disconnette
// in modo anomalo (caduta link, watchdog): il backend lo riceve su TOPIC_STATUS
#define MQTT_LWT_PAYLOAD   "{\"hub_id\":\"" MQTT_HUB_ID "\",\"status\":\"offline\"}"

// [ADATTATO] Buffer PubSubClient: il payload con hub_id+device_id supera
// i 256 byte di default; 512 copre comodamente tutti i campi
#define MQTT_BUFFER_SIZE   512

// ===================================================================
// PID e file di backlog  (INVARIATI)
// ===================================================================
#define TARGET_TEMP_C      250.0f
#define PID_Kp             5.0f
#define PID_Ki             0.1f
#define PID_Kd             1.0f
#define PID_I_MAX          100.0f

#define BACKLOG_FILE       "/backlog.jsonl"

// ===================================================================
// [ADATTATO] Calibrazione — ADC baseline per zero-cal e sensor_response
//
// ADC_AIR_BASELINE: valore ADC in aria pulita a 0 ppm CO2.
//   Derivato dalla specifica hardware: 1380 ADC = corrente in aria.
//   Può essere aggiornato via ZERO_CAL command dal backend.
// ===================================================================
#define ADC_AIR_BASELINE   1380.0f

// ===================================================================
// Strutture dati
// ===================================================================

// [ADATTATO] Aggiunto raw_adc e sensor_response al payload interno.
// I nomi degli altri campi sono invariati per minimizzare il diff,
// ma vengono serializzati con i nuovi nomi che il backend si aspetta.
struct Payload {
    uint32_t ts;
    float    co2_ppm;
    float    heater_temp;   // serializzato come "heater_temp"
    float    env_temp;      // serializzato come "env_temp"
    float    env_hum;       // serializzato come "env_hum"
    float    rail_12v;
    uint16_t raw_adc;       // [ADATTATO] valore ADC grezzo 12-bit
    float    sensor_response; // [ADATTATO] |I_air - I| / I_air
};

// [ADATTATO] Struttura per gli ACK di calibrazione.
// Il callback MQTT non può fare publish() in modo sicuro con PubSubClient
// (rischio di re-entrant call inside loop()). Usiamo una coda FreeRTOS
// per disaccoppiare la ricezione del comando dalla pubblicazione dell'ACK.
struct AckMessage {
    long   event_id;
    char   command[16];  // "ZERO_CAL" | "OFFSET_CAL"
    char   status[8];    // "OK" | "ERROR"
};

// ===================================================================
// Variabili globali
// ===================================================================
static QueueHandle_t payloadQueue = NULL;
// [ADATTATO] Coda per ACK calibrazione (dimensione 4: raramente ne arrivano più di 1)
static QueueHandle_t ackQueue     = NULL;

static EthernetClient ethClient;
static PubSubClient   mqttClient(ethClient);
static Adafruit_SHT4x sht4 = Adafruit_SHT4x();
static bool has_sht41 = false;

static float simulatedTemp    = 25.0f;
static float heaterTemp       = 25.0f;
static int   currentFanDuty   = 0;
static int   currentHeaterDuty= 0;

// [ADATTATO] Stato calibrazione — volatile perché scritto dal callback
// (nel contesto di Task_NetworkAndFS) e letto da Task_Sensing.
// Per sicurezza cross-task su single-core ESP32-C3: volatile basta,
// ma le scritture sono comunque serializzate dallo scheduler cooperativo.
static volatile float cal_air_baseline = ADC_AIR_BASELINE; // aggiornato da ZERO_CAL
static volatile float cal_offset_ppm   = 0.0f;             // aggiornato da OFFSET_CAL
static volatile bool  do_zero_cal      = false;
static volatile bool  do_offset_cal    = false;
static volatile float pending_offset   = 0.0f;
static volatile long  pending_event_id = -1L;

// ===================================================================
// Dichiarazioni forward
// ===================================================================
void Task_ThermalControl(void *pvParameters);
void Task_Sensing(void *pvParameters);
void Task_NetworkAndFS(void *pvParameters);

// [ADATTATO] buildMqttTopic() rimossa: sostituita dai #define compile-time
bool connectNetwork();
bool connectMqtt();
void publishTelemetry(const Payload &payload);
void appendBacklog(const Payload &payload);
bool flushBacklog();
void publishStatus(const char *message);
uint32_t currentEpochSeconds();

// [ADATTATO] Nuove funzioni per il contratto IncuSense
void mqttCallback(char *topic, byte *payload, unsigned int length);
void publishAck(long eventId, const char *command, const char *status);

// ===================================================================
// Funzioni di rete e filesystem
// ===================================================================

bool connectNetwork() {
    if (Ethernet.linkStatus() == LinkON) {
        Ethernet.maintain();
        return true;
    }

    uint8_t mac[6] = {0xDE, 0xAD, 0xBE, 0xEF, 0xFE, 0xED};
    IPAddress localIp(192, 168, 1, 101);
    IPAddress dns(192, 168, 1, 1);
    IPAddress gateway(192, 168, 1, 1);
    IPAddress subnet(255, 255, 255, 0);

    Ethernet.init(PIN_SPI_CS);
    SPI.begin(PIN_SPI_SCK, PIN_SPI_MISO, PIN_SPI_MOSI, PIN_SPI_CS);

    Serial.println("[NET] Initializing wired Ethernet");
    int dhcpOk = Ethernet.begin(mac);
    if (dhcpOk == 0) {
        Serial.println("[NET] DHCP failed, using static Ethernet address");
        Ethernet.begin(mac, localIp, dns, gateway, subnet);
    }

    delay(1000);

    if (Ethernet.hardwareStatus() == EthernetNoHardware) {
        Serial.println("[NET] Ethernet controller not found. Check W5500 wiring and CS pin.");
        return false;
    }

    if (Ethernet.linkStatus() != LinkON) {
        Serial.println("[NET] Ethernet link is down. Check cable/switch/PoE injector.");
        return false;
    }

    Serial.printf("[NET] Ethernet connected. IP=%s broker=%s:%d\n",
                  Ethernet.localIP().toString().c_str(), MQTT_BROKER_HOST, MQTT_BROKER_PORT);
    return true;
}

// [ADATTATO] connectMqtt() — aggiunge LWT, callback, buffer size e subscribe
bool connectMqtt() {
    if (mqttClient.connected()) {
        mqttClient.loop();
        return true;
    }

    // [ADATTATO] Imposta buffer più grande prima di qualsiasi operazione
    mqttClient.setBufferSize(MQTT_BUFFER_SIZE);

    // [ADATTATO] Registra il callback per i messaggi in arrivo (comandi calibrazione)
    mqttClient.setCallback(mqttCallback);

    char clientId[48];
    snprintf(clientId, sizeof(clientId), "biosentry-%s", MQTT_NODE_ID);

    mqttClient.setServer(MQTT_BROKER_HOST, MQTT_BROKER_PORT);
    mqttClient.setKeepAlive(10);

    // [ADATTATO] Last Will Testament: il broker pubblica automaticamente
    // "offline" su TOPIC_STATUS se il dispositivo perde la connessione
    // in modo anomalo (timeout keepalive, reset hardware, cavo scollegato).
    // Il backend lo intercetta su incusense/hubs/{hub_id}/status.
    bool connected = mqttClient.connect(
        clientId,
        nullptr,               // username (non usato in questa versione)
        nullptr,               // password
        TOPIC_STATUS,          // LWT topic
        1,                     // LWT QoS
        true,                  // LWT retain (persiste per i client che si connettono dopo)
        MQTT_LWT_PAYLOAD       // LWT payload
    );

    if (connected) {
        // [ADATTATO] Sottoscrive al topic comandi per ricevere
        // ZERO_CAL e OFFSET_CAL dal backend (RF-09, RF-10)
        bool subOk = mqttClient.subscribe(TOPIC_COMMANDS, 1);
        Serial.printf("[MQTT] Connected to %s:%d. Sub commands: %s\n",
                      MQTT_BROKER_HOST, MQTT_BROKER_PORT, subOk ? "OK" : "FAIL");
    } else {
        Serial.printf("[MQTT] Connect failed. state=%d broker=%s:%d\n",
                      mqttClient.state(), MQTT_BROKER_HOST, MQTT_BROKER_PORT);
    }
    return connected;
}

// ===================================================================
// [ADATTATO] mqttCallback — gestisce i comandi in arrivo dal backend
//
// Formato atteso del payload JSON (pubblicato da CalibrationService.java):
//   ZERO_CAL:   {"command":"ZERO_CAL",  "event_id":42}
//   OFFSET_CAL: {"command":"OFFSET_CAL","offset_ppm":250.0,"event_id":43}
//
// NOTA ARCHITETTURALE: questo callback viene eseguito all'interno di
// mqttClient.loop(), ovvero nel contesto di Task_NetworkAndFS.
// Non fa mai publish() direttamente (rischio re-entrant call), ma
// incoda l'ACK su ackQueue per farlo inviare da Task_NetworkAndFS
// al ciclo successivo.
// ===================================================================
void mqttCallback(char *topic, byte *rawPayload, unsigned int length) {
    // Copia payload in buffer null-terminato (rawPayload non lo è)
    char buf[256];
    size_t safeLen = min((unsigned int)(sizeof(buf) - 1), length);
    memcpy(buf, rawPayload, safeLen);
    buf[safeLen] = '\0';

    Serial.printf("[CMD] Received on %s: %s\n", topic, buf);

    // Parsing del JSON comando
    StaticJsonDocument<256> doc;
    DeserializationError err = deserializeJson(doc, buf);
    if (err) {
        Serial.printf("[CMD] JSON parse error: %s\n", err.c_str());
        return;
    }

    const char *cmd     = doc["command"] | "";
    long        eventId = doc["event_id"] | -1L;

    AckMessage ack;
    ack.event_id = eventId;
    strlcpy(ack.status, "OK", sizeof(ack.status));

    if (strcmp(cmd, "ZERO_CAL") == 0) {
        // [ADATTATO] Segnala a Task_Sensing di acquisire la baseline corrente
        // come nuovo punto zero (sensore in aria pulita)
        strlcpy(ack.command, "ZERO_CAL", sizeof(ack.command));
        pending_event_id = eventId;
        do_zero_cal = true;
        Serial.println("[CAL] ZERO_CAL scheduled");

    } else if (strcmp(cmd, "OFFSET_CAL") == 0) {
        // [ADATTATO] Applica un offset di correzione in ppm.
        // L'utente espone il sensore a un gas di riferimento a concentrazione
        // nota (pending_offset) e il firmware calcola la correzione.
        float offsetPpm = doc["offset_ppm"] | 0.0f;
        strlcpy(ack.command, "OFFSET_CAL", sizeof(ack.command));
        pending_offset   = offsetPpm;
        pending_event_id = eventId;
        do_offset_cal    = true;
        Serial.printf("[CAL] OFFSET_CAL scheduled: target=%.1f ppm\n", offsetPpm);

    } else {
        Serial.printf("[CMD] Unknown command: %s\n", cmd);
        strlcpy(ack.command, cmd, sizeof(ack.command));
        strlcpy(ack.status, "ERROR", sizeof(ack.status));
    }

    // Incoda l'ACK — non fa publish() qui (non re-entrant safe con PubSubClient)
    if (ackQueue != NULL) {
        xQueueSend(ackQueue, &ack, 0); // non-blocking: se piena, l'ACK viene perso
    }
}

// ===================================================================
// [ADATTATO] publishAck — pubblica l'ACK di calibrazione
//
// Topic: incusense/hubs/{hub_id}/ack
// Payload: {"hub_id":"...","command":"ZERO_CAL","status":"OK","event_id":42}
//
// Chiamato da Task_NetworkAndFS dopo aver svuotato ackQueue,
// mai dall'interno di mqttCallback.
// ===================================================================
void publishAck(long eventId, const char *command, const char *status) {
    StaticJsonDocument<256> doc;
    doc["hub_id"]   = MQTT_HUB_ID;
    doc["command"]  = command;
    doc["status"]   = status;
    if (eventId >= 0) doc["event_id"] = eventId;
    doc["ts"]       = currentEpochSeconds();

    char buffer[256];
    serializeJson(doc, buffer, sizeof(buffer));

    if (mqttClient.publish(TOPIC_ACK, buffer)) {
        Serial.printf("[ACK] Published: cmd=%s status=%s event_id=%ld\n",
                      command, status, eventId);
    } else {
        Serial.println("[ACK] Publish failed");
    }
}

// ===================================================================
// [ADATTATO] appendBacklog — serializzazione con nuovi nomi dei campi
//
// I nomi dei campi nel backlog devono coincidere con quelli che il backend
// si aspetta da TOPIC_MEASUREMENTS, perché flushBacklog() li pubblica
// tal quali. Non è necessario un secondo parser lato firmware.
// ===================================================================
void appendBacklog(const Payload &payload) {
    File file = LittleFS.open(BACKLOG_FILE, FILE_APPEND);
    if (!file) {
        Serial.println("[BACKLOG] open append failed");
        return;
    }
    char line[240];
    int len = snprintf(line, sizeof(line),
        "{\"ts\":%u,\"co2_ppm\":%.2f,\"heater_temp\":%.2f,"
        "\"env_temp\":%.2f,\"env_hum\":%.2f,\"rail_12v\":%.2f}\n",
        payload.ts,
        payload.co2_ppm,
        payload.heater_temp,
        payload.env_temp,
        payload.env_hum,
        payload.rail_12v);

    file.write((const uint8_t *)line, len);
    file.close();
    Serial.println("[BACKLOG] appended payload to local storage");
}

// ===================================================================
// flushBacklog  (INVARIATA nella logica, topic aggiornato)
// ===================================================================
bool flushBacklog() {
    if (!LittleFS.exists(BACKLOG_FILE)) return true;

    File file = LittleFS.open(BACKLOG_FILE, FILE_READ);
    if (!file) return false;

    bool allSent = true;
    while (file.available()) {
        String line = file.readStringUntil('\n');
        if (line.length() == 0) continue;

        // [ADATTATO] Usa TOPIC_MEASUREMENTS (era buildMqttTopic "telemetry")
        if (!mqttClient.publish(TOPIC_MEASUREMENTS, line.c_str())) {
            allSent = false;
            break;
        }
    }
    file.close();

    if (allSent) {
        LittleFS.remove(BACKLOG_FILE);
        Serial.println("[BACKLOG] Data flushed to broker");
    }
    return allSent;
}

// ===================================================================
// [ADATTATO] publishTelemetry — JSON allineato al contratto del backend
//
// Campi pubblicati su TOPIC_MEASUREMENTS (incusense/hubs/{hub_id}/telemetry):
//   ts                    → epoch seconds UTC
//   co2_ppm               → CO2 calibrata in ppm
//   env_temp              → temperatura ambiente °C (SHT41)
//   env_hum               → umidità relativa % (SHT41)
//   heater_temp           → temperatura substrato sensore (PID)
//   rail_12v              → tensione rail 12V (diagnostica)
// ===================================================================
void publishTelemetry(const Payload &payload) {
    StaticJsonDocument<256> doc;
    doc["ts"]          = payload.ts;
    doc["co2_ppm"]     = serialized(String(payload.co2_ppm, 2));
    doc["heater_temp"] = serialized(String(payload.heater_temp, 2));
    doc["env_temp"]    = serialized(String(payload.env_temp, 2));
    doc["env_hum"]     = serialized(String(payload.env_hum, 2));
    doc["rail_12v"]    = serialized(String(payload.rail_12v, 3));

    char buffer[MQTT_BUFFER_SIZE];
    serializeJson(doc, buffer, sizeof(buffer));

    if (!mqttClient.publish(TOPIC_MEASUREMENTS, buffer)) {
        appendBacklog(payload);
    } else {
        Serial.printf("[MQTT] Published telemetry -> %s: %s\n", TOPIC_MEASUREMENTS, buffer);
    }
}

// ===================================================================
// [ADATTATO] publishStatus — allineato al contratto JSON del backend
//
// Il backend ascolta TOPIC_STATUS e aggiorna SensingHub.status in DB.
// Il payload deve contenere il campo "status" con valore "online"/"offline".
// ===================================================================
void publishStatus(const char *message) {
    StaticJsonDocument<128> doc;
    doc["hub_id"] = MQTT_HUB_ID;  // [ADATTATO] aggiunto per coerenza
    doc["status"] = message;
    doc["ts"]     = currentEpochSeconds();

    char buffer[128];
    serializeJson(doc, buffer, sizeof(buffer));

    // [ADATTATO] Usa TOPIC_STATUS (era buildMqttTopic "status")
    mqttClient.publish(TOPIC_STATUS, buffer);
}

uint32_t currentEpochSeconds() {
    time_t now = time(nullptr);
    if (now > 1000000000) {
        return (uint32_t)now;
    }
    return 1700000000UL + (millis() / 1000UL);
}

// ===================================================================
// TASK FreeRTOS
// ===================================================================

// ── Task_ThermalControl  (INVARIATO) ──────────────────────────────
void Task_ThermalControl(void *pvParameters) {
    TickType_t lastWakeTime = xTaskGetTickCount();
    float integral  = 0.0f;
    float prevError = 0.0f;
    uint32_t cycleCounter = 0;

    while (1) {
        float error = TARGET_TEMP_C - simulatedTemp;
        integral += error * 0.01f;
        integral = constrain(integral, -PID_I_MAX, PID_I_MAX);
        float derivative = (error - prevError) / 0.01f;
        float output = (PID_Kp * error) + (PID_Ki * integral) + (PID_Kd * derivative);
        prevError = error;

        currentHeaterDuty = constrain((int)round(output), 0, 255);

        if (simulatedTemp < TARGET_TEMP_C - 20.0f) {
            currentFanDuty = 25;
        } else {
            currentFanDuty = 127;
            if (currentHeaterDuty >= 250 && error > 2.0f) {
                int penalty = (int)(error * 10.0f);
                currentFanDuty = max(25, currentFanDuty - penalty);
            }
        }

        ledcWrite(LEDC_FAN_CHAN, currentFanDuty);

        digitalWrite(PIN_HEATER_REGIME, HIGH);
        ledcWrite(LEDC_HEATER_CHAN, currentHeaterDuty);
        vTaskDelayUntil(&lastWakeTime, pdMS_TO_TICKS(9));

        digitalWrite(PIN_HEATER_REGIME, LOW);
        ledcWrite(LEDC_HEATER_CHAN, 255);
        vTaskDelayUntil(&lastWakeTime, pdMS_TO_TICKS(1));

        if (currentHeaterDuty > 50) simulatedTemp += 0.4f;
        else simulatedTemp -= 0.2f;

        simulatedTemp = constrain(simulatedTemp, 20.0f, 280.0f);
        heaterTemp = simulatedTemp;

        cycleCounter++;
        if (cycleCounter >= 100) {
            Serial.printf("[THERM] T=%.1f/%.1f H=%d F=%d\n",
                          simulatedTemp, TARGET_TEMP_C, currentHeaterDuty, currentFanDuty);
            cycleCounter = 0;
        }
    }
}

// ── Task_Sensing  [ADATTATO: calibrazione + raw_adc + sensor_response] ──
void Task_Sensing(void *pvParameters) {
    float simulated_time = 0.0f;

    while (1) {
        // -----------------------------------------------------------
        // 1. DATA MOCKING (Sensore a 5% CO2)  (INVARIATO)
        // -----------------------------------------------------------
        float avgTia  = 1747.0f + 367.0f * sin(simulated_time);
        float avgVmon = 2948.0f;
        simulated_time += 0.05f;

        // -----------------------------------------------------------
        // 2. CONVERSIONE FISICA ADC -> PPM
        //
        // [ADATTATO] Usa cal_air_baseline invece della costante 1380.0f:
        // se il backend ha inviato ZERO_CAL, la baseline è stata
        // aggiornata al valore ADC corrente in aria pulita.
        // -----------------------------------------------------------
        float co2ppm = 400.0f;

        if (avgTia > cal_air_baseline) {
            float delta_adc = 2115.0f - cal_air_baseline; // range ADC calibrato
            float delta_ppm = 50000.0f - 400.0f;
            co2ppm = 400.0f + ((avgTia - cal_air_baseline) * (delta_ppm / delta_adc));
        }
        co2ppm = constrain(co2ppm, 400.0f, 55000.0f);

        // -----------------------------------------------------------
        // 3. [ADATTATO] Applicazione calibrazione
        //
        // ZERO_CAL: registra il valore ADC corrente come nuova baseline
        //   in aria pulita. Azzera anche l'offset di correzione ppm.
        //   Deve avvenire quando il sensore è in aria senza CO2.
        //
        // OFFSET_CAL: calcola e applica l'offset di correzione ppm.
        //   L'utente espone il sensore a un gas di riferimento noto
        //   (es. 5000 ppm). Il firmware calcola la differenza tra
        //   il valore target e la lettura corrente e la memorizza
        //   come offset da aggiungere alle misure successive.
        // -----------------------------------------------------------
        if (do_zero_cal) {
            Serial.printf("[CAL] ZERO_CAL applied: old_baseline=%.1f new_baseline=%.1f\n",
                          (float)cal_air_baseline, avgTia);
            cal_air_baseline = avgTia;
            cal_offset_ppm   = 0.0f;
            do_zero_cal      = false;
            // co2ppm ricalcolata al prossimo ciclo con la nuova baseline
        }

        if (do_offset_cal) {
            float correction = pending_offset - co2ppm;
            Serial.printf("[CAL] OFFSET_CAL applied: current=%.1f target=%.1f offset=%.1f\n",
                          co2ppm, (float)pending_offset, correction);
            cal_offset_ppm = correction;
            do_offset_cal  = false;
        }

        // Applica l'offset di calibrazione alla lettura corrente
        co2ppm += cal_offset_ppm;
        co2ppm = constrain(co2ppm, 0.0f, 60000.0f);

        // -----------------------------------------------------------
        // 4. [ADATTATO] Calcolo sensor_response
        //
        // Risposta normalizzata del sensore MOx: |I_air - I| / I_air
        // Dove I_air è la corrente in aria (baseline), I è la corrente
        // attuale. Con l'ADC del TIA: I proporzionale al valore ADC.
        // sensor_response = 0 in aria pulita, >0 in presenza di CO2.
        // -----------------------------------------------------------
        float adcAir     = (float)cal_air_baseline;
        float sensorResp = (adcAir > 0.0f)
                           ? fabsf(adcAir - avgTia) / adcAir
                           : 0.0f;
        sensorResp = constrain(sensorResp, 0.0f, 1.0f);

        // -----------------------------------------------------------
        // 5. Conversioni accessorie  (INVARIATE)
        // -----------------------------------------------------------
        float rail_12V = (avgVmon * 3.0f / 4095.0f) * (122.0f / 22.0f);

        float env_t = 37.0f;
        float env_h = 95.0f;
        if (has_sht41) {
            sensors_event_t humidity, temp;
            sht4.getEvent(&humidity, &temp);
            env_t = temp.temperature;
            env_h = humidity.relative_humidity;
        }

        // -----------------------------------------------------------
        // 6. Impacchettamento  [ADATTATO: aggiunto raw_adc e sensor_response]
        // -----------------------------------------------------------
        Payload payload;
        payload.ts              = currentEpochSeconds();
        payload.co2_ppm         = co2ppm;
        payload.heater_temp     = heaterTemp;
        payload.env_temp        = env_t;
        payload.env_hum         = env_h;
        payload.rail_12v        = rail_12V;
        payload.raw_adc         = (uint16_t)constrain((int)avgTia, 0, 4095); // [ADATTATO]
        payload.sensor_response = sensorResp;                                  // [ADATTATO]

        if (payloadQueue != NULL) {
            xQueueSend(payloadQueue, &payload, 0);
        }

        // [ADATTATO] Log arricchito con raw_adc e sensor_response
        Serial.printf("[SENSE] CO2: %5.0f ppm (offset=%.0f) | ADC: %u | Resp: %.3f | V12V: %.2fV | Env: %.1fC\n",
                      co2ppm, (float)cal_offset_ppm, payload.raw_adc,
                      payload.sensor_response, rail_12V, env_t);

        vTaskDelay(pdMS_TO_TICKS(1000));
    }
}

// ── Task_NetworkAndFS  [ADATTATO: loop() periodico + drain ackQueue] ──
//
// Modifica principale: usa xQueueReceive con timeout 100ms invece di
// portMAX_DELAY. Questo permette di:
//   a) chiamare mqttClient.loop() regolarmente per ricevere i comandi
//      in arrivo (senza il loop il callback non viene mai invocato)
//   b) svuotare l'ackQueue e pubblicare gli ACK di calibrazione
//      in modo disaccoppiato dal callback (sicuro con PubSubClient)
//
// Il comportamento offline/backlog è invariato.
void Task_NetworkAndFS(void *pvParameters) {
    while (1) {
        Payload payload;

        // [ADATTATO] Timeout 100ms: permette loop() e drain ACK anche
        // tra un campione e l'altro (Task_Sensing cadenza a 1 Hz)
        bool gotPayload = (xQueueReceive(payloadQueue, &payload,
                                         pdMS_TO_TICKS(100)) == pdTRUE);

        bool networkOk = (connectNetwork() && connectMqtt());

        if (networkOk) {
            // [ADATTATO] loop() elabora i messaggi MQTT in arrivo
            // (invoca mqttCallback se c'è un comando in coda broker)
            mqttClient.loop();

            // [ADATTATO] Svuota la coda ACK calibrazione e pubblica
            // Viene fatto DOPO loop() per essere certi che il socket
            // non sia in mezzo a una ricezione
            AckMessage ack;
            while (xQueueReceive(ackQueue, &ack, 0) == pdTRUE) {
                publishAck(ack.event_id, ack.command, ack.status);
            }

            // Processa il payload di misura (se arrivato in questo tick)
            if (gotPayload) {
                flushBacklog();
                publishTelemetry(payload);
                publishStatus("online");
            }

        } else {
            // Offline: archivia il payload su LittleFS (backlog recovery)
            if (gotPayload) {
                appendBacklog(payload);
            }
        }
    }
}

// ===================================================================
// setup  [ADATTATO: versione v2.2, creazione ackQueue]
// ===================================================================
void setup() {
    Serial.begin(115200);
    delay(1000);
    // [ADATTATO] Versione bumped a v2.2 per tracciabilità del contratto MQTT
    Serial.println("\n=== BioSentry Firmware v2.2 (IncuSense contract) ===");
    Serial.printf("[ID] hub_id=%s  device_id=%s\n", MQTT_HUB_ID, MQTT_DEVICE_ID);
    Serial.printf("[MQTT] measurements: %s\n", TOPIC_MEASUREMENTS);
    Serial.printf("[MQTT] commands:     %s\n", TOPIC_COMMANDS);

    // Pin e periferiche  (INVARIATO)
    analogReadResolution(12);
    pinMode(PIN_TIA_ADC, INPUT);
    pinMode(PIN_VMON_ADC, INPUT);
    pinMode(PIN_HEATER_REGIME, OUTPUT);

    ledcSetup(LEDC_HEATER_CHAN, LEDC_HEATER_FREQ, LEDC_HEATER_RES);
    ledcAttachPin(PIN_HEATER_PWM, LEDC_HEATER_CHAN);
    ledcSetup(LEDC_FAN_CHAN, LEDC_FAN_FREQ, LEDC_FAN_RES);
    ledcAttachPin(PIN_FAN_PWM, LEDC_FAN_CHAN);

    digitalWrite(PIN_HEATER_REGIME, LOW);
    ledcWrite(LEDC_HEATER_CHAN, 0);
    ledcWrite(LEDC_FAN_CHAN, 0);

    // SHT41  (INVARIATO)
    Wire.begin(PIN_I2C_SDA, PIN_I2C_SCL);
    if (!sht4.begin(&Wire)) {
        Serial.println("[SENSE] SHT41 not found (mock mode)");
    } else {
        has_sht41 = true;
        sht4.setPrecision(SHT4X_HIGH_PRECISION);
        sht4.setHeater(SHT4X_NO_HEATER);
    }

    // LittleFS  (INVARIATO)
    const char* fs_label = "littlefs";
    if (esp_partition_find_first(ESP_PARTITION_TYPE_DATA, ESP_PARTITION_SUBTYPE_ANY, "vfs") != NULL) {
        fs_label = "vfs";
        Serial.println("[PART] Wokwi simulator detected. Target: 'vfs'");
    } else if (esp_partition_find_first(ESP_PARTITION_TYPE_DATA, ESP_PARTITION_SUBTYPE_ANY, "littlefs") != NULL) {
        Serial.println("[PART] Physical hardware detected. Target: 'littlefs'");
    } else {
        Serial.println("[PART] ALARM: No data partition found!");
    }

    bool fsMounted = LittleFS.begin(true, "/littlefs", 10, fs_label);
    if (!fsMounted) Serial.println("[FS] LittleFS mount failed");
    else            Serial.println("[FS] LittleFS mounted successfully");

    // Network
    if (!connectNetwork()) {
        Serial.println("[NET] Offline backlog mode active");
    }

    // Code FreeRTOS
    payloadQueue = xQueueCreate(8, sizeof(Payload));
    if (payloadQueue == NULL) {
        Serial.println("[QUEUE] payload queue creation failed");
    }

    // [ADATTATO] Coda ACK calibrazione (4 slot: ampiamente sufficiente)
    ackQueue = xQueueCreate(4, sizeof(AckMessage));
    if (ackQueue == NULL) {
        Serial.println("[QUEUE] ack queue creation failed");
    }

    // Task (priorità e stack INVARIATI)
    xTaskCreate(Task_ThermalControl, "Thermal_PID",  4096, NULL, 3, NULL);
    xTaskCreate(Task_Sensing,        "Sensore_TIA",  4096, NULL, 2, NULL);
    xTaskCreate(Task_NetworkAndFS,   "Rete_LittleFS",8192, NULL, 1, NULL);
}

void loop() {
    vTaskDelete(NULL);
}
