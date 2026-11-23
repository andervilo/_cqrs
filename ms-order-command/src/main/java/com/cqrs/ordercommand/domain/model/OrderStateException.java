package com.cqrs.ordercommand.domain.model;

public class OrderStateException extends IllegalStateException {
    public OrderStateException(String message) { super(message); }
}
