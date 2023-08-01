package com.morpheusdata.aws.sync

import com.amazonaws.services.autoscaling.model.Instance as AutoScaleInstance
import com.amazonaws.services.ec2.AmazonEC2
import com.morpheusdata.aws.AWSPlugin
import com.morpheusdata.aws.utils.AmazonComputeUtility
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataOrFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.util.SyncList
import com.morpheusdata.model.App
import com.morpheusdata.model.AppInstance
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeCapacityInfo
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerType
import com.morpheusdata.model.ComputeSite
import com.morpheusdata.model.Instance
import com.morpheusdata.model.InstanceScale
import com.morpheusdata.model.Workload
import com.morpheusdata.model.WorkloadTypeSet
import com.morpheusdata.model.projection.ComputeZoneRegionIdentityProjection
import groovy.util.logging.Slf4j

@Slf4j
class ScaleGroupVirtualMachinesSync {
	private Cloud cloud
	private MorpheusContext morpheusContext
	private AWSPlugin plugin
	private Map<String, ComputeServerType> computeServerTypes;
	private VirtualMachineSync vmSync

	ScaleGroupVirtualMachinesSync(AWSPlugin plugin, Cloud cloud) {
		this.plugin = plugin
		this.cloud = cloud
		this.morpheusContext = plugin.morpheusContext
		this.vmSync = new VirtualMachineSync(plugin, cloud)
	}

	def execute() {
		morpheusContext.async.cloud.region.listIdentityProjections(cloud.id).blockingSubscribe { region ->
			morpheusContext.async.instance.scale.listIdentityProjections(cloud.id, region.externalId).blockingSubscribe { scale ->
				if (scale.externalId) {
					scale = morpheusContext.async.instance.scale.get(scale.id).blockingGet()
					def amazonClient = AmazonComputeUtility.getAmazonClient(cloud,false, region.externalId)
					def autoScaleAmazonClient = AmazonComputeUtility.getAmazonAutoScalingClient(cloud, false, region.externalId)
					def scaleGroupResult = AmazonComputeUtility.getAutoScaleGroup(autoScaleAmazonClient, scale.externalId)

					if(scaleGroupResult.success) {
						// existing records includes vms associated to scale group or by externalId
						def existingRecords = morpheusContext.async.computeServer.list(
							new DataQuery().withFilters([
								new DataFilter('zone.id', cloud.id),
								new DataOrFilter([
									new DataFilter('externalId', 'in', scaleGroupResult.group.instances?.collect { it.instanceId }),
									new DataFilter('scale.id', scale.id)
								])
							])
						).toList().blockingGet()

						// If the scale group is part of a cloud formation deployment (has iacId) then:
						// 1.  The Server is associated to the scale group (set scale on computeserver)
						// 2.  Make sure an Instance exists for which multiple VMs (containers can be added to)
						ComputeServer managedSibling = existingRecords.find { it.computeServerTypeCode != 'amazonUnmanaged' }

						SyncList.MatchFunction matchMasterToValidFunc = { ComputeServer server, AutoScaleInstance cloudItem ->
							server.externalId == cloudItem.instanceId
						}
						def syncLists = new SyncList(matchMasterToValidFunc).buildSyncLists(existingRecords, scaleGroupResult.group.instances)

						while(syncLists.addList) {
							for(AutoScaleInstance cloudItem in syncLists.addList.take(50)) {
								addVmToScaleGroup(amazonClient, region, scale, cloudItem, managedSibling)
							}
							syncLists.addList = syncLists.addList.drop(50)
						}
						while(syncLists.updateList) {
							for(SyncList.UpdateItem<ComputeServer, AutoScaleInstance> updateItem in syncLists.updateList.take(50)) {
								if(scale.iacId && updateItem.existingItem.scale?.id != scale.id) {
									addVmToScaleGroup(amazonClient, region, scale, updateItem.masterItem, managedSibling, updateItem.existingItem)
								}
							}
							syncLists.updateList = syncLists.updateList.drop(50)
						}
						while(syncLists.removeList) {
							for(ComputeServer removeItem in syncLists.removeList.take(50)) {
								removeVmFromScaleGroup(removeItem)
							}
							syncLists.removeList = syncLists.removeList.drop(50)
						}
					}
				}
			}
		}
	}

	private addVmToScaleGroup(AmazonEC2 amazonClient, ComputeZoneRegionIdentityProjection region, InstanceScale scale, AutoScaleInstance cloudItem, ComputeServer managedSibling, ComputeServer existingItem = null) {
		if(cloudItem.healthStatus != 'Healthy') {
			log.debug "AWS server (${cloudItem.instanceId} not healthy.. not adding"
			return
		}

		if(!(scale.iacId || managedSibling)) {
			log.debug "Not adding ${cloudItem.instanceId} as scale group ${scale.id} is not managed"
			return
		}

		if(!existingItem) {
			// Must have inventory turned off and we are dealing with a managed scale set
			def serverResults = AmazonComputeUtility.listVpcServers([zone: cloud, amazonClient: amazonClient, filterInstanceId: cloudItem.instanceId, includeAllVPCs: true])
			if (serverResults.success == true && serverResults.serverList?.size() == 1) {
				vmSync.addMissingVirtualMachines(serverResults.serverList, region, serverResults.volumeList, null, 'amazonVm')
				existingItem = morpheusContext.async.computeServer.find(new DataQuery().withFilters('cloud.id': cloud.id, 'externalId': cloudItem.instanceId)).blockingGet()
			}
		}

		if(existingItem) {
			existingItem.computeServerType = managedSibling?.computeServerType ?: allComputeServerTypes['amazonVm']
			existingItem.scale = scale

			if (managedSibling) {
				existingItem.serverType == managedSibling.serverType
				existingItem.computeServerType = managedSibling.computeServerType
				existingItem.provision = managedSibling.provision
				existingItem.singleTenant = managedSibling.singleTenant
				existingItem.lvmEnabled = managedSibling.lvmEnabled
				existingItem.managed = managedSibling.managed
				existingItem.discovered = managedSibling.discovered
				existingItem.resourcePool = managedSibling.resourcePool
				existingItem.hostname = managedSibling.hostname
			}

			// Need to make the VM a part of the instance, create a Container, etc
			Workload siblingWorkload

			if (managedSibling) {
				siblingWorkload = morpheusContext.async.workload.find(new DataQuery().withFilters(['zone.id': cloud.id, 'server.id': managedSibling.id])).blockingGet()
			}

			Instance instance = siblingWorkload?.instance

			if (!instance) {
				instance = morpheusContext.async.instance.find(new DataQuery().withFilter('scale.id', scale.id)).blockingGet()
			}

			// Copy over the existing instance metadata
			if (!instance) {
				// Determine if this instance needs to be added to an App (in the case of cloudformation deploy for scale group)
				App app = morpheusContext.async.app.list(
					new DataQuery().withFilter(new DataFilter('templateType.code', 'in', ['cloudFormation', 'terraform']))
				).toList().blockingGet().find {
					new groovy.json.JsonSlurper().parseText(it.getConfigProperty('resourceMapping') ?: '[]')?.find {
						(it.type == 'aws_autoscaling_group' || it.resourceConfig?.type == 'aws::autoscaling::autoscalinggroup') && (it.data.id?.toString() == scale.id.toString())
					}
				}

				ComputeSite site = app?.site

				if (!site) {
					def sites = morpheusContext.async.computeSite.list(new DataQuery()).toList().blockingGet()
					site = sites.find { it.account.id == cloud.account.id } ?: sites.sort { it.id }.first()
				}

				instance = new Instance(
					name: existingItem.name,
					hostName: existingItem.hostname,
					networkDomain: existingItem.networkDomain,
					site: site,
					unformattedName: instance.unformattedName,
					account: cloud.account,
					instanceTypeCode: 'amazon',
					layoutCode: 'amazon-1.0-single',
					plan: existingItem.plan,
					scale: scale,
					metadata: siblingWorkload?.instance?.metadata
				)
				buildInstanceCapacity(instance)

				instance = morpheusContext.async.instance.create(instance)

				if (app) {
					app.instances << new AppInstance(app: app, instance: instance)
					morpheusContext.async.app.save(app).blockingGet()
				}
			}

			if(!existingItem.computeCapacityInfo) {
				existingItem.setComputeCapacityInfo(new ComputeCapacityInfo())
			}

			existingItem.computeCapacityInfo.maxCores = existingItem.maxCores
			existingItem.computeCapacityInfo.maxMemory = existingItem.maxMemory
			existingItem.computeCapacityInfo.maxStorage = existingItem.maxStorage
			existingItem.computeCapacityInfo.usedStorage = 0
			existingItem.name = getNextComputeServerName(instance)

			// New VM much match closely with its sibling
			if(managedSibling) {
				existingItem.managed = true
				existingItem.config = managedSibling.config
				existingItem.osType = managedSibling.osType
				existingItem.osDevice = managedSibling.osDevice
				existingItem.platform = managedSibling.platform
				existingItem.platformVersion = managedSibling.platformVersion
				existingItem.serverOs = managedSibling.serverOs
				existingItem.serverType = managedSibling.serverType
				existingItem.sourceImage = managedSibling.sourceImage
				existingItem.sshPassword = managedSibling.sshPassword
				existingItem.sshUsername = managedSibling.sshUsername
				existingItem.internalSshUsername = managedSibling.internalSshUsername
			}
			morpheusContext.async.computeServer.save([existingItem]).blockingGet()

			// Need to add a Container
			WorkloadTypeSet typeSet = siblingWorkload?.workloadTypeSet ?: morpheusContext.async.workload.typeSet.find(new DataQuery().withFilter(code:'amazon-1.0-set'))
			Workload workload = new Workload(
				account: instance.account,
				userStatus: Workload.Status.stopped,
				workloadType: typeSet.workloadType,
				workloadTypeSet: typeSet,
				planCategory: typeSet.planCategory,
				computeZonePool: instance.resourcePool,
				plan: existingItem.plan,
				instance: instance
			)
			existingItem = morpheusContext.async.workload.create(workload).blockingGet()

			// insert process job
			/*

					def containerId = newContainer.id
					def serverId = newContainer.server.id
					Promises.task {
						try {
							ComputeServer.withNewSession { session ->
								def tmpContainer = Container.get(containerId)
								def tmpServer = ComputeServer.get(serverId)
								def installAgent = siblingManagedVM?.agentInstalled != null ? siblingManagedVM?.agentInstalled : false
								def finalizeOpts = [callbackType:'scale', installAgent:installAgent ]
								if(newInstanceNeeded || (siblingManagedVM && !siblingManagedVM.agentInstalled))
									finalizeOpts.noAgent = true
								log.debug "FinalizeOpts: ${finalizeOpts}: siblingManagedVM: ${siblingManagedVM}"
								def processConfig = instanceService.containerService.getContainerProcessConfig(tmpContainer, 'Amazon Scale-Group Added Instance', tmpContainer.containerType.provisionType.code, null, [:])
								def processMap = processService.startProcess('provision', processConfig)
								def processStepMap = processService.insertProcessEvent(processMap?.process, null, 'provisionFinalize',
										instanceService.containerService.getContainerProcessEventConfig(tmpContainer, 'Finalize', [:]) + [containerId: tmpContainer.id, jobName: 'finalizeContainer',retryable:true,jobMsg:[containerId: tmpContainer.id]])
								processService.runStep(processMap.process,processStepMap.process,[runResults:[success:true],opts:finalizeOpts])
*/
		}
	}

	private removeVmFromScaleGroup(ComputeServer removeItem) {
		removeItem.status = 'deprovisioning'
		morpheusContext.async.computeServer.save(removeItem)
/*
		if(currentServer.containers.size() > 0) {
			currentServer.containers.each { container ->
				containerService.scaleDownContainer(container,null,[removeResources:false])
			}
		} else {
			//queue delete
			def serverMessage = [refId:currentServer.id, jobType:'serverDelete', serverId:currentServer.id,
								 force:true, removeResources:false, removeVolumes:true]
			sendRabbitMessage('main', '', ApplianceJobService.applianceJobHighQueue, serverMessage)
			//queue update
			def hubMessage = [refId:currentServer.id, jobType:'hubUpdate', serverId:currentServer.id, eventName:'event.server.deleted']
			sendRabbitMessage('main', '', ApplianceJobService.applianceJobLowQueue, hubMessage)
		}
 */
	}

	private Map<String, ComputeServerType> getAllComputeServerTypes() {
		computeServerTypes ?: (computeServerTypes = morpheusContext.async.cloud.getComputeServerTypes(cloud.id).blockingGet().collectEntries {[it.code, it]})
	}

	private buildInstanceCapacity(Instance instance) {
		if(instance.plan) {
			def servicePlanOptions = [:]
			instance.maxMemory = instance.plan.customMaxMemory ?
				(servicePlanOptions.maxMemoryId ? AccountPrice.read(servicePlanOptions.maxMemoryId)?.matchValue?.toLong() : servicePlanOptions.maxMemory?.toLong() ?: instance.plan.maxMemory) :
				instance.plan.maxMemory
			if(servicePlanOptions.maxMemoryId) {
				instance.setConfigProperty('maxMemoryId', servicePlanOptions.maxMemoryId)
			}
			if(servicePlanOptions.maxCoresId) {
				instance.setConfigProperty('maxCoresId', servicePlanOptions.maxCoresId)
			}
			// Calculate customMaxStorage
			if(instance.plan.customMaxStorage || instance.plan.customMaxDataStorage) {
				if(servicePlanOptions?.maxStorage) {
					instance.maxStorage = servicePlanOptions.maxStorage?.toLong()
					instance.setConfigProperty('memoryDisplay', servicePlanOptions["maxMemory-display"]?: 'MB')
				} else {
					instance.maxStorage = instance.plan.getStorageTotal()
				}
			} else {
				instance.maxStorage = instance.plan.maxStorage ?: instance.plan.maxLog ?: 0L
			}
			if(instance.plan.maxCores) {
				instance.maxCores = instance.plan.customCores ?
					(servicePlanOptions.maxCoresId ? AccountPrice.read(servicePlanOptions.maxCoresId)?.matchValue?.toLong() : servicePlanOptions.maxCores?.toLong() ?: instance.plan.maxCores) :
					instance.plan.maxCores
			}
			if(instance.plan.coresPerSocket) {
				instance.coresPerSocket = instance.plan.customCores ? (servicePlanOptions.coresPerSocket?.toLong() ?: instance.plan.coresPerSocket) :
					instance.plan.coresPerSocket
			}
		}
	}

	def getNextComputeServerName(Instance instance) {
		def nextServerIndex = 0

		def exampleName = (instance.containers?.size() > 0 ? instance.containers.find { !it.server.name.startsWith('i-') }?.server?.name : '') ?: 'container_'
		def lastUnderscore = exampleName.lastIndexOf('_')
		def prefix = lastUnderscore != -1 ? exampleName.substring(0, exampleName.lastIndexOf('_')) : exampleName

		try {
			// Gather up all the indexe names
			def indexes = instance.containers?.collect {
				def index = 0
				try {
					if (it.server.name.lastIndexOf('_') != -1) {
						index = it.server.name.substring(it.server.name.lastIndexOf('_') + 1).toLong()
					}
				} catch (e) {
					// swallow it
				}
				index
			} as Set

			if (indexes) {
				indexes = indexes.sort()
				def found = false
				def lastValue = indexes[indexes.size() - 1]
				(0..lastValue).each { idx ->
					if (!found && indexes.find { it.toString() == idx.toString() } == null) {
						nextServerIndex = idx
						found = true
					}
				}
				if (!found) {
					nextServerIndex = lastValue + 1
				}
			}
		} catch(e) {
			log.error "Error in getNextComputeServerName: ${e}", e
		}
		return "${prefix}_${nextServerIndex}"
	}
}
