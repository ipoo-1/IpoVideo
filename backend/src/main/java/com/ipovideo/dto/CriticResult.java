// CriticResult.java
package com.ipovideo.dto;

import java.util.List;

public record CriticResult(
        boolean passed,
        List<String> issues
) {
}