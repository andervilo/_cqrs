package com.cqrs.orderquery.infrastructure.persistence.repository;

import java.util.UUID;
import com.cqrs.orderquery.infrastructure.persistence.entity.ProductDetailEntity;
import org.springframework.data.jpa.repository.*;

public interface ProductDetailRepository extends JpaRepository<ProductDetailEntity,UUID>, JpaSpecificationExecutor<ProductDetailEntity> {}
