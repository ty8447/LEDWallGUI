//DormLED Controller V5 by Cole Rabe
#include <Adafruit_NeoPixel.h>
#include <EasyColor.h>
#include <Adafruit_TinyUSB.h>
#include "RTClib.h"
#include <bluefruit.h>
#include <vector>
#include <Adafruit_LittleFS.h>
#include <InternalFileSystem.h>

#define MAX_PRPH_CONNECTION 2
uint8_t connection_count = 0;

//HSV to RGB Setup
EasyColor::HSVRGB HSVConverter;
RTC_DS3231 rtc;
//End HSV to RGB Setup
#define PIN 5
#define SQWV 10
#define LEDCOUNT 1165
#define SERIAL_SIZE 32
#define MAX_BUFFER_SIZE 256

//LED Status + Modes Variables
int ledMode = 1;
int specialMode = 0;
bool cConnected = false;
uint16_t buffStripBright = 255;
uint16_t stripBright = 255;
uint16_t prevStripBright = 255;

struct LEDMode {
  String name;
  int numParams;
  String paramNames[5];
  // Instead of storing the string value, store the individual RGB components
  unsigned int paramValuesR[5];  // Red values
  unsigned int paramValuesG[5];  // Green values
  unsigned int paramValuesB[5];  // Blue values
  String paramValuesOther[5];    // Store other parameter types as strings
};

// Array of LED Modes
LEDMode ledModes[] = {
  { "Color", 1, { "Color" }, { 255 }, { 0 }, { 0 }, { "" } },  // Red
  { "Rainbow", 1, { "Speed" }, { 0 }, { 0 }, { 0 }, { "20" } },
  { "Alternating colors", 3, { "Color", "SColor", "Speed" }, { 0, 0, 0 }, { 0, 255, 0 }, { 255, 0, 0 }, { "", "", "20" } },  // Blue, Green
  { "Fade", 1, { "Speed" }, { 0 }, { 0 }, { 0 }, { "20" } },
  { "Pulse", 3, { "Color", "SColor", "Speed" }, { 0, 0, 0 }, { 0, 255, 0 }, { 255, 0, 0 }, { "", "", "20" } },  // Blue, Green
  { "Breathe", 2, { "Color", "Speed" }, { 255 }, { 0 }, { 0 }, { "", "20" } },                                  // Red
  { "Paint", 1, { "Speed" }, { 0 }, { 0 }, { 0 }, { "20" } },
  { "Strobe", 2, { "Color", "Speed" }, { 255 }, { 0 }, { 0 }, { "", "30" } },                                   // Red
  { "Time", 1, { "Color" }, { 255 }, { 0 }, { 0 }, { "" } },                                                    // Red
  { "Cylon", 3, { "Color", "SColor", "Speed" }, { 255, 0, 0 }, { 0, 0, 255 }, { 0, 0, 0 }, { "", "", "20" } },  // Red, Blue
  { "Phaser", 2, { "Color", "Speed" }, { 255 }, { 0 }, { 0 }, { "", "20" } },                                   // Red
  { "Alternating Phaser", 2, { "Color", "SColor" }, { 255, 0, 0 }, { 0, 0, 255 }, { 0, 0, 0 }, { "", "" } },    // Red, Blue
  { "Weather", 1, { "Color" }, { 255 }, { 0 }, { 0 }, { "" } },                                                 // Red
  { "Police Lights", 1, { "Speed" }, { 0 }, { 0 }, { 0 }, { "20" } },
  { "Fire", 1, { "Speed" }, { 0 }, { 0 }, { 0 }, { "20" } },
  { "Bouncing Ball", 1, { "Color" }, { 255 }, { 0 }, { 0 }, { "" } },                                    // Red
  { "Twinkle", 2, { "Color", "Frequency" }, { 255 }, { 0 }, { 0 }, { "", "60" } },                       // Red
  { "Sparkle", 2, { "Color", "Frequency" }, { 255 }, { 0 }, { 0 }, { "", "60" } },                       // Red
  { "Running Lights", 3, { "Color", "Speed", "Division" }, { 255 }, { 0 }, { 0 }, { "", "10", "20" } },  // Red
  { "Theatre Chase", 2, { "Color", "Speed" }, { 255 }, { 0 }, { 0 }, { "", "10" } },                     // Red
  { "Meteor", 1, { "Color" }, { 255 }, { 0 }, { 0 }, { "" } },                                           // Red
  { "Pop", 2, { "Color", "Speed" }, { 255 }, { 0 }, { 0 }, { "", "10" } },                               // Red
};

int numModes = sizeof(ledModes) / sizeof(ledModes[0]);  // Get the number of LED modes

bool ledState = true;
bool timeoutlight = false;
bool hasSet = false;
int ddColor1 = 0;
int ddColor2 = 0;
int ch = 69;
int cs = 70;
int cv = 71;
float cr = 255;
float cg = 255;
float cb = 255;
int fh = 0;
float fr = 255;
float fg = 255;
float fb = 255;
String val;
//End LED Status + Modes Variables

//Parsing Serial Data Variables
const byte serialsize = 32;
char input[SERIAL_SIZE];
char tempInput[SERIAL_SIZE];
char intxt_1[SERIAL_SIZE] = { 0 };
int inNum_0 = 0;
float inNum_1 = 0.0;
float inNum_2 = 0.0;
float inNum_3 = 0.0;
bool newData = false;
int second = 0;
int minute = 18;
int hour = 3;
int dayOfWeek = 2;
int dayOfMonth = 1;
int month = 5;
int year = 23;
String currentModeName = "";
String previousModeName = "";
//End Parsing Serial Data Variables

//Global Animation Variables
int minuteMap[60] = { 707, 724, 741, 757, 774, 791, 807, 823, 840, 856, 872, 888, 905, 921, 938, 954, 972, 990, 1008, 1026, 1044, 1063, 1081, 8, 26, 45, 63, 81, 98, 116, 134, 156, 178, 199, 221, 243, 265, 286, 308, 329, 351, 373, 394, 416, 437, 459, 476, 492, 509, 525, 542, 558, 575, 591, 608, 624, 641, 657, 674, 690 };
int hourMap[12] = { 707, 791, 872, 954, 1044, 45, 134, 243, 351, 459, 542, 624 };
int beamSize = LEDCOUNT / 20;
bool ledON = false;
int ledNum = 0;
int beamPos[LEDCOUNT];
unsigned long cMillis = 0;
unsigned long pMillis = 0;
unsigned long rMillis = 0;
unsigned long sMillis = 0;
unsigned long tMillis = 0;
int DST = 0;  //If between March and November (DST) set to 1, else set to 0
int pSecond = 0;
bool aOn = false;
bool goingUp = true;
uint16_t brightness = 254;
uint16_t prevBrightness = 1;
int handBright = 0;
int mHandBright = 0;
int backBright = 0;
int aStep = 0;
bool strobeOn = false;
bool altState = false;
int ledDiv = 15;
bool color = false;
int modeSpeed = 60;
float rStates[LEDCOUNT];
float bStates[LEDCOUNT];
float gStates[LEDCOUNT];
float pulseBar1[8];  // Direction, Size, r, g, b
float pulseBar2[8];
float pulseBar3[8];
float pulseBar4[8];
float pulseBar5[8];
float popHang[LEDCOUNT];
float setPopHang[LEDCOUNT];
uint16_t nextrgb[] = { 255, 0, 0 };
float color1[] = { 255, 255, 255 };
float color2[] = { 0, 255, 0 };
int pixChosen = 0;
int pColorOption = 0;
int ledStatus[LEDCOUNT];
bool barDirection = false;
const int barSize = 30;
int setBarSize = 0;
int phaserNum = 0;
bool inRamp = false;
int rampStep = 0;
int aMBright = 255;
volatile long count = 0;
long pMinute = 0;
int sBarPos = hourMap[0];

#define CUSTOM_SERVICE_UUID "12345678-1234-5678-1234-56789abcdef0"
#define LED_MODE_LIST_UUID "12345678-1234-5678-1234-56789abcdef1"
#define PARAMETER_REQUEST_UUID "12345678-1234-5678-1234-56789abcdef2"
#define PARAMETER_RESPONSE_UUID "12345678-1234-5678-1234-56789abcdef3"
#define PARAMETER_CONTROL_UUID "12345678-1234-5678-1234-56789abcdef4"

#define BLE_CCCD_NOTIFY_VALUE 0x0001
#define BLE_CCCD_INDICATE_VALUE 0x0002

// #define PAIRING_PIN     "123456"

// Parameter 1 = number of pixels in strip
// Parameter 2 = Arduino pin number (most are valid)
// Parameter 3 = pixel type flags, add together as needed:
//   NEO_KHZ800  800 KHz bitstream (most NeoPixel products w/WS2812 LEDs)
//   NEO_KHZ400  400 KHz (classic 'v1' (not v2) FLORA pixels, WS2811 drivers)
//   NEO_GRB     Pixels are wired for GRB bitstream (most NeoPixel products)
//   NEO_RGB     Pixels are wired for RGB bitstream (v1 FLORA pixels, not v2)
//   NEO_RGBW    Pixels are wired for RGBW bitstream (NeoPixel RGBW products)
Adafruit_NeoPixel strip = Adafruit_NeoPixel(LEDCOUNT, PIN, NEO_GRB + NEO_KHZ800);

// IMPORTANT: To reduce NeoPixel burnout risk, add 1000 uF capacitor across
// pixel power leads, add 300 - 500 Ohm resistor on first pixel's data input
// and minimize distance between Arduino and first pixel.  Avoid connecting
// on a live circuit...if you must, connect GND first.

BLEDfu bledfu;  // OTA DFU service
BLEDis bledis;  // device information
// BLEUart bleuart;  // uart over BLE

BLEService customService(CUSTOM_SERVICE_UUID);
BLECharacteristic ledModesCharacteristic(LED_MODE_LIST_UUID, BLERead, 512);
BLECharacteristic parameterRequestCharacteristic(PARAMETER_REQUEST_UUID, BLEWriteWithoutResponse, 128);
BLECharacteristic parameterResponseCharacteristic(PARAMETER_RESPONSE_UUID, BLERead | BLEWriteWithoutResponse | BLENotify, 128);
BLECharacteristic parameterControlCharacteristic(PARAMETER_CONTROL_UUID, BLEWriteWithoutResponse, 256);

uint16_t connHandle;

// Define a structure to store key-value pairs
struct KeyValue {
  String key;
  String value;
};

// Set a max size for your key-value storage
#define MAX_PARAMETERS 10
KeyValue parameters[MAX_PARAMETERS];
int paramCount = 0;

void setup() {
  Serial.begin(115200);
  strip.begin();
  Serial.println("------------------------------\n");
  Serial.println("Starting Controller");
  rtc.begin();
  if (!rtc.begin()) {
    Serial.println("Couldn't find RTC");
    Serial.flush();
    while (1) delay(10);
  }

  strip.setBrightness(255);
  strip.fill(strip.Color(0, 0, 0), 0, LEDCOUNT);
  strip.show();
  if (rtc.lostPower()) {
    Serial.println("RTC lost power, let's set the time!");
    rtc.adjust(DateTime(F(__DATE__), F(__TIME__)));
  }
  ledState = false;
  ledMode = 0;
  DateTime now = rtc.now();
  tMillis = now.second() * 1000;

  // Initialize BLE
  // if (!Bluefruit.begin()) {
  //   Serial.println("BLE initialization failed.");
  //   while (1) delay(10);  // Stay here if initialization fails
  // }
  Bluefruit.configPrphBandwidth(BANDWIDTH_MAX);
  Bluefruit.configCentralBandwidth(BANDWIDTH_MAX);
  Bluefruit.begin(MAX_PRPH_CONNECTION, 0);
  // Bluefruit.setTxPower(4);
  Bluefruit.Periph.setConnectCallback(connect_callback);
  Bluefruit.Periph.setDisconnectCallback(disconnect_callback);

  Serial.println("Configuring the Custom Service");
  customService.begin();

  // Serial.println("Setting pairing PIN to: " PAIRING_PIN);
  // Bluefruit.Security.setPIN(PAIRING_PIN);

  ledModesCharacteristic.setProperties(CHR_PROPS_READ | CHR_PROPS_NOTIFY);
  ledModesCharacteristic.setPermission(SECMODE_OPEN, SECMODE_OPEN);
  ledModesCharacteristic.begin();

  parameterRequestCharacteristic.setCccdWriteCallback(cccd_callback);
  parameterRequestCharacteristic.setWriteCallback(parameterRequestCharacteristicWritten);
  parameterRequestCharacteristic.begin();

  // For the response characteristic, ensure that it supports notifications.
  parameterResponseCharacteristic.setProperties(CHR_PROPS_READ | CHR_PROPS_NOTIFY | CHR_PROPS_WRITE_WO_RESP);
  parameterResponseCharacteristic.setPermission(SECMODE_OPEN, SECMODE_OPEN);
  parameterResponseCharacteristic.setWriteCallback(parameterResponseCharacteristicWritten);
  parameterResponseCharacteristic.setCccdWriteCallback(cccd_callback);
  parameterResponseCharacteristic.begin();

  parameterControlCharacteristic.setWriteCallback(parameterControlCharacteristicWritten);
  parameterControlCharacteristic.begin();

  Serial.println("Starting Mode Push");

  Serial.println("Advertising BLE service");

  bledfu.begin();
  bledis.setManufacturer("Cole Rabe");
  bledis.setModel("DormLED Controller");
  bledis.begin();

  // bleuart.begin();

  Bluefruit.setName("Room LED Controller");  // Change Bluetooth device name here

  // Start advertising
  startAdv();

  pinMode(SQWV, INPUT);
  rtc.writeSqwPinMode(DS3231_SquareWave1kHz);
  cResync();
  attachInterrupt(digitalPinToInterrupt(SQWV), RTCMillis, CHANGE);
  timeout();
}

void loop() {
  cMillis = millis();
  cResync();
  executeCurrentMode();
  static uint8_t value[20] = { 0 };
  static uint8_t counter = 0;
  rampBright();

  if (stripBright != buffStripBright && inRamp == false) {
    stripBright = buffStripBright;
    prevStripBright = buffStripBright;
    hasSet = false;
  }

  // Convert the boolean ledState to a String
  String ledStateStr = ledState ? "1" : "0";

  // Write the string over BLE
  parameterResponseCharacteristic.write((uint8_t*)ledStateStr.c_str(), ledStateStr.length());
}


void sendLEDModeList() {
  String modeList = "";  // Start with an empty string to hold the modes

  // Loop through each mode and append its details
  for (int i = 0; i < numModes; i++) {
    modeList += ledModes[i].name + ":";  // Mode name

    // Append each parameter for the current mode
    for (int j = 0; j < ledModes[i].numParams; j++) {
      modeList += ledModes[i].paramNames[j];  // Only include parameter name
      if (j < ledModes[i].numParams - 1) {
        modeList += ",";
      }
    }

    modeList += ";";  // Separate modes with semicolon
  }

  // Send the mode list via the BLE characteristic
  ledModesCharacteristic.write((const uint8_t*)modeList.c_str(), modeList.length());
  ledModesCharacteristic.notify(modeList.c_str());

  Serial.println("LED Modes sent to tablet:");
  Serial.println(modeList);  // Print to Serial for debugging
}

void parameterControlCharacteristicWritten(uint16_t conn_hdl, BLECharacteristic* chr, uint8_t* data, uint16_t len) {
  Serial.println("Data Control Action Called!");
  if (len <= 0) {
    Serial.println("Error: Received data length is zero.");
    return;
  }

  char cleanedData[MAX_BUFFER_SIZE] = { 0 };
  strncpy(cleanedData, (char*)data, min((int)len, MAX_BUFFER_SIZE - 1));  // Use min to prevent buffer overflow

  String controlData = String(cleanedData);
  controlData.trim();

  Serial.print("Received Command: ");
  Serial.println(controlData);


  int firstColonIndex = controlData.indexOf(":");
  if (firstColonIndex == -1) {
    Serial.println("Invalid command format: Missing first colon.");
    return;
  }

  String controlType = controlData.substring(0, firstColonIndex);
  controlData = controlData.substring(firstColonIndex + 1);  // Remove type from command

  int secondColonIndex = controlData.indexOf(":");
  if (secondColonIndex == -1) {
    Serial.println("Invalid command format: Missing second colon.");
    return;
  }

  String controlName = controlData.substring(0, secondColonIndex);
  String controlValue = controlData.substring(secondColonIndex + 1);

  controlType.trim();
  controlName.trim();
  controlValue.trim();

  Serial.print("Controlling Type: ");
  Serial.print(controlType);
  Serial.print(" Named: ");
  Serial.print(controlName);
  Serial.print(" with Value: ");
  Serial.println(controlValue);

  if (controlValue.startsWith("{") && controlValue.endsWith("}")) {
    controlValue = controlValue.substring(1, controlValue.length() - 1);
  }

  if (controlType == "Mode") {
    currentModeName = controlValue;
    Serial.print("Updated Mode to ");
    Serial.println(currentModeName);
    aOn = false;
  } else if (controlType == "Parameter") {
    bool parameterUpdated = false;
    // Find the current mode in the array
    for (int i = 0; i < sizeof(ledModes) / sizeof(ledModes[0]); i++) {
      if (ledModes[i].name == currentModeName) {
        // Mode found, now find the parameter to update
        for (int j = 0; j < ledModes[i].numParams; j++) {
          if (ledModes[i].paramNames[j] == controlName) {
            if (controlName == "Color" || controlName == "SColor") {
              unsigned int r, g, b;
              parseRGB(controlValue, r, g, b);

              // Update the parameter values in the array (store r, g, b separately)
              for (int j = 0; j < ledModes[i].numParams; j++) {
                if (ledModes[i].paramNames[j] == controlName) {
                  ledModes[i].paramValuesR[j] = r;
                  ledModes[i].paramValuesG[j] = g;
                  ledModes[i].paramValuesB[j] = b;
                  Serial.print("Updated parameter ");
                  Serial.print(controlName);
                  Serial.print(" to ");
                  Serial.print(r);
                  Serial.print(", ");
                  Serial.print(g);
                  Serial.print(", ");
                  Serial.println(b);
                  parameterUpdated = true;
                  break;
                }
              }
            } else if (controlName == "Speed" || controlName == "Frequency" || controlName == "Division") {
              // Remove brackets if present (before converting to int)
              if (controlValue.startsWith("[") && controlValue.endsWith("]")) {
                controlValue = controlValue.substring(1, controlValue.length() - 1);
              }
              int numValue = controlValue.toInt();
              Serial.print("Parsed Numeric Parameter (");
              Serial.print(controlName);
              Serial.print("): ");
              Serial.println(numValue);

              // Update the parameter value in the array (convert to String)
              ledModes[i].paramValuesOther[j] = String(numValue);
              Serial.print("Updated parameter ");
              Serial.print(controlName);
              Serial.print(" to ");
              Serial.println(numValue);
              parameterUpdated = true;
            }
            break;  // Parameter found, exit inner loop
          }
        }
        break;  // Mode found, exit outer loop
      }
    }
    if (parameterUpdated) {  // Check the flag *after* the loops
      Serial.println("Successfully updated parameters in the array.");
      previousModeName = "";
    } else {
      Serial.println("Parameter not found for the current mode.");
    }
  } else if (controlType == "Brightness") {
    int brightnessValue = constrain(controlValue.toInt(), 0, 100);
    setBrightness(brightnessValue);
    Serial.print("Updated Brightness to ");
    Serial.println(brightnessValue);
  } else if (controlType == "Power") {
    int powerState = controlValue.toInt();
    ledState = powerState;
    if (ledState) {
      Serial.println("Turning ON LED");
      previousModeName = "";
    } else {
      strip.fill(strip.Color(0, 0, 0), 0, LEDCOUNT);
      strip.show();
      Serial.println("Turning OFF LED");
    }
    // Set Power State Here
  } else {
    Serial.println("Unknown Type");
  }
}

static void parameterResponseCharacteristicWritten(uint16_t conn_handle, BLECharacteristic* pCharacteristic, uint8_t* data, uint16_t len) {
  if (pCharacteristic->uuid == parameterResponseCharacteristic.uuid) {
    std::vector<uint8_t> value(data, data + len);

    if (value.size() == 2) {
      uint16_t cccdValue = (value[1] << 8) | value[0];

      // Use Adafruit's definitions:
      if (cccdValue == BLE_CCCD_NOTIFY_VALUE) {  // Corrected!
        Serial.println("Notifications enabled for parameterResponseCharacteristic");
        // parameterResponseCharacteristic.notify();  // Notify after setting a value
      } else if (cccdValue == BLE_CCCD_INDICATE_VALUE) {  // Corrected!
        Serial.println("Indications enabled for parameterResponseCharacteristic");
      } else if (cccdValue == 0) {
        Serial.println("Notifications/Indications disabled for parameterResponseCharacteristic");
      }
    }
  }
}

void parameterRequestCharacteristicWritten(uint16_t conn_hdl, BLECharacteristic* chr, uint8_t* data, uint16_t len) {
  Serial.println("Data Request Action Called!");
  Serial.print("Raw Data: ");
  for (int i = 0; i < len; i++) {
    Serial.print(data[i], HEX);
    Serial.print(" ");
  }
  Serial.println();

  // Convert data to string
  char dataString[len + 1];
  memcpy(dataString, data, len);
  dataString[len] = '\0';
  String receivedData = String(dataString);
  receivedData.trim();
  Serial.print("Cleaned Command: ");
  Serial.println(receivedData);

  // Extract mode and parameter
  String requestType = "";   //Type of request
  String requestName = "";   //Request the value from type in mode
  String requestValue = "";  //Request the value from type

  int firstColonIndex = receivedData.indexOf(":");
  if (firstColonIndex == -1) {
    Serial.println("Invalid command format: Missing first colon.");
    return;
  } else {
    String requestType = receivedData.substring(0, firstColonIndex);
    receivedData = receivedData.substring(firstColonIndex + 1);

    int secondColonIndex = receivedData.indexOf(":");
    if (secondColonIndex == -1) {
      Serial.println("Invalid command format: Missing second colon.");
      return;
    }

    String requestName = receivedData.substring(0, secondColonIndex);
    String requestValue = receivedData.substring(secondColonIndex + 1);

    requestType.trim();
    requestName.trim();
    requestValue.trim();

    Serial.print("Requesting Type: ");
    Serial.println(requestType);
    Serial.print("From Mode: ");
    Serial.println(requestName);
    Serial.print("And Value: ");
    Serial.println(requestValue);

    if (requestValue.startsWith("{") && requestValue.endsWith("}")) {
      requestValue = requestValue.substring(1, requestValue.length() - 1);
    }
    if (requestType == "Mode") {
      String currentModeNameStr = String(currentModeName);  // Convert mode name to string
      parameterResponse(currentModeNameStr);
    } else if (requestType == "Parameter") {
      bool parameterFound = false;
      for (int i = 0; i < sizeof(ledModes) / sizeof(ledModes[0]); i++) {
        if (ledModes[i].name == requestName) {
          // Look for the requested parameter
          for (int j = 0; j < ledModes[i].numParams; j++) {
            if (ledModes[i].paramNames[j] == requestValue) {
              String responseValue;
              // Check if the parameter corresponds to a color (RGB)
              if (requestValue == "Color" || requestValue == "SColor") {
                responseValue = "{" + String(ledModes[i].paramValuesR[j]) + "," + String(ledModes[i].paramValuesG[j]) + "," + String(ledModes[i].paramValuesB[j]) + "}";
              } else {
                // Non-color parameters are stored as strings in paramValuesOther
                responseValue = ledModes[i].paramValuesOther[j];
              }
              // Send the response
              parameterResponse(responseValue);
              parameterFound = true;

              Serial.print("Responding with ");
              Serial.print(requestValue);
              Serial.print(": ");
              Serial.println(responseValue);
              break;
            }
          }
          if (!parameterFound) {
            Serial.print("Unknown Parameter Name: ");
            Serial.println(requestValue);
          }
        } else {
          Serial.println("Mode name not found!");
        }
      }
    } else if (requestType == "Brightness") {
      String buffStripBrightStr = String(buffStripBright);  // Convert brightness to string
      parameterResponse(buffStripBrightStr);
    } else if (requestType == "Power") {
      String ledStateStr = String(ledState);  // Convert power state to string
      parameterResponse(ledStateStr);
    } else {
      Serial.println("Unknown Request Type");
    }
  }
  memset(data, 0, len + 1);
}

void parameterResponse(const String& responseStr) {
  if (responseStr.length() > 0) {
    // Write the response to the characteristic value.
    // Note: We cast away constness as the write() function expects a non-const pointer.
    parameterResponseCharacteristic.write((uint8_t*)responseStr.c_str(), responseStr.length());

    // Notify connected devices using the notify() function.
    bool notifySuccess = parameterResponseCharacteristic.notify((uint8_t*)responseStr.c_str(), responseStr.length());

    if (notifySuccess) {
      Serial.print("Responded with: ");
      Serial.println(responseStr);
    } else {
      Serial.println("Notification failed!");
    }
  } else {
    Serial.println("Could not respond as length is 0!");
  }
}

void executeCurrentMode() {
  for (int i = 0; i < numModes; i++) {
    if (ledModes[i].name == currentModeName) {
      unsigned int r = 0, g = 0, b = 0;
      unsigned int sr = 0, sg = 0, sb = 0;
      unsigned long speedParam = 0;
      unsigned long frequencyParam = 0;
      unsigned long divisionParam = 0;

      for (int j = 0; j < ledModes[i].numParams; j++) {
        if (ledModes[i].paramNames[j] == "Color") {
          r = ledModes[i].paramValuesR[j];  // Use the stored r value
          g = ledModes[i].paramValuesG[j];  // Use the stored g value
          b = ledModes[i].paramValuesB[j];  // Use the stored b value
        } else if (ledModes[i].paramNames[j] == "SColor") {
          sr = ledModes[i].paramValuesR[j];
          sg = ledModes[i].paramValuesG[j];
          sb = ledModes[i].paramValuesB[j];
        } else if (ledModes[i].paramNames[j] == "Speed") {
          speedParam = ledModes[i].paramValuesOther[j].toInt();  // Convert to integer
        } else if (ledModes[i].paramNames[j] == "Frequency") {
          frequencyParam = ledModes[i].paramValuesOther[j].toInt();
        } else if (ledModes[i].paramNames[j] == "Division") {
          divisionParam = ledModes[i].paramValuesOther[j].toInt();
        }
      }

      // Call the corresponding function
      if (currentModeName == "Color" && previousModeName != currentModeName) {
        Serial.print("Primary Color RGB: ");
        Serial.print("Red: ");
        Serial.print(r);
        Serial.print(" Green: ");
        Serial.print(g);
        Serial.print(" Blue: ");
        Serial.println(b);
        colorMode(r, g, b);
        aOn = true;

        return;
      } else if (currentModeName == "Rainbow") {
        if (aOn == false) {
          pMillis = -1000;
          aOn = true;
        }
        rainbowCycle(20);
      } else if (currentModeName == "Alternating colors") {
        if (aOn == false) {
          aOn = true;
          altState = false;
          color = false;
          strip.fill(strip.Color(0, 0, 0), 0, LEDCOUNT);
          pMillis = -1000;
        }
        alternatingColors(r, g, b, sr, sg, sb, speedParam);
      } else if (currentModeName == "Fade") {
        if (aOn == false) {
          aOn = true;
          pMillis = -1000;
        }
        fade(speedParam);
      } else if (currentModeName == "Pulse") {
        if (aOn == false) {
          aOn = true;
          pMillis = -1000;
          pixChosen = 0;
          for (int i = 0; i < LEDCOUNT; i++) {
            ledStatus[i] = 0;
          }
        }
        pulse(r, g, b, sr, sg, sb, speedParam);
      } else if (currentModeName == "Breathe") {
        if (aOn == false) {
          aOn = true;
          brightness = stripBright;
          pMillis = -1000;
        }
        breathe(r, g, b, speedParam);
      } else if (currentModeName == "Paint") {
        if (aOn == false) {
          aOn = true;
          pMillis = -1000;
          for (uint16_t i = 0; i < LEDCOUNT; i++) {
            rStates[i] = 0;
            gStates[i] = 0;
            bStates[i] = 0;
          }
          strip.fill(strip.Color(0, 0, 0), 0, LEDCOUNT);
          strip.show();
        }
        paint(speedParam);
      } else if (currentModeName == "Strobe") {
        if (aOn == false) {
          aOn = true;
          pMillis = -1000;
        }
        strobe(r, g, b, speedParam);
      } else if (currentModeName == "Time") {
        if (aOn == false) {
          aOn = true;
          pMillis = -1000;
        }
        tClock(true);
      } else if (currentModeName == "Cylon") {
        if (aOn == false) {
          aOn = true;
          pMillis = -1000;
          strip.fill(strip.Color(0, 0, 0), 0, LEDCOUNT);
          strip.show();
          aStep = 0;
          memset(ledStatus, 0, sizeof(ledStatus));
          barDirection = false;
          setBarSize = 0;
        }
        cylon(r, g, b, sr, sg, sb, speedParam);
      } else if (currentModeName == "Phaser") {
        // Serial.println("Phaser");
        if (aOn == false) {
          aOn = true;
          pMillis = -1000;
          strip.fill(strip.Color(0, 0, 0), 0, LEDCOUNT);
          strip.show();
          aStep = 0;
          memset(ledStatus, 0, sizeof(ledStatus));
          barDirection = false;
          setBarSize = 0;
        }
        phaser(r, g, b, speedParam);
      } else if (currentModeName == "Alternating Phaser") {
        Serial.println("Alt Phaser");
        aPhaser(r, g, b, sr, sg, sb);
      } else if (currentModeName == "Weather") {
        Serial.println("Weather (Not Used)");
        // weather();
      } else if (currentModeName == "Police Lights") {
        if (aOn == false) {
          aOn = true;
          pMillis = -1000;
          strip.fill(strip.Color(0, 0, 0), 0, LEDCOUNT);
          strip.show();
          aStep = 0;
          altState = false;
        }
        pLights(speedParam);
      } else if (currentModeName == "Fire") {
        if (aOn == false) {
          aOn = true;
          pMillis = -1000;
          strip.fill(strip.Color(0, 0, 0), 0, LEDCOUNT);
          strip.show();
          aStep = 0;
        }
        memset(ledStatus, 0, sizeof(ledStatus));
        barDirection = false;
        setBarSize = 0;
        fire(speedParam);
      } else if (currentModeName == "Bouncing Ball") {
      } else if (currentModeName == "Twinkle") {
        Serial.println("Twinkle");
        if (aOn == false) {
          aOn = true;
          pMillis = -1000;
          strip.fill(strip.Color(0, 0, 0), 0, LEDCOUNT);
          strip.show();
        }
        memset(ledStatus, 0, sizeof(ledStatus));
        memset(setPopHang, 0, sizeof(setPopHang));
        memset(popHang, 0, sizeof(popHang));
        memset(rStates, 0, sizeof(rStates));
        memset(gStates, 0, sizeof(gStates));
        memset(bStates, 0, sizeof(bStates));
        twinkle(r, g, b, frequencyParam);
      } else if (currentModeName == "Sparkle") {
        if (aOn == false) {
          aOn = true;
          pMillis = -1000;
          strip.fill(strip.Color(0, 0, 0), 0, LEDCOUNT);
          strip.show();
          aStep = 0;
        }
        memset(ledStatus, 0, sizeof(ledStatus));
        memset(setPopHang, 0, sizeof(setPopHang));
        memset(popHang, 0, sizeof(popHang));
        memset(rStates, 0, sizeof(rStates));
        memset(gStates, 0, sizeof(gStates));
        memset(bStates, 0, sizeof(bStates));
        Serial.println(10);
        sparkle(r, g, b, frequencyParam);
      } else if (currentModeName == "Running Lights") {
        if (aOn == false) {
          aOn = true;
          pMillis = -1000;
          strip.fill(strip.Color(0, 0, 0), 0, LEDCOUNT);
          strip.show();
          aStep = 0;
        }
        memset(ledStatus, 0, sizeof(ledStatus));
        rLights(r, g, b, speedParam, divisionParam);
      } else if (currentModeName == "Theatre Chase") {
        if (aOn == false) {
          aOn = true;
          pMillis = -1000;
          strip.fill(strip.Color(0, 0, 0), 0, LEDCOUNT);
          strip.show();
          aStep = 1;
        }
        memset(ledStatus, 0, sizeof(ledStatus));
        barDirection = false;
        setBarSize = 0;
        tChase(r, g, b, speedParam);
      } else if (currentModeName == "Meteor") {
        // meteorMode(colorParam);
      } else if (currentModeName == "Pop") {
        if (aOn == false) {
          aOn = true;
          pMillis = -1000;
          for (uint16_t i = 0; i < LEDCOUNT; i++) {
            rStates[i] = 0;
            gStates[i] = 0;
            bStates[i] = 0;
          }
          strip.fill(strip.Color(0, 0, 0), 0, LEDCOUNT);
          strip.show();
        }
        pop(r, g, b, speedParam);
      } else {
      }
      return;  // Exit after matching the mode
    }
  }
}

void setBrightness(int brightness) {
  // Logic to set the brightness of the LED, e.g., using PWM
  buffStripBright = map(brightness, 0, 100, 0, 255);
  previousModeName = "";
}

void cccd_callback(uint16_t conn_handle, BLECharacteristic* chr, uint16_t cccd_value) {
  // Display the raw request packet
  Serial.print("CCCD Updated: ");
  Serial.println(cccd_value);
}

// Function to parse the RGB values from a string in the format "r,g,b"
void parseRGB(const String& rgbString, unsigned int& r, unsigned int& g, unsigned int& b) {
  int firstComma = rgbString.indexOf(',');
  int secondComma = rgbString.indexOf(',', firstComma + 1);

  r = rgbString.substring(0, firstComma).toInt();                // Extract and convert to int
  g = rgbString.substring(firstComma + 1, secondComma).toInt();  // Extract and convert to int
  b = rgbString.substring(secondComma + 1).toInt();              // Extract and convert to int
}

void startAdv(void) {
  Bluefruit.Advertising.clearData();

  Bluefruit.Advertising.addFlags(BLE_GAP_ADV_FLAGS_LE_ONLY_GENERAL_DISC_MODE);
  Bluefruit.Advertising.addTxPower();

  // Add the custom service UUID to the advertisement
  Bluefruit.Advertising.addService(customService);

  // // Add UART service UUID if needed
  // Bluefruit.Advertising.addService(bleuart);

  // Add the device name to the scan response
  Bluefruit.ScanResponse.addName();

  // Start advertising
  Bluefruit.Advertising.restartOnDisconnect(true);
  Bluefruit.Advertising.setInterval(32, 244);  // Advertising interval range in ms
  Bluefruit.Advertising.setFastTimeout(30);    // Fast mode timeout in seconds
  Bluefruit.Advertising.start(0);              // 0 = Don't stop advertising after a set time
}

void connect_callback(uint16_t conn_handle) {
  (void)conn_handle;
  connection_count++;
  Serial.println("Connected-ish");
  BLEConnection* connection = Bluefruit.Connection(conn_handle);
  char central_name[32] = { 0 };
  connection->getPeerName(central_name, sizeof(central_name));
  Serial.print("Connected to ");
  Serial.println(central_name);
  sendLEDModeList();

  // Check if the device is valid and the name is available
  if (connection && connection->connected()) {
    // Access device name safely
    // Provide arguments to getPeerName()
    connection->getPeerName(central_name, sizeof(central_name));
    const char* deviceName = central_name;
    Serial.print("Device name: ");
    Serial.println(deviceName);
    if (cConnected == false) {
      Serial.println("yesconnect");
      Serial.println('\n');
      inRamp = false;
      cConnected = true;
      ledState = true;
      if (timeoutlight == true) {
        timeoutlight = false;
        Serial.println("Timeout Light True");
        ledMode = 0;
        strip.fill(strip.Color(0, 0, 0), 0, LEDCOUNT);
        strip.show();
      }
    }
  } else {
    Serial.println("Device information not available yet.");
  }
  if (connection_count < MAX_PRPH_CONNECTION) {
    Serial.println("Keep advertising");
    Bluefruit.Advertising.start(0);
  }
}

void disconnect_callback(uint16_t conn_handle, uint8_t reason) {
  (void)conn_handle;
  (void)reason;
  Serial.println();
  Serial.print("Disconnected, reason = 0x");
  Serial.println(reason, HEX);
  cConnected = false;
  connection_count--;
  Bluefruit.Advertising.start(0);  // Restart advertising when disconnected
}

void RTCMillis() {
  count++;
  if (count >= 113) {
    count = 0;
    sBarPos++;
  }
}

void cResync() {
  DateTime now = rtc.now();
  minute = now.minute();
  if (minute != pMinute) {
    pMinute = minute;
    sBarPos = hourMap[0];
  }
  hour = now.hour() + DST;
  if (hour < 0 || hour > 23) {
    hour = 0;
  }
  second = now.second();
}

void timeout() {
  if (cConnected == false && timeoutlight == false) {
    Serial.println("Timeout Triggered");
    ledMode = 11;
    color1[0] = 255;
    color1[1] = 0;
    color1[2] = 0;
    for (uint16_t i = 0; i < LEDCOUNT; i++) {
      if (ledNum < beamSize) {
        if (ledON == false) {
          beamPos[i] = 0;
        } else {
          beamPos[i] = 1;
        }
        ledNum++;
      } else if (ledNum >= beamSize) {
        ledON = !ledON;
        ledNum = 0;
      }
    }
    ledState = true;
    aOn = true;
    timeoutlight = true;
    Serial.println("Timeout");
    Serial.println('\n');
  }
  if (cConnected == false && timeoutlight == true) {
    Serial.println("Timeout Triggered Again");
    timeoutMode(255, 0, 0, 5);
  }
}

void rampBright() {
  if (inRamp == true && rampStep < aMBright) {
    if ((cMillis - rMillis) >= 100) {
      stripBright = rampStep;
      rampStep++;
      rMillis = cMillis;
    }
  }
}

void colorMode(unsigned int r, unsigned int g, unsigned int b) {
  if (currentModeName != previousModeName) {
    // Scale each color by the brightness factor and ensure they stay within 0-255
    unsigned int br = constrain((r * stripBright) / 255, 0, 255);
    unsigned int bg = constrain((g * stripBright) / 255, 0, 255);
    unsigned int bb = constrain((b * stripBright) / 255, 0, 255);

    // Update LED strip color
    strip.fill(strip.Color(br, bg, bb), 0, LEDCOUNT);
    Serial.print("Brightness Red Val: ");
    Serial.print(br);
    Serial.print(" Brightness Green Val: ");
    Serial.print(bg);
    Serial.print(" Brightness Blue Val: ");
    Serial.println(bb);

    strip.show();
    previousModeName = currentModeName;
  }
}

// Fill the dots one after the other with a color
void colorWipe(uint32_t c, uint8_t freq) {
  uint16_t wait;
  wait = (1000 / freq) / 2;
  for (uint16_t i = 0; i < strip.numPixels(); i++) {
    strip.setPixelColor(i, c);
    strip.show();
    delay(wait);
  }
}

// Slightly different, this makes the rainbow equally distributed throughout
void rainbowCycle(unsigned long freq) {
  uint16_t wait;
  wait = (1000 / freq) / 2;
  uint16_t i;
  if ((cMillis - pMillis >= wait) && (aOn == true) && (ledState == true) && (freq > 0)) {
    pMillis = cMillis;
    if (aStep < 256) {
      for (i = 0; i < strip.numPixels(); i++) {
        strip.setPixelColor(i, Wheel(((i * 256 / strip.numPixels()) + aStep) & 255));
      }
      strip.show();
      aStep++;
    }
  }
  if (aStep >= 256) {
    aStep = 0;
  }
}

// Input a value 0 to 255 to get a color value.
// The colours are a transition r - g - b - back to r.
uint32_t Wheel(byte WheelPos) {
  WheelPos = 255 - WheelPos;
  if (WheelPos < 85) {
    return strip.Color(stripBright * (255 - WheelPos * 3) / 255, 0, stripBright * (WheelPos * 3) / 255);
  }
  if (WheelPos < 170) {
    WheelPos -= 85;
    return strip.Color(0, stripBright * (WheelPos * 3) / 255, stripBright * (255 - WheelPos * 3) / 255);
  }
  WheelPos -= 170;
  return strip.Color(stripBright * (WheelPos * 3) / 255, stripBright * (255 - WheelPos * 3) / 255, 0);
}

void fade(unsigned long freq) {
  uint16_t wait;
  wait = (1000 / freq) / 2;
  if ((cMillis - pMillis >= wait) && (aOn == true) && (ledState == true) && (freq > 0)) {
    pMillis = cMillis;
    if (aStep < 360) {

      fh = aStep;
      rgb out_rgb;
      out_rgb.r = 0;
      out_rgb.g = 0;
      out_rgb.b = 0;
      hsv in_hsv;
      in_hsv.h = fh;
      in_hsv.s = 100;
      in_hsv.v = 100;
      out_rgb = HSVConverter.HSVtoRGB(in_hsv, out_rgb);
      fr = out_rgb.r;
      fg = out_rgb.g;
      fb = out_rgb.b;
      strip.fill(strip.Color(stripBright * (fr / 255), stripBright * (fg / 255), stripBright * (fb / 255)), 0, LEDCOUNT);
    }
    strip.show();
    aStep++;
  }
  if (aStep >= 360) {
    aStep = 0;
  }
}

void breathe(unsigned int r, unsigned int g, unsigned int b, unsigned long freq) {
  long cwait;
  cwait = ((1000 / freq) / 2) * (255 / stripBright);
  if (brightness < stripBright && prevBrightness != brightness && (cMillis - pMillis >= cwait) && (aOn == true) && (ledState == true) && (freq > 0)) {
    prevBrightness = brightness;
    strip.fill(strip.Color(constrain((brightness * r) / 255, 0, 255),
                           constrain((brightness * g) / 255, 0, 255),
                           constrain((brightness * b) / 255, 0, 255)),
               0, LEDCOUNT);

    strip.show();
    if (goingUp == true) {
      brightness++;
    } else if (goingUp == false) {
      brightness--;
    }
    if (brightness >= stripBright) {
      goingUp = false;
    }
    if (brightness <= 0) {
      goingUp = true;
    }
    pMillis = cMillis;
  }
  if (brightness >= stripBright && (cMillis - pMillis >= (1000 / (2 * (brightness - (stripBright)))) && (aOn == true) && (ledState == true)) && (freq > 0)) {
    strip.fill(strip.Color(constrain((brightness * r) / 255, 0, 255),
                           constrain((brightness * g) / 255, 0, 255),
                           constrain((brightness * b) / 255, 0, 255)),
               0, LEDCOUNT);
    strip.show();
    if (brightness == stripBright) {
      prevBrightness = brightness;
    }
    goingUp = false;
    if (goingUp == false) {
      brightness--;
    }
    pMillis = cMillis;
  }
}

void strobe(unsigned int r, unsigned int g, unsigned int b, unsigned long freq) {
  uint16_t wait;
  wait = (1000 / freq) / 2;
  if ((cMillis - pMillis >= wait) && (aOn == true) && (ledState == true) && (freq > 0)) {
    if (strobeOn == false) {
      strip.fill(strip.Color(constrain((stripBright * r) / 255, 0, 255),
                             constrain((stripBright * g) / 255, 0, 255),
                             constrain((stripBright * b) / 255, 0, 255)),
                 0, LEDCOUNT);
      strobeOn = true;
    } else {
      strip.fill(strip.Color(0, 0, 0), 0, LEDCOUNT);
      strobeOn = false;
    }
    strip.show();
    pMillis = cMillis;
  }
}

void alternatingColors(unsigned int r, unsigned int g, unsigned int b, unsigned int sr, unsigned int sg, unsigned int sb, unsigned long freq) {
  uint16_t scaledFreq;
  scaledFreq = map(freq, 0, 100, 0, 60);
  uint16_t wait;
  wait = (1000 / scaledFreq) / 2;
  if ((cMillis - pMillis >= wait) && (aOn == true) && (ledState == true) && (freq > 0)) {
    if ((ledDiv % 2) == 0) {
      if (altState == true) {
        for (int i = 0; i <= (ledDiv - 1); i++) {
          if (color == false) {
            strip.fill(strip.Color(constrain((stripBright * r) / 255, 0, 255),
                                   constrain((stripBright * g) / 255, 0, 255),
                                   constrain((stripBright * b) / 255, 0, 255)),
                       (((LEDCOUNT / ledDiv) + 1) * i), (LEDCOUNT / ledDiv) * (i + 1));
            color = true;
          } else {
            strip.fill(strip.Color(constrain((stripBright * sr) / 255, 0, 255),
                                   constrain((stripBright * sg) / 255, 0, 255),
                                   constrain((stripBright * sb) / 255, 0, 255)),
                       (((LEDCOUNT / ledDiv) + 1) * i), (LEDCOUNT / ledDiv) * (i + 1));
            color = false;
          }
        }
        strip.show();
        altState = false;
      } else {
        for (int j = 0; j <= (ledDiv - 1); j++) {
          if (color == false) {
            strip.fill(strip.Color(constrain((stripBright * sr) / 255, 0, 255),
                                   constrain((stripBright * sg) / 255, 0, 255),
                                   constrain((stripBright * sb) / 255, 0, 255)),
                       (((LEDCOUNT / ledDiv) + 1) * j), (LEDCOUNT / ledDiv) * (j + 1));
            color = true;
          } else {
            strip.fill(strip.Color(constrain((stripBright * r) / 255, 0, 255),
                                   constrain((stripBright * g) / 255, 0, 255),
                                   constrain((stripBright * b) / 255, 0, 255)),
                       (((LEDCOUNT / ledDiv) + 1) * j), (LEDCOUNT / ledDiv) * (j + 1));
            color = false;
          }
        }
        strip.show();
        altState = true;
      }
    } else if ((ledDiv % 2)) {
      if (altState == true) {
        for (int i = 0; i <= (ledDiv - 1); i++) {
          if (color == false) {
            strip.fill(strip.Color(constrain((stripBright * r) / 255, 0, 255),
                                   constrain((stripBright * g) / 255, 0, 255),
                                   constrain((stripBright * b) / 255, 0, 255)),
                       (((LEDCOUNT / ledDiv) + 1) * i), (LEDCOUNT / ledDiv) * (i + 1));
            color = true;
          } else {
            strip.fill(strip.Color(constrain((stripBright * sr) / 255, 0, 255),
                                   constrain((stripBright * sg) / 255, 0, 255),
                                   constrain((stripBright * sb) / 255, 0, 255)),
                       (((LEDCOUNT / ledDiv) + 1) * i), (LEDCOUNT / ledDiv) * (i + 1));

            color = false;
          }
        }
        strip.show();
        altState = true;
      } else {
        for (int j = 0; j <= (ledDiv - 1); j++) {
          if (color == false) {
            strip.fill(strip.Color(constrain((stripBright * sr) / 255, 0, 255),
                                   constrain((stripBright * sg) / 255, 0, 255),
                                   constrain((stripBright * sb) / 255, 0, 255)),
                       (((LEDCOUNT / ledDiv) + 1) * j), (LEDCOUNT / ledDiv) * (j + 1));
            color = true;
          } else {
            strip.fill(strip.Color(constrain((stripBright * r) / 255, 0, 255),
                                   constrain((stripBright * g) / 255, 0, 255),
                                   constrain((stripBright * b) / 255, 0, 255)),
                       (((LEDCOUNT / ledDiv) + 1) * j), (LEDCOUNT / ledDiv) * (j + 1));
            color = false;
          }
        }
        strip.show();
        altState = false;
      }
    }

    pMillis = cMillis;
  }
}

void pulse(unsigned int r, unsigned int g, unsigned int b, unsigned int sr, unsigned int sg, unsigned int sb, unsigned long freq) {
  uint16_t wait;
  wait = (1000 / freq);
  if ((cMillis - pMillis >= wait) && (aOn == true) && (ledState == true) && (freq > 0)) {
    while (pixChosen < 5) {
      if (pixChosen == 1) {

        int dir = (random(0, 1000) / 2);
        if ((dir % 2) == 0) {
          pulseBar1[0] = 0;
        } else {
          pulseBar1[0] = 1;
        }
        pulseBar1[1] = random(2, 10);
        pulseBar1[2] = (stripBright * (r / 255));
        pulseBar1[3] = (stripBright * (g / 255));
        pulseBar1[4] = (stripBright * (b / 255));
        pulseBar1[5] = 72;
        pulseBar1[6] = random(0, 8);
        pulseBar1[7] = 0;
      }
      if (pixChosen == 2) {
        pulseBar2[0] = random(0, 1);
        pulseBar2[1] = random(1, 6);
        pulseBar2[2] = (stripBright * (r / 255));
        pulseBar2[3] = (stripBright * (g / 255));
        pulseBar2[4] = (stripBright * (b / 255));
        pulseBar2[5] = 72;
        pulseBar2[6] = random(0, 8);
        pulseBar2[7] = 0;
      }
      if (pixChosen == 3) {
        pulseBar3[0] = random(0, 1);
        pulseBar3[1] = random(1, 6);
        pulseBar3[2] = (stripBright * (r / 255));
        pulseBar3[3] = (stripBright * (g / 255));
        pulseBar3[4] = (stripBright * (b / 255));
        pulseBar3[5] = 72;
        pulseBar3[6] = random(0, 8);
        pulseBar3[7] = 0;
      }
      if (pixChosen == 4) {
        pulseBar4[0] = random(0, 1);
        pulseBar4[1] = random(1, 6);
        pulseBar4[2] = (stripBright * (r / 255));
        pulseBar4[3] = (stripBright * (g / 255));
        pulseBar4[4] = (stripBright * (b / 255));
        pulseBar4[5] = 72;
        pulseBar4[6] = random(0, 8);
        pulseBar4[7] = 0;
      }
      if (pixChosen == 5) {
        pulseBar5[0] = random(0, 1);
        pulseBar5[1] = random(1, 6);
        pulseBar5[2] = (stripBright * (r / 255));
        pulseBar5[3] = (stripBright * (g / 255));
        pulseBar5[4] = (stripBright * (b / 255));
        pulseBar5[5] = 72;
        pulseBar5[6] = random(0, 8);
        pulseBar5[7] = 0;
      } else {
      }
      pixChosen++;
    }
    if (pixChosen >= 5) {
      if (pulseBar1[7] == pulseBar1[6]) {
        if (pulseBar1[5] == 0 || pulseBar1[5] == LEDCOUNT) {
          if (pulseBar1[0] == 0) {
            pulseBar1[5] = pulseBar1[5] + pulseBar1[1] + 1;
            pulseBar1[0] = 1;
          } else {
            pulseBar1[5] = pulseBar1[5] - pulseBar1[1] - 1;
            pulseBar1[0] = 0;
          }
        }

        ledStatus[int(pulseBar1[5])] = 1;
        if (pulseBar1[0] == 1) {
          ledStatus[int(pulseBar1[5]) - int(pulseBar1[1])] = 0;
          pulseBar1[5]++;
        } else {
          ledStatus[int(pulseBar1[5]) + int(pulseBar1[1])] = 0;
          pulseBar1[5]--;
        }
        pulseBar1[7] = 0;
      } else {
        pulseBar1[7]++;
      }
      if (pulseBar2[7] == pulseBar2[6]) {
        if (pulseBar2[5] == 0 || pulseBar2[5] == LEDCOUNT) {
          if (pulseBar2[0] == 0) {
            pulseBar2[5] = pulseBar2[5] + pulseBar2[1] + 1;
            pulseBar2[0] = 1;
          } else {
            pulseBar2[5] = pulseBar2[5] - pulseBar2[1] - 1;
            pulseBar2[0] = 0;
          }
        }

        ledStatus[int(pulseBar2[5])] = 1;
        if (pulseBar2[0] == 1) {
          ledStatus[int(pulseBar2[5]) - int(pulseBar2[1])] = 0;
          pulseBar2[5]++;
        } else {
          ledStatus[int(pulseBar2[5]) + int(pulseBar2[1])] = 0;
          pulseBar2[5]--;
        }
        pulseBar2[7] = 0;
      } else {
        pulseBar2[7]++;
      }
      if (pulseBar3[7] == pulseBar3[6]) {
        if (pulseBar3[5] == 0 || pulseBar3[5] == LEDCOUNT) {
          if (pulseBar3[0] == 0) {
            pulseBar3[5] = pulseBar3[5] + pulseBar3[1] + 1;
            pulseBar3[0] = 1;
          } else {
            pulseBar3[5] = pulseBar3[5] - pulseBar3[1] - 1;
            pulseBar3[0] = 0;
          }
        }

        ledStatus[int(pulseBar3[5])] = 1;
        if (pulseBar3[0] == 1) {
          ledStatus[int(pulseBar3[5]) - int(pulseBar3[1])] = 0;
          pulseBar3[5]++;
        } else {
          ledStatus[int(pulseBar3[5]) + int(pulseBar3[1])] = 0;
          pulseBar3[5]--;
        }
        pulseBar3[7] = 0;
      } else {
        pulseBar3[7]++;
      }
      if (pulseBar4[7] == pulseBar4[6]) {
        if (pulseBar4[5] == 0 || pulseBar4[5] == LEDCOUNT) {
          if (pulseBar4[0] == 0) {
            pulseBar4[5] = pulseBar4[5] + pulseBar4[1] + 1;
            pulseBar4[0] = 1;
          } else {
            pulseBar4[5] = pulseBar4[5] - pulseBar4[1] - 1;
            pulseBar4[0] = 0;
          }
        }

        ledStatus[int(pulseBar4[5])] = 1;
        if (pulseBar4[0] == 1) {
          ledStatus[int(pulseBar4[5]) - int(pulseBar4[1])] = 0;
          pulseBar4[5]++;
        } else {
          ledStatus[int(pulseBar4[5]) + int(pulseBar4[1])] = 0;
          pulseBar4[5]--;
        }
        pulseBar4[7] = 0;
      } else {
        pulseBar4[7]++;
      }
      if (pulseBar5[7] == pulseBar5[6]) {
        if (pulseBar5[5] == 0 || pulseBar5[5] == LEDCOUNT) {
          if (pulseBar5[0] == 0) {
            pulseBar5[5] = pulseBar5[5] + pulseBar5[1] + 1;
            pulseBar5[0] = 1;
          } else {
            pulseBar5[5] = pulseBar5[5] - pulseBar5[1] - 1;
            pulseBar5[0] = 0;
          }
        }

        ledStatus[int(pulseBar5[5])] = 1;
        if (pulseBar5[0] == 1) {
          ledStatus[int(pulseBar5[5]) - int(pulseBar5[1])] = 0;
          pulseBar5[5]++;
        } else {
          ledStatus[int(pulseBar5[5]) + int(pulseBar5[1])] = 0;
          pulseBar5[5]--;
        }
        pulseBar5[7] = 0;
      } else {
        pulseBar5[7]++;
      }
    }
    for (uint16_t i = 0; i < LEDCOUNT; i++) {
      if (ledStatus[i] == 1) {
        strip.setPixelColor(i, (stripBright * (r / 255)), (stripBright * (g / 255)), (stripBright * (b / 255)) / 10);
      } else {
        strip.setPixelColor(i, ((stripBright * (sr / 255)) / 10), ((stripBright * (sg / 255)) / 10), ((stripBright * (sb / 255)) / 10));
      }
    }
    strip.show();
    pMillis = cMillis;
  }
}

void pop(unsigned int r, unsigned int g, unsigned int b, unsigned long speed) {
  unsigned long freq = map(speed, 1, 100, 300, 1);
  if ((cMillis - pMillis) >= 10 && (aOn == true) && (ledState == true)) {
    if (random(freq) == 1) {
      uint16_t pix = random(LEDCOUNT);
      if (rStates[pix] < 1 && gStates[pix] < 1 && bStates[pix] < 1 && (speed > 0)) {
        rStates[pix] = (constrain((stripBright * r) / 255, 0, 255));
        gStates[pix] = (constrain((stripBright * g) / 255, 0, 255));
        bStates[pix] = (constrain((stripBright * b) / 255, 0, 255));
      }
    }
    for (uint16_t i = 0; i < LEDCOUNT; i++) {
      if (rStates[i] > 1 || gStates[i] > 1 || bStates[i] > 1) {
        strip.setPixelColor(i, rStates[i], gStates[i], bStates[i]);

        if (popHang[i] >= 200) {
          if (rStates[i] > 1) {
            rStates[i] = rStates[i] * 0.99;
          } else {
            rStates[i] = 0;
          }

          if (gStates[i] > 1) {
            gStates[i] = gStates[i] * 0.99;
          } else {
            gStates[i] = 0;
          }

          if (bStates[i] > 1) {
            bStates[i] = bStates[i] * 0.99;
          } else {
            bStates[i] = 0;
          }
        } else if (popHang[i] < 200) {
          popHang[i]++;
        }
      } else {
        strip.setPixelColor(i, ((stripBright * (r / 255)) / 10), ((stripBright * (g / 255)) / 10), ((stripBright * (b / 255)) / 10));
      }
    }
    strip.show();
    pMillis = cMillis;
  }
}

void paint(unsigned long freq) {
  uint16_t wait = (1000 / freq) / 2;
  uint16_t pix;
  if (((cMillis - pMillis) >= wait) && (aOn == true) && (ledState == true) && (freq > 0)) {
    if (random(75) == 1) {
      pix = random(LEDCOUNT);
      if ((rStates[pix] != nextrgb[0]) || (gStates[pix] != nextrgb[1]) || (bStates[pix] != nextrgb[2])) {
        rStates[pix] = nextrgb[0];
        gStates[pix] = nextrgb[1];
        bStates[pix] = nextrgb[2];
        strip.setPixelColor(pix, rStates[pix], gStates[pix], bStates[pix]);
        strip.show();
        Serial.println("Chosen");
        pixChosen++;
        Serial.print("Pixels Left: ");
        Serial.println(LEDCOUNT - pixChosen);
      }
    }
    if (pixChosen >= (LEDCOUNT - 50)) {
      Serial.println("Already Set");

      uint16_t colorOption = random(7);
      while (colorOption == pColorOption) {
        colorOption = random(7);
      }
      pColorOption = colorOption;
      for (uint16_t i = 0; i < LEDCOUNT; i++) {
        rStates[i] = 0;
        gStates[i] = 0;
        bStates[i] = 0;
      }
      Serial.print("Chosen Option: ");
      Serial.println(colorOption);
      pixChosen = 0;
      strip.fill(strip.Color(nextrgb[0], nextrgb[1], nextrgb[2]), 0, LEDCOUNT);
      strip.show();

      if (colorOption == 0) {
        nextrgb[0] = 255;
        nextrgb[1] = 0;
        nextrgb[2] = 0;
      }
      if (colorOption == 1) {
        nextrgb[0] = 255;
        nextrgb[1] = 165;
        nextrgb[2] = 0;
      }
      if (colorOption == 2) {
        nextrgb[0] = 255;
        nextrgb[1] = 255;
        nextrgb[2] = 0;
      }
      if (colorOption == 3) {
        nextrgb[0] = 0;
        nextrgb[1] = 255;
        nextrgb[2] = 0;
      }
      if (colorOption == 4) {
        nextrgb[0] = 0;
        nextrgb[1] = 0;
        nextrgb[2] = 255;
      }
      if (colorOption == 5) {
        nextrgb[0] = 255;
        nextrgb[1] = 0;
        nextrgb[2] = 255;
      }
      if (colorOption == 6) {
        nextrgb[0] = 255;
        nextrgb[1] = 102;
        nextrgb[2] = 178;
      }
      if (colorOption == 7) {
        nextrgb[0] = 102;
        nextrgb[1] = 255;
        nextrgb[2] = 255;
      } else {
      }
    }
    pMillis = cMillis;
  }
}
void cylon(unsigned int r, unsigned int g, unsigned int b, unsigned int sr, unsigned int sg, unsigned int sb, unsigned long freq) {
  uint16_t mappedFreq = map(freq, 0, 100, 0, 1000);
  uint16_t wait = (1000 / mappedFreq);
  if ((barDirection == false) && (ledStatus[aStep] == 0) && (setBarSize <= barSize) && ((cMillis - pMillis) >= (wait)) && (aOn == true) && (ledState == true) && (freq > 0)) {
    strip.setPixelColor(aStep, strip.Color(constrain((stripBright * r) / 255, 0, 255), constrain((stripBright * g) / 255, 0, 255), constrain((stripBright * b) / 255, 0, 255)));
    strip.show();
    ledStatus[aStep] = 1;
    setBarSize++;
    aStep++;
    pMillis = cMillis;
  }
  if ((barDirection == false) && (ledStatus[aStep] == 0) && (setBarSize >= barSize) && (aStep <= LEDCOUNT) && ((cMillis - pMillis) >= (wait)) && (aOn == true) && (ledState == true) && (freq > 0)) {
    strip.setPixelColor(aStep, strip.Color(constrain((stripBright * r) / 255, 0, 255), constrain((stripBright * g) / 255, 0, 255), constrain((stripBright * b) / 255, 0, 255)));
    strip.setPixelColor((aStep - barSize - 1), strip.Color(0, 0, 0));
    ledStatus[aStep - barSize - 1] = 0;
    strip.show();
    ledStatus[aStep] = 1;
    if (aStep == LEDCOUNT) {
      barDirection = true;
      aStep = (aStep - barSize - 1);
    } else {
      aStep++;
    }
    pMillis = cMillis;
  }
  if ((barDirection == true) && (ledStatus[aStep] == 0) && (ledStatus[aStep + 1] == 1) && (setBarSize >= barSize) && (aStep >= 0) && ((cMillis - pMillis) >= (wait)) && (aOn == true) && (ledState == true) && (freq > 0)) {
    strip.setPixelColor(aStep, strip.Color(constrain((stripBright * sr) / 255, 0, 255), constrain((stripBright * sg) / 255, 0, 255), constrain((stripBright * sb) / 255, 0, 255)));
    strip.setPixelColor((aStep + barSize + 1), strip.Color(0, 0, 0));
    ledStatus[aStep + barSize + 1] = 0;
    strip.show();
    ledStatus[aStep] = 1;
    if (aStep == 0) {
      barDirection = false;
      aStep = (aStep + barSize + 1);
    } else {
      aStep--;
    }
    pMillis = cMillis;
  }
}

void phaser(unsigned int r, unsigned int g, unsigned int b, unsigned long freq) {
  uint16_t wait = (1000 / freq);
  if ((barDirection == false) && (ledStatus[aStep] == 0) && (setBarSize <= barSize) && ((cMillis - pMillis) >= (wait)) && (aOn == true) && (ledState == true) && (freq > 0)) {
    strip.setPixelColor(aStep, strip.Color(constrain((stripBright * r) / 255, 0, 255), constrain((stripBright * g) / 255, 0, 255), constrain((stripBright * b) / 255, 0, 255)));
    strip.show();
    ledStatus[aStep] = 1;
    setBarSize++;
    aStep++;
    pMillis = cMillis;
  }
  if ((barDirection == false) && (ledStatus[aStep] == 0) && (setBarSize >= barSize) && (aStep <= LEDCOUNT) && ((cMillis - pMillis) >= (wait)) && (aOn == true) && (ledState == true) && (freq > 0)) {
    strip.setPixelColor(aStep, strip.Color(constrain((stripBright * r) / 255, 0, 255), constrain((stripBright * g) / 255, 0, 255), constrain((stripBright * b) / 255, 0, 255)));
    strip.setPixelColor((aStep - barSize - 1), strip.Color(0, 0, 0));
    ledStatus[aStep - barSize - 1] = 0;
    strip.show();
    ledStatus[aStep] = 1;
    if (aStep == LEDCOUNT) {
      barDirection = true;
      aStep = (aStep - barSize);
    } else {
      aStep++;
    }
    pMillis = cMillis;
  }
  if ((barDirection == true) && (ledStatus[aStep] == 1) && (aStep <= LEDCOUNT) && ((cMillis - pMillis) >= (wait)) && (aOn == true) && (ledState == true) && (freq > 0)) {
    strip.setPixelColor(aStep, strip.Color(0, 0, 0));
    strip.show();
    setBarSize--;
    ledStatus[aStep] = 0;
    if (aStep == LEDCOUNT) {
      barDirection = false;
      aStep = 0;
    } else {
      aStep++;
    }
    pMillis = cMillis;
  }
}

void aPhaser(unsigned int r, unsigned int g, unsigned int b, unsigned int sr, unsigned int sg, unsigned int sb) {
  uint16_t wait = 1000;
  if ((barDirection == false) && (ledStatus[aStep] == 0) && (setBarSize <= barSize) && ((cMillis - pMillis) >= (wait)) && (aOn == true) && (ledState == true) && (phaserNum == 0)) {
    strip.setPixelColor(aStep, strip.Color(constrain((stripBright * r) / 255, 0, 255), constrain((stripBright * g) / 255, 0, 255), constrain((stripBright * b) / 255, 0, 255)));
    strip.show();
    ledStatus[aStep] = 1;
    setBarSize++;
    aStep++;
    pMillis = cMillis;
  }
  if ((barDirection == false) && (ledStatus[aStep] == 0) && (setBarSize >= barSize) && (aStep <= LEDCOUNT) && ((cMillis - pMillis) >= (wait)) && (aOn == true) && (ledState == true) && (phaserNum == 0)) {
    strip.setPixelColor(aStep, strip.Color(constrain((stripBright * r) / 255, 0, 255), constrain((stripBright * g) / 255, 0, 255), constrain((stripBright * b) / 255, 0, 255)));
    strip.setPixelColor((aStep - barSize - 1), strip.Color(0, 0, 0));
    ledStatus[aStep - barSize - 1] = 0;
    strip.show();
    ledStatus[aStep] = 1;
    if (aStep == LEDCOUNT) {
      barDirection = true;
      aStep = (aStep - barSize);
    } else {
      aStep++;
    }
    pMillis = cMillis;
  }
  if ((barDirection == true) && (ledStatus[aStep] == 1) && (aStep <= LEDCOUNT) && ((cMillis - pMillis) >= (wait)) && (aOn == true) && (ledState == true) && (phaserNum == 0)) {
    strip.setPixelColor(aStep, strip.Color(0, 0, 0));
    strip.show();
    setBarSize--;
    ledStatus[aStep] = 0;
    if (aStep == LEDCOUNT) {
      phaserNum = 1;
    } else {
      aStep++;
    }
    pMillis = cMillis;
  }
  if ((barDirection == true) && (ledStatus[aStep] == 0) && (setBarSize <= barSize) && ((cMillis - pMillis) >= (wait)) && (aOn == true) && (ledState == true) && (phaserNum == 1)) {
    strip.setPixelColor(aStep, strip.Color(constrain((stripBright * sr) / 255, 0, 255), constrain((stripBright * sg) / 255, 0, 255), constrain((stripBright * sb) / 255, 0, 255)));
    strip.show();
    ledStatus[aStep] = 1;
    setBarSize++;
    aStep--;
    pMillis = cMillis;
  }
  if ((barDirection == true) && (ledStatus[aStep] == 0) && (setBarSize >= barSize) && (aStep >= 0) && ((cMillis - pMillis) >= (wait)) && (aOn == true) && (ledState == true) && (phaserNum == 1)) {
    strip.setPixelColor(aStep, strip.Color(constrain((stripBright * sr) / 255, 0, 255), constrain((stripBright * sg) / 255, 0, 255), constrain((stripBright * sb) / 255, 0, 255)));
    strip.setPixelColor((aStep + barSize + 1), strip.Color(0, 0, 0));
    ledStatus[aStep + barSize + 1] = 0;
    strip.show();
    ledStatus[aStep] = 1;
    if (aStep == 0) {
      barDirection = false;
      aStep = (aStep + barSize);
    } else {
      aStep--;
    }
    pMillis = cMillis;
  }
  if ((barDirection == false) && (ledStatus[aStep] == 1) && (aStep >= 0) && ((cMillis - pMillis) >= (wait)) && (aOn == true) && (ledState == true) && (phaserNum == 1)) {
    strip.setPixelColor(aStep, strip.Color(0, 0, 0));
    strip.show();
    setBarSize--;
    ledStatus[aStep] = 0;
    if (aStep == 0) {
      phaserNum = 0;
    } else {
      aStep--;
    }
    pMillis = cMillis;
  }
}

void pLights(unsigned long freq) {
  uint16_t wait;
  wait = (1000 / freq) / 2;
  if ((cMillis - pMillis >= wait) && (aOn == true) && (ledState == true) && (altState == false) && (freq <= 10)) {
    strip.fill(strip.Color(255, 0, 0), (LEDCOUNT / 2), LEDCOUNT);
    strip.fill(strip.Color(0, 0, 0), 0, (LEDCOUNT / 2));
    strip.show();
    altState = true;
    pMillis = cMillis;
  }
  if ((cMillis - pMillis >= wait) && (aOn == true) && (ledState == true) && (altState == true) && (freq <= 10)) {
    strip.fill(strip.Color(0, 0, 255), 0, (LEDCOUNT / 2));
    strip.fill(strip.Color(0, 0, 0), (LEDCOUNT / 2), LEDCOUNT);
    strip.show();
    altState = false;
    pMillis = cMillis;
  }
  if ((cMillis - pMillis >= wait) && (aOn == true) && (ledState == true) && (altState == false) && (freq > 10)) {
    if ((aStep == 0) || (aStep == 2) || (aStep == 4) || (aStep == 6) || (aStep == 8)) {
      strip.fill(strip.Color(255, 0, 0), (LEDCOUNT / 2), LEDCOUNT);
      strip.show();
    }

    if ((aStep == 1) || (aStep == 3) || (aStep == 5) || (aStep == 7) || (aStep == 9)) {
      strip.fill(strip.Color(0, 0, 0), (LEDCOUNT / 2), LEDCOUNT);
      strip.show();
    }
    if (aStep >= 9) {
      altState = true;
    } else {
      aStep++;
    }
    pMillis = cMillis;
  }
  if ((cMillis - pMillis >= wait) && (aOn == true) && (ledState == true) && (altState == true) && (freq > 10)) {
    if ((aStep == 0) || (aStep == 2) || (aStep == 4) || (aStep == 6) || (aStep == 8)) {
      strip.fill(strip.Color(0, 0, 255), 0, (LEDCOUNT / 2));
      strip.show();
    }
    if ((aStep == 1) || (aStep == 3) || (aStep == 5) || (aStep == 7) || (aStep == 9)) {
      strip.fill(strip.Color(0, 0, 0), 0, (LEDCOUNT / 2));
      strip.show();
    }
    if (aStep <= 0) {
      altState = false;
    } else {
      aStep--;
    }
    pMillis = cMillis;
  }
}

void tChase(unsigned int r, unsigned int g, unsigned int b, unsigned long freq) {
  uint16_t wait;
  int dotSpacing = 12;
  wait = (1000 / freq);
  if ((cMillis - pMillis >= wait) && (aOn == true) && (ledState == true) && (freq > 0)) {
    if (aStep < dotSpacing) {
      aStep++;
    } else if (aStep >= dotSpacing) {
      aStep = 1;
    }
    for (int i = 0; i <= LEDCOUNT; i++) {
      if ((i + aStep) % dotSpacing == 0) {
        strip.setPixelColor(i, strip.Color(constrain((stripBright * r) / 255, 0, 255), constrain((stripBright * g) / 255, 0, 255), constrain((stripBright * b) / 255, 0, 255)));
      } else {
        strip.setPixelColor(i, strip.Color(0, 0, 0));
      }
    }
    strip.show();
    pMillis = cMillis;
  }
}

void rLights(unsigned int r, unsigned int g, unsigned int b, unsigned long freq, unsigned long div) {
  uint16_t wait;
  int barSize = 3;
  int spacingSize = 12;
  int index = 0;
  wait = (1000 / freq);
  int offsetNum = 0;
  if ((cMillis - pMillis >= wait) && (aOn == true) && (ledState == true) && (freq > 0)) {
    if (aStep < spacingSize) {
      aStep++;
    } else if (aStep >= spacingSize) {
      aStep = 0;
    }
    index = 0;
    strip.fill(strip.Color(0, 0, 0), 0, LEDCOUNT);
    for (int i = 0; i <= LEDCOUNT; i++) {
      if ((index <= (barSize - 1)) && (index <= spacingSize)) {
        strip.setPixelColor(i + aStep, strip.Color(constrain((stripBright * r) / 255, 0, 255), constrain((stripBright * g) / 255, 0, 255), constrain((stripBright * b) / 255, 0, 255)));
        index++;
      } else if ((index >= barSize) && (index <= (spacingSize + 1))) {
        strip.setPixelColor(i + aStep, strip.Color(0, 0, 0));
        index++;
      }
      if ((index > barSize) && (index > spacingSize)) {
        index = 0;
      }
    }
    strip.show();
    pMillis = cMillis;
  }
}

void fire(unsigned long freq) {
  uint16_t wait;
  int barSize = 3;
  int spacingSize = 12;
  int index = 0;
  wait = (1000 / freq);
  int offsetNum = 0;
  if ((cMillis - pMillis >= random(150, 275)) && (aOn == true) && (ledState == true)) {
    for (int i = 0; i < LEDCOUNT; i++) {
      int flicker = random(0, 150);
      int r1 = 255 - flicker;
      int g1 = 150 - flicker;
      int b1 = 40 - flicker;
      if (g1 < 0) g1 = 0;
      if (r1 < 0) r1 = 0;
      if (b1 < 0) b1 = 0;
      strip.setPixelColor(i, r1, g1, b1);
    }
    strip.show();
    pMillis = cMillis;
  }
}

void tClock(bool tUpdate) {
  const int hBar = 20;
  const int mBar = 6;
  const int sBar = 12;
  const int sBarTrail = 3;


  if (!tUpdate || !aOn || !ledState) {
    return;
  }

  if (stripBright >= 30) {
    handBright = (255 / 255) * stripBright;
    mHandBright = (255 / 255) * stripBright / 4;
    backBright = (255 / 255) * stripBright / 4;
  } else {
    handBright = 60;
    mHandBright = 15;
    backBright = (255 / 255) * stripBright / 4;
  }

  int elapsedsec = (cMillis - tMillis) / 1000;
  int cHour;
  if (hour >= 12) {
    cHour = hour - 12;
  } else {
    cHour = hour;
  }
  if (sBarPos - sBar / 2 >= LEDCOUNT) {
    sBarPos = sBar / 2;
  }
  for (int i = 0; i < LEDCOUNT; i++) {
    if ((i <= hourMap[cHour] + (hBar / 2) && i >= hourMap[cHour] - (hBar / 2)) && (i > minuteMap[minute] + (mBar / 2) || i < minuteMap[minute] - (mBar / 2))) {
      strip.setPixelColor(i, strip.Color(handBright, 0, 0));
    } else if ((i > hourMap[cHour] + (hBar / 2) || i < hourMap[cHour] - (hBar / 2)) && (i <= minuteMap[minute] + (mBar / 2) && i >= minuteMap[minute] - (mBar / 2))) {
      strip.setPixelColor(i, strip.Color(handBright, handBright, 0));
    } else if ((i <= hourMap[cHour] + (hBar / 2) && i >= hourMap[cHour] - (hBar / 2)) && (i <= minuteMap[minute] + (mBar / 2) && i >= minuteMap[minute] - (mBar / 2))) {
      strip.setPixelColor(i, strip.Color(handBright, handBright, handBright));
    } else {
      strip.setPixelColor(i, strip.Color(backBright, 0, backBright));
    }
  }
  for (int j = -sBar / 2; j < sBar / 2; j++) {
    if (j < -sBar / 2 + sBarTrail) {
      strip.setPixelColor(j + sBarPos, strip.Color(0, (abs((255 / sBarTrail) / j * 16) / 255) * stripBright, 0));
    } else if (j + sBarPos > LEDCOUNT) {
      strip.setPixelColor(j + sBarPos - LEDCOUNT, strip.Color(0, handBright, 0));
    } else if (j + sBarPos < 0) {
      strip.setPixelColor(LEDCOUNT + j + 1, strip.Color(0, handBright, 0));
    } else {
      strip.setPixelColor(j + sBarPos, strip.Color(0, handBright, 0));
    }
  }
  for (int l = 0; l < sizeof(minuteMap) / sizeof(minuteMap[0]); l++) {
    int m = minuteMap[l];
    bool isInHourMap = false;
    for (int n = 0; n < sizeof(hourMap) / sizeof(hourMap[0]); n++) {
      int k = hourMap[n];
      if (m == k) {
        isInHourMap = true;
        break;
      } else if (n % 3 == 0) {
        strip.setPixelColor(k, strip.Color(handBright, handBright, handBright));
      }
    }
    if (isInHourMap && l != 0 && l != 15 && l != 30 && l != 45) {
      strip.setPixelColor(m, strip.Color(0, handBright / 2, handBright / 2));
    } else if (!isInHourMap) {
      strip.setPixelColor(m, strip.Color(0, 0, mHandBright));
    }
  }
  strip.show();
}
void twinkle(unsigned int r, unsigned int g, unsigned int b, unsigned long speed) {
  unsigned long freq = map(speed, 1, 100, 300, 1);
  if ((cMillis - pMillis) >= 20 && (aOn == true) && (ledState == true)) {
    if (random(freq) == 1) {
      uint16_t pix = random(LEDCOUNT);
      if ((ledStatus[pix] == 0) && (speed > 0)) {
        rStates[pix] = r;
        gStates[pix] = g;
        bStates[pix] = b;
        ledStatus[pix] = 1;
        setPopHang[pix] = random(0, 20);
      }
    }
    for (int i = 0; i < LEDCOUNT; i++) {
      if ((ledStatus[i] >= 100) && (popHang[i] <= setPopHang[i])) {
        popHang[i]++;
      }
      if ((ledStatus[i] > 0) && (ledStatus[i] <= 100)) {
        int correctedbrightness = map(ledStatus[i], 1, 100, 1, 255);
        strip.setPixelColor(i, stripBright * (((rStates[i] / 255) * correctedbrightness) / 255), stripBright * (((gStates[i] / 255) * correctedbrightness) / 255), stripBright * (((bStates[i] / 255) * correctedbrightness)) / 255);
        ledStatus[i]++;
      }
      if ((ledStatus[i] > 100) && (ledStatus[i] < 200) && (popHang[i] >= setPopHang[i])) {
        int correctedbrightness = map(ledStatus[i], 101, 200, 255, 1);
        strip.setPixelColor(i, stripBright * (((rStates[i] / 255) * correctedbrightness) / 255), stripBright * (((gStates[i] / 255) * correctedbrightness) / 255), stripBright * (((bStates[i] / 255) * correctedbrightness)) / 255);
        ledStatus[i]++;
      }
      if ((ledStatus[i] >= 200)) {
        strip.setPixelColor(i, 0, 0, 0);
        ledStatus[i] = 0;
        popHang[i] = 0;
        setPopHang[i] = 0;
        rStates[i] = 0;
        gStates[i] = 0;
        bStates[i] = 0;
      }
    }
    strip.show();
    pMillis = cMillis;
  }
}

void sparkle(unsigned int r, unsigned int g, unsigned int b, unsigned long freq) {
  uint16_t wait;
  int dotSpacing = 12;
  wait = (1000 / freq);
  if ((cMillis - pMillis >= wait) && (aOn == true) && (ledState == true) && (freq > 0)) {
    for (int i = 0; i <= LEDCOUNT; i++) {
      if (i % dotSpacing == 0) {
        if ((ledStatus[i] == setPopHang[i])) {
          popHang[i] = setPopHang[i];
          while ((setPopHang[i] >= popHang[i] - 40) && (setPopHang[i] <= popHang[i] + 40)) {
            setPopHang[i] = random(0, 100);
          }
        }

        if ((ledStatus[i] > setPopHang[i])) {
          ledStatus[i]--;
          int correctedbrightness = map(ledStatus[i], 0, 100, 0, 255);
          strip.setPixelColor(i, stripBright * (((r / 255) * correctedbrightness) / 255), stripBright * (((g / 255) * correctedbrightness) / 255), stripBright * (((b / 255) * correctedbrightness)) / 255);
          strip.show();
        }
        if ((ledStatus[i] < setPopHang[i])) {
          ledStatus[i]++;
          int correctedbrightness = map(ledStatus[i], 0, 100, 0, 255);
          strip.setPixelColor(i, stripBright * (((r / 255) * correctedbrightness) / 255), stripBright * (((g / 255) * correctedbrightness) / 255), stripBright * (((b / 255) * correctedbrightness)) / 255);
          strip.show();
        }
      }
    }
    pMillis = cMillis;
  }
}

void timeoutMode(unsigned int r, unsigned int g, unsigned int b, unsigned long freq) {
  long cwait;
  cwait = ((1000 / freq) / 2);
  if (prevBrightness != brightness && (cMillis - pMillis >= cwait) && (aOn == true) && (ledState == true) && (freq > 0)) {
    Serial.println("timeoutMode Triggered");
    prevBrightness = brightness;
    for (int i = 0; i < LEDCOUNT; i++) {
      if (beamPos[i] == 1) {
        strip.setPixelColor(i, brightness * (r / 255), brightness * (g / 255), brightness * (b / 255));
        //     strip.setPixelColor(i,255,0,0);
      }
      if (beamPos[i] == 0) {
        strip.setPixelColor(i, 0, 0, 0);
      }
    }
    strip.show();
    if (goingUp == true) {
      brightness++;
    } else if (goingUp == false) {
      brightness--;
    }
    if (brightness >= stripBright) {
      goingUp = false;
    }
    if (brightness <= 0) {
      goingUp = true;
    }
    if (aStep < beamSize) {
      for (int i = 1; i < LEDCOUNT; i++) {
        if (beamPos[i] == 0 && beamPos[i + 1] == 1) {
          beamPos[i] = 1;
        }
        if (beamPos[i] == 1 && beamPos[i + 1] == 0) {
          beamPos[i] = 0;
        }
      }
      if (beamPos[beamSize] == 0 && beamPos[LEDCOUNT - beamSize] == 0) {
        beamPos[LEDCOUNT] = 1;
      }
      if (beamPos[LEDCOUNT - beamSize] == 1) {
        beamPos[LEDCOUNT] = 0;
      }

      aStep++;
      if (aStep >= beamSize) {
        aStep = 0;
      }
    }
    pMillis = cMillis;
  }
}