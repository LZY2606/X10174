package com.room.phase;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.TreeMap;

public final class Hashing {
    private Hashing() {}

    public static String sha256(Object value, ObjectMapper mapper) {
        try {
            String canonical = mapper.writeValueAsString(sort(value));
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : hash) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot hash version content", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object sort(Object value) {
        if (value instanceof java.util.Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            map.forEach((key, val) -> sorted.put(String.valueOf(key), sort(val)));
            return sorted;
        }
        if (value instanceof java.util.List<?> list) {
            return list.stream().map(Hashing::sort).toList();
        }
        return value;
    }
}
