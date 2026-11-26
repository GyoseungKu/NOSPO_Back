package kr.nospo.book.persistence;

import static java.nio.charset.StandardCharsets.UTF_8;
import static kr.nospo.support.SqlIds.id;
import static kr.nospo.support.SqlIds.uuid;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import kr.nospo.book.application.BookNames;
import kr.nospo.book.application.BookStore;
import kr.nospo.book.application.EditionMetadata;
import kr.nospo.book.application.WebNovelImport.ChapterEpisode;
import kr.nospo.book.contract.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcBookStore implements BookStore {
    private final JdbcTemplate db;
    private final NamedParameterJdbcTemplate named;

    public JdbcBookStore(JdbcTemplate db) {
        this.db = db;
        this.named = new NamedParameterJdbcTemplate(db);
    }

    @Override
    public Optional<Book> find(UUID bookId) {
        return withAuthors(db.query("SELECT * FROM book_edition WHERE book_id=?", this::book, id(bookId)))
                .stream().findFirst();
    }

    @Override
    public UUID storeEdition(UUID workId, BookType type, String source, EditionMetadata metadata) {
        UUID candidate = UUID.randomUUID();
        byte[] item = metadata.sourceItemId().getBytes(UTF_8);
        db.update("""
                INSERT INTO book_edition(book_id,work_id,book_type,title,publisher,edition_label,isbn,isbn_key,
                    cover_url,description,source,source_item_id,total_units,publication_date)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE book_id=book_id
                """, id(candidate), id(workId), type.name(), BookNames.query(metadata.title()), metadata.publisher(),
                metadata.editionLabel(), metadata.isbn(), metadata.isbn() == null ? null : BookNames.isbn(metadata.isbn()),
                metadata.coverUrl(), metadata.description(), source, item, metadata.totalUnits(), metadata.publicationDate());
        UUID bookId = db.queryForObject("SELECT book_id FROM book_edition WHERE source=? AND source_item_id=?",
                (row, index) -> uuid(row.getBytes(1)), source, item);
        if (candidate.equals(bookId)) {
            for (int index = 0; index < metadata.authors().size(); index++) {
                db.update("INSERT INTO book_author(book_id,author_order,author_name) VALUES (?,?,?)",
                        id(bookId), index, BookNames.query(metadata.authors().get(index)));
            }
        }
        return bookId;
    }

    @Override
    public void storeWork(UUID workId) {
        db.update("INSERT INTO book_work(work_id) VALUES (?) ON DUPLICATE KEY UPDATE work_id=work_id", id(workId));
    }

    @Override
    public void storeEpisode(UUID bookId, ChapterEpisode episode) {
        db.update("""
                INSERT INTO book_episode(episode_id,book_id,sequence_no,episode_label,volume_label,chapter_label)
                VALUES (?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE episode_label=?,volume_label=?,chapter_label=?
                """, id(UUID.randomUUID()), id(bookId), episode.sequence(), episode.episodeLabel(),
                episode.volumeLabel(), episode.chapterLabel(), episode.episodeLabel(),
                episode.volumeLabel(), episode.chapterLabel());
    }

    @Override
    public List<Book> searchNovels(String query, SearchType type, UUID after, int fetch) {
        String filter = switch (type) {
            case TITLE -> "LOCATE(?, LOWER(b.title)) > 0";
            case AUTHOR -> """
                    EXISTS (SELECT 1 FROM book_author a WHERE a.book_id=b.book_id
                        AND LOCATE(?, LOWER(a.author_name)) > 0)
                    """;
            case ISBN -> "b.isbn_key = ?";
        };
        var args = new ArrayList<>();
        args.add(type == SearchType.ISBN ? BookNames.isbn(query) : BookNames.key(query));
        String afterFilter = after == null ? "" : " AND b.book_id > ?";
        if (after != null) args.add(id(after));
        args.add(fetch);
        return withAuthors(db.query("SELECT b.* FROM book_edition b WHERE b.book_type='WEB_NOVEL' AND "
                + filter + afterFilter + " ORDER BY b.book_id LIMIT ?", this::book, args.toArray()));
    }

    @Override
    public List<Episode> episodes(UUID bookId, int after, int fetch) {
        return db.query("""
                SELECT episode_id,sequence_no,episode_label,volume_label,chapter_label
                FROM book_episode WHERE book_id=? AND sequence_no>? ORDER BY sequence_no LIMIT ?
                """, (row, index) -> new Episode(uuid(row.getBytes(1)), row.getInt(2), row.getString(3),
                row.getString(4), row.getString(5)), id(bookId), after, fetch);
    }

    private Book book(ResultSet row, int index) throws SQLException {
        BookType type = BookType.valueOf(row.getString("book_type"));
        return new Book(uuid(row.getBytes("book_id")), uuid(row.getBytes("work_id")), type,
                row.getString("title"), List.of(), row.getString("publisher"), row.getString("edition_label"),
                row.getString("isbn"), row.getString("cover_url"), row.getString("description"),
                row.getString("source"), new String(row.getBytes("source_item_id"), UTF_8),
                type == BookType.BOOK ? PositionUnit.PAGE : PositionUnit.EPISODE,
                row.getObject("total_units", Integer.class), row.getObject("publication_date", LocalDate.class));
    }

    private List<Book> withAuthors(List<Book> books) {
        if (books.isEmpty()) return books;
        Map<UUID, List<String>> authors = new HashMap<>();
        named.query("""
                SELECT book_id,author_name FROM book_author WHERE book_id IN (:ids) ORDER BY book_id,author_order
                """, Map.of("ids", books.stream().map(book -> id(book.bookId())).toList()), row -> {
                    authors.computeIfAbsent(uuid(row.getBytes(1)), ignored -> new ArrayList<>()).add(row.getString(2));
                });
        return books.stream().map(book -> new Book(book.bookId(), book.workId(), book.bookType(), book.title(),
                List.copyOf(authors.getOrDefault(book.bookId(), List.of())), book.publisher(), book.editionLabel(),
                book.isbn(), book.coverUrl(), book.description(), book.source(), book.sourceItemId(),
                book.positionUnit(), book.totalUnits(), book.publicationDate())).toList();
    }
}
