package com.cqrs.apigateway.error;

import java.net.*;
import java.util.concurrent.TimeoutException;
import org.slf4j.*;
import org.springframework.boot.webflux.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.*;
import reactor.core.publisher.Mono;

@Component
@Order(-2)
public class GatewayErrorHandler implements ErrorWebExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GatewayErrorHandler.class);
    private final ProblemWriter writer;
    public GatewayErrorHandler(ProblemWriter writer) { this.writer = writer; }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable error) {
        if (exchange.getResponse().isCommitted()) return Mono.error(error);
        HttpStatus status = classify(error);
        String detail = switch (status) {
            case GATEWAY_TIMEOUT -> "Tempo limite ao comunicar com o serviço.";
            case SERVICE_UNAVAILABLE -> "Serviço temporariamente indisponível.";
            case BAD_GATEWAY -> "A comunicação com o serviço foi interrompida ou recebeu resposta inválida.";
            case PAYLOAD_TOO_LARGE -> "Corpo da requisição acima do limite permitido.";
            case NOT_FOUND -> "Endpoint não encontrado.";
            default -> "Não foi possível processar a requisição.";
        };
        if (status.is5xxServerError() && !exchange.getRequest().getMethod().matches("GET")) {
            detail += " O resultado da operação é indeterminado; confira o estado antes de repetir.";
        }
        log.warn("Gateway failure correlationId={} type={} status={}",
                exchange.getAttribute("correlationId"), error.getClass().getSimpleName(), status.value());
        return writer.write(exchange, status, detail);
    }

    private HttpStatus classify(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof DataBufferLimitException) return HttpStatus.PAYLOAD_TOO_LARGE;
            if (cause instanceof TimeoutException || cause.getClass().getSimpleName().contains("Timeout"))
                return HttpStatus.GATEWAY_TIMEOUT;
            if (cause instanceof ConnectException || cause instanceof UnknownHostException || cause instanceof NoRouteToHostException)
                return HttpStatus.SERVICE_UNAVAILABLE;
            if (cause instanceof ResponseStatusException e) {
                var status = HttpStatus.resolve(e.getStatusCode().value());
                if (status != null) return status;
            }
        }
        return error instanceof java.io.IOException || error.getClass().getName().startsWith("reactor.netty")
                ? HttpStatus.BAD_GATEWAY : HttpStatus.INTERNAL_SERVER_ERROR;
    }
}
