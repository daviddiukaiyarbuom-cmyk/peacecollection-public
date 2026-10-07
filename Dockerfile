FROM eclipse-temurin:21-jdk-jammy

WORKDIR /app
COPY src/PeaceCollectionApplication.java ./src/PeaceCollectionApplication.java
COPY web ./web
RUN javac -Xlint:all -d out src/PeaceCollectionApplication.java

EXPOSE 10000
CMD ["java", "-cp", "out", "PeaceCollectionApplication"]
