package io.soffa.foundation.errors;

public class DatabaseException extends TechnicalException {

    private static final long serialVersionUID = -9219661125423412050L;

    public DatabaseException(Throwable cause, String messsage, Object... args) {
        super(cause, messsage, args);
    }

    public DatabaseException(String messsage, Object... args) {
        super(messsage, args);
    }
}