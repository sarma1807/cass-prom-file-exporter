# CassPromFileExporter Configuration and Usage

## Overview

`CassPromFileExporter` is a Java agent designed to collect metrics from a running Cassandra instance, filter them based on a flexible set of rules, and export them to a text file. This file is formatted for consumption by a Prometheus server, typically via the Node Exporter's `textfile` collector.

The primary goal is to reduce the volume of metrics sent to Prometheus, focusing only on those that are essential for monitoring and alerting, thereby saving on storage and improving query performance.

## How it Works

1.  **Java Agent**: The exporter attaches to the Cassandra JVM at startup using the `-javaagent` flag. It waits until the internal Cassandra metrics registry is initialized before it starts its work.
2.  **Scheduled Collection**: Once initialized, it schedules a task to run every 5 minutes.
3.  **Filtering**: During each run, it fetches all available Dropwizard metrics from Cassandra. It then applies a series of filtering rules defined in a JSON configuration file. This process is highly optimized for performance.
4.  **Zero-Value Pruning**: After the initial filtering, it performs a second pass to remove any metric sample whose value is `0.0`. This further reduces noise and data volume.
5.  **File Export**: The resulting set of metrics is written to `/tmp/oramad_cass_prom_metrics_0.txt`. This file is overwritten on each run.

## Configuration

### Java Agent Setup

To use the exporter, you must attach it to the Cassandra JVM by adding the `-javaagent` flag to the Cassandra startup script (e.g., in `jvm.options` or a similar configuration file).

**Syntax:**

```bash
-javaagent:/path/to/CassPromFileExporter-jXX-all.jar[=/path/to/your_filter.json]
```

-   `/path/to/CassPromFileExporter-jXX-all.jar`: The absolute path to the exporter's JAR file. Use the JAR that matches your JVM version (e.g., `j11` or `j17`).
-   `=/path/to/your_filter.json`: (Optional) The absolute path to your metric filter configuration file.

If you do not specify a filter file path, the agent defaults to using `/apps/opt/cassandra/cass-prom-file-exporter/metrics_filtering/cass_metrics_filter.json`.

### Prometheus Setup

Your Prometheus server should be configured to scrape the output file. This is typically done by configuring the Node Exporter on the Cassandra host to look for files in a specific directory and then having Prometheus scrape that Node Exporter.

1.  **Configure Node Exporter**: Start the Node Exporter with the `--collector.textfile.directory` flag pointing to the directory containing the metrics file.
    ```bash
    # Example: Start node_exporter to read files from /tmp
    node_exporter --collector.textfile.directory="/tmp"
    ```
2.  **Prometheus Scrape Config**: Ensure your Prometheus configuration (`prometheus.yml`) has a job to scrape the Node Exporters on your Cassandra nodes.

## Filtering Logic Explained

The power of the exporter lies in its filtering capabilities. The filtering logic is governed by a JSON file containing `blacklist` and `whitelist` rules.

### General Principles

1.  **Blacklist First**: The `blacklist` is always checked first. If a metric name matches any rule in the blacklist, it is immediately rejected and will not be processed further.
2.  **Whitelist Second**:
    *   If the `whitelist` section is defined and is not empty, a metric **must** also match a rule in the whitelist to be included.
    *   If the `whitelist` section is missing, empty, or not defined, any metric that does not match the blacklist is automatically included.

This allows for two main strategies:
-   **Blacklist-only**: Block a small number of specific metrics.
-   **Whitelist-focused**: Block broad categories of metrics and then specifically allow only the ones you need.

### Filter File Structure

The JSON file must have a root object containing up to two keys: `blacklist` and `whitelist`. Each of these keys holds an object that specifies the filter rules.

**Example `cass_metrics_filter.json`:**

```json
{
  "blacklist": {
    "keyspaces": ["system", "system_schema", "system_auth"],
    "endsWith": [".999Percentile", ".99Percentile"],
    "contains": ["DroppedMessage"],
    "regex": ["^org\\.apache\\.cassandra\\.metrics\\.ThreadPools\\.+" ]
  },
  "whitelist": {
    "startsWith": [
      "org.apache.cassandra.metrics.Keyspace",
      "org.apache.cassandra.metrics.Table",
      "org.apache.cassandra.metrics.ClientRequest"
    ]
  }
}
```

### Filter Rule Types

The following rule types can be used within both `blacklist` and `whitelist` sections.

#### `keyspaces` (Blacklist Only)
This is a special, high-performance rule that blocks metrics associated with specific keyspaces. It is particularly useful for excluding noisy or irrelevant system keyspaces. This rule works in two ways:

1.  **Label-based Matching**: For metrics formatted with Prometheus labels, it checks if the `keyspace` label matches.
    -   *Example Rule*: `"keyspaces": ["system_traces"]`
    -   *Blocks*: `some_metric{keyspace="system_traces", table="schedules"}`
2.  **Dot-separated Name Matching**: For metrics following the standard Dropwizard naming convention, it checks if the keyspace name appears as a whole word in the metric name.
    -   *Example Rule*: `"keyspaces": ["system"]`
    -   *Blocks*: `org.apache.cassandra.metrics.Table.system.some_table.ReadLatency`

#### `exact`
Matches the entire metric name string exactly.

-   *Example Rule*: `"exact": ["org.apache.cassandra.metrics.Storage.Load"]`
-   *Matches*: `org.apache.cassandra.metrics.Storage.Load`
-   *Does Not Match*: `org.apache.cassandra.metrics.Storage.LoadCount`

#### `startsWith`
Matches if the metric name begins with the specified string. This is useful for targeting entire categories of metrics.

-   *Example Rule*: `"startsWith": ["org.apache.cassandra.metrics.CommitLog"]`
-   *Matches*: `org.apache.cassandra.metrics.CommitLog.PendingTasks`
-   *Matches*: `org.apache.cassandra.metrics.CommitLog.TotalCommitLogSize`

#### `endsWith`
Matches if the metric name ends with the specified string. Useful for targeting specific metric types across different objects (e.g., all `.Count` metrics).

-   *Example Rule*: `"endsWith": [".Count"]`
-   *Matches*: `org.apache.cassandra.metrics.CQL.RegularStatementsExecuted.Count`
-   *Matches*: `org.apache.cassandra.metrics.DroppedMessage.READ.Count`

#### `contains`
Matches if the specified string appears anywhere within the metric name. This is a broad way to filter.

-   *Example Rule*: `"contains": ["Latency"]`
-   *Matches*: `org.apache.cassandra.metrics.Table.my_ks.my_table.ReadLatency`
-   *Matches*: `org.apache.cassandra.metrics.ClientRequest.Read.TotalLatency`

#### `regex`
Matches the metric name against a Java-compatible regular expression. This provides the most powerful and flexible matching.

-   *Example Rule*: `"regex": ["^org\\.apache\\.cassandra\\.metrics\\.Cache\\..+Size$"]`
-   *Matches*: `org.apache.cassandra.metrics.Cache.CounterCache.Size`
-   *Matches*: `org.apache.cassandra.metrics.Cache.KeyCache.Size`
-   *Does Not Match*: `org.apache.cassandra.metrics.Cache.KeyCache.Entries`

### Performance Optimizations

The filtering engine is designed for high performance to minimize overhead on the Cassandra node.
-   `startsWith` and `endsWith` matches are performed using an efficient **Trie** data structure.
-   `contains` and `regex` patterns are compiled into a single, optimized regular expression.
-   The actual filtering of the metric map is done in parallel to leverage multiple CPU cores.
