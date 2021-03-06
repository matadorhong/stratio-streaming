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
package com.stratio.streaming.functions.dal;

import java.util.Set;

import org.wso2.siddhi.core.SiddhiManager;

import com.stratio.streaming.commons.constants.REPLY_CODES;
import com.stratio.streaming.commons.constants.STREAM_OPERATIONS;
import com.stratio.streaming.commons.constants.StreamAction;
import com.stratio.streaming.commons.messages.StratioStreamingMessage;
import com.stratio.streaming.functions.ActionBaseFunction;
import com.stratio.streaming.functions.validator.ActionEnabledValidation;
import com.stratio.streaming.functions.validator.RequestValidation;
import com.stratio.streaming.functions.validator.StreamNotExistsValidation;
import com.stratio.streaming.streams.StreamOperations;

public class SaveToCassandraStreamFunction extends ActionBaseFunction {

    private static final long serialVersionUID = 6928586284081343386L;

    private final String cassandraCluster;

    public SaveToCassandraStreamFunction(SiddhiManager siddhiManager, String zookeeperHost, String cassandraCluster) {
        super(siddhiManager, zookeeperHost);
        this.cassandraCluster = cassandraCluster;
    }

    @Override
    protected String getStartOperationCommand() {
        return STREAM_OPERATIONS.ACTION.SAVETO_CASSANDRA;
    }

    @Override
    protected String getStopOperationCommand() {
        return STREAM_OPERATIONS.ACTION.STOP_SAVETO_CASSANDRA;
    }

    @Override
    protected boolean startAction(StratioStreamingMessage message) {
        StreamOperations.save2cassandraStream(message, cassandraCluster, getSiddhiManager());
        return true;
    }

    @Override
    protected boolean stopAction(StratioStreamingMessage message) {
        StreamOperations.stopSave2cassandraStream(message, getSiddhiManager());
        return true;
    }

    @Override
    protected void addStopRequestsValidations(Set<RequestValidation> validators) {
        validators.add(new StreamNotExistsValidation(getSiddhiManager()));
    }

    @Override
    protected void addStartRequestsValidations(Set<RequestValidation> validators) {
        validators.add(new ActionEnabledValidation(getSiddhiManager(), StreamAction.SAVE_TO_CASSANDRA,
                REPLY_CODES.KO_SAVE2CASSANDRA_STREAM_ALREADY_ENABLED));
        validators.add(new StreamNotExistsValidation(getSiddhiManager()));
    }
}
