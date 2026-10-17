package com.themoon.y1.views;

import java.util.ArrayList;
import java.util.List;

/**
 * Разбор SVG-пути (атрибут d) в абсолютные сегменты. Чистая Java без android.*, чтобы проверять
 * JUnit-ом. Нужен только для глифов Hugeicons: у них встречаются M L H V C S Q Z, абсолютные
 * и относительные. Дуги (A) намеренно не поддержаны: набор free-icons обходится без них, а
 * молчаливое пропускание дуги дало бы тихо сломанную иконку, поэтому разбор падает.
 *
 * Формат сегмента: {op, координаты...}; op: 0 moveTo(x,y), 1 lineTo(x,y),
 * 2 cubicTo(x1,y1,x2,y2,x,y), 3 quadTo(x1,y1,x,y), 4 close.
 */
public final class SvgPathData {
    public static final int MOVE = 0;
    public static final int LINE = 1;
    public static final int CUBIC = 2;
    public static final int QUAD = 3;
    public static final int CLOSE = 4;

    private SvgPathData() {}

    public static List<float[]> parse(String d) {
        List<float[]> out = new ArrayList<float[]>();
        int[] pos = {0};
        char cmd = 0;
        float cx = 0, cy = 0, sx = 0, sy = 0;
        // Последняя контрольная точка для S/T: отражается относительно текущей.
        float lcx = 0, lcy = 0;
        boolean lastCubic = false;
        int n = d.length();
        while (true) {
            skipSeparators(d, pos);
            if (pos[0] >= n) break;
            char c = d.charAt(pos[0]);
            if (Character.isLetter(c)) {
                cmd = c;
                pos[0]++;
            } else if (cmd == 0) {
                throw new IllegalArgumentException("путь начинается не с команды: " + d);
            } else if (cmd == 'M') {
                cmd = 'L';
            } else if (cmd == 'm') {
                cmd = 'l';
            }
            boolean rel = Character.isLowerCase(cmd);
            char up = Character.toUpperCase(cmd);
            switch (up) {
                case 'M': {
                    float x = num(d, pos), y = num(d, pos);
                    if (rel) { x += cx; y += cy; }
                    out.add(new float[] {MOVE, x, y});
                    cx = x; cy = y; sx = x; sy = y;
                    lastCubic = false;
                    break;
                }
                case 'L': {
                    float x = num(d, pos), y = num(d, pos);
                    if (rel) { x += cx; y += cy; }
                    out.add(new float[] {LINE, x, y});
                    cx = x; cy = y;
                    lastCubic = false;
                    break;
                }
                case 'H': {
                    float x = num(d, pos);
                    if (rel) x += cx;
                    out.add(new float[] {LINE, x, cy});
                    cx = x;
                    lastCubic = false;
                    break;
                }
                case 'V': {
                    float y = num(d, pos);
                    if (rel) y += cy;
                    out.add(new float[] {LINE, cx, y});
                    cy = y;
                    lastCubic = false;
                    break;
                }
                case 'C': {
                    float x1 = num(d, pos), y1 = num(d, pos);
                    float x2 = num(d, pos), y2 = num(d, pos);
                    float x = num(d, pos), y = num(d, pos);
                    if (rel) { x1 += cx; y1 += cy; x2 += cx; y2 += cy; x += cx; y += cy; }
                    out.add(new float[] {CUBIC, x1, y1, x2, y2, x, y});
                    lcx = x2; lcy = y2; cx = x; cy = y;
                    lastCubic = true;
                    break;
                }
                case 'S': {
                    float x2 = num(d, pos), y2 = num(d, pos);
                    float x = num(d, pos), y = num(d, pos);
                    if (rel) { x2 += cx; y2 += cy; x += cx; y += cy; }
                    float x1 = lastCubic ? 2 * cx - lcx : cx;
                    float y1 = lastCubic ? 2 * cy - lcy : cy;
                    out.add(new float[] {CUBIC, x1, y1, x2, y2, x, y});
                    lcx = x2; lcy = y2; cx = x; cy = y;
                    lastCubic = true;
                    break;
                }
                case 'Q': {
                    float x1 = num(d, pos), y1 = num(d, pos);
                    float x = num(d, pos), y = num(d, pos);
                    if (rel) { x1 += cx; y1 += cy; x += cx; y += cy; }
                    out.add(new float[] {QUAD, x1, y1, x, y});
                    cx = x; cy = y;
                    lastCubic = false;
                    break;
                }
                case 'Z': {
                    out.add(new float[] {CLOSE});
                    cx = sx; cy = sy;
                    lastCubic = false;
                    break;
                }
                default:
                    throw new IllegalArgumentException("команда SVG не поддержана: " + cmd);
            }
        }
        return out;
    }

    private static void skipSeparators(String d, int[] pos) {
        int n = d.length();
        while (pos[0] < n) {
            char c = d.charAt(pos[0]);
            if (c == ' ' || c == ',' || c == '\n' || c == '\t' || c == '\r') pos[0]++;
            else break;
        }
    }

    /** Число с учётом форм вида "-.5", "1.5.5" (два числа подряд) и экспоненты. */
    private static float num(String d, int[] pos) {
        skipSeparators(d, pos);
        int n = d.length();
        int start = pos[0];
        int i = start;
        if (i < n && (d.charAt(i) == '-' || d.charAt(i) == '+')) i++;
        boolean dot = false;
        while (i < n) {
            char c = d.charAt(i);
            if (c >= '0' && c <= '9') {
                i++;
            } else if (c == '.' && !dot) {
                dot = true;
                i++;
            } else if ((c == 'e' || c == 'E') && i > start) {
                i++;
                if (i < n && (d.charAt(i) == '-' || d.charAt(i) == '+')) i++;
            } else {
                break;
            }
        }
        if (i == start) throw new IllegalArgumentException("ожидалось число в позиции " + start + ": " + d);
        pos[0] = i;
        return Float.parseFloat(d.substring(start, i));
    }
}
