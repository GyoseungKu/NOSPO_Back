package kr.nospo.book.contract;

import java.util.UUID;

public record Episode(UUID episodeId, int sequence, String episodeLabel, String volumeLabel, String chapterLabel) {}
