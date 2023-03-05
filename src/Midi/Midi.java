package Midi;

import Midi.exceptions.MidiInvalidHeaderException;
import Midi.exceptions.MidiParseException;

import javax.sound.midi.InvalidMidiDataException;
import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

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
    public String filename;
    public MidiChunk.Header header;
    public List<MidiChunk.Track> tracks = new ArrayList<>();
    private static final String END_OF_TRACK = "FF2f00";

    /**
     * Parses the Midi file
     * @param filename The file to parse
     * @throws MidiParseException When the file is unparsable
     */
    public Midi(String filename) throws IOException {
        parseMidiFile(filename);
    }

    private void parseMidiFile(String filename) throws IOException {
        String[] partsOfFilename = filename.split("\\.");
        boolean isMidiFile = partsOfFilename.length == 2 && partsOfFilename[1].equalsIgnoreCase("MID");
        if (!isMidiFile) {
            throw new MidiParseException("Not a midi file!: " + filename);
        }
        this.filename = filename;


        FileInputStream filestream;
        BufferedInputStream file;
        try {
            filestream = new FileInputStream(filename);
            file = new BufferedInputStream(filestream);
        } catch (FileNotFoundException ex) {
            throw new MidiParseException("File not found!: " + filename);
        }

        System.out.println("Starting to parse " + filename);

        // Now start parsing the Header chunk
        // <Header> = <ident:4B> <len:4B> <format:2B> <ntracks:2B> <tickdiv:2B>
        byte[] chunkId = file.readNBytes(4);
        int chunklen = ByteFns.toUnsignedInt(file.readNBytes(4));
        short format = ByteFns.toUnsignedShort(file.readNBytes(2));
        short ntracks = ByteFns.toUnsignedShort(file.readNBytes(2));
        byte[] tickdiv = file.readNBytes(2);
        header = new MidiChunk.Header(chunkId, chunklen, format, ntracks, tickdiv);

        // Now since we know the # of tracks, we can start parsing the tracks and their events
        for (int i = 0; i < ntracks; ++i) {
            // <Track> = <header <id:4B> <chunklen:4B>> <events:1+ (see parseMidiTrack)>
            byte[] id = file.readNBytes(4);
            int len = ByteFns.toUnsignedInt(file.readNBytes(4));
            var parsedTrack = parseMidiTrack(file, id, len, i);

            // The bytes read must equal the track len
            if (parsedTrack.len != len) {
                throw new MidiParseException("FUCKED UP parsing a track: ntrack="
                        + i + " id=" + Arrays.toString(id) + " parsedTrack.len=" + parsedTrack.len);
            }

            tracks.add(parsedTrack.track);
        }

        if (file.available() > 1) {
            System.out.println(Arrays.toString(file.readAllBytes()));
            throw new MidiParseException("We didn't reach the EoF and apparently there is still some bytes " +
                    "left even after going through all of the ntracks. So here's everything that's left: " +
                    Arrays.toString(file.readAllBytes()));
        }

        System.out.println("Finished parsing " + filename);
        file.close();
    }

    /**
     * Calculates the event's duration
     * @param event The event to get the duration of
     * @param tempo In microseconds per quarter note
     * @return The event'd duration in milliseconds
     */
    public int eventDurationInMs(MidiChunk.Event event, int tempo) {
        if (header.useMetricalTiming) {
            int ticksPerQuarterNote = (header.tickdiv & 0xFF) & 0x7F;
            double millisecondsPerTick = (((double)tempo / ticksPerQuarterNote) / 1000);
            double timeInMs = event.ticks * millisecondsPerTick;
            return (int)timeInMs;
        } else {
            int fps = (header.tickdiv >> 8) & 0xFF;
            if (Stream.of(24, 25, 29, 30).noneMatch(n -> n == fps)) {
                throw new RuntimeException("FPS was not a valid SMPTE option: " + fps);
            }

            int subdivsPerFrame = (header.tickdiv & 0x00FF);
            return (fps * subdivsPerFrame) / 1000;
        }
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
    private MidiTrackParseResult parseMidiTrack(BufferedInputStream file, byte[] id, int len, int trackNum) throws IOException {
        boolean isTrackChunk = Arrays.equals(id, MidiIdentifier.MTrk.id);
        if (!isTrackChunk) {
            throw new MidiParseException("FUCKED UP parsing a track header! id=" + Arrays.toString(id));
        }

        // Now time for event parsing
        // This is where the real *fun* begins
        // TODO: Going to be gay here and only parse Format_1 MIDI files
        if (header.format == MidiFileFormat.FORMAT_0 || header.format == MidiFileFormat.FORMAT_2) {
            throw new MidiParseException("You tried to play format 0 or 2 MIDI. But I'm to lazy to parse those right now so eh.");
        }

        // Format 1 specifics:
        // - track #1 is a global tempo track, evey timing related event MUST be here
        // - tracks #2+ can ONLY be note data tracks

        int bytesRead = 0;
        var events = new ArrayList<MidiChunk.Event>();
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
            VarLenQuant dt = VarLenQuant.from(file);
            bytesRead += dt.nbytes;

            file.mark(len); // mark position to return to when creating the message
            int messageLen = 0; // the # of bytes in the message

            // Look at first byte of the event (the status byte) to determine the type
            short status = (short) (file.readNBytes(1)[0] & 0xFF); // to unsigned byte
            messageLen++;
            MidiEventType eventType = MidiEventType.fromStatusByte(status);
            MidiEventSubType subType = MidiEventSubType.UNKNOWN;

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
                            yield 6;
                        }
                        // 7-byte events
                        case 0x58 -> { // Time Signature FF 58 04 nn dd cc bb
                            file.skipNBytes(5);
                            yield 5;
                        }
                        // 6-byte events
                        case 0x51 -> { // Tempo FF 51 03 tt tt tt
                            file.skipNBytes(4);
                            yield 4;
                        }
                        // 5-byte events
                        case 0x00, 0x59 -> { // Sequence Number, Key Signature
                            file.skipNBytes(3);
                            yield 3;
                        }
                        // 4-byte events
                        // MIDI Channel Prefix
                        case 0x20, 0x21 -> { // MIDI Channel Prefix, MIDI Port
                            file.skipNBytes(2);
                            yield 2;
                        }
                        // 3-byte events
                        case 0x2F -> { // End of Track FF 2F 00
                            file.skipNBytes(1);
                            yield 1;
                        }
                        // Text events, varlen
                        case 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x7F -> {
                            var length = VarLenQuant.from(file);
                            file.skipNBytes(length.value);
                            yield length.value + length.nbytes;
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
                    boolean runningStatus = status < 120;

                    // Status byte is nibblised:
                    // Top nibble is the message type
                    // Lower nibble is the MIDI channel
                    byte messageType = (byte)((status >> 4) & 0xF);
                    subType = MidiEventSubType.fromStatusNibble(messageType);
                    messageLen += switch(messageType) {
                        // 3-byte messages
                        case 0x8, 0x9, 0xA, 0xB, 0xE: {
                            // TODO: Why is runningStatus always false (has to do withn signed integers in Java, probably)
                            // Does this mean that files that use running status will fuck up?
                            int nbyte = runningStatus ? 1 : 2;
                            file.skipNBytes(nbyte);
                            yield nbyte;
                        }
                        // 2-byte messages
                        case 0xC, 0xD: {
                            int nbyte = runningStatus ? 0 : 1;
                            file.skipNBytes(nbyte);
                            yield nbyte;
                        }
                        default: {
                            String msg = String.format("Unexpected MIDI message!\n" +
                                            "trackNum=%d, Status=%02X, messageType=%02X, bytesRead=%d, messageLen=%d",
                                    trackNum, status, messageType, bytesRead, messageLen);
                            throw new IllegalStateException(msg);
                        }
                    };
                }
                case SYSEX ->
                    // SysEx event:
                    // Complete message: <F0> <len:VarLen> <message:len B>
                    // TODO: I think I can just treat these like the varlen text events, same with 0xF7
                        throw new MidiParseException("SysEx events (0xF0 and 0xF7) are not implemented yet!");
                case UNKNOWN -> {
                    String msg = String.format("Unexpected MIDI message!\n" +
                                    "trackNum=%d, Status=%02X, bytesRead=%d, messageLen=%d",
                            trackNum, status, bytesRead, messageLen);
                    throw new IllegalStateException(msg);
                }
            }

            if (messageLen < 2) {
                throw new RuntimeException("Failed to update the message_len!");
            }

            // Reset to the point before the event data
            // Copy message_len bytes into a message buffer while advancing the filestream
            // filestream should be at the end of the event
            file.reset();
            String message = ByteFns.toHex(file.readNBytes(messageLen));
            bytesRead += messageLen;

            // TODO: Remove, this is for debugging
            if (subType == MidiEventSubType.SET_TEMPO) {
                System.out.println("Tempo msg: " + message);
            }

            events.add(new MidiChunk.Event(eventType, subType, dt.value, message));
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
            if (timingRelatedEvents.size() > 0) {
                String msg = String.format("In a format 0 Midi file, track#2+ must NOT have any timing rleated Meta Events," +
                        "but timing related Meta events were encountered!\n" +
                        "track#=%d, bytesRead=%d, timing related events=%s", trackNum, bytesRead, timingRelatedEvents);
                throw new MidiParseException(msg);
            }
        }

        var track = new MidiChunk.Track(id, len, events);
        return new MidiTrackParseResult(track, bytesRead);
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
            public MidiIdentifier id;
            public int len;
            public MidiFileFormat format;
            public short ntracks;
            public int tickdiv;
            public boolean useMetricalTiming;

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
                    throw new MidiInvalidHeaderException("A Midi Header chunk's identifier must be the ASCII characters 'MThd'! Given: " + Arrays.toString(id));
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

                // Based on the MSB of tickdiv, set useMetricalTiming
                var msbSet = ((tickdiv[0] >> 7) & 0x0001) == 1;

                this.id = MidiIdentifier.MThd;
                this.len = len;
                this.format = validatedFormat.get();
                this.ntracks = ntracks;
                this.tickdiv = ByteFns.toUnsignedShort(tickdiv);
                this.useMetricalTiming = !msbSet;
            }
        }

        /** A MidiTrack is a sequence of time-ordered events. */
        final class Track implements MidiChunk {
            public MidiIdentifier id;
            public int len;
            public List<Event> events;

            /**
             * Constructs and validates a Midi Track
             * @param id The first four bytes of a chunk identify it. Must be "MTrk".
             * @param len The number of bytes in this chunk (excludes the id bytes)
             * @param events Each event has a delta-time associated with it,
             *               meaning the amount of time since the previous event (in tickdiv units)
             * @throws MidiInvalidHeaderException When attempting to construct an invalid Midi Track
             */
            public Track(
                    byte[] id,
                    int len,
                    List<Event> events
            ) {
                boolean isTrackChunk = Arrays.equals(id, MidiIdentifier.MTrk.id);
                if (!isTrackChunk) {
                    throw new MidiInvalidHeaderException("A Midi Track chunk's identifier must be the ASCII characters 'MTrk'! Given: " + Arrays.toString(id));
                }

                if (len < 0) {
                    throw new MidiInvalidHeaderException("A Midi Track's len must be greater than 0. Given: " + len);
                }

                // TODO: More error checking here

                this.id = MidiIdentifier.MTrk;
                this.len = len;
                this.events = events;
            }
        }

        record MetaEventParseResult(int type, byte[] data, int len) {}
        record NormalMidiEventParseResult(int status, int data1, int data2) {}
        record ChannelMidiEventParseResult(int cmd, int channel, int data1) {}

        class Event {
            /** One of the three main types of Events: MIDI, Meta, or Sysex */
            public MidiEventType type;

            /** Specifies the specific event */
            public MidiEventSubType subType;

            /** The duration of the event in ticks */
            public int ticks;

            /** The event's bytes */
            public String message;

            /**
             * A MidiEvent consists of:
             * @param type The type of event (MIDI, SYSEX, or META)
             * @param ticks a variable length quantity (1 - 4 bytes) denoting the time since the last event
             * @param message 2 or more bytes describing the event itself
             */
            public Event(MidiEventType type, MidiEventSubType subType, int ticks, String message) {
                this.type = type;
                this.subType = subType;
                this.ticks = ticks;
                this.message = message;
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
                    throw new IllegalStateException("Attempting to parse a NON Meta event as a Meta event! " + type);
                }

                byte[] data = ByteFns.fromHex(message.substring(2));

                if (subType == MidiEventSubType.SET_TEMPO) {
                    // The data is three bytes from the upper byte
                    data = ByteFns.fromHex(message.substring(6));
                    if (data.length != 3) {
                        throw new RuntimeException("WTF should be three bytes: " + Arrays.toString(data));
                    }
                }

                return new MetaEventParseResult(subType.idByte, data, data.length);
            }

            public NormalMidiEventParseResult parseAsNormalMidiEvent() {
                if (type != MidiEventType.MIDI) {
                    throw new IllegalStateException("Attempting to parse a NON Midi event as a Midi event! " + message);
                }
                if (subType.isTwoByteChannelType()) {
                    throw new IllegalArgumentException("Attempting to parse a 2-bute channel Midi event as a 3-byte normal Midi event: " + message);
                }

                int status = Integer.parseUnsignedInt(message.substring(0, 2), 16);
                int data1 = Integer.parseUnsignedInt(message.substring(2, 4), 16);
                int data2 = Integer.parseUnsignedInt(message.substring(4, 6), 16);
                return new NormalMidiEventParseResult(status, data1, data2);
            }

            public ChannelMidiEventParseResult parseAsChannelMidiEvent() {
                if (type != MidiEventType.MIDI) {
                    throw new IllegalStateException("Attempting to parse a NON Midi event as a Midi event! " + type);
                }
                if (!subType.isTwoByteChannelType() || nbytes() != 2) {
                    throw new RuntimeException("Channel Midi Event is fucked: " + message);
                }

                int cmd = Integer.parseUnsignedInt(message.substring(0, 2), 16);
                int channel = (cmd & 0xF);
                int data1 = Integer.parseUnsignedInt(message.substring(2, 4), 16);
                return new ChannelMidiEventParseResult(cmd, channel, data1);
            }
        }
    }
}

/**
 * There are three types of Midi File formats:
 * <ul>
 *     <li>0: a single multi-channel track</li>
 *     <li>1: two or more tracks all played simultaneously</li>
 *     <li>2: one or more tracks played independently</li>
 * </ul>
 */
enum MidiFileFormat {
    /** Single multi-channel track */
    FORMAT_0((short)0),

    /**
     * The most common format in MIDI.
     * Two or more track chunks (header.ntracks) to be played simultaneously:
     * <ul>
     *     <li>the first is the tempo track,</li>
     *     <li>the second is the note data</li>
     * </ul>
     */
    FORMAT_1((short)1),

    /** one or more Track chunks to be played independently */
    FORMAT_2((short)2);

    final short word;
    MidiFileFormat(short word) { this.word = word; }
}

/**
 * The 2 types of Midi Chunks
 */
enum MidiIdentifier {
    MThd("MThd".getBytes()),
    MTrk("MTrk".getBytes());

    final byte[] id;

    MidiIdentifier(byte[] id) {
        this.id = id;
    }
}

