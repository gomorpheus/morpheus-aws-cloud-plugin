package com.morpheusdata.aws.sync

import com.amazonaws.services.ec2.model.AvailabilityZone
import com.amazonaws.services.rds.model.DBSubnetGroup
import com.morpheusdata.aws.AWSPlugin
import com.morpheusdata.aws.utils.AmazonComputeUtility
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ReferenceData
import com.morpheusdata.model.projection.ReferenceDataSyncProjection
import groovy.util.logging.Slf4j
import io.reactivex.Observable
import io.reactivex.Single

@Slf4j
class AvailabilityZoneSync {
	private Cloud cloud
	private MorpheusContext morpheusContext
	private AWSPlugin plugin

	AvailabilityZoneSync(AWSPlugin plugin, Cloud cloud) {
		this.plugin = plugin
		this.cloud = cloud
		this.morpheusContext = plugin.morpheusContext
	}

	def execute() {
		try {
			morpheusContext.async.cloud.region.listIdentityProjections(cloud.id).concatMap { region ->
				def amazonClient = plugin.getAmazonClient(cloud,false, region.externalId)
				def zoneResults = AmazonComputeUtility.listAvailabilityZones(amazonClient: amazonClient)
				if(zoneResults.success) {
					Observable<ReferenceDataSyncProjection> domainRecords = morpheusContext.async.referenceData.listIdentityProjections(
						new DataQuery().withFilters([
							new DataFilter ('account.id', cloud.owner.id ),
							new DataFilter('category', "amazon.ec2.zone.${cloud.id}.${region.externalId}")
						])
					)
					SyncTask<ReferenceDataSyncProjection, AvailabilityZone, ReferenceData> syncTask = new SyncTask<>(domainRecords, zoneResults.zoneList as Collection<AvailabilityZone>)
					return syncTask.addMatchFunction { ReferenceDataSyncProjection domainObject, AvailabilityZone data ->
						domainObject.name == data.zoneName
					}.onDelete { removeItems ->
						removeMissingAvailabilityZones(removeItems)
					}.onUpdate { List<SyncTask.UpdateItem<ReferenceData, DBSubnetGroup>> updateItems ->
						//nothing to update
					}.onAdd { itemsToAdd ->
						addMissingAvailabilityZones(itemsToAdd, region.externalId)
					}.withLoadObjectDetailsFromFinder { List<SyncTask.UpdateItemDto<ReferenceDataSyncProjection, AvailabilityZone>> updateItems ->
						def refData
						refData = morpheusContext.async.referenceData.listById(updateItems.collect { it.existingItem.id } as List<Long>)
						refData
					}.observe()
				} else {
					log.error("Error Caching Subnets for Region: {} - {}", region.externalId, zoneResults.msg)
					return Single.just(false).toObservable()
				}
			}.blockingSubscribe()
		} catch(Exception ex) {
			log.error("AvailabilityZoneSync error: {}", ex, ex)
		}
	}

	protected void addMissingAvailabilityZones(Collection<AvailabilityZone> addList, String region) {
		def adds = []
		for(cloudItem in addList) {
			adds << new ReferenceData(
				account: cloud.owner,
				code: "amazon.ec2.zone.${cloud.id}.${cloudItem.zoneName}",
				category: "amazon.ec2.zone.${cloud.id}.${region}",
				name: cloudItem.zoneName,
				keyValue: cloudItem.zoneName,
				value: cloudItem.zoneName,
				refType: 'ComputeZone',
				refId:"${cloud.id}",
				type: 'string'
			)
		}
		morpheusContext.services.referenceData.bulkCreate(adds)
	}

	protected removeMissingAvailabilityZones(List<ReferenceDataSyncProjection> removeList) {
		log.debug "removeMissingSubnets: ${removeList?.size()}"
		morpheusContext.services.referenceData.bulkRemove(removeList)
	}
}
