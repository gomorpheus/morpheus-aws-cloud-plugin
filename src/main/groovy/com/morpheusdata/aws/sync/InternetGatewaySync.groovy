package com.morpheusdata.aws.sync

import com.amazonaws.services.ec2.model.InternetGateway
import com.morpheusdata.aws.AWSPlugin
import com.morpheusdata.aws.utils.AmazonComputeUtility
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.NetworkRouter
import com.morpheusdata.model.NetworkRouterType
import com.morpheusdata.model.projection.CloudPoolIdentity
import com.morpheusdata.model.projection.NetworkRouterIdentityProjection
import groovy.util.logging.Slf4j
import io.reactivex.Observable
import io.reactivex.Single

/**
 * Sync class for syncing InternetGateways within an AWS Cloud account
 * This sync system first iterates over a list of regions to sync for using the region list
 */
@Slf4j
class InternetGatewaySync {
	private Cloud cloud
	private MorpheusContext morpheusContext
	private AWSPlugin plugin
	private Map<String, CloudPoolIdentity> zonePools

	public InternetGatewaySync(AWSPlugin plugin, Cloud cloud) {
		this.plugin = plugin
		this.cloud = cloud
		this.morpheusContext = plugin.morpheusContext
	}

	def execute() {
		morpheusContext.async.cloud.region.listIdentityProjections(cloud.id).flatMap { region ->
			final String regionCode = region.externalId
			def amazonClient = plugin.getAmazonClient(cloud,false, regionCode)
			def routerResults = AmazonComputeUtility.listInternetGateways([amazonClient: amazonClient])
			if(routerResults.success) {
				Observable<NetworkRouterIdentityProjection> domainRecords = morpheusContext.async.network.router.listIdentityProjections(cloud.id,'amazonInternetGateway')
 				SyncTask<NetworkRouterIdentityProjection, InternetGateway, NetworkRouter> syncTask = new SyncTask<>(domainRecords, routerResults.internetGateways as Collection<InternetGateway>)
				return syncTask.addMatchFunction { NetworkRouterIdentityProjection domainObject, InternetGateway data ->
					domainObject.externalId == data.getInternetGatewayId()
				}.onDelete { removeItems ->
					removeMissingRouters(removeItems)
				}.onUpdate { List<SyncTask.UpdateItem<NetworkRouter, InternetGateway>> updateItems ->
					updateMatchedInternetGateways(updateItems, regionCode)
				}.onAdd { itemsToAdd ->
					addMissingInternetGateways(itemsToAdd, regionCode)
				}.withLoadObjectDetailsFromFinder { List<SyncTask.UpdateItemDto<NetworkRouterIdentityProjection, InternetGateway>> updateItems ->
					return morpheusContext.async.network.router.listById(updateItems.collect { it.existingItem.id } as List<Long>)
				}.observe()
			} else {
				log.error("Error Caching InternetGateways for Region: {} - {}",regionCode,routerResults.msg)
				return Single.just(false).toObservable() //ignore invalid region
			}
		}.blockingSubscribe()
	}
	protected void addMissingInternetGateways(Collection<InternetGateway> addList, String regionCode) {
		def adds = []
		for(InternetGateway addItem in addList) {
			def nameTag = addItem.getTags()?.find{it.getKey() == 'Name'}
			String name = nameTag?.value ?: addItem.internetGatewayId
			def routerConfig = [
				owner        : cloud.owner,
				category     : "aws.internet.gateway.${cloud.id}",
				code         : "aws.internet.gateway.${cloud.id}.${addItem.internetGatewayId}",
				type         : new NetworkRouterType(code:'amazonInternetGateway'),
				name         : name,
				networkServer: cloud.networkServer,
				cloud        : cloud,
				refId        : cloud.id,
				refType      : 'ComputeZone',
				externalId   : addItem.internetGatewayId,
				regionCode   : regionCode,
				poolId       : addItem.attachments ? allZonePools[addItem.attachments.first().vpcId]?.id : null
			]
			NetworkRouter router = new NetworkRouter(routerConfig)
			adds << router
		}
		if(adds) {
			morpheusContext.async.network.router.create(adds).blockingGet()
		}
	}

	protected void updateMatchedInternetGateways(List<SyncTask.UpdateItem<NetworkRouter, InternetGateway>> updateList, String regionCode) {
		def updates = []

		for(update in updateList) {
			def masterItem = update.masterItem
			def existingItem = update.existingItem
			Boolean save = false

			def nameTag = masterItem.getTags()?.find{it.getKey() == 'Name'}
			def name = nameTag?.value ?: masterItem.getInternetGatewayId()

			if(existingItem.name != name) {
				existingItem.name = name
				save = true
			}

			if(existingItem.regionCode != regionCode) {
				existingItem.regionCode = regionCode
				save = true
			}

			def attachments = masterItem.getAttachments()
			attachments?.each { attachment ->
				// should only be one
				def vpcId = attachment.getVpcId()
				def pool = allZonePools[vpcId]
				if(existingItem.poolId?.toString() != pool?.id?.toString()) {
					existingItem.poolId = pool?.id
					save = true
				}
			}

			if(existingItem.poolId && !attachments) {
				existingItem.poolId = null
				save = true
			}
			if(save) {
				updates << existingItem
			}
		}
		if(updates) {
			morpheusContext.async.network.router.save(updates).blockingGet()
		}
	}

	protected removeMissingRouters(List<NetworkRouterIdentityProjection> removeList) {
		morpheusContext.async.network.router.remove(removeList).blockingGet()
	}

	private Map<String, CloudPoolIdentity> getAllZonePools() {
		zonePools ?: (zonePools = morpheusContext.async.cloud.pool.listIdentityProjections(cloud.id, '', null).toMap {it.externalId}.blockingGet())
	}
}