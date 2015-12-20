/*
 * Copyright (C) 2015 Jörg Prante
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.xbib.elasticsearch.helper.client;

import com.google.common.collect.ImmutableSet;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthStatus;
import org.elasticsearch.action.admin.indices.create.CreateIndexAction;
import org.elasticsearch.action.admin.indices.create.CreateIndexRequestBuilder;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexAction;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequestBuilder;
import org.elasticsearch.action.admin.indices.mapping.put.PutMappingAction;
import org.elasticsearch.action.admin.indices.mapping.put.PutMappingRequestBuilder;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.ElasticsearchClient;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.ESLoggerFactory;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.unit.TimeValue;
import org.xbib.elasticsearch.helper.client.http.HttpBulkProcessor;
import org.xbib.elasticsearch.helper.client.http.HttpElasticsearchClient;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public class HttpBulkNodeClient implements Ingest {

    private final static ESLogger logger = ESLoggerFactory.getLogger(HttpBulkNodeClient.class.getName());

    private final ConfigHelper configHelper = new ConfigHelper();

    private int maxActionsPerRequest = DEFAULT_MAX_ACTIONS_PER_REQUEST;

    private int maxConcurrentRequests = DEFAULT_MAX_CONCURRENT_REQUESTS;

    private ByteSizeValue maxVolume = DEFAULT_MAX_VOLUME_PER_REQUEST;

    private TimeValue flushInterval = DEFAULT_FLUSH_INTERVAL;

    private ElasticsearchClient client;

    private HttpBulkProcessor bulkProcessor;

    private IngestMetric metric;

    private Throwable throwable;

    private boolean closed;

    HttpBulkNodeClient() {
    }

    @Override
    public HttpBulkNodeClient maxActionsPerRequest(int maxActionsPerRequest) {
        this.maxActionsPerRequest = maxActionsPerRequest;
        return this;
    }

    @Override
    public HttpBulkNodeClient maxConcurrentRequests(int maxConcurrentRequests) {
        this.maxConcurrentRequests = maxConcurrentRequests;
        return this;
    }

    @Override
    public HttpBulkNodeClient maxVolumePerRequest(ByteSizeValue maxVolume) {
        this.maxVolume = maxVolume;
        return this;
    }

    @Override
    public HttpBulkNodeClient flushIngestInterval(TimeValue flushInterval) {
        this.flushInterval = flushInterval;
        return this;
    }

    @Override
    public HttpBulkNodeClient init(Map<String, String> settings, IngestMetric metric) {
        return init(Settings.builder().put(settings).build(), metric);
    }

    @Override
    public HttpBulkNodeClient init(Settings settings, final IngestMetric metric) {
        return init(HttpElasticsearchClient.builder(settings).build(), metric);
    }

    @Override
    public HttpBulkNodeClient init(ElasticsearchClient client, final IngestMetric metric) {
        this.client = client;
        this.metric = metric;
        if (metric != null) {
            metric.start();
        }
        HttpBulkProcessor.Listener listener = new HttpBulkProcessor.Listener() {
            @Override
            public void beforeBulk(long executionId, BulkRequest request) {
                long l = -1;
                if (metric != null) {
                    metric.getCurrentIngest().inc();
                    l = metric.getCurrentIngest().count();
                    int n = request.numberOfActions();
                    metric.getSubmitted().inc(n);
                    metric.getCurrentIngestNumDocs().inc(n);
                    metric.getTotalIngestSizeInBytes().inc(request.estimatedSizeInBytes());
                }
                logger.debug("before bulk [{}] [actions={}] [bytes={}] [concurrent requests={}]",
                        executionId,
                        request.numberOfActions(),
                        request.estimatedSizeInBytes(),
                        l);
            }

            @Override
            public void afterBulk(long executionId, BulkRequest request, BulkResponse response) {
                long l = -1;
                if (metric != null) {
                    metric.getCurrentIngest().dec();
                    l = metric.getCurrentIngest().count();
                    metric.getSucceeded().inc(response.getItems().length);
                    metric.getFailed().inc(0);
                    metric.getTotalIngest().inc(response.getTookInMillis());
                }
                int n = 0;
                for (BulkItemResponse itemResponse : response.getItems()) {
                    if (itemResponse.isFailed()) {
                        n++;
                        if (metric != null) {
                            metric.getSucceeded().dec(1);
                            metric.getFailed().inc(1);
                        }
                    }
                }
                logger.debug("after bulk [{}] [succeeded={}] [failed={}] [{}ms] {} concurrent requests",
                        executionId,
                        metric != null ? metric.getSucceeded().count() : -1,
                        metric != null ? metric.getFailed().count() : -1,
                        response.getTook().millis(),
                        l);
                if (n > 0) {
                    logger.error("bulk [{}] failed with {} failed items, failure message = {}",
                            executionId, n, response.buildFailureMessage());
                } else {
                    if (metric != null) {
                        metric.getCurrentIngestNumDocs().dec(response.getItems().length);
                    }
                }
            }

            @Override
            public void afterBulk(long executionId, BulkRequest request, Throwable failure) {
                if (metric != null) {
                    metric.getCurrentIngest().dec();
                }
                throwable = failure;
                closed = true;
                logger.error("after bulk [" + executionId + "] error", failure);
            }
        };
        HttpBulkProcessor.Builder builder = HttpBulkProcessor.builder((Client) client, listener)
                .setBulkActions(maxActionsPerRequest)
                .setConcurrentRequests(maxConcurrentRequests)
                .setFlushInterval(flushInterval);
        if (maxVolume != null) {
            builder.setBulkSize(maxVolume);
        }
        this.bulkProcessor = builder.build();
        this.closed = false;
        return this;
    }

    @Override
    public ElasticsearchClient client() {
        return client;
    }

    @Override
    public IngestMetric getMetric() {
        return metric;
    }

    @Override
    public HttpBulkNodeClient putMapping(String index) {
        if (client == null) {
            logger.warn("no client for put mapping");
            return this;
        }
        ClientHelper.putMapping(client, configHelper, index);
        return this;
    }

    @Override
    public HttpBulkNodeClient index(String index, String type, String id, String source) {
        if (closed) {
            throw new ElasticsearchException("client is closed");
        }
        try {
            if (metric != null) {
                metric.getCurrentIngest().inc();
            }
            bulkProcessor.add(new IndexRequest(index).type(type).id(id).create(false).source(source));
        } catch (Exception e) {
            throwable = e;
            closed = true;
            logger.error("bulk add of index request failed: " + e.getMessage(), e);
        } finally {
            if (metric != null) {
                metric.getCurrentIngest().dec();
            }
        }
        return this;
    }

    @Override
    public HttpBulkNodeClient bulkIndex(IndexRequest indexRequest) {
        if (closed) {
            throw new ElasticsearchException("client is closed");
        }
        try {
            if (metric != null) {
                metric.getCurrentIngest().inc();
            }
            bulkProcessor.add(indexRequest);
        } catch (Exception e) {
            throwable = e;
            closed = true;
            logger.error("bulk add of index request failed: " + e.getMessage(), e);
        } finally {
            if (metric != null) {
                metric.getCurrentIngest().dec();
            }
        }
        return this;
    }

    @Override
    public HttpBulkNodeClient delete(String index, String type, String id) {
        if (closed) {
            throw new ElasticsearchException("client is closed");
        }
        try {
            if (metric != null) {
                metric.getCurrentIngest().inc();
            }
            bulkProcessor.add(new DeleteRequest(index).type(type).id(id));
        } catch (Exception e) {
            throwable = e;
            closed = true;
            logger.error("bulk add of delete failed: " + e.getMessage(), e);
        } finally {
            if (metric != null) {
                metric.getCurrentIngest().dec();
            }
        }
        return this;
    }

    @Override
    public HttpBulkNodeClient bulkDelete(DeleteRequest deleteRequest) {
        if (closed) {
            throw new ElasticsearchException("client is closed");
        }
        try {
            if (metric != null) {
                metric.getCurrentIngest().inc();
            }
            bulkProcessor.add(deleteRequest);
        } catch (Exception e) {
            throwable = e;
            closed = true;
            logger.error("bulk add of delete failed: " + e.getMessage(), e);
        } finally {
            if (metric != null) {
                metric.getCurrentIngest().dec();
            }
        }
        return this;
    }

    @Override
    public HttpBulkNodeClient update(String index, String type, String id, String source) {
        if (closed) {
            throw new ElasticsearchException("client is closed");
        }
        try {
            metric.getCurrentIngest().inc();
            bulkProcessor.add(new UpdateRequest().index(index).type(type).id(id).upsert(source));
        } catch (Exception e) {
            throwable = e;
            closed = true;
            logger.error("bulk add of update request failed: " + e.getMessage(), e);
        } finally {
            metric.getCurrentIngest().dec();
        }
        return this;
    }

    @Override
    public HttpBulkNodeClient bulkUpdate(UpdateRequest updateRequest) {
        if (closed) {
            throw new ElasticsearchException("client is closed");
        }
        try {
            metric.getCurrentIngest().inc();
            bulkProcessor.add(updateRequest);
        } catch (Exception e) {
            throwable = e;
            closed = true;
            logger.error("bulk add of update request failed: " + e.getMessage(), e);
        } finally {
            metric.getCurrentIngest().dec();
        }
        return this;
    }

    @Override
    public HttpBulkNodeClient flushIngest() {
        if (closed) {
            throw new ElasticsearchException("client is closed");
        }
        logger.debug("flushing bulk processor");
        bulkProcessor.flush();
        return this;
    }

    @Override
    public HttpBulkNodeClient waitForResponses(TimeValue maxWaitTime) throws InterruptedException, ExecutionException {
        if (closed) {
            throw new ElasticsearchException("client is closed");
        }
        while (!bulkProcessor.awaitClose(maxWaitTime.getMillis(), TimeUnit.MILLISECONDS)) {
            logger.warn("still waiting for responses");
        }
        return this;
    }

    @Override
    public HttpBulkNodeClient startBulk(String index, long startRefreshIntervalMillis, long stopRefreshItervalMillis) throws IOException {
        if (metric == null) {
            return this;
        }
        if (!metric.isBulk(index)) {
            metric.setupBulk(index, startRefreshIntervalMillis, stopRefreshItervalMillis);
            ClientHelper.updateIndexSetting(client, index, "refresh_interval", startRefreshIntervalMillis + "ms");
        }
        return this;
    }

    @Override
    public HttpBulkNodeClient stopBulk(String index) throws IOException {
        if (metric == null) {
            return this;
        }
        if (metric.isBulk(index)) {
            ClientHelper.updateIndexSetting(client, index, "refresh_interval", metric.getStopBulkRefreshIntervals().get(index) + "ms");
            metric.removeBulk(index);
        }
        return this;
    }

    @Override
    public HttpBulkNodeClient flushIndex(String index) {
        ClientHelper.flushIndex(client, index);
        return this;
    }

    @Override
    public HttpBulkNodeClient refreshIndex(String index) {
        ClientHelper.refreshIndex(client, index);
        return this;
    }

    @Override
    public int updateReplicaLevel(String index, int level) throws IOException {
        return ClientHelper.updateReplicaLevel(client, index, level);
    }


    @Override
    public HttpBulkNodeClient waitForCluster(ClusterHealthStatus status, TimeValue timeout) throws IOException {
        ClientHelper.waitForCluster(client, status, timeout);
        return this;
    }

    @Override
    public int waitForRecovery(String index) throws IOException {
        return ClientHelper.waitForRecovery(client, index);
    }

    @Override
    public synchronized void shutdown() {
        try {
            if (bulkProcessor != null) {
                logger.debug("closing bulk processor...");
                bulkProcessor.close();
            }
            if (metric != null && metric.indices() != null && !metric.indices().isEmpty()) {
                logger.debug("stopping bulk mode for indices {}...", metric.indices());
                for (String index : ImmutableSet.copyOf(metric.indices())) {
                    stopBulk(index);
                }
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
    }

    @Override
    public HttpBulkNodeClient newIndex(String index) {
        return newIndex(index, null, null);
    }

    @Override
    public HttpBulkNodeClient newIndex(String index, String type, InputStream settings, InputStream mappings) throws IOException {
        configHelper.reset();
        configHelper.setting(settings);
        configHelper.mapping(type, mappings);
        return newIndex(index, configHelper.settings(), configHelper.mappings());
    }

    @Override
    public HttpBulkNodeClient newIndex(String index, Settings settings, Map<String, String> mappings) {
        if (closed) {
            throw new ElasticsearchException("client is closed");
        }
        if (client == null) {
            logger.warn("no client for create index");
            return this;
        }
        if (index == null) {
            logger.warn("no index name given to create index");
            return this;
        }
        CreateIndexRequestBuilder createIndexRequestBuilder =
                new CreateIndexRequestBuilder(client(), CreateIndexAction.INSTANCE).setIndex(index);
        if (settings != null) {
            logger.info("settings = {}", settings.getAsStructuredMap());
            createIndexRequestBuilder.setSettings(settings);
        }
        if (mappings != null) {
            for (String type : mappings.keySet()) {
                logger.info("found mapping for {}", type);
                createIndexRequestBuilder.addMapping(type, mappings.get(type));
            }
        }
        createIndexRequestBuilder.execute().actionGet();
        logger.info("index {} created", index);
        return this;
    }

    @Override
    public HttpBulkNodeClient newMapping(String index, String type, Map<String, Object> mapping) {
        PutMappingRequestBuilder putMappingRequestBuilder =
                new PutMappingRequestBuilder(client(), PutMappingAction.INSTANCE)
                        .setIndices(index)
                        .setType(type)
                        .setSource(mapping);
        putMappingRequestBuilder.execute().actionGet();
        logger.info("mapping created for index {} and type {}", index, type);
        return this;
    }


    @Override
    public HttpBulkNodeClient deleteIndex(String index) {
        if (closed) {
            throw new ElasticsearchException("client is closed");
        }
        if (client == null) {
            logger.warn("no client");
            return this;
        }
        if (index == null) {
            logger.warn("no index name given to delete index");
            return this;
        }
        DeleteIndexRequestBuilder deleteIndexRequestBuilder =
                new DeleteIndexRequestBuilder(client(), DeleteIndexAction.INSTANCE, index);
        deleteIndexRequestBuilder.execute().actionGet();
        return this;
    }

    @Override
    public boolean hasThrowable() {
        return throwable != null;
    }

    @Override
    public Throwable getThrowable() {
        return throwable;
    }

    public Settings getSettings() {
        return configHelper.settings();
    }

    public void setSettings(Settings settings) {
        configHelper.settings(settings);
    }

    public Settings.Builder getSettingsBuilder() {
        return configHelper.settingsBuilder();
    }

    public void setting(InputStream in) throws IOException {
        configHelper.setting(in);
    }

    public void addSetting(String key, String value) {
        configHelper.setting(key, value);
    }

    public void addSetting(String key, Boolean value) {
        configHelper.setting(key, value);
    }

    public void addSetting(String key, Integer value) {
        configHelper.setting(key, value);
    }

    public void mapping(String type, InputStream in) throws IOException {
        configHelper.mapping(type, in);
    }

    public void mapping(String type, String mapping) throws IOException {
        configHelper.mapping(type, mapping);
    }

}
