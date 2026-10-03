# The podcast server as an image. Build it from the root of the repository:
#
#     docker build -f server/Dockerfile -t podcast-server .
#
# The jar runs on any JVM, so the image builds for the machine that builds
# it, e.g. arm64 on a Raspberry Pi or a Mac with Apple silicon.

FROM clojure:temurin-25-tools-deps AS build
WORKDIR /build
COPY deps.edn build.clj ./
RUN clojure -P -A:server && clojure -P -T:build
COPY src src
COPY server server
RUN clojure -T:build server

FROM eclipse-temurin:25-jre
RUN useradd --system podcast && mkdir /data && chown podcast /data
COPY --from=build /build/target/podcast-server.jar /app/podcast-server.jar
USER podcast
ENV PODCAST_SERVER_HOST=0.0.0.0 \
    PODCAST_SERVER_DATA=/data
VOLUME /data
EXPOSE 8400
CMD ["java", "-jar", "/app/podcast-server.jar"]
