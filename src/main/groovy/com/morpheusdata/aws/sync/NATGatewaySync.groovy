package com.morpheusdata.aws.sync

import com.amazonaws.services.ec2.model.NatGateway
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
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single

@Slf4j
class NATGatewaySync extends InternalResourceSync {
	public NATGatewaySync(AWSPlugin plugin, Cloud cloud) {
		this.plugin = plugin
		this.cloud = cloud
		this.morpheusContext = plugin.morpheusContext
	}

	def execute() {
		try {
			morpheusContext.async.cloud.region.listIdentityProjectionsForRegionsWithCloudPools(cloud.id).concatMap { region ->
				final String regionCode = region.externalId
				def amazonClient = plugin.getAmazonClient(cloud,false, region.externalId)
				def apiList = AmazonComputeUtility.listNatGateways([amazonClient: amazonClient],[:])
				if(apiList.success) {
					Observable<AccountResourceIdentityProjection> domainRecords = morpheusContext.async.cloud.resource.listIdentityProjections(cloud.id,'aws.cloudFormation.ec2.natGateway',regionCode)
					SyncTask<AccountResourceIdentityProjection, NatGateway, AccountResource> syncTask = new SyncTask<>(domainRecords, apiList.natGateways as Collection<NatGateway>)
					return syncTask.addMatchFunction { AccountResourceIdentityProjection domainObject, NatGateway data ->
						domainObject.externalId == data.natGatewayId
					}.onDelete { removeItems ->
						removeMissingResources(removeItems)
					}.onUpdate { List<SyncTask.UpdateItem<AccountResource, NatGateway>> updateItems ->
						updateMatchedNATGateways(updateItems, region)
					}.onAdd { itemsToAdd ->
						addMissingNATGateway(itemsToAdd, region)

					}.withLoadObjectDetailsFromFinder { List<SyncTask.UpdateItemDto<AccountResourceIdentityProjection, NatGateway>> updateItems ->
						return morpheusContext.async.cloud.resource.listById(updateItems.collect { it.existingItem.id } as List<Long>)
					}.observe()
				} else {
					log.error("Error Caching NAT Gateways for Region: {} - {}", regionCode,apiList.msg)
					return Single.just(false).toObservable() //ignore invalid region
				}
			}.blockingSubscribe()
		} catch(Exception ex) {
			log.error("NATGatewaySync error: {}", ex, ex)
		}
	}

	protected String getCategory() {
		return "amazon.ec2.nat.gateway.${cloud.id}"
	}

	protected void addMissingNATGateway(Collection<NatGateway> addList, CloudRegionIdentity region) {
		def adds = []

		for(NatGateway cloudItem in addList) {
			def name = cloudItem.tags?.find { it.key == 'Name' }?.value ?: cloudItem.natGatewayId
			adds << new AccountResource(
				owner:cloud.account, category:getCategory(), code:(getCategory() + '.' + cloudItem.natGatewayId),
				externalId:cloudItem.natGatewayId, cloudId:cloud.id, type:new AccountResourceType(code: 'aws.cloudFormation.ec2.natGateway'), resourceType:'NatGateway',
				cloudName: cloud.name, name: name, displayName: name, region: new CloudRegion(id: region.id)
			)
		}
		if(adds) {
			morpheusContext.async.cloud.resource.bulkCreate(adds).blockingGet()
		}

	}

	protected void updateMatchedNATGateways(List<SyncTask.UpdateItem<AccountResource, NatGateway>> updateList, CloudRegionIdentity region) {
		def updates = []
		for(update in updateList) {
			def masterItem = update.masterItem
			def existingItem = update.existingItem
			Boolean save = false
			def name
			def nameTag = masterItem.getTags()?.find{it.getKey() == 'Name'}
			name = nameTag?.value ?: masterItem.natGatewayId
			if(existingItem.name != name) {
				existingItem.name = name
				save = true
			}
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
