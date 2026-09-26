# Hardware baseline

- Board: Seeed Studio XIAO nRF52840 Sense
- MCU: Nordic nRF52840
- IMU: LSM6DS3TR-C at I2C address `0x6A`
- Initial IMU sampling rate: 50 Hz
- USB serial rate: 115200 baud
- BLE self-test name: `PawLink-Test`
- Battery: 1S 3.7 V 302540 LiPo, 300 mAh, protected
- Battery measurement enable: `P0.14` / `PIN_VBAT_ENABLE`, active low
- Battery ADC: `P0.31` / `PIN_VBAT`, 12-bit through onboard divider
- Charger status: `P0.17` / variant pin index 23 / `~CHG`, active low

For repeatable motion data, mount the board with USB-C pointing left, the
component side facing away from the wearer, and the long axis following the
collar.

## Battery measurement

Firmware holds `PIN_VBAT_ENABLE` low and samples `PIN_VBAT` every 10 seconds.
This follows the safe measurement mode for the original XIAO nRF52840 Sense:
P0.14 enables the divider and P0.31 reads its output. The conversion assumes the
board's 1 MΩ / 510 kΩ divider and a 3.3 V ADC reference.

The reported percentage is an approximate open-circuit LiPo voltage mapping.
Motion, radio transmission, charging, temperature, and cell age can move the
reading, so consumers should smooth it and avoid treating a one-percent change
as significant. A dedicated fuel-gauge IC would be required for accurate state
of-charge estimation.

## Charging and shutdown

Firmware samples `~CHG` with an internal pull-up. A low level means that the
onboard charger is actively charging. A high level means only `NOT_CHARGING`;
it does not distinguish a full battery, an unplugged charger, or no cell.

The BLE shutdown command places the MCU in `SYSTEMOFF` after stopping BLE and
putting both IMU channels in power-down. Reset starts it again. For a collar that
must be completely off during storage or transport, place a suitably rated
miniature switch in series between battery positive and the board's BAT+ pad.
