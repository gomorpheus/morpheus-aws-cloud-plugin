package com.morpheusdata.aws.sync

import com.amazonaws.services.ec2.model.Region
import com.morpheusdata.aws.AWSPlugin
import com.morpheusdata.aws.utils.AmazonComputeUtility
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.CloudRegion
import com.morpheusdata.model.projection.CloudRegionIdentity
import groovy.util.logging.Slf4j
import io.reactivex.rxjava3.core.Observable

@Slf4j
class RegionSync {
	private Cloud cloud
	private MorpheusContext morpheusContext
	private AWSPlugin plugin


	public RegionSync(AWSPlugin plugin, Cloud cloud) {
		this.plugin = plugin
		this.cloud = cloud
		this.morpheusContext = plugin.morpheusContext
	}

	def execute() {
		try {
			//TODO: what is the default region for listing regions
			def amazonClient = plugin.getAmazonClient(cloud,false,null)
			def regionResults = AmazonComputeUtility.listRegions([amazonClient: amazonClient])
			if(regionResults.success) {
				if(cloud.regionCode) { //cloud is scoped to a particular region already
					String region = AmazonComputeUtility.getAmazonEndpointRegion(cloud.regionCode)
					regionResults.regionList = regionResults.regionList.findAll{it.getRegionName() == region}
				}
				Observable<CloudRegionIdentity> domainRecords = morpheusContext.async.cloud.region.listIdentityProjections(cloud.id)
				SyncTask<CloudRegionIdentity, Region, CloudRegion> syncTask = new SyncTask<>(domainRecords, regionResults.regionList as Collection<Region>)
				syncTask.addMatchFunction { CloudRegionIdentity domainObject, Region data ->
					domainObject.externalId == data.getRegionName()
				}.onDelete { removeItems ->
					removeMissingRegions(removeItems)
				}.onUpdate { List<SyncTask.UpdateItem<CloudRegion, Region>> updateItems ->
					// Nothing to do
				}.onAdd { itemsToAdd ->
					addMissingRegions(itemsToAdd)
				}.withLoadObjectDetailsFromFinder { List<SyncTask.UpdateItemDto<CloudRegionIdentity, Region>> updateItems ->
					morpheusContext.async.cloud.region.listById(updateItems.collect { it.existingItem.id } as List<Long>)
				}.start()
			}
		} catch(Exception ex) {
			log.error("RegionSync error: {}", ex, ex)
		}
	}

	protected void addMissingRegions(Collection<Region> addList) {
		def adds = []
		for(cloudItem in addList) {
			def name = cloudItem.getRegionName()
			def add = new CloudRegion(cloud: cloud, account: cloud.account, code: name, name: name, externalId: name, regionCode: name, cloudCode: name, internalId: cloudItem.getEndpoint())
			adds << add
		}
		if(adds) {
			morpheusContext.async.cloud.region.bulkCreate(adds).blockingGet()
		}
	}

	protected removeMissingRegions(List removeList) {
		morpheusContext.async.cloud.region.remove(removeList).blockingGet()
	}
}
