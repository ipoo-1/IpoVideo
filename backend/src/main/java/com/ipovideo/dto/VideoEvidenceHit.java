package com.ipovideo.dto;

public record VideoEvidenceHit(
        long startMs,
        long endMs,
        String text,
        double score,
        String timestampSource
) {

    public VideoEvidenceHit(long startMs, long endMs, String text, double score) {
        this(startMs, endMs, text, score, "UNKNOWN");
    }
}
