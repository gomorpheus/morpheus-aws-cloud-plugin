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

	public NetworkInterfaceSync(AWSPlugin plugin, Cloud cloud) {
		this.plugin = plugin
		this.cloud = cloud
		this.morpheusContext = plugin.morpheusContext
	}

	def execute() {
		morpheusContext.cloud.region.listIdentityProjections(cloud.id).flatMap { region ->
			final String regionCode = region.externalId
			def amazonClient = AmazonComputeUtility.getAmazonClient(cloud,false, region.externalId)
			def apiList = AmazonComputeUtility.listNetworkInterfaces([amazonClient: amazonClient],[:])
			if(apiList.success) {
				Observable<AccountResourceIdentityProjection> domainRecords = morpheusContext.cloud.resource.listIdentityProjections(cloud.id,'aws.cloudFormation.ec2.networkInterface',regionCode)
				SyncTask<AccountResourceIdentityProjection, NetworkInterface, AccountResource> syncTask = new SyncTask<>(domainRecords, apiList.networkInterfaces as Collection<NetworkInterface>)
				return syncTask.addMatchFunction { AccountResourceIdentityProjection domainObject, NetworkInterface data ->
					domainObject.externalId == data.networkInterfaceId
				}.onDelete { removeItems ->
					removeMissingResources(removeItems)
				}.onUpdate { List<SyncTask.UpdateItem<AccountResource, NetworkInterface>> updateItems ->
					updateMatchedNetworkInterfaces(updateItems, region)
				}.onAdd { itemsToAdd ->
					addMissingNetworkInterface(itemsToAdd, region)
				}.withLoadObjectDetailsFromFinder { List<SyncTask.UpdateItemDto<AccountResourceIdentityProjection, NetworkInterface>> updateItems ->
					return morpheusContext.cloud.resource.listById(updateItems.collect { it.existingItem.id } as List<Long>)
				}.observe()
			} else {
				log.error("Error Caching Network Interfaces for Region: {} - {}", regionCode, apiList.msg)
				return Single.just(false).toObservable() //ignore invalid region
			}
		}.blockingSubscribe()
	}

	protected String getCategory() {
		return "amazon.ec2.network.interfaces.${cloud.id}"
	}

	protected void addMissingNetworkInterface(Collection<NetworkInterface> addList, ComputeZoneRegionIdentityProjection region) {
		def adds = []
		for(NetworkInterface cloudItem in addList) {
			def name = cloudItem.networkInterfaceId
			adds << new AccountResource(
				owner:cloud.account, category:getCategory(), code:(getCategory() + '.' + cloudItem.networkInterfaceId),
				externalId:cloudItem.networkInterfaceId, type:new AccountResourceType(code: 'aws.cloudFormation.ec2.transitGateway'),
				resourceType:'NetworkInterface', name: name, displayName: name, cloudId: cloud.id, cloudName: cloud.name,
				region: new ComputeZoneRegion(id: region.id)
			)
		}
		morpheusContext.cloud.resource.create(adds).blockingGet()
	}

	protected void updateMatchedNetworkInterfaces(List<SyncTask.UpdateItem<AccountResource, NetworkInterface>> updateList, ComputeZoneRegionIdentityProjection region) {
		def updates = []
		for(update in updateList) {
			def existingItem = update.existingItem
			Boolean save = false

			if(existingItem.region?.id != region.id) {
				existingItem.region = new ComputeZoneRegion(id: region.id)
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
}
