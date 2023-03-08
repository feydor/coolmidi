# COOLMidi prototype

## Build
```
# To native executable
cd out/production/cool-midi-proto && native-image Main
```

## What works (and doesn't)
- Parsing and playback (except that I haven't done any track delta-time synchronization because I somehow thought that 1 thread per track would work out but it doesn't)
- Formats 0 & 1 (but not 2)