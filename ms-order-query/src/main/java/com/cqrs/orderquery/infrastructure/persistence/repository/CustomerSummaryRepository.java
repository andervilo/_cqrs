package com.cqrs.orderquery.infrastructure.persistence.repository;

import java.util.UUID;
import com.cqrs.orderquery.infrastructure.persistence.entity.CustomerSummaryEntity;
import org.springframework.data.jpa.repository.*;

public interface CustomerSummaryRepository extends JpaRepository<CustomerSummaryEntity,UUID>, JpaSpecificationExecutor<CustomerSummaryEntity> {}
