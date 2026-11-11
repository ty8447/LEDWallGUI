"""Static integration assertions only: NOT substitutes for Android/Adafruit builds or hardware tests."""
from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]
ino = (root / 'DormLED_RTC_Android.ino').read_text()
main = (root/'app/src/main/java/com/example/ledwallgui/MainActivity.kt').read_text()
manager = (root/'app/src/main/java/com/example/ledwallgui/ui/settings/GlobalBluetoothManager.kt').read_text()
settings = (root/'app/src/main/java/com/example/ledwallgui/ui/settings/settingsActivity.kt').read_text()
connection = (root/'app/src/main/java/com/example/ledwallgui/ui/settings/ConnectionActivity.kt').read_text()
manifest = (root/'app/src/main/AndroidManifest.xml').read_text()
setup = (root/'app/src/main/java/com/example/ledwallgui/ui/settings/ControllerSetupActivity.kt').read_text()
motion = (root/'app/src/main/java/com/example/ledwallgui/ui/TabletMotionWake.kt').read_text()
mic = (root/'app/src/main/java/com/example/ledwallgui/ui/AudioReactiveService.kt').read_text()
assert '#define LEDCOUNT 1165' in ino
assert 'memset(data, 0, len + 1)' not in ino
assert 'parameterResponseCharacteristic.write((uint8_t*)ledStateStr' not in ino
assert 'handleClockControl' in ino and 'handleClockRequest' in ino
assert 'RTC' in setup and 'Wall' in setup and 'DST' in setup
assert 'StoredClockConfig' in ino and 'InternalFS.begin()' in ino
assert 'const int secondCenter = (customWallMapEnabled || savedRoomCornerCount >= 4) ? minuteMap[second % 60] : sBarPos' in ino
assert 'Audio reactive' in ino and 'Audio:Level:' in manager
assert 'LedGattSession' in manager and 'connectGatt(' in manager
assert 'ClientBleGatt' not in manager
assert 'writeCharacteristic(' in manager and 'onCharacteristicWrite(' in manager
assert 'setCharacteristicNotification' in manager
assert 'startScan(' in connection and 'stopScan(' in connection
assert 'bluetoothAdapter.startDiscovery()' not in connection
assert 'registerReceiver' in connection and 'unregisterReceiver' in connection
assert 'AudioReactiveService' in manifest and 'foregroundServiceType="microphone"' in manifest
assert 'ControllerSetupActivity' in manifest
assert 'MotionDetectionService' not in manifest
assert 'TabletMotionWake' in main and 'dispatchTouchEvent' in main
assert 'FLAG_KEEP_SCREEN_ON' in motion and 'image.close()' in motion
assert 'screenBrightness' in motion and 'PROXIMITY_SCREEN_OFF_WAKE_LOCK' not in motion
assert 'AudioRecord' in mic and 'RECORD_AUDIO' in mic and 'recording?.release()' in mic
assert 'syncButton.text = "RTC / Wall Map"' in settings
assert main.count('private var motionWake') == 1
minute = re.search(r'originalMinuteMap\[60\] = \{([^}]+)\}', ino)
assert minute
values = [int(x.strip()) for x in minute.group(1).split(',')]
assert len(values) == 60 and values[0] == 707 and values[23] == 8
assert len(set(values)) == 60 and all(0 <= v < 1165 for v in values)
assert ino.count('"Audio reactive"') >= 2
assert 'if (connection_count > 0) connection_count--' in ino
print('PASS: Arduino compatibility, BLE management, scan, RTC/DST, wall editor, mic, motion, permissions')
