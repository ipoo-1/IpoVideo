package com.ipovideo.dto;

import java.util.List;

public record VideoSegment(
        long startMs,
        long endMs,
        String asrText,
        String ocrText,
        List<String> frames,
        String timestampSource
) {

    public VideoSegment(long startMs, long endMs, String asrText, String ocrText, List<String> frames) {
        this(startMs, endMs, asrText, ocrText, frames, "UNKNOWN");
    }
}
