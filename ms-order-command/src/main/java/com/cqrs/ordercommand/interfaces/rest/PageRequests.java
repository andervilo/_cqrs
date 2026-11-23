package com.cqrs.ordercommand.interfaces.rest;

import org.springframework.data.domain.*;

final class PageRequests {
    private PageRequests() {}
    static Pageable of(int page, int size) { return of(page, size, "createdAt"); }
    static Pageable of(int page, int size, String dateField) {
        return PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, dateField, "id"));
    }
}
