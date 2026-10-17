package com.themoon.y1.reader;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Минимальный JSON для positions.json и кэша библиотеки.
 *
 * Свой, а не org.json: на JVM (JUnit) org.json из Android недоступен, а подключать зависимость ради
 * двух файлов нет смысла. Читатель ждёт чужой, возможно битый файл с карты, поэтому на любой порче
 * бросает IOException, а глубина вложенности ограничена.
 *
 * Значения: Map (LinkedHashMap), List, String, Long, Double, Boolean, null.
 */
public final class MiniJson {

    private static final int MAX_DEPTH = 32;

    private final String s;
    private int pos;

    private MiniJson(String s) {
        this.s = s;
    }

    public static Object parse(String json) throws IOException {
        MiniJson p = new MiniJson(json);
        p.skipWs();
        Object v = p.value(0);
        p.skipWs();
        if (p.pos != json.length()) throw p.fail("Лишнее после значения");
        return v;
    }

    private IOException fail(String why) {
        return new IOException(why + " (позиция " + pos + ")");
    }

    private void skipWs() {
        while (pos < s.length()) {
            char c = s.charAt(pos);
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') pos++;
            else break;
        }
    }

    private Object value(int depth) throws IOException {
        if (depth > MAX_DEPTH) throw fail("Слишком глубокая вложенность");
        if (pos >= s.length()) throw fail("Неожиданный конец");
        char c = s.charAt(pos);
        if (c == '{') return object(depth);
        if (c == '[') return array(depth);
        if (c == '"') return string();
        if (c == 't') return literal("true", Boolean.TRUE);
        if (c == 'f') return literal("false", Boolean.FALSE);
        if (c == 'n') return literal("null", null);
        if (c == '-' || (c >= '0' && c <= '9')) return number();
        throw fail("Неожиданный символ");
    }

    private Object literal(String word, Object value) throws IOException {
        if (!s.startsWith(word, pos)) throw fail("Ожидалось " + word);
        pos += word.length();
        return value;
    }

    private Map<String, Object> object(int depth) throws IOException {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        pos++;
        skipWs();
        if (pos < s.length() && s.charAt(pos) == '}') {
            pos++;
            return m;
        }
        for (;;) {
            skipWs();
            if (pos >= s.length() || s.charAt(pos) != '"') throw fail("Ожидался ключ");
            String key = string();
            skipWs();
            if (pos >= s.length() || s.charAt(pos) != ':') throw fail("Ожидалось двоеточие");
            pos++;
            skipWs();
            m.put(key, value(depth + 1));
            skipWs();
            if (pos >= s.length()) throw fail("Объект не закрыт");
            char c = s.charAt(pos++);
            if (c == '}') return m;
            if (c != ',') throw fail("Ожидалась запятая");
        }
    }

    private List<Object> array(int depth) throws IOException {
        List<Object> list = new ArrayList<Object>();
        pos++;
        skipWs();
        if (pos < s.length() && s.charAt(pos) == ']') {
            pos++;
            return list;
        }
        for (;;) {
            skipWs();
            list.add(value(depth + 1));
            skipWs();
            if (pos >= s.length()) throw fail("Массив не закрыт");
            char c = s.charAt(pos++);
            if (c == ']') return list;
            if (c != ',') throw fail("Ожидалась запятая");
        }
    }

    private String string() throws IOException {
        pos++;
        StringBuilder sb = new StringBuilder();
        while (pos < s.length()) {
            char c = s.charAt(pos++);
            if (c == '"') return sb.toString();
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (pos >= s.length()) break;
            char e = s.charAt(pos++);
            switch (e) {
                case '"':
                case '\\':
                case '/':
                    sb.append(e);
                    break;
                case 'b':
                    sb.append('\b');
                    break;
                case 'f':
                    sb.append('\f');
                    break;
                case 'n':
                    sb.append('\n');
                    break;
                case 'r':
                    sb.append('\r');
                    break;
                case 't':
                    sb.append('\t');
                    break;
                case 'u':
                    if (pos + 4 > s.length()) throw fail("Обрыв \\u");
                    int code = 0;
                    for (int i = 0; i < 4; i++) {
                        int d = Character.digit(s.charAt(pos + i), 16);
                        if (d < 0) throw fail("Плохой \\u");
                        code = code * 16 + d;
                    }
                    pos += 4;
                    sb.append((char) code);
                    break;
                default:
                    throw fail("Неизвестное экранирование");
            }
        }
        throw fail("Строка не закрыта");
    }

    private Object number() throws IOException {
        int start = pos;
        if (s.charAt(pos) == '-') pos++;
        int digits = 0;
        while (pos < s.length() && Character.isDigit(s.charAt(pos))) {
            pos++;
            digits++;
        }
        if (digits == 0) throw fail("Ожидались цифры");
        boolean fractional = false;
        if (pos < s.length() && s.charAt(pos) == '.') {
            fractional = true;
            pos++;
            while (pos < s.length() && Character.isDigit(s.charAt(pos))) pos++;
        }
        if (pos < s.length() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
            fractional = true;
            pos++;
            if (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) pos++;
            while (pos < s.length() && Character.isDigit(s.charAt(pos))) pos++;
        }
        String num = s.substring(start, pos);
        try {
            return fractional ? (Object) Double.valueOf(num) : (Object) Long.valueOf(num);
        } catch (NumberFormatException e) {
            throw fail("Плохое число");
        }
    }

    // ------------------------------------------------------------------ запись

    /** Map, List, String, Number, Boolean и null; порядок ключей сохраняется (используйте LinkedHashMap). */
    public static String write(Object v) {
        StringBuilder sb = new StringBuilder();
        write(sb, v);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String) {
            quote(sb, (String) v);
        } else if (v instanceof Boolean || v instanceof Integer || v instanceof Long) {
            sb.append(v);
        } else if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            sb.append(Double.isNaN(d) || Double.isInfinite(d) ? "0" : v.toString());
        } else if (v instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                quote(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object o : (List<?>) v) {
                if (!first) sb.append(',');
                first = false;
                write(sb, o);
            }
            sb.append(']');
        } else {
            quote(sb, v.toString());
        }
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    // ------------------------------------------------------------------ чтение полей с запасным значением

    public static int getInt(Map<String, Object> m, String key, int def) {
        Object v = m.get(key);
        return v instanceof Number ? ((Number) v).intValue() : def;
    }

    public static long getLong(Map<String, Object> m, String key, long def) {
        Object v = m.get(key);
        return v instanceof Number ? ((Number) v).longValue() : def;
    }

    public static String getString(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        return v instanceof String ? (String) v : def;
    }

    public static boolean getBool(Map<String, Object> m, String key, boolean def) {
        Object v = m.get(key);
        return v instanceof Boolean ? (Boolean) v : def;
    }
}
