# ==============================================================================
# Meeting API - Dockerfile for Railway Deployment
# ==============================================================================
# Optimized build with Java 21 and FFmpeg
# Uses AssemblyAI for transcription (no local Whisper needed)
# ==============================================================================

# ------------------------------------------------------------------------------
# Stage 1: Build Java application
# ------------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk AS java-builder

WORKDIR /app

# Copy Maven wrapper and pom.xml first (for caching)
COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .

# Make mvnw executable
RUN chmod +x mvnw

# Download dependencies (cached layer)
RUN ./mvnw dependency:go-offline -B

# Copy source code
COPY src ./src

# Build the application
RUN ./mvnw package -DskipTests -B

# ------------------------------------------------------------------------------
# Stage 2: Final runtime image
# ------------------------------------------------------------------------------
FROM eclipse-temurin:21-jre

# Install FFmpeg for audio conversion
RUN apt-get update && apt-get install -y \
    ffmpeg \
    curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Create directories for temporary files
RUN mkdir -p /tmp/streaming /tmp/recordings

# Copy the built JAR from java-builder
COPY --from=java-builder /app/target/*.jar app.jar

# Environment variables
ENV JAVA_OPTS="-Xmx512m -Xms256m"
ENV SERVER_PORT=8080
ENV FFMPEG_PATH=/usr/bin/ffmpeg

# Expose port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1

# Run the application
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
