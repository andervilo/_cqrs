package com.cqrs.orderquery.infrastructure.persistence.repository;

import java.util.UUID;
import com.cqrs.orderquery.infrastructure.persistence.entity.OrderSummaryEntity;
import org.springframework.data.jpa.repository.*;

public interface OrderSummaryRepository extends JpaRepository<OrderSummaryEntity,UUID>, JpaSpecificationExecutor<OrderSummaryEntity> {}
