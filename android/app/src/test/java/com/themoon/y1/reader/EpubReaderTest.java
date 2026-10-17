package com.themoon.y1.reader;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.junit.Test;

public final class EpubReaderTest {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private static final String CONTAINER = "<?xml version=\"1.0\"?><container version=\"1.0\" "
            + "xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\"><rootfiles>"
            + "<rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/>"
            + "</rootfiles></container>";

    private static String opf(String creator, String manifest, String spine) {
        return "<?xml version=\"1.0\"?><package xmlns=\"http://www.idpf.org/2007/opf\" version=\"2.0\">"
                + "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:opf=\"http://www.idpf.org/2007/opf\">"
                + "<dc:title>Тихая гавань</dc:title>" + creator + "<meta name=\"cover\" content=\"cov\"/></metadata>"
                + "<manifest>" + manifest + "</manifest><spine toc=\"ncx\">" + spine + "</spine></package>";
    }

    private static String xhtml(String title, String body) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?><!DOCTYPE html><html xmlns=\"http://www.w3.org/1999/xhtml\">"
                + "<head><title>" + title + "</title><style>p { color: red }</style></head><body>" + body + "</body></html>";
    }

    private static final String MANIFEST = "<item id=\"c1\" href=\"text/ch1.xhtml\" media-type=\"application/xhtml+xml\"/>"
            + "<item id=\"c2\" href=\"text/ch%202.xhtml\" media-type=\"application/xhtml+xml\"/>"
            + "<item id=\"cov\" href=\"img/cover.jpg\" media-type=\"image/jpeg\"/>"
            + "<item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>";

    /** Zip собирается с порядком записей, отличающимся от порядка spine. */
    private static File epub(Map<String, String> files) throws IOException {
        File dir = java.nio.file.Files.createTempDirectory("epub").toFile();
        File f = new File(dir, "book.epub");
        ZipOutputStream z = new ZipOutputStream(new FileOutputStream(f));
        for (Map.Entry<String, String> e : files.entrySet()) {
            z.putNextEntry(new ZipEntry(e.getKey()));
            z.write(e.getValue().getBytes(UTF8));
            z.closeEntry();
        }
        z.close();
        return f;
    }

    private static Map<String, String> standard() {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("mimetype", "application/epub+zip");
        m.put("META-INF/container.xml", CONTAINER);
        m.put("OEBPS/text/ch 2.xhtml", xhtml("Тихая гавань",
                "<h2>Вечер</h2><p>Вечером они&nbsp;ушли.</p>"));
        m.put("OEBPS/text/ch1.xhtml", xhtml("Тихая гавань",
                "<h1>Утро</h1><p>Утром Нора вышла к причалу.<p>— Кай, ты здесь?</p><br/>Он молчал.<script>var x = '<p>';</script>"
                        + "<svg><title>картинка</title><text>не текст</text></svg>"));
        m.put("OEBPS/content.opf", opf("<dc:creator opf:role=\"aut\">Нора Вест</dc:creator>", MANIFEST,
                "<itemref idref=\"c1\"/><itemref idref=\"c2\"/><itemref idref=\"ncx\"/>"));
        return m;
    }

    private static String joined(BookIndex b) {
        return new String(b.text);
    }

    @Test
    public void chaptersFollowSpineNotZipOrder() throws IOException {
        BookIndex b = new EpubReader().read(epub(standard()), null);
        assertArrayEquals(new String[]{"Утро", "Вечер"}, b.chapterTitles);
        assertTrue(joined(b), joined(b).startsWith("Утро Утром Нора вышла к причалу."));
        assertTrue(joined(b).endsWith("Вечер Вечером они ушли."));
    }

    @Test
    public void readsMetadata() throws IOException {
        BookIndex b = new EpubReader().read(epub(standard()), null);
        assertEquals("Тихая гавань", b.title);
        assertEquals("Нора Вест", b.author);
    }

    @Test
    public void nbspEntityUnclosedParagraphScriptAndSvgAreHandled() throws IOException {
        BookIndex b = new EpubReader().read(epub(standard()), null);
        String t = joined(b);
        assertTrue(t.contains("Вечером они ушли."));
        assertTrue(t.contains("причалу. \u2014\u00A0Кай, ты здесь? Он молчал."));
        assertFalse(t.contains("var"));
        assertFalse(t.contains("картинка"));
        assertFalse(t.contains("не текст"));
        assertFalse(t.contains("color"));
    }

    @Test
    public void pathsWithParentSegmentsAreResolved() throws IOException {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("META-INF/container.xml", CONTAINER);
        m.put("OEBPS/content.opf", opf("", "<item id=\"c1\" href=\"../text/ch1.xhtml\" media-type=\"text/html\"/>"
                .replace("../text", "text/../text"), "<itemref idref=\"c1\"/>"));
        m.put("OEBPS/text/ch1.xhtml", xhtml("t", "<p>Один.</p>"));
        assertEquals("Один.", joined(new EpubReader().read(epub(m), null)));
    }

    @Test
    public void realParentSegmentOutOfOpfFolderWorks() throws IOException {
        String container = CONTAINER.replace("OEBPS/content.opf", "OEBPS/opf/content.opf");
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("META-INF/container.xml", container);
        m.put("OEBPS/opf/content.opf", opf("", "<item id=\"c1\" href=\"../text/ch1.xhtml\" "
                + "media-type=\"application/xhtml+xml\"/>", "<itemref idref=\"c1\"/>"));
        m.put("OEBPS/text/ch1.xhtml", xhtml("t", "<p>Один.</p>"));
        assertEquals("Один.", joined(new EpubReader().read(epub(m), null)));
    }

    @Test
    public void missingCreatorGivesEmptyAuthor() throws IOException {
        Map<String, String> m = standard();
        m.put("OEBPS/content.opf", opf("", MANIFEST, "<itemref idref=\"c1\"/>"));
        BookIndex b = new EpubReader().read(epub(m), null);
        assertEquals("", b.author);
        assertEquals("Тихая гавань", b.title);
    }

    @Test
    public void chapterTitleFallsBackToHeadTitleThenNumber() throws IOException {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("META-INF/container.xml", CONTAINER);
        m.put("OEBPS/content.opf", opf("", "<item id=\"a\" href=\"a.xhtml\" media-type=\"application/xhtml+xml\"/>"
                + "<item id=\"b\" href=\"b.xhtml\" media-type=\"application/xhtml+xml\"/>"
                + "<item id=\"c\" href=\"c.xhtml\" media-type=\"application/xhtml+xml\"/>",
                "<itemref idref=\"a\"/><itemref idref=\"b\"/><itemref idref=\"c\"/>"));
        m.put("OEBPS/a.xhtml", xhtml("Глава про маяк", "<p>Первый текст.</p>"));
        m.put("OEBPS/b.xhtml", xhtml("Тихая гавань", "<p>Второй текст.</p>"));
        m.put("OEBPS/c.xhtml", xhtml("", "<p>Третий текст.</p>"));
        BookIndex b = new EpubReader().read(epub(m), null);
        assertArrayEquals(new String[]{"Глава про маяк", "Глава 2", "Глава 3"}, b.chapterTitles);
    }

    @Test
    public void imageOnlyDocumentGivesNoChapter() throws IOException {
        Map<String, String> m = standard();
        m.put("OEBPS/content.opf", opf("", MANIFEST.replace("</manifest>", "")
                + "<item id=\"pic\" href=\"pic.xhtml\" media-type=\"application/xhtml+xml\"/>",
                "<itemref idref=\"pic\"/><itemref idref=\"c1\"/>"));
        m.put("OEBPS/pic.xhtml", xhtml("x", "<div><img src=\"cover.jpg\"/></div>"));
        BookIndex b = new EpubReader().read(epub(m), null);
        assertArrayEquals(new String[]{"Утро"}, b.chapterTitles);
    }

    @Test
    public void spineMissingFallsBackToManifestOrder() throws IOException {
        Map<String, String> m = standard();
        m.put("OEBPS/content.opf", opf("", MANIFEST, ""));
        BookIndex b = new EpubReader().read(epub(m), null);
        assertArrayEquals(new String[]{"Утро", "Вечер"}, b.chapterTitles);
    }

    @Test
    public void missingContainerThrowsIOException() throws IOException {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("OEBPS/content.opf", "x");
        try {
            new EpubReader().read(epub(m), null);
            fail();
        } catch (IOException e) {
            assertEquals("Не удалось открыть книгу", e.getMessage());
        }
    }

    @Test
    public void notAZipThrowsIOException() throws IOException {
        File f = File.createTempFile("bad", ".epub");
        FileOutputStream o = new FileOutputStream(f);
        o.write("просто текст, а не архив".getBytes(UTF8));
        o.close();
        try {
            new EpubReader().read(f, null);
            fail();
        } catch (IOException e) {
            assertEquals("Не удалось открыть книгу", e.getMessage());
        }
    }

    @Test
    public void encryptedBookWithEmptyDocumentsIsReportedAsUnopenable() throws IOException {
        Map<String, String> m = standard();
        m.put("META-INF/encryption.xml", "<encryption/>");
        m.put("OEBPS/text/ch1.xhtml", "");
        m.put("OEBPS/text/ch 2.xhtml", "");
        try {
            new EpubReader().read(epub(m), null);
            fail();
        } catch (IOException e) {
            assertEquals("Не удалось открыть книгу", e.getMessage());
        }
    }

    @Test
    public void encryptedBookWithGarbageTextIsReportedAsUnopenable() throws IOException {
        StringBuilder garbage = new StringBuilder();
        for (int i = 0; i < 300; i++) garbage.append('\uFFFD');
        Map<String, String> m = standard();
        m.put("META-INF/encryption.xml", "<encryption/>");
        m.put("OEBPS/text/ch1.xhtml", xhtml("t", "<p>" + garbage + "</p>"));
        m.put("OEBPS/text/ch 2.xhtml", xhtml("t", "<p>" + garbage + "</p>"));
        try {
            new EpubReader().read(epub(m), null);
            fail();
        } catch (IOException e) {
            assertEquals("Не удалось открыть книгу", e.getMessage());
        }
    }

    @Test
    public void fontObfuscationAloneDoesNotBreakReading() throws IOException {
        Map<String, String> m = standard();
        m.put("META-INF/encryption.xml", "<encryption/>");
        assertEquals(2, new EpubReader().read(epub(m), null).chapterTitles.length);
    }

    @Test
    public void emptyBookThrowsNoTextError() throws IOException {
        Map<String, String> m = standard();
        m.put("OEBPS/text/ch1.xhtml", xhtml("t", "<div> </div>"));
        m.put("OEBPS/text/ch 2.xhtml", xhtml("t", "<p>&nbsp;</p>"));
        try {
            new EpubReader().read(epub(m), null);
            fail();
        } catch (IOException e) {
            assertEquals("В книге нет текста", e.getMessage());
        }
    }

    @Test
    public void packageGivesCoverPathForLibrary() throws IOException {
        ZipFile zip = new ZipFile(epub(standard()));
        try {
            EpubReader.Pkg pkg = EpubReader.readPackage(zip);
            assertEquals("OEBPS/img/cover.jpg", pkg.coverPath);
            assertEquals("Нора Вест", pkg.author);
            assertEquals("OEBPS/text/ch1.xhtml", pkg.spine.get(0));
            assertEquals("OEBPS/text/ch 2.xhtml", pkg.spine.get(1));
            assertEquals(2, pkg.spine.size());
        } finally {
            zip.close();
        }
    }

    @Test
    public void coverImagePropertyIsRecognised() throws IOException {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("META-INF/container.xml", CONTAINER);
        m.put("OEBPS/content.opf", opf("", "<item id=\"i\" href=\"c.png\" media-type=\"image/png\" properties=\"cover-image\"/>"
                + "<item id=\"a\" href=\"a.xhtml\" media-type=\"application/xhtml+xml\"/>", "<itemref idref=\"a\"/>")
                .replace("<meta name=\"cover\" content=\"cov\"/>", ""));
        m.put("OEBPS/a.xhtml", xhtml("t", "<p>х</p>"));
        ZipFile zip = new ZipFile(epub(m));
        try {
            assertEquals("OEBPS/c.png", EpubReader.readPackage(zip).coverPath);
        } finally {
            zip.close();
        }
    }

    @Test
    public void bookWithoutCoverHasNullCoverPath() throws IOException {
        Map<String, String> m = standard();
        m.put("OEBPS/content.opf", opf("", MANIFEST, "<itemref idref=\"c1\"/>").replace("<meta name=\"cover\" content=\"cov\"/>", ""));
        ZipFile zip = new ZipFile(epub(m));
        try {
            assertNull(EpubReader.readPackage(zip).coverPath);
        } finally {
            zip.close();
        }
    }

    @Test
    public void cancelStopsReading() throws IOException {
        try {
            new EpubReader().read(epub(standard()), new Progress() {
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
        }
    }

    @Test
    public void progressEndsAtHundred() throws IOException {
        final int[] last = {-1};
        new EpubReader().read(epub(standard()), new Progress() {
            @Override
            public void onProgress(int percent) {
                last[0] = percent;
            }

            @Override
            public boolean isCancelled() {
                return false;
            }
        });
        assertEquals(100, last[0]);
    }
}
