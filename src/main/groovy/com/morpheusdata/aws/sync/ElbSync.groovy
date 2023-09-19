package com.morpheusdata.aws.sync

import com.amazonaws.services.elasticloadbalancing.model.LoadBalancerDescription
import com.morpheusdata.aws.AWSPlugin
import com.morpheusdata.aws.utils.AmazonComputeUtility
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.CloudRegion
import com.morpheusdata.model.NetworkLoadBalancer
import com.morpheusdata.model.NetworkLoadBalancerType
import com.morpheusdata.model.projection.CloudRegionIdentity
import com.morpheusdata.model.projection.NetworkLoadBalancerIdentityProjection
import groovy.util.logging.Slf4j
import io.reactivex.Observable
import io.reactivex.Single

@Slf4j
class ElbSync {
	private Cloud cloud
	private MorpheusContext morpheusContext
	private AWSPlugin plugin


	public ElbSync(AWSPlugin plugin, Cloud cloud) {
		this.plugin = plugin
		this.cloud = cloud
		this.morpheusContext = plugin.morpheusContext
	}

	def execute() {
		morpheusContext.async.cloud.region.listIdentityProjections(cloud.id).flatMap { region ->
			def amazonClient = AmazonComputeUtility.getAmazonElbClassicClient(cloud,false, region.externalId)
			def elbList = AmazonComputeUtility.listElbs([amazonClient: amazonClient])
			if(elbList.success) {
				Observable<NetworkLoadBalancerIdentityProjection> domainRecords = morpheusContext.async.loadBalancer.listIdentityProjections(cloud.id, region.externalId,'amazon')
				SyncTask<NetworkLoadBalancerIdentityProjection, LoadBalancerDescription, NetworkLoadBalancer> syncTask = new SyncTask<>(domainRecords, elbList.elbList as Collection<LoadBalancerDescription>)
				return syncTask.addMatchFunction { NetworkLoadBalancerIdentityProjection domainObject, LoadBalancerDescription data ->
					domainObject.externalId == ':loadbalancer/' + data.getLoadBalancerName()
				}.onDelete { removeItems ->
					removeMissingLoadBalancers(removeItems)
				}.onUpdate { List<SyncTask.UpdateItem<NetworkLoadBalancer, LoadBalancerDescription>> updateItems ->
					updateMatchedLoadBalancers(updateItems,region)
				}.onAdd { itemsToAdd ->
					addMissingLoadBalancers(itemsToAdd, region)
				}.withLoadObjectDetailsFromFinder { List<SyncTask.UpdateItemDto<NetworkLoadBalancerIdentityProjection, LoadBalancerDescription>> updateItems ->
					return morpheusContext.async.loadBalancer.listById(updateItems.collect { it.existingItem.id } as List<Long>)
				}.observe()
			} else {
				log.error("Error Caching ELB LoadBalancers for Region: {} - {}",region.externalId,elbList.msg)
				return Single.just(false).toObservable() //ignore invalid region
			}
		}.blockingSubscribe()
	}


	protected void addMissingLoadBalancers(Collection<LoadBalancerDescription> addList, CloudRegionIdentity region) {
		def adds = []
		for(LoadBalancerDescription cloudItem in addList) {
			def loadBalancerConfig = [
				owner: cloud.owner, account: cloud.owner, visibility: 'private',
				externalId: ":loadbalancer/${cloudItem.getLoadBalancerName()}",
				name: cloudItem.getLoadBalancerName(), sshHost: cloudItem.getDNSName(),
				type: new NetworkLoadBalancerType(code:'amazon'), cloud: cloud,
				region: new CloudRegion(id: region.id)
			]
			NetworkLoadBalancer newLoadBalancer = new NetworkLoadBalancer(loadBalancerConfig)
			adds << newLoadBalancer
		}
		if(adds) {
			morpheusContext.async.loadBalancer.create(adds).blockingGet()
		}
	}

	protected void updateMatchedLoadBalancers(List<SyncTask.UpdateItem<NetworkLoadBalancer, LoadBalancerDescription>> updateList, CloudRegionIdentity region) {
		def updates = []
		for(update in updateList) {
			def existingItem = update.existingItem
			Boolean save = false
			def externalId = ':loadbalancer/' + update.masterItem.getLoadBalancerName()

			if(existingItem.externalId != externalId) {
				existingItem.externalId = externalId
				save = true
			}
			if(save) {
				updates << existingItem
			}
		}
		if(updates) {
			morpheusContext.async.loadBalancer.save(updates).blockingGet()
		}
	}

	protected removeMissingLoadBalancers(List<NetworkLoadBalancerIdentityProjection> removeList) {
		morpheusContext.async.loadBalancer.remove(removeList).blockingGet()
	}
}
