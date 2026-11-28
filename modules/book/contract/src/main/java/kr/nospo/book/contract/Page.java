package kr.nospo.book.contract;

import java.util.List;

public record Page<T>(List<T> items, String nextCursor, boolean hasNext) {
    public static <T> Page<T> of(List<T> items, String nextCursor) {
        return new Page<>(List.copyOf(items), nextCursor, nextCursor != null);
    }
}
