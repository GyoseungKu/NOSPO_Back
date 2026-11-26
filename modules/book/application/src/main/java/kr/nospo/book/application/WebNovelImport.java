package kr.nospo.book.application;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import kr.nospo.book.contract.BookType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** For an approved metadata import job. The application does not invoke it at startup. */
@Service
public class WebNovelImport {
    public static final String SOURCE = "WEB_NOVEL_SEED";
    private final BookStore store;

    public WebNovelImport(BookStore store) {
        this.store = store;
    }

    @Transactional
    public UUID importEdition(Novel novel) {
        if (novel.workId() != null) store.storeWork(novel.workId());
        UUID bookId = store.storeEdition(novel.workId(), BookType.WEB_NOVEL, SOURCE, novel.edition());
        var stored = store.find(bookId).orElseThrow();
        if (!Objects.equals(stored.workId(), novel.workId())
                || !Objects.equals(stored.totalUnits(), novel.edition().totalUnits())) {
            throw new IllegalArgumentException("Edition work and total units must match the stored metadata");
        }
        novel.episodes().forEach(episode -> store.storeEpisode(bookId, episode));
        return bookId;
    }

    public record Novel(UUID workId, EditionMetadata edition, List<ChapterEpisode> episodes) {
        public Novel {
            Objects.requireNonNull(edition);
            episodes = List.copyOf(episodes);
            var sequences = new java.util.HashSet<Integer>();
            for (ChapterEpisode episode : episodes) {
                if (!sequences.add(episode.sequence())
                        || (edition.totalUnits() != null && episode.sequence() > edition.totalUnits())) {
                    throw new IllegalArgumentException("Invalid episode sequence");
                }
            }
        }
    }

    public record ChapterEpisode(int sequence, String episodeLabel, String volumeLabel, String chapterLabel) {
        public ChapterEpisode {
            if (sequence < 1) throw new IllegalArgumentException("Invalid episode sequence");
            EditionMetadata.requireText(episodeLabel, 255);
            EditionMetadata.checkLength(volumeLabel, 255);
            EditionMetadata.checkLength(chapterLabel, 255);
        }
    }
}
