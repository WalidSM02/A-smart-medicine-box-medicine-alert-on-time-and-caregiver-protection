#include <WiFi.h>
#include <Firebase_ESP_Client.h>
#include <addons/TokenHelper.h>
#include <WiFiManager.h>
#include <Preferences.h>

// -----------------------------------------
// 1. APP / DEVELOPER CREDENTIALS
// -----------------------------------------

#define API_KEY "AIzaSyAHirXAK-A8F8ujDoSZ8QnOPwHVlFrT_Wc"
#define FIREBASE_PROJECT_ID "smart-med-alert"
// -----------------------------------------
// 2. DYNAMIC USER DATA
// -----------------------------------------
Preferences preferences;
String user_uid = "";
String user_email = "";
String user_password = "";

// -----------------------------------------
// 3. HARDWARE PINS & STRUCTURE
// -----------------------------------------
FirebaseData fbdo;
FirebaseAuth auth;
FirebaseConfig config;

struct BoxSection {
    String slotPrefix;
    int switchPin;
    int greenLedPin;
    int redLedPin;
    bool alarmActive;
    bool boxWasOpened;
    int currentTakenCount;
};

// Defining the three sections for the medicine box
BoxSection sections[3] = {
    {"mor", 22, 21, 19, false, false, 0},
    {"lun", 23, 18,  5, false, false, 0},
    {"nig", 32, 25, 26, false, false, 0}
};

void setup() {
    Serial.begin(115200);

    // Initialize Pins for LEDs and Switches
    for (int i = 0; i < 3; i++) {
        pinMode(sections[i].switchPin, INPUT_PULLUP);
        pinMode(sections[i].greenLedPin, OUTPUT);
        pinMode(sections[i].redLedPin, OUTPUT);
        digitalWrite(sections[i].greenLedPin, HIGH);
        digitalWrite(sections[i].redLedPin, LOW);
    }

    // Retrieve saved user credentials
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

    // If credentials are missing, start the Captive Portal setup
    if (user_uid.length() < 5 || user_email.length() < 5 || user_password.length() < 5) {
        wm.resetSettings(); 
        if (!wm.startConfigPortal("SmartMedBox_Setup")) {
            ESP.restart();
        }
    } else {
        if (!wm.autoConnect("SmartMedBox_Setup")) {
            ESP.restart();
        }
    }

    user_uid = custom_uid.getValue();
    user_email = custom_email.getValue();
    user_password = custom_pass.getValue();

    preferences.putString("uid", user_uid);
    preferences.putString("email", user_email);
    preferences.putString("password", user_password);

    // Firebase Auth Configuration
    config.api_key = API_KEY;
    auth.user.email = user_email.c_str();        
    auth.user.password = user_password.c_str();  
    config.database_url = String(FIREBASE_PROJECT_ID) + ".firebaseio.com";
    config.token_status_callback = tokenStatusCallback;
    
    Firebase.begin(&config, &auth);
    Firebase.reconnectWiFi(true);
}

void loop() {
    if (!Firebase.ready()) {
        delay(1000);
        return;
    }

    String syncDocPath = "users/" + user_uid + "/hardware/sync";

    // --- STEP 1: READ CLOUD ALARM TRIGGERS ---
    if (Firebase.Firestore.getDocument(&fbdo, FIREBASE_PROJECT_ID, "", syncDocPath.c_str(), "")) {
        FirebaseJson json;
        json.setJsonData(fbdo.payload());
        FirebaseJsonData jsonData;

        for (int i = 0; i < 3; i++) {
            String alarmField = "fields/" + sections[i].slotPrefix + "AlarmActive/booleanValue";
            String countField = "fields/" + sections[i].slotPrefix + "TakenCount/integerValue";
            
            bool cloudAlarmActive = false;
            if (json.get(jsonData, alarmField)) cloudAlarmActive = jsonData.boolValue;
            if (json.get(jsonData, countField)) sections[i].currentTakenCount = jsonData.intValue;

            if (cloudAlarmActive && !sections[i].alarmActive) {
                sections[i].alarmActive = true;
                digitalWrite(sections[i].greenLedPin, LOW);
                digitalWrite(sections[i].redLedPin, HIGH);
                Serial.println("⏰ " + sections[i].slotPrefix + " ALARM! Red LED ON.");
            }
        }
    } else {
        String errReason = fbdo.errorReason();
        if (errReason.indexOf("not found") != -1) {
            String content = "{\"fields\":{\"morAlarmActive\":{\"booleanValue\":false},\"lunAlarmActive\":{\"booleanValue\":false},\"nigAlarmActive\":{\"booleanValue\":false},\"morTakenCount\":{\"integerValue\":\"0\"},\"lunTakenCount\":{\"integerValue\":\"0\"},\"nigTakenCount\":{\"integerValue\":\"0\"}}}";
            Firebase.Firestore.createDocument(&fbdo, FIREBASE_PROJECT_ID, "", ("users/" + user_uid + "/hardware").c_str(), "sync", content.c_str(), "");
        }
    }

    // --- STEP 2: PHYSICAL BOX INTERACTION & SYNC ---
    for (int i = 0; i < 3; i++) {
        if (sections[i].alarmActive) {
            // Track when the box is physically opened
            if (digitalRead(sections[i].switchPin) == HIGH) sections[i].boxWasOpened = true;

            // Trigger sync when the box is closed after being opened
            if (digitalRead(sections[i].switchPin) == LOW && sections[i].boxWasOpened) {
                Serial.println("✅ " + sections[i].slotPrefix + " Medicine Taken!");
                
                digitalWrite(sections[i].redLedPin, LOW);
                digitalWrite(sections[i].greenLedPin, HIGH);
                sections[i].alarmActive = false;
                sections[i].boxWasOpened = false;

                // Sync payload: App must listen to these fields to update inventory/history
                int newCount = sections[i].currentTakenCount + 1;
                String updateData = "{\"fields\":{\"" + sections[i].slotPrefix + "AlarmActive\":{\"booleanValue\":false}, \"" + sections[i].slotPrefix + "TakenCount\":{\"integerValue\":\"" + String(newCount) + "\"}}}";
                String updateMask = sections[i].slotPrefix + "AlarmActive," + sections[i].slotPrefix + "TakenCount";

                Firebase.Firestore.patchDocument(&fbdo, FIREBASE_PROJECT_ID, "", syncDocPath.c_str(), updateData.c_str(), updateMask.c_str());
            }
        }
    }
    delay(3000); 
}