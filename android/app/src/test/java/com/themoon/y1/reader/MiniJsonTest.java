package com.themoon.y1.reader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public final class MiniJsonTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> obj(Object o) {
        return (Map<String, Object>) o;
    }

    private static void rejects(String json) {
        try {
            MiniJson.parse(json);
            fail("должно отвергаться: " + json);
        } catch (IOException expected) {
            // ожидаемо
        }
    }

    @Test
    public void roundTripOfNestedStructure() throws IOException {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("s", "Тихая гавань");
        m.put("n", 42);
        m.put("big", 1234567890123L);
        m.put("neg", -5L);
        m.put("t", true);
        m.put("f", false);
        m.put("z", null);
        List<Object> list = new ArrayList<Object>();
        list.add(1);
        list.add("x");
        Map<String, Object> inner = new LinkedHashMap<String, Object>();
        inner.put("k", "v");
        list.add(inner);
        m.put("list", list);
        Map<String, Object> back = obj(MiniJson.parse(MiniJson.write(m)));
        assertEquals("Тихая гавань", back.get("s"));
        assertEquals(42L, back.get("n"));
        assertEquals(1234567890123L, back.get("big"));
        assertEquals(-5L, back.get("neg"));
        assertEquals(Boolean.TRUE, back.get("t"));
        assertEquals(Boolean.FALSE, back.get("f"));
        assertTrue(back.containsKey("z"));
        assertNull(back.get("z"));
        assertEquals(3, ((List<?>) back.get("list")).size());
        assertEquals("v", obj(((List<?>) back.get("list")).get(2)).get("k"));
    }

    @Test
    public void stringEscapesSurvive() throws IOException {
        String tricky = "кавычка \" слэш \\ перевод\nстроки\tтаб \u0001 эмодзи \uD83D\uDE00 </script>";
        assertEquals(tricky, MiniJson.parse(MiniJson.write(tricky)));
    }

    @Test
    public void parsesUnicodeEscapesAndSurrogatePairs() throws IOException {
        assertEquals("Кай \uD83D\uDE00 /", MiniJson.parse("\"\\u041a\\u0430\\u0439 \\ud83d\\ude00 \\/\""));
    }

    @Test
    public void toleratesWhitespaceEverywhere() throws IOException {
        Map<String, Object> m = obj(MiniJson.parse(" \n{ \"a\" :\t[ 1 , 2 ] , \"b\" : { } }\r\n"));
        assertEquals(2, ((List<?>) m.get("a")).size());
        assertEquals(0, obj(m.get("b")).size());
    }

    @Test
    public void parsesDoubleAndExponent() throws IOException {
        assertEquals(1.5, (Double) MiniJson.parse("1.5"), 0);
        assertEquals(1000.0, (Double) MiniJson.parse("1e3"), 0);
    }

    @Test
    public void writerKeepsOrderOfKeys() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("b", 1);
        m.put("a", 2);
        assertEquals("{\"b\":1,\"a\":2}", MiniJson.write(m));
    }

    @Test
    public void garbageIsRejected() {
        rejects("");
        rejects("   ");
        rejects("{");
        rejects("{\"a\":}");
        rejects("{\"a\" 1}");
        rejects("[1,]");
        rejects("[1 2]");
        rejects("\"unterminated");
        rejects("{\"a\":1} trailing");
        rejects("tru");
        rejects("nul");
        rejects("\"\\u12\"");
        rejects("\"\\q\"");
        rejects("-");
        rejects("99999999999999999999");
        rejects("{'a':1}");
        rejects("\u0000\u0001\u0002");
    }

    @Test
    public void deepNestingIsRejectedInsteadOfOverflowingStack() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5000; i++) sb.append('[');
        rejects(sb.toString());
    }

    @Test
    public void helpersReadWithDefaults() throws IOException {
        Map<String, Object> m = obj(MiniJson.parse("{\"n\":7,\"s\":\"x\",\"b\":true,\"d\":2.0}"));
        assertEquals(7, MiniJson.getInt(m, "n", 0));
        assertEquals(9, MiniJson.getInt(m, "none", 9));
        assertEquals(9, MiniJson.getInt(m, "s", 9));
        assertEquals(7L, MiniJson.getLong(m, "n", 0));
        assertEquals(2L, MiniJson.getLong(m, "d", 0));
        assertEquals("x", MiniJson.getString(m, "s", ""));
        assertEquals("?", MiniJson.getString(m, "n", "?"));
        assertTrue(MiniJson.getBool(m, "b", false));
        assertTrue(MiniJson.getBool(m, "none", true));
    }
}
