package org.xbib.elasticsearch.support.client;

import org.xbib.metrics.CounterMetric;
import org.xbib.metrics.MeanMetric;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class State {

    private final Set<String> indexNames = Collections.synchronizedSet(new HashSet<String>());

    private final MeanMetric totalIngest = new MeanMetric();

    private final CounterMetric totalIngestSizeInBytes = new CounterMetric();

    private final CounterMetric currentIngest = new CounterMetric();

    private final CounterMetric currentIngestNumDocs = new CounterMetric();

    private final CounterMetric submitted = new CounterMetric();

    private final CounterMetric succeeded = new CounterMetric();

    private final CounterMetric failed = new CounterMetric();

    public MeanMetric getTotalIngest() {
        return totalIngest;
    }

    public CounterMetric getTotalIngestSizeInBytes() {
        return totalIngestSizeInBytes;
    }

    public CounterMetric getCurrentIngest() {
        return currentIngest;
    }

    public CounterMetric getCurrentIngestNumDocs() {
        return currentIngestNumDocs;
    }

    public CounterMetric getSubmitted() {
        return submitted;
    }

    public CounterMetric getSucceeded() {
        return succeeded;
    }

    public CounterMetric getFailed() {
        return failed;
    }

    public State startBulk(String indexName) {
        indexNames.add(indexName);
        return this;
    }

    public boolean isBulk(String indexName) {
        return indexNames.contains(indexName);
    }

    public State stopBulk(String indexName) {
        indexNames.remove(indexName);
        return this;
    }

    public Set<String> indices() {
        return indexNames;
    }

}
