package com.morpheusdata.aws.sync

import com.amazonaws.services.ec2.model.Instance
import com.bertramlabs.plugins.karman.network.SecurityGroupInterface
import com.morpheusdata.aws.AWSPlugin
import com.morpheusdata.aws.utils.AmazonComputeUtility
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Cloud
import com.bertramlabs.plugins.karman.network.NetworkProvider
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeZonePool
import com.morpheusdata.model.SecurityGroupLocation
import com.morpheusdata.model.SecurityGroupRuleLocation
import com.morpheusdata.model.projection.ComputeZonePoolIdentityProjection
import com.morpheusdata.model.projection.SecurityGroupLocationIdentityProjection
import io.reactivex.Observable

class SecurityGroupSync {
	private Cloud cloud
	private MorpheusContext morpheusContext
	private AWSPlugin plugin
	private Map<String, ComputeZonePoolIdentityProjection> zonePools

	SecurityGroupSync(AWSPlugin plugin, Cloud cloud) {
		this.plugin = plugin
		this.cloud = cloud
		this.morpheusContext = plugin.morpheusContext
	}

	def execute() {
		def vpcId = cloud.getConfigProperty('vpcId')
		def zonePool = vpcId ? allZonePools[vpcId] : null
		morpheusContext.cloud.region.listIdentityProjections(cloud.id).blockingSubscribe { region ->
			def amazonClient = AmazonComputeUtility.getAmazonClient(cloud, false, region.externalId)
			Collection<SecurityGroupInterface> cloudItems = NetworkProvider.create(provider: 'amazon', client: amazonClient).getSecurityGroups().findAll {
				(!vpcId || it.vpcId == vpcId) && (!it.vpcId || allZonePools[it.vpcId]) //this vpc isnt synced in scope so dont add this security group
			}
			Observable<SecurityGroupLocationIdentityProjection> existingRecords = morpheusContext.securityGroup.location.listIdentityProjections(cloud.id, zonePool?.id, null)
			SyncTask<SecurityGroupLocationIdentityProjection, SecurityGroupInterface, SecurityGroupLocation> syncTask = new SyncTask<>(existingRecords, cloudItems)
			syncTask.addMatchFunction { SecurityGroupLocationIdentityProjection existingItem, SecurityGroupInterface cloudItem ->
				existingItem.externalId == cloudItem.id
			}.withLoadObjectDetailsFromFinder { List<SyncTask.UpdateItemDto<SecurityGroupLocationIdentityProjection, SecurityGroupInterface>> updateItems ->
				morpheusContext.securityGroup.location.listByIds(updateItems.collect { it.existingItem.id } as List<Long>)
			}.onAdd { itemsToAdd ->
				addMissingSecurityGroups(itemsToAdd, region.externalId, zonePool?.id)
			}.onUpdate { List<SyncTask.UpdateItem<ComputeServer, Instance>> updateItems ->
				updateMatchedSecurityGroups(updateItems, region.externalId, zonePool?.id)
			}.onDelete { removeItems ->
				removeMissingSecurityGroups(removeItems)
			}.start()
		}
	}

	private addMissingSecurityGroups(Collection<SecurityGroupInterface> addList, String regionCode, Long zonePoolId) {
		for(SecurityGroupInterface cloudItem in addList) {
			SecurityGroupLocation add = new SecurityGroupLocation(
				refType: 'ComputeZone', refId: cloud.id, externalId: cloudItem.id, name: cloudItem.name,
				description: cloudItem.description, regionCode: regionCode, groupName: cloudItem.name,
				ruleHash: cloudItem.md5Hash, securityServer: cloud.securityServer,
				zonePool: cloudItem.vpcId ? new ComputeZonePool(id: allZonePools[cloudItem.vpcId].id) : null
			)
			morpheusContext.securityGroup.location.create(add).blockingGet()
		}
		syncRules(addList, zonePoolId)
	}

	private updateMatchedSecurityGroups(List<SyncTask.UpdateItem<SecurityGroupLocation, SecurityGroupInterface>> updateList, String regionCode, Long zonePoolId) {
		def saveList = []
		for(def updateItem in updateList) {
			def existingItem = updateItem.existingItem
			def cloudItem = updateItem.masterItem
			def save = false
			if(existingItem.ruleHash != cloudItem.md5Hash) {
				existingItem.ruleHash = cloudItem.md5Hash
				save = true
			}
			if(regionCode && existingItem.regionCode != regionCode) {
				existingItem.regionCode = regionCode
				save = true
			}
			if(!existingItem.securityServer && cloud.securityServer) {
				existingItem.securityServer = cloud.securityServer
				save = true
			}
			if(cloudItem.vpcId && existingItem.zonePool?.externalId != cloudItem.vpcId) {
				existingItem.zonePool = new ComputeZonePool(id: allZonePools[cloudItem.vpcId].id)
				save = true
			}
			if(save) {
				saveList << existingItem
			}
		}
		if(saveList) {
			morpheusContext.securityGroup.location.save(saveList)
		}
		syncRules(updateList.collect {it.masterItem}, zonePoolId)
	}

	private removeMissingSecurityGroups(Collection<SecurityGroupLocationIdentityProjection> removeList) {
		morpheusContext.securityGroup.location.removeSecurityGroupLocations(removeList)
	}

	private syncRules(Collection<SecurityGroupInterface> cloudList, Long zonePoolId) {
		def securityGroupLocations = getSecurityGroupLocations(zonePoolId)

		for(SecurityGroupInterface cloudItem in cloudList) {
			def securityGroupLocation = morpheusContext.securityGroup.location.listByIds([securityGroupLocations[cloudItem.id].id]).blockingFirst()
			def rules = cloudItem.rules?.collect { cloudRule ->
				SecurityGroupRuleLocation rule = new SecurityGroupRuleLocation(
					externalId: cloudRule.id?.toString(), name: cloudRule.description, ruleType: 'custom',
					protocol: cloudRule.ipProtocol == '-1' ? 'all' : cloudRule.ipProtocol,
					source: cloudRule.direction == 'ingress' ? (cloudRule.getIpRange() ? cloudRule.getIpRange()[0] : null) : null,
					destination: cloudRule.direction == 'egress' ? (cloudRule.getIpRange() ? cloudRule.getIpRange()[0] : null) : null,
					direction: cloudRule.direction, etherType: cloudRule.ethertype
				)

				def portStart = cloudRule.minPort != null && (cloudRule.minPort > 0 || cloudRule.ipProtocol != 'icmp') ? cloudRule.minPort : null
				def portEnd = cloudRule.maxPort != null && (cloudRule.maxPort > 0 || cloudRule.ipProtocol != 'icmp') ? cloudRule.maxPort : null

				if(portStart != null) {
					rule.portRange = (portEnd && portEnd > 0  && portStart != portEnd) ? "${portStart}-${portEnd}" : "${portStart}"
				} else if(portEnd != null) {
					rule.portRange = "${portEnd}"
				}

				if(cloudRule.direction == 'egress') {
					rule.sourceType = 'all'
					rule.destinationType = 'cidr'
					rule.destinationGroup = securityGroupLocations[cloudRule.targetGroupId]?.securityGroup
					if(cloudRule.targetGroupId) {
						rule.destinationType = 'group'
					}
				} else {
					rule.destinationType = 'instance'
					rule.sourceType = 'cidr'
					rule.sourceGroup = securityGroupLocations[cloudRule.targetGroupId]?.securityGroup
					if(cloudRule.targetGroupId) {
						rule.sourceType = 'group'
					}
				}
				rule
			}
			morpheusContext.securityGroup.location.syncRules(securityGroupLocation, rules).blockingGet()
		}
	}

	private Map<String, ComputeZonePoolIdentityProjection> getAllZonePools() {
		zonePools ?: (zonePools = morpheusContext.cloud.pool.listSyncProjections(cloud.id, '').toMap {it.externalId}.blockingGet())
	}

	private Map<String, SecurityGroupLocationIdentityProjection> getSecurityGroupLocations(Long zonePoolId) {
		morpheusContext.securityGroup.location.listIdentityProjections(cloud.id, zonePoolId, null).toMap{ it.externalId}.blockingGet()
	}
}
