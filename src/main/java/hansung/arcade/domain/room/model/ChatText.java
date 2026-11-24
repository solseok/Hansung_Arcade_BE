package hansung.arcade.domain.room.model;

import java.text.Normalizer;

public final class ChatText {
    static final int MAX_BYTES = 64;

    private ChatText() {}

    public static boolean isBlank(int c) {
        return Character.isWhitespace(c)
                || Character.isSpaceChar(c)
                || c == 0x3164
                || c == 0x115f
                || c == 0x1160
                || c == 0xffa0
                || c == 0x2800;
    }

    public static String stripBlank(String text) {
        int first = 0, last = text.length();
        while (first < last && isBlank(text.codePointAt(first)))
            first += Character.charCount(text.codePointAt(first));
        while (last > first && isBlank(text.codePointBefore(last)))
            last -= Character.charCount(text.codePointBefore(last));
        return text.substring(first, last);
    }

    public static int byteLength(String text) {
        return text.chars().map(c -> c < 128 ? 1 : 2).sum();
    }

    public static String clean(String text) {
        if (text == null) return "";
        StringBuilder line = new StringBuilder();
        Normalizer.normalize(text, Normalizer.Form.NFC)
                .codePoints()
                .forEach(
                        c -> {
                            if (Character.getType(c) == Character.FORMAT
                                    || c >= 0xd800 && c <= 0xdfff) return;
                            line.appendCodePoint(
                                    Character.isISOControl(c) || c == 0x2028 || c == 0x2029
                                            ? ' '
                                            : c);
                        });
        String value = stripBlank(line.toString());
        StringBuilder result = new StringBuilder();
        int bytes = 0;
        for (int c : value.codePoints().toArray()) {
            int size = c < 128 ? 1 : 2 * Character.charCount(c);
            if (bytes + size > MAX_BYTES) break;
            result.appendCodePoint(c);
            bytes += size;
        }
        return stripBlank(result.toString());
    }
}
