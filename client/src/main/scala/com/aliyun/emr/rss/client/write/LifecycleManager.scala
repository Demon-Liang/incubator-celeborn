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

package com.aliyun.emr.rss.client.write

import java.util
import java.util.{List => JList}
import java.util.concurrent.{ConcurrentHashMap, ScheduledFuture, TimeUnit}

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.util.Random

import io.netty.util.internal.ConcurrentSet

import com.aliyun.emr.rss.common.RssConf
import com.aliyun.emr.rss.common.haclient.RssHARetryClient
import com.aliyun.emr.rss.common.internal.Logging
import com.aliyun.emr.rss.common.meta.{PartitionLocationInfo, WorkerInfo}
import com.aliyun.emr.rss.common.protocol.{PartitionLocation, RpcNameConstants}
import com.aliyun.emr.rss.common.protocol.RpcNameConstants.WORKER_EP
import com.aliyun.emr.rss.common.protocol.message.ControlMessages._
import com.aliyun.emr.rss.common.protocol.message.StatusCode
import com.aliyun.emr.rss.common.rpc._
import com.aliyun.emr.rss.common.util.{ThreadUtils, Utils}

class LifecycleManager(appId: String, val conf: RssConf) extends RpcEndpoint with Logging {

  private val lifecycleHost = Utils.localHostName()

  private val RemoveShuffleDelayMs = RssConf.removeShuffleDelayMs(conf)
  private val GetBlacklistDelayMs = RssConf.getBlacklistDelayMs(conf)
  private val ShouldReplicate = RssConf.replicate(conf)
  private val splitThreshold = RssConf.partitionSplitThreshold(conf)
  private val splitMode = RssConf.partitionSplitMode(conf)
  private val storageHint = RssConf.storageHint(conf)

  private val unregisterShuffleTime = new ConcurrentHashMap[Int, Long]()

  private val registeredShuffle = new ConcurrentSet[Int]()
  private val shuffleMapperAttempts = new ConcurrentHashMap[Int, Array[Int]]()
  private val reducerFileGroupsMap =
    new ConcurrentHashMap[Int, Array[Array[PartitionLocation]]]()
  private val dataLostShuffleSet = new ConcurrentSet[Int]()
  private val stageEndShuffleSet = new ConcurrentSet[Int]()
  // maintain each shuffle's map relation of WorkerInfo and partition location
  private val shuffleAllocatedWorkers =
    new ConcurrentHashMap[Int, ConcurrentHashMap[WorkerInfo, PartitionLocationInfo]]()
  private def workerSnapshots(shuffleId: Int): util.Map[WorkerInfo, PartitionLocationInfo] =
    shuffleAllocatedWorkers.get(shuffleId)

  // revive request waiting for response
  // shuffleKey -> (partitionId -> set)
  private val reviving =
    new ConcurrentHashMap[Int, ConcurrentHashMap[Integer, util.Set[RpcCallContext]]]()

  private val splitting =
    new ConcurrentHashMap[Int, ConcurrentHashMap[Integer, util.Set[RpcCallContext]]]()

  // register shuffle request waiting for response
  private val registeringShuffleRequest = new ConcurrentHashMap[Int, util.Set[RpcCallContext]]()

  // blacklist
  private val blacklist = new ConcurrentSet[WorkerInfo]()

  // Threads
  private val forwardMessageThread =
    ThreadUtils.newDaemonSingleThreadScheduledExecutor("master-forward-message-thread")
  private var checkForShuffleRemoval: ScheduledFuture[_] = _
  private var getBlacklist: ScheduledFuture[_] = _

  // Use independent app heartbeat threads to avoid being blocked by other operations.
  private val heartbeatIntervalMs = RssConf.applicationHeatbeatIntervalMs(conf)
  private val heartbeatThread = ThreadUtils.newDaemonSingleThreadScheduledExecutor("app-heartbeat")
  private var appHeartbeat: ScheduledFuture[_] = _
  private val responseCheckerThread = ThreadUtils.
    newDaemonSingleThreadScheduledExecutor("rss-master-resp-checker")

  // init driver rss meta rpc service
  override val rpcEnv: RpcEnv = RpcEnv.create(
    RpcNameConstants.RSS_METASERVICE_SYS,
    lifecycleHost,
    RssConf.driverMetaServicePort(conf),
    conf)
  rpcEnv.setupEndpoint(RpcNameConstants.RSS_METASERVICE_EP, this)

  logInfo(s"Start LifecycleManager on ${rpcEnv.address}")

  private val rssHARetryClient = new RssHARetryClient(rpcEnv, conf)

  // Since method `onStart` is executed when `rpcEnv.setupEndpoint` is executed, and
  // `rssHARetryClient` is initialized after `rpcEnv` is initialized, if method `onStart` contains
  // a reference to `rssHARetryClient`, there may be cases where `rssHARetryClient` is null when
  // `rssHARetryClient` is called. Therefore, it's necessary to uniformly execute the initialization
  // method at the end of the construction of the class to perform the initialization operations.
  private def initialize(): Unit = {
    appHeartbeat = heartbeatThread.scheduleAtFixedRate(new Runnable {
      override def run(): Unit = {
        try {
          require(rssHARetryClient != null, "When sending a heartbeat, client shouldn't be null.")
          val appHeartbeat = HeartBeatFromApplication(appId, ZERO_UUID)
          rssHARetryClient.send(appHeartbeat)
          logDebug("Successfully send app heartbeat.")
        } catch {
          case it: InterruptedException =>
            logWarning("Interrupted while sending app heartbeat.")
            Thread.currentThread().interrupt()
            throw it
          case t: Throwable =>
            logError("Error while send heartbeat", t)
        }
      }
    }, 0, heartbeatIntervalMs, TimeUnit.MILLISECONDS)
  }

  override def onStart(): Unit = {
    checkForShuffleRemoval = forwardMessageThread.scheduleAtFixedRate(new Runnable {
      override def run(): Unit = Utils.tryLogNonFatalError {
        self.send(RemoveExpiredShuffle)
      }
    }, RemoveShuffleDelayMs, RemoveShuffleDelayMs, TimeUnit.MILLISECONDS)

    getBlacklist = forwardMessageThread.scheduleAtFixedRate(new Runnable {
      override def run(): Unit = Utils.tryLogNonFatalError {
        self.send(GetBlacklist(new util.ArrayList[WorkerInfo](blacklist)))
      }
    }, GetBlacklistDelayMs, GetBlacklistDelayMs, TimeUnit.MILLISECONDS)
  }

  override def onStop(): Unit = {
    import scala.concurrent.duration._

    checkForShuffleRemoval.cancel(true)
    getBlacklist.cancel(true)
    ThreadUtils.shutdown(forwardMessageThread, 800.millis)

    appHeartbeat.cancel(true)
    ThreadUtils.shutdown(heartbeatThread, 800.millis)

    ThreadUtils.shutdown(responseCheckerThread, 800.millis)

    rssHARetryClient.close()
    if (rpcEnv != null) {
      rpcEnv.shutdown()
      rpcEnv.awaitTermination()
    }
  }

  def getRssMetaServiceHost: String = {
    lifecycleHost
  }

  def getRssMetaServicePort: Int = {
    rpcEnv.address.port
  }

  override def receive: PartialFunction[Any, Unit] = {
    case RemoveExpiredShuffle =>
      removeExpiredShuffle()
    case msg: GetBlacklist =>
      handleGetBlacklist(msg)
    case StageEnd(applicationId, shuffleId) =>
      logDebug(s"Received StageEnd request, ${Utils.makeShuffleKey(applicationId, shuffleId)}.")
      handleStageEnd(null, applicationId, shuffleId)
    case UnregisterShuffle(applicationId, shuffleId, _) =>
      logDebug(s"Received UnregisterShuffle request," +
        s"${Utils.makeShuffleKey(applicationId, shuffleId)}.")
      handleUnregisterShuffle(null, applicationId, shuffleId)
  }

  override def receiveAndReply(context: RpcCallContext): PartialFunction[Any, Unit] = {
    case RegisterShuffle(applicationId, shuffleId, numMappers, numPartitions) =>
      logDebug(s"Received RegisterShuffle request, " +
        s"$applicationId, $shuffleId, $numMappers, $numPartitions.")
      handleRegisterShuffle(context, applicationId, shuffleId, numMappers,
        numPartitions)

    case Revive(applicationId, shuffleId, mapId, attemptId, reduceId, epoch, oldPartition, cause) =>
      logTrace(s"Received Revive request, " +
        s"$applicationId, $shuffleId, $mapId, $attemptId, ,$reduceId," +
        s" $epoch, $oldPartition, $cause.")
      handleRevive(context, applicationId, shuffleId, mapId, attemptId,
        reduceId, epoch, oldPartition, cause)

    case PartitionSplit(applicationId, shuffleId, reduceId, epoch, oldPartition) =>
      logTrace(s"Received split request, " +
        s"$applicationId, $shuffleId, $reduceId, $epoch, $oldPartition")
      handlePartitionSplitRequest(context, applicationId, shuffleId, reduceId, epoch, oldPartition)

    case MapperEnd(applicationId, shuffleId, mapId, attemptId, numMappers) =>
      logTrace(s"Received MapperEnd request, " +
        s"${Utils.makeMapKey(applicationId, shuffleId, mapId, attemptId)}.")
      handleMapperEnd(context, applicationId, shuffleId, mapId, attemptId, numMappers)

    case GetReducerFileGroup(applicationId: String, shuffleId: Int) =>
      logDebug(s"Received GetShuffleFileGroup request," +
        s"${Utils.makeShuffleKey(applicationId, shuffleId)}.")
      handleGetReducerFileGroup(context, shuffleId)

    case StageEnd(applicationId, shuffleId) =>
      logDebug(s"Received StageEnd request, ${Utils.makeShuffleKey(applicationId, shuffleId)}.")
      handleStageEnd(context, applicationId, shuffleId)
  }

  /* ========================================================== *
   |        START OF EVENT HANDLER                              |
   * ========================================================== */

  private def handleRegisterShuffle(
      context: RpcCallContext,
      applicationId: String,
      shuffleId: Int,
      numMappers: Int,
      numPartitions: Int): Unit = {
    registeringShuffleRequest.synchronized {
      if (registeringShuffleRequest.containsKey(shuffleId)) {
        // If same request already exists in the registering request list for the same shuffle,
        // just register and return.
        logDebug("[handleRegisterShuffle] request for same shuffleKey exists, just register")
        registeringShuffleRequest.get(shuffleId).add(context)
        return
      } else {
        // If shuffle is registered, reply this shuffle's partition location and return.
        // Else add this request to registeringShuffleRequest.
        if (registeredShuffle.contains(shuffleId)) {
          val initialLocs = workerSnapshots(shuffleId)
            .values()
            .asScala
            .flatMap(_.getAllMasterLocationsWithMinEpoch(shuffleId.toString).asScala)
            .filter(_.getEpoch == 0)
            .toList
            .asJava
          if (initialLocs.size != numPartitions) {
            logWarning(s"Shuffle $shuffleId location size ${initialLocs.size} not equal to " +
              s"numPartitions: $numPartitions!")
          }
          context.reply(RegisterShuffleResponse(StatusCode.Success, initialLocs))
          return
        }
        logInfo(s"New shuffle request, shuffleId $shuffleId, numPartitions: $numPartitions.")
        val set = new util.HashSet[RpcCallContext]()
        set.add(context)
        registeringShuffleRequest.put(shuffleId, set)
      }
    }

    // Reply to all RegisterShuffle request for current shuffle id.
    def reply(response: RegisterShuffleResponse): Unit = {
      registeringShuffleRequest.synchronized {
        registeringShuffleRequest.asScala
          .get(shuffleId)
          .foreach(_.asScala.foreach(_.reply(response)))
        registeringShuffleRequest.remove(shuffleId)
      }
    }

    // First, request to get allocated slots from Master
    val reduceIdList = new util.ArrayList[Integer]
    (0 until numPartitions).foreach(idx => reduceIdList.add(new Integer(idx)))
    val res = requestSlotsWithRetry(applicationId, shuffleId, reduceIdList)

    res.status match {
      case StatusCode.Failed =>
        logError(s"OfferSlots RPC request failed for $shuffleId!")
        reply(RegisterShuffleResponse(StatusCode.Failed, List.empty.asJava))
        return
      case StatusCode.SlotNotAvailable =>
        logError(s"OfferSlots for $shuffleId failed!")
        reply(RegisterShuffleResponse(StatusCode.SlotNotAvailable, List.empty.asJava))
        return
      case StatusCode.Success =>
        logInfo(s"OfferSlots for ${Utils.makeShuffleKey(applicationId, shuffleId)} Success!")
        logDebug(s" Slots Info: ${res.workerResource}")
      case _ => // won't happen
        throw new UnsupportedOperationException()
    }

    // Reserve slots for each PartitionLocation. When response status is SUCCESS, WorkerResource
    // won't be empty since master will reply SlotNotAvailable status when reserved slots is empty.
    val slots = res.workerResource
    val candidatesWorkers = new util.HashSet(slots.keySet())
    val connectFailedWorkers = new util.ArrayList[WorkerInfo]()

    // Second, for each worker, try to initialize the endpoint.
    slots.asScala.foreach { case (workerInfo, _) =>
      try {
        workerInfo.endpoint =
          rpcEnv.setupEndpointRef(RpcAddress.apply(workerInfo.host, workerInfo.rpcPort), WORKER_EP)
      } catch {
        case t: Throwable =>
          logError(s"Init rpc client for $workerInfo failed", t)
          connectFailedWorkers.add(workerInfo)
      }
    }

    candidatesWorkers.removeAll(connectFailedWorkers)
    recordWorkerFailure(connectFailedWorkers)

    // Third, for each slot, LifecycleManager should ask Worker to reserve the slot
    // and prepare the pushing data env.
    val reserveSlotsSuccess =
      reserveSlotsWithRetry(applicationId, shuffleId, candidatesWorkers.asScala.toList, slots)

    // If reserve slots failed, clear allocated resources, reply ReserveSlotFailed and return.
    if (!reserveSlotsSuccess) {
      logError(s"reserve buffer for $shuffleId failed, reply to all.")
      reply(RegisterShuffleResponse(StatusCode.ReserveSlotFailed, List.empty.asJava))
      // tell Master to release slots
      requestReleaseSlots(rssHARetryClient,
        ReleaseSlots(applicationId, shuffleId, List.empty.asJava, List.empty.asJava))
    } else {
      logInfo(s"ReserveSlots for ${Utils.makeShuffleKey(applicationId, shuffleId)} success!")
      logDebug(s"Allocated Slots: $slots")
      // Forth, register shuffle success, update status
      val allocatedWorkers = new ConcurrentHashMap[WorkerInfo, PartitionLocationInfo]()
      slots.asScala.foreach { case (workerInfo, (masterLocations, slaveLocations)) =>
        val partitionLocationInfo = new PartitionLocationInfo()
        partitionLocationInfo.addMasterPartitions(shuffleId.toString, masterLocations)
        partitionLocationInfo.addSlavePartitions(shuffleId.toString, slaveLocations)
        allocatedWorkers.put(workerInfo, partitionLocationInfo)
      }
      shuffleAllocatedWorkers.put(shuffleId, allocatedWorkers)
      registeredShuffle.add(shuffleId)


      shuffleMapperAttempts.synchronized {
        if (!shuffleMapperAttempts.containsKey(shuffleId)) {
          val attempts = new Array[Int](numMappers)
          0 until numMappers foreach (idx => attempts(idx) = -1)
          shuffleMapperAttempts.synchronized {
            shuffleMapperAttempts.put(shuffleId, attempts)
          }
        }
      }

      reducerFileGroupsMap.put(shuffleId, new Array[Array[PartitionLocation]](numPartitions))

      // Fifth, reply the allocated partition location to ShuffleClient.
      logInfo(s"Handle RegisterShuffle Success for $shuffleId.")
      val allMasterPartitionLocations = slots.asScala.flatMap(_._2._1.asScala).toList
      reply(RegisterShuffleResponse(StatusCode.Success, allMasterPartitionLocations.asJava))
    }
  }

  private def blacklistPartition(oldPartition: PartitionLocation, cause: StatusCode): Unit = {
    // only blacklist if cause is PushDataFailMain
    val failedWorker = new util.ArrayList[WorkerInfo]()
    if (cause == StatusCode.PushDataFailMain && oldPartition != null) {
      failedWorker.add(oldPartition.getWorker)
    }
    if (!failedWorker.isEmpty) {
      recordWorkerFailure(failedWorker)
    }
  }

  private def handleRevive(
      context: RpcCallContext,
      applicationId: String,
      shuffleId: Int,
      mapId: Int,
      attemptId: Int,
      reduceId: Int,
      oldEpoch: Int,
      oldPartition: PartitionLocation,
      cause: StatusCode): Unit = {
    // If shuffle not registered, reply ShuffleNotRegistered and return
    if (!registeredShuffle.contains(shuffleId)) {
      logError(s"[handleRevive] shuffle $shuffleId not registered!")
      context.reply(ChangeLocationResponse(StatusCode.ShuffleNotRegistered, null))
      return
    }

    // If shuffle registered and corresponding map finished, reply MapEnd and return.
    if (shuffleMapperAttempts.containsKey(shuffleId)
      && shuffleMapperAttempts.get(shuffleId)(mapId) != -1) {
      logWarning(s"[handleRevive] Mapper ended, mapId $mapId, current attemptId $attemptId, " +
        s"ended attemptId ${shuffleMapperAttempts.get(shuffleId)(mapId)}, shuffleId $shuffleId.")
      context.reply(ChangeLocationResponse(StatusCode.MapEnded, null))
      return
    }

    // check if there exists request for the partition, if do just register
    val shuffleReviving = reviving.computeIfAbsent(shuffleId, rpcContextRegisterFunc)
    shuffleReviving.synchronized {
      if (shuffleReviving.containsKey(reduceId)) {
        shuffleReviving.get(reduceId).add(context)
        logTrace(s"For $shuffleId, same partition $reduceId-$oldEpoch is reviving," +
          s"register context.")
        return
      } else {
        // If new slot for the partition has been allocated, reply and return.
        // Else register and allocate for it.
        val latestLoc = getLatestPartition(shuffleId, reduceId, oldEpoch)
        if (latestLoc != null) {
          context.reply(ChangeLocationResponse(StatusCode.Success, latestLoc))
          logDebug(s"New partition found, old partition $reduceId-$oldEpoch return it." +
            s" shuffleId: $shuffleId $latestLoc")
          return
        }
        val set = new util.HashSet[RpcCallContext]()
        set.add(context)
        shuffleReviving.put(reduceId, set)
      }
    }

    logWarning(s"Do Revive for shuffle ${
      Utils.makeShuffleKey(applicationId, shuffleId)}, oldPartition: $oldPartition, cause: $cause")
    blacklistPartition(oldPartition, cause)
    handleChangePartitionLocation(shuffleReviving, applicationId, shuffleId, reduceId, oldEpoch,
      oldPartition)
  }

  private val rpcContextRegisterFunc =
    new util.function.Function[Int, ConcurrentHashMap[Integer, util.Set[RpcCallContext]]]() {
      override def apply(s: Int): ConcurrentHashMap[Integer, util.Set[RpcCallContext]] =
        new ConcurrentHashMap()
    }

  private def handleChangePartitionLocation(
      contexts: ConcurrentHashMap[Integer, util.Set[RpcCallContext]],
      applicationId: String,
      shuffleId: Int,
      reduceId: Int,
      oldEpochId: Int,
      oldPartition: PartitionLocation): Unit = {

    def reply(response: ChangeLocationResponse): Unit = {
      contexts.synchronized {
        contexts.remove(reduceId)
      }.asScala.foreach(_.reply(response))
    }

    val candidates = workersNotBlacklisted(shuffleId)
    if (candidates.size < 1 || (ShouldReplicate && candidates.size < 2)) {
      logError("[Update partition] failed for not enough candidates for revive.")
      reply(ChangeLocationResponse(StatusCode.SlotNotAvailable, null))
      return null
    }

    val newlyAllocatedLocation = if (oldPartition != null) {
      reallocateSlotsFromCandidates(List(oldPartition), candidates)
    } else {
      reallocateForNonExistPartitionLocationFromCandidates(reduceId, oldEpochId, candidates)
    }

    if (!reserveSlotsWithRetry(applicationId, shuffleId, candidates, newlyAllocatedLocation)) {
      logError(s"[Update partition] failed for $shuffleId.")
      reply(ChangeLocationResponse(StatusCode.ReserveSlotFailed, null))
      return
    }

    // Add all re-allocated slots to worker snapshots.
    newlyAllocatedLocation.asScala.foreach { case (workInfo, (masterLocations, slaveLocations)) =>
      workerSnapshots(shuffleId).asScala.get(workInfo).map { partitionLocationInfo =>
        partitionLocationInfo.addMasterPartitions(shuffleId.toString, masterLocations)
        partitionLocationInfo.addSlavePartitions(shuffleId.toString, slaveLocations)
      }
    }
    val (masterLocations, slavePartitions) = newlyAllocatedLocation.asScala.head._2
    // reply the master location of this partition.
    val revivedMasterLocation = if (masterLocations != null && masterLocations.size() > 0) {
      masterLocations.asScala.head
    } else {
      slavePartitions.asScala.head.getPeer
    }

    reply(ChangeLocationResponse(StatusCode.Success, revivedMasterLocation))
    logDebug(s"Renew $shuffleId $reduceId partition success.")
  }

  private def getLatestPartition(
      shuffleId: Int,
      reduceId: Int,
      epoch: Int): PartitionLocation = {
    val locs = workerSnapshots(shuffleId).values().asScala
      .flatMap(_.getLocationWithMaxEpoch(shuffleId.toString, reduceId))
    if (!locs.isEmpty) {
      val latestLoc = locs.maxBy(_.getEpoch)
      if (latestLoc.getEpoch > epoch) {
        return latestLoc
      }
    }
    null
  }

  private def handlePartitionSplitRequest(
      context: RpcCallContext,
      applicationId: String,
      shuffleId: Int,
      reduceId: Int,
      oldEpoch: Int,
      oldPartition: PartitionLocation): Unit = {
    val shuffleSplitting = splitting.computeIfAbsent(shuffleId, rpcContextRegisterFunc)
    shuffleSplitting.synchronized {
      if (shuffleSplitting.containsKey(reduceId)) {
        shuffleSplitting.get(reduceId).add(context)
        return
      } else {
        val latestLoc = getLatestPartition(shuffleId, reduceId, oldEpoch)
        if (latestLoc != null) {
          context.reply(ChangeLocationResponse(StatusCode.Success, latestLoc))
          return
        }
        val set = new util.HashSet[RpcCallContext]()
        set.add(context)
        shuffleSplitting.put(reduceId, set)
      }
    }

    logDebug(s"Relocate partition for shuffle split ${Utils.makeShuffleKey(applicationId,
      shuffleId)}, oldPartition: $oldPartition")

    handleChangePartitionLocation(shuffleSplitting, applicationId, shuffleId, reduceId, oldEpoch,
      oldPartition)
  }

  private def handleMapperEnd(
      context: RpcCallContext,
      applicationId: String,
      shuffleId: Int,
      mapId: Int,
      attemptId: Int,
      numMappers: Int): Unit = {
    var askStageEnd: Boolean = false
    // update max attemptId
    shuffleMapperAttempts.synchronized {
      var attempts = shuffleMapperAttempts.get(shuffleId)
      // it would happen when task with no shuffle data called MapperEnd first
      if (attempts == null) {
        logDebug(s"[handleMapperEnd] $shuffleId not registered, create one.")
        attempts = new Array[Int](numMappers)
        0 until numMappers foreach (idx => attempts(idx) = -1)
        shuffleMapperAttempts.put(shuffleId, attempts)
      }

      if (attempts(mapId) < 0) {
        attempts(mapId) = attemptId
      } else {
        // Mapper with another attemptId called, skip this request
        context.reply(MapperEndResponse(StatusCode.Success))
        return
      }

      if (!attempts.exists(_ < 0)) {
        askStageEnd = true
      }
    }

    if (askStageEnd) {
      // last mapper finished. call mapper end
      logInfo(s"Last MapperEnd, call StageEnd with shuffleKey:" +
        s"${Utils.makeShuffleKey(applicationId, shuffleId)}.")
      self.send(StageEnd(applicationId, shuffleId))
    }

    // reply success
    context.reply(MapperEndResponse(StatusCode.Success))
  }

  private def handleGetReducerFileGroup(
      context: RpcCallContext,
      shuffleId: Int): Unit = {
    logDebug(s"Wait for StageEnd, $shuffleId.")
    var timeout = RssConf.stageEndTimeout(conf)
    val delta = 50
    while (!stageEndShuffleSet.contains(shuffleId)) {
      Thread.sleep(50)
      if (timeout <= 0) {
        logError(s"StageEnd Timeout! $shuffleId.")
        context.reply(
          GetReducerFileGroupResponse(StatusCode.StageEndTimeOut, Array.empty, Array.empty))
        return
      }
      timeout = timeout - delta
    }

    if (dataLostShuffleSet.contains(shuffleId)) {
      context.reply(
        GetReducerFileGroupResponse(StatusCode.ShuffleDataLost, Array.empty, Array.empty))
    } else {
      context.reply(GetReducerFileGroupResponse(
        StatusCode.Success,
        reducerFileGroupsMap.getOrDefault(shuffleId, Array.empty),
        shuffleMapperAttempts.getOrDefault(shuffleId, Array.empty)
      ))
    }
  }

  private def handleStageEnd(
      context: RpcCallContext,
      applicationId: String,
      shuffleId: Int): Unit = {
    // check whether shuffle has registered
    if (!registeredShuffle.contains(shuffleId)) {
      logInfo(s"[handleStageEnd]" +
        s"$shuffleId not registered, maybe no shuffle data within this stage.")
      // record in stageEndShuffleSet
      stageEndShuffleSet.add(shuffleId)
      if (context != null) {
        context.reply(StageEndResponse(StatusCode.ShuffleNotRegistered))
      }
      return
    }

    // ask allLocations workers holding partitions to commit files
    val masterPartMap = new ConcurrentHashMap[String, PartitionLocation]
    val slavePartMap = new ConcurrentHashMap[String, PartitionLocation]
    val committedMasterIds = new ConcurrentSet[String]
    val committedSlaveIds = new ConcurrentSet[String]
    val failedMasterIds = new ConcurrentSet[String]
    val failedSlaveIds = new ConcurrentSet[String]

    val allocatedWorkers = shuffleAllocatedWorkers.get(shuffleId)
    val commitFilesFailedWorkers = new ConcurrentSet[WorkerInfo]

    val parallelism = Math.min(workerSnapshots(shuffleId).size(),
      RssConf.rpcMaxParallelism(conf))
    ThreadUtils.parmap(
      allocatedWorkers.asScala.to, "CommitFiles", parallelism) { w2p =>
      val worker = w2p._1
      val partitionLocationInfo = w2p._2
      if (partitionLocationInfo.containsShuffle(shuffleId.toString)) {
        val masterParts = partitionLocationInfo.getAllMasterLocations(shuffleId.toString)
        val slaveParts = partitionLocationInfo.getAllSlaveLocations(shuffleId.toString)
        masterParts.asScala.foreach { p =>
          val partition = new PartitionLocation(p)
          partition.setFetchPort(worker.fetchPort)
          partition.setPeer(null)
          masterPartMap.put(partition.getUniqueId, partition)
        }
        slaveParts.asScala.foreach { p =>
          val partition = new PartitionLocation(p)
          partition.setFetchPort(worker.fetchPort)
          partition.setPeer(null)
          slavePartMap.put(partition.getUniqueId, partition)
        }

        val masterIds = masterParts.asScala.map(_.getUniqueId).asJava
        val slaveIds = slaveParts.asScala.map(_.getUniqueId).asJava

        val commitFiles = CommitFiles(applicationId, shuffleId, masterIds,
          slaveIds, shuffleMapperAttempts.get(shuffleId))
        val res = requestCommitFiles(worker.endpoint, commitFiles)

        res.status match {
          case StatusCode.Success => // do nothing
          case StatusCode.PartialSuccess | StatusCode.ShuffleNotRegistered | StatusCode.Failed =>
            logDebug(s"Request $commitFiles return ${res.status} for " +
              s"${Utils.makeShuffleKey(applicationId, shuffleId)}")
            commitFilesFailedWorkers.add(worker)
          case _ => // won't happen
        }

        // record committed partitionIds
        committedMasterIds.addAll(res.committedMasterIds)
        committedSlaveIds.addAll(res.committedSlaveIds)

        // record failed partitions
        failedMasterIds.addAll(res.failedMasterIds)
        failedSlaveIds.addAll(res.failedSlaveIds)
      }
    }

    recordWorkerFailure(new util.ArrayList[WorkerInfo](commitFilesFailedWorkers))
    // release resources and clear worker info
    workerSnapshots(shuffleId).asScala.foreach { case (_, partitionLocationInfo) =>
      partitionLocationInfo.removeMasterPartitions(shuffleId.toString)
      partitionLocationInfo.removeSlavePartitions(shuffleId.toString)
    }
    requestReleaseSlots(rssHARetryClient,
      ReleaseSlots(applicationId, shuffleId, List.empty.asJava, List.empty.asJava))

    def hasCommonFailedIds(): Boolean = {
      if (!ShouldReplicate && failedMasterIds.size() != 0) {
        return true
      }
      failedMasterIds.asScala.foreach { id =>
        if (failedSlaveIds.contains(id)) {
          logError(s"For $shuffleId partition $id: data lost.")
          return true
        }
      }
      false
    }

    val dataLost = hasCommonFailedIds()

    if (!dataLost) {
      val committedPartitions = new util.HashMap[String, PartitionLocation]
      committedMasterIds.asScala.foreach { id =>
        committedPartitions.put(id, masterPartMap.get(id))
      }
      committedSlaveIds.asScala.foreach { id =>
        val slavePartition = slavePartMap.get(id)
        val masterPartition = committedPartitions.get(id)
        if (masterPartition ne null) {
          masterPartition.setPeer(slavePartition)
          slavePartition.setPeer(masterPartition)
        } else {
          logWarning(s"Shuffle $shuffleId partition $id: master lost, " +
            s"use slave $slavePartition.")
          committedPartitions.put(id, slavePartition)
        }
      }

      val fileGroups = reducerFileGroupsMap.get(shuffleId)
      val sets = Array.fill(fileGroups.length)(new util.HashSet[PartitionLocation]())
      committedPartitions.values().asScala.foreach { partition =>
        sets(partition.getReduceId).add(partition)
      }
      var i = 0
      while (i < fileGroups.length) {
        fileGroups(i) = sets(i).toArray(new Array[PartitionLocation](0))
        i += 1
      }
    }

    // reply
    if (!dataLost) {
      logInfo(s"Succeed to handle stageEnd for $shuffleId.")
      // record in stageEndShuffleSet
      stageEndShuffleSet.add(shuffleId)
      if (context != null) {
        context.reply(StageEndResponse(StatusCode.Success))
      }
    } else {
      logError(s"Failed to handle stageEnd for $shuffleId, lost file!")
      dataLostShuffleSet.add(shuffleId)
      // record in stageEndShuffleSet
      stageEndShuffleSet.add(shuffleId)
      if (context != null) {
        context.reply(StageEndResponse(StatusCode.PartialSuccess))
      }
    }
  }

  private def handleUnregisterShuffle(
      context: RpcCallContext,
      appId: String,
      shuffleId: Int): Unit = {
    // if StageEnd has not been handled, trigger StageEnd
    if (!stageEndShuffleSet.contains(shuffleId)) {
      logInfo(s"Call StageEnd before Unregister Shuffle $shuffleId.")
      handleStageEnd(null, appId, shuffleId)
    }

    if (partitionExists(shuffleId)) {
      logWarning(s"Partition exists for shuffle $shuffleId, " +
        "maybe caused by task rerun or speculative.")
      workerSnapshots(shuffleId).asScala.foreach { case (_, partitionLocationInfo) =>
        partitionLocationInfo.removeMasterPartitions(shuffleId.toString)
        partitionLocationInfo.removeSlavePartitions(shuffleId.toString)
      }
      requestReleaseSlots(rssHARetryClient,
        ReleaseSlots(appId, shuffleId, List.empty.asJava, List.empty.asJava))
    }

    // add shuffleKey to delay shuffle removal set
    unregisterShuffleTime.put(shuffleId, System.currentTimeMillis())

    logInfo(s"Unregister for $shuffleId success.")
    if (context != null) {
      context.reply(UnregisterShuffleResponse(StatusCode.Success))
    }
  }

  /* ========================================================== *
   |        END OF EVENT HANDLER                                |
   * ========================================================== */

  /**
   * After getting WorkerResource, LifecycleManger needs to ask each Worker to
   * reserve corresponding slot and prepare push data env in Worker side.
   *
   * @param applicationId Application ID
   * @param shuffleId Application shuffle id
   * @param slots WorkerResource to reserve slots
   * @return List of reserving slot failed workers
   */
  private def reserveSlots(
      applicationId: String,
      shuffleId: Int,
      slots: WorkerResource): util.List[WorkerInfo] = {
    val reserveSlotFailedWorkers = new util.ArrayList[WorkerInfo]()

    slots.asScala.foreach { case (workerInfo, (masterLocations, slaveLocations)) =>
      if (blacklist.contains(workerInfo)) {
        logWarning(s"[reserve buffer] failed due to blacklist: $workerInfo")
        reserveSlotFailedWorkers.add(workerInfo)
      } else {
        val res = requestReserveSlots(workerInfo.endpoint,
          ReserveSlots(applicationId, shuffleId, masterLocations, slaveLocations, splitThreshold,
            splitMode, storageHint))
        if (res.status.equals(StatusCode.Success)) {
          logDebug(s"Successfully allocated " +
            s"partitions buffer for ${Utils.makeShuffleKey(applicationId, shuffleId)}" +
            s" from worker ${workerInfo.readableAddress}.")
        } else {
          logError(s"[reserveSlots] Failed to" +
            s" reserve buffers for ${Utils.makeShuffleKey(applicationId, shuffleId)}" +
            s" from worker ${workerInfo.readableAddress}. Reason: ${res.reason}")
          reserveSlotFailedWorkers.add(workerInfo)
        }
      }
    }

    recordWorkerFailure(reserveSlotFailedWorkers)
    reserveSlotFailedWorkers
  }

  /**
   * When enabling replicate, if one of the partition location reserve slots failed,
   * LifecycleManager also needs to release another corresponding partition location.
   * To release the corresponding partition location, LifecycleManager should:
   *   1. Remove the peer partition location of failed partition location from slots.
   *   2. Request the Worker to destroy the slot's FileWriter.
   *   3. Request the Master to release the worker slots status.
   *
   * @param applicationId application id
   * @param shuffleId shuffle id
   * @param slots allocated WorkerResource
   * @param failedPartitionLocations reserve slot failed partition location
   */
  private def releasePeerPartitionLocation(
      applicationId: String,
      shuffleId: Int,
      slots: WorkerResource,
      failedPartitionLocations: mutable.HashMap[Int, PartitionLocation]) = {
    val destroyResource = new WorkerResource
    failedPartitionLocations.values
      .flatMap { partition => Option(partition.getPeer) }
      .foreach { partition =>
        var destroyWorkerInfo = partition.getWorker
        val workerInfoWithRpcRef = slots.keySet().asScala.find(_.equals(destroyWorkerInfo))
          .getOrElse {
            logWarning(s"Cannot find workInfo from previous success workResource:" +
              s" ${destroyWorkerInfo.readableAddress()}, init according to partition info")
            try {
              destroyWorkerInfo.endpoint = rpcEnv.setupEndpointRef(
                RpcAddress.apply(destroyWorkerInfo.host, destroyWorkerInfo.rpcPort),
                WORKER_EP)
            } catch {
              case t: Throwable =>
                logError(s"Init rpc client failed for ${destroyWorkerInfo.readableAddress}", t)
                destroyWorkerInfo = null
            }
            destroyWorkerInfo
          }
        if (slots.containsKey(workerInfoWithRpcRef)) {
          val (masterPartitionLocations, slavePartitionLocations) = slots.get(workerInfoWithRpcRef)
          partition.getMode match {
            case PartitionLocation.Mode.Master =>
              masterPartitionLocations.remove(partition)
              destroyResource.computeIfAbsent(workerInfoWithRpcRef, newLocationFunc)
                ._1.add(partition)
            case PartitionLocation.Mode.Slave =>
              slavePartitionLocations.remove(partition)
              destroyResource.computeIfAbsent(workerInfoWithRpcRef, newLocationFunc)
                ._2.add(partition)
          }
          if (masterPartitionLocations.isEmpty && slavePartitionLocations.isEmpty) {
            slots.remove(workerInfoWithRpcRef)
          }
        }
      }
    if (!destroyResource.isEmpty) {
      destroySlotsWithRetry(applicationId, shuffleId, destroyResource)
      logInfo(s"Destroyed peer partitions for reserve buffer failed workers " +
        s"${Utils.makeShuffleKey(applicationId, shuffleId)}, $destroyResource")

      val workerIds = new util.ArrayList[String]()
      val workerAllocatedSlotsSizes = new util.ArrayList[Integer]()
      Utils.workerToAllocatedSlotsSize(destroyResource).asScala
        .foreach { case (workerInfo, size) =>
        workerIds.add(workerInfo.toUniqueId())
        workerAllocatedSlotsSizes.add(size)
      }
      val msg = ReleaseSlots(applicationId, shuffleId, workerIds, workerAllocatedSlotsSizes)
      requestReleaseSlots(rssHARetryClient, msg)
      logInfo(s"Released slots for reserve buffer failed workers " +
        s"${workerIds.asScala.mkString(",")}" + s"${slots.asScala.mkString(",")}" +
        s"${Utils.makeShuffleKey(applicationId, shuffleId)}, ")
    }
  }

  /**
   * Collect all allocated partition locations on reserving slot failed workers.
   * For each reduce id, we only need to maintain one of the pair locations
   * even if enabling replicate. If RSS want to release the failed partition location,
   * the corresponding peers will be handled in [[releasePeerPartitionLocation]]
   *
   * @param reserveFailedWorkers reserve slot failed WorkerInfo list of slots
   * @param slots the slots tried to reserve a slot
   * @return
   */
  def getFailedPartitionLocations(
      reserveFailedWorkers: util.List[WorkerInfo],
      slots: WorkerResource): mutable.HashMap[Int, PartitionLocation] = {
    val failedPartitionLocations = new mutable.HashMap[Int, PartitionLocation]()
    reserveFailedWorkers.asScala.foreach { workerInfo =>
      val (failedMasterLocations, failedSlaveLocations) = slots.remove(workerInfo)
      if (null != failedMasterLocations) {
        failedMasterLocations.asScala.foreach { failedMasterLocation =>
          failedPartitionLocations += (failedMasterLocation.getReduceId -> failedMasterLocation)
        }
      }
      if (null != failedSlaveLocations) {
        failedSlaveLocations.asScala.foreach { failedSlaveLocation =>
          val reduceId = failedSlaveLocation.getReduceId
          if (!failedPartitionLocations.contains(reduceId)) {
            failedPartitionLocations += (reduceId -> failedSlaveLocation)
          }
        }
      }
    }
    failedPartitionLocations
  }

  /**
   * reserve buffer with retry, retry on another node will cause slots to be inconsistent
   */
  private def reserveSlotsWithRetry(
      applicationId: String,
      shuffleId: Int,
      candidates: List[WorkerInfo],
      slots: WorkerResource): Boolean = {
    // reserve buffers
    val reserveFailedWorkers = reserveSlots(applicationId, shuffleId, slots)

    val finalSuccess = if (!reserveFailedWorkers.isEmpty) {
      logWarning("ReserveSlots failed once, retry again")
      val failedPartitionLocations = getFailedPartitionLocations(reserveFailedWorkers, slots)

      // When enable replicate, if one of the partition location reserve slots failed,
      // we also need to release another corresponding partition location.
      if (ShouldReplicate && failedPartitionLocations.nonEmpty && !slots.isEmpty) {
        releasePeerPartitionLocation(applicationId, shuffleId, slots, failedPartitionLocations)
      }

      var retrySuccess = true
      // get retryCandidates resource and retry reserve buffer
      val retryCandidates = new util.HashSet(slots.keySet())
      // add candidates to avoid revive action passed in slots only 2 worker
      retryCandidates.addAll(candidates.asJava)
      // remove blacklist from retryCandidates
      retryCandidates.removeAll(blacklist)


      if (retryCandidates.size < 1 || (ShouldReplicate && retryCandidates.size < 2)) {
        logError("Retry reserve slots failed caused by no enough slots.")
        retrySuccess = false
      } else {
        // retry another worker if failed
        val retrySlots = reallocateSlotsFromCandidates(failedPartitionLocations.values.toList,
          retryCandidates.asScala.toList)
        val retryReserveFailedWorkers = reserveSlots(applicationId, shuffleId, retrySlots)
        retrySuccess = retryReserveFailedWorkers.isEmpty
        if (retrySuccess) {
          // Add success buffers to slots
          retrySlots.asScala
            .foreach { case (workerInfo, (retryMasterLocations, retrySlaveLocations)) =>
              val (masterPartitionLocations, slavePartitionLocations) =
                slots.computeIfAbsent(workerInfo, newLocationFunc)
              masterPartitionLocations.addAll(retryMasterLocations)
              slavePartitionLocations.addAll(retrySlaveLocations)
            }
        } else {
          // Destroy the buffer that reserve slot success.
          val retryReserveSlotsSucceedLocations =
            retrySlots.asScala.filterKeys(!retryReserveFailedWorkers.contains(_)).toMap
          destroySlotsWithRetry(applicationId, shuffleId,
            new WorkerResource(retryReserveSlotsSucceedLocations.asJava))
        }
      }

      retrySuccess
    } else {
      true
    }

    // if failed after retry, destroy all allocated buffers
    if (!finalSuccess) {
      logWarning(s"Reserve buffers $shuffleId still fail after retrying, clear buffers.")
      destroySlotsWithRetry(applicationId, shuffleId, slots)
    } else {
      logInfo(s"Reserve buffer success for ${Utils.makeShuffleKey(applicationId, shuffleId)}")
    }

    finalSuccess
  }

  private val newLocationFunc =
    new util.function.Function[WorkerInfo, (JList[PartitionLocation], JList[PartitionLocation])] {
      override def apply(w: WorkerInfo): (JList[PartitionLocation], JList[PartitionLocation]) =
        (new util.LinkedList[PartitionLocation](), new util.LinkedList[PartitionLocation]())
    }

  /**
   * Allocate a new master/slave PartitionLocation pair from the current WorkerInfo list.
   *
   * @param reduceId Shuffle partition reduce id
   * @param oldEpochId Current partition reduce location last epoch id
   * @param candidates WorkerInfo list can be used to offer worker slots
   * @param slots Current WorkerResource
   */
  private def allocateFromCandidates(
      reduceId: Int,
      oldEpochId: Int,
      candidates: List[WorkerInfo],
      slots: WorkerResource): Unit = {
    val masterIndex = Random.nextInt(candidates.size)
    val masterLocation = new PartitionLocation(
      reduceId,
      oldEpochId + 1,
      candidates(masterIndex).host,
      candidates(masterIndex).rpcPort,
      candidates(masterIndex).pushPort,
      candidates(masterIndex).fetchPort,
      candidates(masterIndex).replicatePort,
      PartitionLocation.Mode.Master)

    if (ShouldReplicate) {
      val slaveIndex = (masterIndex + 1) % candidates.size
      val slaveLocation = new PartitionLocation(
        reduceId,
        oldEpochId + 1,
        candidates(slaveIndex).host,
        candidates(slaveIndex).rpcPort,
        candidates(slaveIndex).pushPort,
        candidates(slaveIndex).fetchPort,
        candidates(slaveIndex).replicatePort,
        PartitionLocation.Mode.Slave,
        masterLocation
      )
      masterLocation.setPeer(slaveLocation)
      val masterAndSlavePairs = slots.computeIfAbsent(candidates(slaveIndex), newLocationFunc)
      masterAndSlavePairs._2.add(slaveLocation)
    }

    val masterAndSlavePairs = slots.computeIfAbsent(candidates(masterIndex), newLocationFunc)
    masterAndSlavePairs._1.add(masterLocation)
  }

  private def reallocateForNonExistPartitionLocationFromCandidates(
      reduceId: Int,
      oldEpochId: Int,
      candidates: List[WorkerInfo]): WorkerResource = {
    val slots = new WorkerResource()
    allocateFromCandidates(reduceId, oldEpochId, candidates, slots)
    slots
  }

  private def reallocateSlotsFromCandidates(
      oldPartitions: List[PartitionLocation],
      candidates: List[WorkerInfo]): WorkerResource = {
    val slots = new WorkerResource()
    oldPartitions.foreach { partition =>
      allocateFromCandidates(partition.getReduceId, partition.getEpoch, candidates, slots)
    }
    slots
  }

  /**
   * For the slots that need to be destroyed, LifecycleManager will ask the corresponding worker
   * to destroy related FileWriter.
   *
   * @param applicationId application id
   * @param shuffleId shuffle id
   * @param slotsToDestroy worker resource to be destroyed
   * @return destroy failed master and slave location unique id
   */
  private def destroySlotsWithRetry(
      applicationId: String,
      shuffleId: Int,
      slotsToDestroy: WorkerResource): Unit = {
    val shuffleKey = Utils.makeShuffleKey(applicationId, shuffleId)
    slotsToDestroy.asScala.foreach { case (workerInfo, (masterLocations, slaveLocations)) =>
      val destroy = Destroy(shuffleKey,
        masterLocations.asScala.map(_.getUniqueId).asJava,
        slaveLocations.asScala.map(_.getUniqueId).asJava)
      var res = requestDestroy(workerInfo.endpoint, destroy)
      if (res.status != StatusCode.Success) {
        logDebug(s"Request $destroy return ${res.status} for " +
          s"${Utils.makeShuffleKey(applicationId, shuffleId)}")
        res = requestDestroy(workerInfo.endpoint,
          Destroy(shuffleKey, res.failedMasters, res.failedSlaves))
      }
    }
  }

  private def removeExpiredShuffle(): Unit = {
    val currentTime = System.currentTimeMillis()
    val keys = unregisterShuffleTime.keys().asScala.toList
    keys.foreach { key =>
      if (unregisterShuffleTime.get(key) < currentTime - RemoveShuffleDelayMs) {
        logInfo(s"Clear shuffle $key.")
        // clear for the shuffle
        registeredShuffle.remove(key)
        registeringShuffleRequest.remove(key)
        reducerFileGroupsMap.remove(key)
        dataLostShuffleSet.remove(key)
        shuffleMapperAttempts.remove(key)
        stageEndShuffleSet.remove(key)
        reviving.remove(key)
        splitting.remove(key)
        unregisterShuffleTime.remove(key)
        shuffleAllocatedWorkers.remove(key)

        requestUnregisterShuffle(rssHARetryClient, UnregisterShuffle(appId, key))
      }
    }
  }

  private def handleGetBlacklist(msg: GetBlacklist): Unit = {
    val res = requestGetBlacklist(rssHARetryClient, msg)
    if (res.statusCode == StatusCode.Success) {
      logInfo(s"Received Blacklist from Master, blacklist: ${res.blacklist} " +
        s"unkown workers: ${res.unknownWorkers}")
      blacklist.clear()
      blacklist.addAll(res.blacklist)
      blacklist.addAll(res.unknownWorkers)
    }
  }

  private def requestSlotsWithRetry(
      applicationId: String,
      shuffleId: Int,
      reduceIdList: util.ArrayList[Integer]): RequestSlotsResponse = {
    val req = RequestSlots(applicationId, shuffleId, reduceIdList, lifecycleHost, ShouldReplicate)
    val res = requestRequestSlots(rssHARetryClient, req)
    if (res.status != StatusCode.Success) {
      requestRequestSlots(rssHARetryClient, req)
    } else {
      res
    }
  }

  private def requestRequestSlots(
      rssHARetryClient: RssHARetryClient,
      message: RequestSlots): RequestSlotsResponse = {
    val shuffleKey = Utils.makeShuffleKey(message.applicationId, message.shuffleId)
    try {
      rssHARetryClient.askSync[RequestSlotsResponse](message, classOf[RequestSlotsResponse])
    } catch {
      case e: Exception =>
        logError(s"AskSync RegisterShuffle for $shuffleKey failed.", e)
        RequestSlotsResponse(StatusCode.Failed, new WorkerResource())
    }
  }

  private def requestReserveSlots(
      endpoint: RpcEndpointRef,
      message: ReserveSlots): ReserveSlotsResponse = {
    val shuffleKey = Utils.makeShuffleKey(message.applicationId, message.shuffleId)
    try {
      endpoint.askSync[ReserveSlotsResponse](message)
    } catch {
      case e: Exception =>
        val msg = s"Exception when askSync ReserveSlots for $shuffleKey."
        logError(msg, e)
        ReserveSlotsResponse(StatusCode.Failed, msg + s" ${e.getMessage}")
    }
  }

  private def requestDestroy(endpoint: RpcEndpointRef, message: Destroy): DestroyResponse = {
    try {
      endpoint.askSync[DestroyResponse](message)
    } catch {
      case e: Exception =>
        logError(s"AskSync Destroy for ${message.shuffleKey} failed.", e)
        DestroyResponse(StatusCode.Failed, message.masterLocations, message.slaveLocations)
    }
  }

  private def requestCommitFiles(
      endpoint: RpcEndpointRef,
      message: CommitFiles): CommitFilesResponse = {
    try {
      endpoint.askSync[CommitFilesResponse](message)
    } catch {
      case e: Exception =>
        logError(s"AskSync CommitFiles for ${message.shuffleId} failed.", e)
        CommitFilesResponse(StatusCode.Failed, List.empty.asJava, List.empty.asJava,
          message.masterIds, message.slaveIds)
    }
  }

  private def requestReleaseSlots(
      rssHARetryClient: RssHARetryClient,
      message: ReleaseSlots): ReleaseSlotsResponse = {
    try {
      rssHARetryClient.askSync[ReleaseSlotsResponse](message, classOf[ReleaseSlotsResponse])
    } catch {
      case e: Exception =>
        logError(s"AskSync ReleaseSlots for ${message.shuffleId} failed.", e)
        ReleaseSlotsResponse(StatusCode.Failed)
    }
  }

  private def requestUnregisterShuffle(
      rssHARetryClient: RssHARetryClient,
      message: UnregisterShuffle): UnregisterShuffleResponse = {
    try {
      rssHARetryClient.askSync[UnregisterShuffleResponse](
        message, classOf[UnregisterShuffleResponse])
    } catch {
      case e: Exception =>
        logError(s"AskSync UnregisterShuffle for ${message.shuffleId} failed.", e)
        UnregisterShuffleResponse(StatusCode.Failed)
    }
  }

  private def requestGetBlacklist(
      rssHARetryClient: RssHARetryClient,
      message: GetBlacklist): GetBlacklistResponse = {
    try {
      rssHARetryClient.askSync[GetBlacklistResponse](message, classOf[GetBlacklistResponse])
    } catch {
      case e: Exception =>
        logError(s"AskSync GetBlacklist failed.", e)
        GetBlacklistResponse(StatusCode.Failed, List.empty.asJava, List.empty.asJava)
    }
  }

  private def recordWorkerFailure(failures: util.List[WorkerInfo]): Unit = {
    val failedWorker = new util.ArrayList[WorkerInfo](failures)
    logInfo(s"Report Worker Failure: ${failedWorker.asScala}, current blacklist $blacklist")
    blacklist.addAll(failedWorker)
  }

  def isClusterOverload(numPartitions: Int = 0): Boolean = {
    try {
      rssHARetryClient.askSync[GetClusterLoadStatusResponse](GetClusterLoadStatus(numPartitions),
        classOf[GetClusterLoadStatusResponse]).isOverload
    } catch {
      case e: Exception =>
        logError(s"AskSync Cluster Load Status failed.", e)
        true
    }
  }

  private def partitionExists(shuffleId: Int): Boolean = {
    val workers = workerSnapshots(shuffleId)
    if (workers == null || workers.isEmpty) {
      false
    } else {
      workers.values().asScala.exists(_.containsShuffle(shuffleId.toString))
    }
  }

  private def workersNotBlacklisted(shuffleId: Int): List[WorkerInfo] = {
    workerSnapshots(shuffleId)
      .keySet()
      .asScala
      .filter(w => !blacklist.contains(w))
      .toList
  }

  // Initialize at the end of LifecycleManager construction.
  initialize()
}