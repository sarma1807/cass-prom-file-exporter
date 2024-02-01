This is currently just a POC.


Build using the same Java version as Cassandra:

`./gradlew shadowJar`

Grab the jar and drop it in your Cassandra lib directory.  Something like this perhaps:

```shell
cp ./build/libs/cassandra-dropwizard-1.0-SNAPSHOT-all.jar ../cassandra/lib
```

Add the agent to your cassandra-env.sh:


```text
JVM_OPTS="$JVM_OPTS -javaagent:$CASSANDRA_HOME/lib/cassandra-dropwizard-1.0-SNAPSHOT-all.jar"
```

In an ideal world, metrics would be available at:

```text
http://localhost:1234/metrics
```
