package com.themoon.y1.reader;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.Before;
import org.junit.Test;

public final class BookLibraryTest {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private File books;
    private ReaderPositions positions;

    @Before
    public void setUp() throws IOException {
        books = Files.createTempDirectory("books").toFile();
        positions = new ReaderPositions(new File(books, ".cache/positions.json"));
        positions.load();
    }

    // ------------------------------------------------------------------ сборка файлов

    private static String fb2(String title, String author, String coverId, String body, String binary) {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<FictionBook xmlns=\"http://www.gribuser.ru/xml/fictionbook/2.0\" "
                + "xmlns:l=\"http://www.w3.org/1999/xlink\"><description><title-info>");
        if (author != null) sb.append("<author><first-name>").append(author).append("</first-name></author>");
        if (title != null) sb.append("<book-title>").append(title).append("</book-title>");
        if (coverId != null) sb.append("<coverpage><image l:href=\"#").append(coverId).append("\"/></coverpage>");
        sb.append("</title-info></description><body><section><p>").append(body).append("</p></section></body>");
        if (binary != null) {
            sb.append("<binary id=\"").append(coverId).append("\" content-type=\"image/png\">")
                    .append(binary).append("</binary>");
        }
        return sb.append("</FictionBook>").toString();
    }

    private File write(String relative, String content) throws IOException {
        return write(relative, content.getBytes(UTF8));
    }

    private File write(String relative, byte[] content) throws IOException {
        File f = new File(books, relative);
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), content);
        return f;
    }

    private static byte[] zip(String name, byte[] data) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ZipOutputStream z = new ZipOutputStream(bos);
        z.putNextEntry(new ZipEntry(name));
        z.write(data);
        z.closeEntry();
        z.close();
        return bos.toByteArray();
    }

    private static byte[] epub(String title, String author, byte[] cover) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ZipOutputStream z = new ZipOutputStream(bos);
        put(z, "mimetype", "application/epub+zip".getBytes(UTF8));
        put(z, "META-INF/container.xml", ("<?xml version=\"1.0\"?><container><rootfiles>"
                + "<rootfile full-path=\"OEBPS/content.opf\"/></rootfiles></container>").getBytes(UTF8));
        String coverMeta = cover == null ? "" : "<meta name=\"cover\" content=\"cov\"/>";
        String coverItem = cover == null ? ""
                : "<item id=\"cov\" href=\"images/cover.png\" media-type=\"image/png\"/>";
        put(z, "OEBPS/content.opf", ("<?xml version=\"1.0\"?><package><metadata>"
                + "<dc:title>" + title + "</dc:title><dc:creator>" + author + "</dc:creator>" + coverMeta
                + "</metadata><manifest><item id=\"c1\" href=\"c1.xhtml\" media-type=\"application/xhtml+xml\"/>"
                + coverItem + "</manifest><spine><itemref idref=\"c1\"/></spine></package>").getBytes(UTF8));
        put(z, "OEBPS/c1.xhtml", ("<html><body><h1>Глава</h1><p>Нора вышла к воде. " + title
                + "</p></body></html>").getBytes(UTF8));
        if (cover != null) put(z, "OEBPS/images/cover.png", cover);
        z.close();
        return bos.toByteArray();
    }

    private static void put(ZipOutputStream z, String name, byte[] data) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(data);
        z.closeEntry();
    }

    private static BookIndex tenWords() {
        BookBuilder b = new BookBuilder();
        b.text("раз два три четыре пять шесть семь восемь девять десять ");
        return b.build();
    }

    private List<LibraryEntry> scan() {
        return new BookLibrary().scan(books, positions, null);
    }

    private static List<String> titles(List<LibraryEntry> list) {
        List<String> t = new ArrayList<String>();
        for (LibraryEntry e : list) t.add(e.title);
        return t;
    }

    private static LibraryEntry byTitle(List<LibraryEntry> list, String title) {
        for (LibraryEntry e : list) {
            if (e.title.equals(title)) return e;
        }
        throw new AssertionError("нет книги " + title + " среди " + titles(list));
    }

    // ------------------------------------------------------------------ поиск файлов и метаданные

    @Test
    public void findsBooksAndReadsTitleAndAuthorFromAllFormats() throws IOException {
        write("a.fb2", fb2("Тихая гавань", "Нора", null, "Кай ждал.", null));
        write("b.fb2.zip", zip("b.fb2", fb2("Маяк", "Кай", null, "Нора пришла.", null).getBytes(UTF8)));
        write("c.epub", epub("Бухта", "Нора Вест", null));
        List<LibraryEntry> list = scan();
        assertEquals(3, list.size());
        assertEquals("Нора", byTitle(list, "Тихая гавань").author);
        assertEquals("Кай", byTitle(list, "Маяк").author);
        assertEquals("Нора Вест", byTitle(list, "Бухта").author);
    }

    @Test
    public void fb2WithoutMetadataGetsFileName() throws IOException {
        write("Без названия.fb2", fb2(null, null, null, "Чайки кричали.", null));
        write("Старая книга.fb2.zip", zip("x.fb2", fb2(null, null, null, "Ветер.", null).getBytes(UTF8)));
        List<LibraryEntry> list = scan();
        assertEquals(2, list.size());
        assertEquals("", byTitle(list, "Без названия").author);
        byTitle(list, "Старая книга");
    }

    @Test
    public void brokenEpubStaysInListUnderFileName() throws IOException {
        write("поломанная.epub", new byte[]{1, 2, 3, 4, 5});
        List<LibraryEntry> list = scan();
        assertEquals(1, list.size());
        assertEquals("поломанная", list.get(0).title);
    }

    @Test
    public void zipWithoutFb2AndBrokenZipAreNotBooks() throws IOException {
        write("photos.zip", zip("notes.txt", "просто текст".getBytes(UTF8)));
        write("broken.zip", new byte[]{'P', 'K', 3, 4, 9, 9, 9});
        write("empty.zip", new byte[0]);
        write("ok.fb2", fb2("Маяк", "Кай", null, "Нора пришла.", null));
        assertEquals(java.util.Arrays.asList("Маяк"), titles(scan()));
    }

    @Test
    public void foldersDeeperThanTwoAndHiddenOnesAreSkipped() throws IOException {
        write("one.fb2", fb2("Один", null, null, "слово один.", null));
        write("sub/two.fb2", fb2("Два", null, null, "слово два.", null));
        write("sub/inner/three.fb2", fb2("Три", null, null, "слово три.", null));
        write("sub/inner/deep/four.fb2", fb2("Четыре", null, null, "слово четыре.", null));
        write(".hidden/five.fb2", fb2("Пять", null, null, "слово пять.", null));
        write("notes.txt", "не книга");
        write("COVER.JPG", "не книга");
        List<String> t = titles(scan());
        assertEquals(3, t.size());
        assertTrue(t.contains("Один"));
        assertTrue(t.contains("Два"));
        assertTrue(t.contains("Три"));
    }

    @Test
    public void extensionsAreCaseInsensitive() throws IOException {
        write("BIG.FB2", fb2("Громкая", null, null, "слово громко.", null));
        assertEquals(1, scan().size());
    }

    @Test
    public void missingOrEmptyFolderGivesEmptyList() throws IOException {
        assertEquals(0, new BookLibrary().scan(new File(books, "нет такой"), positions, null).size());
        assertEquals(0, scan().size());
    }

    // ------------------------------------------------------------------ кэш

    private static final class CountingMeta implements BookLibrary.MetaSource {
        int opens;

        @Override
        public String[] read(File file) {
            opens++;
            return new String[]{"Книга " + file.getName(), "Автор"};
        }
    }

    @Test
    public void unchangedFilesAreNotOpenedAgain() throws IOException {
        File a = write("a.fb2", fb2("А", null, null, "слово а.", null));
        write("b.fb2", fb2("Б", null, null, "слово б.", null));
        CountingMeta meta = new CountingMeta();
        BookLibrary lib = new BookLibrary(meta);
        assertEquals(2, lib.scan(books, positions, null).size());
        assertEquals(2, meta.opens);
        assertTrue(new File(books, ".cache/library.json").isFile());

        List<LibraryEntry> again = lib.scan(books, positions, null);
        assertEquals(2, meta.opens);
        assertEquals("Книга a.fb2", byTitle(again, "Книга a.fb2").title);

        Files.write(a.toPath(), fb2("А", null, null, "слово а, но длиннее.", null).getBytes(UTF8));
        lib.scan(books, positions, null);
        assertEquals("изменился только один файл", 3, meta.opens);
    }

    @Test
    public void cachedZipThatIsNotBookIsNotOpenedAgain() throws IOException {
        write("photos.zip", zip("notes.txt", "текст".getBytes(UTF8)));
        final int[] opens = {0};
        BookLibrary lib = new BookLibrary(new BookLibrary.MetaSource() {
            @Override
            public String[] read(File file) {
                opens[0]++;
                return null;
            }
        });
        assertEquals(0, lib.scan(books, positions, null).size());
        assertEquals(0, lib.scan(books, positions, null).size());
        assertEquals(1, opens[0]);
    }

    @Test
    public void deletedFileDisappearsAndCorruptCacheIsIgnored() throws IOException {
        File a = write("a.fb2", fb2("А", null, null, "слово а.", null));
        write("b.fb2", fb2("Б", null, null, "слово б.", null));
        assertEquals(2, scan().size());
        assertTrue(a.delete());
        assertEquals(java.util.Arrays.asList("Б"), titles(scan()));
        Files.write(new File(books, ".cache/library.json").toPath(), "{битый".getBytes(UTF8));
        assertEquals(java.util.Arrays.asList("Б"), titles(scan()));
    }

    @Test
    public void metadataFailureIsCachedToo() throws IOException {
        write("x.epub", new byte[]{1, 2, 3});
        final int[] opens = {0};
        BookLibrary lib = new BookLibrary(new BookLibrary.MetaSource() {
            @Override
            public String[] read(File file) throws IOException {
                opens[0]++;
                throw new IOException("нечитаемо");
            }
        });
        assertEquals("x", lib.scan(books, positions, null).get(0).title);
        lib.scan(books, positions, null);
        assertEquals(1, opens[0]);
    }

    @Test
    public void cancelledScanDoesNotWriteCacheAndReturnsWhatItHas() throws IOException {
        write("a.fb2", fb2("А", null, null, "слово а.", null));
        write("b.fb2", fb2("Б", null, null, "слово б.", null));
        List<LibraryEntry> list = new BookLibrary().scan(books, positions, new Progress() {
            @Override
            public void onProgress(int percent) {
            }

            @Override
            public boolean isCancelled() {
                return true;
            }
        });
        assertEquals(0, list.size());
        assertTrue(!new File(books, ".cache/library.json").exists());
    }

    // ------------------------------------------------------------------ состояние и порядок

    @Test
    public void fiveStatesAreOrderedLastReadStartedNewRead() throws IOException {
        String[] names = {"Вельбот", "Гавань", "Дюны", "Бриз", "Алый парус"};
        for (String n : names) write(n + ".fb2", fb2(n, null, null, "текст книги " + n + ".", null));
        List<LibraryEntry> list = scan();
        BookIndex idx = tenWords();
        positions.track(byTitle(list, "Вельбот").id(), idx, 3, 300, 1000, "p");
        positions.track(byTitle(list, "Гавань").id(), idx, 5, 300, 9000, "p");
        positions.track(byTitle(list, "Дюны").id(), idx, 9, 300, 5000, "p");

        list = scan();
        assertEquals(java.util.Arrays.asList("Гавань", "Вельбот", "Алый парус", "Бриз", "Дюны"), titles(list));
        assertEquals(LibraryEntry.STARTED, list.get(0).state);
        assertEquals(55, list.get(0).pct);
        assertEquals(9000, list.get(0).lastReadMs);
        assertEquals(LibraryEntry.NEW, list.get(2).state);
        assertEquals(LibraryEntry.READ, list.get(4).state);
        assertEquals(100, list.get(4).pct);
    }

    @Test
    public void titleOrderIgnoresState() throws IOException {
        write("1.fb2", fb2("вельбот", null, null, "текст 1.", null));
        write("2.fb2", fb2("Алый парус", null, null, "текст 2.", null));
        write("3.fb2", fb2("Бриз", null, null, "текст 3.", null));
        List<LibraryEntry> list = scan();
        positions.track(byTitle(list, "вельбот").id(), tenWords(), 3, 300, 1000, "p");
        list = scan();
        BookLibrary.sort(list, BookLibrary.ORDER_TITLE);
        assertEquals(java.util.Arrays.asList("Алый парус", "Бриз", "вельбот"), titles(list));
    }

    @Test
    public void markReadMarkUnreadAndRestartChangeState() throws IOException {
        write("a.fb2", fb2("Маяк", null, null, "Нора пришла.", null));
        LibraryEntry e = scan().get(0);
        assertEquals(LibraryEntry.NEW, e.state);

        BookLibrary.markRead(positions, e);
        assertEquals(LibraryEntry.READ, e.state);
        assertEquals(LibraryEntry.READ, scan().get(0).state);

        BookLibrary.markUnread(positions, e);
        assertEquals(LibraryEntry.NEW, e.state);

        positions.track(e.id(), tenWords(), 4, 300, 10, "p");
        BookLibrary.applyState(e, positions);
        assertEquals(LibraryEntry.STARTED, e.state);

        BookLibrary.restart(positions, e);
        assertEquals(LibraryEntry.NEW, e.state);
        assertEquals(0, e.pct);
    }

    @Test
    public void idIsContentHashAndSurvivesRename() throws IOException {
        File f = write("a.fb2", fb2("Маяк", null, null, "Нора пришла.", null));
        String id = scan().get(0).id();
        assertTrue(f.renameTo(new File(books, "переименована.fb2")));
        assertEquals(id, scan().get(0).id());
    }

    // ------------------------------------------------------------------ обложки

    private static byte[] fakePng(int size) {
        byte[] b = new byte[size];
        new Random(7).nextBytes(b);
        b[0] = (byte) 0x89;
        b[1] = 'P';
        b[2] = 'N';
        b[3] = 'G';
        return b;
    }

    @Test
    public void fb2CoverIsDecodedFromBinary() throws IOException {
        byte[] png = fakePng(5000);
        String b64 = Base64.getMimeEncoder(76, "\n".getBytes(UTF8)).encodeToString(png);
        write("a.fb2", fb2("Маяк", null, "cover.png", "Нора пришла.", b64));
        LibraryEntry e = scan().get(0);
        assertArrayEquals(png, new BookLibrary().coverFor(e));
    }

    @Test
    public void fb2CoverWithoutPaddingAndOddLengthsIsDecoded() throws IOException {
        for (int size = 1; size <= 4; size++) {
            byte[] png = fakePng(size + 6);
            String b64 = Base64.getEncoder().withoutPadding().encodeToString(png);
            File f = write("c" + size + ".fb2", fb2("Книга" + size, null, "i", "текст " + size + ".", b64));
            LibraryEntry e = byTitle(scan(), "Книга" + size);
            assertArrayEquals("размер " + png.length, png, new BookLibrary().coverFor(e));
            assertTrue(f.delete());
        }
    }

    @Test
    public void fb2CoverPicksBinaryByIdAmongOthers() throws IOException {
        byte[] png = fakePng(300);
        String text = "<?xml version=\"1.0\" encoding=\"utf-8\"?><FictionBook xmlns:l=\"x\"><description><title-info>"
                + "<book-title>Маяк</book-title><coverpage><image l:href=\"#second\"/></coverpage></title-info>"
                + "</description><body><section><p>Кай ждал.</p></section></body>"
                + "<binary id=\"first\" content-type=\"image/png\">AAAA</binary>"
                + "<binary id=\"second\" content-type=\"image/png\">" + Base64.getEncoder().encodeToString(png)
                + "</binary></FictionBook>";
        write("a.fb2", text);
        assertArrayEquals(png, new BookLibrary().coverFor(scan().get(0)));
    }

    @Test
    public void fb2CoverInsideZipWorks() throws IOException {
        byte[] png = fakePng(777);
        String xml = fb2("Маяк", null, "c", "Нора пришла.", Base64.getEncoder().encodeToString(png));
        write("a.fb2.zip", zip("a.fb2", xml.getBytes(UTF8)));
        assertArrayEquals(png, new BookLibrary().coverFor(scan().get(0)));
    }

    @Test
    public void bookWithoutCoverGivesNull() throws IOException {
        write("a.fb2", fb2("Маяк", null, null, "Нора пришла.", null));
        write("b.epub", epub("Бриз", "Кай", null));
        List<LibraryEntry> list = scan();
        BookLibrary lib = new BookLibrary();
        assertNull(lib.coverFor(byTitle(list, "Маяк")));
        assertNull(lib.coverFor(byTitle(list, "Бриз")));
    }

    @Test
    public void coverPointingToMissingBinaryGivesNull() throws IOException {
        write("a.fb2", fb2("Маяк", null, "nope", "Нора пришла.", null));
        assertNull(new BookLibrary().coverFor(scan().get(0)));
    }

    @Test
    public void epubCoverIsReadFromArchive() throws IOException {
        byte[] png = fakePng(1234);
        write("b.epub", epub("Бриз", "Кай", png));
        assertArrayEquals(png, new BookLibrary().coverFor(scan().get(0)));
    }

    @Test
    public void tooBigCoverIsRefused() throws IOException {
        byte[] png = fakePng(BookLibrary.MAX_COVER_BYTES + 3);
        String b64 = Base64.getEncoder().encodeToString(png);
        long t0 = System.nanoTime();
        write("a.fb2", fb2("Маяк", null, "cover.png", "Нора пришла.", b64));
        write("b.epub", epub("Бриз", "Кай", png));
        List<LibraryEntry> list = scan();
        assertNull(new BookLibrary().coverFor(byTitle(list, "Маяк")));
        assertNull(new BookLibrary().coverFor(byTitle(list, "Бриз")));
        assertTrue("обложки отвергаются быстро", (System.nanoTime() - t0) / 1_000_000 < 20_000);
        assertNotNull(list);
    }

    @Test
    public void coverOfUnreadableBookIsNullNotCrash() throws IOException {
        File f = write("x.epub", new byte[]{1, 2, 3});
        LibraryEntry e = scan().get(0);
        assertTrue(f.delete());
        assertNull(new BookLibrary().coverFor(e));
    }
}
