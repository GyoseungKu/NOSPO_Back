ALTER TABLE wiki ADD CONSTRAINT fk_wiki_book FOREIGN KEY (book_id) REFERENCES book_edition(book_id);
ALTER TABLE wiki_draft ADD CONSTRAINT fk_wiki_draft_book FOREIGN KEY (book_id) REFERENCES book_edition(book_id);
