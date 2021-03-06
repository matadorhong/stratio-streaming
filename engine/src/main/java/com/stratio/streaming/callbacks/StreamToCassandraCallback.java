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
package com.stratio.streaming.callbacks;

import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.siddhi.core.event.Event;
import org.wso2.siddhi.core.event.in.InEvent;
import org.wso2.siddhi.core.stream.output.StreamCallback;
import org.wso2.siddhi.query.api.definition.Attribute;
import org.wso2.siddhi.query.api.definition.StreamDefinition;
import org.wso2.siddhi.query.compiler.exception.SiddhiPraserException;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.Cluster.Builder;
import com.datastax.driver.core.ColumnMetadata;
import com.datastax.driver.core.DataType;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.exceptions.AlreadyExistsException;
import com.datastax.driver.core.querybuilder.Insert;
import com.datastax.driver.core.utils.UUIDs;
import com.google.common.collect.Lists;
import com.hazelcast.core.Message;
import com.hazelcast.core.MessageListener;
import com.stratio.streaming.commons.constants.STREAMING;
import com.stratio.streaming.commons.messages.ColumnNameTypeValue;
import com.stratio.streaming.commons.messages.StratioStreamingMessage;
import com.stratio.streaming.utils.SiddhiUtils;

public class StreamToCassandraCallback extends StreamCallback implements MessageListener<String> {

    private static Logger logger = LoggerFactory.getLogger(StreamToCassandraCallback.class);

    private StreamDefinition streamDefinition;
    private String cassandraNodesCluster;
    private Boolean running;
    private Session cassandraSession;
    private Cluster cassandraCluster;

    public StreamToCassandraCallback(StreamDefinition streamDefinition, String cassandraNodesCluster) {
        this.streamDefinition = streamDefinition;
        this.cassandraNodesCluster = cassandraNodesCluster;
        running = Boolean.TRUE;

        init();

        logger.debug("Starting listener for stream " + streamDefinition.getStreamId());

    }

    private void init() {
        Builder cassandraBuilder = new Cluster.Builder();

        for (String node : cassandraNodesCluster.split(",")) {
            // Add data center to Cassandra cluster
            cassandraBuilder.addContactPoint(node);
        }

        Cluster cassandraCluster = cassandraBuilder.build();
        cassandraSession = cassandraCluster.connect();

        if (cassandraSession.getCluster().getMetadata().getKeyspace(STREAMING.STREAMING_KEYSPACE_NAME) == null) {
            cassandraSession.execute(STREAMING.CREATE_STREAMING_KEYSPACE);
        }

        try {
            cassandraSession.execute(generateCassandraCreateTableForStream());
        } catch (AlreadyExistsException ae) {
            logger.info("Stream table already exists");
        }

    }

    private HashMap<String, String> getStreamFieldsAndTypes() {

        HashMap<String, String> fields = new HashMap<String, String>();
        for (Attribute field : streamDefinition.getAttributeList()) {
            switch (field.getType()) {
            case BOOL:
                fields.put(field.getName(), DataType.Name.BOOLEAN.toString());
                break;
            case DOUBLE:
                fields.put(field.getName(), DataType.Name.DOUBLE.toString());
                break;
            case FLOAT:
                fields.put(field.getName(), DataType.Name.FLOAT.toString());
                break;
            case INT:
                fields.put(field.getName(), DataType.Name.INT.toString());
                break;
            case LONG:
                fields.put(field.getName(), DataType.Name.DOUBLE.toString());
                break;
            case STRING:
                fields.put(field.getName(), DataType.Name.TEXT.toString());
                break;
            default:
                throw new SiddhiPraserException("Unsupported Column type");
            }
        }

        return fields;
    }

    private String generateCassandraCreateTableForStream() {

        String cqlFieldsAndTypes = "";

        for (Entry<String, String> entry : getStreamFieldsAndTypes().entrySet()) {
            cqlFieldsAndTypes += entry.getKey() + " " + entry.getValue() + ",";

        }

        return "CREATE TABLE " + STREAMING.STREAMING_KEYSPACE_NAME + "." + streamDefinition.getStreamId()
                + "(time_taken timeuuid," + cqlFieldsAndTypes
                + "PRIMARY KEY (time_taken)) WITH compression = {'sstable_compression': ''}";

    }

    private String generateCassandraAlterTableForStream() {

        StringBuffer cqlFieldsAndTypes = new StringBuffer();
        List<String> streamTableColumnNames = getTableColumnNames();

        for (Entry<String, String> entry : getStreamFieldsAndTypes().entrySet()) {
            if (!streamTableColumnNames.contains(entry.getKey())) {
                cqlFieldsAndTypes.append("ALTER TABLE " + STREAMING.STREAMING_KEYSPACE_NAME + "."
                        + streamDefinition.getStreamId() + " ADD " + entry.getKey() + " " + entry.getValue() + ";");
            }
        }

        return cqlFieldsAndTypes.toString();
    }

    private List<String> getTableColumnNames() {

        List<String> columnNames = Lists.newArrayList();

        for (ColumnMetadata column : cassandraSession.getCluster().getMetadata()
                .getKeyspace(STREAMING.STREAMING_KEYSPACE_NAME).getTable(streamDefinition.getStreamId()).getColumns()) {
            columnNames.add(column.getName());
        }

        return columnNames;

    }

    @Override
    public void receive(Event[] events) {

        if (running) {

            List<StratioStreamingMessage> collectedEvents = Lists.newArrayList();

            for (Event e : events) {

                if (e instanceof InEvent) {
                    InEvent ie = (InEvent) e;

                    List<ColumnNameTypeValue> columns = Lists.newArrayList();

                    for (Attribute column : streamDefinition.getAttributeList()) {

                        // avoid retrieving a value out of the scope
                        // outputStream could have more fields defined than the
                        // output events (projection)
                        if (ie.getData().length >= streamDefinition.getAttributePosition(column.getName()) + 1) {

                            columns.add(new ColumnNameTypeValue(column.getName(), SiddhiUtils.encodeSiddhiType(column
                                    .getType()), ie.getData(streamDefinition.getAttributePosition(column.getName()))));

                        }

                    }

                    collectedEvents.add(new StratioStreamingMessage(streamDefinition.getStreamId(), // value.streamName
                            ie.getTimeStamp(), // value.timestamp
                            columns)); // value.columns
                }
            }

            persistEventsToCassandra(collectedEvents);
        }

    }

    private void persistEventsToCassandra(List<StratioStreamingMessage> collectedEvents) {

        List<Insert> statements = Lists.newArrayList();

        HashMap<String, String> fieldsAndTypes = getStreamFieldsAndTypes();

        for (StratioStreamingMessage event : collectedEvents) {

            // check if current
            if (fieldsAndTypes.size() > (cassandraSession.getCluster().getMetadata()
                    .getKeyspace(STREAMING.STREAMING_KEYSPACE_NAME).getTable(streamDefinition.getStreamId())
                    .getColumns().size() - 1)) {
                logger.debug("Enlarging stream table to store new fields with query: "
                        + generateCassandraAlterTableForStream());
                cassandraSession.execute(generateCassandraAlterTableForStream());
            }

            Object[] values = new Object[event.getColumns().size() + 1];
            String[] fields = new String[fieldsAndTypes.size() + 1];

            fields[0] = "time_taken";
            values[0] = UUIDs.startOf(System.currentTimeMillis());

            int i = 1;
            for (String field : fieldsAndTypes.keySet()) {
                fields[i] = field;
                values[i] = event.getColumns()
                        .get(event.getColumns().indexOf(new ColumnNameTypeValue(field, null, null))).getValue();
                i++;
            }

            Insert hola = com.datastax.driver.core.querybuilder.QueryBuilder.insertInto(
                    STREAMING.STREAMING_KEYSPACE_NAME, streamDefinition.getStreamId()).values(fields, values);
            logger.debug("------------------->" + hola.getQueryString());
            statements.add(hola);
        }

        for (Insert statement : statements) {
            cassandraSession.execute(statement);
        }

    }

    private void shutdownCallback() {
        if (running) {
            if (cassandraSession != null) {
                cassandraSession.close();
            }
            if (cassandraCluster != null) {
                cassandraCluster.close();
            }
        }
    }

    @Override
    public void onMessage(Message<String> message) {
        if (running) {
            if (message.getMessageObject().equalsIgnoreCase(streamDefinition.getStreamId())
                    || message.getMessageObject().equalsIgnoreCase("*")) {

                shutdownCallback();
                running = Boolean.FALSE;
                logger.debug("Shutting down save2cassandra for stream " + streamDefinition.getStreamId());
            }
        }

    }

}
