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
import org.opensearch.action.admin.cluster.health.ClusterHealthRequest
import org.opensearch.action.admin.cluster.health.ClusterHealthResponse
import org.opensearch.action.admin.cluster.node.stats.NodesStatsRequest
import org.opensearch.action.admin.cluster.node.stats.NodesStatsResponse
import org.opensearch.action.admin.cluster.reroute.ClusterRerouteRequest
import org.opensearch.action.admin.cluster.reroute.ClusterRerouteResponse
import org.opensearch.action.admin.indices.stats.IndicesStatsRequest
import org.opensearch.action.admin.indices.stats.IndicesStatsResponse
import org.opensearch.action.support.master.AcknowledgedResponse
import org.opensearch.client.Client
import org.opensearch.cluster.metadata.IndexMetadata
import org.opensearch.cluster.routing.allocation.command.MoveAllocationCommand
import org.opensearch.cluster.routing.allocation.decider.Decision
import org.opensearch.cluster.service.ClusterService
import org.opensearch.common.collect.Tuple
import org.opensearch.common.settings.Settings
import org.opensearch.common.unit.ByteSizeValue
import org.opensearch.indexmanagement.indexstatemanagement.model.ManagedIndexMetaData
import org.opensearch.indexmanagement.indexstatemanagement.model.action.ShrinkActionConfig
import org.opensearch.indexmanagement.indexstatemanagement.model.managedindexmetadata.ActionProperties
import org.opensearch.indexmanagement.indexstatemanagement.model.managedindexmetadata.ShrinkActionProperties
import org.opensearch.indexmanagement.indexstatemanagement.model.managedindexmetadata.StepMetaData
import org.opensearch.indexmanagement.indexstatemanagement.step.Step
import org.opensearch.indexmanagement.indexstatemanagement.util.getActionStartTime
import org.opensearch.indexmanagement.indexstatemanagement.util.getShrinkLockModel
import org.opensearch.indexmanagement.indexstatemanagement.util.issueUpdateSettingsRequest
import org.opensearch.indexmanagement.opensearchapi.suspendUntil
import org.opensearch.jobscheduler.repackage.com.cronutils.utils.VisibleForTesting
import org.opensearch.jobscheduler.spi.JobExecutionContext
import org.opensearch.jobscheduler.spi.LockModel
import java.lang.Exception
import java.time.Duration
import java.time.Instant
import java.util.PriorityQueue
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.collections.HashSet
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt

@SuppressWarnings("TooManyFunctions")
class AttemptMoveShardsStep(
    val clusterService: ClusterService,
    val client: Client,
    val config: ShrinkActionConfig,
    managedIndexMetaData: ManagedIndexMetaData,
    val context: JobExecutionContext
) : Step(name, managedIndexMetaData) {
    private val logger = LogManager.getLogger(javaClass)
    private var stepStatus = StepStatus.STARTING
    private var info: Map<String, Any>? = null
    private var shrinkActionProperties: ShrinkActionProperties? = null
    override fun isIdempotent() = true

    @Suppress("TooGenericExceptionCaught", "ComplexMethod", "ReturnCount", "LongMethod")
    override suspend fun execute(): AttemptMoveShardsStep {
        try {
            checkTimeOut()
            // check whether the target index name is available.
            val indexNameSuffix = config.targetIndexSuffix ?: DEFAULT_TARGET_SUFFIX
            val shrinkTargetIndexName = managedIndexMetaData.index + indexNameSuffix
            val indexExists = clusterService.state().metadata.indices.containsKey(shrinkTargetIndexName)
            if (indexExists) {
                info = mapOf("message" to getIndexExistsMessage(shrinkTargetIndexName))
                stepStatus = StepStatus.FAILED
                return this
            }

            // get cluster health
            val healthReq = ClusterHealthRequest().indices(managedIndexMetaData.index).waitForGreenStatus()
            val response: ClusterHealthResponse = client.admin().cluster().suspendUntil { health(healthReq, it) }
            // check status of cluster health
            if (response.isTimedOut) {
                info = mapOf("message" to getFailureMessage())
                stepStatus = StepStatus.CONDITION_NOT_MET
                return this
            }

            // force_unsafe check
            val numReplicas = clusterService.state().metadata.indices[managedIndexMetaData.index].numberOfReplicas
            val shouldFailForceUnsafeCheck = numReplicas == 0 && ((config.forceUnsafe != null && !config.forceUnsafe) || (config.forceUnsafe == null))
            if (shouldFailForceUnsafeCheck) {
                info = mapOf("message" to getUnsafeFailure())
                stepStatus = StepStatus.FAILED
                return this
            }
            // Get the number of primary shards in the index -- all will be active because index health is green
            val numOriginalShards = clusterService.state().metadata.indices[managedIndexMetaData.index].numberOfShards
            if (numOriginalShards == 1) {
                info = mapOf("message" to getOnePrimaryShardFailure())
                stepStatus = StepStatus.FAILED
                return this
            }
            // Get the size of the index
            val statsRequest = IndicesStatsRequest().indices(indexName)
            val statsResponse: IndicesStatsResponse = client.admin().indices().suspendUntil {
                stats(statsRequest, it)
            }
            val statsStore = statsResponse.total.store
            if (statsStore == null) {
                info = mapOf("message" to getFailureMessage())
                stepStatus = StepStatus.FAILED
                return this
            }
            val indexSize = statsStore.sizeInBytes

            // get the number of shards that the target index will have
            val numTargetShards = getNumTargetShards(numOriginalShards, indexSize)
            // get the nodes with enough memory
            val suitableNodes = findSuitableNodes(statsResponse, indexSize, bufferPercentage, numOriginalShards)
            // iterate through the nodes and try to acquire a lock on those nodes
            val lock = acquireLockOnNode(suitableNodes)
            if (lock == null) {
                logger.info("${managedIndexMetaData.index} could not find available node to shrink onto.")
                info = mapOf("message" to getNoAvailableNodesMessage())
                stepStatus = StepStatus.CONDITION_NOT_MET
                return this
            }
            // move the shards
            val nodeName = lock.resource[RESOURCE_NAME] as String
            shrinkActionProperties = ShrinkActionProperties(
                nodeName,
                shrinkTargetIndexName,
                numTargetShards,
                lock.primaryTerm,
                lock.seqNo,
                lock.lockTime.epochSecond
            )
            setToReadOnlyAndMoveIndexToNode(nodeName)
            info = mapOf("message" to getSuccessMessage(nodeName))
            stepStatus = StepStatus.COMPLETED
            return this
        } catch (e: Exception) {
            info = mapOf("message" to getFailureMessage(), "cause" to "{${e.message}}")
            stepStatus = StepStatus.FAILED
            return this
        }
    }

    private suspend fun setToReadOnlyAndMoveIndexToNode(node: String) {
        val updateSettings = Settings.builder()
            .put(IndexMetadata.SETTING_BLOCKS_WRITE, true)
            .put(ROUTING_SETTING, node)
            .build()
        issueUpdateAndUnlockIfFail(updateSettings, getUpdateFailedMessage())
    }

    private suspend fun issueUpdateAndUnlockIfFail(settings: Settings, failureMessage: String) {
        try {
            val response: AcknowledgedResponse = issueUpdateSettingsRequest(client, managedIndexMetaData, settings)
            if (!response.isAcknowledged) {
                stepStatus = StepStatus.FAILED
                info = mapOf("message" to failureMessage)
            }
        } catch (e: Exception) {
            handleException(e, failureMessage)
            val copyProperties = shrinkActionProperties
            if (copyProperties != null) {
                val lock = getShrinkLockModel(
                    copyProperties.nodeName,
                    context.jobIndexName,
                    context.jobId,
                    copyProperties.lockEpochSecond,
                    copyProperties.lockPrimaryTerm,
                    copyProperties.lockSeqNo
                )
                context.lockService.suspendUntil<Boolean> { release(lock, it) }
            }
        }
    }

    private suspend fun acquireLockOnNode(suitableNodes: List<String>): LockModel? {
        var lock: LockModel? = null
        for (node in suitableNodes) {
            val nodeResourceObject: HashMap<String, String> = HashMap()
            nodeResourceObject[RESOURCE_NAME] = node
            val lockTime = config.configTimeout?.timeout?.seconds ?: MOVE_SHARDS_TIMEOUT_IN_SECONDS
            lock = context.lockService.suspendUntil<LockModel> {
                acquireLockOnResource(context, lockTime, RESOURCE_TYPE, nodeResourceObject as Map<String, Any>?, it)
            }
            if (lock != null) {
                return lock
            }
        }
        return lock
    }

    @VisibleForTesting
    @SuppressWarnings("NestedBlockDepth", "ComplexMethod")
    private suspend fun findSuitableNodes(
        indicesStatsResponse: IndicesStatsResponse,
        indexSize: Long,
        buffer: Long,
        numOriginalShards: Int
    ): List<String> {
        val nodesStatsReq = NodesStatsRequest().addMetric(OS_METRIC)
        val nodeStatsResponse: NodesStatsResponse = client.admin().cluster().suspendUntil { nodesStats(nodesStatsReq, it) }
        val nodesList = nodeStatsResponse.nodes
        val comparator = kotlin.Comparator { o1: Tuple<Long, String>, o2: Tuple<Long, String> -> o1.v1().compareTo(o2.v1()) }
        val nodesWithSpace = PriorityQueue<Tuple<Long, String>>(comparator)
        for (node in nodesList) {
            val osStats = node.os
            if (osStats != null) {
                val memLeftInNode = osStats.mem.free.bytes
                val totalNodeMem = osStats.mem.total.bytes
                val bufferSize = ByteSizeValue(buffer * totalNodeMem)
                val requiredBytes = (2 * indexSize) + bufferSize.bytes
                if (memLeftInNode > requiredBytes) {
                    val memLeftAfterTransfer: Long = memLeftInNode - requiredBytes
                    nodesWithSpace.add(Tuple(memLeftAfterTransfer, node.node.name))
                }
            }
        }
        val suitableNodes: ArrayList<String> = ArrayList()
        for (sizeNodeTuple in nodesWithSpace) {
            val nodeName = sizeNodeTuple.v2()
            val movableShardIds = HashSet<Int>()
            for (shard in indicesStatsResponse.shards) {
                val shardId = shard.shardRouting.shardId()
                val currentShardNode = clusterService.state().nodes[shard.shardRouting.currentNodeId()]
                if (currentShardNode.name.equals(nodeName)) {
                    movableShardIds.add(shardId.id)
                } else {
                    val allocationCommand = MoveAllocationCommand(indexName, shardId.id, currentShardNode.name, nodeName)
                    val rerouteRequest = ClusterRerouteRequest().explain(true).dryRun(true).add(allocationCommand)

                    val clusterRerouteResponse: ClusterRerouteResponse = client.admin().cluster().suspendUntil { reroute(rerouteRequest, it) }
                    val filteredExplanations = clusterRerouteResponse.explanations.explanations().filter {
                        it.decisions().type().equals(Decision.Type.YES)
                    }
                    if (filteredExplanations.isNotEmpty()) {
                        movableShardIds.add(shardId.id)
                    }
                }
            }
            if (movableShardIds.size >= numOriginalShards) {
                suitableNodes.add(sizeNodeTuple.v2())
            }
        }
        return suitableNodes
    }

    @SuppressWarnings("ReturnCount")
    private fun getNumTargetShards(numOriginalShards: Int, indexSize: Long): Int {
        // case where user specifies a certain number of shards in the target index
        if (config.numNewShards != null) return getGreatestFactorLessThan(numOriginalShards, config.numNewShards)

        // case where user specifies a percentage decrease in the number of shards in the target index
        if (config.percentageDecrease != null) {
            val numTargetShards = floor((config.percentageDecrease) * numOriginalShards).toInt()
            return getGreatestFactorLessThan(numOriginalShards, numTargetShards)
        }
        // case where the user specifies a max shard size in the target index
        val maxShardSizeInBytes = config.maxShardSize!!.bytes
        // ensures that numTargetShards is never less than 1
        val minNumTargetShards = ceil(indexSize / maxShardSizeInBytes.toDouble()).toInt()
        return getMinFactorGreaterThan(numOriginalShards, minNumTargetShards)
    }

    @SuppressWarnings("ReturnCount")
    private fun getGreatestFactorLessThan(n: Int, k: Int): Int {
        if (k >= n) return n
        val bound: Int = min(floor(sqrt(n.toDouble())).toInt(), k)
        var greatestFactor = 1
        for (i in 2..bound + 1) {
            if (n % i == 0) {
                val complement: Int = n / i
                if (complement <= k) {
                    return complement
                } else {
                    greatestFactor = i
                }
            }
        }
        return greatestFactor
    }

    @SuppressWarnings("ReturnCount")
    private fun getMinFactorGreaterThan(n: Int, k: Int): Int {
        if (k >= n) {
            return n
        }
        for (i in k..n + 1) {
            if (n % i == 0) return i
        }
        return n
    }

    private fun handleException(e: Exception, message: String) {
        logger.error(message, e)
        stepStatus = StepStatus.FAILED
        val mutableInfo = mutableMapOf("message" to message)
        val errorMessage = e.message
        if (errorMessage != null) mutableInfo["cause"] = errorMessage
        info = mutableInfo.toMap()
    }

    override fun getUpdatedManagedIndexMetaData(currentMetaData: ManagedIndexMetaData): ManagedIndexMetaData {
        val currentActionMetaData = currentMetaData.actionMetaData
        return currentMetaData.copy(
            actionMetaData = currentActionMetaData?.copy(
                actionProperties = ActionProperties(
                    shrinkActionProperties = shrinkActionProperties
                )
            ),
            stepMetaData = StepMetaData(name, getStepStartTime().toEpochMilli(), stepStatus),
            transitionTo = null,
            info = info
        )
    }

    private fun checkTimeOut() {
        val timeFromActionStarted: Duration = Duration.between(getActionStartTime(managedIndexMetaData), Instant.now())
        val timeOutInSeconds = config.configTimeout?.timeout?.seconds ?: MOVE_SHARDS_TIMEOUT_IN_SECONDS
        // Get ActionTimeout if given, otherwise use default timeout of 12 hours
        if (timeFromActionStarted.toSeconds() > timeOutInSeconds) {
            info = mapOf("message" to getTimeoutMessage())
            stepStatus = StepStatus.FAILED
        }
    }

    companion object {
        const val OS_METRIC = "os"
        const val ROUTING_SETTING = "index.routing.allocation.require._name"
        const val RESOURCE_NAME = "node_name"
        const val DEFAULT_TARGET_SUFFIX = "_shrunken"
        const val bufferPercentage = 0.05.toLong()
        const val MOVE_SHARDS_TIMEOUT_IN_SECONDS = 43200L // 12hrs in seconds
        const val name = "attempt_move_shards_step"
        const val RESOURCE_TYPE = "shrink"
        fun getTimeoutMessage() = "Timed out waiting for finding node."
        fun getSuccessMessage(node: String) = "Successfully started moving the shards to $node."
        fun getUpdateFailedMessage() = "Shrink failed because settings could not be updated.."
        fun getNoAvailableNodesMessage() =
            "There are no available nodes for to move to to execute a shrink. Delaying until node becomes available."
        fun getFailureMessage() = "Shrink failed to start moving shards."
        fun getIndexExistsMessage(newIndex: String) = "Shrink failed because $newIndex already exists."
        fun getUnsafeFailure() = "Shrink failed because index has no replicas and force_unsafe is not set to true."
        fun getOnePrimaryShardFailure() = "Shrink failed because index only has one primary shard."
    }
}
