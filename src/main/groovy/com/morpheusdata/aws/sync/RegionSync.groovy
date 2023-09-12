package com.morpheusdata.aws.sync

import com.amazonaws.services.ec2.model.Region
import com.morpheusdata.aws.AWSPlugin
import com.morpheusdata.aws.utils.AmazonComputeUtility
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Account
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.CloudRegion
import com.morpheusdata.model.ComputeZoneRegion
import com.morpheusdata.model.projection.CloudRegionIdentity
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
				addMissingRegions(itemsToAdd, this.@cloud.account)
			}.withLoadObjectDetailsFromFinder { List<SyncTask.UpdateItemDto<CloudRegionIdentity, Region>> updateItems ->
				morpheusContext.async.cloud.region.listById(updateItems.collect { it.existingItem.id } as List<Long>)
			}.start()

			//upload missing key pairs
			ensureKeyPairs()
		}
	}

	protected void addMissingRegions(Collection<Region> addList, Account account) {
		def adds = []
		for(cloudItem in addList) {
			def name = cloudItem.getRegionName()
			def add = new CloudRegion(cloud: cloud, account:account, code: name, name: name, externalId: name, regionCode: name,zoneCode: name,internalId: cloudItem.getEndpoint())
			adds << add
		}
		if(adds) {
			morpheusContext.async.cloud.region.create(adds).blockingGet()
		}
	}

	protected removeMissingRegions(List removeList) {
		morpheusContext.async.cloud.region.remove(removeList).blockingGet()
	}

	protected ensureKeyPairs() {
		def save
		def keyPair = morpheusContext.async.cloud.findOrGenerateKeyPair(cloud.account).blockingGet()

		morpheusContext.async.cloud.region.listIdentityProjections(cloud.id).blockingSubscribe { region ->
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
			morpheusContext.async.keyPair.save([keyPair]).blockingGet()
		}
	}
}
