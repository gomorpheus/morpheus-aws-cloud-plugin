package com.morpheusdata.aws.sync

import com.amazonaws.services.identitymanagement.AmazonIdentityManagement
import com.amazonaws.services.identitymanagement.model.Role
import com.morpheusdata.aws.AWSPlugin
import com.morpheusdata.aws.utils.AmazonComputeUtility
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ReferenceData
import com.morpheusdata.model.projection.ReferenceDataSyncProjection
import groovy.util.logging.Slf4j
import io.reactivex.Observable
import io.reactivex.Single

@Slf4j
class IAMRoleSync {
	private Cloud cloud
	private MorpheusContext morpheusContext
	private AWSPlugin plugin

	public IAMRoleSync(AWSPlugin plugin, Cloud cloud) {
		this.plugin = plugin
		this.cloud = cloud
		this.morpheusContext = plugin.morpheusContext
	}


	def execute() {
		morpheusContext.async.cloud.region.listIdentityProjections(cloud.id).flatMap {
			final String regionCode = it.externalId
			AmazonIdentityManagement amazonClient = AmazonComputeUtility.getAmazonIamClient(cloud,false,it.externalId)
			def RoleResults = AmazonComputeUtility.listRoles([amazonClient: amazonClient])
			if(RoleResults.success) {
				Observable<ReferenceDataSyncProjection> domainRecords = morpheusContext.async.referenceData.listByAccountIdAndCategories(cloud.owner.id, ["amazon.ec2.roles.${cloud.id}.${regionCode}", "amazon.ec2.roles.${cloud.id}"])
				SyncTask<ReferenceDataSyncProjection, Role, ReferenceData> syncTask = new SyncTask<>(domainRecords, RoleResults.results as Collection<Role>)
				return syncTask.addMatchFunction { ReferenceDataSyncProjection domainObject, Role data ->
					domainObject.externalId == data.getRoleId()
				}.addMatchFunction { ReferenceDataSyncProjection domainObject, Role data ->
					domainObject.name == data.getRoleName()
				}.onDelete { removeItems ->
					removeMissingRoles(removeItems)
				}.onUpdate { List<SyncTask.UpdateItem<ReferenceData, Role>> updateItems ->
					//nothing to update for now(probably should at some point)
				}.onAdd { itemsToAdd ->
					addMissingRoles(itemsToAdd, regionCode)
				}.withLoadObjectDetailsFromFinder { List<SyncTask.UpdateItemDto<ReferenceDataSyncProjection, Role>> updateItems ->
					morpheusContext.async.referenceData.listById(updateItems.collect { it.existingItem.id } as List<Long>)
				}.observe()
			} else {
				log.error("Error Caching IAM Roles for Region: {} - {}",regionCode,RoleResults.msg)
				return Single.just(false).toObservable() //ignore invalid region
			}
		}.blockingSubscribe()
	}

	private String getCategory(String regionCode) {
		return "amazon.ec2.roles.${cloud.id}.${regionCode}"
	}


	def addMissingRoles(Collection addList, String regionCode) {
		def adds = []

		for(Role cloudItem in addList) {
			def addConfig = [account:cloud.owner, code:getCategory(regionCode) + '.' + cloudItem.getRoleId(), category:getCategory(regionCode),
							 name:cloudItem.getRoleName(), keyValue:cloudItem.getRoleId(), externalId: cloudItem.getRoleId(), value:cloudItem.getArn(), path:cloudItem.getPath(),
							 refType:'ComputeZone', refId:"${cloud.id}"]
			def add = new ReferenceData(addConfig)
			adds << add
		}

		if(adds) {
			morpheusContext.async.cloud.create(adds, cloud, getCategory(regionCode)).blockingGet()
		}
	}

	private removeMissingRoles(List<ReferenceDataSyncProjection> removeList) {
		morpheusContext.async.cloud.remove(removeList).blockingGet()
	}
}
