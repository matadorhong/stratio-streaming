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
package com.stratio.streaming.unit

import org.scalatest._
import org.scalatest.mock.MockitoSugar
import com.stratio.streaming.commons.messages.StratioStreamingMessage
import org.mockito.Mockito
import org.mockito.Matchers._
import com.stratio.streaming.kafka.KafkaProducer
import com.stratio.streaming.api.StreamingAPIListOperation
import com.stratio.streaming.zookeeper.ZookeeperConsumer
import scala.Some
import scala.concurrent.Future
import scala.collection.JavaConversions._
import com.stratio.streaming.commons.exceptions.{StratioEngineOperationException, StratioAPIGenericException}
import java.util.concurrent.TimeoutException

class StreamingAPIListOperationTests extends FunSpec
with GivenWhenThen
with ShouldMatchers
with MockitoSugar {
  val kafkaProducerMock = mock[KafkaProducer]
  val zookeeperConsumerMock = mock[ZookeeperConsumer]
  val stratioStreamingAPIListOperation = new StreamingAPIListOperation(kafkaProducerMock, zookeeperConsumerMock, 2000)
  val stratioStreamingMessage = new StratioStreamingMessage(
    "theOperation",
    "theStreamName",
    "sessionId",
    "requestId",
    "theRequest",
    123456,
    Seq(),
    Seq(),
    true)

  describe("The Streaming API Sync Operation") {
    it("should throw no exceptions when the engine returns a proper list") {
      Given("a proper streams list")
      val streamsList = """{"count":1,"timestamp":1402494388420,"streams":[{"streamName":"unitTestsStream","columns":[{"column":"column1","type":"STRING"}],"queries":[],"activeActions":[],"userDefined":true}]}"""
      When("we perform the list operation")
      Mockito.doNothing().when(kafkaProducerMock).send(anyString(), anyString())
      org.mockito.Mockito.when(zookeeperConsumerMock.zNodeExists(anyString())).thenReturn(true)
      org.mockito.Mockito.when(zookeeperConsumerMock.readZNode(anyString())).thenReturn(Future.successful())
      org.mockito.Mockito.when(zookeeperConsumerMock.getZNodeData(anyString())).thenReturn(Some(streamsList))
      Then("we should not get a StratioAPISecurityException")
      try {
        stratioStreamingAPIListOperation.getListStreams(stratioStreamingMessage)
      } catch {
        case _ => fail()
      }
    }

    it("should throw a StratioAPIGenericException exception when the engine returns a wrong list") {
      Given("a wrong streams list")
      val streamsList = """{"count":1,"blahblah":1402494388420}"""
      When("we perform the list operation")
      Mockito.doNothing().when(kafkaProducerMock).send(anyString(), anyString())
      org.mockito.Mockito.when(zookeeperConsumerMock.zNodeExists(anyString())).thenReturn(true)
      org.mockito.Mockito.when(zookeeperConsumerMock.readZNode(anyString())).thenReturn(Future.successful())
      org.mockito.Mockito.when(zookeeperConsumerMock.getZNodeData(anyString())).thenReturn(Some(streamsList))
      Then("we should throw a StratioAPIGenericException")
      intercept[StratioAPIGenericException] {
        stratioStreamingAPIListOperation.getListStreams(stratioStreamingMessage)
      }
    }

    it("should throw a StratioEngineOperationException when the ack time-out expired") {
      Given("a time-out exception")
      When("we perform the sync operation")
      Mockito.doNothing().when(kafkaProducerMock).send(anyString(), anyString())
      org.mockito.Mockito.when(zookeeperConsumerMock.zNodeExists(anyString())).thenReturn(true)
      org.mockito.Mockito.when(zookeeperConsumerMock.readZNode(anyString())).thenReturn(Future.failed(new TimeoutException()))
      Then("we should get a StratioEngineOperationException")
      intercept[StratioEngineOperationException] {
        stratioStreamingAPIListOperation.getListStreams(stratioStreamingMessage)
      }
    }
  }
}
