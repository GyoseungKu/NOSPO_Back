package kr.nospo.wiki.application;

import java.util.Optional;
import java.util.UUID;

/** Read port backed by BookCatalog and ReadingProgressCatalog in the integrated application. */
public interface WikiBookAccess {
    enum Unit { PAGE, EPISODE }

    record Position(Unit unit, int value) {}
    record Edition(Unit positionUnit, Integer totalUnits) {}

    /** Looks up a permanent edition ID issued by Book. */
    Optional<Edition> find(UUID bookId);

    /** Returns the current position of an active shelf entry for the authorized member. */
    Optional<Position> currentPosition(UUID memberId, UUID bookId);
}
