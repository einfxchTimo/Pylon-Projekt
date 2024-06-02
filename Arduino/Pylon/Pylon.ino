#include "SoftwareSerial.h"
#include <Adafruit_MPU6050.h>
#include <Adafruit_Sensor.h>
#include <Wire.h>

#define PIN_RX 5
#define PIN_TX 6
#define PIN_LED_R 8
#define PIN_LED_G 9

int ID = 1;

Adafruit_MPU6050 mpu;
float acc_x = 0;
float acc_y = 0;
float acc_z = 0;
float acceleration = 0;

SoftwareSerial FunkSerial(PIN_RX, PIN_TX);
String Funk_Input = "";

bool verbunden = false;
bool scharf = false;
bool alarm = false;
bool send_alarm = false;
int online = 0;

unsigned long led_timer = 0;
unsigned long checkOnline_timer = 0;
unsigned long sendAlarm_timer = 0;
unsigned long sendID_timer = 0;

void setup() {
  Serial.begin(9600);
  FunkSerial.begin(2400);
  pinMode(PIN_LED_R, OUTPUT);
  pinMode(PIN_LED_G, OUTPUT);

  if (!mpu.begin()) {
    Serial.println("Failed to find MPU6050 chip");
    while (1) {
      delay(10);
    }
  }
  mpu.setAccelerometerRange(MPU6050_RANGE_8_G);
  mpu.setGyroRange(MPU6050_RANGE_500_DEG);
  mpu.setFilterBandwidth(MPU6050_BAND_21_HZ);
  delay(100);
}


void loop() {
  MPU();  //Beschleunigungssensor

  if (send_alarm && millis() - sendAlarm_timer > 200) {  // Jede 0.2s Alarm senden wenn send_alarm == true
    sendAlarm_timer = millis();
    FunkSerial.print("!" + String(ID) + "|alarm");
  }

  Funk();
  LED_Controler();

  if (millis() - checkOnline_timer > 1500) {  //schauen ob Signal von Receiver kommt
    checkOnline_timer = millis();
    if (online >= 1) {
      verbunden = true;
    } else {
      verbunden = false;
    }
    online = 0;
  }

  if (millis() - sendID_timer > 1000) {  //jede Sekunde Signal (ID) senden
    sendID_timer = millis();
    FunkSerial.print("!" + String(ID));
  }
}


void Funk() {
  Funk_Input = "";
  boolean data = false;
  while (FunkSerial.available()) {
    char incomingByte = FunkSerial.read();
    delay(5);
    if (data == true) {
      if (incomingByte == '!') {
      	break;
      } else Funk_Input += char(incomingByte);
    } else if (incomingByte == '!') {
      data = true;
    }
  }
  if (data == true) {
    Serial.println(Funk_Input);
    if (Funk_Input == String(ID) + "|an") {  // Scharf schalten
      sensors_event_t a, g, temp;
      mpu.getEvent(&a, &g, &temp);
      acc_x = a.acceleration.x;
      acc_y = a.acceleration.y;
      acc_z = a.acceleration.z;
      FunkSerial.print("!" + String(ID) + "|an|OK");
      scharf = true;
    } else if (Funk_Input == String(ID) + "|aus") {  // Entschärfen
      FunkSerial.print("!" + String(ID) + "|aus|OK");
      scharf = false;
    } else if (Funk_Input == String(ID) + "|off") {  // Alarm deaktivieren
      FunkSerial.print("!" + String(ID) + "|off|OK");
      alarm = false;
      send_alarm = false;
    } else if (Funk_Input == String(ID) + "|stop") {  // Alarm sender deaktivieren
      send_alarm = false;
    } else if (Funk_Input == "0") {  // Online Status
      online++;
    }
  }
}


void MPU() {  //Beschleunigungssensor
  sensors_event_t a, g, temp;
  mpu.getEvent(&a, &g, &temp);
  acceleration = abs(acc_x - a.acceleration.x) + abs(acc_y - a.acceleration.y) + abs(acc_z - a.acceleration.z);
  if (scharf == true && acceleration > 10) {
    scharf = false;
    alarm = true;
    send_alarm = true;
    FunkSerial.print("!" + String(ID) + "|alarm");
    sendAlarm_timer = millis();
  }
}


void LED_Controler() {
  if (alarm) {
    digitalWrite(PIN_LED_R, HIGH);
    digitalWrite(PIN_LED_G, LOW);
  } else if (scharf) {
    if(!verbunden) {
      if (millis() - led_timer > 1000) {
        led_timer = millis();
      }
      if (millis() - led_timer > 500) {
        digitalWrite(PIN_LED_R, LOW);
        digitalWrite(PIN_LED_G, LOW);
      } else {
        digitalWrite(PIN_LED_R, HIGH);
        analogWrite(PIN_LED_G, 75);
      }
    } else {
    digitalWrite(PIN_LED_R, HIGH);
    analogWrite(PIN_LED_G, 75);
    }
  } else if (verbunden) {
    digitalWrite(PIN_LED_R, LOW);
    digitalWrite(PIN_LED_G, HIGH);
  } else {
    if (millis() - led_timer > 1000) {
      led_timer = millis();
    }
    if (millis() - led_timer > 500) {
      digitalWrite(PIN_LED_R, LOW);
      digitalWrite(PIN_LED_G, LOW);
    } else {
      digitalWrite(PIN_LED_R, LOW);
      digitalWrite(PIN_LED_G, HIGH);
    }
  }
}