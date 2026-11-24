package com.cqrs.orderquery.infrastructure.persistence.repository;

import java.util.UUID;
import com.cqrs.orderquery.infrastructure.persistence.entity.CustomerOrderHistoryEntity;
import org.springframework.data.jpa.repository.*;

public interface CustomerOrderHistoryRepository extends JpaRepository<CustomerOrderHistoryEntity,UUID>, JpaSpecificationExecutor<CustomerOrderHistoryEntity> {}
