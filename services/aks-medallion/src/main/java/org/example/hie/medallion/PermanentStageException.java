package org.example.hie.medallion;

class PermanentStageException extends RuntimeException {
    private final String reason;

    PermanentStageException(String reason, Throwable cause) {
        super(reason, cause);
        this.reason = reason;
    }

    String reason() {
        return reason;
    }
}
