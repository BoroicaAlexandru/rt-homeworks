// Device-ul IoT din tema 01, de data aceasta pe un ESP32-C3 Super Mini.
//
// Aceeași logică și același protocol ca device-simulator din tema 01, cu trei
// taskuri FreeRTOS în loc de trei thread-uri Java:
//
//   task periodic --(coada de alarme)--> task sporadic
//        |                                     |
//        +---- telemetry ---> MQTT <--- alarm -+
//                              |
//                          commands --> command_received() --(coada de comenzi)--> task aperiodic ---> status
//
// Gata implementate (nu trebuie modificate):
//   config.h         setările: Wi-Fi, broker, STUDENT, valorile inițiale; DEVICE_ID
//                    se citește din adresa MAC a plăcii
//   connection.h/cpp Wi-Fi, ora exactă (SNTP), clientul MQTT, mqtt_publish(), epoch_ms()
//
// Așa cum este, schița se conectează la Wi-Fi și la broker și afișează pe
// Serial comenzile primite. Taskurile le scrieți voi: TODO 0 ... TODO 3.
// Exemplele de pornire sunt în laboratorul 0 din rt-labs-esp32c3; vezi
// exemple/README.md din folderul temei.
//
// Placa: ESP32C3 Dev Module, USB CDC On Boot: Enabled. Serial Monitor: 115200 baud.

#include "config.h"
#include "connection.h"

// Temperatura cipului, în °C, de la senzorul intern al ESP32-C3.
// Este temperatura siliciului, nu a camerei: imediat după pornirea Wi-Fi-ului
// poate fi în jur de 50 °C, apoi scade în câteva minute spre 35-40 °C.
// Rezoluția este de aproximativ 1 °C. Ca să provocați alarme, puneți pragul
// cu SET_THRESHOLD cu un grad sub valoarea citită.
float read_temperature(void) {
    return temperatureRead();
}

struct AlarmEvent {
    float temperature;
    float threshold;
};

struct Command {
    char text[COMMAND_MAX_LEN];
};

static QueueHandle_t alarmQueue;
static QueueHandle_t commandQueue;

// volatile: citite de taskul periodic, modificate de taskul aperiodic
volatile uint32_t periodMs = PERIOD_MS_INITIAL;
volatile float threshold = THRESHOLD_INITIAL;

// TODO 1: task periodic

void periodic_task(void* arg) {
    TickType_t last = xTaskGetTickCount();

    for (;;) {
  
        float t = read_temperature();
        char msg[96];
        snprintf(msg, sizeof(msg), "%s;%lld;%.2f", DEVICE_ID, (long long)epoch_ms(), t);
        mqtt_publish("telemetry", msg);
        Serial.printf("[periodic] telemetrie: %s\n", msg);

        float th = threshold;
        if (t > th) {
            AlarmEvent ev = { t, th };
            xQueueSend(alarmQueue, &ev, 0);
        }

        xTaskDelayUntil(&last, pdMS_TO_TICKS(periodMs));
    }
}

// TODO 2: task sporadic

void sporadic_task(void* arg) {
    uint32_t lastRelease = 0;
    bool everReleased = false;
    AlarmEvent ev;

    for (;;) {
        xQueueReceive(alarmQueue, &ev, portMAX_DELAY);
        uint32_t now = millis();

        if (everReleased && (now - lastRelease) < MIN_INTERARRIVAL_MS) {
            Serial.printf("[sporadic] alarmă respinsă (interval minim %lu ms neatins), temperatura=%.2f\n",
                          (unsigned long)MIN_INTERARRIVAL_MS, ev.temperature);
            continue;
        }
        lastRelease = now;
        everReleased = true;

        char msg[112];
        snprintf(msg, sizeof(msg), "%s;%lld;%.2f;%.2f",
                 DEVICE_ID, (long long)epoch_ms(), ev.temperature, ev.threshold);
        mqtt_publish("alarm", msg);
        Serial.printf("[sporadic] alarmă publicată: %s\n", msg);
    }
}

// TODO 3: task aperiodic

// Executa o comanda si scrie in reply textul confirmarii
static void execute_command(const char* text, char* reply, size_t size) {
    char buf[COMMAND_MAX_LEN];
    strncpy(buf, text, sizeof(buf) - 1);
    buf[sizeof(buf) - 1] = '\0';

    char* name = strtok(buf, " \t\r\n");
    char* arg = strtok(NULL, " \t\r\n");
    char* extra = strtok(NULL, " \t\r\n");

    if (name == NULL) {
        snprintf(reply, size, "ERROR comanda goala");
    } else if (strcasecmp(name, "SET_INTERVAL") == 0) {
        char* end;
        long ms = arg ? strtol(arg, &end, 10) : 0;
        if (arg == NULL || extra != NULL || *end != '\0') {
            snprintf(reply, size, "ERROR format: SET_INTERVAL <ms>");
        } else if (ms <= 0) {
            snprintf(reply, size, "ERROR intervalul trebuie sa fie pozitiv");
        } else {
            periodMs = (uint32_t)ms;
            snprintf(reply, size, "OK SET_INTERVAL %ld", ms);
        }
    } else if (strcasecmp(name, "SET_THRESHOLD") == 0) {
        char* end;
        float th = arg ? strtof(arg, &end) : 0;
        if (arg == NULL || extra != NULL || *end != '\0') {
            snprintf(reply, size, "ERROR format: SET_THRESHOLD <valoare>");
        } else {
            threshold = th;
            snprintf(reply, size, "OK SET_THRESHOLD %.2f", th);
        }
    } else if (strcasecmp(name, "RESET") == 0) {
        periodMs = PERIOD_MS_INITIAL;
        threshold = THRESHOLD_INITIAL;
        snprintf(reply, size, "OK RESET");
    } else if (strcasecmp(name, "STATUS") == 0) {
        snprintf(reply, size, "OK STATUS period=%lu threshold=%.2f",
                 (unsigned long)periodMs, (float)threshold);
    } else {
        snprintf(reply, size, "ERROR comanda necunoscuta: %s", name);
    }
}

void aperiodic_task(void* arg) {
    Command cmd;

    for (;;) {
        // fiecare comandă primită eliberează un job
        xQueueReceive(commandQueue, &cmd, portMAX_DELAY);

        char reply[96];
        execute_command(cmd.text, reply, sizeof(reply));

        char msg[160];
        snprintf(msg, sizeof(msg), "%s;%lld;%s", DEVICE_ID, (long long)epoch_ms(), reply);
        mqtt_publish("status", msg);
        Serial.printf("[aperiodic] %s -> %s\n", cmd.text, reply);
    }
}

void command_received(const char* text) {
    Serial.printf("[mqtt] comandă primită: %s\n", text);

    Command cmd;
    strncpy(cmd.text, text, COMMAND_MAX_LEN - 1);
    cmd.text[COMMAND_MAX_LEN - 1] = '\0';

    if (xQueueSend(commandQueue, &cmd, 0) != pdTRUE) {
        Serial.println("[mqtt] coada de comenzi plină, comandă ignorată");
    }
}

// TODO 0: setup

void setup() {
    Serial.begin(115200);
    Serial.setTxTimeoutMs(0);
    uint32_t t = millis();
    while (!Serial && millis() - t < 3000) delay(10);
    delay(200);
    Serial.println();
    Serial.printf("=== tema 02: STUDENT %s, DEVICE_ID %s ===\n", STUDENT, DEVICE_ID);

    wifi_connect();
    time_sync();

    alarmQueue = xQueueCreate(8, sizeof(AlarmEvent));
    commandQueue = xQueueCreate(8, sizeof(Command));

    mqtt_start();

    xTaskCreate(periodic_task,  "periodic",  4096, NULL, 5, NULL);
    xTaskCreate(sporadic_task,  "sporadic",  4096, NULL, 4, NULL);
    xTaskCreate(aperiodic_task, "aperiodic", 4096, NULL, 3, NULL);
}

void loop() {

    delay(1000);
}