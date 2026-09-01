package com.sih26046.ctms.security;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserAccountRepository extends JpaRepository<UserAccountEntity, UUID> {

    /**
     * Looks up an account by email, case-insensitively.
     *
     * <p>The cast is required and not decorative. users.email is {@code citext}, but the JDBC
     * driver binds a Java String as {@code varchar}; PostgreSQL then resolves
     * {@code citext = varchar} through {@code text} and compares case-sensitively. The column
     * being citext makes *insertion* case-insensitively unique, but it does not by itself make
     * this *lookup* case-insensitive. Without the cast a user who registered as A@x.in cannot
     * sign in as a@x.in.
     */
    @Query(value = "SELECT * FROM users WHERE email = CAST(:email AS citext)", nativeQuery = true)
    Optional<UserAccountEntity> findByEmail(@Param("email") String email);
}
