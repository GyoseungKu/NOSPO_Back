package kr.nospo.book.application;

import java.util.List;
import kr.nospo.book.contract.SearchType;

/** Adapter for the selected provider. Implementations map upstream failures to Unavailable. */
public interface BookSearchProvider {
    String source();

    default boolean transientResults() {
        return false;
    }

    Results search(String query, SearchType type, String continuation, int limit) throws Unavailable;

    record Results(List<EditionMetadata> items, String nextContinuation) {
        public Results {
            items = List.copyOf(items);
        }
    }

    class InvalidContinuation extends RuntimeException {}

    class Unavailable extends RuntimeException {
        public Unavailable() {
            super("Book provider unavailable");
        }
    }
}
