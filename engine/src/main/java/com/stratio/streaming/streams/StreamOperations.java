/**
 * Copyright (C) 2014 Stratio (http://stratio.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.stratio.streaming.streams;

import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;

import org.wso2.siddhi.core.SiddhiManager;
import org.wso2.siddhi.query.api.definition.Attribute;
import org.wso2.siddhi.query.api.definition.StreamDefinition;
import org.wso2.siddhi.query.api.exception.AttributeAlreadyExistException;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.hazelcast.core.IMap;
import com.hazelcast.core.ITopic;
import com.stratio.streaming.callbacks.StreamToBusCallback;
import com.stratio.streaming.callbacks.StreamToCassandraCallback;
import com.stratio.streaming.callbacks.StreamToIndexerCallback;
import com.stratio.streaming.callbacks.StreamToMongoCallback;
import com.stratio.streaming.commons.constants.STREAMING;
import com.stratio.streaming.commons.constants.StreamAction;
import com.stratio.streaming.commons.messages.ColumnNameTypeValue;
import com.stratio.streaming.commons.messages.StratioStreamingMessage;
import com.stratio.streaming.commons.messages.StreamQuery;
import com.stratio.streaming.utils.SiddhiUtils;

public class StreamOperations {

    private StreamOperations() {
    }

    public static void createStream(StratioStreamingMessage request, SiddhiManager siddhiManager) {
        // create stream in siddhi
        siddhiManager.defineStream(SiddhiUtils.buildDefineStreamSiddhiQL(request));

        // register stream in shared memory
        StreamSharedStatus.createStreamStatus(request.getStreamName(), siddhiManager);
    }

    public static int enlargeStream(StratioStreamingMessage request, SiddhiManager siddhiManager) {

        int addedColumns = 0;
        StreamDefinition streamMetaData = siddhiManager.getStreamDefinition(request.getStreamName());

        for (ColumnNameTypeValue columnNameTypeValue : request.getColumns()) {

            // Siddhi will throw an exception if you try to add a column that
            // already exists,
            // so we first try to find it in the stream
            if (!SiddhiUtils.columnAlreadyExistsInStream(columnNameTypeValue.getColumn(), streamMetaData)) {

                addedColumns++;
                streamMetaData.attribute(columnNameTypeValue.getColumn(),
                        SiddhiUtils.decodeSiddhiType(columnNameTypeValue.getType()));

            } else {
                throw new AttributeAlreadyExistException(columnNameTypeValue.getColumn());
            }
        }

        StreamSharedStatus.updateStreamDefinitionStreamStatus(request.getStreamName(), siddhiManager);

        return addedColumns;

    }

    public static void dropStream(StratioStreamingMessage request, SiddhiManager siddhiManager) {

        // stop all listeners
        siddhiManager.getSiddhiContext().getHazelcastInstance().getTopic(STREAMING.INTERNAL_LISTEN_TOPIC)
                .publish(request.getStreamName());

        // remove all queries
        if (StreamSharedStatus.getStreamStatus(request.getStreamName(), siddhiManager) != null) {
            HashMap<String, QueryDTO> attachedQueries = StreamSharedStatus.getStreamStatus(request.getStreamName(),
                    siddhiManager).getAddedQueries();

            for (String queryId : attachedQueries.keySet()) {
                siddhiManager.removeQuery(queryId);
            }
        }

        // then we removeStream in siddhi
        siddhiManager.removeStream(request.getStreamName());

        // drop the streamStatus
        StreamSharedStatus.removeStreamStatus(request.getStreamName(), siddhiManager);

    }

    public static void addQueryToExistingStream(StratioStreamingMessage request, SiddhiManager siddhiManager) {
        // add query to siddhi
        String queryId = siddhiManager.addQuery(request.getRequest().replaceAll("timebatch", "timeBatch"));
        // register query in stream status
        StreamSharedStatus
                .addQueryToStreamStatus(queryId, request.getRequest(), request.getStreamName(), siddhiManager);

        // check the streams to see if there are new ones, inferred from queries
        // (not user defined)
        for (StreamDefinition streamMetaData : siddhiManager.getStreamDefinitions()) {
            // by getting the stream, it will be created if don't exists (user
            // defined is false)
            StreamSharedStatus.getStreamStatus(streamMetaData.getStreamId(), siddhiManager);
        }
    }

    public static void removeQueryFromExistingStream(StratioStreamingMessage request, SiddhiManager siddhiManager) {

        // remove query in stream status
        String queryId = StreamSharedStatus.removeQueryInStreamStatus(request.getRequest(), request.getStreamName(),
                siddhiManager);

        // remove query in siddhi
        siddhiManager.removeQuery(queryId);

        // recover all cached streams
        IMap<Object, Object> streamStatusMap = siddhiManager.getSiddhiContext().getHazelcastInstance()
                .getMap(STREAMING.STREAM_STATUS_MAP);

        // we will see if siddhi has removed any streams automatically
        for (Entry<Object, Object> streamStatus : streamStatusMap.entrySet()) {

            String streamName = (String) streamStatus.getKey();

            // if this stream does not exist in siddhi
            if (siddhiManager.getStreamDefinition(streamName) == null) {
                // stop all listeners
                siddhiManager.getSiddhiContext().getHazelcastInstance().getTopic(STREAMING.INTERNAL_LISTEN_TOPIC)
                        .publish(streamName);

                // drop the streamStatus
                StreamSharedStatus.removeStreamStatus(streamName, siddhiManager);

            }
        }
    }

    public static List<StratioStreamingMessage> listStreams(StratioStreamingMessage request, SiddhiManager siddhiManager) {

        List<StratioStreamingMessage> streams = Lists.newArrayList();

        for (StreamDefinition streamMetaData : siddhiManager.getStreamDefinitions()) {

            if (!Arrays.asList(STREAMING.STATS_NAMES.STATS_STREAMS).contains(streamMetaData.getStreamId())) {

                List<ColumnNameTypeValue> columns = Lists.newArrayList();
                List<StreamQuery> queries = Lists.newArrayList();
                Set<StreamAction> actions = Sets.newHashSet();
                boolean isUserDefined = false;

                for (Attribute column : streamMetaData.getAttributeList()) {
                    columns.add(new ColumnNameTypeValue(column.getName(),
                            SiddhiUtils.encodeSiddhiType(column.getType()), null));
                }

                StreamStatusDTO streamStatus = StreamSharedStatus.getStreamStatus(streamMetaData.getStreamId(),
                        siddhiManager);

                if (streamStatus != null) {
                    HashMap<String, QueryDTO> attachedQueries = streamStatus.getAddedQueries();

                    for (Entry<String, QueryDTO> entry : attachedQueries.entrySet()) {
                        queries.add(new StreamQuery(entry.getKey(), entry.getValue().getQueryRaw()));
                    }

                    isUserDefined = streamStatus.isUserDefined();

                    actions = streamStatus.getActionsEnabled();
                }

                StratioStreamingMessage streamMessage = new StratioStreamingMessage(streamMetaData.getId(), columns,
                        queries);
                streamMessage.setUserDefined(isUserDefined);
                streamMessage.setActiveActions(actions);

                streams.add(streamMessage);
            }
        }

        return streams;

    }

    public static void listenStream(StratioStreamingMessage request, String kafkaCluster, SiddhiManager siddhiManager) {

        StreamToBusCallback streamCallBack = new StreamToBusCallback(siddhiManager.getStreamDefinition(request
                .getStreamName()), kafkaCluster);

        ITopic<String> listenTopic = siddhiManager.getSiddhiContext().getHazelcastInstance()
                .getTopic(STREAMING.INTERNAL_LISTEN_TOPIC);
        listenTopic.addMessageListener(streamCallBack);

        siddhiManager.addCallback(request.getStreamName(), streamCallBack);

        // TODO to avoid an error when a first event is sended to a non created
        // topic, create the topic first (BUG KAFKA-1124)

        StreamSharedStatus.changeActionStreamStatus(Boolean.TRUE, request.getStreamName(), siddhiManager,
                StreamAction.LISTEN);
    }

    public static void stopListenStream(StratioStreamingMessage request, SiddhiManager siddhiManager) {

        siddhiManager.getSiddhiContext().getHazelcastInstance().getTopic(STREAMING.INTERNAL_LISTEN_TOPIC)
                .publish(request.getStreamName());

        // TODO to avoid an error when a first event is sended to a non created
        // topic, delete the topic first (BUG KAFKA-1124)

        StreamSharedStatus.changeActionStreamStatus(Boolean.FALSE, request.getStreamName(), siddhiManager,
                StreamAction.LISTEN);
    }

    public static void save2cassandraStream(StratioStreamingMessage request, String cassandraCluster,
            SiddhiManager siddhiManager) {

        StreamToCassandraCallback cassandraCallBack = new StreamToCassandraCallback(
                siddhiManager.getStreamDefinition(request.getStreamName()), cassandraCluster);

        ITopic<String> save2cassandraTopic = siddhiManager.getSiddhiContext().getHazelcastInstance()
                .getTopic(STREAMING.INTERNAL_SAVE2CASSANDRA_TOPIC);
        save2cassandraTopic.addMessageListener(cassandraCallBack);

        siddhiManager.addCallback(request.getStreamName(), cassandraCallBack);

        StreamSharedStatus.changeActionStreamStatus(Boolean.TRUE, request.getStreamName(), siddhiManager,
                StreamAction.SAVE_TO_CASSANDRA);
    }

    public static void stopSave2cassandraStream(StratioStreamingMessage request, SiddhiManager siddhiManager) {

        siddhiManager.getSiddhiContext().getHazelcastInstance().getTopic(STREAMING.INTERNAL_SAVE2CASSANDRA_TOPIC)
                .publish(request.getStreamName());

        StreamSharedStatus.changeActionStreamStatus(Boolean.FALSE, request.getStreamName(), siddhiManager,
                StreamAction.SAVE_TO_CASSANDRA);
    }

    public static void streamToIndexer(StratioStreamingMessage request, String elasticSearchHost,
            int elasticSearchPort, SiddhiManager siddhiManager) {

        StreamToIndexerCallback streamToIndexerCallback = new StreamToIndexerCallback(
                siddhiManager.getStreamDefinition(request.getStreamName()), elasticSearchHost, elasticSearchPort);

        ITopic<String> indexerTopic = siddhiManager.getSiddhiContext().getHazelcastInstance()
                .getTopic(STREAMING.INTERNAL_INDEXER_TOPIC);
        indexerTopic.addMessageListener(streamToIndexerCallback);

        siddhiManager.addCallback(request.getStreamName(), streamToIndexerCallback);

        StreamSharedStatus.changeActionStreamStatus(Boolean.TRUE, request.getStreamName(), siddhiManager,
                StreamAction.INDEXED);
    }

    public static void stopStreamToIndexer(StratioStreamingMessage request, SiddhiManager siddhiManager) {

        siddhiManager.getSiddhiContext().getHazelcastInstance().getTopic(STREAMING.INTERNAL_INDEXER_TOPIC)
                .publish(request.getStreamName());
        StreamSharedStatus.changeActionStreamStatus(Boolean.FALSE, request.getStreamName(), siddhiManager,
                StreamAction.INDEXED);
    }

    public static void save2mongoStream(StratioStreamingMessage request, String mongoHost, int mongoPort,
            String username, String password, SiddhiManager siddhiManager) throws UnknownHostException {

        StreamToMongoCallback mongoCallBack = new StreamToMongoCallback(siddhiManager.getStreamDefinition(request
                .getStreamName()), mongoHost, mongoPort, username, password);

        ITopic<String> save2mongoTopic = siddhiManager.getSiddhiContext().getHazelcastInstance()
                .getTopic(STREAMING.INTERNAL_SAVE2MONGO_TOPIC);
        save2mongoTopic.addMessageListener(mongoCallBack);

        siddhiManager.addCallback(request.getStreamName(), mongoCallBack);

        StreamSharedStatus.changeActionStreamStatus(Boolean.TRUE, request.getStreamName(), siddhiManager,
                StreamAction.SAVE_TO_MONGO);
    }

    public static void stopSave2mongoStream(StratioStreamingMessage request, SiddhiManager siddhiManager) {

        siddhiManager.getSiddhiContext().getHazelcastInstance().getTopic(STREAMING.INTERNAL_SAVE2MONGO_TOPIC)
                .publish(request.getStreamName());

        StreamSharedStatus.changeActionStreamStatus(Boolean.FALSE, request.getStreamName(), siddhiManager,
                StreamAction.SAVE_TO_MONGO);
    }
}
