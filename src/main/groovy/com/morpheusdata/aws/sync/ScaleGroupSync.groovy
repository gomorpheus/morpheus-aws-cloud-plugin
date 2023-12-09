package com.morpheusdata.aws.sync

import com.amazonaws.services.autoscaling.model.AutoScalingGroup
import com.morpheusdata.aws.AWSPlugin
import com.morpheusdata.aws.AWSScaleProvider
import com.morpheusdata.aws.utils.AmazonComputeUtility
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.InstanceScale
import com.morpheusdata.model.InstanceThreshold
import com.morpheusdata.model.projection.CloudRegionIdentity
import com.morpheusdata.model.projection.InstanceScaleIdentityProjection
import groovy.util.logging.Slf4j
import io.reactivex.rxjava3.core.Observable

@Slf4j
class ScaleGroupSync {
	private Cloud cloud
	private MorpheusContext morpheusContext
	private AWSPlugin plugin
	private Map<String, InstanceScaleIdentityProjection> scaleTypes

	ScaleGroupSync(AWSPlugin plugin, Cloud cloud) {
		this.plugin = plugin
		this.cloud = cloud
		this.morpheusContext = plugin.morpheusContext
	}

	def execute() {
		try {
			morpheusContext.async.cloud.region.listIdentityProjections(cloud.id).blockingSubscribe { region ->
				def subnetExternalIds
				if(!cloud.getConfigProperty('vpc') && !cloud.getConfigProperty('isVpc')) {
					subnetExternalIds = []
					morpheusContext.async.network.listIdentityProjections(cloud.id, region.externalId).blockingSubscribe {
						subnetExternalIds << it.externalId
					}
				}
				def amazonClient = AmazonComputeUtility.getAmazonAutoScalingClient(cloud, false, region.externalId)
				def cloudItems = AmazonComputeUtility.listScaleGroups(amazonClient, subnetExternalIds).groups
				Observable<InstanceScaleIdentityProjection> existingRecords = morpheusContext.async.instance.scale.listIdentityProjections(cloud.id, region.externalId)
				SyncTask<InstanceScaleIdentityProjection, AutoScalingGroup, InstanceScale> syncTask = new SyncTask<>(existingRecords, cloudItems)
				syncTask.addMatchFunction { InstanceScaleIdentityProjection existingItem, AutoScalingGroup cloudItem ->
					existingItem.externalId == cloudItem.autoScalingGroupName
				}.withLoadObjectDetailsFromFinder { List<SyncTask.UpdateItemDto<InstanceScaleIdentityProjection, InstanceScale>> updateItems ->
					morpheusContext.async.instance.scale.listById(updateItems.collect { it.existingItem.id } as List<Long>)
				}.onAdd { itemsToAdd ->
					addMissingInstanceScales(itemsToAdd, region)
				}.onUpdate { List<SyncTask.UpdateItem<InstanceScale, AutoScalingGroup>> updateItems ->
					updateMatchedInstanceScales(updateItems, region)
				}.onDelete { removeItems ->
					removeMissingInstanceScales(removeItems)
				}.start()
			}
		} catch(Exception ex) {
			log.error("ScaleGroupSync error: {}", ex, ex)
		}
	}

	private addMissingInstanceScales(Collection<AutoScalingGroup> addList, CloudRegionIdentity region) {
		log.debug "addMissingInstanceScales: ${cloud} ${region.externalId} ${addList.size()}"
		def adds = []

		for(AutoScalingGroup cloudItem in addList) {
			adds << new InstanceScale(
				zoneId: cloud.id,
				owner: cloud.account,
				name: cloudItem.autoScalingGroupName,
				externalId: cloudItem.autoScalingGroupName,
				regionCode: region.externalId,
				type: allScaleTypes[AWSScaleProvider.PROVIDER_CODE],
				threshold: new InstanceThreshold(
					type: AWSScaleProvider.PROVIDER_CODE,
					zoneId: cloud.id,
					owner: cloud.account,
					name: cloudItem.autoScalingGroupName,
					minCount: cloudItem.desiredCapacity,
					maxCount: cloudItem.maxSize,
					maxCpu: 100
				)
			)
		}

		// Create em all!
		if(adds) {
			log.debug "About to create ${adds.size()} instance scales"
			morpheusContext.async.instance.scale.create(adds).blockingGet()
		}
	}

	private updateMatchedInstanceScales(List<SyncTask.UpdateItem<InstanceScale, AutoScalingGroup>> updateList, CloudRegionIdentity region) {
		log.debug "updateMatchedInstanceScales: ${cloud} ${region.externalId} ${updateList.size()}"
		def saveList = []

		for(def updateItem in updateList) {
			def existingItem = updateItem.existingItem
			def cloudItem = updateItem.masterItem

			if(existingItem.regionCode != region.externalId) {
				existingItem.regionCode = region.externalId
				saveList << existingItem
			}
			if(!existingItem.threshold) {
				existingItem.threshold = new InstanceThreshold(
					type: AWSScaleProvider.PROVIDER_CODE,
					zoneId: cloud.id,
					owner: cloud.account,
					name: cloudItem.autoScalingGroupName,
					minCount: cloudItem.desiredCapacity,
					maxCount: cloudItem.maxSize,
					maxCpu: 100
				)
			}
		}

		if(saveList) {
			log.debug "About to update ${saveList.size()} instance scales"
			morpheusContext.async.instance.scale.save(saveList).blockingGet()
		}
	}

	private removeMissingInstanceScales(Collection<InstanceScaleIdentityProjection> removeList) {
		log.debug "removeMissingInstanceScales: ${cloud} ${removeList.size()}"
		morpheusContext.async.instance.scale.remove(removeList).blockingGet()
	}

	private getAllScaleTypes() {
		scaleTypes ?: (scaleTypes = morpheusContext.async.instance.scale.scaleType.listAll().toMap { it.code }.blockingGet())
	}
}
