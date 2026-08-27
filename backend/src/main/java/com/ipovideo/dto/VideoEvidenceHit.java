package com.ipovideo.dto;

public record VideoEvidenceHit(
        long startMs,
        long endMs,
        String text,
        double score
) {
}
