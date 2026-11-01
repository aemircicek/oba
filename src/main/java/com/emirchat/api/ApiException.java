package com.emirchat.api;

/** An error reported by (or to be reported to) the other side of the client/server protocol. */
public final class ApiException extends Exception {
    private static final long serialVersionUID = 1L;

    private final ErrorCode code;

    public ApiException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
