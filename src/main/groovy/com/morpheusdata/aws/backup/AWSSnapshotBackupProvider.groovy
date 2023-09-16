package com.morpheusdata.aws.backup

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.backup.AbstractBackupTypeProvider
import com.morpheusdata.core.backup.BackupExecutionProvider
import com.morpheusdata.core.backup.BackupRestoreProvider
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.OptionType
import groovy.util.logging.Slf4j

@Slf4j
class AWSSnapshotBackupProvider extends AbstractBackupTypeProvider {

	public static final String PROVIDER_CODE = 'amazonSnapshot'
	public static final String PROVIDER_NAME = 'Amazon VM Snapshot'

	AWSSnapshotBackupProvider(Plugin plugin, MorpheusContext context) {
		super(plugin, context)
	}

	@Override
	String getCode() {
		return PROVIDER_CODE
	}

	@Override
	String getName() {
		return PROVIDER_NAME
	}

	@Override
	Collection<OptionType> getOptionTypes() {
		return null
	}

	@Override
	BackupExecutionProvider getExecutionProvider() {
		return new AWSSnapshotExecutionProvider(plugin, morpheus);
	}

	@Override
	BackupRestoreProvider getRestoreProvider() {
		return new AWSSnapshotRestoreProvider(plugin, morpheus);
	}

	@Override
	String getContainerType() {
		return 'all'
	}

	@Override
	Boolean getCopyToStore() {
		return false
	}

	@Override
	Boolean getDownloadEnabled() {
		return false
	}

	@Override
	Boolean getRestoreExistingEnabled() {
		return true
	}

	@Override
	Boolean getRestoreNewEnabled() {
		return true
	}

	@Override
	String getRestoreType() {
		return 'offline'
	}

	@Override
	String getRestoreNewMode() {
		return null
	}

	@Override
	Boolean getHasCopyToStore() {
		return false
	}

	def getSnapshotsForBackupResult(backupSetId, containerId){
		ArrayList<Map> snapshots = new ArrayList<>()
		def backupResult = morpheus.async.backup.backupResult.list(
			new DataQuery(sort: "startDate", order: DataQuery.SortOrder.desc, max: 1, offset: 0)
			.withFilters(
				new DataFilter("backupSetId", backupSetId),
				new DataFilter("containerId", containerId),
			)
		).toList().blockingGet()

		if(backupResult) {
			def cloud
			log.debug("BackupResult zoneID: ${backupResult?.zoneId}")
			if(backupResult?.zoneId || backupResult.backup?.zoneId) {
				cloud = morpheus.async.cloud.get(backupResult.zoneId ?: backupResult.backup?.zoneId).blockingGet()
			}
			log.debug("BackupResult cloud: ${cloud}")

			snapshots = backupResult.configMap?.snapshots
			if(snapshots.getAt(0) instanceof List) {
				snapshots = (ArrayList<Map>) snapshots.getAt(0)
			}
			if(cloud) {
				snapshots?.each { snap ->
					snap.usageAccountId = cloud.externalId
					snap.regionCode = cloud.regionCode
					snap.zoneId = cloud.id
				}
			}
		}

		return snapshots
	}

}
