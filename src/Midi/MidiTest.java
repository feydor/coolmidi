package Midi;

import Midi.exceptions.MidiParseException;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class MidiTest {

    String testFile = "bowser_1.mid";

    @Test
    void midiHeaderParseWorks() throws IOException {
        Midi midi = new Midi(testFile);

        assertEquals(MidiIdentifier.MThd, midi.header.id);
        assertEquals(0x00000006, midi.header.len);
        assertEquals(MidiFileFormat.FORMAT_1, midi.header.format);
        assertEquals(0x000d, midi.header.ntracks);
        assertTrue(midi.header.useMetricalTiming);
        assertEquals(0x01e0, midi.header.tickdiv);
    }

    @Test
    void whenGivenWrongFilename_thenThrowsException() {
        assertThrows(MidiParseException.class, () -> new Midi("idk.txt"));
    }

    @Test
    void midiTrackParseWorks() throws IOException {
        Midi midi = new Midi(testFile);

        // Check that the # of tracks parsed is what was expected in the header
        assertEquals(midi.header.ntracks, midi.tracks.size());

        // Verify the # of events and bytes in each of the 13 tracks
        assertEquals(6, midi.tracks.get(0).events.size());
        assertEquals(45, midi.tracks.get(0).len);

        assertEquals(950, midi.tracks.get(1).events.size());
        assertEquals(3895, midi.tracks.get(1).len);

        assertEquals(696, midi.tracks.get(2).events.size());
        assertEquals(2988, midi.tracks.get(2).len);

        assertEquals(1104, midi.tracks.get(3).events.size());
        assertEquals(4620, midi.tracks.get(3).len);

        assertEquals(686, midi.tracks.get(4).events.size());
        assertEquals(2953, midi.tracks.get(4).len);

        assertEquals(194, midi.tracks.get(5).events.size());
        assertEquals(821, midi.tracks.get(5).len);

        assertEquals(1904, midi.tracks.get(6).events.size());
        assertEquals(8445, midi.tracks.get(6).len);

        assertEquals(2382, midi.tracks.get(7).events.size());
        assertEquals(10357, midi.tracks.get(7).len);

        assertEquals(493, midi.tracks.get(8).events.size());
        assertEquals(2076, midi.tracks.get(8).len);

        assertEquals(422, midi.tracks.get(9).events.size());
        assertEquals(1833, midi.tracks.get(9).len);

        assertEquals(3387, midi.tracks.get(10).events.size());
        assertEquals(14525, midi.tracks.get(10).len);

        assertEquals(390, midi.tracks.get(11).events.size());
        assertEquals(1759, midi.tracks.get(11).len);

        assertEquals(270, midi.tracks.get(12).events.size());
        assertEquals(1089, midi.tracks.get(12).len);

        // Verify the first track and it's first event
        var firstTrack = midi.tracks.get(0);
        assertEquals(MidiIdentifier.MTrk, firstTrack.id);

        // First event: Set Tempo (FF 51 03 tttttt(6 bytes, in ms per MIDI 1/4 note))
        var firstEvent = firstTrack.events.get(0);
        assertEquals(0, firstEvent.ticks);
        assertEquals(MidiEventType.SET_TEMPO, firstEvent.type);
        assertEquals(6, firstEvent.nbytes());
        assertEquals("FF5103061A80", firstEvent.message);

        // Verify that the last event in the first track is the End of Track event (0xff, 0x2f, 0x00)
        var lastEvent = firstTrack.events.get(firstTrack.events.size()-1);
        assertEquals(MidiEventType.META, lastEvent.type);
        assertEquals(3, lastEvent.nbytes());
        assertEquals("FF2F00", lastEvent.message);

        // TODO: Verify at least one of each type of event
        // Look through the events of the second track, these should be mostly MIDI events
        var secondTrack = midi.tracks.get(1);
        assertEquals("FF210100", secondTrack.events.get(0).message);
        assertEquals("FF200100", secondTrack.events.get(1).message);
        assertEquals("B00767", secondTrack.events.get(2).message);
        assertEquals("B00A40", secondTrack.events.get(3).message);
        assertEquals("C013", secondTrack.events.get(4).message);

        // Note events
        assertEquals("905164", secondTrack.events.get(5).message); // First Note On
        assertEquals(0, secondTrack.events.get(5).ticks);

        assertEquals("904564", secondTrack.events.get(6).message);
        assertEquals(0x0c, secondTrack.events.get(6).ticks);

        assertEquals("805150", secondTrack.events.get(7).message); // First Note Off
        assertEquals(0x023c, secondTrack.events.get(7).ticks);

        assertEquals("805150", secondTrack.events.get(7).message);
        assertEquals(0x023c, secondTrack.events.get(7).ticks);

        assertEquals("905464", secondTrack.events.get(8).message);
        assertEquals(0, secondTrack.events.get(8).ticks);

        assertEquals("804550", secondTrack.events.get(9).message);
        assertEquals(0x0c, secondTrack.events.get(9).ticks);

        assertEquals("904864", secondTrack.events.get(10).message);
        assertEquals("905764", secondTrack.events.get(11).message);
        assertEquals("805450", secondTrack.events.get(12).message);

        // test calculateDeltatime
        /**
         * public int calculateDeltatime(MidiChunk.Event event, int tempo) {
         *         if (header.useMetricalTiming) {
         *             double millisecondsPerTick = (((double)tempo / header.ticksPerQuarterNote()) / 1000);
         *             double timeInMs = event.ticks * millisecondsPerTick;
         *             System.out.printf("ticks=%d tempo=%d msPerTick=%f timeInMs=%f\n",
         *                     event.ticks, tempo, millisecondsPerTick, timeInMs);
         *             return (int)timeInMs;
         *         } else {
         *             return header.timecode().toMilliseconds();
         *         }
         *     }
         */

//        midi.eventDuration()
    }
}