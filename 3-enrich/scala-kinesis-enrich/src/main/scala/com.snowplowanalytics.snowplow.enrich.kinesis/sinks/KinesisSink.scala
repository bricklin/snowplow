 /*
 * Copyright (c) 2013-2014 Snowplow Analytics Ltd.
 * All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache
 * License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at
 * http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.
 *
 * See the Apache License Version 2.0 for the specific language
 * governing permissions and limitations there under.
 */

package com.snowplowanalytics.snowplow.enrich
package kinesis.sinks

// Snowplow
import com.snowplowanalytics.snowplow.collectors.thrift._
import common.outputs.CanonicalOutput

// Java
import java.nio.ByteBuffer

// Amazon
import com.amazonaws.AmazonServiceException
import com.amazonaws.auth.{
  AWSCredentialsProvider
}

// Scalazon (for Kinesis interaction)
import io.github.cloudify.scala.aws.kinesis.Client
import io.github.cloudify.scala.aws.kinesis.Client.ImplicitExecution._
import io.github.cloudify.scala.aws.kinesis.Definitions.{
  Stream,
  PutResult,
  Record
}
import io.github.cloudify.scala.aws.kinesis.KinesisDsl._

// Config
import com.typesafe.config.Config

// Concurrent libraries.
import scala.concurrent.{Future,Await,TimeoutException}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

// Thrift.
import org.apache.thrift.TSerializer
import org.apache.thrift.TDeserializer

// Logging.
import org.slf4j.LoggerFactory

// Mutable data structures.
import scala.collection.mutable.StringBuilder
import scala.collection.mutable.MutableList

/**
 * Kinesis Sink for Scala enrichment.
 */
class KinesisSink(provider: AWSCredentialsProvider) extends ISink {
  lazy val log = LoggerFactory.getLogger(getClass())
  import log.{error, debug, info, trace}

  // Initialize
  private implicit val kinesis = Client.fromCredentials(provider)
  private var stream: Option[Stream] = None
  private val thriftSerializer = new TSerializer()
  private val thriftDeserializer = new TDeserializer()

  def storeCanonicalOutput(tabDelimCanonicalOutput: String, key: String) = {
    storeEnrichedEvent(tabDelimCanonicalOutput.getBytes, key)
  }

  // Set the current stream to $name.
  def loadStream(name: String) {
    stream = Some(Kinesis.stream(name))
  }

  /**
   * Checks if a stream exists.
   *
   * @param name The name of the stream to check.
   * @param timeout How long to keep checking if the stream became active,
   * in seconds
   * @return true if the stream was loaded, false if the stream doesn't exist.
   */
  def streamExists(name: String, timeout: Int = 60): Boolean = {
    val streamListFuture = for {
      s <- Kinesis.streams.list
    } yield s
    val streamList: Iterable[String] =
      Await.result(streamListFuture, Duration(timeout, SECONDS))
    for (streamStr <- streamList) {
      if (streamStr == name) {
        info(s"Stream $name exists.")
        return true
      }
    }

    info(s"Stream $name doesn't exist.")
    false
  }

  /**
   * Deletes a stream.
   */
  def deleteStream(name: String, timeout: Int = 60): Unit = {
    val localStream = Kinesis.stream(name)

    info(s"Deleting stream $name.")
    val deleteStream = for {
      s <- localStream.delete
    } yield s

    Await.result(deleteStream, Duration(timeout, SECONDS))
    info("Successfully deleted stream.")
  }

  /**
   * Creates a new stream if one doesn't exist.
   * Arguments are optional.
   *
   * @param name The name of the stream to create
   * @param size The number of shards to support for this stream
   * @param timeout How long to keep checking if the stream became active,
   * in seconds
   *
   * @return a Boolean, where:
   * 1. true means the stream was successfully created or already exists
   * 2. false means an error occurred
   */
  def createAndLoadStream(name: String, size: Int,
      timeout: Int = 60): Boolean = {
    if (streamExists(name)) {
      loadStream(name)
      return true
    }

    info(s"Creating stream $name of size $size.")
    val createStream = for {
      s <- Kinesis.streams.create(name)
    } yield s

    try {
      stream = Some(Await.result(createStream, Duration(timeout, SECONDS)))
      Await.result(stream.get.waitActive.retrying(timeout),
        Duration(timeout, SECONDS))
    } catch {
      case _: TimeoutException =>
        info("Error: Timed out.")
        false
    }
    info("Successfully created stream.")
    true
  }

  def storeEnrichedEvent(rawEvent: Array[Byte], key: String): PutResult = {
    //info(s"Writing Thrift record to Kinesis: ${new String(rawEvent)}")
    val result = writeRecord(
      data = ByteBuffer.wrap(rawEvent),
      key = key
    )
    info(s"Writing successful.")
    info(s"  + ShardId: ${result.shardId}")
    info(s"  + SequenceNumber: ${result.sequenceNumber}")
    result
  }

  /**
   * Stores an event to the Kinesis stream.
   *
   * @param data The data for this record
   * @param key The partition key for this record
   * @param timeout Time in seconds to wait to put the data.
   *
   * @return A PutResult containing the ShardId and SequenceNumber
   *   of the record written to.
   */
  private def writeRecord(data: ByteBuffer, key: String,
      timeout: Int = 60): PutResult = {
    val putData = for {
      p <- stream.get.put(data, key)
    } yield p
    val putResult = Await.result(putData, Duration(timeout, SECONDS))
    putResult
  }
}
