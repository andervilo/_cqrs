package com.cqrs.orderquery.application.exception;
public class InvalidQueryException extends RuntimeException {
    public InvalidQueryException() { super("Invalid query."); }
    public InvalidQueryException(String message) { super(message); }
}
