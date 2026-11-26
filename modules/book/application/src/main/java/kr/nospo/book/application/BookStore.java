package kr.nospo.book.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kr.nospo.book.contract.Book;
import kr.nospo.book.contract.BookType;
import kr.nospo.book.contract.Episode;
import kr.nospo.book.contract.SearchType;

public interface BookStore {
    Optional<Book> find(UUID bookId);

    UUID storeEdition(UUID workId, BookType type, String source, EditionMetadata metadata);

    void storeWork(UUID workId);

    void storeEpisode(UUID bookId, WebNovelImport.ChapterEpisode episode);

    List<Book> searchNovels(String query, SearchType type, UUID after, int fetch);

    List<Episode> episodes(UUID bookId, int after, int fetch);
}
