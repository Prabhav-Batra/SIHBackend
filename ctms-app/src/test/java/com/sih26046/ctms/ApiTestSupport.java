package com.sih26046.ctms;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Shared helpers for API tests: create a user of a given role and sign in as them.
 *
 * <p>Users are created through the owner connection, because provisioning a user is not the
 * behaviour under test and doing it through the API would couple every resource's tests to the
 * user-administration endpoints.
 */
public abstract class ApiTestSupport extends AbstractPostgresIT {

    protected static final String PASSWORD = "a-sufficiently-long-passphrase";

    @Autowired protected MockMvc mockMvc;

    @Autowired protected PasswordEncoder passwordEncoder;

    /** Signs in as a freshly created user holding {@code roleName}. */
    protected Cookie loginAs(String roleName) throws Exception {
        return loginAs(roleName, null);
    }

    protected Cookie loginAs(String roleName, UUID institutionId) throws Exception {
        String email = UUID.randomUUID() + "@example.in";
        ownerJdbc()
                .update(
                        "INSERT INTO users (email, password_hash, full_name, role_id,"
                                + " institution_id) VALUES (?,?,?,(SELECT id FROM roles WHERE name"
                                + " = ?),?::uuid)",
                        email,
                        passwordEncoder.encode(PASSWORD),
                        "API Tester",
                        roleName,
                        institutionId == null ? null : institutionId.toString());

        MvcResult result =
                mockMvc.perform(
                                post("/api/v1/auth/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"email\":\"%s\",\"password\":\"%s\"}"
                                                        .formatted(email, PASSWORD)))
                        .andExpect(status().isOk())
                        .andReturn();

        for (Cookie cookie : result.getResponse().getCookies()) {
            if (cookie.getName().equals("access_token")) {
                return cookie;
            }
        }
        throw new AssertionError("login returned no access cookie");
    }

    @SuppressWarnings("unchecked")
    protected <T> T read(MvcResult result, String path) throws Exception {
        return (T) JsonPath.read(result.getResponse().getContentAsString(), path);
    }
}
