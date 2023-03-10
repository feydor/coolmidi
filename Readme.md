# COOLMidi prototype
Java 17+

## Build
```
# Jar
jar cfmv coolmidi.jar META-INF/MANIFEST.MF Main.class Midi
java -jar coolmidi.jar <TEST_MIDI>

# To native executable (in x64 Native Tools CMD Prompt for VS)
set JAVA_HOME="/path/to/GRAALVM/"

cd out/production/cool-midi-proto

# running agentlib to detect dynamic features
java -agentlib:native-image-agent=config-merge-dir=./config Main <TEST_MIDI>

# build the executable
native-image -H:JNIConfigurationFiles=config/jni-config.json Main
```

## What works (and doesn't)
- Parsing and playback (except that I haven't done any track delta-time synchronization because I somehow thought that 1 thread per track would work out but it doesn't)
- Formats 0 & 1 (but not 2)