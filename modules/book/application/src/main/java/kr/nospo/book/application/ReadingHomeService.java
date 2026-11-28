package kr.nospo.book.application;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.nospo.book.contract.DailyReading;
import kr.nospo.support.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReadingHomeService {
    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final ShelfStore shelf;

    ReadingHomeService(ShelfStore shelf) {
        this.shelf = shelf;
    }

    public record Summary(LocalDate from, LocalDate to, String timeZone, long pages, long episodes,
            long activeDays, List<DailyReading> daily) {}
    public record Calendar(String month, String timeZone, List<DailyReading> days) {}

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Summary summary(UUID owner, LocalDate from, LocalDate to) {
        long days = ChronoUnit.DAYS.between(from, to);
        if (days < 0 || days >= 366) throw new ApiException(400, "VALIDATION_ERROR");
        List<DailyReading> daily = daily(owner, from, to);
        return new Summary(from, to, SEOUL.getId(),
                daily.stream().mapToLong(DailyReading::pages).sum(),
                daily.stream().mapToLong(DailyReading::episodes).sum(),
                daily.stream().filter(DailyReading::hasActivity).count(), daily);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Calendar calendar(UUID owner, YearMonth month) {
        return new Calendar(month.toString(), SEOUL.getId(), daily(owner, month.atDay(1), month.atEndOfMonth()));
    }

    private List<DailyReading> daily(UUID owner, LocalDate from, LocalDate to) {
        LocalDate endDate = to.plusDays(1);
        var end = endDate.atStartOfDay(SEOUL).toInstant();
        var rules = SEOUL.getRules();
        Map<LocalDate, DailyReading> totals = new HashMap<>();
        // Group in SQL using an explicit offset. Historical Seoul offset changes split the UTC interval.
        for (var start = from.atStartOfDay(SEOUL).toInstant(); start.isBefore(end); ) {
            var transition = rules.nextTransition(start);
            var until = transition != null && transition.getInstant().isBefore(end) ? transition.getInstant() : end;
            for (var row : shelf.dailyTotals(owner, start, until, rules.getOffset(start).getTotalSeconds())) {
                totals.merge(row.date(), row, (a, b) ->
                        DailyReading.of(a.date(), a.pages() + b.pages(), a.episodes() + b.episodes()));
            }
            start = until;
        }
        return from.datesUntil(endDate).map(day -> {
            var row = totals.get(day);
            return row == null ? DailyReading.of(day, 0, 0) : row;
        }).toList();
    }
}
