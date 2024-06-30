#include <SoftwareSerial.h>
SoftwareSerial hc12(7, 6);

void setup() {
  pinMode(8, OUTPUT);
  digitalWrite(8, LOW);
  hc12.begin(9600);
  Serial.begin(9600);
}

void loop() {
  while (hc12.available()) {
    Serial.write(hc12.read());
  }
  while (Serial.available()) {
    hc12.write(Serial.read());
  }
}


/*
AT-Befehle des HC-12:
Befehl:	    Beschreibung:	                                                                                                      Ausgabe/Beispiel:

AT	        Test-Kommando	                                                                                                      OK

AT+Bxxxx	  Befehl zum Ändern der UART-Baudrate.                                                                                Beispiel: AT+B19200
            Standardwert: 9600bps                                                                                               Antwort: OK+B19200
            Mögliche Werte:                         
            1200bps
            2400bps
            4800bps
            9600bps
            19200bps
            38400bps
            57600bps
            115200bps
            
AT+Cxxx	    Befehl zum Ändern des Kommunikationskanals der drahtlosen Verbindung.                                               Beispiel: AT+C002
            Mögliche Werte: 001 -127 (Ab einem Kanal > 100 ist die Distanz nicht garantiert)                                    Antwort: OK+C002
            Standardwert: 001	

AT+FUx	    UART-Übertragungsmodus ändern.                                                                                      Beispiel: AT+FU1
            Mögliche Werte: FU1, FU2, FU3 und FU4.                                                                              Antwort: OK+FU1
            Standardmodus: FU3	

AT+Px	      Einstellen der Sendeleistung                                                                                        Beispiel: AT+P5
            Mögliche Werte: 1-8                                                                                                 Antwort: OK+P5
            Standardmodus: 8 (20dBm)                          

AT+Ry	      Abfragen der eingestellten Parameter für Baudrate, Kommunikationskanal, Übertragungsmodus und Sendeleistung         Beispiel: AT+RB
            Mögliche Werte: B, C, F, P                                                                                          Antwort: OK+B9600

AT+RX	      Abfragen aller Parameter des Moduls	Beispiel: AT+RX                                                                 Antwort:
                                                                                                                                OK+FU3
                                                                                                                                OK+B9600
                                                                                                                                OK+C001
                                                                                                                                OK+RP:+20dBm

AT+Uxxx	    Kann verwendet werden, um die Datenbits, Paritätsbits und Stoppbits der UART-Kommunikation zu konfigurieren.	      Beispiel: AT+U8O1
                                                                                                                                Antwort: OK+U8O1

AT+SLEEP	  Dieser Befehl versetzt das Modul beim Verlassen des AT-Befehls-Modus in den Deep-Sleep.                             Beispiel: AT+SLEEP
            Der Betriebsstrom beträgt dann etwa 22μA, und das Modul kann die UART-Daten nicht weiterleiten.                     Antwort: OK+SLEEP
            Um den Deep-Sleep Modus wieder zu verlassen, muss das Modul erneut in den AT-Befehlsmodus-Zustand versetzt werden. 
            Durch den Wechsel in den AT-Modus mit der SET Pin verlässt das Modul automatisch den Schlafmodus.

AT+V	    Abfrage der Modul-Firmware Versionsinformationen	                                                                    Beispiel: AT+V
                                                                                                                                Antwort: www.hc01.com HC-12_V2.4

AT+DEFAULT	Wiederherstellen der Standard-Einstellungen	                                                                        Beispiel: AT+DEFAULT
                                                                                                                                Antwort: OK+DEFAULT
*/