FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/samadhanpoint.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java","-XX:+UseContainerSupport","-XX:MaxRAMPercentage=75","-jar","/app/app.jar"]
