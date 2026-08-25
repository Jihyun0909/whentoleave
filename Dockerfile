FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

COPY gradlew build.gradle settings.gradle gradle.properties ./
COPY gradle gradle
RUN ./gradlew --version

COPY src src
RUN ./gradlew bootJar --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar

# 베이스 이미지 기본 TZ는 UTC라, 설정 안 하면 LocalTime.now() 등이 실제 한국 시간과
# 9시간 어긋난다(막차 계산의 "지금" 기준 전체에 영향). JVM 옵션이 아니라 컨테이너 TZ
# 자체를 바꿔야 tzdata 기반 시간대 변환(DST 등 무관하게 KST는 고정 UTC+9)이 일관되게 적용된다.
ENV TZ=Asia/Seoul

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
