#include <Arduino.h>
#include <ArduinoBLE.h>
#include <LSM6DS3.h>
#include <math.h>
#include <nrf.h>
#include <string.h>

#include "model_data.h"

namespace {
constexpr uint32_t kBaudRate = 115200;
constexpr uint32_t kSampleIntervalMs = 20;  // 50 Hz
constexpr uint32_t kStatusIntervalMs = 1000;
constexpr uint32_t kBatteryIntervalMs = 10000;
constexpr uint8_t kImuAddress = 0x6A;
constexpr uint8_t kChargeStatusPin = 23;  // P0.17 / D22 / active-low ~CHG.
constexpr const char* kShutdownCommand = "SHUTDOWN";
constexpr uint8_t kBatteryAdcSamples = 8;
constexpr uint16_t kBatteryAdcReferenceMv = 3300;
constexpr uint16_t kBatteryDividerHighOhmsK = 1000;
constexpr uint16_t kBatteryDividerLowOhmsK = 510;
constexpr uint16_t kBatteryPlausibleMinimumMv = 2500;
constexpr uint16_t kBatteryPlausibleMaximumMv = 5000;

// Exactly 20 bytes: fits one notification with the default BLE ATT MTU.
struct __attribute__((packed)) ImuFrame {
  uint32_t sequence;
  uint32_t timestampMs;
  int16_t accelMilliG[3];
  int16_t gyroCentiDps[3];
};
static_assert(sizeof(ImuFrame) == 20, "BLE IMU frame must remain 20 bytes");

struct ImuSample {
  int16_t accelMilliG[3];
  int16_t gyroCentiDps[3];
};

enum class PowerState : uint8_t {
  kNotCharging = 0,
  kCharging = 1,
  kUnknown = 2,
};

// Stable public action API. Keep this order synchronized with the desktop pet.
enum class ApiAction : uint8_t {
  kFeed = 0,
  kJump = 1,
  kGroom = 2,
  kWash = 3,
  kRoll = 4,
  kWalk = 5,
  kSleep = 6,
};

LSM6DS3 imu(I2C_MODE, kImuAddress);
BLEService selfTestService("7e400001-b5a3-f393-e0a9-e50e24dcca9e");
BLEStringCharacteristic statusCharacteristic(
    "7e400002-b5a3-f393-e0a9-e50e24dcca9e",
    BLERead | BLEWrite | BLENotify, 64);
BLECharacteristic imuDataCharacteristic(
    "7e400003-b5a3-f393-e0a9-e50e24dcca9e", BLERead | BLENotify,
    sizeof(ImuFrame), true);
BLEStringCharacteristic predictionCharacteristic(
    "7e400004-b5a3-f393-e0a9-e50e24dcca9e", BLERead | BLENotify, 64);
BLEUnsignedShortCharacteristic batteryVoltageCharacteristic(
    "7e400005-b5a3-f393-e0a9-e50e24dcca9e", BLERead | BLENotify);
// Four-byte request nonce; 12-byte response nonce, receive millis, send millis.
BLECharacteristic syncCharacteristic(
    "7e400006-b5a3-f393-e0a9-e50e24dcca9e", BLEWrite | BLENotify, 12, false);
BLEService batteryService("180F");
BLEUnsignedCharCharacteristic batteryLevelCharacteristic(
    "2A19", BLERead | BLENotify);

bool imuOk = false;
bool bleOk = false;
uint32_t sequence = 0;
uint32_t lastSampleMs = 0;
uint32_t lastStatusMs = 0;
uint32_t lastBatteryMs = 0;
bool ledOn = false;
uint16_t batteryVoltageMv = 0;
uint8_t batteryPercent = 0;
bool batteryReadingValid = false;
PowerState powerState = PowerState::kUnknown;
ImuSample inferenceWindow[pawlink_model::kWindowSamples];
size_t inferenceCount = 0;
uint32_t inferenceWindowNumber = 0;
ApiAction currentApiAction = ApiAction::kSleep;

int16_t scaledInt16(float value, float scale) {
  const float scaled = value * scale;
  if (scaled > 32767.0f) {
    return 32767;
  }
  if (scaled < -32768.0f) {
    return -32768;
  }
  return static_cast<int16_t>(scaled);
}

void setLed(bool on) {
  // The XIAO user LED is active-low.
  digitalWrite(LED_BUILTIN, on ? LOW : HIGH);
  ledOn = on;
}

const char* apiActionName(ApiAction action) {
  switch (action) {
    case ApiAction::kFeed:
      return "feed";
    case ApiAction::kJump:
      return "jump";
    case ApiAction::kGroom:
      return "groom";
    case ApiAction::kWash:
      return "wash";
    case ApiAction::kRoll:
      return "roll";
    case ApiAction::kWalk:
      return "walk";
    case ApiAction::kSleep:
      return "sleep";
  }
  return "sleep";
}

ApiAction mapClassifierLabelToApiAction(const char* sourceLabel) {
  if (strcmp(sourceLabel, "feed") == 0) {
    return ApiAction::kFeed;
  }
  if (strcmp(sourceLabel, "groom") == 0) {
    return ApiAction::kGroom;
  }
  if (strcmp(sourceLabel, "locomotion") == 0) {
    return ApiAction::kWalk;
  }
  if (strcmp(sourceLabel, "rest") == 0) {
    return ApiAction::kSleep;
  }
  // collar_shake is an internal artifact class, not a pet animation. Preserve
  // the last public action instead of emitting a misleading jump/roll event.
  return currentApiAction;
}

struct BatteryCurvePoint {
  uint16_t millivolts;
  uint8_t percent;
};

constexpr BatteryCurvePoint kBatteryCurve[] = {
    {3300, 0},  {3500, 5},  {3600, 10}, {3700, 20},
    {3750, 30}, {3800, 40}, {3850, 50}, {3900, 60},
    {3950, 70}, {4000, 80}, {4100, 90}, {4200, 100},
};

uint8_t estimateBatteryPercent(uint16_t millivolts) {
  if (millivolts <= kBatteryCurve[0].millivolts) {
    return kBatteryCurve[0].percent;
  }
  const size_t pointCount = sizeof(kBatteryCurve) / sizeof(kBatteryCurve[0]);
  if (millivolts >= kBatteryCurve[pointCount - 1].millivolts) {
    return kBatteryCurve[pointCount - 1].percent;
  }

  for (size_t index = 1; index < pointCount; ++index) {
    const BatteryCurvePoint& upper = kBatteryCurve[index];
    if (millivolts <= upper.millivolts) {
      const BatteryCurvePoint& lower = kBatteryCurve[index - 1];
      const uint32_t voltageOffset = millivolts - lower.millivolts;
      const uint32_t voltageRange = upper.millivolts - lower.millivolts;
      const uint32_t percentRange = upper.percent - lower.percent;
      return lower.percent +
             static_cast<uint8_t>((voltageOffset * percentRange) / voltageRange);
    }
  }
  return 0;
}

PowerState readPowerState() {
  if (digitalRead(kChargeStatusPin) == LOW) {
    return PowerState::kCharging;
  }
  return PowerState::kNotCharging;
}

const char* powerStateName(PowerState state) {
  switch (state) {
    case PowerState::kNotCharging:
      return "NOT_CHARGING";
    case PowerState::kCharging:
      return "CHARGING";
    default:
      return "UNKNOWN";
  }
}

uint16_t readBatteryMillivolts() {
  // The original XIAO nRF52840/Sense requires READ_BAT_ENABLE to stay low
  // while P0.31 is sampled. The divider is 1 MOhm over 510 kOhm.
  uint32_t rawSum = 0;
  (void)analogRead(PIN_VBAT);  // Discard the first conversion after settling.
  for (uint8_t sample = 0; sample < kBatteryAdcSamples; ++sample) {
    rawSum += analogRead(PIN_VBAT);
    delayMicroseconds(250);
  }
  const uint32_t rawAverage = rawSum / kBatteryAdcSamples;
  const uint64_t numerator =
      static_cast<uint64_t>(rawAverage) * kBatteryAdcReferenceMv *
      (kBatteryDividerHighOhmsK + kBatteryDividerLowOhmsK);
  const uint32_t denominator = 4095UL * kBatteryDividerLowOhmsK;
  return static_cast<uint16_t>((numerator + denominator / 2) / denominator);
}

void updateBattery(uint32_t now) {
  batteryVoltageMv = readBatteryMillivolts();
  batteryReadingValid = batteryVoltageMv >= kBatteryPlausibleMinimumMv &&
                        batteryVoltageMv <= kBatteryPlausibleMaximumMv;
  batteryPercent =
      batteryReadingValid ? estimateBatteryPercent(batteryVoltageMv) : 0;
  powerState = readPowerState();
  lastBatteryMs = now;

  Serial.print("BATTERY,");
  Serial.print(now);
  Serial.print(',');
  Serial.print(batteryVoltageMv);
  Serial.print(',');
  if (batteryReadingValid) {
    Serial.println(batteryPercent);
  } else {
    Serial.println("NA");
  }

  Serial.print("POWER,");
  Serial.print(now);
  Serial.print(',');
  Serial.print(powerStateName(powerState));
  Serial.print(",CHARGE_ACTIVE=");
  Serial.println(digitalRead(kChargeStatusPin) == LOW ? 1 : 0);

  if (bleOk) {
    batteryVoltageCharacteristic.writeValue(batteryVoltageMv);
    batteryLevelCharacteristic.writeValue(batteryPercent);
  }
}

void printStatus() {
  Serial.print("STATUS,USB=PASS,IMU=");
  Serial.print(imuOk ? "PASS" : "FAIL");
  Serial.print(",BLE=");
  Serial.print(bleOk ? "PASS" : "FAIL");
  Serial.print(",BATTERY=");
  if (batteryReadingValid) {
    Serial.print(batteryPercent);
    Serial.print('%');
  } else {
    Serial.print("NA");
  }
  Serial.print(",VBAT=");
  Serial.print(batteryVoltageMv);
  Serial.print("mV,POWER=");
  Serial.println(powerStateName(powerState));

  String status = "IMU=";
  status += imuOk ? "PASS" : "FAIL";
  status += ",BLE=";
  status += bleOk ? "PASS" : "FAIL";
  status += ",BAT=";
  status += batteryReadingValid ? String(batteryPercent) + "%" : "NA";
  status += ",VBAT=";
  status += String(batteryVoltageMv);
  status += ",PWR=";
  status += powerStateName(powerState);
  if (bleOk) {
    statusCharacteristic.writeValue(status);
  }
}

[[noreturn]] void enterSystemOff() {
  Serial.println("POWER_OFF,reason=BLE_COMMAND");
  if (bleOk) {
    statusCharacteristic.writeValue("SHUTTING_DOWN");
    delay(150);
    BLE.stopAdvertise();
    BLE.end();
    bleOk = false;
  }

  if (imuOk) {
    imu.writeRegister(LSM6DS3_ACC_GYRO_CTRL1_XL, 0x00);
    imu.writeRegister(LSM6DS3_ACC_GYRO_CTRL2_G, 0x00);
  }
  setLed(false);
  delay(20);

  NRF_POWER->SYSTEMOFF = 1;
  __DSB();
  while (true) {
    __WFE();
  }
}

void extractFeatures(float* features) {
  float sums[4] = {};
  float squareSums[4] = {};
  float minima[4] = {INFINITY, INFINITY, INFINITY, INFINITY};
  float maxima[4] = {-INFINITY, -INFINITY, -INFINITY, -INFINITY};
  float absoluteDifferenceSums[4] = {};
  float differenceSquareSums[4] = {};
  float previous[4] = {};

  for (size_t sampleIndex = 0; sampleIndex < pawlink_model::kWindowSamples;
       ++sampleIndex) {
    const ImuSample& sample = inferenceWindow[sampleIndex];
    const float ax = sample.accelMilliG[0] / 1000.0f;
    const float ay = sample.accelMilliG[1] / 1000.0f;
    const float az = sample.accelMilliG[2] / 1000.0f;
    const float values[4] = {
        ax,
        ay,
        az,
        sqrtf(ax * ax + ay * ay + az * az),
    };
    for (size_t channel = 0; channel < 4; ++channel) {
      sums[channel] += values[channel];
      squareSums[channel] += values[channel] * values[channel];
      minima[channel] = fminf(minima[channel], values[channel]);
      maxima[channel] = fmaxf(maxima[channel], values[channel]);
      if (sampleIndex > 0) {
        const float difference = values[channel] - previous[channel];
        absoluteDifferenceSums[channel] += fabsf(difference);
        differenceSquareSums[channel] += difference * difference;
      }
      previous[channel] = values[channel];
    }
  }

  const float sampleCount = static_cast<float>(pawlink_model::kWindowSamples);
  const float differenceCount = sampleCount - 1.0f;
  for (size_t channel = 0; channel < 4; ++channel) {
    const float mean = sums[channel] / sampleCount;
    const float meanSquare = squareSums[channel] / sampleCount;
    features[channel] = mean;
    features[4 + channel] = sqrtf(fmaxf(0.0f, meanSquare - mean * mean));
    features[8 + channel] = sqrtf(meanSquare);
    features[12 + channel] = minima[channel];
    features[16 + channel] = maxima[channel];
    features[20 + channel] = maxima[channel] - minima[channel];
    features[24 + channel] = absoluteDifferenceSums[channel] / differenceCount;
    features[28 + channel] = sqrtf(differenceSquareSums[channel] / differenceCount);
  }
}

void classifyWindow(uint32_t endingSequence) {
  const uint32_t inferenceStartedUs = micros();
  float features[pawlink_model::kFeatureCount];
  uint16_t votes[pawlink_model::kClassCount] = {};
  extractFeatures(features);

  for (size_t treeIndex = 0; treeIndex < pawlink_model::kTreeCount; ++treeIndex) {
    uint16_t nodeIndex = pawlink_model::kTreeRoots[treeIndex];
    while (pawlink_model::kNodes[nodeIndex].feature != pawlink_model::kLeafFeature) {
      const pawlink_model::TreeNode& node = pawlink_model::kNodes[nodeIndex];
      nodeIndex = features[node.feature] <= node.threshold ? node.left : node.right;
    }
    ++votes[pawlink_model::kNodes[nodeIndex].leafClass];
  }

  size_t bestClass = 0;
  uint16_t bestVotes = 0;
  uint16_t secondVotes = 0;
  for (size_t classIndex = 0; classIndex < pawlink_model::kClassCount; ++classIndex) {
    if (votes[classIndex] > bestVotes) {
      secondVotes = bestVotes;
      bestVotes = votes[classIndex];
      bestClass = classIndex;
    } else if (votes[classIndex] > secondVotes) {
      secondVotes = votes[classIndex];
    }
  }

  const float margin = static_cast<float>(bestVotes - secondVotes) /
                       static_cast<float>(pawlink_model::kTreeCount);
  const uint32_t latencyUs = micros() - inferenceStartedUs;
  ++inferenceWindowNumber;
  const char* sourceLabel = pawlink_model::kLabels[bestClass];
  currentApiAction = mapClassifierLabelToApiAction(sourceLabel);
  const char* action = apiActionName(currentApiAction);

  Serial.print("PREDICT,");
  Serial.print(inferenceWindowNumber);
  Serial.print(',');
  Serial.print(endingSequence - pawlink_model::kWindowSamples + 1);
  Serial.print(',');
  Serial.print(endingSequence);
  Serial.print(',');
  Serial.print(action);
  Serial.print(',');
  Serial.print(sourceLabel);
  Serial.print(',');
  Serial.print(margin, 4);
  Serial.print(',');
  Serial.print(latencyUs);
  for (size_t classIndex = 0; classIndex < pawlink_model::kClassCount;
       ++classIndex) {
    Serial.print(',');
    Serial.print(static_cast<float>(votes[classIndex]) /
                     static_cast<float>(pawlink_model::kTreeCount),
                 4);
  }
  Serial.println();

  if (bleOk) {
    String prediction = action;
    prediction += ',';
    prediction += String(margin, 4);
    prediction += ',';
    prediction += String(latencyUs);
    prediction += ',';
    prediction += String(inferenceWindowNumber);
    prediction += ',';
    prediction += sourceLabel;
    predictionCharacteristic.writeValue(prediction);
  }
}
}  // namespace

void setup() {
  pinMode(LED_BUILTIN, OUTPUT);
  setLed(true);

  // P0.14 enables the onboard battery divider when held low. Keeping it low
  // is the safe mode recommended for the original XIAO nRF52840 Sense.
  pinMode(PIN_VBAT_ENABLE, OUTPUT);
  digitalWrite(PIN_VBAT_ENABLE, LOW);
  pinMode(kChargeStatusPin, INPUT_PULLUP);
  analogReadResolution(12);
  delay(10);

  Serial.begin(kBaudRate);
  const uint32_t serialWaitStarted = millis();
  while (!Serial && millis() - serialWaitStarted < 3000) {
    delay(10);
  }

  Serial.println("PAW-LINK SELF-TEST v5");
  Serial.println("BOARD:XIAO nRF52840 Sense");
  Serial.println("USB_SERIAL:PASS");

  imuOk = (imu.begin() == 0);
  Serial.println(imuOk ? "IMU:PASS" : "IMU:FAIL");

  bleOk = BLE.begin();
  if (bleOk) {
    BLE.setDeviceName("PawLink-Test");
    BLE.setLocalName("PawLink-Test");
    BLE.setAdvertisedService(selfTestService);
    selfTestService.addCharacteristic(statusCharacteristic);
    selfTestService.addCharacteristic(imuDataCharacteristic);
    selfTestService.addCharacteristic(predictionCharacteristic);
    selfTestService.addCharacteristic(batteryVoltageCharacteristic);
    selfTestService.addCharacteristic(syncCharacteristic);
    BLE.addService(selfTestService);
    batteryService.addCharacteristic(batteryLevelCharacteristic);
    BLE.addService(batteryService);
    statusCharacteristic.writeValue("BOOTING");
    predictionCharacteristic.writeValue("WAITING");
    batteryVoltageCharacteristic.writeValue(0);
    batteryLevelCharacteristic.writeValue(0);
    BLE.advertise();
  }
  updateBattery(millis());
  Serial.println(bleOk ? "BLE:PASS" : "BLE:FAIL");
  Serial.println((imuOk && bleOk) ? "SELF_TEST:PASS" : "SELF_TEST:FAIL");
  Serial.print("EDGE_MODEL:PASS,type=");
  Serial.print(pawlink_model::kModelType);
  Serial.print(",classes=");
  Serial.print(pawlink_model::kClassCount);
  Serial.print(",features=");
  Serial.print(pawlink_model::kFeatureCount);
  Serial.print(",window_samples=");
  Serial.println(pawlink_model::kWindowSamples);
  Serial.println("CSV:DATA,sequence,millis,ax_g,ay_g,az_g,gx_dps,gy_dps,gz_dps");
  Serial.println("ACTIONS:feed,jump,groom,wash,roll,walk,sleep");
  Serial.println("AUTO_ACTIONS:feed,groom,walk,sleep");
  Serial.println("CSV:PREDICT,window,first_sequence,last_sequence,action,source_label,margin,latency_us,p_rest,p_locomotion,p_feed,p_groom,p_collar_shake");
  Serial.println("CSV:BATTERY,millis,millivolts,percent");
  Serial.println("CSV:POWER,millis,state,charge_active");
  printStatus();
}

void loop() {
  if (bleOk) {
    BLE.poll();
    if (syncCharacteristic.written() && syncCharacteristic.valueLength() == 4) {
      const uint32_t receivedMs = millis();
      uint8_t request[4];
      syncCharacteristic.readValue(request, sizeof(request));
      uint8_t reply[12];
      memcpy(reply, request, 4);
      const uint32_t sentMs = millis();
      for (uint8_t i = 0; i < 4; ++i) {
        reply[4 + i] = (receivedMs >> (8 * i)) & 0xff;
        reply[8 + i] = (sentMs >> (8 * i)) & 0xff;
      }
      syncCharacteristic.writeValue(reply, sizeof(reply));
    }
    if (statusCharacteristic.written() &&
        statusCharacteristic.value() == kShutdownCommand) {
      enterSystemOff();
    }
  }

  const uint32_t now = millis();
  if (now - lastStatusMs >= kStatusIntervalMs) {
    lastStatusMs = now;
    setLed(!ledOn);
    printStatus();
  }

  if (now - lastBatteryMs >= kBatteryIntervalMs) {
    updateBattery(now);
  }

  if (!imuOk || now - lastSampleMs < kSampleIntervalMs) {
    return;
  }
  lastSampleMs = now;

  const uint32_t frameSequence = sequence++;
  const uint32_t sampleBeginMs = millis();
  const float ax = imu.readFloatAccelX();
  const float ay = imu.readFloatAccelY();
  const float az = imu.readFloatAccelZ();
  const float gx = imu.readFloatGyroX();
  const float gy = imu.readFloatGyroY();
  const float gz = imu.readFloatGyroZ();
  // Midpoint of the six register reads, rather than BLE send time.
  // Sensor conversion age is not measured; do not claim hardware-trigger precision.
  const uint32_t sampledMs = sampleBeginMs + (millis() - sampleBeginMs) / 2;

  Serial.print("DATA,");
  Serial.print(frameSequence);
  Serial.print(',');
  Serial.print(sampledMs);
  Serial.print(',');
  Serial.print(ax, 5);
  Serial.print(',');
  Serial.print(ay, 5);
  Serial.print(',');
  Serial.print(az, 5);
  Serial.print(',');
  Serial.print(gx, 3);
  Serial.print(',');
  Serial.print(gy, 3);
  Serial.print(',');
  Serial.println(gz, 3);

  ImuFrame frame = {
      frameSequence,
      sampledMs,
      {scaledInt16(ax, 1000.0f), scaledInt16(ay, 1000.0f),
       scaledInt16(az, 1000.0f)},
      {scaledInt16(gx, 100.0f), scaledInt16(gy, 100.0f),
       scaledInt16(gz, 100.0f)},
  };
  if (bleOk) {
    imuDataCharacteristic.writeValue(&frame, sizeof(frame));
  }

  inferenceWindow[inferenceCount] = {
      {frame.accelMilliG[0], frame.accelMilliG[1], frame.accelMilliG[2]},
      {frame.gyroCentiDps[0], frame.gyroCentiDps[1], frame.gyroCentiDps[2]},
  };
  ++inferenceCount;
  if (inferenceCount == pawlink_model::kWindowSamples) {
    classifyWindow(frameSequence);
    inferenceCount = 0;
  }
}
