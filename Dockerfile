# syntax=docker/dockerfile:1
# ---- build stage: compile with the JDK only (no third-party dependencies) ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY build.sh ./
COPY src ./src
RUN sed -i 's/\r$//' build.sh && bash build.sh test

# ---- runtime stage: JRE + jar + static web UI ----
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/build/app.jar /app/app.jar
COPY web /app/web
COPY bench/queries.txt bench/test_queries.txt /app/bench/
COPY docker/search docker/bench docker/healthcheck /usr/local/bin/
RUN sed -i 's/\r$//' /usr/local/bin/search /usr/local/bin/bench /usr/local/bin/healthcheck \
 && chmod +x /usr/local/bin/search /usr/local/bin/bench /usr/local/bin/healthcheck \
 && mkdir -p /app/bench/results
ENV PORT=8080 \
    DATA_DIR=/data \
    WEB_DIR=/app/web \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=5s --start-period=20s --retries=3 CMD ["healthcheck"]
# exec: java becomes PID 1 and receives SIGTERM from `docker stop` (graceful shutdown hook)
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
