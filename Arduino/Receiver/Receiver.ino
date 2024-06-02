#include "SoftwareSerial.h"

#define PIN_KNOPF 3
#define PIN_RX 5
#define PIN_TX 6
#define PIN_LED_R 8
#define PIN_LED_G 9
#define PIN_PIEPER 11

SoftwareSerial FunkSerial(PIN_RX, PIN_TX);
String BT_Input;
String Funk_Input;

bool alarm = false;
bool send_alarm = false;
int connected[8] = { 0, 0, 0, 0, 0, 0, 0, 0 };  //0=Offline, 1=Online, 2=Scharf, 3=Online+Verbindungsproblem, 4=Scharf+Verbindungsproblem, 5=Alarm
int FunkRead[8] = { 0, 0, 0, 0, 0, 0, 0, 0 };   //Anzahl der gezählten Signale von jedem Hütchen

unsigned long checkOnline_timer = 0;
unsigned long led_timer = 0;
unsigned long sendOnline_timer = 0;
unsigned long sendAlarm_timer = 0;

void setup() {
  Serial.begin(9600);  // -> Bluetooth
  FunkSerial.begin(2400);
  pinMode(PIN_PIEPER, OUTPUT);
  pinMode(PIN_LED_R, OUTPUT);
  pinMode(PIN_LED_G, OUTPUT);
  pinMode(PIN_KNOPF, INPUT_PULLUP);
  attachInterrupt(digitalPinToInterrupt(PIN_KNOPF), KnopfPressed, RISING);
}

void loop() {
  if (send_alarm && millis() - sendAlarm_timer > 200) {  //alle 0.2s Alarm senden wenn send_alarm == true
    sendAlarm_timer = millis();
    Serial.write("Alarm");
  }

  Funk();
  Bluetooth();
  LED_Controler();

  if (millis() - sendOnline_timer > 1000) {  //alle 1s eigenes Signal senden
    sendOnline_timer = millis();
    FunkSerial.print("!0");
  }
}

void Bluetooth() {
  BT_Input = "";
  boolean BT_data = false;
  while (Serial.available()) {
    char incomingByte = Serial.read();
    delay(5);
    if (BT_data == true) {
      if (incomingByte == '!') {
      	break;
      } else BT_Input += char(incomingByte);
    } else if (incomingByte == '!') {
      BT_data = true;
    }
  }
  if (BT_data == true) {
    if (BT_Input == "0|devices") {
      String msg = "D:" + String(connected[0]) + ":" + connected[1] + ":" + connected[2] + ":" + connected[3] + ":" + connected[4] + ":" + connected[5] + ":" + connected[6] + ":" + connected[7];
      Serial.print(msg);
    } else if (BT_Input == "0|aus") {
      for (int i = 0; i < 8; i++) {
        if (connected[i] == 5) {
          FunkSerial.print("!" + String(i+1) + "|off");
        }
      }
    } else if (BT_Input == "0|stop") {
      send_alarm = false;
    } else if (BT_Input.indexOf("an") > 0 && connected[BT_Input.substring(0, 1).toInt() - 1] != 0) {
      FunkSerial.print("!" + BT_Input.substring(0, 1) + "|an");
    } else if (BT_Input.indexOf("aus") > 0 && connected[BT_Input.substring(0, 1).toInt() - 1] != 0) {
      FunkSerial.print("!" + BT_Input.substring(0, 1) + "|aus");
    }
  }
}

void Funk() {
  Funk_Input = "";
  boolean Funk_data = false;
  while (FunkSerial.available()) {
    char incomingByte = FunkSerial.read();
    delay(5);
    if (Funk_data == true) {
      if (incomingByte == '!') {
      	break;
      } else Funk_Input += char(incomingByte);
    } else if (incomingByte == '!') {
      Funk_data = true;
    }
  }
  if (Funk_data == true) {
    if (Funk_Input.indexOf("|alarm") > 0) {
      FunkSerial.print("!" + Funk_Input.substring(0, 1) + "|stop");
      connected[Funk_Input.substring(0, 1).toInt() - 1] = 5;
      alarm = true;
      if (!send_alarm) {
        send_alarm = true;
        Serial.write("Alarm");
        sendAlarm_timer = millis();
      }
    } else if (Funk_Input.indexOf("|off|OK") > 0) {
      connected[Funk_Input.substring(0, 1).toInt() - 1] = 1;
      Serial.print(Funk_Input.substring(0, 1) + "|off|OK");
      bool test = false;
      for (int i = 0; i < 8; i++) {
        if (connected[i] == 5) {
          test = true;
        }
      }
      if(!test) {
        alarm = false;
        send_alarm = false;
      }
    } else if (isDigit(Funk_Input.charAt(0))) {
      FunkRead[Funk_Input.toInt() - 1]++;
    }
    if (Funk_Input.indexOf("|an|OK") > 0) {
      if (connected[Funk_Input.substring(0, 1).toInt() - 1] == 3) {  // Wenn Online mit Verbindungsproblem
        connected[Funk_Input.substring(0, 1).toInt() - 1] = 4;       // -> Scharf schalten mit Verbindungsproblem
        Serial.print(Funk_Input.substring(0, 1) + "|an|OK|P");
      } else {                                                  // Wenn Online
        connected[Funk_Input.substring(0, 1).toInt() - 1] = 2;  // -> Scharf schalten
        Serial.print(Funk_Input.substring(0, 1) + "|an|OK");
      }
    } else if (Funk_Input.indexOf("|aus|OK") > 0) {
      if (connected[Funk_Input.substring(0, 1).toInt() - 1] == 4) {  // Wenn Scharf mit Verbindungsproblem
        connected[Funk_Input.substring(0, 1).toInt() - 1] = 3;       // -> Online mit Verbindungsproblem
        Serial.print(Funk_Input.substring(0, 1) + "|aus|OK|P");
      } else {                                                  // Wenn Scharf
        connected[Funk_Input.substring(0, 1).toInt() - 1] = 1;  // -> Online
        Serial.print(Funk_Input.substring(0, 1) + "|aus|OK");
      }
    }
  }

  if (millis() - checkOnline_timer > 2500) {  //alle 2.5s schauen wie oft Funk Verbindung da war
    checkOnline_timer = millis();
    bool changed = false;            //Boolean ob es veränderung zu davorigen Zustand gibt
    for (int i = 0; i < 8; i++) {    //Auswertung der Funksignale
      if(connected[i] != 5) {
        int lastState = connected[i];  //Speicherung des letzten Zustands

        if (FunkRead[i] == 0) {  // Bei keiner Verbindung
          connected[i] = 0;      // -> Offline

        } else if (FunkRead[i] >= 2) {                   // Bei guter Verbindung
          if (connected[i] == 2 || connected[i] == 4) {  // Wenn Scharf
            connected[i] = 2;                            // -> Scharf
          } else {                                       // Wenn nicht scharf
            connected[i] = 1;                            // -> Online
          }
        } else {                                         // Bei schlechter Verbindung
          if (connected[i] == 2 || connected[i] == 4) {  // Wenn Scharf
            connected[i] = 4;                            // -> Scharf mit Verbindungsproblem
          } else {                                       // Wenn nicht scharf
            connected[i] = 3;                            // -> Online mit Verbindungsproblem
          }
        }
        FunkRead[i] = 0;                                // Zurücksetzen des Signal Zählers
        if (lastState != connected[i]) changed = true;  // Boolean ob es veränderung zu davorigen Zustand gibt
      }
    }
    if (changed) {
      String msg = "D:" + String(connected[0]) + ":" + connected[1] + ":" + connected[2] + ":" + connected[3] + ":" + connected[4] + ":" + connected[5] + ":" + connected[6] + ":" + connected[7];
      Serial.print(msg);
    }
  }
}

void LED_Controler() {
  bool verbunden = false;
  bool scharf = false;
  for (int i = 0; i < 8; i++) {
    if (connected[i] == 1 || connected[i] == 3) {
      verbunden = true;
    } else if (connected[i] == 2 || connected[i] == 4) {
      scharf = true;
    }
  }
  if (alarm) {
    digitalWrite(PIN_LED_R, HIGH);
    digitalWrite(PIN_LED_G, LOW);
    if (millis() - led_timer > 1000) {
      led_timer = millis();
    }
    if (millis() - led_timer > 250) {
      digitalWrite(PIN_PIEPER, LOW);
    } else {
      analogWrite(PIN_PIEPER, 50);
    }
  } else if (scharf) {
    digitalWrite(PIN_LED_R, HIGH);
    analogWrite(PIN_LED_G, 75);
    digitalWrite(PIN_PIEPER, LOW);
  } else if (verbunden) {
    digitalWrite(PIN_LED_R, LOW);
    digitalWrite(PIN_LED_G, HIGH);
    digitalWrite(PIN_PIEPER, LOW);
  } else {
    if (millis() - led_timer > 1000) {
      led_timer = millis();
    }
    if (millis() - led_timer > 500) {
      digitalWrite(PIN_LED_R, LOW);
      digitalWrite(PIN_LED_G, LOW);
      digitalWrite(PIN_PIEPER, LOW);
    } else {
      digitalWrite(PIN_LED_R, LOW);
      digitalWrite(PIN_LED_G, HIGH);
      digitalWrite(PIN_PIEPER, LOW);
    }
  }
}

void KnopfPressed() {
  bool verbunden = false;
  bool scharf = false;
  for (int i = 0; i < 8; i++) {
    if (connected[i] == 1 || connected[i] == 3) {
      verbunden = true;
    } else if (connected[i] == 2 || connected[i] == 4) {
      scharf = true;
    }
  }

  if (alarm) {
    for (int i = 0; i < 8; i++) {
      if (connected[i] == 5) {
        FunkSerial.print("!" + String(i+1) + "|off");
      }
    }
  } else if (verbunden) {
    for (int i = 0; i < 8; i++) {
      if (connected[i] == 1 || connected[i] == 3) {
        FunkSerial.print("!" + String(i+1) + "|an");
      }
    }
  } else if (scharf) {
    for (int i = 0; i < 8; i++) {
      if (connected[i] == 2 || connected[i] == 4) {
        FunkSerial.print("!" + String(i+1) + "|aus");
      }
    }
  }
}
