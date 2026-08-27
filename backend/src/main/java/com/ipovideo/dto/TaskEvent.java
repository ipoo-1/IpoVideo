package com.ipovideo.dto;

public record TaskEvent(String stage, String message, int progress) {
}
