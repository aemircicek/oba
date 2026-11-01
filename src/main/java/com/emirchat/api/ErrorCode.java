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
    INTERNAL(9),
    USER_NOT_FOUND(10),
    ALREADY_FRIENDS(11),
    NOT_FRIENDS(12),
    INVALID_REQUEST(13),
    LIMIT_REACHED(14);

    final int code;

    ErrorCode(int code) {
        this.code = code;
    }

    static ErrorCode of(int code) {
        for (ErrorCode c : values()) if (c.code == code) return c;
        return PROTOCOL;
    }
}
