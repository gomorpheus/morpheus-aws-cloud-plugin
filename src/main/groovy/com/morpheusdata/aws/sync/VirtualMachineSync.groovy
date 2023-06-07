package com.morpheusdata.aws.sync

import com.amazonaws.services.ec2.model.Instance
import com.amazonaws.services.ec2.model.InstanceBlockDeviceMapping
import com.amazonaws.services.ec2.model.Region
import com.amazonaws.services.ec2.model.Volume
import com.amazonaws.services.ec2.model.Vpc
import com.morpheusdata.aws.AWSPlugin
import com.morpheusdata.aws.utils.AmazonComputeUtility
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.ComputeUtility
import com.morpheusdata.core.util.SyncList
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.core.util.SyncTask.UpdateItemDto
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerInterface
import com.morpheusdata.model.ComputeServerType
import com.morpheusdata.model.ComputeZonePool
import com.morpheusdata.model.ComputeZoneRegion
import com.morpheusdata.model.MetadataTag
import com.morpheusdata.model.Network
import com.morpheusdata.model.OsType
import com.morpheusdata.model.ServicePlan
import com.morpheusdata.model.StorageVolume
import com.morpheusdata.model.StorageVolumeType
import com.morpheusdata.model.projection.ComputeServerIdentityProjection
import com.morpheusdata.model.projection.ComputeZonePoolIdentityProjection
import com.morpheusdata.model.projection.ComputeZoneRegionIdentityProjection
import com.morpheusdata.model.projection.SecurityGroupLocationIdentityProjection
import com.morpheusdata.model.projection.ServicePlanIdentityProjection
import groovy.util.logging.Slf4j
import io.reactivex.Observable
import io.reactivex.Single

@Slf4j
class VirtualMachineSync {
	private Cloud cloud
	private MorpheusContext morpheusContext
	private AWSPlugin plugin
	private Map<String, ComputeServerType> computeServerTypes
	private Collection<ServicePlanIdentityProjection> servicePlans
	private Map<String, ComputeZonePoolIdentityProjection> zonePools
	private Map<String, OsType> osTypes
	private Map<String, StorageVolumeType> storageVolumeTypes

	VirtualMachineSync(AWSPlugin plugin, Cloud cloud) {
		this.plugin = plugin
		this.cloud = cloud
		this.morpheusContext = plugin.morpheusContext
	}

	def execute() {
		def usageLists = [restartUsageIds: [], stopUsageIds: [], startUsageIds: [], updatedSnapshotIds: []]
		def inventoryLevel = cloud.inventoryLevel ?: (cloud.getConfigProperty('importExisting') in [true, 'true', 'on'] ? 'basic' : 'off')
		List<Long> regionIds = []
		morpheusContext.cloud.region.listIdentityProjections(cloud.id).blockingSubscribe {
			regionIds << it.id
		}

		for(Long regionId in regionIds) {
			final ComputeZoneRegion region = morpheusContext.cloud.region.listById([regionId]).blockingFirst()
			def amazonClient = AmazonComputeUtility.getAmazonClient(cloud,false, region.code)
			def vmList = AmazonComputeUtility.listVpcServers([amazonClient: amazonClient, zone: cloud])
			if(vmList.success) {
				Observable<ComputeServerIdentityProjection> vmRecords = morpheusContext.computeServer.listIdentityProjections(cloud.id, region.code)
				SyncTask<ComputeServerIdentityProjection, Instance, ComputeServer> syncTask = new SyncTask<>(vmRecords, vmList.serverList.findAll{ instance -> instance.state?.code != 48 } as Collection<Instance>)
				syncTask.addMatchFunction { ComputeServerIdentityProjection domainObject, Instance cloudItem ->
					log.info("matching: ${domainObject} w/ ${cloudItem}")
					def match = domainObject.externalId == cloudItem.instanceId.toString()

					if(!match && !domainObject.externalId) {
						//check we don't have something being created with the same name - like terraform or cf
						if(!(domainObject.computeServerTypeCode in ['amazonEksKubeMaster', 'amazonRdsVm', 'amazonEksKubeMasterUnmanaged'])) {
							def instanceName = cloudItem.tags?.find { it.key == 'Name' }?.value ?: cloudItem.instanceId
							match = domainObject.name == instanceName || domainObject.internalIp == cloudItem.privateIpAddress || domainObject.externalIp == cloudItem.publicIpAddress
						}
					}
					match
				}.withLoadObjectDetailsFromFinder { List<UpdateItemDto<ComputeServerIdentityProjection, Instance>> updateItems ->
					morpheusContext.computeServer.listById(updateItems.collect { it.existingItem.id } as List<Long>)
				}.onAdd { itemsToAdd ->
					if(inventoryLevel in ['basic', 'full']) {
						addMissingVirtualMachines(itemsToAdd, region, vmList.volumeList, usageLists)
					}
				}.onUpdate { List<SyncTask.UpdateItem<ComputeServer, Instance>> updateItems ->
					//updateMatchedVirtualMachines(cloud, plans, hosts, resourcePools, networks, osTypes, updateItems, usageLists)
				}.onDelete { removeItems ->
					//removeMissingVirtualMachines(cloud, removeItems, blackListedNames)
				}.observe().blockingSubscribe { completed ->
					log.debug "sending usage start/stop/restarts: ${usageLists}"
					morpheusContext.usage.startServerUsage(usageLists.startUsageIds).blockingGet()
					morpheusContext.usage.stopServerUsage(usageLists.stopUsageIds).blockingGet()
					morpheusContext.usage.restartServerUsage(usageLists.restartUsageIds).blockingGet()
					morpheusContext.usage.restartSnapshotUsage(usageLists.updatedSnapshotIds).blockingGet()
				}
			} else {
				log.error("Error Caching VMs for Region: {}", region.code)
			}
		}
	}

	protected addMissingVirtualMachines(Collection<Instance> addList, ComputeZoneRegion region, Map<String, Volume> volumeMap, Map usageLists) {
		while(addList?.size() > 0) {
			addList.take(50).each { cloudItem ->
				def zonePool = allZonePools[cloudItem.vpcId]
				if(zonePool?.inventory) {
					// convert from projection
					if(!(zonePool instanceof ComputeZonePool)) {
						zonePool = morpheusContext.cloud.pool.listById([zonePool.id]).blockingFirst()
						allZonePools[cloudItem.vpcId] = zonePool
					}

					// get service plan
					def servicePlan = cloudItem.instanceType ? getServicePlan("amazon-${cloudItem.instanceType}") : null
					def osType = cloudItem.platform?.toLowerCase()?.contains('windows') ? 'windows' : 'linux'
					def vmConfig = [
						account: cloud.account,
						externalId: cloudItem.instanceId,
						resourcePool: zonePool,
						name: cloudItem.tags?.find { it.key == 'Name' }?.value ?: cloudItem.instanceId,
						externalIp: cloudItem.publicIpAddress,
						internalIp: cloudItem.privateIpAddress,
						osDevice: cloudItem.rootDeviceName,
						sshHost: cloudItem.privateIpAddress,
						serverType: 'vm',
						sshUsername: 'root',
						apiKey: java.util.UUID.randomUUID(),
						provision: false,
						singleTenant: true,
						cloud: cloud,
						lvmEnabled: false,
						discovered: true,
						managed: false,
						dateCreated: cloudItem.launchTime,
						status: 'provisioned',
						powerState: cloudItem.getState()?.getCode() == 16 ? ComputeServer.PowerState.on : ComputeServer.PowerState.off,
						osType: osType,
						serverOs: allOsTypes[osType] ?: new OsType(code: 'unknown'),
						region: region,
						computeServerType: allComputeServerTypes[osType == 'windows' ? 'amazonWindowsVm' : 'amazonUnmanaged'],
						plan: servicePlan,
						maxMemory: servicePlan?.maxMemory
					]

					ComputeServer add = new ComputeServer(vmConfig)
					ComputeServer savedServer = morpheusContext.computeServer.create(add).blockingGet()
					if (!savedServer) {
						log.error "Error in creating server ${add}"
					} else {
						performPostSaveSync(cloudItem, savedServer, volumeMap)
					}

					if (vmConfig.powerState == ComputeServer.PowerState.on) {
						usageLists.startUsageIds << savedServer.id
					} else {
						usageLists.stopUsageIds << savedServer.id
					}
				}
			}
			addList = addList.drop(50)
		}
	}

	private Boolean performPostSaveSync(Instance cloudItem, ComputeServer server, Map<String, Volume> volumeMap) {
		log.debug "performPostSaveSync: ${server?.id}"
		def changes = false
		def cacheResults = cacheVirtualMachineVolumes(cloudItem, server, volumeMap)

		if(server.maxStorage != cacheResults.maxStorage) {
			server.maxStorage = cacheResults.maxStorage
			changes = true
		}

		//set the plan on the server
		if(cloudItem.instanceType && server.plan?.code != "amazon-${cloudItem.instanceType}") {
			def servicePlan = getServicePlan("amazon-${cloudItem.instanceType}")
			if(servicePlan) {
				server.plan = servicePlan
				server.maxMemory = servicePlan.maxMemory
				changes = true
			}
		}

		//tags
		if(syncTags(server, cloudItem.getTags()?.collect{[key:it.getKey(), value:it.getValue()]} ?: [], [maxNameLength: 128, maxValueLength: 256])) {
			changes = true
		}
		//security groups
		syncSecurityGroups(server, cloudItem.getSecurityGroups()?.collect{ it.groupId })
		//network
		syncNetwork(server)

		if(changes) {
			morpheusContext.computeServer.save([server])
		}
	}

	private cacheVirtualMachineVolumes(Instance cloudItem, ComputeServer server, Map<String, Volume> volumeMap) {
		def rtn = [success:false, saveRequired:false, maxStorage:0L]
		try {
			//ignore servers that are being resized
			if(server.status == 'resizing') {
				log.warn("Ignoring server ${server} because it is resizing")
			} else {
				def matchMasterToValidFunc = { StorageVolume morpheusVolume, InstanceBlockDeviceMapping awsVolume ->
					morpheusVolume?.externalId == awsVolume?.getEbs()?.getVolumeId()
				}
				def existingItems = server.volumes
				def masterItems = cloudItem.getBlockDeviceMappings().sort {
					it.deviceName.replace('/dev/','').replace('xvd','').replace('vd','').replace('s','')
				}
				def syncLists = new SyncList(matchMasterToValidFunc).buildSyncLists(existingItems, masterItems)
				def createList = []
				def saveList = []

				// Process the adds
				syncLists.addList?.each { InstanceBlockDeviceMapping awsVolume ->
					log.debug("adding volume: ${awsVolume}")
					def volumeId = awsVolume.getEbs().getVolumeId()
					Volume awsVolumeTypeData = volumeMap[volumeId]
					def deviceIndex = masterItems.indexOf(awsVolume)
					if(awsVolumeTypeData) {
						log.debug("awsVolumeTypeData.getVolumeType(): ${awsVolumeTypeData.getVolumeType()}")
						def storageVolumeType = allStorageVolumeTypes["amazon-${awsVolumeTypeData.getVolumeType()}".toString()]
						if(storageVolumeType) {
							def maxStorage = (awsVolumeTypeData.getSize().toLong() * ComputeUtility.ONE_GIGABYTE)
							def volume = new StorageVolume(
								refType:'ComputeZone',
								refId:cloud.id,
								account:server.account,
								maxStorage:maxStorage,
								maxIOPS: awsVolumeTypeData.getIops(),
								type:storageVolumeType,
								zoneId:server.cloud.id,
								externalId:volumeId,
								deviceName:awsVolume.deviceName,
								name:volumeId,
								displayOrder: deviceIndex,
								rootVolume: awsVolume.deviceName == '/dev/sda1' || awsVolume.deviceName == '/dev/xvda'
							)
							createList << volume
							rtn.saveRequired = true
							rtn.maxStorage += maxStorage
						}
					}
				}
				if(createList) {
					morpheusContext.storageVolume.create(createList, server).blockingGet()
				}

				// Process updates
				syncLists.updateList?.each { updateMap ->
					log.debug("processing update item: ${updateMap}")
					StorageVolume existingVolume = updateMap.existingItem
					def volumeId = existingVolume.externalId
					def deviceIndex = masterItems.indexOf(updateMap.masterItem)

					Volume awsVolumeTypeData = volumeMap[volumeId]
					def save = false
					def maxStorage = awsVolumeTypeData ? (awsVolumeTypeData.getSize().toLong() * ComputeUtility.ONE_GIGABYTE) : 0l
					if(awsVolumeTypeData && existingVolume.maxStorage != maxStorage) {
						existingVolume.maxStorage = maxStorage
						save = true
					}
					if(existingVolume.displayOrder != deviceIndex) {
						existingVolume.displayOrder = deviceIndex
						save = true
					}
					def rootVolume = ['/dev/sda1','/dev/xvda','xvda','sda1','sda'].contains(updateMap.masterItem?.deviceName)
					if( rootVolume != existingVolume.rootVolume) {
						existingVolume.rootVolume = rootVolume
						save = true
					}
					if(awsVolumeTypeData.getIops() != existingVolume.maxIOPS) {
						existingVolume.maxIOPS = awsVolumeTypeData.getIops()
						save = true
					}
					if(save) {
						rtn.saveRequired = true
						saveList << existingVolume
					}
					rtn.maxStorage += maxStorage
				}
				if(saveList) {
					morpheusContext.storageVolume.save(saveList).blockingGet()
				}

				// Process removes
				if(syncLists.removeList) {
					morpheusContext.storageVolume.remove(syncLists.removeList, server, false).blockingGet()
					rtn.saveRequired = true
				}
			}
		} catch(e) {
			log.error("error cacheVirtualMachineVolumes ${e}", e)
		}
		rtn
	}

	/**
	 * Creates or removes the associations for a server given a master list of sec group external ids
	 * @param server
	 * @param cloudSecGroupExternalIds list of security groups external ids associated for the server as obtained from the cloud
	 */
	def syncSecurityGroups(ComputeServer server, List<String> cloudSecGroupExternalIds) {
		log.debug "syncSecurityGroups: ${server}, ${cloudSecGroupExternalIds}"
		try {
			List<SecurityGroupLocationIdentityProjection> securityGroupLocations = []
			morpheusContext.securityGroup.location.listSyncProjections(cloud.id, server.resourcePool, null).blockingSubscribe {
				if(cloudSecGroupExternalIds.contains(it.externalId)) {
					securityGroupLocations << it
				}
			}
			morpheusContext.securityGroup.location.syncAssociations(securityGroupLocations, server)
		} catch(e) {
			log.error "error in sync security groups: ${e}", e
		}
	}

	def syncNetwork(ComputeServer server, String subnetId) {
		ComputeServerInterface nic

		if(server.internalIp) {
			Network subnet

			if(subnetId) {
				morpheusContext.network.listByCloudAndExternalIdIn(cloud.id, [subnetId]).blockingSubscribe {
					if(it.typeCode == 'amazonSubnet') {
						subnet = it
					}
				}
			}

			if(server.interfaces) {
				nic =
					server.interfaces.find { it.ipAddress == server.internalIp } ?:
					server.interfaces.find{it.primaryInterface == true} ?:
					server.interfaces.find{it.displayOrder == 0} ?:
					server.interfaces[0]
			}

			if(nic == null) {
				def interfaceName = server.sourceImage?.interfaceName ?: 'eth0'
				nic = new ComputeServerInterface(
					name:interfaceName,
					ipAddress:server.internalIp,
					publicIpAddress:server.externalIp,
					primaryInterface:true,
					subnet:subnet,
					displayOrder:(server.interfaces?.size() ?: 0) + 1
				)
				morpheusContext.computeServer.computeServerInterface.create([nic], server)
			}
			else {
				def doSave = false
				if (server.externalIp && server.externalIp != nic.publicIpAddress) {
					nic.publicIpAddress = server.externalIp
					doSave = true
				}
				if(subnet && nic.subnet?.id != subnet.id) {
					nic.subnet = subnet
					doSave = true
				}
				if(doSave) {
					morpheusContext.computeServer.computeServerInterface.save([nic])
				}
			}
		}
	}

	private syncTags(ComputeServer server, tagList, opts = [:]) {
		def changes = false
		def matchMasterToValidFunc = { MetadataTag metadataTag, tagMap ->
			def internalName = metadataTag.name?.trim()
			def externalName = tagMap.key?.toString()?.trim()
			//Match truncated names from cloud based on cloud
			def (truncatedName, truncatedNoPeriods) = [false, false]
			if(opts?.maxNameLength) {
				truncatedName = AmazonComputeUtility.truncateElipsis(internalName, opts.maxNameLength - 3)
				truncatedNoPeriods = truncatedName.replaceAll(/\./,'')
			}
			internalName == externalName || truncatedName == externalName || truncatedNoPeriods == externalName
		}
		def existingItems = server.metadata
		def masterItems = tagList.findAll { it.key != 'Name'}
		def syncLists = new SyncList(matchMasterToValidFunc).buildSyncLists(existingItems, masterItems)

		// Process adds
		def createList = []
		syncLists.addList?.each { tagMap ->
			log.debug("adding tag: ${tagMap}")
			createList << new MetadataTag(name: tagMap.key, value: tagMap.value)
		}
		if(createList) {
			morpheusContext.metadataTag.create(createList, server)
			changes = true
		}

		// Process updates
		def saveList = []
		syncLists.updateList?.each { updateMap ->
			log.debug("processing update item: ${updateMap}")
			MetadataTag existingTag = updateMap.existingItem
			def existingValue = existingTag.value?.trim()
			def newValue = updateMap.masterItem.value?.trim()
			def (truncatedValue, truncatedNoPeriods) = [true, true]
			if(opts?.maxValueLength) {
				truncatedValue = AmazonComputeUtility.truncateElipsis(existingValue, opts.maxValueLength - 3)
				truncatedNoPeriods = truncatedValue?.replaceAll(/\./,'')
			}
			//Don't update with truncated values
			def shouldUpdateValue = existingValue != newValue && truncatedValue != newValue && truncatedNoPeriods != newValue
			if(shouldUpdateValue) {
				existingTag.value = updateMap.masterItem.value
				saveList << existingTag
			}
		}
		if(saveList) {
			morpheusContext.metadataTag.save(saveList)
			changes = true
		}

		// Process removes
		if(syncLists.removeList) {
			morpheusContext.metadataTag.remove(syncLists.removeList)
			changes = true
		}

		if(changes) {
			//lets see if we have any instance metadata that needs updated
			if(server.computeServerType?.containerHypervisor != true && server.computeServerType?.vmHypervisor != true) {
				def instanceIds = morpheusContext.cloud.getStoppedContainerInstanceIds(server.id).blockingSubscribe { it.id }
				morpheusContext.instance.listById(instanceIds).blockingSubscribe { instance ->
					syncTags(instance, server.metadata, opts)
				}
			}
		}
		changes
	}

	private syncTags(com.morpheusdata.model.Instance instance, List<MetadataTag> serverTags, opts = [:]) {
		def saveRequired = false
		def matchMasterToValidFunc = { MetadataTag metadataTag, tagMap ->
			def internalName = metadataTag.name?.trim()
			def externalName = tagMap.name?.toString()
			//Match truncated names from cloud based on cloud
			def truncatedName = false
			if(opts?.maxNameLength) {
				truncatedName = MorpheusUtils.truncateElipsis(internalName, opts.maxNameLength - 3)
			}
			return internalName == externalName || truncatedName == externalName
		}
		def syncLists = new SyncList(matchMasterToValidFunc).buildSyncLists(instance.metadata, serverTags)

		// Process adds
		def createList = []
		syncLists.addList?.each { tagMap ->
			log.debug("adding tag: ${tagMap}")
			createList << new MetadataTag(name: tagMap.key, value: tagMap.value)
		}
		if(createList) {
			morpheusContext.metadataTag.create(createList, instance)
			saveRequired = true
		}

		// Process updates
		def saveList = []
		syncLists.updateList?.each { updateMap ->
			log.debug("processing update item: ${updateMap}")
			MetadataTag existingTag = updateMap.existingItem
			def existingValue = existingTag.value?.trim()
			def newValue = updateMap.masterItem.value?.trim()
			def truncatedValue = true
			if(opts?.maxValueLength) {
				truncatedValue = AmazonComputeUtility.truncateElipsis(existingValue, opts.maxValueLength - 3)
			}
			//Don't update with truncated values
			def shouldUpdateValue = existingValue != newValue && truncatedValue != newValue
			if(shouldUpdateValue) {
				existingTag.value = updateMap.masterItem.value
				saveList << existingTag
			}
		}
		if(saveList) {
			morpheusContext.metadataTag.save(saveList)
			saveRequired = true
		}

		// Process removes
		if(syncLists.removeList) {
			morpheusContext.metadataTag.remove(syncLists.removeList)
			saveRequired = true
		}

		if(saveRequired) {
			morpheusContext.instance.save([instance])
		}
		return saveRequired
	}

	private Map<String, ComputeServerType> getAllComputeServerTypes() {
		computeServerTypes ?: (computeServerTypes = morpheusContext.cloud.getComputeServerTypes(cloud.id).blockingGet().collectEntries {[it.code, it]})
	}

	private Map<String, OsType> getAllOsTypes() {
		osTypes ?: (osTypes = morpheusContext.osType.listAll().toMap {it.code}.blockingGet())
	}

	private Map<String, ComputeZonePoolIdentityProjection> getAllZonePools() {
		zonePools ?: (zonePools = morpheusContext.cloud.pool.listSyncProjections(cloud.id, '').toMap {it.externalId}.blockingGet())
	}

	private Map<String, ServicePlanIdentityProjection> getAllServicePlans() {
		servicePlans ?: (servicePlans = morpheusContext.servicePlan.listSyncProjections(cloud.id).toMap { it.code }.blockingGet())
	}

	private ServicePlan getServicePlan(String code) {
		def servicePlan = allServicePlans[code]
		if(servicePlan && !(servicePlan instanceof ServicePlan)) {
			servicePlan = morpheusContext.servicePlan.listById([servicePlan.id]).blockingFirst()
			allServicePlans[code] = servicePlan
		}
		servicePlan
	}

	private Map<String, StorageVolumeType> getAllStorageVolumeTypes() {
		storageVolumeTypes ?: (storageVolumeTypes = morpheusContext.storageVolume.storageVolumeType.listAll().toMap {it.code }.blockingGet())
	}
}
