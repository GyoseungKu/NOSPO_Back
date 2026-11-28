package kr.nospo.nospo_backend.ai;

import kr.nospo.ai.application.AiBookContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Fallback;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static kr.nospo.ai.application.AiBookContext.*;

@Configuration(proxyBeanMethods = false)
class AiContextConfiguration {
    static final UUID SAMPLE_BOOK = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID CHARACTER = UUID.fromString("00000000-0000-0000-0000-000000000102");
    private static final UUID ENTRY = UUID.fromString("00000000-0000-0000-0000-000000000103");

    private static final UUID APPRENTICE = UUID.fromString("00000000-0000-0000-0000-000000000104");
    private static final UUID RELATION = UUID.fromString("00000000-0000-0000-0000-000000000105");

    @Bean
    @Profile("ai-local")
    @Primary
    AiBookContext sampleAiBookContext() {
        return new AiBookContext() {
            public boolean ready() { return true; }

            public Optional<Book> book(UUID bookId) {
                return SAMPLE_BOOK.equals(bookId) ? Optional.of(new Book(Unit.EPISODE, 100)) : Optional.empty();
            }

            public Optional<Position> currentPosition(UUID memberId, UUID bookId) {
                return book(bookId).map(book -> new Position(book.unit(), 40));
            }

            public Optional<Snapshot> visibleSnapshot(UUID bookId, Position position) {
                return book(bookId).map(ignored -> {
                    if (position.value() < 10) return new Snapshot(1, List.of(), List.of(), List.of());
                    var librarian = new Entity(CHARACTER, "CHARACTER", "하린", "별빛 도서관 사서", null);
                    var entry = new Entry(ENTRY, CHARACTER, "하린은 10화에서 별빛 도서관의 지도 보관소를 연다.",
                            new Position(Unit.EPISODE, 10));
                    if (position.value() < 20) return new Snapshot(1, List.of(librarian), List.of(entry), List.of());
                    var apprentice = new Entity(APPRENTICE, "CHARACTER", "유나", "별빛 도서관 견습 사서", null);
                    var relation = new Relation(RELATION, CHARACTER, APPRENTICE, "MENTOR",
                            "하린은 유나에게 지도 보관소 관리법을 가르친다.", new Position(Unit.EPISODE, 20));
                    return new Snapshot(1, List.of(librarian, apprentice), List.of(entry), List.of(relation));
                });
            }
        };
    }

    @Bean
    @Profile("!ai-local")
    @Fallback
    AiBookContext unavailableAiBookContext() {
        return new AiBookContext() {
            public boolean ready() { return false; }
            public Optional<Book> book(UUID bookId) { return Optional.empty(); }
            public Optional<Position> currentPosition(UUID memberId, UUID bookId) { return Optional.empty(); }
            public Optional<Snapshot> visibleSnapshot(UUID bookId, Position position) { return Optional.empty(); }
        };
    }
}
