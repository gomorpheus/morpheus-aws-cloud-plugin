package com.morpheusdata.aws.sync

import com.amazonaws.services.ec2.model.Vpc
import com.morpheusdata.aws.AWSPlugin
import com.morpheusdata.aws.utils.AmazonComputeUtility
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.CloudPool
import com.morpheusdata.model.projection.CloudPoolIdentity
import groovy.util.logging.Slf4j
import io.reactivex.Observable
import io.reactivex.Single

/**
 * Sync class for syncing VPCs within an AWS Cloud account
 * This sync system first iterates over a list of regions to sync for using the region list
 */
@Slf4j
class VPCSync {
	private Cloud cloud
	private MorpheusContext morpheusContext
	private AWSPlugin plugin

	public VPCSync(AWSPlugin plugin, Cloud cloud) {
		this.plugin = plugin
		this.cloud = cloud
		this.morpheusContext = plugin.morpheusContext
	}

	def execute() {
		try {
			morpheusContext.async.cloud.region.listIdentityProjections(cloud.id).concatMap {
				final String regionCode = it.externalId
				def amazonClient = plugin.getAmazonClient(cloud,false,it.externalId)
				def vpcResults = AmazonComputeUtility.listVpcs([amazonClient: amazonClient])
				if(vpcResults.success) {
					Observable<CloudPoolIdentity> domainRecords = morpheusContext.async.cloud.pool.listIdentityProjections(cloud.id, null, regionCode)
					SyncTask<CloudPoolIdentity, Vpc, CloudPool> syncTask = new SyncTask<>(domainRecords, vpcResults.vpcList as Collection<Vpc>)
					return syncTask.addMatchFunction { CloudPoolIdentity domainObject, Vpc data ->
						domainObject.externalId == data.getVpcId()
					}.onDelete { removeItems ->
						removeMissingResourcePools(removeItems)
					}.onUpdate { List<SyncTask.UpdateItem<CloudPool, Vpc>> updateItems ->
						updateMatchedVpcs(updateItems,regionCode)
					}.onAdd { itemsToAdd ->
						addMissingVpcs(itemsToAdd, regionCode)
					}.withLoadObjectDetailsFromFinder { List<SyncTask.UpdateItemDto<CloudPoolIdentity, Vpc>> updateItems ->
						return morpheusContext.async.cloud.pool.listById(updateItems.collect { it.existingItem.id } as List<Long>)
					}.observe()
				} else {
					log.error("Error Caching VPCs for Region: {} - {}",regionCode,vpcResults.msg)
					return Single.just(false).toObservable() //ignore invalid region
				}
			}.blockingSubscribe()
		} catch(Exception ex) {
			log.error("VPCSync error: {}", ex, ex)
		}
	}

	protected void addMissingVpcs(Collection<Vpc> addList, String region) {
		def adds = []
		for(Vpc cloudItem in addList) {
			def tags = cloudItem.getTags()
			def nameTag = tags.find{ it.getKey() == 'Name' }
			def name = nameTag?.value ?: cloudItem.getVpcId()
			def poolConfig = [owner:[id:cloud.owner.id], type:'vpc', name:name, displayName:"${name} (${region})", description:"${name} - ${cloudItem.getVpcId()} - ${cloudItem.getCidrBlock()}",
							  externalId:cloudItem.getVpcId(), refType:'ComputeZone', regionCode: region, refId:cloud.id, cloud:[id:cloud.id], category:"aws.vpc.${cloud.id}",
							  code:"aws.vpc.${cloud.id}.${cloudItem.getVpcId()}"]
			def add = new CloudPool(poolConfig)
			add.setConfigProperty('cidrBlock',cloudItem.getCidrBlock())
			add.setConfigProperty('tenancy',cloudItem.getInstanceTenancy())
			adds << add

		}
		if(adds) {
			morpheusContext.async.cloud.pool.bulkCreate(adds).blockingGet()
		}
	}

	protected void updateMatchedVpcs(List<SyncTask.UpdateItem<CloudPool, Vpc>> updateList, String region) {
		def updates = []

		for(update in updateList) {
			def masterItem = update.masterItem
			def existing = update.existingItem
			Boolean save = false

			def tags = masterItem.getTags()
			def nameTag = tags.find { it.getKey() == 'Name' }
			def name = nameTag?.value ?: masterItem.getVpcId()
			def cidr = masterItem.getCidrBlock()
			def tenancy = masterItem.getInstanceTenancy()
			def description = "${name} - ${masterItem.getVpcId()} - ${masterItem.getCidrBlock()}"
			if(name != existing.name) {
				existing.name = name
				save = true
			}
			if(region && existing.regionCode != region) {
				existing.regionCode = region
				save = true
			}
			if(existing.getConfigProperty('cidrBlock') != cidr) {
				existing.setConfigProperty('cidrBlock', cidr)
				save = true
			}
			if(existing.getConfigProperty('tenancy') != tenancy) {
				existing.setConfigProperty('tenancy', tenancy)
				save = true
			}
			if(description != existing.description) {
				existing.description = description
				save = true
			}
			if(existing.type != 'vpc') {
				existing.type = 'vpc'
				save = true

			}
			if(save) {
				updates << existing
			}
		}
		if(updates) {
			morpheusContext.async.cloud.pool.save(updates).blockingGet()
		}
	}

	protected removeMissingResourcePools(List<CloudPoolIdentity> removeList) {
		log.debug "removeMissingResourcePools: ${removeList?.size()}"
		morpheusContext.async.cloud.pool.remove(removeList).blockingGet()
	}
}