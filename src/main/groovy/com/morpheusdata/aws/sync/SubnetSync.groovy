package com.morpheusdata.aws.sync

import com.amazonaws.services.ec2.model.Subnet
import com.morpheusdata.aws.AWSPlugin
import com.morpheusdata.aws.utils.AmazonComputeUtility
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.Network
import com.morpheusdata.model.projection.ComputeZoneRegionIdentityProjection
import com.morpheusdata.model.projection.NetworkIdentityProjection
import groovy.util.logging.Slf4j
import io.reactivex.Observable
import io.reactivex.Single

@Slf4j
class SubnetSync {
	private Cloud cloud
	private MorpheusContext morpheusContext
	private AWSPlugin plugin

	SubnetSync(AWSPlugin plugin, Cloud cloud) {
		this.plugin = plugin
		this.cloud = cloud
		this.morpheusContext = plugin.morpheusContext
	}

	def execute() {
		morpheusContext.async.cloud.region.listIdentityProjections(cloud.id).flatMap { region ->
			final String regionCode = region.externalId
			def amazonClient = plugin.getAmazonClient(cloud,false, regionCode)
			def subnetResults = AmazonComputeUtility.listSubnets([amazonClient: amazonClient, zone: cloud])
			if(subnetResults.success) {
				Observable<NetworkIdentityProjection> domainRecords = morpheusContext.async.network.listIdentityProjections(cloud)
				SyncTask<NetworkIdentityProjection, Subnet, Network> syncTask = new SyncTask<>(domainRecords, subnetResults.subnetList as Collection<Subnet>)
				return syncTask.addMatchFunction { NetworkIdentityProjection domainObject, Subnet data ->
					domainObject.externalId == data.getSubnetId()
				}.onDelete { removeItems ->
					removeMissingSubnets(removeItems)
				}.onUpdate { List<SyncTask.UpdateItem<Network, Subnet>> updateItems ->
					updateMatchedSubnets(updateItems,regionCode)
				}.onAdd { itemsToAdd ->
					addMissingSubnets(itemsToAdd, regionCode)
				}.withLoadObjectDetailsFromFinder { List<SyncTask.UpdateItemDto<NetworkIdentityProjection, Subnet>> updateItems ->
					morpheusContext.async.cloud.network.listById(updateItems.collect { it.existingItem.id } as List<Long>)
				}.observe()
			} else {
				log.error("Error Caching Subnets for Region: {} - {}",regionCode,subnetResults.msg)
				return Single.just(false).toObservable() //ignore invalid region
			}
		}.blockingSubscribe()
	}


	protected void addMissingSubnets(Collection<Subnet> addList, String region) {
		def adds = []
		def networkType = plugin.networkProvider.networkTypes?.find { it.code == 'amazonSubnet' }
		def vpcRecords = morpheusContext.async.cloud.pool.listByCloudAndExternalIdIn(cloud.id,addList.collect{it.getVpcId()}).toList().blockingGet()?.collectEntries { [(it.externalId):it]}
		for(Subnet cloudItem in addList) {
			def nameTag = cloudItem.getTags()?.find{it.key == 'Name'}
			def vpcRecord = vpcRecords[cloudItem.getVpcId()]
			def networkConfig = [owner: cloud.owner, category:"amazon.ec2.subnet.${cloud.id}", name:nameTag?.getValue() ? "${nameTag.getValue()} (${cloudItem.getSubnetId()})" : "${cloudItem.getCidrBlock()} (${cloudItem.getSubnetId()})", displayName: nameTag?.getValue() ? "${nameTag.getValue()} (${cloudItem.getSubnetId()})" : "${cloudItem.getCidrBlock()} (${cloudItem.getSubnetId()})",
								 code:"amazon.ec2.subnet.${cloud.id}.${cloudItem.getSubnetId()}", uniqueId:cloudItem.getSubnetId(), externalId:cloudItem.getSubnetId(), externalType: 'subnet', type: networkType,
								 refType:'ComputeZone', refId:cloud.id, zonePoolId: vpcRecord.id, description:nameTag?.getValue() ?: cloudItem.getCidrBlock(), active:true, cidr:cloudItem.getCidrBlock(), dhcpServer:true,
								 assignPublicIp:cloudItem.isMapPublicIpOnLaunch(), networkServer: cloud.networkServer, availabilityZone:cloudItem.getAvailabilityZone(), cloud:cloud, regionCode: region]
			def add = new Network(networkConfig)
			adds << add
		}
		if(adds) {
			morpheusContext.async.network.create(adds).blockingGet()
		}
	}

	protected void updateMatchedSubnets(List<SyncTask.UpdateItem<Network, Subnet>> updateList, String region) {
		def updates = []

		for(update in updateList) {
			def masterItem = update.masterItem
			def network = update.existingItem
			Boolean save = false
			def nameTag = masterItem.getTags()?.find { it.key == 'Name' }
			def name = nameTag?.getValue() ? "${nameTag.getValue()} (${masterItem.getSubnetId()})" : "${masterItem.getCidrBlock()} (${masterItem.getSubnetId()})"
			if (network.networkServer?.id != cloud.networkServer?.id) {
				network.networkServer = cloud.networkServer
				save = true
			}
			if (network.name != name) {
				network.name = name
				save = true
			}

			if (network.displayName != name) {
				network.displayName = name
				save = true
			}

			if (network.dhcpServer != true) {
				network.dhcpServer = true
				save = true
			}
			if (network.availabilityZone != masterItem.getAvailabilityZone()) {
				network.availabilityZone = masterItem.getAvailabilityZone()
				save = true
			}
			if (network.assignPublicIp != masterItem.isMapPublicIpOnLaunch()) {
				network.assignPublicIp = masterItem.isMapPublicIpOnLaunch()
				save = true
			}

			def description = nameTag?.getValue() ?: masterItem.getCidrBlock()
			if (network.description != description) {
				network.description = description
				save = true
			}
			if (network.cidr != masterItem.getCidrBlock()) {
				network.cidr = masterItem.getCidrBlock()
				save = true
			}

			if(region && network.regionCode != region) {
				network.regionCode = region
				save = true
			}

			if(save) {
				updates << network
			}
		}
		if(updates) {
			morpheusContext.async.network.save(updates).blockingGet()
		}
	}

	protected removeMissingSubnets(List<NetworkIdentityProjection> removeList) {
		log.debug "removeMissingSubnets: ${removeList?.size()}"
		morpheusContext.async.network.remove(removeList)
	}
}
