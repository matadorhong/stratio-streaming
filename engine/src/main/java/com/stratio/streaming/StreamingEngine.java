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
package com.stratio.streaming;

import java.net.MalformedURLException;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.streaming.Duration;
import org.apache.spark.streaming.api.java.JavaDStream;
import org.apache.spark.streaming.api.java.JavaPairDStream;
import org.apache.spark.streaming.api.java.JavaStreamingContext;
import org.apache.spark.streaming.kafka.KafkaUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.siddhi.core.SiddhiManager;
import org.wso2.siddhi.query.api.definition.Attribute;
import org.wso2.siddhi.query.api.definition.StreamDefinition;

import com.google.common.net.HostAndPort;
import com.hazelcast.core.HazelcastInstanceNotActiveException;
import com.stratio.streaming.commons.constants.BUS;
import com.stratio.streaming.commons.constants.STREAMING;
import com.stratio.streaming.commons.constants.STREAM_OPERATIONS;
import com.stratio.streaming.commons.constants.StreamAction;
import com.stratio.streaming.commons.kafka.service.KafkaTopicService;
import com.stratio.streaming.commons.kafka.service.TopicService;
import com.stratio.streaming.commons.messages.StratioStreamingMessage;
import com.stratio.streaming.configuration.ConfigurationContext;
import com.stratio.streaming.functions.dal.IndexStreamFunction;
import com.stratio.streaming.functions.dal.ListenStreamFunction;
import com.stratio.streaming.functions.dal.SaveToCassandraStreamFunction;
import com.stratio.streaming.functions.dal.SaveToMongoStreamFunction;
import com.stratio.streaming.functions.ddl.AddQueryToStreamFunction;
import com.stratio.streaming.functions.ddl.AlterStreamFunction;
import com.stratio.streaming.functions.ddl.CreateStreamFunction;
import com.stratio.streaming.functions.dml.InsertIntoStreamFunction;
import com.stratio.streaming.functions.dml.ListStreamsFunction;
import com.stratio.streaming.functions.messages.FilterMessagesByOperationFunction;
import com.stratio.streaming.functions.messages.KeepPayloadFromMessageFunction;
import com.stratio.streaming.functions.requests.CollectRequestForStatsFunction;
import com.stratio.streaming.functions.requests.SaveRequestsToAuditLogFunction;
import com.stratio.streaming.streams.QueryDTO;
import com.stratio.streaming.streams.StreamPersistence;
import com.stratio.streaming.streams.StreamSharedStatus;
import com.stratio.streaming.utils.SiddhiUtils;
import com.stratio.streaming.utils.ZKUtils;

/**
 * @author dmorales
 * 
 *         ================= Stratio Streaming =================
 * 
 *         1) Run a global Siddhi CEP engine 2) Listen to the Kafka topic in
 *         order to receive stream commands (CREATE, ADDQUERY, LIST, DROP,
 *         INSERT, LISTEN, ALTER) 3) Execute commands and send ACKs to Zookeeper
 *         4) Send back the events if there are listeners
 * 
 */
public class StreamingEngine {

    private static Logger logger = LoggerFactory.getLogger(StreamingEngine.class);
    private static SiddhiManager siddhiManager;
    private static JavaStreamingContext streamingBaseContext;

    private static ConfigurationContext cc;

    /**
     * @param args
     * @throws MalformedURLException
     * @throws Exception
     */
    public static void main(String[] args) throws MalformedURLException {
        cc = new ConfigurationContext();
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {

                logger.info("Shutting down Stratio Streaming..");

                // shutdown spark
                if (streamingBaseContext != null) {
                    streamingBaseContext.stop();
                }

                // shutdown siddhi
                if (siddhiManager != null) {

                    // remove All revisions (HA)
                    StreamPersistence.removeEngineStatusFromCleanExit(getSiddhiManager());

                    // shutdown listeners

                    try {

                        getSiddhiManager().getSiddhiContext().getHazelcastInstance()
                                .getTopic(STREAMING.INTERNAL_LISTEN_TOPIC).publish("*");
                        getSiddhiManager().getSiddhiContext().getHazelcastInstance()
                                .getTopic(STREAMING.INTERNAL_SAVE2CASSANDRA_TOPIC).publish("*");

                    } catch (HazelcastInstanceNotActiveException notActive) {
                        logger.info("Hazelcast is not active at this moment");
                    }

                    getSiddhiManager().shutdown();
                }

                // shutdown zookeeper
                ZKUtils.shutdownZKUtils();

                logger.info("Shutdown complete, bye.");
            }
        });

        try {
            launchStratioStreamingEngine();
        } catch (Exception e) {
            logger.error("General error: " + e.getMessage() + " // " + e.getClass(), e);
        }

    }

    /**
     * 
     * - Launch the main process: spark context, kafkaInputDstream and siddhi
     * CEP engine - Filter the incoming messages (kafka) by key in order to
     * separate the different commands - Parse the request contained in the
     * payload - execute related command for each request
     * 
     * 
     * 
     * @param sparkMaster
     * @param zkCluster
     * @param kafkaCluster
     * @param topics
     * @throws Exception
     */
    private static void launchStratioStreamingEngine() throws Exception {

        ConfigurationContext cc = new ConfigurationContext();

        String topics = BUS.TOPICS;

        ZKUtils.getZKUtils(cc.getZookeeperHostsQuorum()).createEphemeralZNode(STREAMING.ZK_BASE_PATH + "/" + "engine",
                String.valueOf(System.currentTimeMillis()).getBytes());

        // Create the context with a x seconds batch size
        streamingBaseContext = new JavaStreamingContext(cc.getSparkHost(), StreamingEngine.class.getName(),
                new Duration(cc.getStreamingBatchTime()));
        streamingBaseContext.sparkContext().getConf().setJars(JavaStreamingContext.jarOfClass(StreamingEngine.class));

        KeepPayloadFromMessageFunction keepPayloadFromMessageFunction = new KeepPayloadFromMessageFunction();
        CreateStreamFunction createStreamFunction = new CreateStreamFunction(getSiddhiManager(),
                cc.getZookeeperHostsQuorum());
        AlterStreamFunction alterStreamFunction = new AlterStreamFunction(getSiddhiManager(),
                cc.getZookeeperHostsQuorum());
        InsertIntoStreamFunction insertIntoStreamFunction = new InsertIntoStreamFunction(getSiddhiManager(),
                cc.getZookeeperHostsQuorum());
        AddQueryToStreamFunction addQueryToStreamFunction = new AddQueryToStreamFunction(getSiddhiManager(),
                cc.getZookeeperHostsQuorum());
        ListenStreamFunction listenStreamFunction = new ListenStreamFunction(getSiddhiManager(),
                cc.getZookeeperHostsQuorum(), cc.getKafkaHostsQuorum());
        ListStreamsFunction listStreamsFunction = new ListStreamsFunction(getSiddhiManager(),
                cc.getZookeeperHostsQuorum());
        SaveToCassandraStreamFunction saveToCassandraStreamFunction = new SaveToCassandraStreamFunction(
                getSiddhiManager(), cc.getZookeeperHostsQuorum(), cc.getCassandraHostsQuorum());

        Map<String, Integer> topicMap = new HashMap<String, Integer>();
        String[] topicList = topics.split(",");

        // building the topic map, by using the num of partitions of each topic
        HostAndPort kafkaHostAndPort = HostAndPort.fromString(cc.getKafkaHosts().get(0));
        TopicService topicService = new KafkaTopicService(cc.getZookeeperHostsQuorum(), kafkaHostAndPort.getHostText(),
                kafkaHostAndPort.getPort(), cc.getKafkaConnectionTimeout(), cc.getKafkaSessionTimeout());
        for (String topic : topicList) {
            topicService.createTopicIfNotExist(topic, cc.getKafkaReplicationFactor(), cc.getKafkaPartitions());
            Integer partitions = topicService.getNumPartitionsForTopic(topic);
            if (partitions == 0) {
                partitions = cc.getKafkaPartitions();
            }
            topicMap.put(topic, partitions);
        }

        // Start the Kafka stream
        JavaPairDStream<String, String> messages = KafkaUtils.createStream(streamingBaseContext,
                cc.getZookeeperHostsQuorum(), BUS.STREAMING_GROUP_ID, topicMap);

        // as we are using messages several times, the best option is to cache
        // it
        messages.cache();

        if (cc.getElasticSearchHost() != null) {
            IndexStreamFunction indexStreamFunction = new IndexStreamFunction(getSiddhiManager(),
                    cc.getZookeeperHostsQuorum(), cc.getElasticSearchHost(), cc.getElasticSearchPort());

            JavaDStream<StratioStreamingMessage> streamToIndexerRequests = messages.filter(
                    new FilterMessagesByOperationFunction(STREAM_OPERATIONS.ACTION.INDEX)).map(
                    keepPayloadFromMessageFunction);

            JavaDStream<StratioStreamingMessage> stopStreamToIndexerRequests = messages.filter(
                    new FilterMessagesByOperationFunction(STREAM_OPERATIONS.ACTION.STOP_INDEX)).map(
                    keepPayloadFromMessageFunction);

            streamToIndexerRequests.foreachRDD(indexStreamFunction);

            stopStreamToIndexerRequests.foreachRDD(indexStreamFunction);
        } else {
            logger.warn("Elasticsearch configuration not found.");
        }

        if (cc.getMongoHost() != null) {
            SaveToMongoStreamFunction saveToMongoStreamFunction = new SaveToMongoStreamFunction(getSiddhiManager(),
                    cc.getZookeeperHostsQuorum(), cc.getMongoHost(), cc.getMongoPort(), cc.getMongoUsername(),
                    cc.getMongoPassword());

            JavaDStream<StratioStreamingMessage> saveToMongoRequests = messages.filter(
                    new FilterMessagesByOperationFunction(STREAM_OPERATIONS.ACTION.SAVETO_MONGO)).map(
                    keepPayloadFromMessageFunction);

            JavaDStream<StratioStreamingMessage> stopSaveToMongoRequests = messages.filter(
                    new FilterMessagesByOperationFunction(STREAM_OPERATIONS.ACTION.STOP_SAVETO_MONGO)).map(
                    keepPayloadFromMessageFunction);

            saveToMongoRequests.foreachRDD(saveToMongoStreamFunction);

            stopSaveToMongoRequests.foreach(saveToMongoStreamFunction);
        } else {
            logger.warn("Mongodb configuration not found.");
        }

        // Create a DStream for each command, so we can treat all related
        // requests in the same way and also apply functions by command
        JavaDStream<StratioStreamingMessage> createRequests = messages.filter(
                new FilterMessagesByOperationFunction(STREAM_OPERATIONS.DEFINITION.CREATE)).map(
                keepPayloadFromMessageFunction);

        JavaDStream<StratioStreamingMessage> alterRequests = messages.filter(
                new FilterMessagesByOperationFunction(STREAM_OPERATIONS.DEFINITION.ALTER)).map(
                keepPayloadFromMessageFunction);

        JavaDStream<StratioStreamingMessage> insertRequests = messages.filter(
                new FilterMessagesByOperationFunction(STREAM_OPERATIONS.MANIPULATION.INSERT)).map(
                keepPayloadFromMessageFunction);

        JavaDStream<StratioStreamingMessage> addQueryRequests = messages.filter(
                new FilterMessagesByOperationFunction(STREAM_OPERATIONS.DEFINITION.ADD_QUERY)).map(
                keepPayloadFromMessageFunction);

        JavaDStream<StratioStreamingMessage> removeQueryRequests = messages.filter(
                new FilterMessagesByOperationFunction(STREAM_OPERATIONS.DEFINITION.REMOVE_QUERY)).map(
                keepPayloadFromMessageFunction);

        JavaDStream<StratioStreamingMessage> listenRequests = messages.filter(
                new FilterMessagesByOperationFunction(STREAM_OPERATIONS.ACTION.LISTEN)).map(
                keepPayloadFromMessageFunction);

        JavaDStream<StratioStreamingMessage> stopListenRequests = messages.filter(
                new FilterMessagesByOperationFunction(STREAM_OPERATIONS.ACTION.STOP_LISTEN)).map(
                keepPayloadFromMessageFunction);

        JavaDStream<StratioStreamingMessage> saveToCassandraRequests = messages.filter(
                new FilterMessagesByOperationFunction(STREAM_OPERATIONS.ACTION.SAVETO_CASSANDRA)).map(
                keepPayloadFromMessageFunction);

        JavaDStream<StratioStreamingMessage> stopSaveToCassandraRequests = messages.filter(
                new FilterMessagesByOperationFunction(STREAM_OPERATIONS.ACTION.STOP_SAVETO_CASSANDRA)).map(
                keepPayloadFromMessageFunction);

        JavaDStream<StratioStreamingMessage> listRequests = messages.filter(
                new FilterMessagesByOperationFunction(STREAM_OPERATIONS.MANIPULATION.LIST)).map(
                keepPayloadFromMessageFunction);

        JavaDStream<StratioStreamingMessage> dropRequests = messages.filter(
                new FilterMessagesByOperationFunction(STREAM_OPERATIONS.DEFINITION.DROP)).map(
                keepPayloadFromMessageFunction);

        createRequests.foreachRDD(createStreamFunction);

        alterRequests.foreachRDD(alterStreamFunction);

        insertRequests.foreachRDD(insertIntoStreamFunction);

        addQueryRequests.foreachRDD(addQueryToStreamFunction);

        removeQueryRequests.foreachRDD(addQueryToStreamFunction);

        listenRequests.foreachRDD(listenStreamFunction);

        stopListenRequests.foreachRDD(listenStreamFunction);

        saveToCassandraRequests.foreachRDD(saveToCassandraStreamFunction);

        stopSaveToCassandraRequests.foreach(saveToCassandraStreamFunction);

        listRequests.foreachRDD(listStreamsFunction);

        dropRequests.foreachRDD(createStreamFunction);

        if (cc.isAuditEnabled() || cc.isStatsEnabled()) {

            JavaDStream<StratioStreamingMessage> allRequests = createRequests.union(alterRequests)
                    .union(insertRequests).union(addQueryRequests).union(removeQueryRequests)
                    .union(listenRequests).union(stopListenRequests).union(saveToCassandraRequests)
                    .union(listRequests).union(dropRequests);

            if (cc.isAuditEnabled()) {
                SaveRequestsToAuditLogFunction saveRequestsToAuditLogFunction = new SaveRequestsToAuditLogFunction(
                        getSiddhiManager(), cc.getZookeeperHostsQuorum(), cc.getKafkaHostsQuorum(),
                        cc.getCassandraHostsQuorum());

                // persist the RDDs to cassandra using STRATIO DEEP
                allRequests.window(new Duration(2000), new Duration(6000)).foreachRDD(saveRequestsToAuditLogFunction);
            }

            if (cc.isStatsEnabled()) {
                CollectRequestForStatsFunction collectRequestForStatsFunction = new CollectRequestForStatsFunction(
                        getSiddhiManager(), cc.getZookeeperHostsQuorum(), cc.getKafkaHostsQuorum());

                allRequests.window(new Duration(2000), new Duration(6000)).foreachRDD(collectRequestForStatsFunction);

            }
        }

        StreamPersistence.saveStreamingEngineStatus(getSiddhiManager());

        if (cc.isPrintStreams()) {

            // DEBUG STRATIO STREAMING ENGINE //
            messages.count().foreach(new Function<JavaRDD<Long>, Void>() {

                private static final long serialVersionUID = -2371501158355376325L;

                @Override
                public Void call(JavaRDD<Long> arg0) throws Exception {
                    StringBuffer sb = new StringBuffer();
                    sb.append("\n********************************************\n");
                    sb.append("**            SIDDHI STREAMS              **\n");
                    sb.append("** countSiddhi:");
                    sb.append(siddhiManager.getStreamDefinitions().size());
                    sb.append(" // countHazelcast: ");
                    sb.append(getSiddhiManager().getSiddhiContext().getHazelcastInstance()
                            .getMap(STREAMING.STREAM_STATUS_MAP).size());
                    sb.append("     **\n");

                    for (StreamDefinition streamMetaData : getSiddhiManager().getStreamDefinitions()) {

                        StringBuffer streamDefinition = new StringBuffer();

                        streamDefinition.append(streamMetaData.getStreamId());

                        for (Attribute column : streamMetaData.getAttributeList()) {
                            streamDefinition.append(" |" + column.getName() + "," + column.getType());
                        }

                        if (StreamSharedStatus.getStreamStatus(streamMetaData.getStreamId(), getSiddhiManager()) != null) {
                            HashMap<String, QueryDTO> attachedQueries = StreamSharedStatus.getStreamStatus(
                                    streamMetaData.getStreamId(), getSiddhiManager()).getAddedQueries();

                            streamDefinition.append(" /// " + attachedQueries.size() + " attachedQueries: (");

                            for (String queryId : attachedQueries.keySet()) {
                                streamDefinition.append(queryId + "/");
                            }

                            streamDefinition.append(" - userDefined:"
                                    + StreamSharedStatus.getStreamStatus(streamMetaData.getStreamId(),
                                            getSiddhiManager()).isUserDefined() + "- ");
                            streamDefinition.append(" - listenEnable:"
                                    + StreamSharedStatus.getStreamStatus(streamMetaData.getStreamId(),
                                            getSiddhiManager()).isActionEnabled(StreamAction.LISTEN) + "- ");
                        }

                        sb.append("** stream: ".concat(streamDefinition.toString()).concat("\n"));
                    }

                    sb.append("********************************************\n");

                    logger.info(sb.toString());

                    StreamPersistence.saveStreamingEngineStatus(getSiddhiManager());

                    return null;
                }

            });
        }

        streamingBaseContext.start();
        logger.info("Stratio streaming started at {}", new Date());
        streamingBaseContext.awaitTermination();

    }

    private static SiddhiManager getSiddhiManager() {
        if (siddhiManager == null) {
            siddhiManager = SiddhiUtils.setupSiddhiManager(cc.getCassandraHostsQuorum(), cc.isFailOverEnabled());
        }

        return siddhiManager;
    }

}
