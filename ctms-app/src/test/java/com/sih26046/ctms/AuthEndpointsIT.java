package com.sih26046.ctms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** §18.3, §18.5, §18.6, §6.5 — the authentication endpoints and permission gating. */
@SpringBootTest
@AutoConfigureMockMvc
class AuthEndpointsIT extends AbstractPostgresIT {

    private static final String PASSWORD = "a-sufficiently-long-passphrase";

    @Autowired MockMvc mockMvc;

    @Autowired PasswordEncoder passwordEncoder;

    @Autowired JdbcTemplate jdbc;

    private String createUser(String roleName) {
        String email = UUID.randomUUID() + "@example.in";
        String roleId =
                jdbc.queryForObject("SELECT id FROM roles WHERE name = ?", String.class, roleName);
        jdbc.update(
                "INSERT INTO users (email, password_hash, full_name, role_id) VALUES"
                        + " (?,?,?,?::uuid)",
                email,
                passwordEncoder.encode(PASSWORD),
                "Endpoint Tester",
                roleId);
        return email;
    }

    private MvcResult login(String email, String password) throws Exception {
        return mockMvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"email\":\"%s\",\"password\":\"%s\"}"
                                                .formatted(email, password)))
                .andReturn();
    }

    private Cookie[] cookiesFrom(MvcResult result) {
        return result.getResponse().getCookies();
    }

    private Cookie named(MvcResult result, String name) {
        return List.of(cookiesFrom(result)).stream()
                .filter(c -> c.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no cookie named " + name));
    }

    @Test
    void loginSetsBothCookies() throws Exception {
        MvcResult result = login(createUser("RESEARCH_STAFF"), PASSWORD);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(named(result, "access_token").getValue()).isNotBlank();
        assertThat(named(result, "refresh_token").getValue()).isNotBlank();
    }

    @Test
    void cookiesCarryTheAttributesFromTheSpecification() throws Exception {
        MvcResult result = login(createUser("RESEARCH_STAFF"), PASSWORD);
        List<String> setCookie = result.getResponse().getHeaders("Set-Cookie");

        String access =
                setCookie.stream().filter(h -> h.startsWith("access_token")).findFirst()
                        .orElseThrow();
        String refresh =
                setCookie.stream().filter(h -> h.startsWith("refresh_token")).findFirst()
                        .orElseThrow();

        // §18.3 — HttpOnly closes the XSS exfiltration path; the refresh cookie is Strict and
        // path-scoped so it never travels on ordinary API calls.
        assertThat(access).contains("HttpOnly").contains("Secure").contains("SameSite=Lax");
        assertThat(access).contains("Path=/");
        assertThat(refresh).contains("HttpOnly").contains("Secure").contains("SameSite=Strict");
        assertThat(refresh).contains("Path=/api/v1/auth/refresh");
    }

    @Test
    void loginWithAWrongPasswordIsRejectedGenerically() throws Exception {
        MvcResult result = login(createUser("RESEARCH_STAFF"), "wrong-password");

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        // §18.5 — the message must not distinguish wrong password from unknown account.
        assertThat(result.getResponse().getContentAsString())
                .contains("Invalid email or password");
    }

    @Test
    void meRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void meReturnsTheCallersIdentityAndPermissions() throws Exception {
        String email = createUser("RESEARCH_STAFF");
        Cookie access = named(login(email, PASSWORD), "access_token");

        mockMvc.perform(get("/api/v1/auth/me").cookie(access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.role").value("RESEARCH_STAFF"))
                .andExpect(jsonPath("$.permissions").isArray());
    }

    @Test
    void aRoleWithoutThePermissionIsForbidden() throws Exception {
        Cookie access = named(login(createUser("RESEARCH_STAFF"), PASSWORD), "access_token");

        // §6.4 — lacking the permission is 403, and is distinct from being out of scope.
        mockMvc.perform(get("/api/v1/users").cookie(access))
                .andExpect(status().isForbidden());
    }

    @Test
    void aRoleHoldingThePermissionIsAllowed() throws Exception {
        Cookie access = named(login(createUser("SYSTEM_ADMIN"), PASSWORD), "access_token");

        mockMvc.perform(get("/api/v1/users").cookie(access)).andExpect(status().isOk());
    }

    @Test
    void refreshRotatesBothCookies() throws Exception {
        MvcResult loggedIn = login(createUser("RESEARCH_STAFF"), PASSWORD);
        Cookie refresh = named(loggedIn, "refresh_token");

        MvcResult refreshed =
                mockMvc.perform(post("/api/v1/auth/refresh").cookie(refresh))
                        .andExpect(status().isOk())
                        .andReturn();

        assertThat(named(refreshed, "refresh_token").getValue())
                .isNotEqualTo(refresh.getValue());
        assertThat(named(refreshed, "access_token").getValue()).isNotBlank();
    }

    @Test
    void replayingARefreshTokenIsRejected() throws Exception {
        MvcResult loggedIn = login(createUser("RESEARCH_STAFF"), PASSWORD);
        Cookie refresh = named(loggedIn, "refresh_token");

        mockMvc.perform(post("/api/v1/auth/refresh").cookie(refresh)).andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/auth/refresh").cookie(refresh))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRevokesTheSession() throws Exception {
        MvcResult loggedIn = login(createUser("RESEARCH_STAFF"), PASSWORD);
        Cookie access = named(loggedIn, "access_token");

        mockMvc.perform(post("/api/v1/auth/logout").cookie(access))
                .andExpect(status().isNoContent());

        // The access token is still cryptographically valid, but its session is revoked —
        // which is exactly why sessions exist as server-side state (§8.6).
        mockMvc.perform(get("/api/v1/auth/me").cookie(access))
                .andExpect(status().isUnauthorized());
    }
}
