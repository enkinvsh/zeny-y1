package com.themoon.y1.reader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.Charset;

import org.junit.Test;

public final class MarkupScannerTest {

    /** Собирает события в одну строку: <имя[атрибуты]> для старта, </имя> для конца, остальное текст. */
    private static final class Log implements MarkupScanner.Handler {
        final StringBuilder out = new StringBuilder();
        int textCalls;

        @Override
        public void start(String name, String[] attrs, boolean selfClosing) {
            out.append('<').append(name);
            for (int i = 0; i + 1 < attrs.length; i += 2) {
                out.append(' ').append(attrs[i]).append('=').append(attrs[i + 1]);
            }
            out.append(selfClosing ? "/>" : ">");
        }

        @Override
        public void end(String name) {
            out.append("</").append(name).append('>');
        }

        @Override
        public void text(char[] buf, int off, int len) {
            textCalls++;
            out.append(buf, off, len);
        }
    }

    private static String scan(String src) throws IOException {
        Log log = new Log();
        MarkupScanner.scan(new StringReader(src), log);
        return log.out.toString();
    }

    @Test
    public void nbspEntityBecomesNoBreakSpace() throws IOException {
        assertEquals("<p>a\u00A0b</p>", scan("<p>a&nbsp;b</p>"));
    }

    @Test
    public void numericEntitiesDecode() throws IOException {
        assertEquals("<p>\u2014\u2014</p>", scan("<p>&#x2014;&#8212;</p>"));
    }

    @Test
    public void namedEntitiesDecode() throws IOException {
        assertEquals("<p>& < > \" ' \u2014 \u2013 \u2026 \u00AB \u00BB</p>",
                scan("<p>&amp; &lt; &gt; &quot; &apos; &mdash; &ndash; &hellip; &laquo; &raquo;</p>"));
    }

    @Test
    public void softHyphenEntityIsRemoved() throws IOException {
        assertEquals("<p>ab</p>", scan("<p>a&shy;b</p>"));
    }

    @Test
    public void unknownEntityStaysLiteral() throws IOException {
        assertEquals("<p>x &foo; y &</p>", scan("<p>x &foo; y &</p>"));
    }

    @Test
    public void ampersandWithoutSemicolonStaysLiteral() throws IOException {
        assertEquals("<p>Tom & Jerry</p>", scan("<p>Tom & Jerry</p>"));
    }

    @Test
    public void astralNumericEntityMakesSurrogatePair() throws IOException {
        assertEquals("<p>\uD83D\uDE00</p>", scan("<p>&#x1F600;</p>"));
    }

    @Test
    public void unclosedParagraphsDoNotFail() throws IOException {
        assertEquals("<p>1<p>2", scan("<p>1<p>2"));
    }

    @Test
    public void voidElementsGiveStartAndEnd() throws IOException {
        assertEquals("<p>a<br/></br>b</p>", scan("<p>a<br/>b</p>"));
        assertEquals("<p>a<br></br>b</p>", scan("<p>a<br>b</p>"));
    }

    @Test
    public void strayEndTagOfVoidElementIsIgnored() throws IOException {
        assertEquals("<br></br>", scan("<br></br>"));
    }

    @Test
    public void unquotedAndQuotedAttributesAreRead() throws IOException {
        assertEquals("<a type=note href=#n1>x</a>", scan("<a type=note href='#n1'>x</a>"));
        assertEquals("<a type=note>", scan("<A TYPE=note>"));
    }

    @Test
    public void attributeWithoutValueGetsEmptyString() throws IOException {
        assertEquals("<input disabled=></input>", scan("<input disabled>"));
    }

    @Test
    public void namespacePrefixIsStripped() throws IOException {
        assertEquals("<p>x</p>", scan("<fb:P>x</fb:p>"));
        assertEquals("<image href=#a/></image>", scan("<image l:href=\"#a\"/>"));
    }

    @Test
    public void entitiesInsideAttributeValuesAreDecoded() throws IOException {
        assertEquals("<a title=a&b>", scan("<a title=\"a&amp;b\">"));
    }

    @Test
    public void commentsProcessingInstructionsAndDoctypeAreDropped() throws IOException {
        assertEquals("<p>x</p>y", scan("<?xml version='1.0'?><!DOCTYPE html PUBLIC \"-//W3C\" \"x.dtd\">"
                + "<!-- <b>not a tag</b> --><p>x</p><!---->y"));
    }

    @Test
    public void cdataIsPassedVerbatim() throws IOException {
        assertEquals("<p><b>&amp;</p>", scan("<p><![CDATA[<b>&amp;]]></p>"));
    }

    @Test
    public void scriptAndStyleBodiesAreSkipped() throws IOException {
        assertEquals("<script></script><p>ok</p><style></style>",
                scan("<script>if (a < b && c) { x = '</p>'; }</script><p>ok</p><STYLE>p{color:red}</STYLE>"));
    }

    @Test
    public void selfClosingScriptHasNoBodyToSkip() throws IOException {
        assertEquals("<script/></script><p>ok</p>", scan("<script/><p>ok</p>"));
    }

    @Test
    public void lessThanWithoutTagIsLiteralText() throws IOException {
        assertEquals("<p>1 < 2 and a<3</p>", scan("<p>1 < 2 and a<3</p>"));
    }

    @Test
    public void brokenLessThanAtEndDoesNotHang() throws IOException {
        assertEquals("<p>tail<", scan("<p>tail<"));
        assertEquals("<p>tail", scan("<p>tail<p"));
        assertEquals("<", scan("<"));
        assertEquals("", scan(""));
    }

    @Test
    public void unterminatedCommentEatsRest() throws IOException {
        assertEquals("<p>a", scan("<p>a<!-- never closed <p>b"));
    }

    @Test
    public void textIsFlushedInChunksButRestoredInOrder() throws IOException {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 20000; i++) big.append((char) ('a' + i % 26));
        Log log = new Log();
        MarkupScanner.scan(new StringReader("<p>" + big + "</p>"), log);
        assertEquals("<p>" + big + "</p>", log.out.toString());
        assertTrue("длинный текст должен приходить кусками, а не одним буфером", log.textCalls > 1);
    }

    @Test
    public void binaryBodyIsSkippedWithoutTextWhenAsked() throws IOException {
        StringBuilder b64 = new StringBuilder("<FictionBook><binary id=\"c\" content-type=\"image/jpeg\">");
        for (int i = 0; i < 400000; i++) b64.append((char) ('A' + i % 26));
        b64.append("</binary><p>after</p></FictionBook>");
        Log log = new Log();
        long t0 = System.nanoTime();
        MarkupScanner.scan(new StringReader(b64.toString()), log, true);
        long ms = (System.nanoTime() - t0) / 1000000;
        assertEquals("<fictionbook><binary id=c content-type=image/jpeg></binary><p>after</p></fictionbook>",
                log.out.toString());
        assertTrue("пропуск binary занял " + ms + " мс", ms < 2000);
    }

    @Test
    public void binaryBodyIsDeliveredWhenNotSkipped() throws IOException {
        Log log = new Log();
        MarkupScanner.scan(new StringReader("<binary id=a>QUJD</binary>"), log, false);
        assertEquals("<binary id=a>QUJD</binary>", log.out.toString());
    }

    @Test
    public void handlerCanStopScanEarly() throws IOException {
        final StringBuilder seen = new StringBuilder();
        MarkupScanner.scan(new StringReader("<a>1</a><stop/><b>2</b>"), new MarkupScanner.Handler() {
            @Override
            public void start(String name, String[] attrs, boolean selfClosing) {
                seen.append(name);
                if (name.equals("stop")) throw new MarkupScanner.StopScan();
            }

            @Override
            public void end(String name) {
            }

            @Override
            public void text(char[] buf, int off, int len) {
            }
        });
        assertEquals("astop", seen.toString());
    }

    @Test
    public void whitespaceInsideTagsIsTolerated() throws IOException {
        assertEquals("<p id=1 class=x>t</p>", scan("<p\n id = \"1\"\tclass='x' >t</p >"));
    }

    @Test
    public void unterminatedTagAtEndIsDropped() throws IOException {
        assertFalse(scan("<p>a<a href=\"x").contains("href"));
    }

    @Test
    public void textDecodingReadsWindows1251FromProlog() throws IOException {
        String xml = "<?xml version=\"1.0\" encoding=\"windows-1251\"?><p>Тихая гавань</p>";
        byte[] bytes = xml.getBytes(Charset.forName("windows-1251"));
        assertEquals("<p>Тихая гавань</p>", scanBytes(bytes));
    }

    @Test
    public void textDecodingHonoursUtf8BomAndSkipsIt() throws IOException {
        byte[] body = "<p>Нора</p>".getBytes(Charset.forName("UTF-8"));
        byte[] bytes = new byte[body.length + 3];
        bytes[0] = (byte) 0xEF;
        bytes[1] = (byte) 0xBB;
        bytes[2] = (byte) 0xBF;
        System.arraycopy(body, 0, bytes, 3, body.length);
        assertEquals("<p>Нора</p>", scanBytes(bytes));
    }

    @Test
    public void textDecodingReadsUtf16Bom() throws IOException {
        assertEquals("<p>Кай</p>", scanBytes("\uFEFF<p>Кай</p>".getBytes(Charset.forName("UTF-16BE"))));
        assertEquals("<p>Кай</p>", scanBytes("\uFEFF<p>Кай</p>".getBytes(Charset.forName("UTF-16LE"))));
    }

    @Test
    public void textDecodingFallsBackToUtf8ForUnknownEncoding() throws IOException {
        String xml = "<?xml version=\"1.0\" encoding=\"no-such-charset\"?><p>Нора и Кай</p>";
        assertEquals("<p>Нора и Кай</p>", scanBytes(xml.getBytes(Charset.forName("UTF-8"))));
    }

    @Test
    public void textDecodingDefaultsToUtf8WithoutProlog() throws IOException {
        assertEquals("<p>Гавань</p>", scanBytes("<p>Гавань</p>".getBytes(Charset.forName("UTF-8"))));
    }

    @Test
    public void textDecodingReadsKoi8r() throws IOException {
        String xml = "<?xml version='1.0' encoding='koi8-r'?><p>Маяк</p>";
        assertEquals("<p>Маяк</p>", scanBytes(xml.getBytes(Charset.forName("koi8-r"))));
    }

    private static String scanBytes(byte[] bytes) throws IOException {
        InputStream in = new ByteArrayInputStream(bytes);
        Log log = new Log();
        MarkupScanner.scan(TextDecoding.open(in), log);
        return log.out.toString();
    }
}
