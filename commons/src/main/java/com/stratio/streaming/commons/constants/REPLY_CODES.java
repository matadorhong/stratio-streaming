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
package com.stratio.streaming.commons.constants;

public abstract class REPLY_CODES {

    public static final Integer OK = 1;
    public static final Integer KO_PARSER_ERROR = 2;
    public static final Integer KO_STREAM_ALREADY_EXISTS = 3;
    public static final Integer KO_STREAM_DOES_NOT_EXIST = 4;
    public static final Integer KO_QUERY_ALREADY_EXISTS = 5;
    public static final Integer KO_LISTENER_ALREADY_EXISTS = 6;
    public static final Integer KO_GENERAL_ERROR = 7;
    public static final Integer KO_COLUMN_ALREADY_EXISTS = 8;
    public static final Integer KO_COLUMN_DOES_NOT_EXIST = 9;
    public static final Integer KO_LISTENER_DOES_NOT_EXIST = 10;
    public static final Integer KO_QUERY_DOES_NOT_EXIST = 11;
    public static final Integer KO_STREAM_IS_NOT_USER_DEFINED = 12;
    public static final Integer KO_OUTPUTSTREAM_EXISTS_AND_DEFINITION_IS_DIFFERENT = 13;
    public static final Integer KO_SAVE2CASSANDRA_STREAM_ALREADY_ENABLED = 14;
    public static final Integer KO_SOURCE_STREAM_DOES_NOT_EXIST = 15;
    public static final Integer KO_INDEX_STREAM_ALREADY_ENABLED = 16;
    public static final Integer KO_STREAM_OPERATION_NOT_ALLOWED = 17;
    public static final Integer KO_SAVE2MONGO_STREAM_ALREADY_ENABLED = 18;

    public static String getReadableErrorFromCode(Integer code) {

        String decodedReply = "";

        switch (code) {
        case 1:
            decodedReply = "OK";
            break;
        case 2:
            decodedReply = "KO: PARSER ERROR";
            break;
        case 3:
            decodedReply = "KO: STREAM ALREADY EXISTS";
            break;
        case 4:
            decodedReply = "KO: STREAM DOES NOT EXIST ";
            break;
        case 5:
            decodedReply = "KO: QUERY ALREADY EXISTS";
            break;
        case 6:
            decodedReply = "KO: LISTENER ALREADY EXISTS";
            break;
        case 7:
            decodedReply = "KO: GENERAL ERROR";
            break;
        case 8:
            decodedReply = "KO: COLUMN ALREADY EXISTS";
            break;
        case 9:
            decodedReply = "KO: COLUMN DOES NOT EXIST";
            break;
        case 10:
            decodedReply = "KO: LISTENER DOES NOT EXIST";
            break;
        case 11:
            decodedReply = "KO: QUERY DOES NOT EXIST";
            break;
        case 12:
            decodedReply = "KO: STREAM IS NOT USER_DEFINED";
            break;
        case 13:
            decodedReply = "KO: OUTPUT STREAM ALREADY EXISTS AND ITS DEFINITION IS DIFFERENT";
            break;
        case 14:
            decodedReply = "KO: SAVE2CASSANDRA IN THIS STREAM IS ALREADY_ENABLED";
            break;
        case 15:
            decodedReply = "KO: SOURCE STREAM IN QUERY DOES NOT EXIST";
            break;
        case 16:
            decodedReply = "KO: INDEX IN THIS STREAM IS ALREADY_ENABLED";
            break;
        case 17:
            decodedReply = "KO: STREAM OPERATION NOT ALLOWED";
            break;
        case 18:
            decodedReply = "KO: SAVE2MONGO IN THIS STREAM IS ALREADY_ENABLED";
            break;
        default:
            decodedReply = "UNKOWN ERROR";
            break;
        }

        return decodedReply;
    }
}
