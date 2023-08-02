package com.morpheusdata.aws.sync

import com.amazonaws.services.ec2.model.TransitGateway
import com.morpheusdata.aws.AWSPlugin
import com.morpheusdata.aws.utils.AmazonComputeUtility
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.AccountResource
import com.morpheusdata.model.AccountResourceType
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeZoneRegion
import com.morpheusdata.model.projection.AccountResourceIdentityProjection
import com.morpheusdata.model.projection.ComputeZoneRegionIdentityProjection
import groovy.util.logging.Slf4j
import io.reactivex.Observable
import io.reactivex.Single

@Slf4j
class TransitGatewaySync extends InternalResourceSync{
	public TransitGatewaySync(AWSPlugin plugin, Cloud cloud) {
		this.plugin = plugin
		this.cloud = cloud
		this.morpheusContext = plugin.morpheusContext
	}

	def execute() {
		morpheusContext.async.cloud.region.listIdentityProjections(cloud.id).flatMap {
			final String regionCode = it.externalId
			def amazonClient = plugin.getAmazonClient(cloud,false,it.externalId)
			def apiList = AmazonComputeUtility.listTransitGateways([amazonClient: amazonClient],[:])
			if(apiList.success) {
				Observable<AccountResourceIdentityProjection> domainRecords = morpheusContext.async.cloud.resource.listIdentityProjections(cloud.id,'aws.cloudFormation.ec2.transitGateway',regionCode)
				SyncTask<AccountResourceIdentityProjection, TransitGateway, AccountResource> syncTask = new SyncTask<>(domainRecords, apiList.transitGateways as Collection<TransitGateway>)
				return syncTask.addMatchFunction { AccountResourceIdentityProjection domainObject, TransitGateway data ->
					domainObject.externalId == data.transitGatewayId
				}.onDelete { removeItems ->
					removeMissingResources(removeItems)
				}.onUpdate { List<SyncTask.UpdateItem<AccountResource, TransitGateway>> updateItems ->
					updateMatchedTransitGateways(updateItems,regionCode)
				}.onAdd { itemsToAdd ->
					addMissingTransitGateway(itemsToAdd, regionCode)

				}.withLoadObjectDetailsFromFinder { List<SyncTask.UpdateItemDto<AccountResourceIdentityProjection, TransitGateway>> updateItems ->
					return morpheusContext.async.cloud.resource.listById(updateItems.collect { it.existingItem.id } as List<Long>)
				}.observe()
			} else {
				log.error("Error Caching Transit Gateways for Region: {} - {}",regionCode,apiList.msg)
				return Single.just(false).toObservable() //ignore invalid region
			}
		}.blockingSubscribe()
	}

	protected String getCategory() {
		return "amazon.ec2.transit.gateways.${cloud.id}"
	}

	protected void addMissingTransitGateway(Collection<TransitGateway> addList, ComputeZoneRegionIdentityProjection region) {
		def adds = []

		for(TransitGateway cloudItem in addList) {
			def name = cloudItem.tags?.find { it.key == 'Name' }?.value ?: cloudItem.transitGatewayId
			adds << new AccountResource(
				owner:cloud.account, category:getCategory(), code:(getCategory() + '.' + cloudItem.transitGatewayId),
				externalId:cloudItem.transitGatewayId, zoneId:cloud.id, type:new AccountResourceType(code: 'aws.cloudFormation.ec2.transitGateway'), resourceType:'TransitGateway',
				zoneName: cloud.name, name: name, displayName: name, region: new ComputeZoneRegion(id: region.id)
			)
		}
		if(adds) {
			morpheusContext.async.cloud.resource.create(adds).blockingGet()
		}
	}

	protected void updateMatchedTransitGateways(List<SyncTask.UpdateItem<AccountResource, TransitGateway>> updateList, ComputeZoneRegionIdentityProjection region) {
		def updates = []
		for(update in updateList) {
			def masterItem = update.masterItem
			def existingItem = update.existingItem
			Boolean save = false
			def name
			def nameTag = masterItem.getTags()?.find{it.getKey() == 'Name'}
			name = nameTag?.value ?: masterItem.transitGatewayId
			if(existingItem.name != name) {
				existingItem.name = name
				save = true
			}
			if(existingItem.region?.id != region.id) {
				existingItem.region = new ComputeZoneRegion(id: region.id)
				save = true
			}


			if(save) {
				updates << existingItem
			}
		}
		if(updates) {
			morpheusContext.async.cloud.resource.save(updates).blockingGet()
		}
	}
}
