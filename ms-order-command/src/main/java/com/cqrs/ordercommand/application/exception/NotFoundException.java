package com.cqrs.ordercommand.application.exception;

public class NotFoundException extends RuntimeException {
    public NotFoundException(String resource) { super(resource + " nao encontrado."); }
}
