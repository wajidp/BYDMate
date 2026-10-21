# BYDMate+ — fid catalog dumps

Symbol catalogs pulled from real cars with BYDMate's **Save fid catalog** button in Settings. Each line is
`<Class>.<CONSTANT>=<id>`: the firmware's own name for a device or feature id, nothing else. No
values were read from the car, and the dump holds no VIN, key or account data — only the names and
numbers compiled into the head unit's framework.

They are reference material for reverse engineering. **Nothing in the app reads these files**, and a
name here is not permission to write it: every write still goes through `WriteAllowlist`, and the
banned devices stay banned whatever the catalog calls them.

## Dumps

A dump belongs to a **firmware build**, not to one car: any head unit on the same build has the same
catalog. Another build may number things differently — the reason the app resolves its read
addresses per car (`FidResolver`) instead of trusting its compiled constants.

| File | Head unit | Build | Pulled with | Lines |
|---|---|---|---|---|
| [dilink5.0-SKQ1.230128.001-2026-10-21.txt](dilink5.0-SKQ1.230128.001-2026-10-21.txt) | DiLink 5.0 | `SKQ1.230128.001 release-keys` | BYDMate+ 4.0.1.3 | 14,910 |

Biggest groups: `Setting` 3385, `BYDAutoFeatureIds` 3213 (the same ids by feature name), `Bodywork`
2118, `Adas` 1333, `Instrument` 826, `Audio` 594, `Ac` 572, `Charging` 471, `Light` 438.

```bash
grep -i 'AEB' docs/plus/fid-catalog/*.txt          # by name
grep '=944767020$' docs/plus/fid-catalog/*.txt      # which name an id has
```

## What it confirmed: Keep AEB off

The two AEB fids BYDMate+ uses came from decompiling two third-party tools. The catalog names them:

| Id | Catalog name | Used by Keep AEB off as |
|---|---|---|
| 944767020 | `Adas.ADAS_AEB_STATE_SET` | the switch: write 0 (`aeb_off`) |
| 852492332 | `Adas.ADAS_AEB_STATE` | the state: read, 1 = on, 0 = off; also written 0 (`aeb_state_off`), as both tools do |
| 1038 | `BYDAutoConstants.BYDAUTO_DEVICE_ADAS` | the device, not banned |

Neighbours, read-only reference, **not used**: `ADAS_AEB_ACTIVE`=303038502 (AEB intervening right
now), `ADAS_AEB_ERROR`=852492322, `ADAS_HAS_AEB`=854373 / `ADAS_HAS2_AEB`=1126170633 (fitted).

`_STATE_SET` is the setter and `_STATE` the status, the pattern the whole Adas group follows (e.g.
`ADAS_BSD_STATE_SET`=944767024 / `ADAS_BSD_STATE`=1098907656). So the second write, to the status
fid, is probably redundant — the setter alone should do it. Not changed: the pair is what worked on
the car on 2026-10-21 (`SWITCHED_OFF before=1 after=0`), and dropping a write is for a later,
car-tested change.

## Device numbers

From `BYDAutoConstants.BYDAUTO_DEVICE_*` on this build:

| Id | Device | Id | Device | Id | Device |
|---|---|---|---|---|---|
| 1000 | AC | 1017 | LOCATION | 1034 | YUN |
| 1001 | BODYWORK | 1018 | VIDEO | 1035 | PROPERTY |
| 1002 | AUDIO | 1019 | AUX | 1036 | QCFS |
| 1004 | LIGHT | 1020 | MOTOR | 1037 | SIGNAL |
| 1005 | POWER | 1021 | RADIO | 1038 | ADAS |
| 1006 | ENERGY | 1022 | TEST | 1039 | GB |
| 1007 | INSTRUMENT | 1023 | SETTING | 1040 | RESCUE |
| 1008 | PM2P5 | 1024 | TIME | 1041 | DOOR_LOCK |
| 1009 | CHARGING | 1025 | RADAR | 1042 | SAFETY_BELT |
| 1010 | SECURITY | 1026 | REMINDER | 1043 | SENSOR |
| 1011 | GEARBOX | 1027 | VERSION | 1045 | DTC |
| 1012 | ENGINE | 1028 | FUNCNOTICE | 1046 | WIPER |
| 1013 | SPEED | 1029 | PHONE | 1047 | REAR_VIEW_MIRROR |
| 1014 | STATISTIC | 1030 | CPUTEMPRATURE | 1048 | VEHICLE_DATA |
| 1015 | COLLISION | 1031 | PANORAMA | 1049 | SPECIAL |
| 1016 | TYRE | 1032 | OTA | 1061 | BIG_DATA |
| | | 1033 | MQTT | 1062 | RSE |

The catalog's names for some banned devices differ from the labels in `WriteAllowlist` (1006 ENERGY, 1007
INSTRUMENT, 1014 STATISTIC, 1023 SETTING, 1032 OTA). That changes nothing: the banned list is the rule, kept as it is.

## Adding a dump

Tap **Save fid catalog** in Settings on the car (it lands in Download as `fid-dump-<time>.txt`), copy it here as
`<head unit>-<build>-<date>.txt` and add a row to the table. Keep the header lines the app writes.
