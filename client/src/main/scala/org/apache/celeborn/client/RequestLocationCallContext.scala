/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.celeborn.client

import java.util.concurrent.ConcurrentHashMap

import org.apache.celeborn.common.protocol.PartitionLocation
import org.apache.celeborn.common.protocol.message.ControlMessages.{ChangeLocationResponse, ChangeLocationsResponse, RegisterShuffleResponse}
import org.apache.celeborn.common.protocol.message.StatusCode
import org.apache.celeborn.common.rpc.RpcCallContext

trait RequestLocationCallContext {
  def reply(id: Int, status: StatusCode, partitionLocationOpt: Option[PartitionLocation]): Unit
}

case class ChangeLocationCallContext(context: RpcCallContext) extends RequestLocationCallContext {
  override def reply(
      id: Int,
      status: StatusCode,
      partitionLocationOpt: Option[PartitionLocation]): Unit = {
    context.reply(ChangeLocationResponse(status, partitionLocationOpt))
  }
}

case class ChangeLocationsCallContext(context: RpcCallContext, count: Int)
  extends RequestLocationCallContext {
  val locMap = new ConcurrentHashMap[Int, (StatusCode, PartitionLocation)](count)
  override def reply(
      id: Int,
      status: StatusCode,
      partitionLocationOpt: Option[PartitionLocation]): Unit = {
    locMap.putIfAbsent(id, (status, partitionLocationOpt.getOrElse(new PartitionLocation())))
    if (locMap.size() == count) {
      locMap.synchronized {
        if (locMap.size() == count || id == -1) {
          context.reply(ChangeLocationsResponse(locMap))
          locMap.clear()
        }
      }
    }
  }
}

case class ApplyNewLocationCallContext(context: RpcCallContext) extends RequestLocationCallContext {
  override def reply(
      id: Int,
      status: StatusCode,
      partitionLocationOpt: Option[PartitionLocation]): Unit = {
    partitionLocationOpt match {
      case Some(partitionLocation) =>
        context.reply(RegisterShuffleResponse(status, Array(partitionLocation)))
      case None => context.reply(RegisterShuffleResponse(status, Array.empty))
    }
  }
}
