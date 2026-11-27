package kr.nospo.wiki.application;

import java.util.UUID;

import kr.nospo.support.ApiException;
import kr.nospo.wiki.application.WikiBookAccess.Position;

public final class WikiReadingScope {
    private final WikiBookAccess books;

    public WikiReadingScope(WikiBookAccess books) {
        this.books = books;
    }

    /** The caller must authorize the member before resolving a reading scope. */
    public Position resolve(UUID memberId, UUID bookId, Position requested) {
        var edition = books.find(bookId).orElseThrow(() -> new ApiException(404, "RESOURCE_NOT_FOUND"));
        var current = books.currentPosition(memberId, bookId)
                .orElseThrow(() -> new ApiException(403, "SHELF_REQUIRED"));
        var selected = requested == null ? current : requested;
        if (current.unit() != edition.positionUnit() || selected.unit() != edition.positionUnit()
                || selected.value() < 0 || (edition.totalUnits() != null && selected.value() > edition.totalUnits()))
            throw new ApiException(422, "INVALID_POSITION");
        if (selected.value() > current.value())
            throw new ApiException(422, "POSITION_EXCEEDS_CURRENT");
        return selected;
    }
}
