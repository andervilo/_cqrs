package com.cqrs.ordercommand.infrastructure.persistence.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import com.cqrs.ordercommand.domain.model.*;

@Entity
@Table(name = "customer")
public class CustomerEntity extends AuditedEntity {
    @Column(nullable = false, length = 160)
    private String name;
    @Column(nullable = false, unique = true, length = 254)
    private String email;
    @Column(length = 24)
    private String phone;
    @Column(nullable = false)
    private boolean active;
    protected CustomerEntity() {}

    public CustomerEntity(String name, String email, String phone) {
        super(UUID.randomUUID());
        update(name, email, phone, true);
    }
    public void update(String name, String email, String phone, boolean active) {
        this.name = name.strip();
        this.email = email.strip().toLowerCase(Locale.ROOT);
        this.phone = phone == null || phone.isBlank() ? null : phone.strip();
        this.active = active;
    }
    public String getName() { return name; }
    public String getEmail() { return email; }
    public String getPhone() { return phone; }
    public boolean isActive() { return active; }
}
