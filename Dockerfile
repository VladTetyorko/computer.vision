# Minimal runtime image for the vision-app Spring Boot jar (docs/plans/done/MVP1-PLAN.md
# §C9). This Dockerfile does NOT run Maven -- it only copies the already-built
# executable jar, so the image stays small and fast to (re)build. Build the
# jar first:
#
#   ./mvnw -B package -DskipTests
#
# then either let docker-compose build this image (`docker compose up -d`,
# see docker-compose.yml's vision-app service) or build/run it directly:
#
#   docker build -t vision-app .
#   docker run --rm -p 8080:8080 vision-app
#
# Context is the repo root because the jar lives under vision-app/target/,
# one level below where a Dockerfile scoped to just vision-app/ could reach.
FROM eclipse-temurin:21-jre

WORKDIR /app

# Wildcard match: the pom pins the version (0.0.1-SNAPSHOT today) but this
# avoids having to edit this file on every version bump.
COPY vision-app/target/vision-app-*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
