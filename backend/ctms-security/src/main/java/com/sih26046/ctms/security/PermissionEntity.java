package com.sih26046.ctms.security;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/** A single {@code resource:action} capability (§6.3). */
@Entity
@Table(name = "permissions")
public class PermissionEntity {

    @Id private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String resource;

    @Column(nullable = false)
    private String action;

    protected PermissionEntity() {} // JPA

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getResource() {
        return resource;
    }

    public String getAction() {
        return action;
    }
}
