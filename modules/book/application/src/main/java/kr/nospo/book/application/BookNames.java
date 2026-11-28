package kr.nospo.book.application;

import java.text.Normalizer;
import java.util.Locale;

public final class BookNames {
    private BookNames() {}

    public static String query(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFC)
                .replaceAll("(?U)\\s+", " ").strip();
    }

    public static String key(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    public static String isbn(String value) {
        return key(value).replace("-", "").replace(" ", "");
    }
}
