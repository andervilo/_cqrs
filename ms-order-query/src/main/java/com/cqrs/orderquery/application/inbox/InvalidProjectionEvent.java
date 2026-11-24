package com.cqrs.orderquery.application.inbox;

/** Permanent contract failure; messages contain field names, never raw payloads. */
public class InvalidProjectionEvent extends RuntimeException {
    public InvalidProjectionEvent(String message) { super(message); }
}
