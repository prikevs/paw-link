# BLE IMU protocol

Service UUID: `7e400001-b5a3-f393-e0a9-e50e24dcca9e`

| Characteristic | UUID suffix | Properties | Payload |
| --- | --- | --- | --- |
| Status / control | `0002` | Read, Write, Notify | UTF-8 status; write `SHUTDOWN` to enter `SYSTEMOFF` |
| IMU data | `0003` | Read, Notify | 20-byte binary frame |
| Prediction | `0004` | Read, Notify | UTF-8: `action,margin,latency_us,window,source_label` |
| Battery voltage | `0005` | Read, Notify | Little-endian `uint16`, millivolts |

The IMU frame is little-endian and uses the following packed layout:

| Offset | Type | Field | Scale |
| ---: | --- | --- | --- |
| 0 | `uint32` | Sequence number | 1 |
| 4 | `uint32` | Device uptime | milliseconds |
| 8 | `int16[3]` | X/Y/Z acceleration | 0.001 g |
| 14 | `int16[3]` | X/Y/Z angular rate | 0.01 degrees/second |

Format string for Python `struct`: `<IIhhhhhh`.

The sequence number wraps at `2^32` and allows receivers to measure packet
loss. One frame is exactly 20 bytes so it fits in a notification at the
default BLE ATT MTU.

The device classifies each non-overlapping 100-sample window (about two
seconds). Predictions are also written to USB serial as:

```text
PREDICT,window,first_sequence,last_sequence,action,source_label,margin,latency_us,p_rest,p_locomotion,p_feed,p_groom,p_collar_shake
```

The five `p_` values are the share of random-forest trees voting for each
class and sum to approximately 1. The margin is the vote-share difference
between the top two source classes. The BLE payload carries the stable public
action, margin, inference latency, window number, and original model label.

## Public action API

The firmware-facing animation contract is fixed to these names and numeric
positions:

| ID | Action | Current automatic source |
| ---: | --- | --- |
| 0 | `feed` | source `feed` |
| 1 | `jump` | reserved; requires labelled training data |
| 2 | `groom` | source `groom` |
| 3 | `wash` | reserved; requires labelled training data |
| 4 | `roll` | reserved; requires labelled training data |
| 5 | `walk` | source `locomotion` |
| 6 | `sleep` | source `rest` |

Source `collar_shake` is retained for diagnostics but does not change the
public action. The firmware preserves the last action instead of inventing a
`jump` or `roll` result. This keeps the desktop-pet API stable while later
seven-class models can replace the current model without renaming animations.

## Battery telemetry

The firmware also exposes the standard BLE Battery Service:

| Service | Characteristic | Properties | Payload |
| --- | --- | --- | --- |
| Battery Service `180F` | Battery Level `2A19` | Read, Notify | `uint8`, 0–100 percent |

Battery voltage is measured through the XIAO's onboard divider every 10 seconds.
USB serial prints:

```text
BATTERY,millis,millivolts,percent
POWER,millis,state,CHARGE_ACTIVE=0|1
```

`percent` is `NA` if the ADC result is outside the plausible range. Otherwise it
is a voltage-based LiPo estimate, not a fuel-gauge measurement. The custom
`0005` value provides the calculated millivolts so the Android application can
apply a different battery curve later without changing the IMU frame.

When USB-C is connected, VBAT alone cannot reliably prove whether a cell is
physically attached because the charger BAT node may still have voltage. Treat
the value as battery-node voltage, not as a definitive presence detector.

The `PWR` field in `0002` reads the XIAO's active-low `~CHG` signal. It is
`CHARGING` while the onboard charger actively charges and `NOT_CHARGING`
otherwise. The latter can mean full, unplugged, or no attached cell.

Writing UTF-8 `SHUTDOWN` to `0002` stops BLE, powers down the IMU, turns off the
user LED, and enters `SYSTEMOFF`. Press Reset to boot again. This prototype
command is not authenticated, so the Android UI should require explicit
confirmation. A switch in series with battery positive remains the only true
zero-standby-power off.
