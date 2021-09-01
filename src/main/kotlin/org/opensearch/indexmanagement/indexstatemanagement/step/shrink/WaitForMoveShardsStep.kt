/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 *
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.indexmanagement.indexstatemanagement.step.shrink

import org.apache.logging.log4j.LogManager
import org.opensearch.action.admin.indices.stats.IndicesStatsRequest
import org.opensearch.action.admin.indices.stats.IndicesStatsResponse
import org.opensearch.action.admin.indices.stats.ShardStats
import org.opensearch.client.Client
import org.opensearch.cluster.service.ClusterService
import org.opensearch.index.shard.ShardId
import org.opensearch.indexmanagement.indexstatemanagement.model.ManagedIndexMetaData
import org.opensearch.indexmanagement.indexstatemanagement.model.action.ShrinkActionConfig
import org.opensearch.indexmanagement.indexstatemanagement.model.managedindexmetadata.StepMetaData
import org.opensearch.indexmanagement.indexstatemanagement.step.Step
import org.opensearch.indexmanagement.indexstatemanagement.util.getActionStartTime
import org.opensearch.indexmanagement.indexstatemanagement.util.releaseShrinkLock
import org.opensearch.indexmanagement.opensearchapi.suspendUntil
import org.opensearch.jobscheduler.spi.JobExecutionContext
import org.opensearch.transport.RemoteTransportException
import java.lang.Exception
import java.time.Duration
import java.time.Instant

class WaitForMoveShardsStep(
    val clusterService: ClusterService,
    val client: Client,
    val config: ShrinkActionConfig,
    managedIndexMetaData: ManagedIndexMetaData,
    val context: JobExecutionContext
) : Step(name, managedIndexMetaData) {
    private val logger = LogManager.getLogger(javaClass)
    private var stepStatus = StepStatus.STARTING
    private var info: Map<String, Any>? = null

    override fun isIdempotent() = true

    @Suppress("TooGenericExceptionCaught", "ComplexMethod", "ReturnCount")
    override suspend fun execute(): WaitForMoveShardsStep {
        if (managedIndexMetaData.actionMetaData?.actionProperties?.shrinkActionProperties == null) {
            info = mapOf("message" to "Metadata not properly populated")
            stepStatus = StepStatus.FAILED
            return this
        }
        try {
            val indexStatsRequests: IndicesStatsRequest = IndicesStatsRequest().indices(managedIndexMetaData.index)
            val response: IndicesStatsResponse = client.admin().indices().suspendUntil { stats(indexStatsRequests, it) }
            val numPrimaryShards = clusterService.state().metadata.indices[managedIndexMetaData.index].numberOfShards
            val nodeToMoveOnto = managedIndexMetaData.actionMetaData.actionProperties.shrinkActionProperties.nodeName
            var numShardsOnNode = 0
            val shardToCheckpointSetMap: MutableMap<ShardId, MutableSet<Long>> = mutableMapOf()
            for (shard: ShardStats in response.shards) {
                val seqNoStats = shard.seqNoStats
                val routingInfo = shard.shardRouting
                if (seqNoStats != null) {
                    val checkpoint = seqNoStats.localCheckpoint
                    val shardId = shard.shardRouting.shardId()
                    val checkpointsOfShard = shardToCheckpointSetMap.getOrDefault(shardId, mutableSetOf())
                    checkpointsOfShard.add(checkpoint)
                    shardToCheckpointSetMap[shardId] = checkpointsOfShard
                }
                // TODO: Test if we can make this appear / if we can, fail the action.
                shardToCheckpointSetMap.entries.forEach {
                    (_, checkpointSet) ->
                    if (checkpointSet.size > 1) {
                        logger.warn("There are shards with varying local checkpoints")
                    }
                }
                val nodeIdShardIsOn = routingInfo.currentNodeId()
                val nodeShardIsOn = clusterService.state().nodes()[nodeIdShardIsOn].name
                if (nodeShardIsOn.equals(nodeToMoveOnto) && routingInfo.started()) {
                    numShardsOnNode++
                }
            }
            if (numShardsOnNode >= numPrimaryShards) {
                info = mapOf("message" to getSuccessMessage(nodeToMoveOnto))
                stepStatus = StepStatus.COMPLETED
                return this
            }
            val numShardsLeft = numPrimaryShards - numShardsOnNode
            checkTimeOut(numShardsLeft, nodeToMoveOnto)
            return this
        } catch (e: RemoteTransportException) {
            releaseShrinkLock(managedIndexMetaData.actionMetaData.actionProperties.shrinkActionProperties, context, logger)
            info = mapOf("message" to getFailureMessage())
            stepStatus = StepStatus.FAILED
            return this
        } catch (e: Exception) {
            releaseShrinkLock(managedIndexMetaData.actionMetaData.actionProperties.shrinkActionProperties, context, logger)
            info = mapOf("message" to getFailureMessage(), "cause" to "{${e.message}}")
            stepStatus = StepStatus.FAILED
            return this
        }
    }

    override fun getUpdatedManagedIndexMetaData(currentMetaData: ManagedIndexMetaData): ManagedIndexMetaData {
        // Saving maxNumSegments in ActionProperties after the force merge operation has begun so that if a ChangePolicy occurred
        // in between this step and WaitForForceMergeStep, a cached segment count expected from the operation is available
        val currentActionMetaData = currentMetaData.actionMetaData
        return currentMetaData.copy(
            actionMetaData = currentActionMetaData?.copy(),
            stepMetaData = StepMetaData(name, getStepStartTime().toEpochMilli(), stepStatus),
            transitionTo = null,
            info = info
        )
    }

    private suspend fun checkTimeOut(numShardsLeft: Int, nodeToMoveOnto: String) {
        val timeFromActionStarted: Duration = Duration.between(getActionStartTime(managedIndexMetaData), Instant.now())
        val timeOutInSeconds = config.configTimeout?.timeout?.seconds ?: MOVE_SHARDS_TIMEOUT_IN_SECONDS
        // Get ActionTimeout if given, otherwise use default timeout of 12 hours
        stepStatus = if (timeFromActionStarted.toSeconds() > timeOutInSeconds) {
            logger.debug(
                "Move shards failing on [$indexName] because" +
                    " [$numShardsLeft] shards still needing to be moved"
            )
            if (managedIndexMetaData.actionMetaData?.actionProperties?.shrinkActionProperties != null) {
                releaseShrinkLock(managedIndexMetaData.actionMetaData.actionProperties.shrinkActionProperties, context, logger)
            }
            info = mapOf("message" to getTimeoutFailure(nodeToMoveOnto))
            StepStatus.FAILED
        } else {
            logger.debug(
                "Move shards still running on [$indexName] with" +
                    " [$numShardsLeft] shards still needing to be moved"
            )
            info = mapOf("message" to getTimeoutDelay(nodeToMoveOnto))
            StepStatus.CONDITION_NOT_MET
        }
    }

    companion object {
        const val name = "wait_for_move_shards_step"
        fun getSuccessMessage(node: String) = "The shards successfully moved to $node."
        fun getTimeoutFailure(node: String) = "Shrink failed because it took to long to move shards to $node"
        fun getTimeoutDelay(node: String) = "Shrink delayed because it took to long to move shards to $node"
        fun getFailureMessage() = "Shrink failed when waiting for shards to move."
        const val MOVE_SHARDS_TIMEOUT_IN_SECONDS = 43200L // 12hrs in seconds
        const val RESOURCE_NAME = "node_name"
        const val RESOURCE_TYPE = "shrink"
    }
}
