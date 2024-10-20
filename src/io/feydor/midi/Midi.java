package io.feydor.midi;

import io.feydor.midi.exceptions.MidiInvalidHeaderException;
import io.feydor.midi.exceptions.MidiParseException;
import io.feydor.util.ByteFns;
import io.feydor.util.VarLenQuant;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * This class represents a parsed MIDI file.
 * <p>
 * A Midi file is a series of chunks:
 * <ul>
 *     <li>
 *         MThd: a Header chunk containing the file's meta-data
 *     </li>
 *     <li>
 *         MTrk: 1 or more Track chunks containing MIDI events
 *     </li>
 * </ul>
 */
public class Midi {
    public MidiChunk.Header header;
    private final CopyOnWriteArrayList<MidiChunk.Track> tracks = new CopyOnWriteArrayList<>();
    private final List<MidiChunk.Track> unmodifiableTracks;
    public final boolean[] channelsUsed = new boolean[16]; // channels 0-15 -> 1-16
    public final String filename;
    private static final String END_OF_TRACK = "FF2f00";
    private final boolean verbose;

    /**
     * Parses the Midi file
     * @param filename The file to parse
     * @throws MidiParseException When the file is unparsable
     */
    public Midi(String filename, boolean verbose) throws IOException {
        this.filename = filename;
        this.verbose = verbose;
        parseMidiFile(filename);
        unmodifiableTracks = Collections.unmodifiableList(tracks);
    }

    public Midi(String filename) throws IOException {
        this.filename = filename;
        this.verbose = true;
        parseMidiFile(filename);
        unmodifiableTracks = Collections.unmodifiableList(tracks);
    }

    private void parseMidiFile(String filename) throws IOException {
        FileInputStream filestream;
        BufferedInputStream file;
        try {
            filestream = new FileInputStream(filename);
            file = new BufferedInputStream(filestream);
        } catch (FileNotFoundException ex) {
            throw new MidiParseException("File not found!: " + filename);
        }

        logDebug("Starting to parse " + filename + "...");

        // Now start parsing the Header chunk
        header = MidiChunk.Header.readFrom(file);

        // Now since we know the # of tracks, we can start parsing the tracks and their events
        for (int i = 0; i < header.ntracks; ++i) {
            // <Track> = <header <id:4B> <chunklen:4B>> <events:1+ (see parseMidiTrack)>
            byte[] id = file.readNBytes(4);
            int len = ByteFns.toUnsignedInt(file.readNBytes(4));

            var parsedTrack = parseMidiTrack(file, id, len, i, channelsUsed);

            // The bytes read must equal the track len
            if (parsedTrack.len != len) {
                throw new MidiParseException("Messed up parsing a track: ntrack=" + i + " id=" + Arrays.toString(id) +
                        " parsedTrack.len=" + parsedTrack.len);
            }

            tracks.add(parsedTrack.track);
        }

        // In format 1, all tracks get their tempo from the first global tempo track
        // In format 1, all tracks get their time signature from the first global tempo track
        if (header.format == MidiFileFormat.FORMAT_1) {
            int firstTrackTempo = tracks.get(0).tempo;
            var firstTimeSig = tracks.get(0).timeSignature;
            logDebug("MIDI Format 1: Aligning all track tempos with the first global tempo track (%d) ...\n", firstTrackTempo);
            logDebug("MIDI Format 1: Aligning all track time signatures with the first global tempo track (%s) ...\n", firstTimeSig);

            for (var track : tracks) {
                track.tempo = firstTrackTempo;
                track.timeSignature = firstTimeSig;
            }
        }

        logDebug("Printing the MIDI header: " + header);

        if (file.available() > 1) {
            byte[] remaining = file.readAllBytes();
            logDebug("WARN: We didn't reach the EoF and apparently there is still some bytes left over after track parsing. "
                    + "So here's the rest of the bytes: " + ByteFns.toHex(remaining));
        }

        logDebug("Finished parsing " + filename);
        file.close();
    }

    public void updateGlobalTempo(int newTempo) {
        if (newTempo < 1) {
            throw new IllegalArgumentException("newTempo must be greater than 0: newTempo=" + newTempo);
        }
        if (header.format != MidiFileFormat.FORMAT_1) {
            throw new IllegalStateException("Only Format 1 MIDI files have a global tempo");
        }

        synchronized (this) {
            tracks.get(0).setTempo(newTempo);
        }
    }

    /**
     * Current tempo for the MIDI file. Only makes sense if this file is a format 1 header.
     * In that case, the global tempo is the tempo set by the first track.
     * @throws IllegalStateException When this MIDI files is not in format 1
     */
    public int getGlobalTempo() {
        if (header.format != MidiFileFormat.FORMAT_1) {
            throw new IllegalStateException("Only Format 1 MIDI files have a global tempo");
        }

        return tracks.get(0).getTempo();
    }

    /**
     * Current milliseconds per tick for the MIDI file
     * @throws IllegalStateException When this MIDI files is not in format 1
     */
    public double msPerTick() {
        if (header.format != MidiFileFormat.FORMAT_1) {
            throw new IllegalStateException("Only Format 1 MIDI files have a global tempo");
        }

        if (!header.useTicksPerBeatTimeDiv)
            throw new UnsupportedOperationException("Cannot yet schedule MIDIs with \"frames per second\" time division");

        return (tracks.get(0).getTempo() / (double)header.tickdiv) / 1000.0;
    }

    /**
     * Current BPM for this MIDI file
     */
    public int bpm() {
        // TODO: Assuming format 1 (track 0 has global tempo)
        return 60_000_000 / tracks.get(0).getTempo();
    }

    /**
     * Current microseconds per tick for the MIDI file
     * @throws IllegalStateException When this MIDI files is not in format 1
     */
    public double microsPerTick() {
        if (header.format != MidiFileFormat.FORMAT_1) {
            throw new IllegalStateException("Only Format 1 MIDI files have a global tempo");
        }

        if (!header.useTicksPerBeatTimeDiv)
            throw new UnsupportedOperationException("Cannot yet schedule MIDIs with \"frames per second\" time division");

        return tracks.get(0).getTempo() / (double)header.tickdiv;
    }


    /** Returns an unmodifiable view of the tracks */
    public List<MidiChunk.Track> getTracks() {
        return unmodifiableTracks;
    }

    public int numTracks() {
        return tracks.size();
    }

    /** The result of parseMidiTrack: the parsed track and the # of bytes read */
    private record MidiTrackParseResult(MidiChunk.Track track, int len) {}

    /**
     * Parse a Midi Track chunk
     * @param file the filestream to read from
     * @param id the id bytes parsed from the Track Chunk header
     * @param len the # of bytes in the Track Chunk, parsed from the header
     * @return the parsed track and the # of bytes read
     */
    private MidiTrackParseResult parseMidiTrack(BufferedInputStream file, byte[] id, int len, int trackNum,
                                                boolean[] channelUsed) throws IOException {
        boolean isTrackChunk = Arrays.equals(id, MidiIdentifier.MTrk.id);
        if (!isTrackChunk) {
            throw new MidiParseException("Messed up parsing a track header! id=" + Arrays.toString(id));
        }

        // Now time for event parsing
        // This is where the real *fun* begins
        // TODO: Skipping format 2 midis for now, though they should work as is
        // NOTE: In format 2, each MTrk should begin with at least one initial tempob(and time signature) event
        if (header.format == MidiFileFormat.FORMAT_2) {
            throw new MidiParseException("You tried to play format 2 MIDI. But I'm to lazy to parse those right now so eh.");
        }

        // Format 1 specifics:
        // - track #1 is a global tempo track, evey timing related event MUST be here
        // - tracks #2+ can ONLY be note data tracks

        int bytesRead = 0;
        var events = new ArrayList<MidiChunk.Event>();
        MidiChunk.Event prevEvent = null; // Used to check for running status
        short prevStatus = 0;
        int tempo = 0; boolean tempoSet = false;
        MidiChunk.TimeSignature timeSignature = null; boolean timeSignatureSet = false;
        while (bytesRead < len) {
            // The overall strategy here is this:
            // For each event:
            // 1. Read the mandatory VarLen delta-time
            // 2. Save the position of the filestream which is at the first byte of the event data.
            // 3. Read the first byte of the data and determine the event type
            // 4. Advance the filestream however many bytes the specific event takes
            // 5. At the end, reset the filestream to be at the first byte of the event
            // 6. Read messageLen bytes to save the event & advance the filestream to the end of the event

            // Format: <MTrk chunk> = <delta-time:VarLen(1-4B)><event:(2+ B)>
            // Note: Delta-time is associated with an event
            VarLenQuant dt = VarLenQuant.readBytes(file);
            bytesRead += dt.nbytes;

            file.mark(len); // mark position to return to when creating the message
            int messageLen = 0; // the # of bytes in the message

            // Look at first byte of the event (the status byte) to determine the type
            short status = ByteFns.toUnsignedShort(file.readNBytes(1)); // to unsigned byte
            messageLen++;
            var pair = MidiEventType.fromStatusByte(status, prevEvent);
            MidiEventType eventType = pair.first();
            boolean runningStatus = pair.second();
            if (runningStatus) {
                status = prevStatus;
            }

            MidiEventSubType subType = MidiEventSubType.UNKNOWN;
            int dataStart = 0;
            int dataLen = 0;
            switch (eventType) {
                case META -> {
                    // Meta-Event: <FF:1B> <type:1B> <len:Varlen><data:len B>
                    short type = (short) (file.readNBytes(1)[0] & 0xFF);
                    messageLen++;
                    subType = MidiEventSubType.fromTypeByte(type);
                    messageLen += switch (type) {
                        // 8-byte events
                        case 0x54 -> { // SMPTE Offset FF 54 05 hr mn se fr ff
                            file.skipNBytes(6);
                            dataStart = 3;
                            dataLen = 5;
                            yield 6;
                        }
                        // 7-byte events
                        case 0x58 -> { // Time Signature FF 58 04 nn dd cc bb
                            file.skipNBytes(1); // skip the 04
                            byte[] timeSig = file.readNBytes(4);
                            if ((timeSig[3] & 0xFF) != 0x08) {
                                logDebug("WARN: A Time Signature event (%s) is specifying a # of 32nd notes in a" +
                                                "MIDI quarter-note (%02x) that is NOT supported by my parser, for now...\n",
                                        "FF5804" + ByteFns.toHex(timeSig), timeSig[3] & 0xFF);
                            }

                            // TODO: Dynamically change time signature at runtime in MidiScheduler
                            // For now, just save the first time signature event encountered
                            var newTimeSig = new MidiChunk.TimeSignature(timeSig[0] & 0xFF, timeSig[1] & 0xFF,
                                    timeSig[2] & 0xFF, timeSig[3] & 0xFF);
                            if (timeSignatureSet) {
                                logWarn("The time signature for track#%d has already been set! Skipping... "
                                                + "OLD=%s NEW=%s\n", trackNum, timeSignature, newTimeSig);
                            } else {
                                timeSignature = newTimeSig;
                                timeSignatureSet = true;
                                logDebug(String.format("New time signature detected: bytes=FF5804%s varlen_dt=%d parsed=%s",
                                        ByteFns.toHex(timeSig), dt.value, newTimeSig));
                            }

                            dataStart = 3;
                            dataLen = 4;
                            yield 5;
                        }
                        // 6-byte events
                        case 0x51 -> { // Tempo FF 51 03 tt tt tt
                            file.skipNBytes(1); // skip the 03
                            int newTempo = ByteFns.toUnsignedInt(file.readNBytes(3));
                            // Setting the tempo of the track (and for format_1 all of the tracks) as the first SET_TEMPO event encountered
                            // TODO: This is arbitrary but I will need to come up with a way to set each track'scurrent tempo dynamically at runtime
                            if (tempoSet) {
                                logDebug("WARN: The tempo for track#%d has already been set! OLD=%d NEW=%d Skipping change...\n",
                                        trackNum, tempo, newTempo);
                            } else {
                                tempo = newTempo;
                                tempoSet = true;
                            }

                            dataStart = 3;
                            dataLen = 3;
                            yield 4;
                        }
                        // 5-byte events
                        case 0x00, 0x59 -> { // Sequence Number (FF 00 02 ss ss),
                                             // Key Signature (FF 59 02 sf mi)
                            file.skipNBytes(3);
                            dataStart = 3;
                            dataLen = 2;
                            yield 3;
                        }
                        // 4-byte events
                        // MIDI Channel Prefix
                        case 0x20, 0x21 -> { // MIDI Channel Prefix (FF 20 01 cc), MIDI Port
                            file.skipNBytes(2);
                            dataStart = 3;
                            dataLen = 1;
                            yield 2;
                        }
                        // 3-byte events
                        case 0x2F -> { // End of Track FF 2F 00
                            file.skipNBytes(1);
                            dataStart = 2;
                            yield 1;
                        }
                        // Text events, varlen
                        case 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x7F -> {
                            var length = VarLenQuant.readBytes(file);
                            file.skipNBytes(length.value);
                            dataStart = 2 + length.nbytes; // exclude the varlen bytes from the actual data bytes
                            dataLen = length.value;
                            yield length.value + length.nbytes; // rest of message len (varlen + data bytes)
                        }
                        default -> throw new MidiParseException("Unknown Meta-Event encountered! status=" + status);
                    };
                }
                case MIDI -> {
                    // Midi events: <status:1B> <data:1 | 2 B>
                    // Special case: If the status byte is less than 0x80 (120),
                    // then running status in effect which means that
                    // this byte is actually the first data byte (the status is carried over from the previous event)
                    // Only can occur if the last event was also a MIDI event.

                    // Status byte is nibblised:
                    // Top nibble is the message type
                    // Lower nibble is the MIDI channel
                    byte messageType = (byte)((status >> 4) & 0xF);
                    byte channel = (byte)(status & 0xF);

                    subType = MidiEventSubType.fromStatusNibble(messageType);
                    messageLen += switch(messageType) {
                        // 3-byte messages
                        case 0x8, 0x9, 0xA, 0xB, 0xE : {
                            if (messageType != 0xB) {
                                // All messages except Controller
                                channelUsed[channel] = true;
                            }

                            int nbyte = runningStatus ? 1 : 2;
                            file.skipNBytes(nbyte);
                            dataStart = 1;
                            dataLen = 2;
                            yield nbyte;
                        }
                        // 2-byte messages
                        case 0xC, 0xD: {
                            int nbyte = runningStatus ? 0 : 1;
                            file.skipNBytes(nbyte);
                            dataStart = 1;
                            dataLen = 1;
                            yield nbyte;
                        }
                        default: {
                            String msg = String.format("Unexpected MIDI message!\n" +
                                            "trackNum=%d, Status=%02x, messageType=%02x, subType=%s, bytesRead=%d, messageLen=%d",
                                    trackNum, status, messageType, subType, bytesRead, messageLen);
                            logDebug(msg);
                            throw new IllegalStateException(msg);
                        }
                    };
                }
                case SYSEX -> {
                    // SysEx event:
                    // Complete message: <F0> <len:VarLen> <message:len B>
                    var length = VarLenQuant.readBytes(file);
                    file.skipNBytes(length.value);
                    dataStart = 1 + length.nbytes;
                    dataLen = length.value;
                    messageLen += length.value + length.nbytes;
                }
                case UNKNOWN -> {
                    String msg = String.format("Unexpected MIDI message!\n" +
                                    "trackNum=%d, Status=%02x, bytesRead=%d, messageLen=%d, prevEvent=%s",
                            trackNum, status, bytesRead, messageLen, prevEvent);
                    throw new IllegalStateException(msg);
                }
            }

            if (messageLen < 2 && !runningStatus) {
                throw new RuntimeException("Failed to update the non-runningStatus message_len! status=" + status);
            }

            // Reset to the point before the event data
            // Copy message_len bytes into a message buffer while advancing the filestream
            // filestream should be at the end of the event
            file.reset();
            String message = ByteFns.toHex(file.readNBytes(messageLen));
            bytesRead += messageLen;

            // Just absolutely fuck it, add the previous status to the runningStatus message
            // Yes, this negates the entire performance reason for having running status but it
            // makes my life so easier so eh
            if (runningStatus) {
                message = ByteFns.toHex((byte) prevStatus) + message;
            }

            var event = new MidiChunk.Event(eventType, subType, dt.value, dt.nbytes, message, runningStatus, dataStart, dataLen);
            prevEvent = event;
            prevStatus = status;
            events.add(event);
        }

        // Last event in each chunk MUST be End of Track
        if (!Midi.END_OF_TRACK.equalsIgnoreCase(events.get(events.size() - 1).message)) {
            String msg = String.format("The last event in track#%d was NOT the End of Track event (%s): %s",
                    trackNum, Midi.END_OF_TRACK, events.get(events.size() - 1).message);
            throw new MidiParseException(msg);
        }

        // Format_1 only: Track#2+ must NOT have any timing related Meta Events
        if (header.format == MidiFileFormat.FORMAT_1 && trackNum > 1) {
            var timingRelatedEvents = events.stream().filter(e -> e.subType.isTimingRelated()).toList();
            if (!timingRelatedEvents.isEmpty()) {
                String msg = String.format("WARN: In a format 1 Midi file, track#2+ must NOT have any timing related Meta Events," +
                        "but timing related Meta events were encountered!\n" +
                        "track#=%d, bytesRead=%d, timing related events=%s", trackNum, bytesRead, timingRelatedEvents);
                logDebug(msg);
            }
        }

        var track = new MidiChunk.Track(trackNum, id, len, events, tempo, timeSignature);
        return new MidiTrackParseResult(track, bytesRead);
    }

    public List<List<MidiChunk.Event>> eventsByDt() {
        return tracks.stream()
                .flatMap(track -> track.events.stream())
                .filter(event -> event.subType != MidiEventSubType.END_OF_TRACK)
                .collect(Collectors.groupingBy(event -> event.ticks, TreeMap::new, Collectors.toList()))
                .values()
                .stream()
                .toList();
    }

    /**
     * Converts every track's events relative delta-times into absolute times in milliseconds
     * @return Every event sorted by absolute time in milliseconds
     */
    public List<List<MidiChunk.Event>> allEventsInAbsoluteTime() {
        return updateAbsoluteTimes(-1).values().stream().toList();
    }

    public List<List<MidiChunk.Event>> allEventsInAbsoluteTime(long elapsedTime) {
        return updateAbsoluteTimes(elapsedTime).values().stream().toList();
    }

    /**
     * Converts every track's events relative delta-times into absolute times in milliseconds
     * @return Every event sorted by absolute time in milliseconds
     */
    public TreeMap<Double, List<MidiChunk.Event>> updateAbsoluteTimes(long elapsedTime) {
        // convert track events in absolute time and keep in same internal order
        // but group all by absolute time
        return tracks.stream()
                .flatMap(track -> track.eventsInAbsoluteTime2(header.tickdiv, elapsedTime).stream())
                .filter(event -> event.absoluteTime > elapsedTime)
                .filter(event -> event.subType != MidiEventSubType.END_OF_TRACK)
                .collect(Collectors.groupingBy(MidiChunk.Event::absoluteTime, TreeMap::new, Collectors.toList()));
    }

    /**
     * The hexadecimal contents in the same format as Unix hexdump/xdd
     */
    public String hexdump() {
        return header.hexdump() + tracks.stream().map(track -> track.hexdump()).collect(Collectors.joining(""));
    }

    /**
     * A MidiChunk has:
     * <p>
     * <ul>
     *     <li>
     *         an 8-byte header: <id - bytes 0-3><chunklen - bytes 4-7>
     *     </li>
     *     <li>
     *         two types: Header and Track chunks
     *     </li>
     * </ul>
     */
    public sealed interface MidiChunk {
        /** A MidiHeader represents the header for a MIDI file */
        final class Header implements MidiChunk {
            public final MidiIdentifier id;
            public final int len;
            public final MidiFileFormat format;
            public final short ntracks;
            /** 2 byte value representing the time division. See, {@link #useTicksPerBeatTimeDiv}*/
            public final int tickdiv;
            /** Used to interpret the tickdiv. If true then, tickdiv bytes represents "ticks per beat" ("per quarter note"). Otherwise they represent "frames per second" */
            public final boolean useTicksPerBeatTimeDiv;

            private static final Map<Integer, MidiFileFormat> VALID_FORMATS = Map.of(
                    0, MidiFileFormat.FORMAT_0,
                    1, MidiFileFormat.FORMAT_1,
                    2, MidiFileFormat.FORMAT_2
            );

            /**
             * Validate and construct a Midi Header
             * @param id The first four bytes of a chunk identify it. Must be "MThd".
             * @param len The number of bytes in this chunk (excludes the id bytes)
             * @param format One of the three possible Midi file formats
             * @param ntracks The number of track chunks in the file
             * @param tickdiv Specifies the timing interval to be used. Use metrical timing (Bar.Beat) otherwise use timecode (Hrs.Mins.Secs.Frames).
             *                With metrical timing, the timing interval is tempo related, otherwise it is in absolute time.
             *                If metrical timing is set, bits 0-14 of tickdivision indicates the # of sub-divisions of a quarter note.
             *                When timcode is set, the first byte specifies the FPS,
             *                and will be one of the SMPTE standards: 24, 25, 29, or 30.
             *                Meanwhile the second byte is the # of sub-divisions of a frame.
             * @throws MidiInvalidHeaderException When any of the parameters are invalid, see message for specifics
             */
            public Header(byte[] id,
                          int len,
                          short format,
                          short ntracks,
                          byte[] tickdiv) {
                boolean isMidiHeader = Arrays.equals(id, MidiIdentifier.MThd.id);
                if (!isMidiHeader) {
                    throw new MidiInvalidHeaderException("Not a MIDI File: A Midi Header chunk's identifier must be the ASCII characters 'MThd'! Given: " + Arrays.toString(id));
                }

                int BYTES_IN_MTHD = 6;
                if (len != BYTES_IN_MTHD) {
                    throw new MidiInvalidHeaderException("A Midi Header chunk must be " + BYTES_IN_MTHD + " bytes in size! Given: " + len);
                }

                var validatedFormat = VALID_FORMATS.entrySet().stream()
                                                          .filter(e -> e.getKey() == format)
                                                          .map(Map.Entry::getValue)
                                                          .findFirst();
                if (validatedFormat.isEmpty()) {
                    throw new MidiInvalidHeaderException("A Midi Header chunk must have a format of 0, 1 or 3! Given: " + format);
                }

                if (validatedFormat.get() == MidiFileFormat.FORMAT_0 && ntracks != 1) {
                    throw new MidiInvalidHeaderException("A format 0 Midi Header can only have 1 track! Given: " + ntracks);
                } else if (ntracks < 1) {
                    throw new MidiInvalidHeaderException("A format 1 or 2 Midi Header chunk must have more than have one or more tracks! Given: " + ntracks);
                }

                this.id = MidiIdentifier.MThd;
                this.len = len;
                this.format = validatedFormat.get();
                this.ntracks = ntracks;
                this.tickdiv = ByteFns.toUnsignedShort(tickdiv);
                this.useTicksPerBeatTimeDiv = ((tickdiv[0] >> 7) & 0x0001) == 0; // MSB of tickdiv determines time division method
            }

            /**
             * Reads the bytes that make up a MIDI header from the file
             * @param file an open file input stream
             * @return A MIDI Header
             * @throws IOException When EOF or other file reading exception is thrown.
             */
            public static Header readFrom(BufferedInputStream file) throws IOException {
                if (file.available() < 1) {
                    throw new IllegalArgumentException("The file input stream does not have any remaining bytes. file=" + file);
                }

                // <Header> = <ident:4B> <len:4B> <format:2B> <ntracks:2B> <tickdiv:2B>
                byte[] chunkId = file.readNBytes(4);
                int chunklen = ByteFns.toUnsignedInt(file.readNBytes(4));
                short format = ByteFns.toUnsignedShort(file.readNBytes(2));
                short ntracks = ByteFns.toUnsignedShort(file.readNBytes(2));
                byte[] tickdiv = file.readNBytes(2);
                return new MidiChunk.Header(chunkId, chunklen, format, ntracks, tickdiv);
            }

            /**
             * The hexadecimal contents in the same format as Unix hexdump
             */
            public String hexdump() {
                return ByteFns.toHex(id.getBytes())
                        + String.format("%08x", len)
                        + String.format("%04x", format.word)
                        + String.format("%04x", ntracks)
                        +String.format("%04x", tickdiv);
            }

            @Override
            public String toString() {
                return "Header{" +
                        "id=" + id +
                        ", len=" + len +
                        ", format=" + format +
                        ", ntracks=" + ntracks +
                        ", tickdiv=" + tickdiv +
                        ", useMetricalTiming=" + useTicksPerBeatTimeDiv +
                        '}';
            }
        }

        record TimeSignature(int numerator, int denominator, int clocksPerClick, int notated32ndNotesPerBeat) {}

        /** A MidiTrack is a sequence of time-ordered events. */
        final class Track implements MidiChunk {
            public final int trackNum;
            public final MidiIdentifier id;
            public final int len;
            public final List<Event> events;
            /** In microseconds per quarter-note */
            private volatile int tempo;
            public TimeSignature timeSignature;

            public synchronized int getTempo() { return tempo; }
            public void setTempo(int tempo) { this.tempo = tempo; }

            /**
             * Constructs and validates a Midi Track
             * @param id The first four bytes of a chunk identify it. Must be "MTrk".
             * @param len The number of bytes in this chunk (excludes the id bytes)
             * @param events Each event has a delta-time associated with it, meaning the amount of time since the previous event (in tickdiv units)
             * @throws MidiInvalidHeaderException When attempting to construct an invalid Midi Track
             */
            public Track(
                    int trackNum,
                    byte[] id,
                    int len,
                    List<Event> events,
                    int tempo,
                    TimeSignature timeSignature
            ) {
                boolean isTrackChunk = Arrays.equals(id, MidiIdentifier.MTrk.id);
                if (!isTrackChunk) {
                    throw new MidiInvalidHeaderException("A Midi Track chunk's identifier must be the ASCII characters 'MTrk'! Given: " + Arrays.toString(id));
                }

                if (len < 0) {
                    throw new MidiInvalidHeaderException("A Midi Track's len must be greater than 0. Given: " + len);
                }

                this.id = MidiIdentifier.MTrk;
                this.len = len;
                this.events = events;
                // Set the default tempo of 500,000 microseconds per beat (120 BPM)
                this.tempo = (tempo <= 0) ? 500_000 : tempo;
                // Set the default time signature of 4/4 with a metronome click every 1/4 note
                this.timeSignature = (timeSignature == null) ? new TimeSignature(4, 2, 24, 8)
                                                             : timeSignature;
                this.trackNum = trackNum;
            }

            public Track(Track o, List<Event> withEvents) {
                trackNum = o.trackNum;
                id = o.id;
                len = o.len;
                events = withEvents;
                tempo = o.tempo;
                timeSignature = o.timeSignature;
            }

            /**
             * Convert the track's events relative delta-times into absolute times in milliseconds
             * @param tickdiv Comes from the MIDI header
             * @return The track's events sorted by absolute time in milliseconds
             */
            public List<Event> updateAbsoluteTimes(int tickdiv) {
                double msPerTick = tempo / (double)tickdiv / 1000.0;

                int t = 0;
                List<Event> eventsSortedByAbsoluteTime = new ArrayList<>();
                for (var event : events) {
                    t += event.ticks;
                    event.absoluteTime = t * msPerTick;
                    eventsSortedByAbsoluteTime.add(event);
                }

                return eventsSortedByAbsoluteTime;
            }

            /** Does not modify the events. Makes a copy. */
            public List<Event> eventsInAbsoluteTime(int tickdiv, long elapsedTime) {
                double msPerTick = tempo / (double)tickdiv / 1000.0;

                long t = 0;
                List<Event> eventsCopy = new ArrayList<>(events);
                List<Event> eventsSortedByAbsoluteTime = new ArrayList<>();
                for (var event : eventsCopy) {
//                    if (event.absoluteTime < elapsedTime) continue;
                    t += event.ticks;
                    event.absoluteTime = t * msPerTick;
                    eventsSortedByAbsoluteTime.add(event);
                }

                return eventsSortedByAbsoluteTime;
            }

            /**
             * Does not modify the events. Makes a copy.
             * @param elapsedTime in milliseconds
             * @return events after elapsedTime with absolute times per track tempo
             */
            public List<Event> eventsInAbsoluteTime2(int tickdiv, long elapsedTime) {
                double msPerTick = tempo / (double)tickdiv / 1000.0;

                long ticks = 0;
//                List<Event> eventsCopy = new ArrayList<>(events);
                List<Event> eventsSortedByAbsoluteTime = new ArrayList<>();
                for (var event : events) {
                    ticks += event.ticks;
                    if (event.absoluteTime < elapsedTime) continue;

                    // ticks caught up to elapsedTime, rest of ticks use new tempo/msPerTick
//                    double newAbsoluteTime = event.absoluteTime +
                    event.absoluteTime = ticks * msPerTick;
                    eventsSortedByAbsoluteTime.add(event);
                }

                return eventsSortedByAbsoluteTime;
            }

            @Override
            public String toString() {
                return "Track{" +
                        "trackNum=" + trackNum +
                        ", id=" + id +
                        ", len=" + len +
                        ", tempo=" + tempo +
                        ", timeSignature=" + timeSignature +
                        '}';
            }

            /**
             * The hexadecimal contents in the same format as Unix hexdump
             */
            public String hexdump() {
                return ByteFns.toHex(id.getBytes())
                        + String.format("%08x", len)
                        + events.stream().map(event -> event.hexdump()).collect(Collectors.joining(""));
            }
        }

        record MetaEventParseResult(int type, byte[] data, int len) {}

        record ChannelMidiEventParseResult(int cmd, int channel, int data1, int data2) {}

        class Event {
            /** One of the three main types of Events: MIDI, Meta, or Sysex */
            public final MidiEventType type;

            /** Specifies the specific event */
            public final MidiEventSubType subType;

            /** The duration of the event in ticks */
            public final int ticks;

            /** The number of bytes used to store the ticks in the file. 1-4 bytes. */
            public final int tickBytes;

            /** The event's bytes */
            public final String message;

            /** If set, the message's status byte is the same the previous message and the receiver should assume it was the same as the last one. */
            public final boolean runningStatus;

            /** The byte where the data starts in the message */
            public final int dataStart;

            /** The byte length of the data in the message */
            public final int dataLen;

            /** Is not set until the track sets it */
            public double absoluteTime;

            /**
             * A MidiEvent consists of:
             * @param type The type of event (MIDI, SYSEX, or META)
             * @param ticks a variable length quantity (1 - 4 bytes) denoting the time since the last event
             * @param message 2 or more bytes describing the event itself
             */
            public Event(MidiEventType type, MidiEventSubType subType, int ticks, int tickBytes,
                         String message, boolean runningStatus, int dataStart, int dataLen) {
                if (tickBytes < 0 || tickBytes > 4) {
                    throw new IllegalArgumentException("The varlen for ticks must be between 1 and 4 bytes: tickBytes=" + tickBytes);
                }

                this.type = type;
                this.subType = subType;
                this.ticks = ticks;
                this.tickBytes = tickBytes;
                this.message = message;
                this.runningStatus = runningStatus;
                this.dataStart = dataStart;
                this.dataLen = dataLen;
            }

            public Event(Event other, int withTicks) {
                type = other.type;
                subType = other.subType;
                ticks = withTicks;
                tickBytes = other.tickBytes;
                message = other.message;
                runningStatus = other.runningStatus;
                dataStart = other.dataStart;
                dataLen = other.dataLen;
            }

            /** Returns the number of bytes represented in the message */
            public int nbytes() {
                return message.length() / 2;
            }

            /**
             * @throws IllegalStateException When the event is NOT a meta event
             * @return The type byte, the data, and the length of the message
             */
            public MetaEventParseResult parseAsMetaEvent() {
                if (type != MidiEventType.META) {
                    throw new IllegalStateException("Attempting to parse a NON Meta event as a Meta event! event=" + this);
                }
                byte[] msgBytes = ByteFns.fromHex(message);
                byte[] data = Arrays.copyOfRange(msgBytes, dataStart, msgBytes.length); // skip status + varlen

                // Only End of Track events have 0 dataLen
                if (data.length != dataLen && subType != MidiEventSubType.END_OF_TRACK) {
                    throw new MidiParseException("data.length !+ dataLen: " + Arrays.toString(data) + " " + dataLen);
                }

                return new MetaEventParseResult(subType.idByte, data, data.length);
            }

            public ChannelMidiEventParseResult parseAsChannelMidiEvent() {
                if (type != MidiEventType.MIDI) {
                    throw new IllegalStateException("Attempting to parse a NON Midi event as a Midi event! " + this);
                }
                if (!subType.isChannelType()) {
                    throw new RuntimeException("Attempting to parse a non-channel MIDI event! " + this);
                }

                int cmd = Integer.parseUnsignedInt(message.substring(0, 2), 16);
                int channel = (cmd & 0xF);
                int data1 = Integer.parseUnsignedInt(message.substring(2, 4), 16);
                int data2 = message.length() == 6 ? Integer.parseUnsignedInt(message.substring(4), 16)
                                                  : 0xDEADBEEF;
                return new ChannelMidiEventParseResult(cmd, channel, data1, data2);
            }

            public MetaEventParseResult parseAsSysexEvent() {
                if (type != MidiEventType.SYSEX) {
                    throw new IllegalStateException("Attempting to parse a NON SYSEX event as a SYSEX event! " + this);
                }

                byte[] msg = ByteFns.fromHex(message);
                byte[] data = Arrays.copyOfRange(msg, dataStart, msg.length);
                int status = Integer.parseUnsignedInt(message.substring(0, 2), 16);
                return new MetaEventParseResult(status, data, data.length);
            }

            @Override
            public String toString() {
                return "Event{" +
                        "type=" + type +
                        ", subType=" + subType +
                        ", ticks=" + ticks +
                        ", message='" + message + '\'' +
                        ", runningStatus=" + runningStatus +
                        ", dataStart=" + dataStart +
                        ", dataLen=" + dataLen +
                        ", absoluteTime=" + absoluteTime +
                        '}';
            }

            public double absoluteTime() {
                return absoluteTime;
            }

            public void setAbsoluteTime(double absoluteTime) {
                this.absoluteTime = absoluteTime;
            }

            /**
             * The hexadecimal contents in the same format as Unix hexdump
             */
            public String hexdump() {
                // running status means this event is using the previous event's status byte
                String msgBytes = runningStatus ? message.substring(2) : message;
                return VarLenQuant.encode(ticks) + msgBytes;
            }
        }
    }

    private void logDebug(String msg) {
        if (verbose) System.out.println("INFO: " + msg);
    }

    private void logDebug(String format, Object ... args) {
        if (verbose) System.out.printf("INFO: " + format, args);
    }

    private void logWarn(String format, Object ... args) {
        if (verbose) System.out.printf("WARN: " + format, args);
    }
}
