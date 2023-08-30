package com.morpheusdata.aws

import com.morpheusdata.aws.backup.AWSSnapshotBackupProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.backup.AbstractBackupProvider
import com.morpheusdata.core.backup.BackupJobProvider
import com.morpheusdata.core.backup.MorpheusBackupProvider
import com.morpheusdata.model.BackupProvider
import com.morpheusdata.model.Icon
import com.morpheusdata.model.OptionType
import com.morpheusdata.response.ServiceResponse

class AWSBackupProvider extends MorpheusBackupProvider {

	AWSBackupProvider(Plugin plugin, MorpheusContext context) {
		super(plugin, context)

		AWSSnapshotBackupProvider awsSnapshotBackupProvider = new AWSSnapshotBackupProvider(plugin, morpheus)
		plugin.registerProvider(awsSnapshotBackupProvider)
		addScopedProvider(awsSnapshotBackupProvider, "amazon", null)
	}

}
