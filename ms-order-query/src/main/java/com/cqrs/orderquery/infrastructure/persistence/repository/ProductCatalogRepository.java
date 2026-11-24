package com.cqrs.orderquery.infrastructure.persistence.repository;

import java.util.UUID;
import com.cqrs.orderquery.infrastructure.persistence.entity.ProductCatalogEntity;
import org.springframework.data.jpa.repository.*;

public interface ProductCatalogRepository extends JpaRepository<ProductCatalogEntity,UUID>, JpaSpecificationExecutor<ProductCatalogEntity> {}
