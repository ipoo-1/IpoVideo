
package com.ipovideo.dto;

import java.util.List;

public record VideoSegment(
        long startMs,
        long endMs,
        String asrText,
        String ocrText,
        List<String> frames
) {
}