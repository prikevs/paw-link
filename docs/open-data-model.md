# Open-data cat behaviour model

The active firmware model is trained from Carolyn Dunford's CC0 dataset,
*Domestic cat accelerometer data calibrated with behaviours*:

- DOI: <https://doi.org/10.5061/dryad.q2bvq83sx>
- Source: 9 cats, collar-mounted tri-axis accelerometer, 40 Hz
- Device input: resampled to 50 Hz, 100-sample (2-second) windows
- Labels: `rest`, `locomotion`, `feed`, `groom`, `collar_shake`
- `locomotion` combines the source labels `Walk`, `Trot`, and `Run`

The model labels are internal evidence labels. Firmware maps them to the public
desktop-pet API as `feed → feed`, `groom → groom`, `locomotion → walk`, and
`rest → sleep`. `jump`, `wash`, and `roll` are reserved API actions because this
dataset contains no labelled examples for them. `collar_shake` is diagnostic
only and preserves the last public action.

The classifier is a 32-tree random forest with maximum depth 8. It uses 32
acceleration-only features: mean, population standard deviation, RMS, minimum,
maximum, range, mean absolute first difference, and RMS first difference for
X/Y/Z and acceleration magnitude. Gyroscope data remains in the BLE raw frame
but is not used by this model because the source dataset has no gyroscope.

## Honest validation result

Validation holds out every cat in turn, so no window from the test cat appears
in its training fold:

- 5,880 windows
- balanced accuracy: 0.361
- macro F1 across five labels: 0.350

Confusion matrix (rows are actual, columns are predicted):

| Actual | rest | locomotion | feed | groom | collar_shake |
| --- | ---: | ---: | ---: | ---: | ---: |
| rest | 4409 | 271 | 63 | 6 | 0 |
| locomotion | 92 | 865 | 23 | 2 | 2 |
| feed | 116 | 8 | 0 | 0 | 0 |
| groom | 10 | 6 | 0 | 0 | 0 |
| collar_shake | 0 | 7 | 0 | 0 | 0 |

`rest` and `locomotion` are useful as a preliminary baseline. The dataset has
only 16 two-second grooming windows and 7 collar-shake windows after
segmentation, and the three rare behaviours did not generalize to unseen cats.
Their outputs must be treated as experimental until video-labelled data from
the target collar and cat is available.

The source collars were mounted under the chin with defined body axes. A
different mounting position or orientation creates an additional domain shift.

## XIAO deployment measurement

- Forest size: 32 trees, 4,532 nodes
- Firmware Flash: 391,688 bytes (48%)
- Firmware static RAM: 72,968 bytes (30%)
- Stationary bench inference: about 1.5 ms without an active BLE client
- Observed with BLE subscribed: about 4–6.5 ms

On the stationary bench window, MCU and desktop portable inference both
reported `rest=0.9688`, `locomotion=0.0312`, and zero votes for the other
classes.
