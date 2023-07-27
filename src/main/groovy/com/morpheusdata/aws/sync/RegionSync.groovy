package com.morpheusdata.aws.sync

import com.amazonaws.services.ec2.model.Region
import com.morpheusdata.aws.AWSPlugin
import com.morpheusdata.aws.utils.AmazonComputeUtility
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeZoneRegion
import com.morpheusdata.model.projection.ComputeZoneRegionIdentityProjection
import groovy.util.logging.Slf4j
import io.reactivex.Observable

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
		//TODO: what is the default region for listing regions
		def amazonClient = AmazonComputeUtility.getAmazonClient(cloud,false,null)
		def regionResults = AmazonComputeUtility.listRegions([amazonClient: amazonClient])
		if(regionResults.success) {
			if(cloud.regionCode) { //cloud is scoped to a particular region already
				String region = AmazonComputeUtility.getAmazonEndpointRegion(cloud.regionCode)
				regionResults.regionList = regionResults.regionList.findAll{it.getRegionName() == region}
			}
			Observable<ComputeZoneRegionIdentityProjection> domainRecords = morpheusContext.cloud.region.listIdentityProjections(cloud.id)
			SyncTask<ComputeZoneRegionIdentityProjection, Region, ComputeZoneRegion> syncTask = new SyncTask<>(domainRecords, regionResults.regionList as Collection<Region>)
			syncTask.addMatchFunction { ComputeZoneRegionIdentityProjection domainObject, Region data ->
				domainObject.externalId == data.getRegionName()
			}.onDelete { removeItems ->
				removeMissingRegions(removeItems)
			}.onUpdate { List<SyncTask.UpdateItem<ComputeZoneRegion, Region>> updateItems ->
				// Nothing to do
			}.onAdd { itemsToAdd ->
				addMissingRegions(itemsToAdd)
			}.withLoadObjectDetailsFromFinder { List<SyncTask.UpdateItemDto<ComputeZoneRegionIdentityProjection, Region>> updateItems ->
				morpheusContext.cloud.region.listById(updateItems.collect { it.existingItem.id } as List<Long>)
			}.start()

			//upload missing key pairs
			ensureKeyPairs()
		}
	}

	protected void addMissingRegions(Collection<Region> addList) {
		def adds = []
		for(cloudItem in addList) {
			def name = cloudItem.getRegionName()
			def add = new ComputeZoneRegion(cloud: cloud, code: name, name: name, externalId: name, regionCode: name,zoneCode: name,internalId: cloudItem.getEndpoint())
			adds << add
		}
		if(adds) {
			morpheusContext.cloud.region.create(adds).blockingGet()
		}
	}

	protected removeMissingRegions(List removeList) {
		morpheusContext.cloud.region.remove(removeList).blockingGet()
	}

	protected ensureKeyPairs() {
		def save
		def keyPair = morpheusContext.cloud.findOrGenerateKeyPair(cloud.account).blockingGet()

		morpheusContext.cloud.region.listIdentityProjections(cloud.id).blockingSubscribe { region ->
			def amazonClient = plugin.getAmazonClient(cloud, false, region.externalId)
			def keyLocationId = "amazon-${cloud.id}-${region.externalId}".toString()
			def keyResults = AmazonComputeUtility.uploadKeypair(
				[key: keyPair, account: cloud.account, zone: cloud, keyName: keyPair.getConfigProperty(keyLocationId), amazonClient:amazonClient]
			)
			if(keyResults.success) {
				if (keyResults.uploaded) {
					keyPair.setConfigProperty(keyLocationId, keyResults.keyName)
					save = true
				}
			} else {
				log.error "unable to upload keypair"
			}
		}

		if(save) {
			morpheusContext.keyPair.save([keyPair]).blockingGet()
		}
	}
}
