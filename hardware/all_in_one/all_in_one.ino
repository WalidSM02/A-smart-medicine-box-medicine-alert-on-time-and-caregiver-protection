#include <WiFi.h>
#include <Firebase_ESP_Client.h>
#include <addons/TokenHelper.h>
#include <WiFiManager.h>
#include <Preferences.h>
#include <Wire.h>
#include <time.h> // Real-Time Clock Library
#include "MAX30105.h"
#include "spo2_algorithm.h"

// -----------------------------------------
// 1. CREDENTIALS
// -----------------------------------------
#define API_KEY "AIzaSyAHirXAK-A8F8ujDoSZ8QnOPwHVlFrT_Wc"
#define FIREBASE_PROJECT_ID "smart-med-alert"

Preferences preferences;
String user_uid = "";
String user_email = "";
String user_password = "";

FirebaseData fbdo;
FirebaseAuth auth;
FirebaseConfig config;

// -----------------------------------------
// 2. HARDWARE & SENSOR VARIABLES
// -----------------------------------------
struct BoxSection {
    String slotPrefix;
    int switchPin;
    int greenLedPin;
    int redLedPin;
    bool alarmActive;
    bool boxWasOpened;
    int currentTakenCount;
    String targetTime;      // The scheduled time pulled from the App
    bool triggeredToday;    // Stops it from ringing multiple times a minute
};

BoxSection sections[3] = {
    {"mor", 22, 21, 19, false, false, 0, "08:00 AM", false},
    {"lun", 23, 18,  5, false, false, 0, "01:00 PM", false},
    {"nig", 32, 25, 26, false, false, 0, "08:00 PM", false}
};

MAX30105 particleSensor;
uint32_t irBuffer[100]; 
uint32_t redBuffer[100];

// Shared thread-safe variables for the Sensor Core
volatile int currentHR = 0;
volatile int currentSPO2 = 0;

// Tracking variables for Cloud Sync
int lastSentHR = -1;
int lastSentSPO2 = -1;

// -----------------------------------------
// 3. FREERTOS SENSOR TASK (RUNS ON CORE 1)
// -----------------------------------------
void max30102Task(void * parameter) {
    // Remap the ESP32 I2C pins to 12 (SDA) and 13 (SCL)
    Wire.begin(12, 13);

    if (!particleSensor.begin(Wire, I2C_SPEED_FAST)) {
        Serial.println("❌ MAX30102 not found! Check wiring to SDA=12 & SCL=13.");
        vTaskDelete(NULL);
    }
    
    Serial.println("✅ MAX30102 Initialized on Pins 12 & 13.");
    
    // BLAZING FAST ACCURACY SETUP
    particleSensor.setup(60, 2, 2, 400, 411, 4096);

    int32_t bufferLength = 100;
    int32_t spo2;
    int8_t validSPO2;
    int32_t heartRate;
    int8_t validHeartRate;

    const byte SMOOTHING_SIZE = 5;
    int hrArray[SMOOTHING_SIZE] = {0};
    int spo2Array[SMOOTHING_SIZE] = {0};
    byte smoothIdx = 0;
    
    bool fingerIsOn = false; 

    while (true) {
        long irValue = particleSensor.getIR();
        
        if (irValue < 15000) { 
            if (fingerIsOn) {
                fingerIsOn = false;
                currentHR = 0;
                currentSPO2 = 0;
                Serial.println("👆 Finger removed. Waiting for scan...");
            }
            for (byte x = 0; x < SMOOTHING_SIZE; x++) {
                hrArray[x] = 0;
                spo2Array[x] = 0;
            }
            smoothIdx = 0;
            
            vTaskDelay(200 / portTICK_PERIOD_MS); 
            continue; 
        }

        if (!fingerIsOn) {
            fingerIsOn = true;
            Serial.println("👇 Finger detected! Gathering fast samples...");
        }

        for (byte i = 0 ; i < bufferLength ; i++) {
            while (particleSensor.available() == false) {
                particleSensor.check();
                // CRITICAL FIX: Proper FreeRTOS delay instead of yield() prevents task starvation
                vTaskDelay(1 / portTICK_PERIOD_MS); 
            }
            redBuffer[i] = particleSensor.getRed();
            irBuffer[i] = particleSensor.getIR();
            particleSensor.nextSample();
        }

        maxim_heart_rate_and_oxygen_saturation(irBuffer, bufferLength, redBuffer, &spo2, &validSPO2, &heartRate, &validHeartRate);

        bool fingerRemainsOnSensor = true;
        
        while (fingerRemainsOnSensor) {
            for (byte i = 25; i < 100; i++) {
                redBuffer[i - 25] = redBuffer[i];
                irBuffer[i - 25] = irBuffer[i];
            }

            for (byte i = 75; i < 100; i++) {
                while (particleSensor.available() == false) {
                    particleSensor.check();
                    vTaskDelay(1 / portTICK_PERIOD_MS);
                }
                redBuffer[i] = particleSensor.getRed();
                irBuffer[i] = particleSensor.getIR();
                particleSensor.nextSample();

                if (irBuffer[i] < 15000) { 
                    fingerRemainsOnSensor = false;
                }
            }

            if (!fingerRemainsOnSensor) break; 

            maxim_heart_rate_and_oxygen_saturation(irBuffer, bufferLength, redBuffer, &spo2, &validSPO2, &heartRate, &validHeartRate);

            if (validSPO2 == 1 && validHeartRate == 1 && spo2 >= 50 && spo2 <= 100 && heartRate >= 30 && heartRate <= 220) {
                
                hrArray[smoothIdx] = heartRate;
                spo2Array[smoothIdx] = spo2;
                smoothIdx++;
                smoothIdx %= SMOOTHING_SIZE;

                int hrSum = 0;
                int spo2Sum = 0;
                int validCount = 0;
                
                for (byte x = 0; x < SMOOTHING_SIZE; x++) {
                    if (hrArray[x] > 0) {
                        hrSum += hrArray[x];
                        spo2Sum += spo2Array[x];
                        validCount++;
                    }
                }

                if (validCount > 0) {
                    currentHR = hrSum / validCount;
                    currentSPO2 = spo2Sum / validCount;
                    Serial.println("❤️ Smoothed HR: " + String(currentHR) + " | 🩸 Smoothed SpO2: " + String(currentSPO2) + "%");
                }
            }
            vTaskDelay(1 / portTICK_PERIOD_MS);
        }
    }
}

// -----------------------------------------
// 4. MAIN SETUP (WIFI & FIREBASE)
// -----------------------------------------
void setup() {
    Serial.begin(115200);

    // Initialize Box Pins
    for (int i = 0; i < 3; i++) {
        pinMode(sections[i].switchPin, INPUT_PULLUP);
        pinMode(sections[i].greenLedPin, OUTPUT);
        pinMode(sections[i].redLedPin, OUTPUT);
        digitalWrite(sections[i].greenLedPin, HIGH);
        digitalWrite(sections[i].redLedPin, LOW);
    }

    // CRITICAL FIX: Shifted the Sensor Task to Core 1 so Core 0 is 100% free for Wi-Fi and SSL Encryption
    xTaskCreatePinnedToCore(max30102Task, "SensorTask", 10000, NULL, 1, NULL, 1);

    preferences.begin("medbox", false);
    user_uid = preferences.getString("uid", "");
    user_email = preferences.getString("email", "");
    user_password = preferences.getString("password", "");

    WiFiManager wm;
    WiFiManagerParameter custom_uid("uid", "App User UID", user_uid.c_str(), 40);
    WiFiManagerParameter custom_email("email", "App Login Email", user_email.c_str(), 40);
    WiFiManagerParameter custom_pass("password", "App Password", user_password.c_str(), 40);

    wm.addParameter(&custom_uid);
    wm.addParameter(&custom_email);
    wm.addParameter(&custom_pass);

    if (user_uid.length() < 5 || user_email.length() < 5 || user_password.length() < 5) {
        wm.resetSettings(); 
        if (!wm.startConfigPortal("SmartMedBox_Setup")) ESP.restart();
    } else {
        if (!wm.autoConnect("SmartMedBox_Setup")) ESP.restart();
    }

    // WiFi is connected at this point! 
    Serial.println("⏳ Syncing Real-Time Clock with NTP Server for SSL Validation...");
    configTime(6 * 3600, 0, "pool.ntp.org", "time.nist.gov"); // UTC+6
    struct tm timeinfo;
    int ntpRetries = 0;
    while (!getLocalTime(&timeinfo) && ntpRetries < 15) {
        Serial.print(".");
        delay(500);
        ntpRetries++;
    }
    if(ntpRetries < 15) {
        Serial.println("\n✅ Real-Time Clock Synced to Bangladesh Time!");
    } else {
        Serial.println("\n⚠️ NTP Sync Failed. SSL might still fail.");
    }

    user_uid = custom_uid.getValue();
    user_email = custom_email.getValue();
    user_password = custom_pass.getValue();

    preferences.putString("uid", user_uid);
    preferences.putString("email", user_email);
    preferences.putString("password", user_password);

    config.api_key = API_KEY;
    auth.user.email = user_email.c_str();        
    auth.user.password = user_password.c_str();  
    config.database_url = String(FIREBASE_PROJECT_ID) + ".firebaseio.com";
    config.token_status_callback = tokenStatusCallback;
    
    // Increased timeouts to prevent network drops
    config.timeout.socketConnection = 10 * 1000;
    
    // Allocate a larger SSL buffer to ensure the handshake doesn't run out of memory
    fbdo.setBSSLBufferSize(4096, 1024);
    
    Firebase.begin(&config, &auth);
    Firebase.reconnectWiFi(true);
}

// -----------------------------------------
// 5. MAIN LOOP (FIREBASE SYNC)
// -----------------------------------------
void loop() {
    if (!Firebase.ready()) {
        delay(1000);
        return;
    }

    unsigned long currentMillis = millis();
    static unsigned long lastFirestorePoll = 0;

    // A. FAST VITAL SYNC: Instantly clear App screen when finger is removed
    if (currentHR == 0 && (lastSentHR != 0 || lastSentSPO2 != 0)) {
        lastSentHR = 0;
        lastSentSPO2 = 0;
        String healthPath = "users/" + user_uid + "/hardware";
        String healthData = "{\"fields\":{\"heartRate\":{\"integerValue\":\"0\"}, \"spo2\":{\"integerValue\":\"0\"}}}";
        Firebase.Firestore.patchDocument(&fbdo, FIREBASE_PROJECT_ID, "", (healthPath + "/health").c_str(), healthData.c_str(), "heartRate,spo2");
    }

    // B. CLOUD POLLING & NTP TRIGGERING (Every 5 seconds)
    if (currentMillis - lastFirestorePoll >= 5000) {
        lastFirestorePoll = currentMillis;
        String syncDocPath = "users/" + user_uid + "/hardware/sync";

        // 1. Sync Active Health Data
        if (currentHR > 0 && currentSPO2 > 0) {
            lastSentHR = currentHR;
            lastSentSPO2 = currentSPO2;
            String healthPath = "users/" + user_uid + "/hardware";
            String healthData = "{\"fields\":{\"heartRate\":{\"integerValue\":\"" + String(currentHR) + "\"}, \"spo2\":{\"integerValue\":\"" + String(currentSPO2) + "\"}}}";
            Firebase.Firestore.patchDocument(&fbdo, FIREBASE_PROJECT_ID, "", (healthPath + "/health").c_str(), healthData.c_str(), "heartRate,spo2");
        }

        // 2. Fetch User's Scheduled Times from Firebase
        if (Firebase.Firestore.getDocument(&fbdo, FIREBASE_PROJECT_ID, "", syncDocPath.c_str(), "")) {
            FirebaseJson json;
            json.setJsonData(fbdo.payload());
            FirebaseJsonData jsonData;

            for (int i = 0; i < 3; i++) {
                String alarmField = "fields/" + sections[i].slotPrefix + "AlarmActive/booleanValue";
                String countField = "fields/" + sections[i].slotPrefix + "TakenCount/integerValue";
                String timeField  = "fields/" + sections[i].slotPrefix + "TargetTime/stringValue"; 
                
                if (json.get(jsonData, countField)) sections[i].currentTakenCount = jsonData.intValue;
                
                if (json.get(jsonData, timeField)) {
                    sections[i].targetTime = jsonData.stringValue;
                    sections[i].targetTime.trim(); // Ensure no spaces break the comparison
                }
                
                // CRITICAL FIX: Only sync ON states from the cloud (to recover if ESP32 reboots).
                // NEVER sync OFF states from the cloud to prevent the false-cancellation race condition.
                if (json.get(jsonData, alarmField)) {
                    if (jsonData.boolValue && !sections[i].alarmActive && !sections[i].boxWasOpened) {
                        sections[i].alarmActive = true;
                        digitalWrite(sections[i].greenLedPin, LOW);
                        digitalWrite(sections[i].redLedPin, HIGH);
                    }
                }
            }
        }

        // 3. NTP REAL-TIME CLOCK CHECK (Autonomous Hardware Triggering)
        struct tm timeinfo;
        if (getLocalTime(&timeinfo)) {
            char timeStringBuff[15];
            strftime(timeStringBuff, sizeof(timeStringBuff), "%I:%M %p", &timeinfo);
            String currentTimeStr = String(timeStringBuff); 
            currentTimeStr.trim();
            
            for (int i = 0; i < 3; i++) {
                // If current atomic time perfectly matches scheduled time...
                if (sections[i].targetTime == currentTimeStr) {
                    if (!sections[i].alarmActive && !sections[i].triggeredToday) {
                        
                        // OVERDOSE PROTECTION: Force-clear older missed alarms locally & in cloud!
                        String updateData = "{\"fields\":{\"" + sections[i].slotPrefix + "AlarmActive\":{\"booleanValue\":true}";
                        String updateMask = sections[i].slotPrefix + "AlarmActive";

                        for(int j=0; j<3; j++) {
                            if (i != j && sections[j].alarmActive) {
                                sections[j].alarmActive = false;
                                digitalWrite(sections[j].greenLedPin, HIGH);
                                digitalWrite(sections[j].redLedPin, LOW);
                                updateData += ", \"" + sections[j].slotPrefix + "AlarmActive\":{\"booleanValue\":false}";
                                updateMask += "," + sections[j].slotPrefix + "AlarmActive";
                            }
                        }
                        updateData += "}}";

                        sections[i].alarmActive = true;
                        sections[i].triggeredToday = true; // Locks it from firing twice in the same minute
                        
                        digitalWrite(sections[i].greenLedPin, LOW);
                        digitalWrite(sections[i].redLedPin, HIGH);
                        Serial.println("⏰ AUTOMATIC NTP ALARM TRIGGERED FOR: " + sections[i].slotPrefix + " at " + currentTimeStr);
                        
                        // Push activation to Firebase so Android App knows the box opened the alarm
                        Firebase.Firestore.patchDocument(&fbdo, FIREBASE_PROJECT_ID, "", syncDocPath.c_str(), updateData.c_str(), updateMask.c_str());
                    }
                } else {
                    // Resets the lock once that specific minute passes so it works again tomorrow
                    sections[i].triggeredToday = false;
                }
            }
        }
    }

    // C. LISTEN FOR PHYSICAL BOX INTERACTIONS (Instantly Responsive)
    for (int i = 0; i < 3; i++) {
        if (sections[i].alarmActive) {
            if (digitalRead(sections[i].switchPin) == HIGH) sections[i].boxWasOpened = true;

            if (digitalRead(sections[i].switchPin) == LOW && sections[i].boxWasOpened) {
                digitalWrite(sections[i].redLedPin, LOW);
                digitalWrite(sections[i].greenLedPin, HIGH);
                sections[i].alarmActive = false;
                sections[i].boxWasOpened = false;

                // Get the exact Atomic Time the box was opened!
                struct tm timeinfo;
                String takenTimeStr = "";
                if (getLocalTime(&timeinfo)) {
                    char timeStringBuff[15];
                    strftime(timeStringBuff, sizeof(timeStringBuff), "%I:%M %p", &timeinfo);
                    takenTimeStr = String(timeStringBuff);
                }

                int newCount = sections[i].currentTakenCount + 1;
                String updateData = "{\"fields\":{\"" + sections[i].slotPrefix + "AlarmActive\":{\"booleanValue\":false}, \"" + sections[i].slotPrefix + "TakenCount\":{\"integerValue\":\"" + String(newCount) + "\"}";
                String updateMask = sections[i].slotPrefix + "AlarmActive," + sections[i].slotPrefix + "TakenCount";
                
                // Attach the exact timestamp to Firebase for the App's History Log!
                if (takenTimeStr != "") {
                    updateData += ", \"" + sections[i].slotPrefix + "LastTakenTime\":{\"stringValue\":\"" + takenTimeStr + "\"}";
                    updateMask += "," + sections[i].slotPrefix + "LastTakenTime";
                }
                updateData += "}}";

                String syncDocPath = "users/" + user_uid + "/hardware/sync";
                Firebase.Firestore.patchDocument(&fbdo, FIREBASE_PROJECT_ID, "", syncDocPath.c_str(), updateData.c_str(), updateMask.c_str());
                Serial.println("✅ Medicine taken for: " + sections[i].slotPrefix + " at " + takenTimeStr);
            }
        }
    }

    delay(50); // Small 50ms delay for high responsiveness without CPU hogging
}