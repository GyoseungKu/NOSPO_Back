package kr.nospo.book.integration;

import java.util.Locale;

final class KakaoIsbn {
    private KakaoIsbn() {}

    static String canonical(String value) {
        if (value == null) return null;
        String result = null;
        for (String token : value.strip().split("(?U)\\s+")) {
            String isbn = token.replace("-", "").toUpperCase(Locale.ROOT);
            String candidate = toIsbn13(isbn);
            if (candidate == null) continue;
            if (result != null && !result.equals(candidate)) return null;
            result = candidate;
        }
        return result;
    }

    private static String toIsbn13(String isbn) {
        if (isbn.matches("97[89][0-9]{10}"))
            return isbn.charAt(12) - '0' == checkDigit(isbn) ? isbn : null;
        if (!isbn.matches("[0-9]{9}[0-9X]")) return null;
        int sum = isbn.charAt(9) == 'X' ? 10 : isbn.charAt(9) - '0';
        for (int i = 0; i < 9; i++) sum += (10 - i) * (isbn.charAt(i) - '0');
        if (sum % 11 != 0) return null;
        String prefix = "978" + isbn.substring(0, 9);
        return prefix + checkDigit(prefix);
    }

    private static int checkDigit(String isbn) {
        int sum = 0;
        for (int i = 0; i < 12; i++) sum += (i % 2 == 0 ? 1 : 3) * (isbn.charAt(i) - '0');
        return (10 - sum % 10) % 10;
    }
}
