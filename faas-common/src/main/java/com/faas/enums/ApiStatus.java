package com.faas.enums;

public enum ApiStatus {
    ENQUEUED(202),
    FAIL(404);

    private final int code;

    ApiStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}