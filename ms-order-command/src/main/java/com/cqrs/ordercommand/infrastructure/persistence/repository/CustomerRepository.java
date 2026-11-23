package com.cqrs.ordercommand.infrastructure.persistence.repository;

import com.cqrs.ordercommand.infrastructure.persistence.entity.CustomerEntity;
import org.springframework.data.jpa.repository.*;
import java.util.UUID;

public interface CustomerRepository extends JpaRepository<CustomerEntity, UUID>, JpaSpecificationExecutor<CustomerEntity> {
}
