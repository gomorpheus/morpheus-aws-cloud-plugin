package com.morpheusdata.aws.sync

import com.morpheusdata.aws.AWSPlugin
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.projection.AccountResourceIdentityProjection

class InternalResourceSync {

	protected Cloud cloud
	protected MorpheusContext morpheusContext
	protected AWSPlugin plugin

	String getResourceTypeCode() {
		return 'generic'
	}



	protected removeMissingResources(List<AccountResourceIdentityProjection> removeList) {
		if(removeList) {
			morpheusContext.async.cloud.resource.bulkRemove(removeList).blockingGet()
		}

	}
}
