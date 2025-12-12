# ==============================================================================
# Meeting API - Dockerfile for Railway Deployment
# ==============================================================================
# Multi-stage build for optimized image size
# Includes: Java 21, FFmpeg, Whisper.cpp, and Whisper model
# ==============================================================================

# ------------------------------------------------------------------------------
# Stage 1: Build Whisper.cpp from source
# ------------------------------------------------------------------------------
FROM ubuntu:22.04 AS whisper-builder

RUN apt-get update && apt-get install -y \
    git \
    build-essential \
    cmake \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /build

# Clone and build whisper.cpp
RUN git clone https://github.com/ggerganov/whisper.cpp.git && \
    cd whisper.cpp && \
    cmake -B build && \
    cmake --build build --config Release -j$(nproc)

# ------------------------------------------------------------------------------
# Stage 2: Build Java application
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
# Stage 3: Final runtime image
# ------------------------------------------------------------------------------
FROM eclipse-temurin:21-jre

# Install FFmpeg and required libraries
RUN apt-get update && apt-get install -y \
    ffmpeg \
    curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Copy whisper-cli binary from builder
COPY --from=whisper-builder /build/whisper.cpp/build/bin/whisper-cli /usr/local/bin/whisper-cli
RUN chmod +x /usr/local/bin/whisper-cli

# Create directory for whisper models
RUN mkdir -p /app/models

# Download Whisper model (small - best balance for Portuguese)
RUN curl -L -o /app/models/ggml-small.bin \
    "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin"

# Create directories for temporary files
RUN mkdir -p /tmp/streaming /tmp/recordings

# Copy the built JAR from java-builder
COPY --from=java-builder /app/target/*.jar app.jar

# Environment variables for Railway
ENV JAVA_OPTS="-Xmx512m -Xms256m"
ENV SERVER_PORT=8080
ENV WHISPER_MODE=local
ENV WHISPER_PATH=/usr/local/bin/whisper-cli
ENV WHISPER_MODELS_PATH=/app/models
ENV WHISPER_MODEL=small
ENV WHISPER_LANGUAGE=pt
ENV WHISPER_THREADS=2
ENV FFMPEG_PATH=/usr/bin/ffmpeg

# Expose port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

# Run the application
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
