package com.morpheusdata.aws.sync

import com.amazonaws.services.ec2.model.InstanceTypeInfo
import com.morpheusdata.aws.AWSPlugin
import com.morpheusdata.aws.utils.AmazonComputeUtility
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.ComputeUtility
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ProvisionType
import com.morpheusdata.model.ServicePlan
import com.morpheusdata.model.projection.ServicePlanIdentityProjection
import groovy.util.logging.Slf4j
import io.reactivex.Observable

@Slf4j
class ServicePlanSync {
	private Cloud cloud
	private MorpheusContext morpheusContext
	private AWSPlugin plugin

	ServicePlanSync(AWSPlugin plugin, Cloud cloud) {
		this.plugin = plugin
		this.cloud = cloud
		this.morpheusContext = plugin.morpheusContext
	}

	def execute() {
		// Get map of instance types to regions
		def instanceTypeRegions = [:]
		morpheusContext.cloud.region.listIdentityProjections(cloud.id).blockingSubscribe { region ->
			def amazonClient = AmazonComputeUtility.getAmazonClient(cloud, false, region.externalId)

			for(InstanceTypeInfo instanceType in AmazonComputeUtility.listInstanceTypes([amazonClient: amazonClient]).instanceTypes) {
				if(!instanceTypeRegions[instanceType.instanceType]) {
					instanceTypeRegions[instanceType.instanceType] = [instanceType: instanceType, regionCodes: [] as Set]
				}
				instanceTypeRegions[instanceType.instanceType].regionCodes << region.externalId
			}
		}

		def cloudItems = instanceTypeRegions.values().collect { it.instanceType }
		Observable<ServicePlanIdentityProjection> existingRecords = morpheusContext.servicePlan.listIdentityProjections(new ProvisionType(code: 'amazon'))
		SyncTask<ServicePlanIdentityProjection, InstanceTypeInfo, ServicePlan> syncTask = new SyncTask<>(existingRecords, cloudItems)
		syncTask.addMatchFunction { ServicePlanIdentityProjection existingItem, InstanceTypeInfo cloudItem ->
			existingItem.externalId == cloudItem.instanceType
		}.withLoadObjectDetailsFromFinder { List<SyncTask.UpdateItemDto<ServicePlanIdentityProjection, ServicePlan>> updateItems ->
			morpheusContext.servicePlan.listById(updateItems.collect { it.existingItem.id } as List<Long>)
		}.onAdd { itemsToAdd ->
			addMissingServicePlans(itemsToAdd, instanceTypeRegions)
		}.onUpdate { List<SyncTask.UpdateItem<ServicePlan, InstanceTypeInfo>> updateItems ->
			updateMatchedServicePlans(updateItems, instanceTypeRegions)
		}.onDelete { removeItems ->
			removeMissingServicePlans(removeItems)
		}.start()
	}

	private addMissingServicePlans(Collection<InstanceTypeInfo> addList, Map instanceTypeRegions) {
		log.debug "addMissingServicePlans: ${cloud} ${addList.size()}"
		def adds = []

		for(cloudItem in addList) {
			def externalId = cloudItem.instanceType
			def parts = externalId.tokenize('.')
			def serverClass = parts[0]
			def maxCores = cloudItem.vCpuInfo.defaultVCpus
			def maxMemory = cloudItem.memoryInfo.sizeInMiB * com.morpheusdata.core.util.ComputeUtility.ONE_MEGABYTE

			// Fairly arbitrary maxStorage settings.. derived (roughly) from our initial amazon seed sizes
			def maxStorage
			if(maxMemory <= 2 * com.morpheus.util.ComputeUtility.ONE_GIGABYTE) {
				maxStorage = 20l * com.morpheus.util.ComputeUtility.ONE_GIGABYTE
			} else if(maxMemory <= 7 * com.morpheus.util.ComputeUtility.ONE_GIGABYTE) {
				maxStorage = 40l * com.morpheus.util.ComputeUtility.ONE_GIGABYTE
			} else if(maxMemory <= 8 * com.morpheus.util.ComputeUtility.ONE_GIGABYTE) {
				maxStorage = 80l * com.morpheus.util.ComputeUtility.ONE_GIGABYTE
			} else if(maxMemory <= 15 * com.morpheus.util.ComputeUtility.ONE_GIGABYTE) {
				maxStorage = 150l * com.morpheus.util.ComputeUtility.ONE_GIGABYTE
			} else if(maxMemory <= 20 * com.morpheus.util.ComputeUtility.ONE_GIGABYTE) {
				maxStorage = 160l * com.morpheus.util.ComputeUtility.ONE_GIGABYTE
			} else if(maxMemory <= 30 * com.morpheus.util.ComputeUtility.ONE_GIGABYTE) {
				maxStorage = 300l * com.morpheus.util.ComputeUtility.ONE_GIGABYTE
			} else if(maxMemory <= 32 * com.morpheus.util.ComputeUtility.ONE_GIGABYTE) {
				maxStorage = 320l * com.morpheus.util.ComputeUtility.ONE_GIGABYTE
			} else if(maxMemory <= 128 * com.morpheus.util.ComputeUtility.ONE_GIGABYTE) {
				maxStorage = 600l * com.morpheus.util.ComputeUtility.ONE_GIGABYTE
			} else {
				maxStorage = 1200 * com.morpheus.util.ComputeUtility.ONE_GIGABYTE
			}

			def name = buildServicePlanName(cloudItem)
			adds << new ServicePlan(
				code: "amazon-${cloudItem.instanceType}",
				active: cloudItem.currentGeneration == true,
				name: name,
				description: name,
				customMaxStorage: true,
				customMaxDataStorage: true,
				externalId: externalId,
				maxCores: maxCores,
				maxMemory: maxMemory,
				maxStorage: maxStorage,
				provisionTypeCode: 'amazon',
				serverClass: serverClass,
				editable: false,
				addVolumes: true,
				subRegionCodes: instanceTypeRegions[cloudItem.instanceType].regionCodes
			)
		}

		// Create em all!
		log.debug "About to create ${adds.size()} snapshots"
		morpheusContext.servicePlan.create(adds).blockingGet()
	}

	private updateMatchedServicePlans(List<SyncTask.UpdateItem<ServicePlan, InstanceTypeInfo>> updateList, Map instanceTypesToRegion) {
		log.debug "updateMatchedServicePlans: ${cloud} ${region.externalId} ${updateList.size()}"
		def saveList = []

		for(def updateItem in updateList) {
			def existingItem = updateItem.existingItem
			def cloudItem = updateItem.masterItem
			def name = buildServicePlanName(cloudItem)
			def save

			if(existingItem.name != name) {
				log.debug "Name changed for ${servicePlan.name} to ${name}"
				existingItem.name = name
				existingItem.description = name
				save = true
			}
			def isActive = (cloudItem.currentGeneration == true)
			if(existingItem.active != isActive) {
				existingItem.active = isActive
				save = true
			}

			def externalId = cloudItem.instanceType
			def parts = externalId?.tokenize('.')
			def serverClass = parts[0]
			if(serverClass && existingItem.serverClass != serverClass) {
				existingItem.serverClass = serverClass
				save = true
			}

			def subRegionCodes = instanceTypesToRegion[cloudItem.instanceType].regionCodes
			if(existingItem.subRegionCodes?.size() != subRegionCodes.size() || (existingItem.subRegionCodes?.sort() != subRegionCodes.sort()) ) {
				existingItem.subRegionCodes = subRegionCodes
				save = true
			}

			if(save) {
				saveList << existingItem
			}
		}

		if(saveList) {
			log.debug "About to update ${saveList.size()} service plans"
			morpheusContext.servicePlan.save(saveList)
		}
	}

	private removeMissingServicePlans(Collection<ServicePlanIdentityProjection> removeList) {
		removeList = removeList.findAll{ it.deleted != true }

		if(removeList) {
			log.debug "removeMissingServicePlans: ${cloud} ${removeList.size()}"
			morpheusContext.servicePlan.remove(removeList).blockingGet()
		}
	}

	private buildServicePlanName(cloudItem) {
		def externalId = cloudItem.instanceType
		def parts = externalId.tokenize('.')
		def serverClass = parts[0]
		def maxCores = cloudItem.vCpuInfo.defaultVCpus
		def maxMemory = cloudItem.memoryInfo.sizeInMiB * ComputeUtility.ONE_MEGABYTE
		def legacy = cloudItem.currentGeneration == false
		def memVal = maxMemory.toLong() / ComputeUtility.ONE_GIGABYTE
		if(!memVal.toString().isInteger()) {
			memVal = new BigDecimal(memVal).setScale(1, BigDecimal.ROUND_HALF_UP )
		}
		"${legacy ? 'Legacy ' : ''}${serverClass.toString().toUpperCase()} ${parts[1].toString().capitalize()} - ${maxCores} Core, ${memVal.toString()}GB Memory"
	}
}