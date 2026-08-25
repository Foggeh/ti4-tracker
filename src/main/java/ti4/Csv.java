package ti4;

import java.util.ArrayList;
import java.util.List;

/** Minimal CSV handling shared by the seed files in {@code data/}. */
final class Csv {

    private Csv() {
    }

    /** True for blank lines and {@code #} comments, which callers skip. */
    static boolean isSkippable(String line) {
        String trimmed = line.strip();
        return trimmed.isEmpty() || trimmed.startsWith("#");
    }

    /** Minimal RFC 4180 style split: handles quoted fields and doubled quotes. */
    static List<String> parseLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    boolean escapedQuote = i + 1 < line.length() && line.charAt(i + 1) == '"';
                    if (escapedQuote) {
                        current.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    current.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                fields.add(current.toString().strip());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString().strip());
        return fields;
    }

    static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
