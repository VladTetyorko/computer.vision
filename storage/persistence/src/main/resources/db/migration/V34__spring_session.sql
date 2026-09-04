-- docs/plans/active/AUTH-ROLES-PLAN.md §3.6, wave B5: sessions that survive the station. Spring
-- Session JDBC (station/vision-app) replaces Tomcat's in-memory session map with rows in this same
-- Postgres database -- a station restart (redeploy, crash, `docker compose restart`) no longer logs
-- every operator out. Flyway owns this schema, not Spring Session's own `initialize-schema` (kept
-- `never`, application.yaml) -- same "one migration path, not two" rule as every other table here.
--
-- Byte-for-byte copy of spring-session-jdbc 4.1.0's own
-- org/springframework/session/jdbc/schema-postgresql.sql (extracted from the jar, not retyped),
-- unquoted identifiers and all -- Postgres folds unquoted identifiers to lowercase, and so does
-- every hardcoded SQL string JdbcOperationsSessionRepository issues against these same names, so
-- the two stay consistent by construction. Do not rename/quote anything here: a name mismatch would
-- 500 on the very first login rather than fail at migration time.
CREATE TABLE SPRING_SESSION (
	PRIMARY_ID CHAR(36) NOT NULL,
	SESSION_ID CHAR(36) NOT NULL,
	CREATION_TIME BIGINT NOT NULL,
	LAST_ACCESS_TIME BIGINT NOT NULL,
	MAX_INACTIVE_INTERVAL INT NOT NULL,
	EXPIRY_TIME BIGINT NOT NULL,
	PRINCIPAL_NAME VARCHAR(100),
	CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
);

CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
CREATE INDEX SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);

CREATE TABLE SPRING_SESSION_ATTRIBUTES (
	SESSION_PRIMARY_ID CHAR(36) NOT NULL,
	ATTRIBUTE_NAME VARCHAR(200) NOT NULL,
	ATTRIBUTE_BYTES BYTEA NOT NULL,
	CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
	CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID) REFERENCES SPRING_SESSION(PRIMARY_ID) ON DELETE CASCADE
);
