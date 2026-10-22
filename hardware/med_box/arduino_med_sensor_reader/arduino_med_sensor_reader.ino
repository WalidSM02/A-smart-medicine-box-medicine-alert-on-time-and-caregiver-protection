#include <Wire.h>
#include "MAX30105.h"
#include "spo2_algorithm.h"
#include <SoftwareSerial.h>

// Set up SoftwareSerial to talk to the ESP32 on Pins 2 and 3
SoftwareSerial espSerial(2, 3); // RX, TX

MAX30105 particleSensor;

#define MAX_BRIGHTNESS 255

// CRITICAL FIX: Changed from uint32_t to uint16_t to match Arduino Uno's memory-saving SpO2 algorithm requirements
uint16_t irBuffer[100]; // infrared LED sensor data
uint16_t redBuffer[100];  // red LED sensor data

int32_t bufferLength; // data length
int32_t spo2; // SPO2 value
int8_t validSPO2; // indicator to show if the SPO2 calculation is valid
int32_t heartRate; // heart rate value
int8_t validHeartRate; // indicator to show if the heart rate calculation is valid

void setup() {
  Serial.begin(115200); // For PC debugging
  espSerial.begin(9600); // To ESP32

  if (!particleSensor.begin(Wire, I2C_SPEED_FAST)) {
    Serial.println(F("MAX30102 was not found. Please check wiring/power."));
    while (1);
  }

  byte ledBrightness = 60; //Options: 0=Off to 255=50mA
  byte sampleAverage = 4; //Options: 1, 2, 4, 8, 16, 32
  byte ledMode = 2; //Options: 1 = Red only, 2 = Red + IR, 3 = Red + IR + Green
  byte sampleRate = 100; //Options: 50, 100, 200, 400, 800, 1000, 1600, 3200
  int pulseWidth = 411; //Options: 69, 118, 215, 411
  int adcRange = 4096; //Options: 2048, 4096, 8192, 16384

  particleSensor.setup(ledBrightness, sampleAverage, ledMode, sampleRate, pulseWidth, adcRange);
}

void loop() {
  bufferLength = 100; 

  // Read the first 100 samples
  for (byte i = 0 ; i < bufferLength ; i++) {
    while (particleSensor.available() == false) particleSensor.check();
    redBuffer[i] = particleSensor.getRed();
    irBuffer[i] = particleSensor.getIR();
    particleSensor.nextSample();
  }

  // Calculate heart rate and SpO2
  maxim_heart_rate_and_oxygen_saturation(irBuffer, bufferLength, redBuffer, &spo2, &validSPO2, &heartRate, &validHeartRate);

  // Continuously take samples and calculate
  while (1) {
    for (byte i = 25; i < 100; i++) {
      redBuffer[i - 25] = redBuffer[i];
      irBuffer[i - 25] = irBuffer[i];
    }

    for (byte i = 75; i < 100; i++) {
      while (particleSensor.available() == false) particleSensor.check();
      redBuffer[i] = particleSensor.getRed();
      irBuffer[i] = particleSensor.getIR();
      particleSensor.nextSample();
      
      // Print to Serial Monitor and send to ESP32 if valid
      if (validSPO2 == 1 && validHeartRate == 1 && spo2 > 0 && heartRate > 0) {
        
        // CRITICAL MEMORY FIX: Removed the 'String' object and used F() macro to save RAM.
        // With only 44 bytes of free RAM originally, the String object would cause the Arduino to crash!
        Serial.print(F("Sending: HR:"));
        Serial.print(heartRate);
        Serial.print(F(",SPO2:"));
        Serial.println(spo2);
        
        espSerial.print(F("HR:"));
        espSerial.print(heartRate);
        espSerial.print(F(",SPO2:"));
        espSerial.println(spo2);
        
        delay(1000); // Send an update every 1 second
      }
    }

    maxim_heart_rate_and_oxygen_saturation(irBuffer, bufferLength, redBuffer, &spo2, &validSPO2, &heartRate, &validHeartRate);
  }
}