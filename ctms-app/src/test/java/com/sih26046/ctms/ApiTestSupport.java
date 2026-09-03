package com.sih26046.ctms;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.sih26046.ctms.security.AuthCookies;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Shared helpers for API tests: create a user of a given role and sign in as them.
 *
 * <p>Users are created through the owner connection, because provisioning a user is not the
 * behaviour under test and doing it through the API would couple every resource's tests to the
 * user-administration endpoints.
 *
 * <p>{@link #loginAs} returns both the session's {@code access_token} and {@code csrf_token}
 * cookies as a {@code Cookie[]}, rather than a single {@code Cookie}. {@code MockMvc}'s {@code
 * .cookie(Cookie...)} accepts that array directly wherever a single cookie used to be passed, so
 * every existing {@code .cookie(session)} call site — GET included — keeps working unchanged and
 * now also carries the CSRF cookie. State-changing requests additionally need the matching {@code
 * X-CSRF-Token} header, attached with {@link #csrf(Cookie[])}.
 */
public abstract class ApiTestSupport extends AbstractPostgresIT {

    protected static final String PASSWORD = "a-sufficiently-long-passphrase";

    @Autowired protected MockMvc mockMvc;

    @Autowired protected PasswordEncoder passwordEncoder;

    /** Signs in as a freshly created user holding {@code roleName}. */
    protected Cookie[] loginAs(String roleName) throws Exception {
        return loginAs(roleName, null);
    }

    protected Cookie[] loginAs(String roleName, UUID institutionId) throws Exception {
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

        Cookie access = null;
        Cookie csrf = null;
        for (Cookie cookie : result.getResponse().getCookies()) {
            if (cookie.getName().equals(AuthCookies.ACCESS_COOKIE)) {
                access = cookie;
            } else if (cookie.getName().equals(AuthCookies.CSRF_COOKIE)) {
                csrf = cookie;
            }
        }
        if (access == null || csrf == null) {
            throw new AssertionError("login returned no access/csrf cookie");
        }
        return new Cookie[] {access, csrf};
    }

    /**
     * A {@link RequestPostProcessor} that echoes {@code session}'s {@code csrf_token} cookie
     * value into the {@code X-CSRF-Token} header, satisfying {@code CsrfDoubleSubmitFilter}'s
     * double-submit check for state-changing requests. {@code session} must be a {@link
     * #loginAs} result carrying that cookie alongside the access cookie already attached via
     * {@code .cookie(session)}.
     */
    protected RequestPostProcessor csrf(Cookie[] session) {
        for (Cookie cookie : session) {
            if (AuthCookies.CSRF_COOKIE.equals(cookie.getName())) {
                String token = cookie.getValue();
                return request -> {
                    request.addHeader(AuthCookies.CSRF_HEADER, token);
                    return request;
                };
            }
        }
        throw new AssertionError("session missing csrf cookie");
    }

    @SuppressWarnings("unchecked")
    protected <T> T read(MvcResult result, String path) throws Exception {
        return (T) JsonPath.read(result.getResponse().getContentAsString(), path);
    }
}
