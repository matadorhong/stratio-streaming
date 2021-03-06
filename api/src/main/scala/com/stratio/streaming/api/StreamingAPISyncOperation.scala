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
package com.stratio.streaming.api

import java.util.UUID
import com.stratio.streaming.commons.messages.StratioStreamingMessage
import com.stratio.streaming.commons.exceptions.{StratioAPIGenericException, StratioAPISecurityException, StratioEngineOperationException}
import com.stratio.streaming.kafka.KafkaProducer
import com.stratio.streaming.zookeeper.ZookeeperConsumer
import com.stratio.streaming.commons.constants.REPLY_CODES._
import com.google.gson.Gson
import com.stratio.streaming.commons.dto.ActionCallbackDto

case class StreamingAPISyncOperation(
  kafkaProducer: KafkaProducer,
  zookeeperConsumer: ZookeeperConsumer,
  ackTimeOutInMs: Int)
  extends StreamingAPIOperation {

  /**
   * Sends the message to the StratioStreamingEngine and waits
   * for the Acknowledge to be written in zookeeper.
   *
   * @param message
   */
  def performSyncOperation(message: StratioStreamingMessage) = {
    val zNodeUniqueId = UUID.randomUUID().toString
    addMessageToKafkaTopic(message, zNodeUniqueId, kafkaProducer)
    val syncOperationResponse = waitForTheStreamingResponse(zookeeperConsumer, message, ackTimeOutInMs)
    manageStreamingResponse(syncOperationResponse, message)
  }

  private def manageStreamingResponse(response: String, message: StratioStreamingMessage) = {
    val responseDto = parseTheEngineResponse(response)
    responseDto match {
      case None => throw new StratioAPIGenericException("StratioEngine error: Unable to parse the engine response")
      case Some(responseDto) =>
        val replyCode = responseDto.getErrorCode
        val replyDescription = responseDto.getDescription
        replyCode match {
          case OK => {
            val messageOperation = message.getOperation
            val streamName = message.getStreamName
            log.info(s"StratioEngine Ack received for the operation $messageOperation on the $streamName stream")
          }
          case KO_STREAM_OPERATION_NOT_ALLOWED |
               KO_STREAM_IS_NOT_USER_DEFINED => {
            createLogError(replyCode, replyDescription)
            throw new StratioAPISecurityException(replyDescription)
          }
          case _ => {
            createLogError(replyCode, replyDescription)
            throw new StratioEngineOperationException("StratioEngine error: "+replyDescription)
          }
        }
    }

  }

  private def createLogError(responseCode: Int, errorDescription: String) = {
    log.error(s"StratioAPI - [ACK_CODE,QUERY_STRING]: [$responseCode,$errorDescription]")
  }

  private def parseTheEngineResponse(response: String): Option[ActionCallbackDto] = {
    try {
      val parsedResponse = new Gson().fromJson(response, classOf[ActionCallbackDto])
      parsedResponse.getErrorCode match {
        case null => None
        case _ => Some(parsedResponse)
      }
    } catch {
      case _ => None
    }
  }
}
