package com.morpheusdata.aws.sync

import com.amazonaws.services.ec2.model.Region
import com.amazonaws.services.ec2.model.Vpc
import com.morpheusdata.aws.AWSPlugin
import com.morpheusdata.aws.utils.AmazonComputeUtility
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeZonePool
import com.morpheusdata.model.ComputeZoneRegion
import com.morpheusdata.model.NetworkRouter
import com.morpheusdata.model.NetworkRouterType
import com.morpheusdata.model.projection.ComputeZonePoolIdentityProjection
import com.morpheusdata.model.projection.ComputeZoneRegionIdentityProjection
import com.morpheusdata.model.projection.NetworkRouterIdentityProjection
import groovy.util.logging.Slf4j
import io.reactivex.Observable
import io.reactivex.Single

/**
 * Sync class for syncing VPCs within an AWS Cloud account
 * This sync system first iterates over a list of regions to sync for using the region list
 */
@Slf4j
class VPCRouterSync {
	private Cloud cloud
	private MorpheusContext morpheusContext
	private AWSPlugin plugin


	public VPCRouterSync(AWSPlugin plugin, Cloud cloud) {
		this.plugin = plugin
		this.cloud = cloud
		this.morpheusContext = plugin.morpheusContext
	}

	def execute() {
		List<ComputeZonePoolIdentityProjection> resourcePools = morpheusContext.cloud.pool.listSyncProjections(cloud.id,null).toList().blockingGet()
		Observable<NetworkRouterIdentityProjection> vpcRouters = morpheusContext.network.router.listIdentityProjections(cloud.id,'amazonVpcRouter')
		SyncTask<NetworkRouterIdentityProjection, ComputeZonePoolIdentityProjection, NetworkRouter> syncTask = new SyncTask<>(vpcRouters, resourcePools)
		syncTask.addMatchFunction { NetworkRouterIdentityProjection domainObject, ComputeZonePoolIdentityProjection data ->
			domainObject.refType == 'ComputeZonePool' && domainObject.refId == data.id
		}.onDelete { removeItems ->
			removeMissingRouters(removeItems)
		}.onUpdate { List<SyncTask.UpdateItem<ComputeZoneRegion, Region>> updateItems ->
			updateMatchedVpcRouters(updateItems)
		}.onAdd { itemsToAdd ->
			addMissingVPCRouters(itemsToAdd)
		}.withLoadObjectDetailsFromFinder { List<SyncTask.UpdateItemDto<NetworkRouterIdentityProjection, NetworkRouter>> updateItems ->
			morpheusContext.network.router.listById(updateItems.collect { it.existingItem.id } as List<Long>)
		}.start()
	}


	protected void addMissingVPCRouters(Collection<ComputeZonePoolIdentityProjection> addList) {
		def adds = []
		Collection<ComputeZonePool> pools = morpheusContext.cloud.pool.listById(addList.collect{it.id}).toList().blockingGet()
		for(ComputeZonePoolIdentityProjection resourcePool in pools) {
			def routerConfig = [
					owner        : cloud.owner,
					category     : "aws.router.${cloud.id}",
					code         : "aws.router.${cloud.id}.${resourcePool.id}",
					type         : new NetworkRouterType(code:'amazonVpcRouter'),
					name         : resourcePool.name,
					networkServer: cloud.networkServer,
					cloud        : cloud,
					refId        : resourcePool.id,
					refType      : 'ComputeZonePool'
			]
			NetworkRouter router = new NetworkRouter(routerConfig)
			adds << router

		}
		if(adds) {
			morpheusContext.network.router.create(adds).blockingGet()
		}
	}

	protected void updateMatchedVpcRouters(List<SyncTask.UpdateItem<NetworkRouter, ComputeZonePoolIdentityProjection>> updateList) {
		def updates = []

		for(update in updateList) {
			def masterItem = update.masterItem
			def existing = update.existingItem
			Boolean save = false
			if(existing.name != masterItem.name) {
				existing.name = masterItem.name
				save = true
			}
			if(save) {
				updates << existing
			}
		}
		if(updates) {
			morpheusContext.network.router.save(updates).blockingGet()
		}
	}

	protected removeMissingRouters(List<NetworkRouterIdentityProjection> removeList) {
		morpheusContext.network.router.remove(removeList).blockingGet()
	}
}