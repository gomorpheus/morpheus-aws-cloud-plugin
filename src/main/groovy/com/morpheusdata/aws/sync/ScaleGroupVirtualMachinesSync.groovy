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
import com.morpheusdata.model.Process
import com.morpheusdata.model.ProcessEvent
import com.morpheusdata.model.Workload
import com.morpheusdata.model.WorkloadTypeSet
import com.morpheusdata.model.projection.CloudRegionIdentity
import groovy.util.logging.Slf4j

@Slf4j
class ScaleGroupVirtualMachinesSync {
	private Cloud cloud
	private MorpheusContext morpheusContext
	private AWSPlugin plugin
	private Map<String, ComputeServerType> computeServerTypes
	private List<ComputeSite> sites
	private VirtualMachineSync vmSync

	ScaleGroupVirtualMachinesSync(AWSPlugin plugin, Cloud cloud) {
		this.plugin = plugin
		this.cloud = cloud
		this.morpheusContext = plugin.morpheusContext
		this.vmSync = new VirtualMachineSync(plugin, cloud)
	}

	def execute() {
		try {
			morpheusContext.async.cloud.region.listIdentityProjections(cloud.id).blockingSubscribe { region ->
				morpheusContext.async.instance.scale.listIdentityProjections(cloud.id, region.externalId).blockingSubscribe { scale ->
					if (scale.externalId) {
						scale = morpheusContext.async.instance.scale.get(scale.id).blockingGet()
						def amazonClient = plugin.getAmazonClient(cloud,false, region.externalId)
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
							ComputeServer managedSibling = existingRecords.find { it.scale?.id == scale.id && it.computeServerTypeCode != 'amazonUnmanaged' }

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
								removeVmsFromScaleGroup(syncLists.removeList.take(50), scale)
								syncLists.removeList = syncLists.removeList.drop(50)
							}
						}
					}
				}
			}
		} catch(Exception ex) {
			log.error("ScaleGroupVirtualMachinesSync error: {}", ex, ex)
		}
	}

	private addVmToScaleGroup(AmazonEC2 amazonClient, CloudRegionIdentity region, InstanceScale scale, AutoScaleInstance cloudItem, ComputeServer managedSibling, ComputeServer existingItem = null) {
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
			def serverResults = AmazonComputeUtility.listVpcServers([cloud: cloud, amazonClient: amazonClient, filterInstanceId: cloudItem.instanceId, includeAllVPCs: true])
			if (serverResults.success == true && serverResults.serverList?.size() == 1) {
				vmSync.addMissingVirtualMachines(serverResults.serverList, region, serverResults.volumeList, null, 'amazonVm')
				existingItem = morpheusContext.async.computeServer.find(new DataQuery().withFilters('cloud.id': cloud.id, 'externalId': cloudItem.instanceId)).blockingGet()
			}
		}

		if(existingItem) {
			existingItem.computeServerType = managedSibling?.computeServerType ?: allComputeServerTypes['amazonVm']
			existingItem.scale = scale

			// Need to make the VM a part of the instance, create a Container, etc
			Workload siblingWorkload

			if (managedSibling) {
				siblingWorkload = morpheusContext.async.workload.find(new DataQuery().withFilter('server.id', managedSibling.id)).blockingGet()
			}

			Instance instance = siblingWorkload?.instance

			if (!instance) {
				instance = morpheusContext.async.instance.find(new DataQuery().withFilter('scale.id', scale.id)).blockingGet()
			}

			Boolean newInstanceNeeded = instance == null

			// Copy over the existing instance metadata
			if (newInstanceNeeded) {
				// Determine if this instance needs to be added to an App (in the case of cloudformation deploy for scale group)
				App app = morpheusContext.async.app.list(
					new DataQuery().withFilter(new DataFilter('templateType.code', 'in', ['cloudFormation', 'terraform']))
				).toList().blockingGet().find {
					new groovy.json.JsonSlurper().parseText(it.getConfigProperty('resourceMapping') ?: '[]')?.find {
						(it.type == 'aws_autoscaling_group' || it.resourceConfig?.type == 'aws::autoscaling::autoscalinggroup') && (it.data.id?.toString() == scale.id.toString())
					}
				}

				ComputeSite site = app?.site ?: allSites.find { it.account.id == cloud.account.id } ?: allSites.first()

				instance = new Instance(
					name: existingItem.name,
					hostName: existingItem.hostname,
					networkDomain: existingItem.networkDomain,
					site: site,
					status: 'running',
					unformattedName: existingItem.name,
					account: cloud.account,
					instanceTypeCode: 'amazon',
					layoutCode: 'amazon-1.0-single',
					plan: existingItem.plan,
					scale: scale,
					metadata: siblingWorkload?.instance?.metadata
				)
				buildInstanceCapacity(instance)

				instance = morpheusContext.async.instance.create(instance).blockingGet()

				if (app) {
					app.instances << new AppInstance(app: app, instance: instance)
					morpheusContext.async.app.save(app).blockingGet()
				}
			}

			if (!existingItem.computeCapacityInfo) {
				existingItem.setComputeCapacityInfo(new ComputeCapacityInfo())
			}

			existingItem.computeCapacityInfo.maxCores = existingItem.maxCores
			existingItem.computeCapacityInfo.maxMemory = existingItem.maxMemory
			existingItem.computeCapacityInfo.maxStorage = existingItem.maxStorage
			existingItem.computeCapacityInfo.usedStorage = 0
			existingItem.name = getNextComputeServerName(instance)

			// New VM much match closely with its sibling
			if (managedSibling && managedSibling.id != existingItem.id) {
				existingItem.managed = true
				existingItem.serverType = managedSibling.serverType
				existingItem.computeServerType = managedSibling.computeServerType
				existingItem.provision = managedSibling.provision
				existingItem.singleTenant = managedSibling.singleTenant
				existingItem.lvmEnabled = managedSibling.lvmEnabled
				existingItem.managed = managedSibling.managed
				existingItem.discovered = managedSibling.discovered
				existingItem.resourcePool = managedSibling.resourcePool
				existingItem.hostname = managedSibling.hostname
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

			// Need to add a Workload
			if (siblingWorkload?.server?.id != existingItem.id) {
				WorkloadTypeSet typeSet = siblingWorkload?.workloadTypeSet ?: morpheusContext.async.workload.typeSet.find(new DataQuery().withFilter('code', 'amazon-1.0-set')).blockingGet()
				Workload workload = new Workload(
					account: instance.account,
					userStatus: Workload.Status.stopped,
					workloadType: typeSet.workloadType,
					workloadTypeSet: typeSet,
					planCategory: typeSet.planCategory,
					computeZonePool: instance.resourcePool,
					plan: existingItem.plan,
					instance: instance,
					server: new ComputeServer(id: existingItem.id)
				)

				workload = morpheusContext.async.workload.create(workload).blockingGet()

				// set to 'Amazon Scale-Group Added Instance', provision
				def installAgent = managedSibling?.agentInstalled
				def finalizeJobConfig = [callbackType:'scale', installAgent:installAgent ]
				if(newInstanceNeeded || (managedSibling && !managedSibling.agentInstalled))
					finalizeJobConfig.noAgent = true
				Process process = morpheusContext.async.process.startProcess(workload, ProcessEvent.ProcessType.provision, null, workload.workloadType.provisionType.code, 'Amazon Scale-Group Added Instance').blockingGet()
				// start step provisionFinalize
				ProcessEvent processEvent = new ProcessEvent(
					type: ProcessEvent.ProcessType.provisionFinalize,
					jobName: 'finalizeContainer',
					jobConfig: finalizeJobConfig
				)
				morpheusContext.async.process.startProcessStep(process, processEvent, 'finalize').blockingGet()
			}
		}
	}

	private removeVmsFromScaleGroup(List<ComputeServer> servers, InstanceScale instanceScale) {
		morpheusContext.async.computeServer.remove(servers, instanceScale)
	}

	private Map<String, ComputeServerType> getAllComputeServerTypes() {
		computeServerTypes ?: (computeServerTypes = morpheusContext.async.cloud.getComputeServerTypes(cloud.id).blockingGet().collectEntries {[it.code, it]})
	}

	private buildInstanceCapacity(Instance instance) {
		if(instance.plan) {
			instance.maxMemory = instance.plan.maxMemory
			instance.maxStorage = instance.plan.maxStorage ?: instance.plan.maxLog ?: 0L
			if(instance.plan.maxCores) {
				instance.maxCores = instance.plan.maxCores
			}
			if(instance.plan.coresPerSocket) {
				instance.coresPerSocket = instance.plan.coresPerSocket
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

	private List<ComputeSite> getAllSites() {
		sites ?: (sites = morpheusContext.services.computeSite.list(new DataQuery()).sort { it.id })
	}
}
