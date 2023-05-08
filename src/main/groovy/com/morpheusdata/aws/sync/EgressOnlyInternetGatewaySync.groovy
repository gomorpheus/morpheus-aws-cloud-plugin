package com.morpheusdata.aws.sync

import com.amazonaws.services.ec2.model.EgressOnlyInternetGateway
import com.morpheusdata.aws.AWSPlugin
import com.morpheusdata.aws.utils.AmazonComputeUtility
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.AccountResource
import com.morpheusdata.model.AccountResourceType
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeZoneRegion
import com.morpheusdata.model.projection.AccountResourceIdentityProjection
import groovy.util.logging.Slf4j
import io.reactivex.Observable
import io.reactivex.Single

@Slf4j
class EgressOnlyInternetGatewaySync extends InternalResourceSync {
	public EgressOnlyInternetGatewaySync(AWSPlugin plugin, Cloud cloud) {
		this.plugin = plugin
		this.cloud = cloud
		this.morpheusContext = plugin.morpheusContext
	}

	def execute() {
		morpheusContext.cloud.region.listIdentityProjections(cloud.id).flatMap {
			final String regionCode = it.externalId
			def amazonClient = AmazonComputeUtility.getAmazonClient(cloud,false,it.externalId)
			def apiList = AmazonComputeUtility.listEgressOnlyInternetGateways([amazonClient: amazonClient],[:])
			if(apiList.success) {
				Observable<AccountResourceIdentityProjection> domainRecords = morpheusContext.cloud.resource.listIdentityProjections(cloud.id,'aws.cloudFormation.ec2.egressOnlyInternetGateway',regionCode)
				SyncTask<AccountResourceIdentityProjection, EgressOnlyInternetGateway, AccountResource> syncTask = new SyncTask<>(domainRecords, apiList.egressOnlyInternetGateways as Collection<EgressOnlyInternetGateway>)
				return syncTask.addMatchFunction { AccountResourceIdentityProjection domainObject, EgressOnlyInternetGateway data ->
					domainObject.externalId == data.egressOnlyInternetGatewayId
				}.onDelete { removeItems ->
					removeMissingResources(removeItems)
				}.onUpdate { List<SyncTask.UpdateItem<AccountResource, EgressOnlyInternetGateway>> updateItems ->
					updateMatchedEgressOnlyInternetGateways(updateItems,regionCode)
				}.onAdd { itemsToAdd ->
					addMissingEgressOnlyInternetGateways(itemsToAdd, regionCode)

				}.withLoadObjectDetailsFromFinder { List<SyncTask.UpdateItemDto<AccountResourceIdentityProjection, EgressOnlyInternetGateway>> updateItems ->
					return morpheusContext.cloud.pool.listById(updateItems.collect { it.existingItem.id } as List<Long>)
				}.observe()
			} else {
				log.error("Error Caching Egress Only Internet Gateways for Region: {} - {}",regionCode,apiList.msg)
				return Single.just(false).toObservable() //ignore invalid region
			}
		}.blockingSubscribe()
	}

	protected String getCategory() {
		return "amazon.ec2.egress.only.internet.gateway.${cloud.id}"
	}

	protected void addMissingEgressOnlyInternetGateways(Collection<EgressOnlyInternetGateway> addList, String region) {
		def adds = []

		for(EgressOnlyInternetGateway cloudItem in addList) {
			def name = cloudItem.egressOnlyInternetGatewayId

			def addConfig = [owner     :cloud.account, category:getCategory(), code:(getCategory() + '.' + cloudItem.egressOnlyInternetGatewayId),
							 externalId:cloudItem.egressOnlyInternetGatewayId, zoneId:cloud.id, type:new AccountResourceType(code: 'aws.cloudFormation.ec2.egressOnlyInternetGateway'), resourceType:'EgressOnlyInternetGateway',
							 zoneName: cloud.name, name: name, displayName: name
			]
			AccountResource newResource = new AccountResource(addConfig)
			newResource.region = new ComputeZoneRegion(regionCode: region)
			adds << newResource
		}
		if(adds) {
			morpheusContext.cloud.resource.create(adds).blockingGet()
		}
	}

	protected void updateMatchedEgressOnlyInternetGateways(List<SyncTask.UpdateItem<AccountResource, EgressOnlyInternetGateway>> updateList, String region) {
		def updates = []
		for(update in updateList) {
			def masterItem = update.masterItem
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

}
