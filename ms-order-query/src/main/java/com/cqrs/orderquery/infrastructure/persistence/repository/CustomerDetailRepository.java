package com.cqrs.orderquery.infrastructure.persistence.repository;

import java.util.UUID;
import com.cqrs.orderquery.infrastructure.persistence.entity.CustomerDetailEntity;
import org.springframework.data.jpa.repository.*;

public interface CustomerDetailRepository extends JpaRepository<CustomerDetailEntity,UUID>, JpaSpecificationExecutor<CustomerDetailEntity> {}
