package com.morpheusdata.aws.sync

import com.amazonaws.services.ec2.model.VpcPeeringConnection
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
class VpcPeeringConnectionSync extends InternalResourceSync {
	public VpcPeeringConnectionSync(AWSPlugin plugin, Cloud cloud) {
		this.plugin = plugin
		this.cloud = cloud
		this.morpheusContext = plugin.morpheusContext
	}

	def execute() {
		try {
			log.debug("VpcPeeringConnectionSync: starting sync")
			morpheusContext.async.cloud.region.listIdentityProjections(cloud.id).concatMap { region ->
				def amazonClient = plugin.getAmazonClient(cloud,false, region.externalId)
				def apiList = AmazonComputeUtility.listVpcPeeringConnections([amazonClient: amazonClient],[:])
				if(apiList.success) {
					Observable<AccountResourceIdentityProjection> domainRecords = morpheusContext.async.cloud.resource.listIdentityProjections(cloud.id,'aws.cloudFormation.ec2.vpcPeeringConnection', region.externalId)
					SyncTask<AccountResourceIdentityProjection, VpcPeeringConnection, AccountResource> syncTask = new SyncTask<>(domainRecords, apiList.vpcPeeringConnections as Collection<VpcPeeringConnection>)
					return syncTask.addMatchFunction { AccountResourceIdentityProjection domainObject, VpcPeeringConnection data ->
						domainObject.externalId == data.vpcPeeringConnectionId
					}.onDelete { removeItems ->
						removeMissingResources(removeItems)
					}.onUpdate { List<SyncTask.UpdateItem<AccountResource, VpcPeeringConnection>> updateItems ->
						updateMatchedVpcPeeringConnections(updateItems, region)
					}.onAdd { itemsToAdd ->
						addMissingVpcPeeringConnection(itemsToAdd, region)
					}.withLoadObjectDetailsFromFinder { List<SyncTask.UpdateItemDto<AccountResourceIdentityProjection, VpcPeeringConnection>> updateItems ->
						return morpheusContext.async.cloud.resource.listById(updateItems.collect { it.existingItem.id } as List<Long>)
					}.observe()
				} else {
					log.error("Error Caching VPC Peering Connections for Region: {} - {}", region.externalId, apiList.msg)
					return Single.just(false).toObservable() //ignore invalid region
				}
			}.blockingSubscribe()
		} catch(Exception ex) {
			log.error("VpcPeeringConnectionSync error: {}", ex, ex)
		}

	}

	protected String getCategory() {
		return "amazon.ec2.vpc.peerings.${cloud.id}"
	}

	protected void addMissingVpcPeeringConnection(Collection<VpcPeeringConnection> addList, CloudRegionIdentity region) {
		def adds = []
		for(VpcPeeringConnection cloudItem in addList) {
			def name = cloudItem.tags?.find{it.key == 'Name'}?.value ?: cloudItem.vpcPeeringConnectionId
			adds << new AccountResource(
				owner:cloud.account, category:getCategory(), code:(getCategory() + '.' + cloudItem.vpcPeeringConnectionId),
				externalId:cloudItem.vpcPeeringConnectionId, cloudId:cloud.id, type: new AccountResourceType(code: 'aws.cloudFormation.ec2.vpcPeeringConnection'),
				resourceType:'VpcPeeringConnection', cloudName: cloud.name, name: name, displayName: name, region: new CloudRegion(id: region.id)
			)
		}
		if(adds) {
			morpheusContext.async.cloud.resource.create(adds).blockingGet()
		}

	}

	protected void updateMatchedVpcPeeringConnections(List<SyncTask.UpdateItem<AccountResource, VpcPeeringConnection>> updateList, CloudRegionIdentity region) {
		def updates = []
		for(update in updateList) {
			def masterItem = update.masterItem
			def existingItem = update.existingItem
			Boolean save = false
			def name = masterItem.tags?.find{it.key == 'Name'}?.value ?: masterItem.vpcPeeringConnectionId
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
