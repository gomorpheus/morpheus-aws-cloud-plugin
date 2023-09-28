package com.morpheusdata.aws.sync

import com.amazonaws.services.ec2.model.EgressOnlyInternetGateway
import com.morpheusdata.aws.AWSPlugin
import com.morpheusdata.aws.utils.AmazonComputeUtility
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.AccountResource
import com.morpheusdata.model.AccountResourceType
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.CloudRegion
import com.morpheusdata.model.projection.AccountResourceIdentityProjection
import com.morpheusdata.model.projection.CloudRegionIdentity
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
		morpheusContext.async.cloud.region.listIdentityProjections(cloud.id).flatMap { region ->
			final String regionCode = region.externalId
			def amazonClient = plugin.getAmazonClient(cloud,false, region.externalId)
			def apiList = AmazonComputeUtility.listEgressOnlyInternetGateways([amazonClient: amazonClient],[:])
			if(apiList.success) {
				Observable<AccountResourceIdentityProjection> domainRecords = morpheusContext.async.cloud.resource.listIdentityProjections(cloud.id,'aws.cloudFormation.ec2.egressOnlyInternetGateway',regionCode)
				SyncTask<AccountResourceIdentityProjection, EgressOnlyInternetGateway, AccountResource> syncTask = new SyncTask<>(domainRecords, apiList.egressOnlyInternetGateways as Collection<EgressOnlyInternetGateway>)
				return syncTask.addMatchFunction { AccountResourceIdentityProjection domainObject, EgressOnlyInternetGateway data ->
					domainObject.externalId == data.egressOnlyInternetGatewayId
				}.onDelete { removeItems ->
					removeMissingResources(removeItems)
				}.onUpdate { List<SyncTask.UpdateItem<AccountResource, EgressOnlyInternetGateway>> updateItems ->
					updateMatchedEgressOnlyInternetGateways(updateItems, region)
				}.onAdd { itemsToAdd ->
					addMissingEgressOnlyInternetGateways(itemsToAdd, region)

				}.withLoadObjectDetailsFromFinder { List<SyncTask.UpdateItemDto<AccountResourceIdentityProjection, EgressOnlyInternetGateway>> updateItems ->
					return morpheusContext.async.cloud.pool.listById(updateItems.collect { it.existingItem.id } as List<Long>)
				}.observe()
			} else {
				log.error("Error Caching Egress Only Internet Gateways for Region: {} - {}", regionCode,apiList.msg)
				return Single.just(false).toObservable() //ignore invalid region
			}
		}.blockingSubscribe()
	}

	protected String getCategory() {
		return "amazon.ec2.egress.only.internet.gateway.${cloud.id}"
	}

	protected void addMissingEgressOnlyInternetGateways(Collection<EgressOnlyInternetGateway> addList, CloudRegionIdentity region) {
		def adds = []

		for(EgressOnlyInternetGateway cloudItem in addList) {
			adds << new AccountResource(
				owner:cloud.account, category:getCategory(), code:(getCategory() + '.' + cloudItem.egressOnlyInternetGatewayId),
				externalId:cloudItem.egressOnlyInternetGatewayId, cloudId:cloud.id, type:new AccountResourceType(code: 'aws.cloudFormation.ec2.egressOnlyInternetGateway'), resourceType:'EgressOnlyInternetGateway',
				zoneName: cloud.name, name: cloudItem.egressOnlyInternetGatewayId, displayName: cloudItem.egressOnlyInternetGatewayId,
				region: new CloudRegion(id: region.id)
			)
		}
		morpheusContext.async.cloud.resource.create(adds).blockingGet()
	}

	protected void updateMatchedEgressOnlyInternetGateways(List<SyncTask.UpdateItem<AccountResource, EgressOnlyInternetGateway>> updateList, CloudRegionIdentity region) {
		def updates = []
		for(update in updateList) {
			def masterItem = update.masterItem
			def existingItem = update.existingItem
			Boolean save = false

			if(existingItem.region?.id != region.id) {
				existingItem.region = new CloudRegion(id: region.id)
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
