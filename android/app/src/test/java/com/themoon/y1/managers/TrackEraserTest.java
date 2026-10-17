package com.themoon.y1.managers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;

public class TrackEraserTest {
    private static final Charset UTF8 = Charset.forName("UTF-8");

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File card;
    private File music;
    private File playlists;
    private File covers;

    @Before
    public void setUp() {
        card = new File(tmp.getRoot(), "card");
        music = new File(card, "Music");
        playlists = new File(card, "Y1_Playlists");
        covers = new File(card, "Y1_Covers");
        music.mkdirs();
        playlists.mkdirs();
        covers.mkdirs();
    }

    private File write(File f, String content) throws IOException {
        f.getParentFile().mkdirs();
        FileOutputStream o = new FileOutputStream(f);
        o.write(content.getBytes(UTF8));
        o.close();
        return f;
    }

    private String read(File f) throws IOException {
        FileInputStream in = new FileInputStream(f);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[1024];
        int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n);
        in.close();
        return new String(out.toByteArray(), UTF8);
    }

    @Test
    public void erasesTrackSidecarCoverAndEmptyDirs() throws Exception {
        File track = write(new File(music, "Artist/Album/01 Song.mp3"), "audio");
        File lrc = write(new File(music, "Artist/Album/01 Song.lrc"), "[00:01.00]x");
        File cover = write(new File(covers, "01 Song.jpg"), "img");
        TrackEraser.Result r = TrackEraser.erase(track, music, playlists, covers);
        assertTrue(r.ok);
        assertFalse(track.exists());
        assertFalse(lrc.exists());
        assertFalse(cover.exists());
        assertEquals(3, r.filesDeleted);
        assertEquals(2, r.dirsRemoved);
        assertFalse(new File(music, "Artist").exists());
        assertTrue("Music itself must stay", music.isDirectory());
    }

    @Test
    public void keepsNonEmptyParentAndSiblings() throws Exception {
        File track = write(new File(music, "A/Al/01.mp3"), "a");
        File other = write(new File(music, "A/Al/02.mp3"), "b");
        TrackEraser.Result r = TrackEraser.erase(track, music, playlists, covers);
        assertTrue(r.ok);
        assertTrue(other.exists());
        assertEquals(0, r.dirsRemoved);
    }

    @Test
    public void refusesOutsideMusicAndDeletesNothing() throws Exception {
        File book = write(new File(card, "Books/x.mp3"), "b");
        File sibling = write(new File(card, "Music2/x.mp3"), "b");
        assertFalse(TrackEraser.erase(book, music, playlists, covers).ok);
        assertFalse(TrackEraser.erase(sibling, music, playlists, covers).ok);
        assertTrue(book.exists());
        assertTrue(sibling.exists());
    }

    @Test
    public void refusesDotDotEscape() throws Exception {
        File victim = write(new File(card, "Books/x.mp3"), "b");
        File sneaky = new File(music, "../Books/x.mp3");
        assertFalse(TrackEraser.erase(sneaky, music, playlists, covers).ok);
        assertTrue(victim.exists());
    }

    @Test
    public void refusesDirectoryAndMissingFileAndRoot() throws Exception {
        File dir = new File(music, "Artist");
        dir.mkdirs();
        write(new File(dir, "t.mp3"), "x");
        assertFalse(TrackEraser.erase(dir, music, playlists, covers).ok);
        assertFalse(TrackEraser.erase(new File(music, "gone.mp3"), music, playlists, covers).ok);
        assertFalse(TrackEraser.erase(music, music, playlists, covers).ok);
        assertTrue(new File(dir, "t.mp3").exists());
    }

    @Test
    public void dropsTrackAndExtinfFromPlaylistsKeepingRestByteForByte() throws Exception {
        File track = write(new File(music, "A/Al/01 Song.mp3"), "a");
        String abs = track.getAbsolutePath();
        String keepLines = "#EXTM3U\r\n#EXTINF:100,Keep\r\n" + music.getAbsolutePath() + "/B/keep.mp3\r\n";
        File pl = write(new File(playlists, "mine.m3u"), keepLines + "#EXTINF:200,Gone\r\n" + abs + "\r\n");
        File otg = write(new File(playlists, "On-The-Go.m3u8"),
                "#EXTM3U\n" + abs + "\n#EXTINF:1,Tail\n/x/tail.mp3");
        File untouched = write(new File(playlists, "other.m3u"), "#EXTM3U\n/a.mp3\n");
        long stamp = untouched.lastModified();
        File notList = write(new File(playlists, "notes.txt"), abs + "\n");
        TrackEraser.Result r = TrackEraser.erase(track, music, playlists, covers);
        assertTrue(r.ok);
        assertEquals(2, r.playlistsChanged);
        assertEquals(keepLines, read(pl));
        assertEquals("#EXTM3U\n#EXTINF:1,Tail\n/x/tail.mp3", read(otg));
        assertEquals("#EXTM3U\n/a.mp3\n", read(untouched));
        assertEquals(stamp, untouched.lastModified());
        assertEquals(abs + "\n", read(notList));
        assertFalse(new File(playlists, "mine.m3u.tmp").exists());
    }

    @Test
    public void acceptsPathRelativeToCardRootAndUtf8Names() throws Exception {
        File track = write(new File(music, "Группа/Альбом/Песня.mp3"), "a");
        File pl = write(new File(playlists, "r.m3u"),
                "#EXTM3U\n#EXTINF:5,Песня\nMusic/Группа/Альбом/Песня.mp3\n#EXTINF:6,Другая\nMusic/Z/z.mp3\n");
        assertTrue(TrackEraser.erase(track, music, playlists, covers).ok);
        assertEquals("#EXTM3U\n#EXTINF:6,Другая\nMusic/Z/z.mp3\n", read(pl));
    }

    @Test
    public void noPlaylistsDirIsFine() throws Exception {
        File track = write(new File(music, "t.mp3"), "a");
        TrackEraser.Result r = TrackEraser.erase(track, music, new File(card, "nope"), new File(card, "nope2"));
        assertTrue(r.ok);
        assertEquals(0, r.playlistsChanged);
    }
}
