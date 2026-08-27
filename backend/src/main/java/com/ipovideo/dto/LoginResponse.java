package com.ipovideo.dto;

public record LoginResponse(String token, UserView user) {
}
