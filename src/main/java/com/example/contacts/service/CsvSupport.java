package com.example.contacts.service;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal RFC 4180-ish CSV reader/writer with no external dependencies.
 *
 * <p>Supports quoted fields, escaped quotes ({@code ""}), embedded commas and
 * newlines, and both LF and CRLF line endings. Sufficient for round-tripping
 * the directory's own export and tolerating typical hand-made CSV files.
 */
public final class CsvSupport {

    private CsvSupport() {
    }

    /**
     * Serialises a single row to a CSV line (no trailing newline). Each field is
     * quoted only when it contains a comma, quote or newline.
     *
     * @param fields the field values (nulls are treated as empty)
     * @return the CSV-encoded line
     */
    public static String toRow(List<String> fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(escape(fields.get(i)));
        }
        return sb.toString();
    }

    /** Quote and escape a single field if needed. */
    private static String escape(String value) {
        String v = value == null ? "" : value;
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    /**
     * Parses CSV text into rows of fields. A trailing empty line is ignored.
     *
     * @param content the raw CSV text
     * @return the parsed rows (each a list of field strings)
     */
    public static List<List<String>> parse(String content) {
        List<List<String>> rows = new ArrayList<>();
        if (content == null || content.isEmpty()) {
            return rows;
        }
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        int i = 0;
        int n = content.length();

        while (i < n) {
            char c = content.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < n && content.charAt(i + 1) == '"') {
                        field.append('"');
                        i += 2;
                    } else {
                        inQuotes = false;
                        i++;
                    }
                } else {
                    field.append(c);
                    i++;
                }
                continue;
            }
            if (c == '"') {
                inQuotes = true;
                i++;
            } else if (c == ',') {
                row.add(field.toString());
                field.setLength(0);
                i++;
            } else if (c == '\r' || c == '\n') {
                row.add(field.toString());
                field.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
                i++;
                if (c == '\r' && i < n && content.charAt(i) == '\n') {
                    i++;
                }
            } else {
                field.append(c);
                i++;
            }
        }
        // Flush the final field/row.
        row.add(field.toString());
        rows.add(row);

        // Drop a trailing empty row produced by a file ending in a newline.
        List<String> last = rows.get(rows.size() - 1);
        if (last.size() == 1 && last.get(0).isEmpty()) {
            rows.remove(rows.size() - 1);
        }
        return rows;
    }
}
