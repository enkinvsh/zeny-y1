package com.themoon.y1.reader;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public final class BookBuilderTest {

    static List<String> words(BookIndex b) {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < b.wordCount(); i++) {
            out.add(new String(b.text, b.wordStart[i], b.wordLength(i)));
        }
        return out;
    }

    private static BookIndex of(String s) {
        BookBuilder b = new BookBuilder();
        b.text(s);
        return b.build();
    }

    private static List<String> list(String... w) {
        List<String> out = new ArrayList<String>();
        for (String x : w) out.add(x);
        return out;
    }

    @Test
    public void dialogueDashIsGluedToNextWordWithNbsp() {
        assertEquals(list("\u2014\u00A0Привет,", "Нора"), words(of("— Привет, Нора")));
    }

    @Test
    public void allDashKindsGlueForward() {
        assertEquals(list("\u2013\u00A0Да", "\u002D\u00A0нет", "\u2014\u00A0ладно"), words(of("– Да - нет — ладно")));
    }

    @Test
    public void dashAcrossLineBreakStillGlues() {
        assertEquals(list("\u2014\u00A0Идём"), words(of("—\n\t Идём")));
    }

    @Test
    public void dashTokenSplitAcrossTextCallsStillGlues() {
        BookBuilder b = new BookBuilder();
        b.text("—");
        b.text(" Кай");
        assertEquals(list("\u2014\u00A0Кай"), words(b.build()));
    }

    @Test
    public void trailingLoneDashAtEndOfParagraphIsDropped() {
        BookBuilder b = new BookBuilder();
        b.text("Он ушёл —");
        b.endParagraph();
        b.text("Она осталась");
        BookIndex idx = b.build();
        assertEquals(list("Он", "ушёл", "Она", "осталась"), words(idx));
        assertTrue((idx.flags[1] & BookIndex.F_PARA_END) != 0);
    }

    @Test
    public void dashAttachedToWordIsNotSplit() {
        assertEquals(list("\u2014Привет", "а-а"), words(of("—Привет а-а")));
    }

    @Test
    public void hyphenatedAndVeryLongWordsStayWhole() {
        StringBuilder longWord = new StringBuilder();
        for (int i = 0; i < 29; i++) longWord.append('о');
        assertEquals(list("кто-нибудь", "по-прежнему", longWord.toString()),
                words(of("кто-нибудь по-прежнему " + longWord)));
    }

    @Test
    public void ellipsisEndsSentenceBothForms() {
        BookIndex idx = of("Он ждал… Потом ушёл... Всё");
        assertTrue((idx.flags[1] & BookIndex.F_SENT_END) != 0);
        assertTrue((idx.flags[3] & BookIndex.F_SENT_END) != 0);
        assertEquals(0, idx.flags[4] & BookIndex.F_SENT_END);
    }

    @Test
    public void closingQuoteOrBracketAfterPeriodKeepsSentenceEnd() {
        BookIndex idx = of("\u00ABНет.\u00BB Она (вышла.) \u201CДа!\u201D Тут");
        assertTrue((idx.flags[0] & BookIndex.F_SENT_END) != 0);
        assertTrue((idx.flags[2] & BookIndex.F_SENT_END) != 0);
        assertTrue((idx.flags[3] & BookIndex.F_SENT_END) != 0);
    }

    @Test
    public void periodAfterAbbreviationCountsAsSentenceEndOnPurpose() {
        // Осознанное допущение: сокращение «г.» даёт лишнюю паузу, зато код не тащит словарь сокращений.
        BookIndex idx = of("в 1990 г. Нора");
        assertTrue((idx.flags[2] & BookIndex.F_SENT_END) != 0);
    }

    @Test
    public void commaColonSemicolonGiveMidPause() {
        BookIndex idx = of("да, нет: ну; всё");
        assertTrue((idx.flags[0] & BookIndex.F_PAUSE_MID) != 0);
        assertTrue((idx.flags[1] & BookIndex.F_PAUSE_MID) != 0);
        assertTrue((idx.flags[2] & BookIndex.F_PAUSE_MID) != 0);
        assertEquals(0, idx.flags[3]);
    }

    @Test
    public void allWhitespaceRunsCollapseAndEmptyWordsNeverAppear() {
        assertEquals(list("a", "b", "c"), words(of("  a \n\r\t  b\u00A0\u00A0c  ")));
        assertEquals(0, of("   \n\t \u00A0 ").wordCount());
        assertEquals(0, of("").wordCount());
    }

    @Test
    public void thinAndNarrowSpacesSeparateWords() {
        assertEquals(list("раз", "два", "три", "четыре"), words(of("раз\u2009два\u200Aтри\u202Fчетыре")));
        assertEquals(list("раз", "два"), words(of("раз\u2007два")));
    }

    @Test
    public void zeroWidthSpaceAndSoftHyphenAreRemoved() {
        assertEquals(list("собака", "яблоко"), words(of("со\u200Bбака я\u00ADблоко")));
    }

    @Test
    public void wordSplitAcrossTextCallsIsOneWord() {
        BookBuilder b = new BookBuilder();
        b.text("при");
        b.text("вет мир");
        assertEquals(list("привет", "мир"), words(b.build()));
    }

    @Test
    public void textFromCharArrayMatchesCharSequence() {
        BookBuilder b = new BookBuilder();
        char[] c = "xx Нора и Кай yy".toCharArray();
        b.text(c, 3, 10);
        assertEquals(list("Нора", "и", "Кай"), words(b.build()));
    }

    @Test
    public void paragraphEndMarksLastWordAsParagraphAndSentenceEnd() {
        BookBuilder b = new BookBuilder();
        b.text("Один два");
        b.endParagraph();
        b.endParagraph();
        b.text("три");
        b.endParagraph();
        BookIndex idx = b.build();
        assertEquals(BookIndex.F_PARA_END | BookIndex.F_SENT_END, idx.flags[1]);
        assertEquals(BookIndex.F_PARA_END | BookIndex.F_SENT_END, idx.flags[2]);
        assertEquals(BookIndex.F_CHAP_START, idx.flags[0]);
    }

    @Test
    public void chaptersRecordTitlesAndFirstWords() {
        BookBuilder b = new BookBuilder();
        b.startChapter("Первая");
        b.text("Один два.");
        b.endParagraph();
        b.text("Три");
        b.startChapter("  Вторая \n глава ");
        b.text("Четыре");
        BookIndex idx = b.build();
        assertArrayEquals(new String[]{"Первая", "Вторая глава"}, idx.chapterTitles);
        assertArrayEquals(new int[]{0, 3}, idx.chapterFirstWord);
        assertTrue((idx.flags[0] & BookIndex.F_CHAP_START) != 0);
        assertTrue((idx.flags[3] & BookIndex.F_CHAP_START) != 0);
        assertEquals(0, idx.flags[1] & BookIndex.F_CHAP_START);
        assertTrue("начало главы закрывает абзац", (idx.flags[2] & BookIndex.F_PARA_END) != 0);
    }

    @Test
    public void chapterWithoutWordsIsReplacedByNextOne() {
        BookBuilder b = new BookBuilder();
        b.startChapter("Пустая");
        b.startChapter("Настоящая");
        b.text("слово");
        BookIndex idx = b.build();
        assertArrayEquals(new String[]{"Настоящая"}, idx.chapterTitles);
        assertArrayEquals(new int[]{0}, idx.chapterFirstWord);
    }

    @Test
    public void textWithoutChapterCreatesImplicitFirstChapter() {
        BookIndex idx = of("слово");
        assertEquals(1, idx.chapterTitles.length);
        assertEquals(0, idx.chapterFirstWord[0]);
    }

    @Test
    public void chapterAfterLoneDashDoesNotLoseItsStart() {
        BookBuilder b = new BookBuilder();
        b.startChapter("А");
        b.text("—");
        b.endParagraph();
        b.text("слово");
        BookIndex idx = b.build();
        assertEquals(list("слово"), words(idx));
        assertEquals(1, idx.chapterTitles.length);
        assertTrue((idx.flags[0] & BookIndex.F_CHAP_START) != 0);
        assertEquals("А", idx.chapterTitles[0]);
    }

    @Test
    public void forceSentenceEndMarksLastWord() {
        BookBuilder b = new BookBuilder();
        b.text("Глава первая");
        b.forceSentenceEnd();
        b.text("Текст");
        BookIndex idx = b.build();
        assertTrue((idx.flags[1] & BookIndex.F_SENT_END) != 0);
        assertEquals(0, idx.flags[0] & BookIndex.F_SENT_END);
    }

    @Test
    public void metaIsKept() {
        BookBuilder b = new BookBuilder();
        b.meta("Тихая гавань", "Нора Вест");
        b.text("слово");
        BookIndex idx = b.build();
        assertEquals("Тихая гавань", idx.title);
        assertEquals("Нора Вест", idx.author);
    }

    @Test
    public void nullMetaBecomesEmptyStrings() {
        BookBuilder b = new BookBuilder();
        b.meta(null, null);
        BookIndex idx = b.build();
        assertEquals("", idx.title);
        assertEquals("", idx.author);
    }

    @Test
    public void wordStartLayoutMatchesContract() {
        BookIndex idx = of("аб в гдеж");
        assertEquals("аб в гдеж", new String(idx.text));
        assertArrayEquals(new int[]{0, 3, 5, 10}, idx.wordStart);
        assertEquals(2, idx.wordLength(0));
        assertEquals(4, idx.wordLength(2));
    }

    @Test
    public void emptyBookHasContractLayout() {
        BookIndex idx = new BookBuilder().build();
        assertEquals(0, idx.wordCount());
        assertArrayEquals(new int[]{1}, idx.wordStart);
        assertEquals(0, idx.chapterTitles.length);
    }

    @Test
    public void avgDelayFactorIsOneForPlainShortWords() {
        assertEquals(1.0f, of("раз два три").avgDelayFactor, 1e-6);
    }

    @Test
    public void avgDelayFactorGrowsWithPausesAndLongWords() {
        BookBuilder b = new BookBuilder();
        b.text("Раз. Два. Три.");
        BookIndex idx = b.build();
        assertEquals(2.5f, idx.avgDelayFactor, 1e-6);
    }

    @Test
    public void cleanTitleCollapsesWhitespace() {
        assertEquals("а б в", BookBuilder.cleanTitle("  а\u00A0 б\n\tв "));
        assertEquals("", BookBuilder.cleanTitle(null));
    }

    @Test
    public void bigBookBuildsInReasonableTime() {
        BookBuilder b = new BookBuilder();
        char[] chunk = "Утром Нора вышла к причалу, Кай ждал у лодки. ".toCharArray();
        long t0 = System.nanoTime();
        for (int i = 0; i < 40000; i++) b.text(chunk, 0, chunk.length);
        BookIndex idx = b.build();
        long ms = (System.nanoTime() - t0) / 1000000;
        assertEquals(40000 * 9, idx.wordCount());
        assertTrue("сборка заняла " + ms + " мс", ms < 5000);
    }
}
