package com.cqrs.ordercommand.infrastructure.persistence.repository;

import com.cqrs.ordercommand.infrastructure.persistence.entity.OrderOutboxEntity;
import org.springframework.data.jpa.repository.*;
import java.util.UUID;

public interface OrderOutboxRepository extends JpaRepository<OrderOutboxEntity, UUID>, JpaSpecificationExecutor<OrderOutboxEntity> {
}
