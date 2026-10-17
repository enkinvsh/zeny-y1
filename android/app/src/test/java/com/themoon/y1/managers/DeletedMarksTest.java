package com.themoon.y1.managers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.Charset;
import java.util.List;

public class DeletedMarksTest {
    private static final Charset UTF8 = Charset.forName("UTF-8");

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void addDedupesByPathLatestWins() {
        DeletedMarks m = new DeletedMarks();
        m.add("Music/a.mp3", "A", "One", 1);
        m.add("Music/b.mp3", "B", "Two", 2);
        m.add("Music/a.mp3", "A2", "One2", 3);
        List<DeletedMarks.Item> items = m.items();
        assertEquals(2, items.size());
        assertEquals("Music/b.mp3", items.get(0).path);
        assertEquals("A2", items.get(1).artist);
        assertEquals(3, items.get(1).at);
    }

    @Test
    public void removeDropsEntry() {
        DeletedMarks m = new DeletedMarks();
        m.add("Music/a.mp3", "A", "One", 1);
        assertTrue(m.remove("Music/a.mp3"));
        assertFalse(m.remove("Music/a.mp3"));
        assertEquals(0, m.size());
    }

    @Test
    public void jsonRoundTripKeepsFormat() {
        DeletedMarks m = new DeletedMarks();
        m.add("Music/Artist/Album/01 Title.opus", "A\"x", "Тест", 1760000000000L);
        String json = m.toJson();
        assertTrue(json.startsWith("{\"v\":1,\"items\":[{\"path\":\"Music/Artist/Album/01 Title.opus\""));
        DeletedMarks back = DeletedMarks.parse(json);
        assertFalse(back.isCorrupt());
        assertEquals(1, back.size());
        assertEquals("A\"x", back.items().get(0).artist);
        assertEquals("Тест", back.items().get(0).title);
        assertEquals(1760000000000L, back.items().get(0).at);
    }

    @Test
    public void garbageGivesEmptyAndCorrupt() {
        for (String s : new String[] {"not json", "[1,2]", "{\"items\": 5}", "{\"items\": [", "   "}) {
            DeletedMarks m = DeletedMarks.parse(s);
            assertEquals(s, 0, m.size());
            assertTrue(s, m.isCorrupt());
        }
    }

    @Test
    public void emptyOrNullIsNotCorrupt() {
        assertFalse(DeletedMarks.parse("").isCorrupt());
        assertFalse(DeletedMarks.parse(null).isCorrupt());
    }

    @Test
    public void badItemsSkippedGoodKept() {
        DeletedMarks m = DeletedMarks.parse("{\"v\":1,\"items\":[5,{\"title\":\"x\"},{\"path\":\"Music/a.mp3\"}]}");
        assertEquals(1, m.size());
        assertEquals("", m.items().get(0).artist);
    }

    @Test
    public void unknownFieldsSurviveRoundTrip() {
        DeletedMarks m = DeletedMarks.parse(
                "{\"v\":1,\"note\":\"hi\",\"items\":[{\"path\":\"Music/a.mp3\",\"artist\":\"A\",\"title\":\"T\",\"at\":5,\"src\":\"pc\"}]}");
        String json = m.toJson();
        assertTrue(json, json.contains("\"src\":\"pc\""));
        assertTrue(json, json.contains("\"note\":\"hi\""));
    }

    @Test
    public void saveIsAtomicAndCreatesDir() throws Exception {
        File f = new File(tmp.getRoot(), "Y1_Deleted/deleted.json");
        DeletedMarks m = new DeletedMarks();
        m.add("Music/a.mp3", "A", "T", 1);
        m.save(f);
        assertTrue(f.isFile());
        assertFalse(new File(f.getParentFile(), "deleted.json.tmp").exists());
        assertEquals(1, DeletedMarks.load(f).size());
    }

    @Test
    public void corruptFileIsBackedUpBeforeOverwrite() throws Exception {
        File f = new File(tmp.getRoot(), "deleted.json");
        FileOutputStream out = new FileOutputStream(f);
        out.write("{broken".getBytes(UTF8));
        out.close();
        DeletedMarks m = DeletedMarks.load(f);
        assertTrue(m.isCorrupt());
        m.add("Music/a.mp3", "A", "T", 1);
        m.save(f);
        File bak = new File(tmp.getRoot(), "deleted.json.bak");
        assertTrue(bak.isFile());
        assertEquals(7, bak.length());
        assertEquals(1, DeletedMarks.load(f).size());
        assertFalse(m.isCorrupt());
    }

    @Test
    public void missingFileLoadsEmpty() {
        DeletedMarks m = DeletedMarks.load(new File(tmp.getRoot(), "nope.json"));
        assertEquals(0, m.size());
        assertFalse(m.isCorrupt());
    }
}
