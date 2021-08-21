package com.github.jojotech.id.generator;

public class IdGenerateException extends RuntimeException {
    public IdGenerateException() {
        super();
    }

    public IdGenerateException(String message, Throwable cause) {
        super(message, cause);
    }

    public IdGenerateException(Throwable cause) {
        super(cause);
    }

    protected IdGenerateException(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }

    public IdGenerateException(String msg) {
        super(msg);
    }
}
