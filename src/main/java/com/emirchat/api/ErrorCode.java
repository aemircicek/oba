package com.emirchat.api;

/** Machine-readable error codes the server can return. */
public enum ErrorCode {
    PROTOCOL(1),
    INVALID_USERNAME(2),
    WEAK_PASSWORD(3),
    USERNAME_TAKEN(4),
    BAD_CREDENTIALS(5),
    RATE_LIMITED(6),
    NOT_LOGGED_IN(7),
    ALREADY_LOGGED_IN(8),
    INTERNAL(9);

    final int code;

    ErrorCode(int code) {
        this.code = code;
    }

    static ErrorCode of(int code) {
        for (ErrorCode c : values()) if (c.code == code) return c;
        return PROTOCOL;
    }
}
