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

import java.util.List;
import java.util.Properties;

import kafka.javaapi.producer.Producer;
import kafka.producer.KeyedMessage;
import kafka.producer.ProducerConfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.siddhi.core.event.Event;
import org.wso2.siddhi.core.event.in.InEvent;
import org.wso2.siddhi.core.stream.output.StreamCallback;
import org.wso2.siddhi.query.api.definition.Attribute;
import org.wso2.siddhi.query.api.definition.StreamDefinition;

import com.google.common.collect.Lists;
import com.google.gson.Gson;
import com.hazelcast.core.Message;
import com.hazelcast.core.MessageListener;
import com.stratio.streaming.commons.messages.ColumnNameTypeValue;
import com.stratio.streaming.commons.messages.StratioStreamingMessage;
import com.stratio.streaming.utils.SiddhiUtils;

public class StreamToBusCallback extends StreamCallback implements MessageListener<String> {

    private static Logger logger = LoggerFactory.getLogger(StreamToBusCallback.class);

    private StreamDefinition streamDefinition;
    private String kafkaCluster;
    private Producer<String, String> producer;
    private Boolean running;

    public StreamToBusCallback(StreamDefinition streamDefinition, String kafkaCluster) {
        this.streamDefinition = streamDefinition;
        this.kafkaCluster = kafkaCluster;
        this.producer = new Producer<String, String>(createProducerConfig());
        running = Boolean.TRUE;
        logger.debug("Starting listener for stream " + streamDefinition.getStreamId());
    }

    @Override
    public void receive(Event[] events) {

        if (running) {

            List<StratioStreamingMessage> collected_events = Lists.newArrayList();

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

                    collected_events.add(new StratioStreamingMessage(streamDefinition.getStreamId(), // value.streamName
                            ie.getTimeStamp(), // value.timestamp
                            columns)); // value.columns

                }
            }

            sendEventsToBus(collected_events);
        }

    }

    private void sendEventsToBus(List<StratioStreamingMessage> collected_events) {

        for (StratioStreamingMessage event : collected_events) {

            KeyedMessage<String, String> message = new KeyedMessage<String, String>(streamDefinition.getId(), // topic
                    streamDefinition.getId() + "event", // key
                    new Gson().toJson(event)); // message

            producer.send(message);

        }

    }

    private ProducerConfig createProducerConfig() {
        Properties properties = new Properties();
        properties.put("serializer.class", "kafka.serializer.StringEncoder");
        properties.put("metadata.broker.list", kafkaCluster);
        properties.put("producer.type", "async");

        return new ProducerConfig(properties);
    }

    private void shutdownCallback() {
        if (running) {
            this.producer.close();
        }
    }

    @Override
    public void onMessage(Message<String> message) {
        if (running) {
            if (message.getMessageObject().equalsIgnoreCase(streamDefinition.getStreamId())
                    || message.getMessageObject().equalsIgnoreCase("*")) {

                shutdownCallback();
                running = Boolean.FALSE;
                logger.debug("Shutting down listener for stream " + streamDefinition.getStreamId());
            }
        }

    }

}
