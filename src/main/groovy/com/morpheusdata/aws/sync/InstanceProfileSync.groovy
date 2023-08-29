package com.morpheusdata.aws.sync

import com.amazonaws.services.identitymanagement.AmazonIdentityManagement
import com.amazonaws.services.identitymanagement.model.InstanceProfile
import com.amazonaws.services.rds.AmazonRDS
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
class InstanceProfileSync {
	private Cloud cloud
	private MorpheusContext morpheusContext
	private AWSPlugin plugin

	public InstanceProfileSync(AWSPlugin plugin, Cloud cloud) {
		this.plugin = plugin
		this.cloud = cloud
		this.morpheusContext = plugin.morpheusContext
	}


	def execute() {
		morpheusContext.async.cloud.region.listIdentityProjections(cloud.id).flatMap {
			final String regionCode = it.externalId
			AmazonIdentityManagement amazonClient = AmazonComputeUtility.getAmazonIamClient(cloud,false,it.externalId)
			def instanceProfileResults = AmazonComputeUtility.listInstanceProfiles([amazonClient: amazonClient])
			if(instanceProfileResults.success) {
				Observable<ReferenceDataSyncProjection> domainRecords = morpheusContext.async.referenceData.listByAccountIdAndCategories(cloud.owner.id, ["amazon.ec2.profiles.${cloud.id}.${regionCode}", "amazon.ec2.profiles.${cloud.id}"])
				SyncTask<ReferenceDataSyncProjection, InstanceProfile, ReferenceData> syncTask = new SyncTask<>(domainRecords, instanceProfileResults.results as Collection<InstanceProfile>)
				return syncTask.addMatchFunction { ReferenceDataSyncProjection domainObject, InstanceProfile data ->
					domainObject.externalId == data.getInstanceProfileId()
				}.addMatchFunction { ReferenceDataSyncProjection domainObject, InstanceProfile data ->
					domainObject.name == data.getInstanceProfileName()
				}.onDelete { removeItems ->
					removeMissingInstanceProfiles(removeItems)
				}.onUpdate { List<SyncTask.UpdateItem<ReferenceData, InstanceProfile>> updateItems ->
					//nothing to update for now(probably should at some point)
				}.onAdd { itemsToAdd ->
					addMissingInstanceProfiles(itemsToAdd, regionCode)
				}.withLoadObjectDetailsFromFinder { List<SyncTask.UpdateItemDto<ReferenceDataSyncProjection, InstanceProfile>> updateItems ->
					morpheusContext.async.referenceData.listById(updateItems.collect { it.existingItem.id } as List<Long>)
				}.observe()
			} else {
				log.error("Error Caching Instance Profiles for Region: {} - {}",regionCode,instanceProfileResults.msg)
				return Single.just(false).toObservable() //ignore invalid region
			}
		}.blockingSubscribe()
	}

	private String getCategory(String regionCode) {
		return "amazon.ec2.profiles.${cloud.id}.${regionCode}"
	}


	def addMissingInstanceProfiles(Collection addList, String regionCode) {
		def adds = []

		for(InstanceProfile cloudItem in addList) {
			def addConfig = [account:cloud.owner, code:"amazon.ec2.profiles.${cloud.id}.${cloudItem.getInstanceProfileId()}", category:getCategory(regionCode),
							 name:cloudItem.getInstanceProfileName(), keyValue:cloudItem.getInstanceProfileId(), value:cloudItem.getArn(), path:cloudItem.getPath(),
							 refType:'ComputeZone', refId:"${cloud.id}", externalId: cloudItem.getInstanceProfileId()]
			def add = new ReferenceData(addConfig)

			adds << add
		}

		if(adds) {
			morpheusContext.async.referenceData.create(adds).blockingGet()
		}
	}

	private removeMissingInstanceProfiles(List<ReferenceDataSyncProjection> removeList) {
		morpheusContext.async.referenceData.remove(removeList).blockingGet()
	}
}
