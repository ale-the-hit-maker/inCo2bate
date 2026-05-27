#include <Arduino.h>
#include <SPI.h>
#include <Ethernet.h>
#include <PubSubClient.h>
#include <ArduinoJson.h>
#include <LittleFS.h>

// ===================================================================
// Hardware pin mapping
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

#define MQTT_BROKER_IP     "192.168.1.100"
#define MQTT_BROKER_PORT   1883
#define MQTT_LAB_ID        "lab_alpha"
#define MQTT_INC_ID        "inc_01"
#define MQTT_NODE_ID       "node_a"

#define TARGET_TEMP_C      250.0f
#define PID_Kp             5.0f
#define PID_Ki             0.1f
#define PID_Kd             1.0f
#define PID_I_MAX          100.0f

#define BACKLOG_FILE       "/backlog.jsonl"

struct Payload {
    uint32_t ts;
    float co2_ppm;
    float heater_temp;
    float env_temp;
    float env_hum;
};

static QueueHandle_t payloadQueue = NULL;
static EthernetClient ethClient;
static PubSubClient mqttClient(ethClient);

static float simulatedTemp = 25.0f;
static float heaterTemp = 25.0f;
static float envHumidity = 45.0f;
static int currentFanDuty = 0;
static int currentHeaterDuty = 0;

void Task_ThermalControl(void *pvParameters);
void Task_Sensing(void *pvParameters);
void Task_NetworkAndFS(void *pvParameters);

void buildMqttTopic(char *buffer, size_t size, const char *suffix);
bool connectEthernet();
bool connectMqtt();
void publishTelemetry(const Payload &payload);
void appendBacklog(const Payload &payload);
bool flushBacklog();
void publishStatus(const char *message);

void buildMqttTopic(char *buffer, size_t size, const char *suffix) {
    snprintf(buffer, size, "biosentry/%s/%s/%s/%s", MQTT_LAB_ID, MQTT_INC_ID, MQTT_NODE_ID, suffix);
}

bool connectEthernet() {
    uint8_t mac[6] = {0xDE, 0xAD, 0xBE, 0xEF, 0xFE, 0xED};
    IPAddress localIp(192, 168, 1, 101);

    Ethernet.init(PIN_SPI_CS);
    SPI.begin(PIN_SPI_SCK, PIN_SPI_MISO, PIN_SPI_MOSI);

    Ethernet.begin(mac, localIp);
    delay(1000);
    if (Ethernet.linkStatus() != LinkON) {
        Serial.println("[NET] Ethernet link is down");
        return false;
    }
    return true;
}

bool connectMqtt() {
    if (mqttClient.connected()) {
        mqttClient.loop();
        return true;
    }

    char clientId[32];
    snprintf(clientId, sizeof(clientId), "biosentry-%s", MQTT_NODE_ID);
    mqttClient.setServer(MQTT_BROKER_IP, MQTT_BROKER_PORT);
    mqttClient.setKeepAlive(10);

    if (mqttClient.connect(clientId)) {
        Serial.println("[MQTT] connected");
        return true;
    }

    Serial.printf("[MQTT] connect failed, state=%d\n", mqttClient.state());
    return false;
}

void appendBacklog(const Payload &payload) {
    File file = LittleFS.open(BACKLOG_FILE, FILE_APPEND);
    if (!file) {
        Serial.println("[BACKLOG] open append failed");
        return;
    }

    char line[192];
    int len = snprintf(line, sizeof(line), "{\"ts\":%u,\"co2_ppm\":%.2f,\"heater_temp\":%.2f,\"env_temp\":%.2f,\"env_hum\":%.2f}\n",
                       payload.ts, payload.co2_ppm, payload.heater_temp, payload.env_temp, payload.env_hum);
    file.write((const uint8_t *)line, len);
    file.close();
    Serial.println("[BACKLOG] appended payload");
}

bool flushBacklog() {
    if (!LittleFS.exists(BACKLOG_FILE)) {
        return true;
    }

    File file = LittleFS.open(BACKLOG_FILE, FILE_READ);
    if (!file) {
        Serial.println("[BACKLOG] open read failed");
        return false;
    }

    bool allSent = true;
    while (file.available()) {
        String line = file.readStringUntil('\n');
        if (line.length() == 0) {
            continue;
        }

        StaticJsonDocument<256> doc;
        DeserializationError err = deserializeJson(doc, line);
        if (err) {
            Serial.println("[BACKLOG] invalid JSON, skipping");
            continue;
        }

        char topic[128];
        buildMqttTopic(topic, sizeof(topic), "telemetry");
        if (!mqttClient.publish(topic, line.c_str())) {
            Serial.println("[BACKLOG] publish failed, retry later");
            allSent = false;
            break;
        }
    }

    file.close();
    if (allSent) {
        LittleFS.remove(BACKLOG_FILE);
        Serial.println("[BACKLOG] flushed");
    }
    return allSent;
}

void publishTelemetry(const Payload &payload) {
    StaticJsonDocument<256> doc;
    doc["ts"] = payload.ts;
    doc["co2_ppm"] = payload.co2_ppm;
    doc["heater_temp"] = payload.heater_temp;
    doc["env_temp"] = payload.env_temp;
    doc["env_hum"] = payload.env_hum;

    char buffer[256];
    serializeJson(doc, buffer, sizeof(buffer));

    char topic[128];
    buildMqttTopic(topic, sizeof(topic), "telemetry");
    if (!mqttClient.publish(topic, buffer)) {
        Serial.println("[MQTT] telemetry publish failed");
        appendBacklog(payload);
        return;
    }
    Serial.println("[MQTT] telemetry published");
}

void publishStatus(const char *message) {
    StaticJsonDocument<128> doc;
    doc["ts"] = (uint32_t)time(nullptr);
    doc["status"] = message;
    char buffer[128];
    serializeJson(doc, buffer, sizeof(buffer));

    char topic[128];
    buildMqttTopic(topic, sizeof(topic), "status");
    mqttClient.publish(topic, buffer);
}

void Task_ThermalControl(void *pvParameters) {
    TickType_t lastWakeTime = xTaskGetTickCount();
    float integral = 0.0f;
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
        vTaskDelayUntil(&lastWakeTime, pdMS_TO_TICKS(10));

        if (currentHeaterDuty > 50) {
            simulatedTemp += 0.4f;
        } else {
            simulatedTemp -= 0.2f;
        }
        simulatedTemp = constrain(simulatedTemp, 20.0f, 280.0f);
        heaterTemp = simulatedTemp;

        cycleCounter++;
        if (cycleCounter >= 100) {
            Serial.printf("[THERM] T=%.1f/%.1f H=%d F=%d\n", simulatedTemp, TARGET_TEMP_C, currentHeaterDuty, currentFanDuty);
            cycleCounter = 0;
        }
    }
}

void Task_Sensing(void *pvParameters) {
    while (1) {
        long sumTia = 0;
        long sumVmon = 0;
        for (int i = 0; i < 64; i++) {
            sumTia += analogRead(PIN_TIA_ADC);
            sumVmon += analogRead(PIN_VMON_ADC);
        }

        float avgTia = sumTia / 64.0f;
        float avgVmon = sumVmon / 64.0f;
        float co2ppm = avgTia * 0.2f;
        float envTemp = (avgVmon * 3.0f / 4095.0f) * (122.0f / 22.0f);

        Payload payload;
        payload.ts = (uint32_t)time(nullptr);
        payload.co2_ppm = co2ppm;
        payload.heater_temp = heaterTemp;
        payload.env_temp = envTemp;
        payload.env_hum = envHumidity;

        if (payloadQueue != NULL) {
            if (xQueueSend(payloadQueue, &payload, 0) != pdTRUE) {
                Serial.println("[QUEUE] publish failed");
            }
        }

        Serial.printf("[SENSE] CO2=%.1f ppm, Tenv=%.2f Vmon=%.2f\n", co2ppm, envTemp, avgVmon);
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
}

void Task_NetworkAndFS(void *pvParameters) {
    while (1) {
        Payload payload;
        if (xQueueReceive(payloadQueue, &payload, portMAX_DELAY) == pdTRUE) {
            if (Ethernet.linkStatus() == LinkON && connectMqtt()) {
                flushBacklog();
                publishTelemetry(payload);
                publishStatus("online");
            } else {
                appendBacklog(payload);
                Serial.println("[NET] offline backlog");
            }
        }
    }
}

void setup() {
    Serial.begin(115200);
    delay(1000);
    Serial.println("\n=== BioSentry Firmware v2.1 ===");

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

    if (!LittleFS.begin(true)) {
        Serial.println("[FS] LittleFS mount failed");
    }

    if (!connectEthernet()) {
        Serial.println("[NET] Ethernet initialization failed");
    }

    payloadQueue = xQueueCreate(8, sizeof(Payload));
    if (payloadQueue == NULL) {
        Serial.println("[QUEUE] creation failed");
    }

    xTaskCreate(Task_ThermalControl, "Thermal_PID", 4096, NULL, 3, NULL);
    xTaskCreate(Task_Sensing, "Sensore_TIA", 4096, NULL, 2, NULL);
    xTaskCreate(Task_NetworkAndFS, "Rete_LittleFS", 8192, NULL, 1, NULL);
}

void loop() {
    vTaskDelete(NULL);
}
