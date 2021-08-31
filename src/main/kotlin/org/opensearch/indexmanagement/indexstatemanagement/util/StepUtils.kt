package org.opensearch.indexmanagement.indexstatemanagement.util

import org.apache.logging.log4j.Logger
import org.opensearch.action.admin.indices.settings.put.UpdateSettingsRequest
import org.opensearch.action.support.master.AcknowledgedResponse
import org.opensearch.client.Client
import org.opensearch.common.settings.Settings
import org.opensearch.indexmanagement.indexstatemanagement.model.ManagedIndexMetaData
import org.opensearch.indexmanagement.indexstatemanagement.model.managedindexmetadata.ShrinkActionProperties
import org.opensearch.indexmanagement.indexstatemanagement.step.shrink.WaitForMoveShardsStep
import org.opensearch.indexmanagement.opensearchapi.suspendUntil
import org.opensearch.jobscheduler.spi.JobExecutionContext
import org.opensearch.jobscheduler.spi.LockModel
import org.opensearch.transport.RemoteTransportException
import java.time.Instant

suspend fun issueUpdateSettingsRequest(client: Client, managedIndexMetaData: ManagedIndexMetaData, settings: Settings): AcknowledgedResponse {
    try {
        return client.admin()
            .indices()
            .suspendUntil { updateSettings(UpdateSettingsRequest(settings, managedIndexMetaData.index), it) }
    } catch (e: Exception) {
        throw e
    }
}

suspend fun releaseShrinkLock(
    shrinkActionProperties: ShrinkActionProperties,
    context: JobExecutionContext,
    logger: Logger
) {
    try {
        val lock: LockModel = getShrinkLockModel(shrinkActionProperties, context)
        val released: Boolean = context.lockService.suspendUntil { release(lock, it) }
        if (!released) {
            logger.warn("Lock not released on failure")
        }
    } catch (e: RemoteTransportException) {
        throw e
    }
}

fun getShrinkLockModel(
    shrinkActionProperties: ShrinkActionProperties,
    context: JobExecutionContext
): LockModel {
    return getShrinkLockModel(
        shrinkActionProperties.nodeName,
        context.jobIndexName,
        context.jobId,
        shrinkActionProperties.lockEpochSecond,
        shrinkActionProperties.lockPrimaryTerm,
        shrinkActionProperties.lockSeqNo
    )
}

@SuppressWarnings("LongParameterList")
fun getShrinkLockModel(
    nodeName: String,
    jobIndexName: String,
    jobId: String,
    lockEpochSecond: Long,
    lockPrimaryTerm: Long,
    lockSeqNo: Long
): LockModel {
    val resource: HashMap<String, String> = HashMap()
    resource[WaitForMoveShardsStep.RESOURCE_NAME] = nodeName
    val lockCreationInstant: Instant = Instant.ofEpochSecond(lockEpochSecond)
    return LockModel(
        jobIndexName,
        jobId,
        WaitForMoveShardsStep.RESOURCE_TYPE,
        resource as Map<String, Any>?,
        lockCreationInstant,
        WaitForMoveShardsStep.MOVE_SHARDS_TIMEOUT_IN_SECONDS,
        false,
        lockSeqNo,
        lockPrimaryTerm
    )
}

fun getActionStartTime(managedIndexMetaData: ManagedIndexMetaData): Instant {
    if (managedIndexMetaData.actionMetaData?.startTime == null) {
        return Instant.now()
    }

    return Instant.ofEpochMilli(managedIndexMetaData.actionMetaData.startTime)
}
