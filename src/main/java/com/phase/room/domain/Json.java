package com.phase.room.domain;

import com.fasterxml.jackson.databind.ObjectMapper;

public final class Json {
    public static final ObjectMapper CODEC = new ObjectMapper();

    private Json() {
    }

    public static String write(Object value) {
        try {
            return CODEC.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static <T> T read(String json, Class<T> type) {
        try {
            return CODEC.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
