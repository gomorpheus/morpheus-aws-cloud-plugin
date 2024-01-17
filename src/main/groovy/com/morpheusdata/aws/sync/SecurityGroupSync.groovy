package com.morpheusdata.aws.sync

import com.amazonaws.services.ec2.model.Instance
import com.morpheusdata.aws.AWSPlugin
import com.morpheusdata.aws.AWSSecurityGroupProvider
import com.morpheusdata.aws.utils.AmazonComputeUtility
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Cloud
import com.amazonaws.services.ec2.model.SecurityGroup as AWSSecurityGroup
import com.morpheusdata.model.CloudPool
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.SecurityGroupLocation
import com.morpheusdata.model.SecurityGroupRuleLocation
import com.morpheusdata.model.projection.CloudPoolIdentity
import com.morpheusdata.model.projection.SecurityGroupIdentityProjection
import com.morpheusdata.model.projection.SecurityGroupLocationIdentityProjection
import io.reactivex.Observable
import groovy.util.logging.Slf4j
import io.reactivex.rxjava3.core.Observable

import java.security.MessageDigest

@Slf4j
class SecurityGroupSync {
	private Cloud cloud
	private MorpheusContext morpheusContext
	private AWSPlugin plugin
	private Map<String, CloudPoolIdentity> zonePools

	SecurityGroupSync(AWSPlugin plugin, Cloud cloud) {
		this.plugin = plugin
		this.cloud = cloud
		this.morpheusContext = plugin.morpheusContext
	}

	def execute() {
		try {
			log.debug("SecurityGroupSync: starting sync")
			def vpcId = cloud.getConfigProperty('vpcId')
			morpheusContext.async.cloud.region.listIdentityProjections(cloud.id).blockingSubscribe { region ->
				def amazonClient = plugin.getAmazonClient(cloud, false, region.externalId)
				Collection<AWSSecurityGroup> cloudItems = AmazonComputeUtility.listSecurityGroups([amazonClient: amazonClient, zone: cloud]).securityList.findAll {
					(!vpcId || it.vpcId == vpcId) && (!it.vpcId || allZonePools[it.vpcId])
				} as Collection<AWSSecurityGroup>

				Observable<SecurityGroupLocation> existingRecords = getSecurityGroupLocations(region.externalId)
				SyncTask<SecurityGroupLocation, AWSSecurityGroup, SecurityGroupLocation> syncTask = new SyncTask<>(existingRecords, cloudItems)
				syncTask.addMatchFunction { SecurityGroupLocation existingItem, AWSSecurityGroup cloudItem ->
					existingItem.externalId == cloudItem.groupId && ((!cloudItem.vpcId && !existingItem.zonePool) || (cloudItem.vpcId == existingItem.zonePool?.externalId))
				}.withLoadObjectDetailsFromFinder { List<SyncTask.UpdateItemDto<SecurityGroupLocationIdentityProjection, AWSSecurityGroup>> updateItems ->
					morpheusContext.async.securityGroup.location.listByIds(updateItems.collect { it.existingItem.id } as List<Long>)
				}.onAdd { itemsToAdd ->
					addMissingSecurityGroups(itemsToAdd, region.externalId)
				}.onUpdate { List<SyncTask.UpdateItem<ComputeServer, Instance>> updateItems ->
					updateMatchedSecurityGroups(updateItems, region.externalId)
				}.onDelete { removeItems ->
					removeMissingSecurityGroups(removeItems)
				}.start()
			}
		} catch (Exception ex) {
			log.error("SecurityGroupSync error: {}", ex, ex)
		}

	}

	private addMissingSecurityGroups(Collection<AWSSecurityGroup> addList, String regionCode) {
		List<SecurityGroupLocation> adds = []
		for(AWSSecurityGroup cloudItem in addList) {
			adds << new SecurityGroupLocation(
				refType: 'ComputeZone', refId: cloud.id, externalId: cloudItem.groupId, name: cloudItem.groupName,
				description: cloudItem.description, regionCode: regionCode, groupName: cloudItem.groupName,
				ruleHash: AWSSecurityGroupProvider.getGroupRuleHash(cloudItem), securityServer: cloud.securityServer,
				zonePool: cloudItem.vpcId ? new CloudPool(id: allZonePools[cloudItem.vpcId].id) : null
			)
		}
		if(adds) {
			morpheusContext.async.securityGroup.location.create(adds).blockingGet()
		}
		syncRules(addList, regionCode)
	}

	private updateMatchedSecurityGroups(List<SyncTask.UpdateItem<SecurityGroupLocation, AWSSecurityGroup>> updateList, String regionCode) {
		def saveList = []
		for(def updateItem in updateList) {
			def existingItem = updateItem.existingItem
			def cloudItem = updateItem.masterItem
			def ruleHash = AWSSecurityGroupProvider.getGroupRuleHash(cloudItem)
			def save = false
			if(existingItem.ruleHash != ruleHash) {
				existingItem.ruleHash = ruleHash
				save = true
			}
			if(existingItem.regionCode != regionCode) {
				existingItem.regionCode = regionCode
				save = true
			}
			if(!existingItem.securityServer && cloud.securityServer) {
				existingItem.securityServer = cloud.securityServer
				save = true
			}
			if(cloudItem.vpcId != existingItem.zonePool?.externalId) {
				existingItem.zonePool = cloudItem.vpcId ? new CloudPool(id: allZonePools[cloudItem.vpcId].id) : null
				save = true
			}
			if(save) {
				saveList << existingItem
			}
		}
		if(saveList) {
			morpheusContext.async.securityGroup.location.save(saveList).blockingGet()
		}
		syncRules(updateList.collect {it.masterItem}, regionCode)
	}

	private removeMissingSecurityGroups(Collection<SecurityGroupLocationIdentityProjection> removeList) {
		morpheusContext.async.securityGroup.location.removeSecurityGroupLocations(removeList as List<SecurityGroupLocationIdentityProjection>).blockingGet()

		List<SecurityGroupIdentityProjection> removeGroups = morpheusContext.services.securityGroup.listIdentityProjections(
			new DataQuery().withFilters(
				new DataFilter('id', 'in', removeList.collect { it.securityGroup.id }),
				new DataFilter('locations', 'empty', true)
			)
		)
		if(removeGroups) {
			morpheusContext.services.securityGroup.bulkRemove(removeGroups)
		}
	}

	private syncRules(Collection<AWSSecurityGroup> cloudList, String regionCode) {
		def securityGroupLocations = getSecurityGroupLocations(regionCode).toMap { it.externalId }.blockingGet()

		for(AWSSecurityGroup cloudItem in cloudList) {
			def securityGroupLocation = securityGroupLocations[cloudItem.groupId]
			if(securityGroupLocation) {
				def rules = AWSSecurityGroupProvider.getGroupRules(cloudItem).collect { cloudRule ->
					SecurityGroupRuleLocation rule = new SecurityGroupRuleLocation(
						name: cloudRule.description, ruleType: 'custom',
						protocol: cloudRule.ipProtocol == '-1' ? 'all' : cloudRule.ipProtocol,
						source: cloudRule.direction == 'ingress' ? cloudRule.ipRange : null,
						destination: cloudRule.direction == 'egress' ? cloudRule.ipRange : null,
						direction: cloudRule.direction, etherType: 'internet'
					)

					def portStart = cloudRule.minPort != null && (cloudRule.minPort > 0 || cloudRule.ipProtocol != 'icmp') ? cloudRule.minPort : null
					def portEnd = cloudRule.maxPort != null && (cloudRule.maxPort > 0 || cloudRule.ipProtocol != 'icmp') ? cloudRule.maxPort : null

					if (portStart != null) {
						rule.portRange = (portEnd && portEnd > 0 && portStart != portEnd) ? "${portStart}-${portEnd}" : "${portStart}"
					} else if (portEnd != null) {
						rule.portRange = "${portEnd}"
					}

					if (cloudRule.direction == 'egress') {
						rule.sourceType = 'all'
						rule.destinationType = 'cidr'
						rule.destinationGroup = securityGroupLocations[cloudRule.targetGroupId]?.securityGroup
						if (cloudRule.targetGroupId) {
							rule.destinationType = 'group'
						}
					} else {
						rule.destinationType = 'instance'
						rule.sourceType = 'cidr'
						rule.sourceGroup = securityGroupLocations[cloudRule.targetGroupId]?.securityGroup
						if (cloudRule.targetGroupId) {
							rule.sourceType = 'group'
						}
					}
					rule
				}
				morpheusContext.async.securityGroup.location.syncRules(securityGroupLocation, rules).blockingGet()
			}
		}
	}

	private Map<String, CloudPoolIdentity> getAllZonePools() {
		zonePools ?: (zonePools = morpheusContext.async.cloud.pool.listIdentityProjections(cloud.id, null, null).toMap {it.externalId}.blockingGet())
	}

	private Observable<SecurityGroupLocation> getSecurityGroupLocations(String regionCode) {
		morpheusContext.async.securityGroup.location.list(
			new DataQuery().withFilters(
				new DataFilter('refType', 'ComputeZone'),
				new DataFilter('refId', cloud.id),
				new DataFilter('regionCode', regionCode)
			)
		)
	}
}
