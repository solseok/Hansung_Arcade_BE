# 한성 아케이드 게임 서버 이미지
#   docker build -t hansung-arcade-server .
#   docker run -d -p 9000:9000 hansung-arcade-server
#
# 테스트는 CI에서 이미 돌리므로 여기서는 실행 파일만 만든다.

# ---- 1단계: 빌드 ----
# 빌드 단계는 러너의 기본 플랫폼에서 돈다. ARM 이미지를 만들 때도 Gradle을 에뮬레이션하지 않는다 (결과물은 플랫폼과 무관한 jar)
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk-noble AS build
WORKDIR /workspace

# 의존성 레이어를 먼저 받아 두면, 소스만 바뀐 빌드는 이 레이어를 그대로 쓴다
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN ./gradlew --no-daemon dependencies > /dev/null

COPY src ./src
RUN ./gradlew --no-daemon installDist -x test

# ---- 2단계: 실행 ----
FROM eclipse-temurin:21-jre-noble
# 로그 시각을 한국 시간으로
ENV TZ=Asia/Seoul
# 컨테이너 메모리 한도(docker run --memory)의 75%까지 힙으로 쓴다. 한도 없이 띄우면 호스트 RAM 기준이 된다
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"

RUN groupadd --system arcade && useradd --system --gid arcade --no-create-home arcade
WORKDIR /app
COPY --from=build /workspace/build/install/hansung_arcade_server/ ./
USER arcade

# application.properties의 network.port
EXPOSE 9000

# 시작 스크립트가 마지막에 exec java로 바뀌므로 java가 PID 1이 되어 docker stop의 SIGTERM을 받고,
# 종료 훅이 연결을 정리한 뒤 끝난다. 포트를 바꾸려면: docker run ... hansung-arcade-server --network.port=9100
ENTRYPOINT ["/app/bin/hansung_arcade_server"]
