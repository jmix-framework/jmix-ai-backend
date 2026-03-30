FROM eclipse-temurin:21-jre
WORKDIR /app
COPY build/libs/jmix-ai-backend-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", \
  "-XX:+UseG1GC", \
  "-XX:MaxGCPauseMillis=200", \
  "-XX:+UseStringDeduplication", \
  "-Xms768m", "-Xmx1280m", \
  "-XX:MetaspaceSize=256m", "-XX:MaxMetaspaceSize=384m", \
  "-Xss512k", \
  "-XX:+AlwaysPreTouch", \
  "-XX:+ExitOnOutOfMemoryError", \
  "-Dspring.profiles.active=prod", \
  "-jar", "app.jar"]
