package com.cqrs.orderquery.interfaces.rest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import com.cqrs.orderquery.application.exception.QueryNotFoundException;
import com.cqrs.orderquery.application.exception.InvalidQueryException;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(QueryNotFoundException.class)
    public ProblemDetail notFound(QueryNotFoundException error) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,error.getMessage());
    }
    @ExceptionHandler(InvalidQueryException.class)
    public ProblemDetail invalidQuery(InvalidQueryException error) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,error.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpectedException(Exception exception) {
        log.error("Erro inesperado ao processar requisicao", exception);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "Nao foi possivel processar a requisicao.");
    }
}
