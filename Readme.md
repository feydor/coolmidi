# COOLMidi prototype
requires Java 17+ (might get native executable compilation working)

## Screenshot
![Screenshot](/screenshot-v0.1.0-alpha.png)

## Build
```
# Jar
jar cfmv coolmidi.jar META-INF/MANIFEST.MF io.feydor.MidiCliPlayer.class io.feydor.midi
java -jar coolmidi.jar <TEST_MIDI>

# To native executable (in x64 Native Tools CMD Prompt for VS)
# NOTE: This won't actually run without JAVA_HOME because of some wierd reflection going on in the Java io.feydor.midi Receiver ???
set JAVA_HOME="/path/to/GRAALVM/"

cd out/production/cool-midi-proto

# running agentlib to detect dynamic features
java -agentlib:native-image-agent=config-merge-dir=./config io.feydor.MidiCliPlayer <TEST_MIDI>

# build the executable
native-image -H:JNIConfigurationFiles=config/jni-config.json io.feydor.MidiCliPlayer
```

## What works (and doesn't)
- Parsing and playback (track synchronization works now, but some MIDIs (i've heard it on Undertale_-_Megalovania_v1_2.mid) occasionally fire off a Note On message without a corresponding Note Off and sound awful)
- Formats 0 & 1 (but not 2)