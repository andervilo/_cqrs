package com.cqrs.ordercommand.infrastructure.persistence.repository;

import com.cqrs.ordercommand.infrastructure.persistence.entity.ProductEntity;
import org.springframework.data.jpa.repository.*;
import java.util.UUID;

public interface ProductRepository extends JpaRepository<ProductEntity, UUID>, JpaSpecificationExecutor<ProductEntity> {
}
