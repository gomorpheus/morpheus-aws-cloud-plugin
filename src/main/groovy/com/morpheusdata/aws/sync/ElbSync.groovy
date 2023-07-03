package com.morpheusdata.aws.sync

import com.amazonaws.services.elasticloadbalancing.model.LoadBalancerDescription
import com.morpheusdata.aws.AWSPlugin
import com.morpheusdata.aws.utils.AmazonComputeUtility
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeZonePool
import com.morpheusdata.model.NetworkLoadBalancer
import com.morpheusdata.model.NetworkLoadBalancerType
import com.morpheusdata.model.projection.ComputeZonePoolIdentityProjection
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
		morpheusContext.cloud.region.listIdentityProjections(cloud.id).flatMap {
			final String regionCode = it.externalId
			def amazonClient = AmazonComputeUtility.getAmazonElbClassicClient(cloud,false,it.externalId)
			def elbList = AmazonComputeUtility.listElbs([amazonClient: amazonClient])
			if(elbList.success) {
				Observable<NetworkLoadBalancerIdentityProjection> domainRecords = morpheusContext.loadBalancer.listIdentityProjections(cloud.id,regionCode,'amazon')
				SyncTask<NetworkLoadBalancerIdentityProjection, LoadBalancerDescription, NetworkLoadBalancer> syncTask = new SyncTask<>(domainRecords, elbList.elbList as Collection<LoadBalancerDescription>)
				return syncTask.addMatchFunction { NetworkLoadBalancerIdentityProjection domainObject, LoadBalancerDescription data ->
					domainObject.externalId == ':loadbalancer/' + data.getLoadBalancerName()
				}.onDelete { removeItems ->
					removeMissingLoadBalancers(removeItems)
				}.onUpdate { List<SyncTask.UpdateItem<NetworkLoadBalancer, LoadBalancerDescription>> updateItems ->
					updateMatchedLoadBalancers(updateItems,regionCode)
				}.onAdd { itemsToAdd ->
					addMissingLoadBalancers(itemsToAdd, regionCode)

				}.withLoadObjectDetailsFromFinder { List<SyncTask.UpdateItemDto<NetworkLoadBalancerIdentityProjection, LoadBalancerDescription>> updateItems ->
					return morpheusContext.loadBalancer.listById(updateItems.collect { it.existingItem.id } as List<Long>)
				}.observe()
			} else {
				log.error("Error Caching ELB LoadBalancers for Region: {} - {}",regionCode,elbList.msg)
				return Single.just(false).toObservable() //ignore invalid region
			}
		}.blockingSubscribe()
	}


	protected void addMissingLoadBalancers(Collection<LoadBalancerDescription> addList, String region) {
		def adds = []
		for(LoadBalancerDescription cloudItem in addList) {
			def loadBalancerConfig = [owner: cloud.owner, account: cloud.owner, visibility: 'private', externalId: ":loadbalancer/${cloudItem.getLoadBalancerName()}", name: cloudItem.getLoadBalancerName(), sshHost: cloudItem.getDNSName(), type: new NetworkLoadBalancerType(code:'amazon'), cloud: cloud]
			NetworkLoadBalancer newLoadBalancer = new NetworkLoadBalancer(loadBalancerConfig)
			adds << newLoadBalancer
		}
		if(adds) {
			morpheusContext.loadBalancer.create(adds).blockingGet()
		}
	}

	protected void updateMatchedLoadBalancers(List<SyncTask.UpdateItem<ComputeZonePool, LoadBalancerDescription>> updateList, String region) {
		def updates = []
		for(update in updateList) {
			def masterItem = update.masterItem
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
			morpheusContext.loadBalancer.save(updates).blockingGet()
		}
	}

	protected removeMissingLoadBalancers(List<NetworkLoadBalancerIdentityProjection> removeList) {
		morpheusContext.loadBalancer.remove(removeList).blockingGet()
	}
}
