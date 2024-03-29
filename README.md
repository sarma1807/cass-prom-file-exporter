## cass-prom-file-exporter : Cassandra Prometheus File Exporter

#### intended to be used with https://github.com/sarma1807/Prometheus-Grafana-Cassandra

---

## ` *** this code is not compatible with Java 1.8 *** `

### minimum requirement is Java 11

```
$ java -version
openjdk version "11.0.22" 2024-01-16 LTS
OpenJDK Runtime Environment (Red_Hat-11.0.22.0.7-1) (build 11.0.22+7-LTS)
OpenJDK 64-Bit Server VM (Red_Hat-11.0.22.0.7-1) (build 11.0.22+7-LTS, mixed mode, sharing)

$ javac -version
javac 11.0.22
```
##### or use Java 17

```
$ java -version
openjdk version "17.0.10" 2024-01-16 LTS
OpenJDK Runtime Environment (Red_Hat-17.0.10.0.7-1) (build 17.0.10+7-LTS)
OpenJDK 64-Bit Server VM (Red_Hat-17.0.10.0.7-1) (build 17.0.10+7-LTS, mixed mode, sharing)

$ javac -version
javac 17.0.10
```
---

#### git clone this project and prepare for build

```
cd ~
git clone https://github.com/sarma1807/cass-prom-file-exporter

cd ~/cass-prom-file-exporter
chmod u+x gradlew
```

#### build

```
cd ~/cass-prom-file-exporter
./gradlew shadowJar
```

```
-- after some time ...
BUILD SUCCESSFUL in 2m 47s
2 actionable tasks: 2 executed
```

#### if no errors from previous build, then you will have a compiled jar

```
~/cass-prom-file-exporter/build/libs/CassPromFileExporter-j11-all.jar
```
or
```
~/cass-prom-file-exporter/build/libs/CassPromFileExporter-j17-all.jar
```

j{version} is defined in https://github.com/sarma1807/cass-prom-file-exporter/blob/main/build.gradle.kts

---

### pre-compiled jars are available in following location :

https://github.com/sarma1807/cass-prom-file-exporter/tree/main/build/libs

---
