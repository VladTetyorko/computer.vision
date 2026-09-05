package com.drones.vision.app;

import com.drones.vision.identity.application.GroupService;
import com.drones.vision.identity.application.UserService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.ExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.util.MultiValueMap;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The regression test AUTH-ROLES-PLAN.md's B5-fix requires: proves a real login actually persists
 * through Spring Session JDBC, against a real embedded server and a real Postgres connection — not
 * through {@code MockMvc}, which is exactly what let this break ship in the first place.
 *
 * <p><strong>Why {@link AuthEnabledFlowTest} (this package's other login test) never caught the
 * live {@code POST /api/auth/login} 500.</strong> That test builds its {@code MockMvc} by hand —
 * {@code MockMvcBuilders.webAppContextSetup(webApplicationContext).addFilters(springSecurityFilterChain)}
 * — which registers exactly one filter bean, the named Spring Security chain. Spring Boot's own
 * {@code SessionRepositoryFilter} (the filter {@code spring-boot-session-jdbc}'s autoconfiguration
 * registers as a {@code FilterRegistrationBean} against the real {@code ServletContext}) is never
 * one of the filters handed to {@code addFilters(...)}, so it is never in that test's exercised
 * chain. {@code request.getSession(...)} there resolves to a plain {@code MockHttpSession} the
 * servlet-mock layer invents on the spot — never a JDBC-backed {@code Session} — so {@code
 * HttpSessionSecurityContextRepository#saveContext} just calls {@code setAttribute} on that mock
 * object and Java serialization is never invoked at all. This is not a lazy-flush timing gap;
 * MockMvc's manual filter list structurally cannot reach {@code SessionRepositoryFilter}, so no
 * amount of extra assertions inside {@code AuthEnabledFlowTest} itself could have caught this —
 * only a test that goes through a real servlet container (this class, via {@code
 * webEnvironment = RANDOM_PORT} plus a real {@link RestTestClient#bindToServer()} HTTP round trip)
 * exercises the filter that was missing.
 *
 * <p>Deliberately its own {@code @SpringBootTest} context (a different {@code webEnvironment} from
 * every other class's default {@code MOCK}), so it is never silently folded into the cached context
 * {@link AuthEnabledFlowTest} and its siblings share — this is the one test in the module that
 * actually needs a live socket. {@link com.drones.vision.app.testsupport.PostgresContextCustomizerFactory}
 * still points it at the shared Testcontainers Postgres, and {@link
 * com.drones.vision.app.testsupport.PostgresResetTestExecutionListener} still resets that database
 * before this class's first test, exactly as for every other {@code @SpringBootTest} here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"vision.auth.enabled=true", "vision.publish.enabled=false"})
class SessionPersistenceIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private UserService userService;

    @Autowired
    private GroupService groupService;

    @BeforeEach
    void setUp() {
        DevAccountSeeder.seedIfAbsent(userService, groupService);
    }

    @Test
    void loginPersistsARealJdbcBackedSessionUnderTheSpringSessionCookie() {
        RestTestClient client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();

        // The live defect this closes: with the JDBC store genuinely active (spring-boot-session-jdbc,
        // Defect 1's fix), the un-Serializable principal graph (VisionUserDetails -> User/Ownership)
        // made this call 500 with SerializationFailedException/NotSerializableException instead of
        // returning 200 -- exactly the crash a MockMvc-only suite could never observe (see class
        // javadoc). A 500 here is this test failing for the same reason production did.
        ExchangeResult result = client.post()
                .uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"username\":\"admin\",\"password\":\"admin\"}")
                .exchange()
                .returnResult();

        assertEquals(HttpStatus.OK, result.getStatus(),
                "login must succeed against the real JDBC-backed session store, not 500 on "
                        + "SecurityContext serialization");

        MultiValueMap<String, ?> cookies = result.getResponseCookies();
        assertTrue(cookies.containsKey("SESSION"),
                "Spring Session's own cookie name is SESSION -- its absence means "
                        + "SessionRepositoryFilter never engaged (Defect 1 regressed)");
        assertFalse(cookies.containsKey("JSESSIONID"),
                "a JSESSIONID cookie means Tomcat's own in-memory session won this request, not "
                        + "Spring Session's JDBC-backed one");

        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        Integer sessionRows = jdbcTemplate.queryForObject("select count(*) from spring_session", Integer.class);
        assertTrue(sessionRows != null && sessionRows > 0,
                "a real login must leave a row in spring_session -- the whole point of wave B5's "
                        + "JDBC-backed sessions");
    }
}
