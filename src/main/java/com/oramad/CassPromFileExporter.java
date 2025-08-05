//////////////////////////////////////////////////
// original code written by Jon Haddad aka rustyrazorblade
// https://github.com/rustyrazorblade/cassandra-prometheus-exporter
//
// CassPromFileExporter.java
// Cassandra Dropwizard Metrics to Prometheus as Flat File
// code version : v02_20250805 : Sarma Pydipally
//////////////////////////////////////////////////

package com.oramad;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import io.prometheus.client.Collector;
import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.Gauge;
import io.prometheus.client.dropwizard.DropwizardExports;
import com.codahale.metrics.Metric;
import com.codahale.metrics.MetricRegistry;
import io.prometheus.client.exporter.common.TextFormat;
import org.apache.cassandra.metrics.CassandraMetricsRegistry;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.Writer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class CassPromFileExporter {

    private static final String METRICS_FILE_PATH = "/tmp/oramad_cass_prom_metrics_0.txt";
    private static final int WRITE_INTERVAL_MINUTES = 5;
    private static final String DEFAULT_FILTER_FILE = "/apps/opt/cassandra/cass-prom-file-exporter/cass_metrics_filter.json";

    public static void premain(String agentArgs, Instrumentation inst) {
        String filterFilePath = agentArgs != null && !agentArgs.isEmpty() ? agentArgs : DEFAULT_FILTER_FILE;

        Thread thread = new Thread(() -> {
            while (CassandraMetricsRegistry.Metrics == null) {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    System.err.println("ERROR [oramad] CassPromFileExporter: Interrupted while waiting for Cassandra metrics.");
                    return;
                }
            }
            System.out.println("INFO  [oramad] CassPromFileExporter: Cassandra metrics agent is now ready.");

            try {
                MetricFilter filter = new MetricFilter(filterFilePath);
                CollectorRegistry.defaultRegistry.register(new FilteredCassandraMetricsCollector(filter));

                ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
                // Use scheduleWithFixedDelay to ensure one execution finishes before the next one starts.
                scheduler.scheduleWithFixedDelay(CassPromFileExporter::writeMetricsToFile, 1, WRITE_INTERVAL_MINUTES, TimeUnit.MINUTES);
            } catch (Exception e) {
                System.err.println("ERROR [oramad] CassPromFileExporter: Failed to initialize and schedule metrics collection.");
                e.printStackTrace();
            }
        });
        thread.setName("OramadCassPromFileExporter");
        thread.start();
    }

    private static void writeMetricsToFile() {
        try (Writer writer = new FileWriter(METRICS_FILE_PATH)) {
            TextFormat.write004(writer, CollectorRegistry.defaultRegistry.metricFamilySamples());
        } catch (Exception e) {
            System.err.println("ERROR [oramad] CassPromFileExporter: Failed to write metrics file.");
            e.printStackTrace();
        }
    }

    // --- Inner Classes ---

    // The main collector that filters metrics on the fly and measures performance.
    static class FilteredCassandraMetricsCollector extends Collector {
        private final MetricFilter filter;
        private final Gauge collectionTimeGauge;
        // Reuse registry and exports objects to avoid excessive object creation on each scrape.
        private final MetricRegistry filteredRegistry = new MetricRegistry();
        private final DropwizardExports exports = new DropwizardExports(filteredRegistry);


        FilteredCassandraMetricsCollector(MetricFilter filter) {
            this.filter = filter;
            this.collectionTimeGauge = Gauge.build()
                .name("org_apache_cassandra_oramad_metrics_collection_time_in_ms")
                .help("Time taken in milliseconds to collect and filter Cassandra metrics.")
                .create();
        }

        @Override
        public List<MetricFamilySamples> collect() {
            long startTime = System.nanoTime();

            // Clear the registry and repopulate it with the latest filtered metrics.
            // This is more efficient than creating a new registry on every scrape.
            // Use the safe, built-in method to clear the registry.
            filteredRegistry.removeMatching(com.codahale.metrics.MetricFilter.ALL);

            final Map<String, Metric> metrics = CassandraMetricsRegistry.Metrics.getMetrics();
            if (metrics != null) {
                // Parallelize the filtering process to leverage multiple CPU cores.
                // This is a significant performance improvement for systems with many metrics.
                // The filtering logic and the target registry are both thread-safe.
                metrics.entrySet().parallelStream().forEach(entry -> {
                    if (filter.shouldInclude(entry.getKey())) {
                        filteredRegistry.register(entry.getKey(), entry.getValue());
                    }
                });
            }

            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
            collectionTimeGauge.set(durationMs);

            // Directly collect the samples from the DropwizardExports.
            List<MetricFamilySamples> allSamples = new ArrayList<>(exports.collect());

            // Filter out metrics where the value is 0.0, as they are not useful.
            List<MetricFamilySamples> filteredByValueSamples = allSamples.stream()
                .map(mfs -> {
                    // Filter the samples within each metric family to exclude zero values.
                    List<MetricFamilySamples.Sample> nonZeroSamples = mfs.samples.stream()
                        .filter(s -> s.value != 0.0)
                        .collect(Collectors.toList());

                    // If the metric family still has samples after filtering, keep it.
                    if (!nonZeroSamples.isEmpty()) {
                        return new MetricFamilySamples(mfs.name, mfs.type, mfs.help, nonZeroSamples);
                    }
                    return null;
                })
                .filter(java.util.Objects::nonNull) // Remove metric families that became empty.
                .collect(Collectors.toList());


            // Always include the collection time gauge, regardless of its value.
            filteredByValueSamples.addAll(collectionTimeGauge.collect());

            return filteredByValueSamples;
        }
    }

    // Trie data structure for efficient prefix/suffix matching
    static class Trie {
        private final TrieNode root = new TrieNode();

        void insert(String word) {
            TrieNode current = root;
            for (char l : word.toCharArray()) {
                current = current.getChildren().computeIfAbsent(l, c -> new TrieNode());
            }
            current.setEndOfWord(true);
        }

        boolean startsWith(String text) {
            TrieNode current = root;
            for (int i = 0; i < text.length(); i++) {
                char ch = text.charAt(i);
                TrieNode node = current.getChildren().get(ch);
                if (node == null) {
                    return false; // No prefix match found
                }
                if (node.isEndOfWord()) {
                    return true; // The text starts with a known prefix
                }
                current = node;
            }
            return false; // Text is shorter than any prefix or no match
        }

        /**
         * Checks if the given text ends with any of the words in the trie.
         * This method assumes the words were inserted into the trie in reverse order.
         * It iterates through the text backward to avoid creating a new reversed string on every call.
         *
         * @param text The text to check.
         * @return true if the text has a suffix that exists as a reversed word in the trie.
         */
        boolean reverseStartsWith(String text) {
            TrieNode current = root;
            for (int i = text.length() - 1; i >= 0; i--) {
                char ch = text.charAt(i);
                TrieNode node = current.getChildren().get(ch);
                if (node == null) {
                    return false; // No suffix match found
                }
                if (node.isEndOfWord()) {
                    return true; // The text ends with a known suffix
                }
                current = node;
            }
            return false;
        }
    }

    static class TrieNode {
        private final Map<Character, TrieNode> children = new HashMap<>();
        private boolean endOfWord;

        Map<Character, TrieNode> getChildren() { return children; }
        boolean isEndOfWord() { return endOfWord; }
        void setEndOfWord(boolean endOfWord) { this.endOfWord = endOfWord; }
    }

    // Handles loading and applying filter rules
    static class MetricFilter {
        private final FilterRules blacklist;
        private final FilterRules whitelist;
        private final boolean isWhitelistEnabled;

        MetricFilter(String filterFilePath) {
            Map<String, FilterRules> rules = loadFilterRules(filterFilePath);
            this.blacklist = rules.getOrDefault("blacklist", new FilterRules());
            this.whitelist = rules.getOrDefault("whitelist", new FilterRules());
            this.isWhitelistEnabled = !this.whitelist.isEmpty();
        }

        private Map<String, FilterRules> loadFilterRules(String filePath) {
            try (FileReader reader = new FileReader(filePath)) {
                Gson gson = new Gson();
                Type type = new TypeToken<Map<String, Map<String, List<String>>>>() {}.getType();
                Map<String, Map<String, List<String>>> rawRules = gson.fromJson(reader, type);

                Map<String, FilterRules> loadedRules = new HashMap<>();
                if (rawRules != null) {
                    rawRules.forEach((key, value) -> loadedRules.put(key, new FilterRules(value)));
                }
                System.out.println("INFO  [oramad] CassPromFileExporter: Successfully loaded filter rules from " + filePath);
                return loadedRules;
            } catch (Exception e) {
                System.err.println("WARN  [oramad] CassPromFileExporter: Could not load or parse filter file: " + filePath + ". No filtering will be applied.");
                e.printStackTrace(); // Print stack trace for debugging
                return Collections.emptyMap();
            }
        }

        boolean shouldInclude(String metricName) {
            // Check blacklist first for fail-fast behavior.
            // The new keyspace check is the most specific and should come first.
            if (blacklist.matchesKeyspace(metricName)) {
                return false;
            }
            if (blacklist.matches(metricName)) {
                return false;
            }

            // If a whitelist is defined, the metric must match it.
            if (isWhitelistEnabled) {
                return whitelist.matches(metricName);
            }

            // If no whitelist, include everything that wasn't blacklisted.
            return true;
        }
    }

    // Holds the compiled filter patterns for high performance
    static class FilterRules {
        private final Set<String> exact;
        private final Trie startsWith;
        private final Trie endsWith;
        private final Pattern combinedPattern; // For contains and regex
        private final Set<String> keyspaces;
        private final Pattern keyspacePattern; // For dot-separated keyspace matching

        // Pattern to extract the keyspace name from a metric string with labels.
        // It looks for "keyspace=" followed by any characters except a comma or closing brace.
        private static final Pattern KEYSPACE_LABEL_PATTERN = Pattern.compile("keyspace=\"?([^,\"}]+)\"?");

        FilterRules() {
            this.exact = Collections.emptySet();
            this.startsWith = new Trie();
            this.endsWith = new Trie();
            this.combinedPattern = null;
            this.keyspaces = Collections.emptySet();
            this.keyspacePattern = null;
        }

        FilterRules(Map<String, List<String>> rules) {
            // Filter nulls from lists to prevent NullPointerException during processing.
            this.exact = rules.getOrDefault("exact", Collections.emptyList()).stream()
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());

            this.startsWith = new Trie();
            rules.getOrDefault("startsWith", Collections.emptyList()).stream()
                .filter(java.util.Objects::nonNull)
                .forEach(this.startsWith::insert);

            this.endsWith = new Trie();
            rules.getOrDefault("endsWith", Collections.emptyList()).stream()
                .filter(java.util.Objects::nonNull)
                .forEach(p -> this.endsWith.insert(new StringBuilder(p).reverse().toString()));

            // Initialize the new keyspaces blacklist
            this.keyspaces = rules.getOrDefault("keyspaces", Collections.emptyList()).stream()
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());

            // Pre-compile a regex for efficient dot-separated keyspace matching.
            // This pattern correctly identifies a keyspace as a whole "word".
            if (this.keyspaces.isEmpty()) {
                this.keyspacePattern = null;
            } else {
                String keyspaceRegex = this.keyspaces.stream()
                    .map(Pattern::quote)
                    .collect(Collectors.joining("|"));
                this.keyspacePattern = Pattern.compile("(^|\\.)(" + keyspaceRegex + ")(\\.|$)");
            }

            // Combine 'contains' and 'regex' into a single optimized pattern
            List<String> allPatterns = new ArrayList<>();
            // Quote 'contains' patterns to treat them as literals
            rules.getOrDefault("contains", Collections.emptyList()).stream()
                .filter(java.util.Objects::nonNull)
                .map(Pattern::quote)
                .forEach(allPatterns::add);
            // Add 'regex' patterns as-is
            rules.getOrDefault("regex", Collections.emptyList()).stream()
                .filter(java.util.Objects::nonNull)
                .forEach(allPatterns::add);

            if (allPatterns.isEmpty()) {
                this.combinedPattern = null;
            } else {
                this.combinedPattern = Pattern.compile(String.join("|", allPatterns));
            }
        }

        /**
         * Checks if the metric name matches a blacklisted keyspace.
         * This method is optimized to handle both dot-separated names (e.g., "ks.table.metric")
         * and label-based names (e.g., 'metric{keyspace="ks"}').
         *
         * @param metricName The name of the metric to check.
         * @return true if the metric belongs to a blacklisted keyspace.
         */
        boolean matchesKeyspace(String metricName) {
            if (keyspaces.isEmpty() || metricName == null) {
                return false;
            }

            // 1. Check for label-based metric format first.
            Matcher labelMatcher = KEYSPACE_LABEL_PATTERN.matcher(metricName);
            if (labelMatcher.find()) {
                String keyspace = labelMatcher.group(1);
                if (keyspaces.contains(keyspace)) {
                    return true;
                }
            }

            // 2. Check for dot-separated format using the pre-compiled regex.
            // This is faster and more accurate than string contains/endsWith checks.
            if (keyspacePattern != null && keyspacePattern.matcher(metricName).find()) {
                return true;
            }

            return false;
        }

        boolean matches(String metricName) {
            if (metricName == null) return false;
            if (exact.contains(metricName)) return true;
            if (startsWith.startsWith(metricName)) return true;
            // Check for suffix match efficiently without creating new strings.
            if (endsWith.reverseStartsWith(metricName)) return true;
            // Check the combined pattern for 'contains' and 'regex' matches.
            // Use find() to look for a match anywhere in the string.
            if (combinedPattern != null && combinedPattern.matcher(metricName).find()) return true;
            return false;
        }

        boolean isEmpty() {
            return exact.isEmpty() && startsWith.root.getChildren().isEmpty() && endsWith.root.getChildren().isEmpty() && combinedPattern == null && keyspaces.isEmpty();
        }
    }
}
