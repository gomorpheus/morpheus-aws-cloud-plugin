package com.morpheusdata.aws.sync

import com.amazonaws.services.ec2.model.Instance
import com.amazonaws.services.ec2.model.InstanceBlockDeviceMapping
import com.amazonaws.services.ec2.model.Volume
import com.morpheusdata.aws.AWSPlugin
import com.morpheusdata.aws.utils.AmazonComputeUtility
import com.morpheusdata.core.BulkSaveResult
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.util.ComputeUtility
import com.morpheusdata.core.util.SyncList
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.core.util.SyncTask.UpdateItemDto
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.CloudPool
import com.morpheusdata.model.CloudRegion
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerInterface
import com.morpheusdata.model.ComputeServerType
import com.morpheusdata.model.MetadataTag
import com.morpheusdata.model.Network
import com.morpheusdata.model.OsType
import com.morpheusdata.model.ServicePlan
import com.morpheusdata.model.StorageVolume
import com.morpheusdata.model.StorageVolumeType
import com.morpheusdata.model.Workload
import com.morpheusdata.model.projection.CloudPoolIdentity
import com.morpheusdata.model.projection.CloudRegionIdentity
import com.morpheusdata.model.projection.ComputeServerIdentityProjection
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
	private Map<String, CloudPoolIdentity> zonePools
	private Map<String, OsType> osTypes
	private Map<String, StorageVolumeType> storageVolumeTypes

	VirtualMachineSync(AWSPlugin plugin, Cloud cloud) {
		this.plugin = plugin
		this.cloud = cloud
		this.morpheusContext = plugin.morpheusContext
	}

	def execute() {
		try {
			def inventoryLevel = cloud.inventoryLevel ?: (cloud.getConfigProperty('importExisting') in [true, 'true', 'on'] ? 'basic' : 'off')
			morpheusContext.async.cloud.region.listIdentityProjectionsForRegionsWithCloudPools(cloud.id).blockingSubscribe { region ->
				def amazonClient = plugin.getAmazonClient(cloud,false, region.externalId)
				def vmList = AmazonComputeUtility.listVpcServers([amazonClient: amazonClient, cloud: cloud])
				if(vmList.success) {
					Observable<ComputeServerIdentityProjection> vmRecords = morpheusContext.async.computeServer.listIdentityProjections(cloud.id, region.externalId)
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
						morpheusContext.async.computeServer.listById(updateItems.collect { it.existingItem.id } as List<Long>)
					}.onAdd { itemsToAdd ->
						if(inventoryLevel in ['basic', 'full']) {
							addMissingVirtualMachines(itemsToAdd, region, vmList.volumeList)
						}
					}.onUpdate { List<SyncTask.UpdateItem<ComputeServer, Instance>> updateItems ->
						updateMatchedVirtualMachines(updateItems, region, vmList.volumeList, inventoryLevel)
					}.onDelete { removeItems ->
						removeMissingVirtualMachines(removeItems)
					}.observe().blockingSubscribe()
				} else {
					log.error("Error Caching VMs for Region: {}", region.externalId)
				}
			}
		} catch(Exception ex) {
			log.error("VirtualMachineSync error: {}", ex, ex)
		}
	}

	def addMissingVirtualMachines(List<Instance> addList, CloudRegionIdentity region, Map<String, Volume> volumeMap, String defaultServerType = null) {
		while (addList) {
			Map<String, Instance> cloudItems = [:]
			List<ComputeServer> adds = []
			for(Instance cloudItem : addList.take(50)) {
				cloudItems[cloudItem.instanceId] = cloudItem
				def zonePool = allZonePools[cloudItem.vpcId]
				if ((!cloudItem.getVpcId() || zonePool?.inventory != false) && cloudItem.getState()?.getCode() != 0) {
					// get service plan
					def servicePlan = cloudItem.instanceType ? amazonServicePlans["amazon-${cloudItem.instanceType}".toString()] : null
					def osType = cloudItem.platform?.toLowerCase()?.contains('windows') ? 'windows' : 'linux'
					ComputeServer add = new ComputeServer(
						account: cloud.account,
						externalId: cloudItem.instanceId,
						resourcePool: zonePool ? new CloudPool(id: zonePool.id) : null,
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
						region: new CloudRegion(id: region.id),
						computeServerType: allComputeServerTypes[defaultServerType ?: (osType == 'windows' ? 'amazonWindows' : 'amazonUnmanaged')],
						maxMemory: servicePlan?.maxMemory,
						configMap: [
							blockDevices  : cloudItem.blockDeviceMappings?.collect { [name: it.deviceName, ebs: it.ebs] } ?: [],
							imageId       : cloudItem.imageId,
							instanceType  : cloudItem.instanceType,
							keyName       : cloudItem.keyName,
							launchTime    : cloudItem.launchTime,
							privateDnsName: cloudItem.privateDnsName,
							publicDnsName : cloudItem.publicDnsName,
							securityGroups: cloudItem.securityGroups?.collect { [id: it.groupId, name: it.groupName] } ?: [],
							subnetId      : cloudItem.subnetId,
							tags          : cloudItem.tags?.collect { [key: it.key, value: it.value] } ?: [],
							vpcId         : cloudItem.vpcId,
							vpc           : cloudItem.vpcId,
							architecture  : cloudItem.architecture,
							clientToken   : cloudItem.clientToken,
							ebsOptimized  : cloudItem.ebsOptimized
						]
					)
					if (servicePlan) {
						applyServicePlan(add, servicePlan)
					}
					adds << add
				} else {
					log.info("skipping: {}", cloudItem)
				}
			}

			if(adds) {
				List<ComputeServer> saves = []
				adds = morpheusContext.async.computeServer.bulkCreate(adds).blockingGet().getPersistedItems()

				for(ComputeServer server : adds) {
					if(performPostSaveSync(cloudItems[server.externalId], server, volumeMap).saveRequired) {
						saves << server
					}
					if(usageLists) {
						if (server.powerState == ComputeServer.PowerState.on) {
							usageLists.startUsageIds << server.id
						} else {
							usageLists.stopUsageIds << server.id
						}
					}
				}
				if(saves) {
					morpheusContext.async.computeServer.bulkSave(saves).blockingGet()
				}
			}
			addList = addList.drop(50)
		}
	}

	void updateMatchedVirtualMachines(List<SyncTask.UpdateItem<ComputeServer, Instance>> updateList, CloudRegionIdentity region, Map<String, Volume> volumeMap, String inventoryLevel) {
		def statsData = []

		List<ComputeServer> saves = []

		for(update in updateList) {
			ComputeServer currentServer = update.existingItem
			Instance cloudItem = update.masterItem

			if (currentServer.status != 'provisioning') {
				try {
					def save = false
					def name = cloudItem.tags?.find { it.key == 'Name' }?.value ?: cloudItem.instanceId
					def zonePool = allZonePools[cloudItem.vpcId]
					def powerState = cloudItem.state?.code == 16 ? ComputeServer.PowerState.on : ComputeServer.PowerState.off

					if (!currentServer.computeServerType) {
						currentServer.computeServerType = allComputeServerTypes['amazonUnmanaged']
						save = true
					}
					if (currentServer.region?.externalId != region.externalId) {
						currentServer.region = new CloudRegion(id: region.id)
						save = true
					}
					if (name != currentServer.name) {
						currentServer.name = name
						save = true
					}
					if (currentServer.resourcePool?.id != zonePool?.id) {
						currentServer.resourcePool = zonePool?.id ? new CloudPool(id: zonePool.id) : null
						save = true
					}
					if (currentServer.externalIp != cloudItem.publicIpAddress) {
						if (currentServer.externalIp == currentServer.sshHost) {
							if (cloudItem.publicIpAddress) {
								currentServer.sshHost = cloudItem.publicIpAddress
							} else if (powerState == ComputeServer.PowerState.off) {
								currentServer.sshHost = currentServer.internalIp
							} else {
								currentServer.sshHost = null
							}
						}
						currentServer.externalIp = cloudItem.publicIpAddress
						save = true
					}
					if (currentServer.internalIp != cloudItem.privateIpAddress) {
						if (currentServer.internalIp == currentServer.sshHost) {
							currentServer.sshHost = cloudItem.privateIpAddress
						}
						currentServer.internalIp = cloudItem.privateIpAddress
						save = true
					}
					if (powerState != currentServer.powerState) {
						currentServer.powerState = powerState
						save = true
					}

					def cacheVolumesResults = cacheVirtualMachineVolumes(cloudItem, currentServer, volumeMap)

					if(cacheVolumesResults.maxStorage && server.maxStorage != cacheVolumesResults.maxStorage) {
						currentServer.maxStorage = cacheVolumesResults.maxStorage
						//planChanged = true
						save = true
					}

					// Set the plan on the server
					if(currentServer.plan?.code != "amazon-${cloudItem.getInstanceType()}") {
						ServicePlan servicePlan = amazonServicePlans["amazon-${cloudItem.getInstanceType()}".toString()]
						if(servicePlan) {
							applyServicePlan(currentServer, servicePlan)
							save = true
						}
					}

					//security groups
					if(currentServer.computeServerType?.managed) {
						syncSecurityGroups(currentServer, cloudItem.getSecurityGroups()?.collect { it.groupId })
					}

					if (save) {
						saves << currentServer
					}
				} catch(e) {
					log.warn("Error Updating Virtual Machine ${currentServer?.name} - ${currentServer.externalId} - ${e}", e)
				}
			}
		}

		if(saves) {
			morpheusContext.async.computeServer.bulkSave(saves).blockingGet()
		}
	}

	def removeMissingVirtualMachines(List<ComputeServerIdentityProjection> removeList) {
		log.debug "removeMissingVirtualMachines: ${cloud} ${removeList.size()}"
		morpheusContext.async.computeServer.remove(removeList).blockingGet()
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
			ServicePlan servicePlan = amazonServicePlans["amazon-${cloudItem.getInstanceType()}".toString()]
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
					morpheusContext.async.storageVolume.create(createList, server).blockingGet()
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
					morpheusContext.async.storageVolume.save(saveList).blockingGet()
				}

				// Process removes
				if(syncLists.removeList) {
					morpheusContext.async.storageVolume.remove(syncLists.removeList, server, false).blockingGet()
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
			morpheusContext.async.securityGroup.location.listSyncProjections(cloud.id, server.resourcePool.id, null).blockingSubscribe {
				if(cloudSecGroupExternalIds.contains(it.externalId)) {
					securityGroupLocations << it
				}
			}
			morpheusContext.async.securityGroup.location.syncAssociations(server, securityGroupLocations)
		} catch(e) {
			log.error "error in sync security groups: ${e}", e
		}
	}

	private syncNetwork(ComputeServer server, String subnetId = null) {
		ComputeServerInterface nic

		if(server.internalIp) {
			Network subnet

			if(subnetId) {
				morpheusContext.async.network.listByCloudAndExternalIdIn(cloud.id, [subnetId]).blockingSubscribe {
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
					network:subnet,
					displayOrder:(server.interfaces?.size() ?: 0) + 1
				)
				morpheusContext.async.computeServer.computeServerInterface.create([nic], server)
			}
			else {
				def doSave = false
				if (server.externalIp && server.externalIp != nic.publicIpAddress) {
					nic.publicIpAddress = server.externalIp
					doSave = true
				}
				if(subnet && nic.subnet?.id != subnet.id) {
					nic.network = subnet
					doSave = true
				}
				if(doSave) {
					morpheusContext.async.computeServer.computeServerInterface.save([nic])
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
			morpheusContext.async.metadataTag.create(createList, server)
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
			morpheusContext.async.metadataTag.save(saveList)
			changes = true
		}

		// Process removes
		if(syncLists.removeList) {
			morpheusContext.async.metadataTag.remove(syncLists.removeList)
			changes = true
		}

		if(changes) {
			//lets see if we have any instance metadata that needs updated
			if(server.computeServerType?.containerHypervisor != true && server.computeServerType?.vmHypervisor != true) {
				def instanceIds = morpheusContext.async.cloud.getStoppedContainerInstanceIds(server.id).blockingSubscribe { it.id }

				if(instanceIds) {
					morpheusContext.async.instance.listById(instanceIds).blockingSubscribe { instance ->
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
			morpheusContext.async.metadataTag.create(createList, instance)
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
			morpheusContext.async.metadataTag.save(saveList)
			saveRequired = true
		}

		// Process removes
		if(syncLists.removeList) {
			morpheusContext.async.metadataTag.remove(syncLists.removeList)
			saveRequired = true
		}

		if(saveRequired) {
			morpheusContext.async.instance.save([instance])
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
				morpheusContext.async.cloud.saveWorkload(workload).blockingGet()

				if(instanceId) {
					instanceIds << instanceId
				}
			}

			if(instanceIds) {
				def instancesToSave = []
				def instances = morpheusContext.async.instance.listById(instanceIds).toList().blockingGet()
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
					morpheusContext.async.instance.save(instancesToSave).blockingGet()
				}
			}
		} catch(e) {
			log.error "Error in updateServerContainersAndInstances: ${e}", e
		}
	}


	private getWorkloadsForServer(ComputeServer currentServer) {
		def workloads = []
		def projections = morpheusContext.async.cloud.listCloudWorkloadProjections(cloud.id).filter { it.serverId == currentServer.id }.toList().blockingGet()
		for(proj in projections) {
			workloads << morpheusContext.async.cloud.getWorkloadById(proj.id).blockingGet()
		}
		workloads
	}

	private ComputeServer saveAndGet(ComputeServer server) {
		def saveSuccessful = morpheusContext.async.computeServer.save([server]).blockingGet()
		if(!saveSuccessful) {
			log.warn("Error saving server: ${server?.id}" )
		}
		return morpheusContext.async.computeServer.get(server.id).blockingGet()
	}

	private Map<String, ComputeServerType> getAllComputeServerTypes() {
		computeServerTypes ?: (computeServerTypes = morpheusContext.async.cloud.getComputeServerTypes(cloud.id).blockingGet().collectEntries {[it.code, it]})
	}

	private Map<String, OsType> getAllOsTypes() {
		osTypes ?: (osTypes = morpheusContext.async.osType.listAll().toMap {it.code}.blockingGet())
	}

	private Map<String, CloudPoolIdentity> getAllZonePools() {
		zonePools ?: (zonePools = morpheusContext.async.cloud.pool.listIdentityProjections(cloud.id, '', null).toMap {it.externalId}.blockingGet())
	}

	private Map<String, ServicePlan> getAmazonServicePlans() {
		servicePlans ?: (servicePlans = morpheusContext.async.servicePlan.list(new DataQuery().withFilter('provisionTypeCode', 'amazon')).toMap{ it.code }.blockingGet())
	}

	private Map<String, StorageVolumeType> getAllStorageVolumeTypes() {
		storageVolumeTypes ?: (storageVolumeTypes = morpheusContext.async.storageVolume.storageVolumeType.listAll().toMap {it.code }.blockingGet())
	}
}
