package kr.nospo.book.integration;

import kr.nospo.book.application.BookIsbn;

final class KakaoIsbn {
    private KakaoIsbn() {}

    static String canonical(String value) {
        return BookIsbn.canonical(value);
    }
}
