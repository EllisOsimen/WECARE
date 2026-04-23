# Use a standard Java environment (adjust to 21 if you are using Java 21)
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# The ADD command in Docker automatically extracts .tar files!
# This extracts your tar and drops the .jar into the /app directory.
ADD target/wecare.tar /app/

# Expose your web port (default Spring Boot is 8080)
EXPOSE 8080

# Run the jar file
ENTRYPOINT ["java", "-jar", "wecare-0.0.1-SNAPSHOT.jar"]