package com.cqrs.ordercommand.infrastructure.persistence.repository;

import com.cqrs.ordercommand.infrastructure.persistence.entity.OrderEntity;
import org.springframework.data.jpa.repository.*;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<OrderEntity, UUID>, JpaSpecificationExecutor<OrderEntity> {
    boolean existsByCustomerId(UUID customerId);
}
