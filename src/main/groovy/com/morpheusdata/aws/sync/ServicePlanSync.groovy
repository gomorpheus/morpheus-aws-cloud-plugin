package com.morpheusdata.aws.sync

import com.amazonaws.services.ec2.model.InstanceTypeInfo
import com.morpheusdata.aws.AWSPlugin
import com.morpheusdata.aws.utils.AmazonComputeUtility
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.util.ComputeUtility
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ProvisionType
import com.morpheusdata.model.ServicePlan
import com.morpheusdata.model.projection.ServicePlanIdentityProjection
import groovy.util.logging.Slf4j
import io.reactivex.rxjava3.core.Observable

@Slf4j
class ServicePlanSync {
	private MorpheusContext morpheusContext
	private AWSPlugin plugin

	ServicePlanSync(AWSPlugin plugin) {
		this.plugin = plugin
		this.morpheusContext = plugin.morpheusContext
	}

	def execute() {
		try {
			log.info("Syncing Plans for AWS")
			// Find cloud per region
			def clouds = morpheusContext.async.cloud.list(new DataQuery().withFilter('cloudType.code', 'amazon')).toMap { it.id }.blockingGet()
			def regionClouds = [:]
			morpheusContext.async.cloud.region.list(new DataQuery().withFilter('cloud.id', 'in', clouds.keySet())).blockingSubscribe { region ->
				if(!regionClouds[region.externalId])
					regionClouds[region.externalId] = [region: region, cloud: clouds[region.cloud.id]]
			}
			def firstCloud = clouds.values().first()
			// Get map of instance types to regions
			def instanceTypeRegions = [:]
			for(String regionCode : regionClouds.keySet()) {
				def region = regionClouds[regionCode].region
				def cloud = regionClouds[regionCode].cloud
				if(!cloud.accountCredentialLoaded) {
					cloud.accountCredentialLoaded = true
					cloud.accountCredentialData = morpheusContext.async.accountCredential.loadCredentials(cloud).blockingGet()?.data
				}
				def amazonClient = plugin.getAmazonClient(cloud, false, regionCode)
				for(InstanceTypeInfo instanceType in AmazonComputeUtility.listInstanceTypes([amazonClient: amazonClient]).instanceTypes) {
					if(!instanceTypeRegions[instanceType.instanceType]) {
						instanceTypeRegions[instanceType.instanceType] = [instanceType: instanceType, regionCodes: [] as Set]
					}
					instanceTypeRegions[instanceType.instanceType].regionCodes << region.internalId
				}
			}

			def cloudItems = instanceTypeRegions.values().collect { it.instanceType }
			Observable<ServicePlanIdentityProjection> existingRecords = morpheusContext.async.servicePlan.listIdentityProjections(new ProvisionType(code: 'amazon'))
			SyncTask<ServicePlanIdentityProjection, InstanceTypeInfo, ServicePlan> syncTask = new SyncTask<>(existingRecords, cloudItems)
			syncTask.addMatchFunction { ServicePlanIdentityProjection existingItem, InstanceTypeInfo cloudItem ->
				existingItem.externalId == cloudItem.instanceType
			}.withLoadObjectDetailsFromFinder { List<SyncTask.UpdateItemDto<ServicePlanIdentityProjection, ServicePlan>> updateItems ->
				morpheusContext.async.servicePlan.listById(updateItems.collect { it.existingItem.id } as List<Long>)
			}.onAdd { itemsToAdd ->
				addMissingServicePlans(itemsToAdd, instanceTypeRegions,firstCloud)
			}.onUpdate { List<SyncTask.UpdateItem<ServicePlan, InstanceTypeInfo>> updateItems ->
				updateMatchedServicePlans(updateItems, instanceTypeRegions)
			}.onDelete { removeItems ->
				removeMissingServicePlans(removeItems)
			}.start()

			//Fix Plan Sorting
			List<ServicePlan> existingItems = morpheusContext.services.servicePlan.list(new DataQuery().withFilters(new DataFilter<Boolean>("deleted","false"),new DataFilter<String>("provisionType.code","amazon")))
			existingItems.sort { a, b ->
				return comparePlans(a, b)
			}
			List<ServicePlan> updateList = [] as List<ServicePlan>
			def sortId = 0
			existingItems.each { ServicePlan p ->
				if(p.sortOrder != sortId) {
					p.sortOrder = sortId
					updateList << p
				}
				sortId++
			}
			if(updateList) {
				morpheusContext.services.servicePlan.bulkSave(updateList)
			}

		} catch(Exception ex) {
			log.error("ServicePlanSync error: {}", ex, ex)
		}
	}

	private addMissingServicePlans(Collection<InstanceTypeInfo> addList, Map instanceTypeRegions, Cloud cloud) {
		log.debug "addMissingServicePlans: ${addList.size()}"
		def adds = []

		for(cloudItem in addList) {
			def externalId = cloudItem.instanceType
			def parts = externalId.tokenize('.')
			def serverClass = parts[0]
			def maxCores = cloudItem.vCpuInfo.defaultVCpus
			def maxMemory = cloudItem.memoryInfo.sizeInMiB * com.morpheusdata.core.util.ComputeUtility.ONE_MEGABYTE

			// Fairly arbitrary maxStorage settings.. derived (roughly) from our initial amazon seed sizes
			def maxStorage
			if(maxMemory <= 2 * ComputeUtility.ONE_GIGABYTE) {
				maxStorage = 20l * ComputeUtility.ONE_GIGABYTE
			} else if(maxMemory <= 7 * ComputeUtility.ONE_GIGABYTE) {
				maxStorage = 40l * ComputeUtility.ONE_GIGABYTE
			} else if(maxMemory <= 8 * ComputeUtility.ONE_GIGABYTE) {
				maxStorage = 80l * ComputeUtility.ONE_GIGABYTE
			} else if(maxMemory <= 15 * ComputeUtility.ONE_GIGABYTE) {
				maxStorage = 150l * ComputeUtility.ONE_GIGABYTE
			} else if(maxMemory <= 20 * ComputeUtility.ONE_GIGABYTE) {
				maxStorage = 160l * ComputeUtility.ONE_GIGABYTE
			} else if(maxMemory <= 30 * ComputeUtility.ONE_GIGABYTE) {
				maxStorage = 300l * ComputeUtility.ONE_GIGABYTE
			} else if(maxMemory <= 32 * ComputeUtility.ONE_GIGABYTE) {
				maxStorage = 320l * ComputeUtility.ONE_GIGABYTE
			} else if(maxMemory <= 128 * ComputeUtility.ONE_GIGABYTE) {
				maxStorage = 600l * ComputeUtility.ONE_GIGABYTE
			} else {
				maxStorage = 1200 * ComputeUtility.ONE_GIGABYTE
			}

			def name = buildServicePlanName(cloudItem)
			adds << new ServicePlan(
				code: "amazon-${cloudItem.instanceType}",
				active: cloudItem.currentGeneration == true && cloud?.defaultPlanSyncActive,
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
		if(adds) {
			log.debug "About to create ${adds.size()} snapshots"
			morpheusContext.async.servicePlan.create(adds).blockingGet()
		}

	}

	private updateMatchedServicePlans(List<SyncTask.UpdateItem<ServicePlan, InstanceTypeInfo>> updateList, Map instanceTypesToRegion) {
		log.debug "updateMatchedServicePlans: ${updateList.size()}"
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

			if(existingItem.deleted) {
				existingItem.deleted = false
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
			morpheusContext.async.servicePlan.bulkSave(saveList).blockingGet()
		}
	}

	private removeMissingServicePlans(Collection<ServicePlanIdentityProjection> removeList) {
		removeList = removeList.findAll{ it.deleted != true }

		if(removeList) {
			log.debug "removeMissingServicePlans: ${removeList.size()}"
			morpheusContext.async.servicePlan.remove(removeList.collect { new ServicePlan(id: it.id)}).blockingGet()
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

	private comparePlans(ServicePlan a, ServicePlan b) {
		// We want all t, m, c at the beginning.. then the rest alphabetically
		def serverClassA = getServerClassSortValue(a.serverClass)
		def serverClassB = getServerClassSortValue(b.serverClass)
		def compareValue = serverClassA <=> serverClassB
		if(compareValue == 0) {
			compareValue = a?.maxMemory?.toLong() <=> b?.maxMemory?.toLong()
			if(compareValue == 0) {
				compareValue = a?.maxCores?.toLong() <=> b?.maxCores?.toLong()
			}
		}
		return compareValue
	}

	private getServerClassSortValue(String serverClass) {
		if(serverClass?.startsWith('t')) {
			return "1${serverClass}"
		}
		if(serverClass?.startsWith('m')){
			return "2${serverClass}"
		}
		if(serverClass?.startsWith('c')){
			return "3${serverClass}"
		}
		return serverClass
	}
}
