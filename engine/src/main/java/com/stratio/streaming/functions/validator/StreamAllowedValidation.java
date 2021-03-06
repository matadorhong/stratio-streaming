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
package com.stratio.streaming.functions.validator;

import org.wso2.siddhi.core.SiddhiManager;

import com.stratio.streaming.commons.constants.REPLY_CODES;
import com.stratio.streaming.commons.messages.StratioStreamingMessage;
import com.stratio.streaming.exception.RequestValidationException;
import com.stratio.streaming.utils.SiddhiUtils;

public class StreamAllowedValidation extends BaseSiddhiRequestValidation {

    private final static String STREAM_OPERATION_NOT_ALLOWED = "Operation %s not allowed in stream %s";

    public StreamAllowedValidation(SiddhiManager sm) {
        super(sm);
    }

    @Override
    public void validate(StratioStreamingMessage request) throws RequestValidationException {
        if (!SiddhiUtils.isStreamAllowedForThisOperation(request.getStreamName(), request.getOperation())) {
            throw new RequestValidationException(REPLY_CODES.KO_STREAM_OPERATION_NOT_ALLOWED, String.format(
                    STREAM_OPERATION_NOT_ALLOWED, request.getOperation(), request.getStreamName()));
        }
    }
}
