package com.skillorbit.skillorbit_ai.synthetic;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Minimal RFC-4180 CSV reader/writer. Always LF line endings so checksums are OS-independent. */
public final class Csv {
    private Csv() {}

    static String esc(String s) {
        if (s == null) return "";
        if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    public static void write(Path p, String[] header, List<String[]> rows) throws IOException {
        Files.createDirectories(p.getParent());
        try (BufferedWriter w = Files.newBufferedWriter(p, StandardCharsets.UTF_8)) {
            w.write(String.join(",", header));
            w.write('\n');
            StringBuilder sb = new StringBuilder();
            for (String[] r : rows) {
                sb.setLength(0);
                for (int i = 0; i < r.length; i++) {
                    if (i > 0) sb.append(',');
                    sb.append(esc(r[i]));
                }
                sb.append('\n');
                w.write(sb.toString());
            }
        }
    }

    /** Returns all rows including the header row. */
    public static List<String[]> read(Path p) throws IOException {
        String s = Files.readString(p, StandardCharsets.UTF_8);
        List<String[]> out = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        StringBuilder f = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (q) {
                if (c == '"') {
                    if (i + 1 < s.length() && s.charAt(i + 1) == '"') { f.append('"'); i++; } else q = false;
                } else f.append(c);
            } else if (c == '"') q = true;
            else if (c == ',') { cur.add(f.toString()); f.setLength(0); }
            else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < s.length() && s.charAt(i + 1) == '\n') i++;
                cur.add(f.toString()); f.setLength(0);
                if (!(cur.size() == 1 && cur.get(0).isEmpty())) out.add(cur.toArray(new String[0]));
                cur = new ArrayList<>();
            } else f.append(c);
        }
        if (f.length() > 0 || !cur.isEmpty()) {
            cur.add(f.toString());
            if (!(cur.size() == 1 && cur.get(0).isEmpty())) out.add(cur.toArray(new String[0]));
        }
        return out;
    }
}
