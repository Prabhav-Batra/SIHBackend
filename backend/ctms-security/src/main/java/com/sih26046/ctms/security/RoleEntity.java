package com.sih26046.ctms.security;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/** One of the seven roles (§8.3). Reference data. */
@Entity
@Table(name = "roles")
public class RoleEntity {

    @Id private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    protected RoleEntity() {} // JPA

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDisplayName() {
        return displayName;
    }
}
