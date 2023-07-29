package com.morpheusdata.aws.sync

import com.amazonaws.services.ec2.model.Instance
import com.amazonaws.services.ec2.model.InstanceBlockDeviceMapping
import com.amazonaws.services.ec2.model.Volume
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
import com.morpheusdata.model.ProvisionType
import com.morpheusdata.model.ServicePlan
import com.morpheusdata.model.StorageVolume
import com.morpheusdata.model.StorageVolumeType
import com.morpheusdata.model.Workload
import com.morpheusdata.model.projection.ComputeServerIdentityProjection
import com.morpheusdata.model.projection.ComputeZonePoolIdentityProjection
import com.morpheusdata.model.projection.ComputeZoneRegionIdentityProjection
import com.morpheusdata.model.projection.SecurityGroupLocationIdentityProjection
import com.morpheusdata.model.projection.ServicePlanIdentityProjection
import com.morpheusdata.model.projection.WorkloadIdentityProjection
import groovy.util.logging.Slf4j
import io.reactivex.Observable

@Slf4j
class VirtualMachineSync {
	private Cloud cloud
	private MorpheusContext morpheusContext
	private AWSPlugin plugin
	private Map<String, ComputeServerType> computeServerTypes
	private Map<String, ServicePlanIdentityProjection> servicePlans
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
		morpheusContext.cloud.region.listIdentityProjections(cloud.id).blockingSubscribe { region ->
			def amazonClient = AmazonComputeUtility.getAmazonClient(cloud,false, region.externalId)
			def vmList = AmazonComputeUtility.listVpcServers([amazonClient: amazonClient, zone: cloud])
			if(vmList.success) {
				Observable<ComputeServerIdentityProjection> vmRecords = morpheusContext.computeServer.listIdentityProjections(cloud.id, region.externalId)
				SyncTask<ComputeServerIdentityProjection, Instance, ComputeServer> syncTask = new SyncTask<>(vmRecords, vmList.serverList.findAll{ instance -> instance.state?.code != 48 } as Collection<Instance>)
				syncTask.addMatchFunction { ComputeServerIdentityProjection domainObject, Instance cloudItem ->
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
					updateMatchedVirtualMachines(updateItems, region, vmList.volumeList, usageLists, inventoryLevel)
				}.onDelete { removeItems ->
					removeMissingVirtualMachines(removeItems)
				}.observe().blockingSubscribe { completed ->
					log.debug "sending usage start/stop/restarts: ${usageLists}"
					morpheusContext.usage.startServerUsage(usageLists.startUsageIds).blockingGet()
					morpheusContext.usage.stopServerUsage(usageLists.stopUsageIds).blockingGet()
					morpheusContext.usage.restartServerUsage(usageLists.restartUsageIds).blockingGet()
					morpheusContext.usage.restartSnapshotUsage(usageLists.updatedSnapshotIds).blockingGet()
				}
			} else {
				log.error("Error Caching VMs for Region: {}", region.externalId)
			}
		}
	}

	def addMissingVirtualMachines(List<Instance> addList, ComputeZoneRegionIdentityProjection region, Map<String, Volume> volumeMap, Map usageLists = null, String defaultServerType = null) {
		while (addList?.size() > 0) {
			addList.take(50).each { cloudItem ->
				def zonePool = allZonePools[cloudItem.vpcId]
				if ((!cloudItem.getVpcId() || zonePool?.inventory != false) && cloudItem.getState()?.getCode() != 0) {
					// get service plan
					def servicePlan = cloudItem.instanceType ? getServicePlan("amazon-${cloudItem.instanceType}") : null
					def osType = cloudItem.platform?.toLowerCase()?.contains('windows') ? 'windows' : 'linux'
					def vmConfig = [
						account          : cloud.account,
						externalId       : cloudItem.instanceId,
						resourcePool     : zonePool ? new ComputeZonePool(id:zonePool.id) : null,
						name             : cloudItem.tags?.find { it.key == 'Name' }?.value ?: cloudItem.instanceId,
						externalIp       : cloudItem.publicIpAddress,
						internalIp       : cloudItem.privateIpAddress,
						osDevice         : cloudItem.rootDeviceName,
						sshHost          : cloudItem.privateIpAddress,
						serverType       : 'vm',
						sshUsername      : 'root',
						apiKey           : java.util.UUID.randomUUID(),
						provision        : false,
						singleTenant     : true,
						cloud            : cloud,
						lvmEnabled       : false,
						discovered       : true,
						managed          : false,
						dateCreated      : cloudItem.launchTime,
						status           : 'provisioned',
						powerState       : cloudItem.getState()?.getCode() == 16 ? ComputeServer.PowerState.on : ComputeServer.PowerState.off,
						osType           : osType,
						serverOs         : allOsTypes[osType] ?: new OsType(code: 'unknown'),
						region           : new ComputeZoneRegion(id: region.id),
						computeServerType: allComputeServerTypes[defaultServerType ?: (osType == 'windows' ? 'amazonWindowsVm' : 'amazonUnmanaged')],
						maxMemory        : servicePlan?.maxMemory
					]

					ComputeServer add = new ComputeServer(vmConfig)
					if (servicePlan) {
						applyServicePlan(add, servicePlan)
					}
					ComputeServer savedServer = morpheusContext.computeServer.create(add).blockingGet()
					if (!savedServer) {
						log.error "Error in creating server ${add}"
					} else {
						def postSaveResults = performPostSaveSync(cloudItem, savedServer, volumeMap)
						if (postSaveResults.saveRequired) {
							morpheusContext.computeServer.save([savedServer]).blockingGet()
						}
					}

					if(usageLists) {
						if (vmConfig.powerState == ComputeServer.PowerState.on) {
							usageLists.startUsageIds << savedServer.id
						} else {
							usageLists.stopUsageIds << savedServer.id
						}
					}
				} else {
					log.info("skipping: {}", cloudItem)
				}
			}
			addList = addList.drop(50)
		}
	}

	def updateMatchedVirtualMachines(List<SyncTask.UpdateItem<ComputeServer, Instance>> updateList, ComputeZoneRegionIdentityProjection region, Map<String, Volume> volumeMap, Map usageLists, String inventoryLevel) {
		def statsData = []
		def managedServerIds = updateList.findAll { it.existingItem.computeServerType?.managed }.collect{it.existingItem.id}
		def workloads = managedServerIds ? morpheusContext.cloud.listCloudWorkloadProjections(cloud.id).filter { workload ->
			workload.serverId in managedServerIds
		}.toMap {it.serverId}.blockingGet() : [:]

		for(update in updateList) {
			ComputeServer currentServer = update.existingItem
			Instance cloudItem = update.masterItem

			if(currentServer.status != 'provisioning') {
				try {
					def save = false
					def name = cloudItem.tags?.find { it.key == 'Name' }?.value ?: cloudItem.instanceId
					def zonePool = allZonePools[cloudItem.vpcId]
					def powerState = cloudItem.state?.code == 16 ? ComputeServer.PowerState.on : ComputeServer.PowerState.off

					if(!currentServer.computeServerType) {
						currentServer.computeServerType = allComputeServerTypes['amazonUnmanaged']
						save = true
					}
					if(currentServer.region?.externalId != region.externalId) {
						currentServer.region = new ComputeZoneRegion(id: region.id)
						save = true
					}
					if(name != currentServer.name) {
						currentServer.name = name
						save = true
					}
					if(currentServer.resourcePool?.id != zonePool?.id) {
						currentServer.resourcePool = zonePool?.id ? new ComputeZonePool(id:zonePool.id) : null
						save = true
					}

					if(currentServer.externalIp != cloudItem.publicIpAddress) {
						if(currentServer.externalIp == currentServer.sshHost) {
							if(cloudItem.publicIpAddress) {
								currentServer.sshHost = cloudItem.publicIpAddress
							} else if(powerState == ComputeServer.PowerState.off) {
								currentServer.sshHost = currentServer.internalIp
							} else {
								currentServer.sshHost = null
							}
						}
						currentServer.externalIp = cloudItem.publicIpAddress
						save = true
					}
					if(currentServer.internalIp != cloudItem.privateIpAddress) {
						if(currentServer.internalIp == currentServer.sshHost) {
							currentServer.sshHost = cloudItem.privateIpAddress
						}
						currentServer.internalIp = cloudItem.privateIpAddress
						save = true
					}

					if(powerState != currentServer.powerState) {
						currentServer.powerState = powerState
						if (currentServer.computeServerType?.guestVm) {
							morpheusContext.computeServer.updatePowerState(currentServer.id, currentServer.powerState).blockingGet()
						}
					}

					if (save) {
						currentServer = saveAndGet(currentServer)
						save = false
					}

					def postSaveResults = performPostSaveSync(cloudItem, currentServer, volumeMap)

					if(postSaveResults.planChanged) {
						if(currentServer.computeServerType?.guestVm) {
							updateServerContainersAndInstances(currentServer, currentServer.plan)
						}
					}

					//check for restart usage records
					if(postSaveResults.saveRequired) {
						if (!usageLists.stopUsageIds.contains(currentServer.id) && !usageLists.startUsageIds.contains(currentServer.id)) {
							usageLists.restartUsageIds << currentServer.id
						}
						save = true
					}

					if (inventoryLevel == 'full' && currentServer.status != 'provisioning' &&
						(currentServer.agentInstalled == false || currentServer.powerState == ComputeServer.PowerState.off || currentServer.powerState == ComputeServer.PowerState.paused)) {
						statsData += updateVirtualMachineStats(currentServer, workloads)
						save = true
					}

					if (save) {
						morpheusContext.computeServer.save([currentServer]).blockingGet()
					}
				} catch(e) {
					log.warn("Error Updating Virtual Machine ${currentServer?.name} - ${currentServer.externalId} - ${e}", e)
				}
			}
		}
		if(statsData) {
			for(statData in statsData) {
				morpheusContext.stats.updateWorkloadStats(new WorkloadIdentityProjection(id: statData.workload.id), statData.maxMemory, statData.maxUsedMemory, statData.maxStorage, statData.maxUsedStorage, statData.cpuPercent, statData.running)
			}
		}
	}

	def removeMissingVirtualMachines(List<ComputeServerIdentityProjection> removeList) {
		log.debug "removeMissingVirtualMachines: ${cloud} ${removeList.size()}"
		morpheusContext.computeServer.remove(removeList).blockingGet()
	}

	private applyServicePlan(ComputeServer server, ServicePlan servicePlan) {
		server.plan = servicePlan
		server.maxCores = servicePlan.maxCores
		server.maxCpu = servicePlan.maxCpu
		server.maxMemory = servicePlan.maxMemory
		if(server.computeCapacityInfo) {
			server.computeCapacityInfo.maxCores = server.maxCores
			server.computeCapacityInfo.maxCpu = server.maxCpu
			server.computeCapacityInfo.maxMemory = server.maxMemory
		}
	}

	private performPostSaveSync(Instance cloudItem, ComputeServer server, Map<String, Volume> volumeMap) {
		log.debug "performPostSaveSync: ${server?.id}"
		def saveRequired, planChanged, tagsChanged
		def cacheVolumesResults = cacheVirtualMachineVolumes(cloudItem, server, volumeMap)

		if(cacheVolumesResults.maxStorage && server.maxStorage != cacheVolumesResults.maxStorage) {
			server.maxStorage = cacheVolumesResults.maxStorage
			planChanged = true
			saveRequired = true
		}

		if(cacheVolumesResults.saveRequired == true) {
			saveRequired = true
		}

		// Set the plan on the server
		if(server.plan?.code != "amazon-${cloudItem.getInstanceType()}") {
			def servicePlan = getServicePlan("amazon-${cloudItem.getInstanceType()}")
			if(servicePlan) {
				applyServicePlan(server, servicePlan)
				planChanged = true
				saveRequired = true
			}
		}

		//tags
		if(syncTags(server, cloudItem.getTags()?.collect{[key:it.getKey(), value:it.getValue()]} ?: [], [maxNameLength: 128, maxValueLength: 256])) {
			tagsChanged = true
		}

		//security groups
		if(server.computeServerType?.managed) {
			syncSecurityGroups(server, cloudItem.getSecurityGroups()?.collect { it.groupId })
		}

		//network
		syncNetwork(server)

		[saveRequired:saveRequired, planChanged:planChanged, tagsChanged:tagsChanged]
	}

	private cacheVirtualMachineVolumes(Instance cloudItem, ComputeServer server, Map<String, Volume> volumeMap) {
		def rtn = [success:false, saveRequired:false, maxStorage:0L]
		try {
			//ignore servers that are being resized
			if(server.status == 'resizing') {
				log.warn("Ignoring server ${server} because it is resizing")
			} else {
				SyncList.MatchFunction matchMasterToValidFunc = { StorageVolume morpheusVolume, InstanceBlockDeviceMapping awsVolume ->
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
								refType: 'ComputeZone',
								refId: cloud.id,
								regionCode: server.region?.regionCode,
								account: server.account,
								maxStorage: maxStorage,
								maxIOPS: awsVolumeTypeData.getIops(),
								type: storageVolumeType,
								externalId: volumeId,
								deviceName: awsVolume.deviceName,
								deviceDisplayName: AmazonComputeUtility.extractDiskDisplayName(awsVolume.deviceName)?.replaceAll('sd', 'xvd'),
								name: volumeId,
								displayOrder: deviceIndex,
								status: 'provisioned',
								rootVolume: ['/dev/sda1','/dev/xvda','xvda','sda1','sda'].contains(awsVolume.deviceName)
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
					def deviceDisplayName = AmazonComputeUtility.extractDiskDisplayName(updateMap.masterItem.deviceName)?.replaceAll('sd', 'xvd')
					if(existingVolume.deviceDisplayName != deviceDisplayName) {
						existingVolume.deviceDisplayName = deviceDisplayName
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
					if(existingVolume.regionCode != server.region?.regionCode) {
						existingVolume.regionCode = server.region.regionCode
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
	private syncSecurityGroups(ComputeServer server, List<String> cloudSecGroupExternalIds) {
		log.debug "syncSecurityGroups: ${server}, ${cloudSecGroupExternalIds}"
		try {
			List<SecurityGroupLocationIdentityProjection> securityGroupLocations = []
			morpheusContext.securityGroup.location.listSyncProjections(cloud.id, server.resourcePool.id, null).blockingSubscribe {
				if(cloudSecGroupExternalIds.contains(it.externalId)) {
					securityGroupLocations << it
				}
			}
			morpheusContext.securityGroup.location.syncAssociations(server, securityGroupLocations)
		} catch(e) {
			log.error "error in sync security groups: ${e}", e
		}
	}

	private syncNetwork(ComputeServer server, String subnetId = null) {
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
		SyncList.MatchFunction matchMasterToValidFunc = { MetadataTag metadataTag, tagMap ->
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

				if(instanceIds) {
					morpheusContext.instance.listById(instanceIds).blockingSubscribe { instance ->
						syncTags(instance, server.metadata, opts)
					}
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
				truncatedName = AmazonComputeUtility.truncateElipsis(internalName, opts.maxNameLength - 3)
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

	private updateServerContainersAndInstances(ComputeServer currentServer, ServicePlan plan) {
		log.debug "updateServerContainersAndInstances: ${currentServer}"
		try {
			// Save the workloads
			def instanceIds = []
			def workloads = getWorkloadsForServer(currentServer)
			for(Workload workload in workloads) {
				workload.plan = plan
				workload.maxCores = currentServer.maxCores
				workload.maxMemory = currentServer.maxMemory
				workload.coresPerSocket = currentServer.coresPerSocket
				workload.maxStorage = currentServer.maxStorage
				def instanceId = workload.instance?.id
				morpheusContext.cloud.saveWorkload(workload).blockingGet()

				if(instanceId) {
					instanceIds << instanceId
				}
			}

			if(instanceIds) {
				def instancesToSave = []
				def instances = morpheusContext.instance.listById(instanceIds).toList().blockingGet()
				instances.each { Instance instance ->
					if(plan) {
						if (instance.containers.every { cnt -> (cnt.plan.id == currentServer.plan.id && cnt.maxMemory == currentServer.maxMemory && cnt.maxCores == currentServer.maxCores && cnt.coresPerSocket == currentServer.coresPerSocket) || cnt.server.id == currentServer.id }) {
							log.debug("Changing Instance Plan To : ${plan.name} - memory: ${currentServer.maxMemory} for ${instance.name} - ${instance.id}")
							instance.plan = plan
							instance.maxCores = currentServer.maxCores
							instance.maxMemory = currentServer.maxMemory
							instance.maxStorage = currentServer.maxStorage
							instance.coresPerSocket = currentServer.coresPerSocket
							instancesToSave << instance
						}
					}
				}
				if(instancesToSave.size() > 0) {
					morpheusContext.instance.save(instancesToSave).blockingGet()
				}
			}
		} catch(e) {
			log.error "Error in updateServerContainersAndInstances: ${e}", e
		}
	}

	private def updateVirtualMachineStats(ComputeServer server, Map<Long, WorkloadIdentityProjection> workloads = [:]) {
		def statsData = []
		try {
			def maxUsedStorage = 0
			if (server.agentInstalled && server.usedStorage) {
				maxUsedStorage = server.usedStorage
			}

			def workload = workloads[server.id]
			if (workload) {
				statsData << [
					workload      : workload,
					maxMemory     : server.maxMemory,
					maxStorage    : server.maxStorage,
					maxUsedStorage: maxUsedStorage,
					cpuPercent    : server.usedCpu,
					running       : server.powerState == ComputeServer.PowerState.on
				]
			}
		} catch (e) {
			log.warn("error updating vm stats: ${e}", e)
			return []
		}
		return statsData
	}

	private getWorkloadsForServer(ComputeServer currentServer) {
		def workloads = []
		def projections = morpheusContext.cloud.listCloudWorkloadProjections(cloud.id).filter { it.serverId == currentServer.id }.toList().blockingGet()
		for(proj in projections) {
			workloads << morpheusContext.cloud.getWorkloadById(proj.id).blockingGet()
		}
		workloads
	}

	private ComputeServer saveAndGet(ComputeServer server) {
		def saveSuccessful = morpheusContext.computeServer.save([server]).blockingGet()
		if(!saveSuccessful) {
			log.warn("Error saving server: ${server?.id}" )
		}
		return morpheusContext.computeServer.get(server.id).blockingGet()
	}

	private Map<String, ComputeServerType> getAllComputeServerTypes() {
		computeServerTypes ?: (computeServerTypes = morpheusContext.cloud.getComputeServerTypes(cloud.id).blockingGet().collectEntries {[it.code, it]})
	}

	private Map<String, OsType> getAllOsTypes() {
		osTypes ?: (osTypes = morpheusContext.osType.listAll().toMap {it.code}.blockingGet())
	}

	private Map<String, ComputeZonePoolIdentityProjection> getAllZonePools() {
		zonePools ?: (zonePools = morpheusContext.cloud.pool.listIdentityProjections(cloud.id, '', null).toMap {it.externalId}.blockingGet())
	}

	private Map<String, ServicePlanIdentityProjection> getAllServicePlans() {
		servicePlans ?: (servicePlans = morpheusContext.servicePlan.listSyncProjections(new ProvisionType(code:'amazon')).toMap { it.code }.blockingGet())
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
