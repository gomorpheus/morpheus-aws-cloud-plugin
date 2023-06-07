package com.morpheusdata.aws.sync

import com.amazonaws.services.ec2.model.NetworkInterface
import com.morpheusdata.aws.AWSPlugin
import com.morpheusdata.aws.utils.AmazonComputeUtility
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.AccountResource
import com.morpheusdata.model.AccountResourceType
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeZoneRegion
import com.morpheusdata.model.projection.AccountResourceIdentityProjection
import com.morpheusdata.model.projection.ComputeZoneRegionIdentityProjection
import io.reactivex.Observable
import io.reactivex.Single

class NetworkInterfaceSync extends InternalResourceSync {
	private Map<String, ComputeZoneRegionIdentityProjection> regions

	public NetworkInterfaceSync(AWSPlugin plugin, Cloud cloud) {
		this.plugin = plugin
		this.cloud = cloud
		this.morpheusContext = plugin.morpheusContext
	}

	def execute() {
		morpheusContext.cloud.region.listIdentityProjections(cloud.id).flatMap {
			final String regionCode = it.externalId
			def amazonClient = AmazonComputeUtility.getAmazonClient(cloud,false,it.externalId)
			def apiList = AmazonComputeUtility.listNetworkInterfaces([amazonClient: amazonClient],[:])
			if(apiList.success) {
				Observable<AccountResourceIdentityProjection> domainRecords = morpheusContext.cloud.resource.listIdentityProjections(cloud.id,'aws.cloudFormation.ec2.networkInterface',regionCode)
				SyncTask<AccountResourceIdentityProjection, NetworkInterface, AccountResource> syncTask = new SyncTask<>(domainRecords, apiList.networkInterfaces as Collection<NetworkInterface>)
				return syncTask.addMatchFunction { AccountResourceIdentityProjection domainObject, NetworkInterface data ->
					domainObject.externalId == data.networkInterfaceId
				}.onDelete { removeItems ->
					removeMissingResources(removeItems)
				}.onUpdate { List<SyncTask.UpdateItem<AccountResource, NetworkInterface>> updateItems ->
					updateMatchedNetworkInterfaces(updateItems, regionCode)
				}.onAdd { itemsToAdd ->
					addMissingNetworkInterface(itemsToAdd, regionCode)
				}.withLoadObjectDetailsFromFinder { List<SyncTask.UpdateItemDto<AccountResourceIdentityProjection, NetworkInterface>> updateItems ->
					return morpheusContext.cloud.resource.listById(updateItems.collect { it.existingItem.id } as List<Long>)
				}.observe()
			} else {
				log.error("Error Caching Network Interfaces for Region: {} - {}",regionCode,apiList.msg)
				return Single.just(false).toObservable() //ignore invalid region
			}
		}.blockingSubscribe()
	}

	protected String getCategory() {
		return "amazon.ec2.network.interfaces.${cloud.id}"
	}

	protected void addMissingNetworkInterface(Collection<NetworkInterface> addList, String regionCode) {
		def adds = []
		def region = allRegions[regionCode]

		if(!(region instanceof ComputeZoneRegion)) {
			region = morpheusContext.cloud.region.listById([region.id]).blockingFirst()
			allRegions[regionCode] = region
		}

		for(NetworkInterface cloudItem in addList) {
			def name = cloudItem.networkInterfaceId
			def addConfig = [
				owner:cloud.account, category:getCategory(), code:(getCategory() + '.' + cloudItem.networkInterfaceId),
				externalId:cloudItem.networkInterfaceId, region:region, type:new AccountResourceType(code: 'aws.cloudFormation.ec2.transitGateway'),
				resourceType:'NetworkInterface', name: name, displayName: name, cloudId: cloud.id, cloudName: cloud.name
			]
			AccountResource newResource = new AccountResource(addConfig)
			newResource.region = new ComputeZoneRegion(regionCode: region)
			adds << newResource
		}
		if(adds) {
			morpheusContext.cloud.resource.create(adds).blockingGet()
		}
	}

	protected void updateMatchedNetworkInterfaces(List<SyncTask.UpdateItem<AccountResource, NetworkInterface>> updateList, String region) {
		def updates = []
		for(update in updateList) {
			def existingItem = update.existingItem
			Boolean save = false

			if(existingItem.region?.code != region) {
				existingItem.region = new ComputeZoneRegion(regionCode: region)
				save = true
			}

			if(save) {
				updates << existingItem
			}
		}
		if(updates) {
			morpheusContext.cloud.resource.save(updates).blockingGet()
		}
	}

	private Map<String, ComputeZoneRegionIdentityProjection> getAllRegions() {
		regions ?: (regions = morpheusContext.cloud.region.listIdentityProjections(cloud.id).toMap {it.externalId}.blockingGet())
	}
}
