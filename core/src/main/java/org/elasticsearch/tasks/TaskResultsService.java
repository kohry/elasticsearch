/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.tasks;

import org.apache.lucene.util.IOUtils;
import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.indices.create.CreateIndexRequest;
import org.elasticsearch.action.admin.indices.create.CreateIndexResponse;
import org.elasticsearch.action.admin.indices.create.TransportCreateIndexAction;
import org.elasticsearch.action.admin.indices.mapping.put.PutMappingResponse;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.io.Streams;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.indices.IndexAlreadyExistsException;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * Service that can persist task results
 */
public class TaskResultsService extends AbstractComponent {

    public static final String TASK_RESULT_INDEX = ".results";

    public static final String TASK_RESULT_TYPE = "result";

    public static final String TASK_RESULT_INDEX_MAPPING_FILE = "task-results-index-mapping.json";

    private final Client client;

    private final ClusterService clusterService;

    private final TransportCreateIndexAction createIndexAction;

    @Inject
    public TaskResultsService(Settings settings, Client client, ClusterService clusterService,
                              TransportCreateIndexAction createIndexAction) {
        super(settings);
        this.client = client;
        this.clusterService = clusterService;
        this.createIndexAction = createIndexAction;
    }

    public void persist(TaskResult taskResult, ActionListener<Void> listener) {

        ClusterState state = clusterService.state();

        if (state.routingTable().hasIndex(TASK_RESULT_INDEX) == false) {
            CreateIndexRequest createIndexRequest = new CreateIndexRequest();
            createIndexRequest.settings(taskResultIndexSettings());
            createIndexRequest.index(TASK_RESULT_INDEX);
            createIndexRequest.mapping(TASK_RESULT_TYPE, taskResultIndexMapping());
            createIndexRequest.cause("auto(task api)");

            createIndexAction.execute(null, createIndexRequest, new ActionListener<CreateIndexResponse>() {
                @Override
                public void onResponse(CreateIndexResponse result) {
                    doPersist(taskResult, listener);
                }

                @Override
                public void onFailure(Throwable e) {
                    if (ExceptionsHelper.unwrapCause(e) instanceof IndexAlreadyExistsException) {
                        // we have the index, do it
                        try {
                            doPersist(taskResult, listener);
                        } catch (Throwable e1) {
                            listener.onFailure(e1);
                        }
                    } else {
                        listener.onFailure(e);
                    }
                }
            });
        } else {
            IndexMetaData metaData = state.getMetaData().index(TASK_RESULT_INDEX);
            if (metaData.getMappings().containsKey(TASK_RESULT_TYPE) == false) {
                // The index already exists but doesn't have our mapping
                client.admin().indices().preparePutMapping(TASK_RESULT_INDEX).setType(TASK_RESULT_TYPE).setSource(taskResultIndexMapping())
                    .execute(new ActionListener<PutMappingResponse>() {
                                 @Override
                                 public void onResponse(PutMappingResponse putMappingResponse) {
                                     doPersist(taskResult, listener);
                                 }

                                 @Override
                                 public void onFailure(Throwable e) {
                                     listener.onFailure(e);
                                 }
                             }
                    );
            } else {
                doPersist(taskResult, listener);
            }
        }
    }


    private void doPersist(TaskResult taskResult, ActionListener<Void> listener) {
        client.prepareIndex(TASK_RESULT_INDEX, TASK_RESULT_TYPE, taskResult.getTaskId().toString()).setSource(taskResult.getResult())
            .execute(new ActionListener<IndexResponse>() {
                @Override
                public void onResponse(IndexResponse indexResponse) {
                    listener.onResponse(null);
                }

                @Override
                public void onFailure(Throwable e) {
                    listener.onFailure(e);
                }
            });

    }

    private Settings taskResultIndexSettings() {
        return Settings.builder()
            .put(IndexMetaData.INDEX_NUMBER_OF_SHARDS_SETTING.getKey(), 1)
            .put(IndexMetaData.INDEX_AUTO_EXPAND_REPLICAS_SETTING.getKey(), "0-1")
            .put(IndexMetaData.SETTING_PRIORITY, Integer.MAX_VALUE)
            .build();
    }

    public String taskResultIndexMapping() {
        try (InputStream is = getClass().getResourceAsStream(TASK_RESULT_INDEX_MAPPING_FILE)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Streams.copy(is, out);
            return out.toString(IOUtils.UTF_8);
        } catch (Exception e) {
            logger.error("failed to create tasks results index template [{}]", e, TASK_RESULT_INDEX_MAPPING_FILE);
            throw new IllegalStateException("failed to create tasks results index template [" + TASK_RESULT_INDEX_MAPPING_FILE + "]", e);
        }

    }
}
