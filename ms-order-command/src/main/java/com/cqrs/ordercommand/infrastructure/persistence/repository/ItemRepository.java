package com.cqrs.ordercommand.infrastructure.persistence.repository;

import com.cqrs.ordercommand.infrastructure.persistence.entity.ItemEntity;
import org.springframework.data.jpa.repository.*;
import java.util.UUID;

public interface ItemRepository extends JpaRepository<ItemEntity, UUID> {
    java.util.Optional<ItemEntity> findByIdAndOrderId(UUID id, UUID orderId);
    boolean existsByProductId(UUID productId);
}
