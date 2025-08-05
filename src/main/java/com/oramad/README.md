# CassPromFileExporter: Cassandra Metrics to Prometheus File Exporter

## Overview

`CassPromFileExporter` is a high-performance Java agent designed to run alongside an Apache Cassandra instance. Its primary function is to collect Cassandra's internal metrics (exposed via the Dropwizard Metrics library), filter them according to a flexible set of rules, and export them in the Prometheus text format to a local file.

This allows a standard Prometheus `node_exporter` (or a similar agent) to pick up the Cassandra metrics from the file using its `textfile` collector module, making them available for monitoring, visualization, and alerting within a Prometheus ecosystem without requiring a dedicated HTTP endpoint.

The agent is heavily optimized for performance to ensure it has a negligible impact on the Cassandra node it monitors.

## How it Works (Step-by-Step)

The agent follows a clear, scheduled process to collect, filter, and write metrics.

### 1. Agent Initialization (`premain`)

-   **Java Agent Entry Point**: The agent is loaded into the Cassandra JVM using the `-javaagent` flag. The `premain` method is the first piece of its code to execute.
-   **Asynchronous Startup**: To avoid delaying Cassandra's own startup process, the agent immediately spins up a dedicated background thread named `OramadCassPromFileExporter`.
-   **Waiting for Cassandra**: This thread patiently waits in a loop, checking every 5 seconds, until Cassandra's internal metrics registry (`CassandraMetricsRegistry.Metrics`) is initialized and available. This ensures the agent doesn't try to access metrics before they exist.
-   **Loading Filter Configuration**: Once the metrics system is ready, the agent loads a JSON configuration file that defines which metrics to include or exclude. The path to this file can be passed as an argument to the agent (e.g., `-javaagent:agent.jar=/path/to/filter.json`); if not provided, it defaults to `/apps/opt/cassandra/cass-prom-file-exporter/cass_metrics_filter.json`.

### 2. Scheduled Metric Collection

-   **Periodic Execution**: The agent uses a `ScheduledExecutorService` to run the metric collection task every 5 minutes (`WRITE_INTERVAL_MINUTES`).
-   **Guaranteed Interval**: It uses `scheduleWithFixedDelay`, which guarantees a fixed 5-minute delay between the *end* of one execution and the *start* of the next. This prevents overlapping runs, even if a collection cycle takes longer than expected.

### 3. The Collection Process (`FilteredCassandraMetricsCollector.collect`)

This is the core data-gathering logic that executes every 5 minutes. It's a multi-stage process designed for efficiency and relevance.

#### Step 3.1: High-Performance Filtering

1.  The collector fetches the complete list of all available metrics from the Cassandra registry.
2.  To maximize performance on multi-core systems, it processes the entire list of metrics in parallel using a `parallelStream()`.
3.  For each metric, it applies the filtering logic defined in the `MetricFilter` class (see Filtering Engine below). Only metrics that pass the filter rules are kept.
4.  The selected metrics are added to a temporary, in-memory `filteredRegistry`. This registry is cleared and rebuilt on every collection cycle.

#### Step 3.2: Zero-Value Suppression

-   After the initial filtering, the agent performs a second filtering pass. It iterates through every individual metric sample and **discards any sample where the value is exactly `0.0`**.
-   This is a critical feature that significantly reduces the volume of data written to the output file and stored in Prometheus, saving disk space and reducing noise from inactive or irrelevant metrics.

#### Step 3.3: Self-Monitoring

-   The agent measures the time it takes to complete the entire collection and filtering process (from start to finish).
-   This duration is recorded as a special gauge metric named `org_apache_cassandra_oramad_metrics_collection_time_in_ms`. This allows you to monitor the performance of the exporter itself and ensure it's not adding significant overhead.

### 4. Writing to File (`writeMetricsToFile`)

-   The final, filtered, non-zero list of metrics is passed to the `TextFormat.write004` method.
-   This method formats the metrics into the standard Prometheus text-based exposition format.
-   The formatted text is written to the output file at `/tmp/oramad_cass_prom_metrics_0.txt`, overwriting the previous content. This file is now ready to be scraped.

## Filtering Engine

The agent's filtering engine is highly efficient and configurable, allowing precise control over which metrics are exported.

### Configuration (`cass_metrics_filter.json`)

The filtering rules are defined in a JSON file with two main sections: `blacklist` and `whitelist`.

**Sample JSON Structure:**

```json
{
  "blacklist": {
    "keyspaces": ["system", "system_schema"],
    "exact": ["org.apache.cassandra.metrics.Client.AuthFailure"],
    "startsWith": ["org.apache.cassandra.metrics.Cache."],
    "endsWith": [".999thPercentile"],
    "contains": ["DroppedMessage"],
    "regex": [".*p999.*"]
  },
  "whitelist": {
    "startsWith": ["org.apache.cassandra.metrics.Keyspace."]
  }
}
```

### Filter Rule Types

The engine supports several types of matching rules for fine-grained control:

-   `keyspaces`: A special list of keyspace names to completely exclude from metrics.
-   `exact`: An exact string match on the full metric name.
-   `startsWith`: Matches any metric name that begins with the given prefix.
-   `endsWith`: Matches any metric name that ends with the given suffix.
-   `contains`: Matches any metric name that contains the given substring.
-   `regex`: Matches any metric name against a Java regular expression.

### Special Keyspace Filtering

The `keyspaces` rule is optimized to identify and filter out metrics belonging to specific keyspaces. It intelligently handles the two common ways keyspaces appear in Cassandra metric names:

1.  **Dot-separated names**: e.g., `org.apache.cassandra.metrics.Keyspace.my_keyspace.ReadLatency`
2.  **Label-based names**: e.g., `org_apache_cassandra_metrics_table_live_ss_table_count{keyspace="my_keyspace",...}`

### Filtering Logic

The decision to include a metric (`shouldInclude`) follows a strict order of operations:

1.  **Blacklist First**: The metric is first checked against all `blacklist` rules. If it matches *any* blacklist rule, it is immediately **rejected**.
2.  **Whitelist Check**: If a `whitelist` is defined and is not empty, the metric is then checked against it. If the metric does **not** match any `whitelist` rule, it is **rejected**.
3.  **Default Pass**: If the metric is not on the blacklist, and either (a) no whitelist is defined or (b) it is on the whitelist, the metric is **included**.

## Performance Optimizations

The agent was engineered with performance as a top priority to minimize its footprint.

-   **Parallel Stream Processing**: Leverages multi-core CPUs to filter the full metric list much faster than a sequential loop.
-   **Efficient Data Structures**:
    -   A **Trie** (prefix tree) is used for `startsWith` and `endsWith` matching. This is significantly faster than calling `String.startsWith()` or `String.endsWith()` against a large list of patterns.
    -   A `HashSet` is used for `exact` matching, providing constant-time O(1) lookups.
-   **Compiled Regex Patterns**: All `contains` and `regex` rules are combined into a single, pre-compiled `Pattern` object. This avoids the overhead of compiling multiple regular expressions on every collection run.
-   **Object Reuse**: Key objects like the `DropwizardExports` bridge and the temporary `filteredRegistry` are created once and reused for every collection cycle, reducing object churn and garbage collection pressure.
-   **Optimized Suffix Matching**: For `endsWith` checks, the patterns are reversed and stored in a Trie. The agent then iterates backward over the metric name to check for a match, avoiding the creation of a new reversed string on every check.

## How to Use

1.  Compile the Java code into a JAR file (e.g., `cass-prom-file-exporter.jar`).
2.  Create your `cass_metrics_filter.json` configuration file.
3.  Add the `-javaagent` flag to your Cassandra startup script, pointing to the JAR file. You can optionally pass the path to your filter file as an argument.

**Example:**

```bash
# Without a custom filter file path (uses default)
-javaagent:/path/to/cass-prom-file-exporter.jar

# With a custom filter file path
-javaagent:/path/to/cass-prom-file-exporter.jar=/etc/cassandra/my_filter.json
```

## Output

-   **Metrics File**: The exported metrics are written to `/tmp/oramad_cass_prom_metrics_0.txt`.
-   **Prometheus Integration**: To consume these metrics, configure the `node_exporter` on your Cassandra node to look for files in that directory.

**Node Exporter Configuration (`--collector.textfile.directory`):**

```bash
/usr/bin/node_exporter --collector.textfile.directory=/tmp
```

Prometheus will then automatically scrape the `node_exporter`, which in turn will present the metrics from the file.
