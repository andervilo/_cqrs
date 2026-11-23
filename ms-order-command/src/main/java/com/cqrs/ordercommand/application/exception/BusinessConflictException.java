package com.cqrs.ordercommand.application.exception;

public class BusinessConflictException extends RuntimeException {
    public BusinessConflictException(String message) { super(message); }
}
