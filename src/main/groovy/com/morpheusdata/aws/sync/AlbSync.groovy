package com.morpheusdata.aws.sync

import com.amazonaws.services.elasticloadbalancingv2.model.LoadBalancer
import com.morpheusdata.aws.ALBLoadBalancerProvider
import com.morpheusdata.aws.AWSPlugin
import com.morpheusdata.aws.utils.AmazonComputeUtility
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeZonePool
import com.morpheusdata.model.ComputeZoneRegion
import com.morpheusdata.model.NetworkLoadBalancer
import com.morpheusdata.model.NetworkLoadBalancerType
import com.morpheusdata.model.projection.ComputeZonePoolIdentityProjection
import com.morpheusdata.model.projection.ComputeZoneRegionIdentityProjection
import com.morpheusdata.model.projection.NetworkLoadBalancerIdentityProjection
import groovy.util.logging.Slf4j
import io.reactivex.Observable
import io.reactivex.Single

@Slf4j
class AlbSync {
	private Cloud cloud
	private MorpheusContext morpheusContext
	private AWSPlugin plugin

	AlbSync(AWSPlugin plugin, Cloud cloud) {
		this.plugin = plugin
		this.cloud = cloud
		this.morpheusContext = plugin.morpheusContext
	}

	def execute() {
		morpheusContext.cloud.region.listIdentityProjections(cloud.id).flatMap { region ->
			final String regionCode = region.externalId
			def amazonClient = AmazonComputeUtility.getAmazonElbClient(cloud,false,regionCode)
			def albList = AmazonComputeUtility.listAlbs([amazonClient: amazonClient])
			if(albList.success) {
				Observable<NetworkLoadBalancerIdentityProjection> domainRecords = morpheusContext.loadBalancer.listIdentityProjections(cloud.id, regionCode, ALBLoadBalancerProvider.PROVIDER_CODE)
				SyncTask<NetworkLoadBalancerIdentityProjection, LoadBalancer, NetworkLoadBalancer> syncTask = new SyncTask<>(domainRecords, albList.albList as Collection<LoadBalancer>)
				return syncTask.addMatchFunction { NetworkLoadBalancerIdentityProjection domainObject, LoadBalancer data ->
					domainObject.externalId == (':' + data.getLoadBalancerArn().split(':')[5..-1].join(':'))
				}.onDelete { removeItems ->
					removeMissingLoadBalancers(removeItems)
				}.onUpdate { List<SyncTask.UpdateItem<NetworkLoadBalancer, LoadBalancer>> updateItems ->
					updateMatchedLoadBalancers(updateItems, region)
				}.onAdd { itemsToAdd ->
					addMissingLoadBalancers(itemsToAdd, region)
				}.withLoadObjectDetailsFromFinder { List<SyncTask.UpdateItemDto<NetworkLoadBalancerIdentityProjection, LoadBalancer>> updateItems ->
					return morpheusContext.loadBalancer.listById(updateItems.collect { it.existingItem.id } as List<Long>)
				}.observe()
			} else {
				log.error("Error Caching LoadBalancers for Region: {} - {}",regionCode,albList.msg)
				return Single.just(false).toObservable() //ignore invalid region
			}
		}.blockingSubscribe()
	}


	protected void addMissingLoadBalancers(Collection<LoadBalancer> addList, ComputeZoneRegionIdentityProjection region) {
		def adds = []
		List<String> subnetIds = addList.collect{ it.getAvailabilityZones().collect{it.getSubnetId()}}.flatten() as List<String>
		def subnets = morpheusContext.network.listByCloudAndExternalIdIn(cloud.id,subnetIds).toList().blockingGet().collectEntries{ [(it.externalId): it]}
		for(LoadBalancer cloudItem in addList) {
			def loadBalancerConfig = [owner: cloud.owner, account: cloud.owner, region: new ComputeZoneRegion(id: region.id), visibility: 'private', externalId: ':' + cloudItem.getLoadBalancerArn().split(':')[5..-1].join(':'), name: cloudItem.getLoadBalancerName(), sshHost: cloudItem.getDNSName(), type: new NetworkLoadBalancerType(code: ALBLoadBalancerProvider.PROVIDER_CODE), cloud: cloud]
			NetworkLoadBalancer newLoadBalancer = new NetworkLoadBalancer(loadBalancerConfig)

			def configMap = [scheme: cloudItem.getScheme() == 'internet-facing' ? 'Internet-facing' : cloudItem.getScheme(), arn: cloudItem.getLoadBalancerArn(), amazonVpc: cloudItem.getVpcId(), subnetIds: [], securityGroupIds: cloudItem.getSecurityGroups()]
			cloudItem.getAvailabilityZones()?.each { availabilityZone ->
				def subnetId = availabilityZone.getSubnetId()
				def subnet = subnets[subnetId]
				if (subnet) {
					configMap.subnetIds << subnet.id
				}
			}

			newLoadBalancer.configMap = configMap
			adds << newLoadBalancer
		}
		if(adds) {
			morpheusContext.loadBalancer.create(adds).blockingGet()
		}
	}

	protected void updateMatchedLoadBalancers(List<SyncTask.UpdateItem<ComputeZonePool, LoadBalancer>> updateList, ComputeZoneRegionIdentityProjection region) {
		def updates = []
		List<String> subnetIdsForUpdates = updateList.collect{ it.masterItem.getAvailabilityZones().collect{it.getSubnetId()}}.flatten() as List<String>
		def subnets = morpheusContext.network.listByCloudAndExternalIdIn(cloud.id,subnetIdsForUpdates).toList().blockingGet().collectEntries{ [(it.externalId): it]}
		for(update in updateList) {
			def masterItem = update.masterItem
			def existingItem = update.existingItem
			Boolean save = false

			if (existingItem.type.code != ALBLoadBalancerProvider.PROVIDER_CODE) {
				existingItem.type = new NetworkLoadBalancerType([code:ALBLoadBalancerProvider.PROVIDER_CODE])
				save = true
			}
			def securityGroups = masterItem.getSecurityGroups()?.join(',')
			def existingSecurityGroups = existingItem.getConfigProperty('securityGroupIds')?.join(',')
			if(existingSecurityGroups != securityGroups) {
				existingItem.setConfigProperty('securityGroupIds', masterItem.getSecurityGroups())
				save = true
			}
			def externalId = ':' + masterItem.getLoadBalancerArn().split(':')[5..-1].join(':')
			def subnetIds = []
			masterItem.getAvailabilityZones()?.each { availabilityZone ->
				def subnetId = availabilityZone.getSubnetId()
				def subnet = subnets[subnetId]
				if (subnet) {
					subnetIds << subnet.id
				}
			}
			def existingSubnetIds = existingItem.getConfigProperty('subnetIds')?.join(',')
			if(existingSubnetIds != subnetIds.join(',')) {
				existingItem.setConfigProperty('subnetIds', subnetIds)
				save = true
			}

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
