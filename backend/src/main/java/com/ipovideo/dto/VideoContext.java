// VideoContext.java
package com.ipovideo.dto;

import java.util.List;

public record VideoContext(
        List<VideoSegment> segments
) {
}