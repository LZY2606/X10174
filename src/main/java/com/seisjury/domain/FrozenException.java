package com.seisjury.domain;

public class FrozenException extends RuntimeException {
    public FrozenException(String message) {
        super(message);
    }
}
