package kr.nospo.book.contract;

import java.time.LocalDate;

public record DailyReading(LocalDate date, long pages, long episodes, boolean hasActivity) {
    public static DailyReading of(LocalDate date, long pages, long episodes) {
        return new DailyReading(date, pages, episodes, pages > 0 || episodes > 0);
    }
}
