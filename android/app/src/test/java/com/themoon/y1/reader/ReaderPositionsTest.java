package com.themoon.y1.reader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;

import org.junit.Test;

public final class ReaderPositionsTest {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** Две главы по 50 уникальных слов: a0..a49 и b0..b49. */
    private static BookIndex book(String title, String firstPrefix, String secondPrefix) {
        BookBuilder b = new BookBuilder();
        b.meta(title, "Нора Вест");
        b.startChapter("Первая");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) sb.append(firstPrefix).append(i).append(' ');
        b.text(sb);
        b.startChapter("Вторая");
        sb.setLength(0);
        for (int i = 0; i < 50; i++) sb.append(secondPrefix).append(i).append(' ');
        b.text(sb);
        return b.build();
    }

    private static BookIndex book() {
        return book("Тихая гавань", "a", "b");
    }

    private static File card() throws IOException {
        return new File(Files.createTempDirectory("positions").toFile(), ".cache/positions.json");
    }

    private static ReaderPositions open(File f) {
        ReaderPositions p = new ReaderPositions(f);
        p.load();
        return p;
    }

    @Test
    public void positionSurvivesRestart() throws IOException {
        File f = card();
        BookIndex idx = book();
        ReaderPositions p = open(f);
        p.track("id1", idx, 70, 350, 1000, "/books/a.fb2");
        assertTrue(p.flushNow());

        ReaderPositions again = open(f);
        ReaderPositions.Position pos = again.resolve("id1", idx);
        assertEquals(70, pos.word);
        assertEquals(ReaderPositions.Position.EXACT, pos.source);
        ReaderPositions.Entry e = again.get("id1");
        assertEquals(350, e.wpm);
        assertEquals(1, e.chapter);
        assertEquals(20, e.wordInChapter);
        assertEquals(70, e.pct);
        assertEquals("Тихая гавань", e.title);
        assertEquals("/books/a.fb2", e.lastPath);
        assertEquals(1000, e.updatedMs);
    }

    @Test
    public void unknownBookResolvesToNull() throws IOException {
        assertNull(open(card()).resolve("none", book()));
    }

    @Test
    public void percentUsesFirstAsZeroAndLastAsHundred() throws IOException {
        ReaderPositions p = open(card());
        BookIndex idx = book();
        p.track("id", idx, 0, 300, 0, "p");
        assertEquals(0, p.get("id").pct);
        p.track("id", idx, 99, 300, 0, "p");
        assertEquals(100, p.get("id").pct);
    }

    @Test
    public void insertedWordsAtStartAreFoundByAnchor() throws IOException {
        File f = card();
        ReaderPositions p = open(f);
        p.track("id", book(), 30, 300, 0, "p");
        p.flushNow();

        BookBuilder b = new BookBuilder();
        b.startChapter("Предисловие");
        b.text("новое предисловие из пяти слов ");
        BookIndex old = book();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) sb.append('a').append(i).append(' ');
        b.startChapter("Первая");
        b.text(sb);
        sb.setLength(0);
        for (int i = 0; i < 50; i++) sb.append('b').append(i).append(' ');
        b.startChapter("Вторая");
        b.text(sb);
        BookIndex edited = b.build();

        ReaderPositions.Position pos = open(f).resolve("id", edited);
        assertEquals(ReaderPositions.Position.BY_ANCHOR, pos.source);
        assertEquals(35, pos.word);
        assertEquals("a30", new String(edited.text, edited.wordStart[pos.word], edited.wordLength(pos.word)));
        assertNotNull(old);
    }

    @Test
    public void rewrittenTextFallsBackToChapterAndOffset() throws IOException {
        File f = card();
        ReaderPositions p = open(f);
        p.track("id", book(), 70, 300, 0, "p");
        p.flushNow();
        BookIndex rewritten = book("Тихая гавань", "a", "y");
        ReaderPositions.Position pos = open(f).resolve("id", rewritten);
        assertEquals(ReaderPositions.Position.BY_CHAPTER, pos.source);
        assertEquals(70, pos.word);
    }

    @Test
    public void chapterFallbackStaysInsideChapter() throws IOException {
        File f = card();
        ReaderPositions p = open(f);
        p.track("id", book(), 95, 300, 0, "p");
        p.flushNow();
        BookBuilder b = new BookBuilder();
        b.startChapter("Первая");
        b.text("a b c d e f g h i j ");
        b.startChapter("Вторая");
        b.text("u v w x y z q r s t ");
        BookIndex shorter = b.build();
        ReaderPositions.Position pos = open(f).resolve("id", shorter);
        assertEquals(ReaderPositions.Position.BY_CHAPTER, pos.source);
        assertEquals(19, pos.word);
    }

    @Test
    public void lostPositionFallsBackToStart() throws IOException {
        File f = card();
        ReaderPositions p = open(f);
        p.track("id", book(), 70, 300, 0, "p");
        p.flushNow();
        BookBuilder b = new BookBuilder();
        b.text("совсем другая книга без глав и якорей");
        ReaderPositions.Position pos = open(f).resolve("id", b.build());
        assertEquals(ReaderPositions.Position.LOST, pos.source);
        assertEquals(0, pos.word);
    }

    @Test
    public void replacedFileIsFoundByTitleAndAuthor() throws IOException {
        File f = card();
        ReaderPositions p = open(f);
        p.track("old-id", book(), 30, 300, 5, "p");
        p.addMark("old-id", book(), 10, 6);
        p.flushNow();

        BookBuilder b = new BookBuilder();
        b.meta("тихая ГАВАНЬ ", "Нора  Вест");
        b.startChapter("Первая");
        b.text("вставленный абзац из шести слов подряд ");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) sb.append('a').append(i).append(' ');
        b.text(sb);
        sb.setLength(0);
        for (int i = 0; i < 50; i++) sb.append('b').append(i).append(' ');
        b.startChapter("Вторая");
        b.text(sb);
        BookIndex fixed = b.build();

        ReaderPositions again = open(f);
        assertNull(again.resolve("new-id", fixed));
        ReaderPositions.Position pos = again.resolveByTitle(fixed.title, fixed.author, fixed);
        assertEquals("old-id", pos.fromId);
        assertEquals(ReaderPositions.Position.BY_ANCHOR, pos.source);
        assertEquals(36, pos.word);
    }

    @Test
    public void resolveOrAdoptCopiesEntryUnderNewId() throws IOException {
        File f = card();
        ReaderPositions p = open(f);
        p.track("old-id", book(), 30, 300, 5, "p");
        p.addMark("old-id", book(), 10, 6);
        ReaderPositions.Position pos = p.resolveOrAdopt("new-id", book());
        assertEquals(30, pos.word);
        assertEquals(1, p.get("new-id").marks.size());
        assertEquals("Нора Вест", p.get("new-id").author);
    }

    @Test
    public void resolveByTitleIgnoresUnnamedBooks() throws IOException {
        ReaderPositions p = open(card());
        BookBuilder b = new BookBuilder();
        b.text("слово");
        p.track("x", b.build(), 0, 300, 0, "p");
        assertNull(p.resolveByTitle("", "", b.build()));
    }

    @Test
    public void corruptMainFileFallsBackToBackup() throws IOException {
        File f = card();
        BookIndex idx = book();
        ReaderPositions p = open(f);
        p.track("id", idx, 10, 300, 1000, "p");
        p.flushNow();
        p.track("id", idx, 20, 300, 2000, "p");
        p.flushNow();
        Files.write(f.toPath(), "{\"v\":1,\"books\":{\"id\":{\"wor".getBytes(UTF8));

        ReaderPositions again = open(f);
        assertEquals(10, again.resolve("id", idx).word);
    }

    @Test
    public void garbageMainWithoutBackupStartsEmptyWithoutCrash() throws IOException {
        File f = card();
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), new byte[]{0, 1, 2, 3, (byte) 0xFF});
        ReaderPositions p = open(f);
        assertNull(p.get("id"));
    }

    @Test
    public void missingMainWithBackupIsRestored() throws IOException {
        File f = card();
        BookIndex idx = book();
        ReaderPositions p = open(f);
        p.track("id", idx, 10, 300, 1000, "p");
        p.flushNow();
        p.track("id", idx, 20, 300, 2000, "p");
        p.flushNow();
        // Обрыв питания между «основной → .bak» и «временный → основной».
        assertTrue(f.delete());
        assertEquals(10, open(f).resolve("id", idx).word);
    }

    @Test
    public void leftoverTempFileDoesNotHarmMainFile() throws IOException {
        File f = card();
        BookIndex idx = book();
        ReaderPositions p = open(f);
        p.track("id", idx, 20, 300, 1000, "p");
        p.flushNow();
        Files.write(new File(f.getPath() + ".tmp").toPath(), "{\"v\":1,\"bo".getBytes(UTF8));
        assertEquals(20, open(f).resolve("id", idx).word);
        p.track("id", idx, 30, 300, 2000, "p");
        assertTrue(p.flushNow());
        assertEquals(30, open(f).resolve("id", idx).word);
    }

    @Test
    public void goodBackupIsNotLostWhenSavingAfterCorruptMain() throws IOException {
        File f = card();
        BookIndex idx = book();
        ReaderPositions p = open(f);
        p.track("id", idx, 10, 300, 1000, "p");
        p.flushNow();
        p.track("id", idx, 20, 300, 2000, "p");
        p.flushNow();
        Files.write(f.toPath(), "битый".getBytes(UTF8));

        ReaderPositions again = open(f);
        assertEquals(10, again.resolve("id", idx).word);
        assertFalse("битый основной файл должен быть убран с дороги", f.exists());
        assertTrue(new File(f.getPath() + ".bak").exists());
        again.track("id", idx, 40, 300, 3000, "p");
        assertTrue(again.flushNow());
        assertEquals(40, open(f).resolve("id", idx).word);
    }

    @Test
    public void unavailableCardKeepsPositionsInMemoryAndWritesLater() throws IOException {
        File root = Files.createTempDirectory("sd").toFile();
        File blocker = new File(root, "Books");
        Files.write(blocker.toPath(), new byte[]{1});
        File f = new File(blocker, ".cache/positions.json");
        BookIndex idx = book();

        ReaderPositions p = open(f);
        p.track("id", idx, 33, 300, 1000, "p");
        assertFalse("карта недоступна: запись не удалась", p.flushNow());
        assertTrue(p.isDirty());
        assertEquals(33, p.resolve("id", idx).word);

        assertTrue(blocker.delete());
        assertTrue("карта вернулась: запись идёт", p.flushNow());
        assertFalse(p.isDirty());
        assertEquals(33, open(f).resolve("id", idx).word);
    }

    @Test
    public void flushIfDueWritesNoLaterThanFiveSecondsAfterLastWrite() throws IOException {
        File f = card();
        BookIndex idx = book();
        ReaderPositions p = open(f);
        p.track("id", idx, 1, 300, 0, "p");
        assertTrue(p.flushIfDue(0));

        p.track("id", idx, 5, 300, 1000, "p");
        assertFalse(p.flushIfDue(1000));
        assertFalse(p.flushIfDue(4999));
        assertEquals("обрыв питания на 4999-й мс теряет только последние секунды", 1, open(f).resolve("id", idx).word);
        assertTrue(p.flushIfDue(5000));
        assertEquals(5, open(f).resolve("id", idx).word);
    }

    @Test
    public void flushIfDueDoesNothingWhenNothingChanged() throws IOException {
        File f = card();
        ReaderPositions p = open(f);
        assertFalse(p.flushIfDue(100000));
        assertFalse(f.exists());
        p.track("id", book(), 1, 300, 0, "p");
        assertTrue(p.flushNow());
        assertFalse(p.flushNow());
    }

    @Test
    public void failedWriteIsRetriedOnlyAfterPeriod() throws IOException {
        File root = Files.createTempDirectory("sd").toFile();
        File blocker = new File(root, "Books");
        Files.write(blocker.toPath(), new byte[]{1});
        ReaderPositions p = open(new File(blocker, ".cache/positions.json"));
        p.track("id", book(), 1, 300, 0, "p");
        assertFalse(p.flushIfDue(0));
        assertTrue(blocker.delete());
        assertFalse(p.flushIfDue(2000));
        assertTrue(p.flushIfDue(5000));
    }

    @Test
    public void twentyFirstMarkEvictsOldest() throws IOException {
        ReaderPositions p = open(card());
        BookIndex idx = book();
        p.track("id", idx, 0, 300, 0, "p");
        for (int i = 0; i < 21; i++) assertTrue(p.addMark("id", idx, i, 100 + i));
        ReaderPositions.Entry e = p.get("id");
        assertEquals(20, e.marks.size());
        for (ReaderPositions.Mark m : e.marks) assertTrue("слово 0 должно быть вытеснено", m.word != 0);
        assertEquals(20, e.marks.get(19).word);
    }

    @Test
    public void markKeepsSnippetChapterAndPercent() throws IOException {
        ReaderPositions p = open(card());
        BookIndex idx = book();
        p.track("id", idx, 0, 300, 0, "p");
        p.addMark("id", idx, 70, 123);
        ReaderPositions.Mark m = p.get("id").marks.get(0);
        assertEquals(70, m.word);
        assertEquals("Вторая", m.chapterTitle);
        assertEquals(70, m.pct);
        assertEquals(123, m.ms);
        assertEquals(idx.anchorAround(70, 8), m.anchor);
    }

    @Test
    public void markOnTheSameWordIsReplacedNotDuplicated() throws IOException {
        ReaderPositions p = open(card());
        BookIndex idx = book();
        p.track("id", idx, 0, 300, 0, "p");
        p.addMark("id", idx, 7, 1);
        p.addMark("id", idx, 7, 2);
        assertEquals(1, p.get("id").marks.size());
        assertEquals(2, p.get("id").marks.get(0).ms);
    }

    @Test
    public void marksSurviveRestartAndFollowEditedText() throws IOException {
        File f = card();
        ReaderPositions p = open(f);
        p.track("id", book(), 0, 300, 0, "p");
        p.addMark("id", book(), 30, 1);
        p.flushNow();
        BookBuilder b = new BookBuilder();
        b.startChapter("Первая");
        b.text("раз два три ");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) sb.append('a').append(i).append(' ');
        b.text(sb);
        BookIndex edited = b.build();
        ReaderPositions again = open(f);
        assertEquals(33, again.resolveMark(again.get("id").marks.get(0), edited));
    }

    @Test
    public void markCanBeRemoved() throws IOException {
        ReaderPositions p = open(card());
        BookIndex idx = book();
        p.track("id", idx, 0, 300, 0, "p");
        p.addMark("id", idx, 7, 1);
        p.addMark("id", idx, 8, 2);
        p.removeMark("id", 0);
        assertEquals(1, p.get("id").marks.size());
        assertEquals(8, p.get("id").marks.get(0).word);
    }

    @Test
    public void returnPointRoundTrips() throws IOException {
        File f = card();
        BookIndex idx = book();
        ReaderPositions p = open(f);
        p.track("id", idx, 80, 300, 0, "p");
        p.setReturnPoint("id", idx, 10);
        p.flushNow();
        ReaderPositions again = open(f);
        assertTrue(again.get("id").hasReturn);
        assertEquals(10, again.resolveReturnPoint("id", idx).word);
        again.clearReturnPoint("id");
        assertNull(again.resolveReturnPoint("id", idx));
    }

    @Test
    public void bookIsMarkedReadAtNinetyNinePercentAndStaysRead() throws IOException {
        ReaderPositions p = open(card());
        BookIndex idx = book();
        p.track("id", idx, 97, 300, 0, "p");
        assertFalse(p.get("id").read);
        p.track("id", idx, 98, 300, 0, "p");
        assertTrue(p.get("id").read);
        p.track("id", idx, 10, 300, 0, "p");
        assertTrue("отметка «прочитано» липкая", p.get("id").read);
    }

    @Test
    public void restartClearsReadAndPosition() throws IOException {
        ReaderPositions p = open(card());
        BookIndex idx = book();
        p.track("id", idx, 99, 300, 0, "p");
        p.setReturnPoint("id", idx, 5);
        p.addMark("id", idx, 3, 1);
        p.restart("id");
        ReaderPositions.Entry e = p.get("id");
        assertFalse(e.read);
        assertEquals(0, e.word);
        assertEquals(0, e.pct);
        assertFalse(e.hasReturn);
        assertEquals("закладки при «читать сначала» остаются", 1, e.marks.size());
    }

    @Test
    public void setReadCreatesEntryForNeverOpenedBook() throws IOException {
        ReaderPositions p = open(card());
        p.ensure("id", "Маяк", "Кай", "/b/m.epub");
        p.setRead("id", true);
        assertTrue(p.get("id").read);
        p.setRead("id", false);
        assertFalse(p.get("id").read);
        assertEquals("Маяк", p.get("id").title);
    }

    @Test
    public void jsonEscapingKeepsTrickyTitles() throws IOException {
        File f = card();
        ReaderPositions p = open(f);
        p.ensure("id", "«Нора» \"и\" Кай\n\\ \uD83D\uDE00", "О'Генри", "/b/\"x\".fb2");
        assertTrue(p.flushNow());
        ReaderPositions.Entry e = open(f).get("id");
        assertEquals("«Нора» \"и\" Кай\n\\ \uD83D\uDE00", e.title);
        assertEquals("/b/\"x\".fb2", e.lastPath);
    }

    @Test
    public void trackedAnchorIsComputedOnlyWhenSaving() throws IOException {
        File f = card();
        BookIndex idx = book();
        ReaderPositions p = open(f);
        p.track("id", idx, 30, 300, 0, "p");
        assertEquals(idx.anchorAround(30, 8), p.resolve("id", idx).word == 30 ? p.get("id").anchor : "?");
        p.flushNow();
        assertEquals(idx.anchorAround(30, 8), open(f).get("id").anchor);
    }

    @Test
    public void malformedEntryIsSkippedOthersLoad() throws IOException {
        File f = card();
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), ("{\"v\":1,\"books\":{\"bad\":5,\"good\":{\"word\":7,\"title\":\"Маяк\"}}}")
                .getBytes(UTF8));
        ReaderPositions p = open(f);
        assertNull(p.get("bad"));
        assertEquals(7, p.get("good").word);
    }
}
