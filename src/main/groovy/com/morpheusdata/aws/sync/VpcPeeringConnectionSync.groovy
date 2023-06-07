package com.morpheusdata.aws.sync

import com.amazonaws.services.ec2.model.VpcPeeringConnection
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
class VpcPeeringConnectionSync extends InternalResourceSync {
	private Map<String, ComputeZoneRegionIdentityProjection> regions

	public VpcPeeringConnectionSync(AWSPlugin plugin, Cloud cloud) {
		this.plugin = plugin
		this.cloud = cloud
		this.morpheusContext = plugin.morpheusContext
	}

	def execute() {
		morpheusContext.cloud.region.listIdentityProjections(cloud.id).flatMap {
			final String regionCode = it.externalId
			def amazonClient = AmazonComputeUtility.getAmazonClient(cloud,false,it.externalId)
			def apiList = AmazonComputeUtility.listVpcPeeringConnections([amazonClient: amazonClient],[:])
			if(apiList.success) {
				Observable<AccountResourceIdentityProjection> domainRecords = morpheusContext.cloud.resource.listIdentityProjections(cloud.id,'aws.cloudFormation.ec2.vpcPeeringConnection',regionCode)
				SyncTask<AccountResourceIdentityProjection, VpcPeeringConnection, AccountResource> syncTask = new SyncTask<>(domainRecords, apiList.vpcPeeringConnections as Collection<VpcPeeringConnection>)
				return syncTask.addMatchFunction { AccountResourceIdentityProjection domainObject, VpcPeeringConnection data ->
					domainObject.externalId == data.vpcPeeringConnectionId
				}.onDelete { removeItems ->
					removeMissingResources(removeItems)
				}.onUpdate { List<SyncTask.UpdateItem<AccountResource, VpcPeeringConnection>> updateItems ->
					updateMatchedVpcPeeringConnections(updateItems,regionCode)
				}.onAdd { itemsToAdd ->
					addMissingVpcPeeringConnection(itemsToAdd, regionCode)
				}.withLoadObjectDetailsFromFinder { List<SyncTask.UpdateItemDto<AccountResourceIdentityProjection, VpcPeeringConnection>> updateItems ->
					return morpheusContext.cloud.resource.listById(updateItems.collect { it.existingItem.id } as List<Long>)
				}.observe()
			} else {
				log.error("Error Caching VPC Peering Connections for Region: {} - {}",regionCode,apiList.msg)
				return Single.just(false).toObservable() //ignore invalid region
			}
		}.blockingSubscribe()
	}

	protected String getCategory() {
		return "amazon.ec2.vpc.peerings.${cloud.id}"
	}

	protected void addMissingVpcPeeringConnection(Collection<VpcPeeringConnection> addList, String regionCode) {
		def adds = []
		def region = allRegions[regionCode]

		if(!(region instanceof ComputeZoneRegion)) {
			region = morpheusContext.cloud.region.listById([region.id]).blockingFirst()
			allRegions[regionCode] = region
		}

		for(VpcPeeringConnection cloudItem in addList) {
			def name
			def nameTag = cloudItem.getTags()?.find{it.getKey() == 'Name'}
			name = nameTag?.value ?: cloudItem.vpcPeeringConnectionId
			def addConfig = [owner:cloud.account, category:getCategory(), code:(getCategory() + '.' + cloudItem.vpcPeeringConnectionId),
				externalId:cloudItem.vpcPeeringConnectionId, cloudId:cloud.id, type:new AccountResourceType(code: 'aws.cloudFormation.ec2.VpcPeeringConnection'), resourceType:'VpcPeeringConnection',
				cloudName: cloud.name, name: name, displayName: name, region: region
			]
			AccountResource newResource = new AccountResource(addConfig)
			newResource.region = new ComputeZoneRegion(regionCode: region)
			adds << newResource
		}
		if(adds) {
			morpheusContext.cloud.resource.create(adds).blockingGet()
		}
	}

	protected void updateMatchedVpcPeeringConnections(List<SyncTask.UpdateItem<AccountResource, VpcPeeringConnection>> updateList, String regionCode) {
		def updates = []
		def region = allRegions[regionCode]

		if(!(region instanceof ComputeZoneRegion)) {
			region = morpheusContext.cloud.region.listById([region.id]).blockingFirst()
			allRegions[regionCode] = region
		}

		for(update in updateList) {
			def masterItem = update.masterItem
			def existingItem = update.existingItem
			Boolean save = false
			def name
			def nameTag = masterItem.getTags()?.find{it.getKey() == 'Name'}
			name = nameTag?.value ?: masterItem.vpcPeeringConnectionId
			if(existingItem.name != name) {
				existingItem.name = name
				save = true
			}
			if(existingItem.region?.code != regionCode) {
				existingItem.region = region
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
