package com.cqrs.orderquery.application.exception;
public class QueryNotFoundException extends RuntimeException {
    public QueryNotFoundException() { super("Projection not found."); }
    public QueryNotFoundException(String message) { super(message); }
}
