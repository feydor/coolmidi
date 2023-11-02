CoolMidi
========
A MIDI parser, sequencer, and CLI player with the following features:
* Support for format 0 & 1 MIDI files including the following standards: GM, XG, GS
* Plays back all provided MIDI files in the given order (playlist support)
* A few different CLI UI options
* MIDI files with [running status](http://midi.teragonaudio.com/tech/midispec/run.htm) work fine
* ~~The name sounds like chlamydia~~

And also what isn't supported (yet):
* Format 2 MIDI files
* Some MIDI files that have their tempo information encoded in BPM instead of the standard Pulse Per Quarter Note (PPQN) will play a lot slower than expected
* There's likely a lot MIDI files out their with broken headers that will raise an exception in CoolMIDI

UI Screenshot
-------------
![Screenshot](/Screenshot%202023-11-02%20010229.png)

Build
-----
```
# requires Java 17+

# Jar
cd out/production/cool-midi-proto
jar cMf coolmidi.jar *
java -jar coolmidi.jar <TEST_MIDI>

# To native executable (in x64 Native Tools CMD Prompt for VS)
# NOTE: This won't actually run without JAVA_HOME because of some wierd reflection going on in the Java Receiver ???
set JAVA_HOME="/path/to/GRAALVM/"

cd out/production/cool-midi-proto

# running agentlib to detect dynamic features
java -agentlib:native-image-agent=config-merge-dir=./config io.feydor.MidiCliPlayer <TEST_MIDI>

# build the executable
native-image -H:JNIConfigurationFiles=config/jni-config.json io.feydor.MidiCliPlayer
```

License
-------
Copyright © 2023 Victor Reyes. (MIT License)  

Permission is hereby granted, free of charge, to any person obtaining a copy of
this software and associated documentation files (the "Software"), to deal in
the Software without restriction, including without limitation the rights to
use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
the Software, and to permit persons to whom the Software is furnished to do so,
subject to the following conditions:

* The above copyright notice and this permission notice shall be included in
  all copies or substantial portions of the Software.

* The Software is provided "as is", without warranty of any kind, express or
  implied, including but not limited to the warranties of merchantability,
  fitness for a particular purpose and noninfringement. In no event shall the
  authors or copyright holders be liable for any claim, damages or other
  liability, whether in an action of contract, tort or otherwise, arising from,
  out of or in connection with the Software or the use or other dealings in the
  Software.