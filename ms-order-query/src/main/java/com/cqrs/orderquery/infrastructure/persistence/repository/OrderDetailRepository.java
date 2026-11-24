package com.cqrs.orderquery.infrastructure.persistence.repository;

import java.util.UUID;
import com.cqrs.orderquery.infrastructure.persistence.entity.OrderDetailEntity;
import org.springframework.data.jpa.repository.*;

public interface OrderDetailRepository extends JpaRepository<OrderDetailEntity,UUID>, JpaSpecificationExecutor<OrderDetailEntity> {}
