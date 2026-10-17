package com.themoon.y1.reader;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.Test;

public final class Fb2ReaderTest {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private static final String HEAD = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
            + "<FictionBook xmlns=\"http://www.gribuser.ru/xml/fictionbook/2.0\" xmlns:l=\"http://www.w3.org/1999/xlink\">"
            + "<description><title-info><genre>prose</genre>"
            + "<author><first-name>Нора</first-name><middle-name>Т.</middle-name><last-name>Вест</last-name></author>"
            + "<book-title>Тихая гавань</book-title>"
            + "<annotation><p>Это описание, оно не часть текста.</p></annotation>"
            + "<coverpage><image l:href=\"#cover.jpg\"/></coverpage></title-info>"
            + "<document-info><author><nickname>scanner</nickname></author></document-info></description>";

    private static String book(String body) {
        return HEAD + body + "</FictionBook>";
    }

    private static final String TWO_CHAPTERS = "<body>"
            + "<section><title><p>Утро</p></title>"
            + "<p>Утром <emphasis>Нора</emphasis> вышла к причалу.</p><empty-line/>"
            + "<p>— Кай, ты здесь?<a l:href=\"#n1\" type=\"note\">[1]</a></p></section>"
            + "<section><title><p>Вечер</p><p>второй</p></title><p>Вечером они ушли.</p></section>"
            + "</body>"
            + "<body name=\"notes\"><section id=\"n1\"><title><p>1</p></title><p>Сноска про чайку.</p></section></body>"
            + "<binary id=\"cover.jpg\" content-type=\"image/jpeg\">QUJDREVGRw==</binary>";

    private static BookIndex read(String xml) throws IOException {
        byte[] b = xml.getBytes(UTF8);
        return new Fb2Reader().readStream(new ByteArrayInputStream(b), b.length, null);
    }

    private static String joined(BookIndex b) {
        return new String(b.text);
    }

    @Test
    public void readsMetaChaptersAndText() throws IOException {
        BookIndex b = read(book(TWO_CHAPTERS));
        assertEquals("Тихая гавань", b.title);
        assertEquals("Нора Вест", b.author);
        assertArrayEquals(new String[]{"Утро", "Вечер второй"}, b.chapterTitles);
        assertTrue(joined(b), joined(b).startsWith("Утро Утром Нора вышла к причалу. Кай, ты здесь?".replace("Кай", "\u2014\u00A0Кай")));
        assertTrue(joined(b).contains("Вечер второй Вечером они ушли."));
    }

    @Test
    public void chapterTitleWordsAreASeparateSentence() throws IOException {
        BookIndex b = read(book(TWO_CHAPTERS));
        int first = b.chapterFirstWord[0];
        assertEquals(0, first);
        assertTrue((b.flags[0] & BookIndex.F_SENT_END) != 0);
        assertTrue((b.flags[0] & BookIndex.F_CHAP_START) != 0);
        int second = b.chapterFirstWord[1];
        assertTrue((b.flags[second + 1] & BookIndex.F_SENT_END) != 0);
    }

    @Test
    public void notesBodyAndNoteLinksAreDropped() throws IOException {
        BookIndex b = read(book(TWO_CHAPTERS));
        assertFalse(joined(b).contains("Сноска"));
        assertFalse(joined(b).contains("[1]"));
        assertFalse(joined(b).contains("чайк"));
    }

    @Test
    public void annotationAndDocumentInfoAreNotBookText() throws IOException {
        BookIndex b = read(book(TWO_CHAPTERS));
        assertFalse(joined(b).contains("описание"));
        assertFalse(joined(b).contains("scanner"));
    }

    @Test
    public void binaryDoesNotLeakIntoText() throws IOException {
        BookIndex b = read(book(TWO_CHAPTERS));
        assertFalse(joined(b).contains("QUJD"));
    }

    @Test
    public void emphasisInsideWordKeepsItWhole() throws IOException {
        BookIndex b = read(book("<body><section><p>ма<emphasis>ма</emphasis> мыла</p></section></body>"));
        assertEquals("мама мыла", joined(b));
    }

    @Test
    public void firstUntitledSectionIsCalledStart() throws IOException {
        BookIndex b = read(book("<body><section><p>Раз два.</p></section>"
                + "<section><title><p>Дальше</p></title><p>Три.</p></section></body>"));
        assertArrayEquals(new String[]{"Начало", "Дальше"}, b.chapterTitles);
    }

    @Test
    public void laterUntitledSectionInheritsParentChapter() throws IOException {
        BookIndex b = read(book("<body><section><title><p>Глава</p></title><p>Раз.</p>"
                + "<section><p>Два.</p></section><section><p>Три.</p></section></section></body>"));
        assertArrayEquals(new String[]{"Глава"}, b.chapterTitles);
        assertTrue(joined(b).endsWith("Раз. Два. Три."));
    }

    @Test
    public void everyParagraphLikeElementEndsParagraph() throws IOException {
        BookIndex b = read(book("<body><section><p>а</p><poem><stanza><v>б</v><v>в</v></stanza>"
                + "<text-author>г</text-author></poem><cite><p>д</p></cite><subtitle>е</subtitle>"
                + "<epigraph><p>ж</p></epigraph></section></body>"));
        for (int i = 0; i < b.wordCount(); i++) {
            assertTrue("слово " + i, (b.flags[i] & BookIndex.F_PARA_END) != 0);
        }
        assertEquals(7, b.wordCount());
    }

    @Test
    public void emptyLineAndImageGiveNothing() throws IOException {
        BookIndex b = read(book("<body><section><p>а</p><empty-line/><image l:href=\"#x\"/><p>б</p></section></body>"));
        assertEquals("а б", joined(b));
    }

    @Test
    public void windows1251IsDecodedFromProlog() throws IOException {
        String xml = book(TWO_CHAPTERS).replace("encoding=\"utf-8\"", "encoding=\"windows-1251\"");
        byte[] bytes = xml.getBytes(Charset.forName("windows-1251"));
        BookIndex b = new Fb2Reader().readStream(new ByteArrayInputStream(bytes), bytes.length, null);
        assertEquals("Тихая гавань", b.title);
        assertTrue(joined(b).contains("Вечером они ушли."));
    }

    @Test
    public void hugeBinaryDoesNotSlowReading() throws IOException {
        StringBuilder bin = new StringBuilder("<binary id=\"c\" content-type=\"image/jpeg\">");
        for (int i = 0; i < 500000; i++) bin.append((char) ('A' + i % 26));
        bin.append("</binary>");
        long t0 = System.nanoTime();
        BookIndex b = read(book("<body><section><p>Слово.</p></section></body>" + bin));
        long ms = (System.nanoTime() - t0) / 1000000;
        assertEquals("Слово.", joined(b));
        assertTrue("чтение заняло " + ms + " мс", ms < 3000);
    }

    @Test
    public void emptyBodyGivesNoTextError() {
        try {
            read(book("<body><section><p> </p></section></body><body name=\"notes\"><section><p>x</p></section></body>"));
            fail();
        } catch (IOException e) {
            assertEquals("В книге нет текста", e.getMessage());
        }
    }

    @Test
    public void brokenXmlReturnsWhatWasRead() throws IOException {
        String xml = book(TWO_CHAPTERS);
        String cut = xml.substring(0, xml.indexOf("Вечером") + 3);
        BookIndex b = read(cut);
        assertTrue(joined(b).contains("Утро"));
        assertTrue(joined(b).contains("Веч"));
    }

    @Test
    public void garbageBetweenTagsDoesNotFail() throws IOException {
        BookIndex b = read(book("<body><section><p>а &nbsp; б &unknown; в < г</p></section></body>"));
        assertEquals("а б &unknown; в < г", joined(b));
    }

    @Test
    public void progressGrowsAndFinishesAtHundred() throws IOException {
        final List<Integer> seen = new ArrayList<Integer>();
        StringBuilder body = new StringBuilder("<body><section>");
        for (int i = 0; i < 2000; i++) body.append("<p>Нора и Кай смотрели на воду.</p>");
        body.append("</section></body>");
        byte[] b = book(body.toString()).getBytes(UTF8);
        new Fb2Reader().readStream(new ByteArrayInputStream(b), b.length, new Progress() {
            @Override
            public void onProgress(int percent) {
                seen.add(percent);
            }

            @Override
            public boolean isCancelled() {
                return false;
            }
        });
        assertTrue(seen.size() > 3);
        for (int i = 1; i < seen.size(); i++) assertTrue(seen.get(i) >= seen.get(i - 1));
        assertEquals(100, (int) seen.get(seen.size() - 1));
    }

    @Test
    public void cancelStopsReading() {
        StringBuilder body = new StringBuilder("<body><section>");
        for (int i = 0; i < 5000; i++) body.append("<p>Нора и Кай смотрели на воду.</p>");
        body.append("</section></body>");
        byte[] b = book(body.toString()).getBytes(UTF8);
        try {
            new Fb2Reader().readStream(new ByteArrayInputStream(b), b.length, new Progress() {
                @Override
                public void onProgress(int percent) {
                }

                @Override
                public boolean isCancelled() {
                    return true;
                }
            });
            fail();
        } catch (InterruptedIOException expected) {
            // ожидаемо
        } catch (IOException other) {
            fail("ожидали отмену, а получили " + other);
        }
    }

    @Test
    public void readMetaStopsAtEndOfTitleInfo() throws IOException {
        byte[] b = book(TWO_CHAPTERS).getBytes(UTF8);
        String[] meta = Fb2Reader.readMeta(new ByteArrayInputStream(b));
        assertEquals("Тихая гавань", meta[0]);
        assertEquals("Нора Вест", meta[1]);
    }

    @Test
    public void readMetaFallsBackToNickname() throws IOException {
        String xml = "<FictionBook><description><title-info><author><nickname>Кай</nickname></author>"
                + "<book-title>Маяк</book-title></title-info></description></FictionBook>";
        String[] meta = Fb2Reader.readMeta(new ByteArrayInputStream(xml.getBytes(UTF8)));
        assertEquals("Кай", meta[1]);
    }

    @Test
    public void readsPlainFileAndFb2Zip() throws IOException {
        File dir = java.nio.file.Files.createTempDirectory("fb2").toFile();
        File plain = new File(dir, "a.fb2");
        write(plain, book(TWO_CHAPTERS).getBytes(UTF8));
        assertEquals("Тихая гавань", new Fb2Reader().read(plain, null).title);

        File zip = new File(dir, "a.fb2.zip");
        ZipOutputStream z = new ZipOutputStream(new FileOutputStream(zip));
        z.putNextEntry(new ZipEntry("readme.txt"));
        z.write("не книга".getBytes(UTF8));
        z.closeEntry();
        z.putNextEntry(new ZipEntry("inner/Book.FB2"));
        z.write(book(TWO_CHAPTERS).getBytes(UTF8));
        z.closeEntry();
        z.close();
        BookIndex b = new Fb2Reader().read(zip, null);
        assertEquals("Тихая гавань", b.title);
        assertTrue(joined(b).contains("Вечером"));
    }

    @Test
    public void zipWithoutFb2IsRejected() throws IOException {
        File dir = java.nio.file.Files.createTempDirectory("fb2").toFile();
        File zip = new File(dir, "x.zip");
        ZipOutputStream z = new ZipOutputStream(new FileOutputStream(zip));
        z.putNextEntry(new ZipEntry("a.txt"));
        z.write(1);
        z.closeEntry();
        z.close();
        try {
            new Fb2Reader().read(zip, null);
            fail();
        } catch (IOException e) {
            assertEquals("Не удалось открыть книгу", e.getMessage());
        }
    }

    private static void write(File f, byte[] data) throws IOException {
        OutputStream o = new FileOutputStream(f);
        try {
            o.write(data);
        } finally {
            o.close();
        }
    }
}
