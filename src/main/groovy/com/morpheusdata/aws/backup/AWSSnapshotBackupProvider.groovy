package com.morpheusdata.aws.backup

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.backup.AbstractBackupTypeProvider
import com.morpheusdata.core.backup.BackupExecutionProvider
import com.morpheusdata.core.backup.BackupRestoreProvider
import com.morpheusdata.model.OptionType

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


}
