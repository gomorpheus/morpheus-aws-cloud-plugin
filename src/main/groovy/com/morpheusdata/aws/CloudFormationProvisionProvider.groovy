package com.morpheusdata.aws

import com.amazonaws.services.cloudformation.model.StackEvent
import com.amazonaws.services.cloudformation.model.StackResource
import com.amazonaws.services.migrationhubstrategyrecommendations.model.OSType
import com.bertramlabs.plugins.karman.network.SecurityGroupInterface
import com.morpheusdata.aws.sync.VirtualMachineSync
import com.morpheusdata.aws.utils.AmazonComputeUtility
import com.morpheusdata.aws.utils.CloudFormationResourceMappingUtility
import com.morpheusdata.core.AbstractProvisionProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.providers.CloudNativeProvisionProvider
import com.morpheusdata.core.providers.ResourceProvisionProvider
import com.morpheusdata.core.util.ComputeUtility
import com.morpheusdata.model.AccountResource
import com.morpheusdata.model.AccountResourceType
import com.morpheusdata.model.App
import com.morpheusdata.model.AppTemplate
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.CloudPool
import com.morpheusdata.model.CloudRegion
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerAccess
import com.morpheusdata.model.ComputeServerInterface
import com.morpheusdata.model.ComputeServerInterfaceType
import com.morpheusdata.model.ComputeServerType
import com.morpheusdata.model.ComputeTypeLayout
import com.morpheusdata.model.HostType
import com.morpheusdata.model.InstanceScale
import com.morpheusdata.model.MorpheusModel
import com.morpheusdata.model.Network
import com.morpheusdata.model.OsType
import com.morpheusdata.model.Instance
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.Process
import com.morpheusdata.model.ProcessEvent
import com.morpheusdata.model.ResourceSpec
import com.morpheusdata.model.SecurityGroupLocation
import com.morpheusdata.model.ServicePlan
import com.morpheusdata.model.VirtualImage
import com.morpheusdata.model.Workload
import com.morpheusdata.model.WorkloadState
import com.morpheusdata.model.provisioning.AppRequest
import com.morpheusdata.model.provisioning.InstanceRequest
import com.morpheusdata.response.AppProvisionResponse
import com.morpheusdata.response.CodeRepositoryResponse
import com.morpheusdata.response.PrepareAppResponse
import com.morpheusdata.response.PrepareInstanceResponse
import com.morpheusdata.response.ServiceResponse
import com.morpheusdata.response.ProvisionResponse
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j
import com.morpheusdata.core.util.MorpheusUtils

@Slf4j
class CloudFormationProvisionProvider extends AbstractProvisionProvider implements CloudNativeProvisionProvider, ResourceProvisionProvider.AppFacet {
	AWSPlugin plugin
	MorpheusContext morpheusContext

	CloudFormationProvisionProvider(AWSPlugin plugin, MorpheusContext morpheusContext) {
		this.plugin = plugin
		this.morpheusContext = morpheusContext
	}

	/**
	* Provides a Collection of OptionType inputs that need to be made available to various provisioning Wizards
	* @return Collection of OptionTypes
	*/
	@Override
	Collection<OptionType> getOptionTypes() {
		//AC TODO - region?
		return null
	}

	/**
	 * Provides a Collection of OptionType inputs for configuring node types
	 * @since 0.9.0
	 * @return Collection of OptionTypes
	 */
	@Override
	Collection<OptionType> getNodeOptionTypes() {
		return new ArrayList<OptionType>()
	}

	/**
	 * Provides a Collection of ${@link ServicePlan} related to this ProvisionProvider
	 * @return Collection of ServicePlan
	 */
	@Override
	Collection<ServicePlan> getServicePlans() {
		return null
	}

	/**
	 * Provides a Collection of {@link ComputeServerInterfaceType} related to this ProvisionProvider
	 * @return Collection of ComputeServerInterfaceType
	 */
	@Override
	Collection<ComputeServerInterfaceType> getComputeServerInterfaceTypes() {
		return null
	}

	/**
	 * Determines if this provision type has datastores that can be selected or not.
	 * @return Boolean representation of whether or not this provision type has datastores
	 */
	@Override
	Boolean hasDatastores() {
		return false
	}

	/**
	 * Determines if this provision type has networks that can be selected or not.
	 * @return Boolean representation of whether or not this provision type has networks
	 */
	@Override
	Boolean hasNetworks() {
		return false
	}

	/**
	 * Determines if this provision type supports node types
	 * @return Boolean representation of whether or not this provision type supports node types
	 */
	@Override
	Boolean hasNodeTypes() {
		return false
	}

	/**
	 * Determines if this provision type supports selecting automatic Datastore
	 * @return Boolean representation of whether or not this provision type supports selecting automatic Datastore
	 */
	@Override
	Boolean supportsAutoDatastore() {
		return false
	}

	/**
	 * Determines if this provision type should set the server type to something different than the cloud code
	 * @return Boolean representation of whether or not this provision type should set the server type to something different than the cloud code
	 */
	@Override
	String serverType() {
		return "service"
	}


	/**
	 * Determines if this provision type supports service plans that expose the tag match property.
	 * @return Boolean representation of whether or not service plans expose the tag match property.
	 */
	@Override
	Boolean hasPlanTagMatch() {
		return false
	}

	/**
	 * Returns the maximum number of network interfaces that can be chosen when provisioning with this type
	 * @return maximum number of networks or 0,null if unlimited.
	 */
	@Override
	Integer getMaxNetworks() {
		return 0
	}

	/**
	 * Determines if this provision type has resources pools that can be selected or not.
	 * @return Boolean representation of whether or not this provision type has resource pools
	 */
	@Override
	Boolean hasComputeZonePools() {
		return false
	}


	/**
	 * Validate the provided provisioning options for an Instance.  A return of success = false will halt the
	 * creation and display errors
	 * @param instance the Instance to validate
	 * @param opts options
	 * @return Response from API
	 */
	@Override
	ServiceResponse validateInstance(Instance instance, Map opts) {
		return null
	}


	@Override
	ServiceResponse<PrepareInstanceResponse> prepareInstance(Instance instance, InstanceRequest instanceRequest, Map opts) {
		PrepareInstanceResponse prepareInstanceResponse = new PrepareInstanceResponse()
		def rtn = [success:false, data:[resources:[], containers:[]]]

		try {
			//cloud formation instance
			def performingUpdate = (opts.performingUpdate == true)
			def instanceConfig = instance.getConfigMap()
			log.debug("instanceConfig: {}", instanceConfig)
			//template params
			def templateParams = instanceConfig.templateParameter
			def layoutSpecs = opts.layoutSpecs
			def scriptConfig = opts.scriptConfig
			def cloud = instance.provisionZoneId ? morpheusContext.async.cloud.getCloudById(instance.provisionZoneId).blockingGet() : null
			opts.cloud = cloud


			def allSuccess = true
			layoutSpecs.each { spec ->
				def specTemplate = spec.specTemplate
				def specFileContent = spec.specContent
				ResourceSpec resourceSpec = instance.specs.find { it.template == specTemplate }

				//get the content for the spec template
				def specContent = performingUpdate && resourceSpec.isolated ? resourceSpec.templateContent : specFileContent
				//parse it

				def parseResults = CloudFormationResourceMappingUtility.parseResource(specContent, scriptConfig as Map, morpheusContext)
				if(parseResults.success == true) {
					if(resourceSpec) {
						resourceSpec.templateContent = parseResults.data.spec
						resourceSpec.templateParameters = templateParams.encodeAsJSON().toString()
						morpheusContext.async.resourceSpec.save(resourceSpec).blockingGet()

					} else {
						//create a resource spec for the instance with the configured contents
						def addSpecConfig = [name: specTemplate.name, template: specTemplate, templateContent: parseResults.data.spec,
						                     templateParameters: templateParams.encodeAsJSON().toString(), externalType: 'cloudFormation',
						                     resourceType: 'cloudFormation', uuid: UUID.randomUUID()
						]
						resourceSpec = new ResourceSpec(addSpecConfig)
						resourceSpec = morpheusContext.async.resourceSpec.create(resourceSpec).blockingGet()
						instance.specs += resourceSpec
						prepareInstanceResponse.instance = instance
					}


					WorkloadState workloadState = morpheusContext.async.workloadState.find(new DataQuery().withFilters([
					        new DataFilter('refType', 'Instance'),
					        new DataFilter('refId', instance.id),
					        new DataFilter('subRefType', 'ResourceSpec'),
					        new DataFilter('subRefId', resourceSpec.id)
					])).blockingGet()
					if (!workloadState) {
						buildWorkloadStateForInstance(instance, resourceSpec, specContent)
					}

					def resourceList = []
					//grab the resources and map them
					parseResults.data.resources?.each { resource ->
						//[type:typeMatch.type, apiType:typeMatch.apiType, enabled:typeMatch.enabled, spec:processedSpec,
						//  specMap:resourceConfig, morpheusType:typeMatch.morpheusType, name:key]
						//map it
						def mapResults = CloudFormationResourceMappingUtility.mapResource(instance, resource, opts, morpheusContext)
						log.debug("map results: {}", mapResults)
						if (mapResults.success == true && mapResults.data.resource) {
							def resourceRow = mapResults.data.resource
							resourceRow.spec = resourceSpec
							resourceList << resourceRow
						} else if (mapResults.success == false) {
							allSuccess = false
						}
					}


					if(!performingUpdate) {
						//create each resource
						resourceList.each { row ->
							def resourceResults = CloudFormationResourceMappingUtility.createResource(instance, row, opts, morpheusContext)
							log.debug("resourceResults: {}", resourceResults)
							if (resourceResults.success == true && resourceResults.data) {
								//store container result
								def addRow = [resource: resourceResults.data.output, results: resourceResults.data]
								rtn.data.resources << addRow
								log.debug("new resource row: {}", addRow)
								//append them to the instance
								if (addRow.results.type == 'accountResource') {
									//add it to the instance
									instance.resources += addRow.resource
									log.info("added resource to instance: {}", addRow.resource)
								}
								//see if there is an additional morpheus resource
								def morpheusResource = resourceResults.data.morpheusResource
								log.info("morpheus resource: {}", morpheusResource)
								if (morpheusResource) {
									if (morpheusResource.success == true && morpheusResource.data) {
										def morpheusRow = [resource: morpheusResource.data.output, results: morpheusResource.data]
										if(morpheusResource.data.container) {
											morpheusRow.container = morpheusResource.data.container
											morpheusRow.type = morpheusResource.data.type
											morpheusRow.accountResource = addRow.resource
										}
										rtn.data.resources << morpheusRow
										//decorate the account resource
										addRow.resource.refType = morpheusResource.data.type
										addRow.resource.refId = morpheusResource.data.id
										log.info("new morpheus resource row: {}", morpheusRow)
										if(morpheusResource.data.type == 'container'){
											def plan = morpheusResource.data.output?.plan
											if(plan != null && plan != instance.plan)
												instance.plan = plan
										}
									}
								}
							} else if (resourceResults.success == false) {
								allSuccess = false
							}
						}
					}

				} else {
					allSuccess = false
					rtn.msg = "Error parsing resource: ${specContent}"
					opts.processError = rtn.msg
				}
			}
			prepareInstanceResponse.workloadsToSave = rtn.data.containers
			prepareInstanceResponse.resources = rtn.data.resources
			rtn.success = allSuccess
			//done
		} catch(e) {
			log.error("error preparing instance: ${e}", e)
			return new ServiceResponse(success: false, msg: e.message, error: e.message, data: prepareInstanceResponse)
		}


		return new ServiceResponse(success: rtn.success, msg: rtn.msg, data: prepareInstanceResponse)
	}

	@Override
	ServiceResponse<ProvisionResponse> runInstance(Instance instance, InstanceRequest instanceRequest, Map opts) {
		def rtn = [success:false, data:[resources:[]]]
		def performingUpdate = opts.performingUpdate
		def cloud = instance.provisionZoneId ? morpheusContext.async.cloud.getCloudById(instance.provisionZoneId).blockingGet() : null
		opts.cloud = cloud
		def regionCode = opts.regionCode
		def successList = []
		def failedList = []
		def deleteList = []
		def processOutputList = []
		def templateOutput = []
		//apply each spec
		//get the workload states

		instance.specs?.each { spec ->
			WorkloadState workloadState = morpheusContext.async.workloadState.find(new DataQuery().withFilters([
				new DataFilter('subRefType', 'ResourceSpec'),
				new DataFilter('subRefId', spec.id),
				new DataFilter('refId', instance.id),
				new DataFilter('refType', 'Instance'),

			])).blockingGet()
			//each spec is a stack
			def stackName = performingUpdate ? spec.resourceName : getUniqueStackName(cloud, instance.name, regionCode)


			log.debug("cloud formation stack base template: {}", spec.templateContent)
			//json spec
			def isYaml = !spec.templateContent?.trim()?.startsWith('{')
			def templateJson = CloudFormationResourceMappingUtility.loadYamlOrJsonMap(spec.templateContent)
			//params
			def templateParams = new JsonSlurper().parseText(spec.templateParameters)
			//process the spec for any customizations
			templateJson = configureResourceSpec(instance, templateJson as Map, opts, regionCode)
			log.info("cloud formation stack template: {}", templateJson)
			//provision it
			def capabilities = parseCapabilities(spec.template?.getConfigProperty('cloudformation') as Map)
			def specResults

			if(performingUpdate) {
				specResults = AmazonComputeUtility.updateCloudFormationStack(plugin.getAmazonCloudFormationClient(cloud, false, regionCode as String), stackName, templateJson, templateParams, capabilities, isYaml)
			} else {
				specResults = AmazonComputeUtility.createCloudFormationStack(plugin.getAmazonCloudFormationClient(cloud, false, regionCode as String), stackName, templateJson, templateParams, capabilities, isYaml)
			}

			log.info("specResults: {}", specResults)
			if(specResults.success == true) {
				spec.resourceName = stackName
				spec.externalId = specResults.stackId
				morpheusContext.async.resourceSpec.save(spec).blockingGet()

				workloadState.setConfigProperty('stackName', stackName)
				workloadState.setConfigProperty('stackId', spec.externalId)
				workloadState.setConfigProperty('regionCode', regionCode)
				morpheusContext.async.workloadState.save(workloadState).blockingGet()

				//update process
				processOutputList << 'stack created: ' + specResults.stackId
				//wait for completion or error
				def waitResults = waitForCloudFormationStack(cloud, regionCode as String, stackName)
				log.info("waitResults: {}", waitResults)

				def stackDetails = waitResults.results
				updateWorkloadStateFromCloud(workloadState, [stack: stackDetails])

				if(!performingUpdate) {
					//grab all the events and add them to the history
					def eventResults = AmazonComputeUtility.getCloudFormationStackEvents(plugin.getAmazonCloudFormationClient(cloud, false, regionCode as String), stackName)
					//append events results to history
					if (eventResults.success == true) {
						//append events to history (if we haven't already)
						eventResults.stackEvents?.each { row ->
							def stackStatus = row.getResourceStatus()
							def statusMessage = row.getResourceStatusReason()
							def processStatus = 'failed'
							if (statusMessage)
								processOutputList << statusMessage
							//configure based on status
							if (stackStatus == 'CREATE_FAILED' || stackStatus == 'ROLLBACK_IN_PROGRESS' || stackStatus == 'ROLLBACK_COMPLETE' || stackStatus.contains('FAILED')) {
								if (stackStatus.indexOf('ROLLBACK') > -1)
									processStatus = 'resource rollback'
								else //if(stackStatus.indexOf('FAILED') > -1)
									processStatus = 'failed'
								//grab an error message
								rtn.msg = rtn.msg + ' - ' + statusMessage
								opts.processError = opts.processError + ' \n' + statusMessage
							} else {
								//grab output
								if (stackStatus?.startsWith('DELETE'))
									processStatus = 'deleting resource'
								else
									processStatus = 'provision resource'
							}
							//add the event
							morpheusContext.async.process.startProcessStep(instanceRequest.process, new ProcessEvent(type: ProcessEvent.ProcessType.provisionItem), processStatus).blockingGet()

						}
					}
				}

				//if the stack succeeded - proceed
				if(waitResults.success) {
					processOutputList << 'stack status complete'
					// Fetch the resources
					def stackResults = AmazonComputeUtility.getCloudFormationStack(plugin.getAmazonCloudFormationClient(cloud, false, regionCode as String), stackName)
					def resourceResults = AmazonComputeUtility.getCloudFormationStackResources(plugin.getAmazonCloudFormationClient(cloud, false, regionCode as String), stackName)
					log.debug("stackResults: {}", stackResults)
					log.debug("resourceResults: {}", resourceResults)
					List<StackResource> stackResourcesResult = resourceResults.resources
					//get the list of resource
					def resources = instance.resources
					//resolve them
					resources.each { row ->
						def resource = [id:row.id, morpheusType:'accountResource', type:row.resourceType, name:row.name, resource:row]
						log.debug("resolving resource: {}", resource)
						//resolve it
						opts.cloudFormationClient = plugin.getAmazonCloudFormationClient(cloud, false, regionCode as String)
						opts.amazonClient = plugin.getAmazonClient(cloud, false, regionCode as String)
						def resolveResults = CloudFormationResourceMappingUtility.resolveResource(instance, resource, stackResourcesResult, opts, morpheusContext)
						log.debug("resolve results: {}", resolveResults)
						if(resolveResults.success == true) {
							//update the resource
							resource.state = resolveResults.data.resourceState
							def updateResults = CloudFormationResourceMappingUtility.updateResource(resource, opts, morpheusContext)
							if(updateResults.success == true) {
								//add to success list
								def successRow = [templateJson:templateJson, templateParameters:templateParams, id:row.id, type:row.resourceType,
									name:row.name, resource:row, regionCode: regionCode]
								successList << successRow
								//add to process updates
								processOutputList << getResourceProcessOutput(resource)
								//done
							} else {
								//let it happen on sync?
							}
							//done
						} else {
							//couldn't find it - error?
							deleteList << [resource:resource, msg:(resolveResults.msg ?: 'failed to deploy resource')]
						}
					}
					//grab the outputs and add to the instance
					if(stackResults.success == true) {
						//stack
						stackResults.stack?.getOutputs()?.each { row ->
							def outputItem = [key:row.getOutputKey(), value:row.getOutputValue(), exportName:row.getExportName(),
								description:row.getDescription()]
							templateOutput << outputItem
						}
					} else {
						//error loading stack - ignore?
					}
					rtn.success = true
					//done
				} else {
					//failed
					instance.resources.each { row ->
						def resource = [id:row.id, morpheusType:'accountResource', type:row.resourceType, name:row.name, resource:row]
						failedList << [resource:resource, msg:(rtn.msg ?: waitResults.msg ?: 'CloudFormation stack failed')]
					}
					//add error info
					rtn.msg = rtn.msg ?:  waitResults.msg ?: 'CloudFormation stack failed'
					opts.processError = opts.processError ?: 'stack provision failed'
				}
			} else {
				//failed
				instance.resources.each { row ->
					def resource = [id:row.id, morpheusType:'accountResource', type:row.resourceType, name:row.name, resource:row]
					failedList << [resource:resource, msg:(rtn.msg ?: specResults.msg ?: 'CloudFormation stack failed')]
				}
				//add error info
				rtn.msg = rtn.msg ?: specResults.msg ?: 'CloudFormation stack failed'
				opts.processError = opts.processError ?: 'stack provision failed'
			}
		}
		//add output to the instance
		if(templateOutput?.size() > 0) {
			instance.setConfigProperty('templateOutput', templateOutput)
		}

		//handle success
		if(rtn.success) {
			if(performingUpdate) { // Don't do finalize again on an update
				instance.status = Instance.Status.running
			} else {
				//each success item
				successList.each { row ->
						def resource = morpheusContext.async.cloud.resource.get(row.id).blockingGet()
						def resourceOpts = [templateJson: row.templateJson, templateParams: row.templateParams, id: row.id, type: row.resourceType,
											name : row.name, installAgent: cloud.agentMode != 'cloudInit', regionCode: regionCode ?: row.regionCode, process: instanceRequest.process]
						def finalizeResults = finalizeResource(instance, resource, resourceOpts)
						rtn.data.resources += finalizeResults.data
						//resource done?
					}

			}
		}
		//failed list - always handle
		if(failedList?.size() > 0) {
			failedList.each { row ->
				log.warn("failed resource: {}", row)
				//update the status
				if(row.resource) {
					def resource = morpheusContext.async.cloud.resource.get(row.id).blockingGet()
					resource.status = 'failed'
					resource.statusMessage = rtn.msg
					morpheusContext.async.cloud.resource.save(resource).blockingGet()
				}
			}

		}
		if(deleteList?.size() > 0) {
			deleteList.each { row ->
				log.warn("removed resource: {}", row)
				//remove this resource as it was likely a conditional resource
				if(row.resource) {
					def resource = morpheusContext.async.cloud.resource.get(row.id).blockingGet()
					instance.resources.remove(resource)
					instance = saveAndGet(instance)
					morpheusContext.async.cloud.resource.remove(resource).blockingGet()
				}
			}
		}
		//output the info
		opts.processOutput = processOutputList?.size() > 0 ? processOutputList.join('\n') : ''

		instance = saveAndGet(instance)
		//done
		return new ServiceResponse(success: rtn.success, msg: rtn.msg, data: rtn.data + [instance: instance])
	}


	@Override
	ServiceResponse destroyInstance(Instance instance, Map opts) {
		def rtn = [success:true, removeServer:true]
		try {
			def cloud = instance.provisionZoneId ? morpheusContext.async.cloud.getCloudById(instance.provisionZoneId).blockingGet() : null
			if(cloud) {
				//delete the stack
				if(instance.specs?.size() > 0) {
					instance.specs?.each { spec ->
						def stackName = spec.resourceName
						if(stackName) {
							def workloadState = morpheusContext.async.workloadState.find(
									new DataQuery().withFilters([
											new DataFilter('refType', 'Instance'),
											new DataFilter('refId', instance.id),
											new DataFilter('subRefType', 'ResourceSpec'),
											new DataFilter('subRefId', spec.id)
									])
							).blockingGet()
							def regionCode = workloadState.getConfigProperty('regionCode')
							def amazonClient = plugin.getAmazonCloudFormationClient(cloud, false, regionCode)
							def deleteResults = AmazonComputeUtility.deleteCloudFormationStack(amazonClient, stackName)
							log.info("destroyInstance results: {}", deleteResults)
							rtn.success = rtn.success && deleteResults.success
						}
					}
				} else {
					//nothing to destroy
					rtn.success = true
				}
			}
		} catch(e) {
			log.error("destroy instance error: ${e}", e)
			rtn.msg = 'error executing CloudFormation destroy'
		}
		return new ServiceResponse(success: rtn.success, data: [removeServer: rtn.removeServer])
	}

	ServiceResponse refreshInstance(Instance instance, Map opts) {
		log.debug "refreshInstance: ${instance} ${opts}"

		def rtn = [success: false, data: [:]]
		try {
			def opDate = new Date()
			def cloud = opts.cloud
			def specsList = opts.specsList
			specsList.each { specMap ->
				def instanceSpec = specMap.spec
				//get the matching state
				WorkloadState workloadState = morpheusContext.async.workloadState.find(new DataQuery().withFilters([
						new DataFilter('refType', 'Instance'),
						new DataFilter('refId', instance.id),
						new DataFilter('subRefType', 'ResourceSpec'),
						new DataFilter('subRefId', instanceSpec.id)
				])).blockingGet()
				if (!workloadState) {
					def specContent = specMap.content
					workloadState = buildWorkloadStateForInstance(instance, instanceSpec, specContent)
				}
				

				// Fetch the stack and resources
				def stackName = workloadState.getConfigProperty('stackName')
				if(stackName) {
					def regionCode = workloadState.getConfigProperty('regionCode')
					def amazonClient = plugin.getAmazonCloudFormationClient(cloud, false, regionCode)
					def updateOptions = [
							stack      : AmazonComputeUtility.getCloudFormationStack(amazonClient, stackName)?.stack,
							resources  : AmazonComputeUtility.getCloudFormationStackResources(amazonClient, stackName)?.resources,
							stackEvents: AmazonComputeUtility.getCloudFormationStackEvents(amazonClient, stackName)?.stackEvents,
							template   : AmazonComputeUtility.getCloudFormationStackTemplate(amazonClient, stackName)?.template
					]

					updateWorkloadStateFromCloud(workloadState, updateOptions)

					workloadState.stateDate = opDate
					morpheusContext.async.workloadState.save(workloadState).blockingGet()

					// Update our resources (CRUD)

					//SyncTask<StorageVolumeIdentityProjection, Volume, StorageVolume> syncTask = new SyncTask<>(existingRecords, cloudItems)
					//			syncTask.addMatchFunction { StorageVolumeIdentityProjection existingItem, Volume cloudItem ->
					//				existingItem.externalId == cloudItem.volumeId
					//			}.withLoadObjectDetailsFromFinder { List<SyncTask.UpdateItemDto<StorageVolumeIdentityProjection, StorageVolume>> updateItems ->
					//				morpheusContext.async.storageVolume.listById(updateItems.collect { it.existingItem.id } as List<Long>)
					//			}.onAdd { itemsToAdd ->
					//				addMissingStorageVolumes(itemsToAdd, region.externalId)
					//			}.onUpdate { List<SyncTask.UpdateItem<ComputeServer, Instance>> updateItems ->
					//				updateMatchedStorageVolumes(updateItems, region.externalId)
					//			}.onDelete { removeItems ->
					//				removeMissingStorageVolumes(removeItems)
					//			}.start()
					List<StackResource> masterItems = updateOptions.resources
					def existingItems = instance.resources
					def matchFunction = { AccountResource morpheusItem, StackResource cloudItem ->
						morpheusItem.externalId == cloudItem.getPhysicalResourceId()
					}
					def syncLists = ComputeUtility.buildSyncLists(existingItems, masterItems, matchFunction)

					log.debug "sync stack resources have ${masterItems?.size()} from the cloud, ${existingItems?.size()} existing, adding: ${syncLists?.addList?.size()}, updating: ${syncLists.updateList?.size()}, removing: ${syncLists?.removeList?.size()}"

					for (StackResource stackResource in syncLists.addList) {
						log.debug "Adding ${stackResource}"

						def typeMatch = CloudFormationResourceMappingUtility.findAwsResourceType(stackResource.getResourceType())
						if (typeMatch && typeMatch instanceof AccountResourceType) {
							def addConfig = [type        : typeMatch.type, apiType: typeMatch.apiType, enabled: typeMatch.enabled,
											 morpheusType: typeMatch.morpheusType, name: stackResource.getLogicalResourceId(),
											 input       : [type: typeMatch.type, key: stackResource.getLogicalResourceId()],
											 mapping     : [
													 morpheusType: 'accountResource',
													 type        : typeMatch, apiType: typeMatch.apiType, enabled: typeMatch.enabled, name: stackResource.getLogicalResourceId(),
													 iacProvider : 'cloudFormation', iacType: typeMatch.apiType, resourceSpec: instanceSpec, owner: instance.account,
													 resourceType: typeMatch.type, zone: cloud,
													 resourceIcon: typeMatch.resourceIcon,
													 zoneName    : cloud.name,
													 externalId  : stackResource.getPhysicalResourceId(), displayName: stackResource.getLogicalResourceId(),
													 internalId  : stackResource.getLogicalResourceId(),
													 instanceName: instance.name
											 ],
											 iacProvider : 'cloudFormation', iacType: typeMatch.apiType, resourceSpec: instanceSpec,
											 resourceType: typeMatch
							]
							def resourceResults = CloudFormationResourceMappingUtility.createResource(instance, addConfig, opts)
							def newAccountResource = resourceResults.data.output
							instance.resources += newAccountResource
							CloudFormationResourceMappingUtility.updateResource(newAccountResource, stackResource)
						} else {
							log.debug "Not adding: ${stackResource} as type is ${typeMatch}"
						}
					}

					syncLists.updateList.each { it ->
						def masterItem = it.masterItem
						AccountResource existingItem = it.existingItem
						log.debug "Updating: ${existingItem} with ${masterItem}"
						CloudFormationResourceMappingUtility.updateResource(existingItem, masterItem)
					}

					for (AccountResource morpheusItem in syncLists.removeList) {
						log.debug "Removing ${morpheusItem}"
						instance.resources.remove(morpheusItem)
						morpheusContext.async.cloud.resource.remove(morpheusItem).blockingGet()
					}
				} else {
					log.debug "No stackname.. unable to update stack data for ${instanceSpec}"
				}
			}

			rtn.success = true
		} catch (e) {
			log.error("refresh cloud formation error: ${e}", e)
			rtn.mgs = 'unknown error refreshing cloud formation instance'
		}
		return ServiceResponse.create(rtn)
	}

	@Override
	ServiceResponse<ProvisionResponse> updateInstance(Instance instance, InstanceRequest instanceRequest, Map map) {
		return null
	}

	@Override
	ServiceResponse stopInstance(Instance instance) {
		return null
	}

	@Override
	ServiceResponse startInstance(Instance instance) {
		return null
	}

	@Override
	ServiceResponse validateApp(App app, Map opts) {
		def rtn = [success: false, errors: [:]]
		try {
			def cloudFormationOpts = opts.cloudFormation
			// Must have a zone
			def zoneId = cloudFormationOpts.zoneId ?: opts.defaultCloud?.id
			if(!zoneId) {
				rtn.errors['cloudFormation.zoneId'] = 'Required'
			}
			def cloud = morpheusContext.async.cloud.get(zoneId).blockingGet()

			// Must have a region
			def regionCode = cloudFormationOpts.regionCode
			if(!regionCode) {
				rtn.errors['cloudFormation.regionCode'] = 'Required'
			}
			def templateJson
			switch(cloudFormationOpts.configType) {
				case 'git':
					def codeRepositoryId = cloudFormationOpts.git.repoId
					def codeRepository = morpheusContext.async.codeRepository.fetchCodeRepository(codeRepositoryId, cloudFormationOpts.git.branch)
					templateJson = loadCloudFormationConfigFromRepo(codeRepository, cloudFormationOpts)?.template
					break
				case 'yaml':
					templateJson = CloudFormationResourceMappingUtility.loadYamlOrJsonMap(cloudFormationOpts.yaml)
					break
				case 'json':
				default:
					templateJson = new groovy.json.JsonSlurper().parseText(cloudFormationOpts.json)
					break
			}
			// Set the defaults if not passed in
			configureDefaultTemplateParameters(app, templateJson, true)
			if(opts.templateParameter == null) {
				opts.templateParameter = new groovy.json.JsonSlurper().parseText(app.getConfigProperty('config') ?: '{}')?.templateParameter
			}
			// First.. verify ALL of the template parameters are set
			AppTemplate appTemplate = app.getTemplate()
			def requiredParameters = getTemplateParameters(appTemplate, opts)?.data
			requiredParameters?.each { requiredParam ->
				if(!opts.templateParameter[requiredParam.name]) {
					rtn.errors["templateParameter.${requiredParam.name}"] = 'Required'
				}
			}
			// Second.. if still no errors.. validate against Amazon
			if(!rtn.errors) {
				def amazonClient = plugin.getAmazonCloudFormationClient(cloud, false, opts.cloudFormation?.regionCode)
				def validateDeployResults = AmazonComputeUtility.validateCloudFormationTemplate(amazonClient, templateJson)
				if (!validateDeployResults.success) {
					rtn.errors['cloudFormation.general'] = validateDeployResults.msg ?: 'CloudFormation validation failed'
				}
			}
			rtn.success = !rtn.errors
		} catch(e) {
			log.error "Error in validation: ${e}", e
			rtn.errors['cloudFormation.general'] = "Validation error for CloudFormation template: ${e.message}"
		}
		return rtn.success ? ServiceResponse.success() : ServiceResponse.error(rtn.msg ?: "Validation errors", rtn.errors)
	}

	@Override
	ServiceResponse<PrepareAppResponse> prepareApp(App app, AppRequest appRequest, Map opts) {
		log.info("prepareApp: ${app} ${opts}")
		def rtn = [success: false]
		def response = new PrepareAppResponse()
		try {
			def appTemplate = app.template
			def templateConfig = appTemplate.getConfigMap()
			def cloudFormationOpts = templateConfig.cloudFormation
			def configType = cloudFormationOpts.configType ?: 'json'
			def appConfig = new groovy.json.JsonSlurper().parseText(app.getConfigProperty('config'))
			def cloudId = appConfig.cloudFormation.zoneId?.toLong() ?: opts.defaultCloud?.id?.toLong()
			Cloud cloud = morpheusContext.async.cloud.get(cloudId).blockingGet()
			opts.cloud = cloud
			opts.amazonClient = plugin.getAmazonClient(cloud)
			// Get the template JSON
			def templateJson
			switch(configType) {
				case 'git':
					def codeRepositoryId = cloudFormationOpts.git.repoId
					def codeRepository = morpheusContext.async.codeRepository.fetchCodeRepository(codeRepositoryId, cloudFormationOpts.git.branch)
					templateJson = loadCloudFormationConfigFromRepo(codeRepository, cloudFormationOpts)?.template
					break
				case 'yaml':
					templateJson = CloudFormationResourceMappingUtility.loadYamlOrJsonMap(opts.cloudFormation?.yaml ?: cloudFormationOpts.yaml)
					break
				case 'json':
				default:
					templateJson = new groovy.json.JsonSlurper().parseText(opts.cloudFormation?.json ?: cloudFormationOpts.json)
					break
			}
			// Create the resources on our end
			def resources = []
			opts.defaultLayout = morpheusContext.async.instanceTypeLayout.find(new DataQuery().withFilter('code', 'amazon-1.0-single')).blockingGet()
			def resourceList = CloudFormationResourceMappingUtility.mapResources(app, cloud, templateJson, opts)
			resourceList?.each { resourceConfig ->
				if(resourceConfig.needsProvision) {
					def resourceResults = CloudFormationResourceMappingUtility.createAppTemplateResource(app, cloud, resourceConfig as Map, opts, morpheusContext, opts)
					log.debug("resourceResults: ${resourceResults}")
					if (resourceResults.success == true) {
						def resourceData = resourceResults.data
						if (resourceData) {
							resources += [
									resourceConfig: resourceConfig,
									data          : [
											id          : resourceData.id,
											iacId       : resourceData.iacId,
											appId       : app.id,
											newResource : resourceData.newResource,
											containerIds: resourceData.containers?.collect { it.id },
											instanceId  : resourceData.instance?.id,
											serverIds   : resourceData.servers?.collect { it.id }
									]
							]
						}
					}

				} else if (resourceConfig.resourceType instanceof AccountResourceType) {
					def resourceResults = CloudFormationResourceMappingUtility.createResource(app, resourceConfig as Map, opts, morpheusContext)
					log.debug("resourceResults: {}", resourceResults)
					if(resourceResults.success == true && resourceResults.data) {
						def addRow = [resource:resourceResults.data.output, results:resourceResults.data]
						log.debug("new resource row: {}", addRow)
						app.resources += addRow.resource
					}
				}
			}
			response.setApp(app)
			response.setResourceMapping(convertResourcesToJSON(resources))
			rtn.success = true
		} catch(e) {
			log.error("prepareApp error: ${e}", e)
			rtn.msg = "Error during deployment: ${e.message}"
		}
		return new ServiceResponse<PrepareAppResponse>(success: rtn.success, msg: rtn.msg, data: response)
	}

	@Override
	ServiceResponse<AppProvisionResponse> runApp(App app, AppRequest appRequest, Map opts) {
		log.info("runApp: ${app} ${opts}")
		def response = new AppProvisionResponse(success: false)
		try {
			def performingUpdate = opts.performingUpdate
			def appTemplate = app.template
			def templateConfig = appTemplate.getConfigMap()
			def cloudFormationOpts = templateConfig.cloudFormation
			def configType = cloudFormationOpts.configType ?: 'json'
			def appConfig = new groovy.json.JsonSlurper().parseText(app.getConfigProperty('config'))
			def templateParameters = appConfig.templateParameter
			def regionCode = appConfig.cloudFormation?.regionCode
			opts.regionCode = regionCode
			def cloudId = appConfig.cloudFormation?.zoneId?.toLong() ?: opts.defaultCloud?.id?.toLong()
			Cloud cloud = morpheusContext.async.cloud.get(cloudId).blockingGet()
			// Get the template JSON
			def templateJson
			def isYaml = false
			switch(configType) {
				case 'git':
					def codeRepositoryId = cloudFormationOpts.git.repoId
					def codeRepository = morpheusContext.async.codeRepository.fetchCodeRepository(codeRepositoryId, cloudFormationOpts.git.branch)
					def loadResults = loadCloudFormationConfigFromRepo(codeRepository, cloudFormationOpts)
					templateJson = loadResults?.template
					isYaml = loadResults?.isYaml
					break
				case 'yaml':
					templateJson = CloudFormationResourceMappingUtility.loadYamlOrJsonMap(opts.cloudFormation?.yaml ?: cloudFormationOpts.yaml)
					isYaml = true
					break
				case 'json':
				default:
					templateJson = new groovy.json.JsonSlurper().parseText(opts.cloudFormation?.json ?: cloudFormationOpts.json)
					break
			}
			// Fetch the data describing the mapping of our resources
			def finalizeList = []
			def failedList = []
			def resources = new groovy.json.JsonSlurper().parseText(app.getConfigProperty('resourceMapping'))
			cloudFormationOpts.applyCloudInit = cloudFormationOpts.cloudInitEnabled
			// Some post processing after the initial resources are created (cloud init, agent config, passwords, etc)
			resources?.each { newResource ->
				def resourceConfig = newResource.resourceConfig
				def resourceData = newResource.data
				if(resourceData.newResource && resourceConfig.type == 'aws::ec2::instance') {
					resourceConfig.installAgent = cloudFormationOpts.installAgent ?: false
					resourceConfig.cloudInitEnabled = cloudFormationOpts.applyCloudInit
					Workload workload = resourceData.containerIds.size() > 0 ? morpheusContext.async.workload.get(resourceData.containerIds.first().toLong()).blockingGet() : null
					if(!workload) {
						throw new Exception("Container not created")
					}
					ComputeServer server = resourceData.serverIds.size() > 0 ? morpheusContext.async.computeServer.get(resourceData.serverIds.first().toLong()).blockingGet() : null
					if(!server) {
						throw new Exception("Server not created")
					}
					// Determine if linux or windows!
					def imageId = parseImageId(resourceConfig, appConfig)
					// resourceConfig.image_id && resourceConfig.image_id instanceof String
					if(imageId) {
						if(!updateServerForAMI(server, cloud, imageId as String, regionCode)) {
							failedList << [msg: "Error in obtaining image information for ${imageId}"] + newResource
						}
					} else if(resourceConfig.installAgent || cloudFormationOpts.applyCloudInit) {
						resources.each { failedList << [msg: "Installing the agent and/or using cloud-init requires a string to be specified for ImageId for the resource ${resourceConfig.name}"] + it}
					}
					if(!failedList) {
						def sshPassword = generatePassword()
						def osType = server.osType ?: 'linux'

						def cloudConfigUser = opts.cloudConfigUserMap[server.id].cloudConfigUser
						def userConfig = opts.cloudConfigUserMap[server.id].userConfig
						if(cloudFormationOpts.applyCloudInit || resourceConfig.installAgent ) {
							def amazonClient = plugin.getAmazonClient(cloud, false, regionCode)
							try {
								// MUST specify a keypair (KeyName in the template for the resource)
								if(resourceConfig.key_name) {
									// Use the key pair specified (exception if we do not know about the key pair)
									def keyNameValue = getValueForProperty(resourceConfig.key_name, templateParameters)
									def keyConfig = [:]
									def key = morpheusContext.async.keyPair.find(new DataQuery().withFilters([
									        new DataFilter('accountId', app.account.id),
											new DataFilter('name', keyNameValue)
									])).blockingGet()
									if (!key) {
										failedList << [msg: "KeyName of (${keyNameValue}) specified but no key by that name exists in Morpheus."] + newResource
									} else {

										EC2ProvisionProvider.ensureAmazonKeyPair(keyConfig, amazonClient, app.account, cloud, key)
										// primaryKey is AccountKeyPair
										resourceConfig.key_id = key.id
										resourceConfig.key_name = keyConfig.publicKeyName
										templateJson.Resources[resourceConfig.name].Properties.KeyName = keyConfig.publicKeyName
									}
								} else {
									// If not specified, use the account key pair and update the template
									def key = morpheusContext.async.keyPair.findOrGenerateByAccount(app.account.id).blockingGet()
									def keyConfig = [:]
									EC2ProvisionProvider.ensureAmazonKeyPair(keyConfig, amazonClient, app.account, cloud, key)
									// primaryKey is AccountKeyPair
									resourceConfig.key_id = key.id
									resourceConfig.key_name = keyConfig.publicKeyName
									templateJson.Resources[resourceConfig.name].Properties.KeyName = keyConfig.publicKeyName
								}
								addCloudInit(templateJson, resourceConfig.name, cloudConfigUser, server.osType)
							} catch(e) {
								log.error("Error in finding keypair ${e}", e)
								failedList << [msg: "KeyName of (${resourceConfig.key_name}) specified but no error getting key in Morpheus."] + newResource
							}
						}
						server.sshUsername = userConfig.sshUsername
						server.sshPassword = userConfig.sshPassword ?: sshPassword
						morpheusContext.async.computeServer.save(server).blockingGet()
					}
				}
			}
			if(!failedList) {
				def stackName = performingUpdate ? app.getConfigProperty('stackName') : getUniqueStackName(cloud, app.name, appConfig.regionCode ?: regionCode)
				def amazonClient = plugin.getAmazonCloudFormationClient(cloud, false, appConfig.regionCode ?: regionCode)
				log.debug "creating CloudFormation stack with template: ${JsonOutput.prettyPrint(templateJson.encodeAsJSON().toString())}"
				def updateOrCreateResults
				def capabilities = parseCapabilities(cloudFormationOpts)
				if(performingUpdate) {
					updateOrCreateResults = AmazonComputeUtility.updateCloudFormationStack(amazonClient, stackName, templateJson, templateParameters, capabilities, isYaml)
				} else {
					updateOrCreateResults = AmazonComputeUtility.createCloudFormationStack(amazonClient, stackName, templateJson, templateParameters, capabilities, isYaml)
				}
				if(updateOrCreateResults.success == true) {
					app.setConfigProperty('stackName', stackName)
					app = saveAndGet(app)
					def deploymentResults = waitForCloudFormationStack(cloud, regionCode, stackName)

					if(deploymentResults.success) {
						// Fetch the resources
						def resourcesResult = AmazonComputeUtility.getCloudFormationStackResources(amazonClient, stackName)
						List<StackResource> stackResourcesResult = resourcesResult.resources
						// After deployment, update the server volumes, nics, etc
						// But make sure we handle security groups first
						resources = resources?.sort { a, b ->
							def aIdx = a.resourceConfig?.morpheusType == 'securityGroupLocation' ? -1 : 0
							def bIdx = b.resourceConfig?.morpheusType == 'securityGroupLocation' ? -1 : 0
							aIdx <=> bIdx
						}
						resources.each { newResource ->
							def resourceConfig = newResource.resourceConfig
							def resourceData = newResource.data
							def physicalResourceId = stackResourcesResult.find {
								it.getLogicalResourceId() == resourceConfig.name
							}?.getPhysicalResourceId()
							def updateResults = updateAppResource(resourceConfig, resourceData, physicalResourceId, cloud, regionCode)
							if (updateResults.success) {
								if (resourceData.newResource) {
									if(resourceConfig?.morpheusType == 'instance') {
										finalizeList << resourceData.instanceId.toLong()
									}
								}
							} else {
								failedList << [msg: updateResults.msg ?: 'Failed to deploy item'] + newResource
							}
						}

						//get the list of resources
						def appResources = app.resources
						//resolve them
						appResources.each { row ->
							def resource = [id:row.id, morpheusType:'accountResource', type:row.resourceType, name:row.name, resource:row]
							log.debug("resolving resource: {}", resource)
							//resolve it
							def resolveResults = CloudFormationResourceMappingUtility.resolveResource(app, resource, stackResourcesResult, opts)
							log.debug("resolve results: {}", resolveResults)
							if(resolveResults.success == true) {
								//update the resource
								resource.state = resolveResults.data.resourceState
								def updateResults = CloudFormationResourceMappingUtility.updateResource(app, resource, opts)
							}
						}
						response.success = deploymentResults.success
					} else {
						resources.each { failedList << [msg: deploymentResults.msg ?: 'Deployment failed'] + it }
					}
				} else {
					// Couldn't even start the deploy of the template... fail every instance
					resources.each { failedList << [msg: updateOrCreateResults.msg ?: 'Failed to deploy stack'] + it }
				}
			}
			//finalize
			response.instanceFinalizeList = finalizeList

			def failedInstances = [:]
			//failed stuff
			failedList.each { resource ->
				def resourceConfig = resource.resourceConfig
				def resourceData = resource.data
				if(resourceData.newResource == true) {
					if(resourceConfig?.morpheusType == 'instance') {
						failedInstances[resourceData.instanceId?.toLong()] = resource.msg
					} else if(resourceConfig?.morpheusType == 'computeZonePool') {
						CloudPool cloudPool = morpheusContext.async.cloud.pool.get(resourceData.id?.toLong()).blockingGet()
						if(cloudPool) {
							cloudPool.status = CloudPool.Status.failed
							morpheusContext.async.cloud.pool.save(cloudPool).blockingGet()
						}
					} else if(resourceConfig?.morpheusType == 'network') {
						//TODO - this is aspirational data services
						Network network = morpheusContext.async.network.get(resourceData.id?.toLong()).blockingGet()
						if(network) {
							network.status = 'failed'
							morpheusContext.async.network.save(network).blockingGet()
						}

					} else if(resourceConfig?.morpheusType == 'instanceScale') {
						InstanceScale instanceScale = morpheusContext.async.instance.scale.get(resourceData.id?.toLong()).blockingGet()
						if(instanceScale) {
							instanceScale.status = InstanceScale.Status.failed
							morpheusContext.async.instance.scale.save(instanceScale).blockingGet()
						}

					} else if(resourceConfig?.morpheusType == 'securityGroupLocation') {
						SecurityGroupLocation securityGroupLocation = morpheusContext.async.securityGroup.location.get(resourceData.id?.toLong()).blockingGet()
						if(securityGroupLocation) {
							securityGroupLocation.status = SecurityGroupLocation.Status.failed
							morpheusContext.async.securityGroup.location.save(securityGroupLocation).blockingGet()
						}

					}
				}
			}
			response.failedInstances = failedInstances

		} catch(e) {
			log.error("run app error: ${e}", e)
			response.message = "Error during deployment: ${e.message}"
		}
		return new ServiceResponse<AppProvisionResponse>(success: response.success, msg: response.message, data: response)
	}

	@Override
	ServiceResponse destroyApp(App app, Map opts) {
		log.info("destroyApp: ${app} ${opts}")
		def rtn = [success:false]
		try {
			// Delete the stack
			def stackName = app.getConfigProperty('stackName')
			def appConfig = new groovy.json.JsonSlurper().parseText(app.getConfigProperty('config'))
			def cloudId = appConfig?.cloudFormation?.zoneId?.toLong() ?: opts.defaultCloud?.id?.toLong()
			def cloud = morpheusContext.async.cloud.get(cloudId).blockingGet()
			if(stackName && cloud) {
				def amazonClient = plugin.getAmazonCloudFormationClient(cloud, false, appConfig.cloudFormation?.regionCode ?: appConfig.regionCode)
				AmazonComputeUtility.deleteCloudFormationStack(amazonClient, stackName)
			}
			rtn.success = true
		} catch(e) {
			log.error("delete app error: ${e}", e)
			rtn.msg = 'error executing CloudFormation destroy'
		}
		return rtn
	}

	@Override
	ServiceResponse<ProvisionResponse> updateApp(App app, AppRequest appRequest, Map map) {
		return null
	}
/**
	 * Returns the host type that is to be provisioned
	 * @return HostType
	 */
	@Override
	HostType getHostType() {
		return HostType.vm
	}

	/**
	 * Provides a Collection of {@link VirtualImage} related to this ProvisionProvider. This provides a way to specify
	 * known VirtualImages in the Cloud environment prior to a typical 'refresh' on a Cloud. These are often used in
	 * predefined layouts. For example, when building up ComputeTypeLayouts
	 * @return Collection of {@link VirtualImage}
	 */
	@Override
	Collection<VirtualImage> getVirtualImages() {
		return new ArrayList<VirtualImage>()
	}

	/**
	 * Provides a Collection of {@link ComputeTypeLayout} related to this ProvisionProvider. These define the types
	 * of clusters that are exposed for this ProvisionProvider. ComputeTypeLayouts have a collection of ComputeTypeSets,
	 * which reference a ContainerType. When returning this structure from implementations, it is often helpful to start
	 * with the ComputeTypeLayoutFactory to construct the default structure and modify fields as needed.
	 * @return Collection of ComputeTypeLayout
	 */
	@Override
	Collection<ComputeTypeLayout> getComputeTypeLayouts() {
		return new ArrayList<ComputeTypeLayout>()
	}

	/**
	 * Returns the Morpheus Context for interacting with data stored in the Main Morpheus Application
	 *
	 * @return an implementation of the MorpheusContext for running Future based rxJava queries
	 */
	@Override
	MorpheusContext getMorpheus() {
		return morpheusContext
	}

	/**
	 * A unique shortcode used for referencing the provided provider. Make sure this is going to be unique as any data
	 * that is seeded or generated related to this provider will reference it by this code.
	 * @return short code string that should be unique across all other plugin implementations.
	 */
	@Override
	String getCode() {
		'cloudFormation'
	}

	/**
	 * Provides the provider name for reference when adding to the Morpheus Orchestrator
	 * NOTE: This may be useful to set as an i18n key for UI reference and localization support.
	 *
	 * @return either an English name of a Provider or an i18n based key that can be scanned for in a properties file.
	 */
	@Override
	String getName() {
		return "CloudFormation"
	}

	/**
	 * A unique shortcode used for referencing the provided provider provision type. Make sure this is going to be unique as any data
	 * that is seeded or generated related to this provider will reference it by this code.
	 * @return short code string that should be unique across all other plugin implementations.
	 */
	@Override
	String getProvisionTypeCode() {
		return "cloudFormation"
	}

	/**
	 * Specifies which deployment service should be used with this provider. In this case we are using the vm service
	 * @return the name of the service
	 */
	@Override
	String getDeployTargetService() {
		return "vmDeployTargetService"
	}

	/**
	 * Specifies what format of nodes are created by this provider. In this case we are using the vm format
	 * @return the name of the format
	 */
	@Override
	String getNodeFormat() {
		return null
	}

	/**
	 * For the CF provision provider, we do NOT want a CF instance type created
	 * @return
	 */
	@Override
	Boolean createDefaultInstanceType() {
		return false
	}


	private Instance saveAndGet(Instance instance) {
		def saveSuccessful = morpheusContext.async.instance.save(instance).blockingGet()
		if(!saveSuccessful) {
			log.warn("Error saving instance: ${instance?.id}" )
		}
		return morpheusContext.async.instance.get(instance.id).blockingGet()
	}

	private App saveAndGet(App app) {
		def saveSuccessful = morpheusContext.async.app.save(app).blockingGet()
		if(!saveSuccessful) {
			log.warn("Error saving app: ${app?.id}" )
		}
		return morpheusContext.async.app.get(app.id).blockingGet()
	}

	def buildWorkloadStateForInstance(Instance instance, ResourceSpec spec, String specContent) {
		log.debug "buildWorkloadStateForInstance: ${instance}"

		def stateDate = new Date()
		def stateConfig = [
				account        : instance.account,
				stateType      : 'cloudFormation',
				code           : ('cloudFormation.state.resource-spec-' + spec.id),
				category       : 'cloudFormation.state',
				stateContext   : '',
				enabled        : true,
				tags           : instance.tags,
				refType        : 'Instance',
				refId          : instance.id,
				refName        : instance.name,
				refVersion     : '1',
				refDate        : stateDate,
				resourceVersion: '1',
				stateId        : 'resource-spec-' + spec.id,
				stateDate      : stateDate,
				name           : instance.name + ' - ' + spec.name + ' cf state',
				subRefType     : 'ResourceSpec',
				subRefId       : spec.id,
				subRefName     : spec.name,
				subRefVersion  : spec.resourceVersion,
				subRefDate     : stateDate,
				planData       : specContent,
				uuid           : UUID.randomUUID(),
				apiKey         : UUID.randomUUID()
		]

		WorkloadState workloadState = new WorkloadState(stateConfig)
		workloadState.setConfigProperty('stackId', spec.externalId)
		workloadState.setConfigProperty('stackName', spec.resourceName)
		workloadState = morpheusContext.async.workloadState.create(workloadState).blockingGet()
		return workloadState
	}

	private getUniqueStackName(cloud, proposedName, regionCode) {
		// Must make sure it is unique within AWS
		def amazonClient = plugin.getAmazonCloudFormationClient(cloud, false, regionCode)
		def currentStackName = proposedName
		// Must contain only letters, numbers, dashes and start with an alpha character
		currentStackName = currentStackName.replaceAll(/[^a-zA-Z0-9\-]/, '')
		def startsWithAlpha = (currentStackName ==~ /^[(a-z)|(A-Z)].*/)
		if(!startsWithAlpha) {
			currentStackName = "morpheus${currentStackName}"
		}
		currentStackName = currentStackName.take(255)
		def baseName = currentStackName
		def hasStackName = true
		def i = 0
		while(hasStackName == true && i < 100) {
			hasStackName = AmazonComputeUtility.getCloudFormationStack(amazonClient, currentStackName).success
			if(hasStackName) {
				currentStackName = "${baseName}${i}"
				i++
			}
		}
		return currentStackName
	}

	def configureResourceSpec(Instance instance, Map templateJson, Map opts, regionCode) {
		def rtn = templateJson
		//if we have any container vms on the instance, we need to customize the cloud init data
		if(instance.containers?.size() > 0) {
			instance.containers?.each { container ->
				def server = container.server
				//get the matching image?
				def awsConfig = server.getConfigProperty('awsConfig')
				def amazonClient = plugin.getAmazonClient(server.cloud, false, regionCode)
				if(awsConfig?.imageId) {
					//save the image
					def imageResults = EC2ProvisionProvider.saveAccountImage(amazonClient, instance.account, server.cloud, regionCode, awsConfig.imageId, morpheusContext)
					log.debug("imageResults: {}", imageResults)
					def awsImage
					if(imageResults.image) {
						server.sourceImage = imageResults.image
						awsImage = imageResults.awsImage
					}
					//image config
					if(awsImage == null) {
						def awsResults = AmazonComputeUtility.loadImage([amazonClient:amazonClient, imageId:awsConfig.imageId])
						if(awsResults.success == true && awsResults.image)
							awsImage = awsResults.image
					}
					//set os info
					if(awsImage) {
						server.osType = awsImage.getPlatform() == 'windows' ? 'windows' : 'linux'
						//server.isCloudInit = server.osType == 'windows' ? false : true
						server.serverOs = new OsType(code:server.osType)
					}
				}
				//key
				if(awsConfig?.keyName) {
					def awsKey = morpheusContext.async.keyPair.find(new DataQuery().withFilters([
					        new DataFilter('account', instance.account.id),
							new DataFilter('name', awsConfig.keyName)
					])).blockingGet()
					log.debug("keyName: {} key: {}", awsConfig.keyName, awsKey)
					if(awsKey) {
						def accessConfig = [accessType:'ssh', host:'0.0.0.0', username:server.sshUsername ?: 'root',
											password:server.sshPassword, accessKey:awsKey]
						def serverAccess = new ComputeServerAccess(accessConfig)
						serverAccess = morpheusContext.async.computeServer.access.create(serverAccess).blockingGet()
						server.accesses += serverAccess
						morpheusContext.async.computeServer.save(server)
					}
				}

				def cloudConfigUser = opts.cloudConfigUserData[container.id]
				addCloudInit(rtn, container.displayName, cloudConfigUser as String, server.osType)

			}
		}
		return rtn
	}

	private parseCapabilities(Map cloudformationConfig=[:]) {
		log.debug "parseCapabilities: ${cloudformationConfig}"
		def capabilities = []
		if(cloudformationConfig) {
			if(MorpheusUtils.parseBooleanConfig(cloudformationConfig['IAM'])) {
				capabilities << 'CAPABILITY_IAM'
			}
			if(MorpheusUtils.parseBooleanConfig(cloudformationConfig['CAPABILITY_NAMED_IAM'])) {
				capabilities << 'CAPABILITY_NAMED_IAM'
			}
			if(MorpheusUtils.parseBooleanConfig(cloudformationConfig['CAPABILITY_AUTO_EXPAND'])) {
				capabilities << 'CAPABILITY_AUTO_EXPAND'
			}
		}
		capabilities
	}

	def waitForCloudFormationStack(Cloud cloud, String regionCode, String stackName) {
		def rtn = [success:false]
		try {
			def pending = true
			def attempts = 0
			while(pending) {
				def amazonClient = plugin.getAmazonCloudFormationClient(cloud, false, regionCode)
				def stackDetail = AmazonComputeUtility.getCloudFormationStack(amazonClient, stackName)
				if(stackDetail.success == true) {
					def stackStatus = stackDetail?.stack?.getStackStatus()
					//update every 50 seconds or so?
					if((attempts % 5) == 0)
						log.info("${stackName} is ${stackStatus}")
					rtn.results = stackDetail.stack
					if(stackStatus in ['CREATE_COMPLETE', 'UPDATE_COMPLETE']) {
						log.info("${stackName} completed as ${stackStatus}")
						rtn.success = true
						pending = false
					} else if(stackStatus == 'ROLLBACK_COMPLETE' || stackStatus.contains('FAILED')) {
						log.info("${stackName} failed with ${stackStatus}")
						rtn.success = false
						pending = false
					}
				}
				attempts ++
				if(attempts > 1080) {
					pending = false
				} else {
					if(pending) {
						sleep(1000l * 10l)
					}
				}
			}
		} catch(e) {
			log.error(e)
		}
		return rtn
	}

	def updateWorkloadStateFromCloud(WorkloadState workloadState, Map opts) {
		log.debug "updateWorkloadStateFromCloud: ${workloadState} ${opts}"

		try {
			if(opts.stack) {
				updateWorkloadStateInfo(workloadState, opts.stack)
			}
			if(opts.stackEvents) {
				updateWorkloadStateEvents(workloadState, opts.stackEvents)
			}
			if(opts.stack) {
				updateWorkloadStateOutputs(workloadState, opts.stack)
			}
			if(opts.stack) {
				updateWorkloadStateParameters(workloadState, opts.stack)
			}
			if(opts.template) {
				updateWorkloadStateTemplate(workloadState, opts.template)
			}
		} catch(e) {
			log.error "Error in updateWorkloadStateFromCloud: ${e}", e
		}
	}

	def updateWorkloadStateInfo(WorkloadState workloadState, stack) {
		log.debug "updateWorkloadStateInfo: ${workloadState} ${stack}"

		if(workloadState && stack) {
			def data = [
					stackId                    : stack.stackId,
					stackName                  : stack.stackName,
					changeSetId                : stack.changeSetId,
					description                : stack.description,
					creationTime               : stack.creationTime,
					deletionTime               : stack.deletionTime,
					lastUpdatedTime            : stack.lastUpdatedTime,
					rollbackConfiguration      : stack.rollbackConfiguration,
					stackStatus                : stack.stackStatus,
					stackStatusReason          : stack.stackStatusReason,
					notificationARNs           : stack.notificationARNs,
					timeoutInMinutes           : stack.timeoutInMinutes,
					capabilities               : stack.capabilities,
					tags                       : stack.tags?.collect { it -> [key: it.key, value: it.value] },
					roleARN                    : stack.roleARN,
					enableTerminationProtection: stack.enableTerminationProtection,
					parentId                   : stack.parentId,
					rootId                     : stack.rootId,
					driftInformation           : [stackDriftStatus: stack.driftInformation?.stackDriftStatus, lastCheckTimestamp: stack.driftInformation?.lastCheckTimestamp]
			]

			workloadState.setConfigProperty('stackName', stack.stackName)
			workloadState.setConfigProperty('stackId', stack.stackId)
			workloadState.stateData = data.encodeAsJSON().toString()
			morpheusContext.async.workloadState.save(workloadState).blockingGet()
		}
	}

	def updateWorkloadStateEvents(WorkloadState workloadState, stackEvents) {
		log.debug "updateWorkloadStateEvents: ${workloadState} ${stackEvents}"

		if(workloadState && stackEvents) {
			def rawData = [events: []]
			for (StackEvent stackEvent in stackEvents) {
				rawData.events << [
						timestamp        : stackEvent.timestamp,
						logicalResourceId: stackEvent.logicalResourceId,
						status           : stackEvent.resourceStatus,
						statusReason     : stackEvent.resourceStatusReason
				]
			}

			workloadState.rawData = rawData.encodeAsJSON().toString()
			morpheusContext.async.workloadState.save(workloadState).blockingGet()
		}
	}

	def updateWorkloadStateOutputs(WorkloadState workloadState, stack) {
		log.debug "updateWorkloadStateOutputs: ${workloadState} ${stack}"

		if(workloadState && stack) {
			def outputData = [outputs: []]
			for(output in stack.outputs) {
				outputData.outputs << [key: output.outputKey, value: output.outputValue, description: output.description, exportName: output.exportName]
			}
			workloadState.outputData = outputData.encodeAsJSON().toString()
			morpheusContext.async.workloadState.save(workloadState).blockingGet()
		}
	}

	def updateWorkloadStateParameters(WorkloadState workloadState, stack) {
		log.debug "updateWorkloadStateParameters: ${workloadState} ${stack}"

		if(workloadState && stack) {
			def inputData = [parameters: []]
			for (p in stack.parameters) {
				inputData.parameters << [
						key          : p.parameterKey,
						value        : p.parameterValue,
						resolvedValue: p.resolvedValue
				]
			}
			workloadState.inputData = inputData.encodeAsJSON().toString()
			morpheusContext.async.workloadState.save(workloadState).blockingGet()
		}
	}

	def updateWorkloadStateTemplate(WorkloadState workloadState, template) {
		log.debug "updateWorkloadStateTemplate: ${workloadState} ${template}"

		if(workloadState && template) {
			def data = [
					stagesAvailable: template.stagesAvailable
			]
			workloadState.planData = data.encodeAsJSON().toString()
			morpheusContext.async.workloadState.save(workloadState).blockingGet()

			ResourceSpec resourceSpec = morpheusContext.async.resourceSpec.find(new DataQuery().withFilter('id', workloadState.subRefId)).blockingGet()
			def templateContent = template.templateBody
			def isYaml = !templateContent?.trim()?.startsWith('{')
			if(isYaml) {
				resourceSpec.templateContent = templateContent
			} else {
				resourceSpec.templateContent = JsonOutput.prettyPrint(templateContent)
			}
			morpheusContext.async.resourceSpec.save(resourceSpec).blockingGet()
		}
	}

	def getResourceProcessOutput(Map resource) {
		//resource: [id, morpheusType:'accountResource', type, name, resource, state]
		def rtn = ''
		if(resource.state) {
			def itemList = []
			//build up a description of what was done
			Date date = resource.state.getTimestamp()
			def timestamp = date.format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone('GMT'))
			itemList << "resource created: ${resource.state.getLogicalResourceId()} at: ${timestamp}"
			itemList << "  type: ${resource.state.getResourceType()} - id: ${resource.state.getPhysicalResourceId()}"
			if(resource.state.getDescription())
				itemList << "  description: ${resource.state.getDescription()}"
			itemList << "  status: ${resource.state.getResourceStatus()}"
			if(resource.state.getResourceStatusReason())
				itemList << "  reason: ${resource.state.getDescription()}"
			rtn = itemList.join('\n')
		}
		return rtn
	}

	def finalizeResource(Instance instance, AccountResource resource, Map opts) {
		def rtn = [success:false, data: [:]]
		if(resource && resource.refType && resource.refId) {
			//linked resource - sync it
			if(resource.refType == 'container') {
				def workload = morpheusContext.async.workload.get(resource.refId?.toLong()).blockingGet()
				if(workload)
					rtn = finalizeResourceWorkload(workload, opts)
			} else if(resource.refType == 'instance') {
				rtn = finalizeResourceInstance(instance, opts)
			}
			//add other types?
		} else {
			rtn.success = true
		}
		return rtn
	}

	def finalizeResourceInstance(App app, Instance instance, Map opts) {
		//override in provision service
		for(workload in instance.containers) {
			return finalizeResourceWorkload(workload, opts)
		}
	}

	def finalizeResourceInstance(Instance instance, Map opts) {
		//override in provision service
		for(workload in instance.containers) {
			return finalizeResourceWorkload(workload, opts)
		}
	}

	//applied on each container found during an app provision
	def finalizeResourceWorkload(Workload workload, Map opts) {
		try {
			def server = workload?.server
			def rtn = [success: false, error: "", data: [:]]
			if(server && server.externalId) {
				//wait for it to finish
				def amazonClient = plugin.getAmazonClient(server.cloud, server.resourcePool?.regionCode)
				def runConfig = [amazonClient:amazonClient, serverId:server.externalId, name:server.name]


				//wait for the server to launch
				AmazonComputeUtility.waitForServerExists(runConfig)

				if(!opts.skipMetadataTags) {
					//apply tags
					//runConfig.tagList = buildMetadataTagList(server, [maxNameLength: 128, maxValueLength: 256])
					//AmazonComputeUtility.applyEc2Tags(runConfig)
				}

				//wait for ready
				def statusResults = AmazonComputeUtility.checkServerReady(runConfig)
				if(statusResults.success == true) {
					//good to go
					def serverDetails = AmazonComputeUtility.getServerDetail(runConfig)
					if(serverDetails.success == true) {
						log.debug("server details: {}", serverDetails)
						//install agent

						def privateIp = serverDetails.server.getPrivateIpAddress()
						def publicIp = serverDetails.server.getPublicIpAddress()
						def networkOpts = [:]
						//update network info


						morpheusContext.async.process.startProcessStep(opts.process as Process, new ProcessEvent(type: ProcessEvent.ProcessType.provisionFinalize), 'pending').blockingGet()
						rtn.success = true
						rtn.data = [server: server, privateIp: privateIp, publicIp: publicIp]
						//done
					} else {
						//error
						log.warn("finalize resource error: {}", serverDetails)
						rtn.error = 'failed to load instance detail'
					}
				} else {
					//error
					log.warn("finalize resource error: {}", statusResults)
					rtn.error = 'failed waiting for instance to become ready'
				}
			} else {
				//error
				log.warn("finalize resource error - no id for instance")
				rtn.error = 'failed to resolve instance id'
			}
			return rtn
		} catch(e) {
			log.error("error finalizing resource workload: ${e}", e)
		}
	}

	private addCloudInit(templateJson, logicalId, String cloudInit, osType) {
		def isLinux = (osType != 'windows')
		// Need to find the resource block for this VM and update the userdata section
		def propertiesBlock = templateJson?.Resources[logicalId]?.Properties
		def existingUserData = JsonOutput.toJson(propertiesBlock?.UserData ?: {})
		log.debug("cloudInit: {}", cloudInit)
		log.debug("existingUserData : {}", existingUserData)
		if(!propertiesBlock.UserData || !existingUserData.contains('Fn::Base64')) {
			propertiesBlock.UserData = cloudInit.bytes.encodeBase64().toString()
		} else if(existingUserData?.contains('Fn::Base64')) {
			def lines = []
			if(propertiesBlock.UserData['Fn::Base64'] instanceof String) {
				// i.e. [UserData: ['Fn::Base64':'''#!/bin/bash\nsudo touch /tmp/start\n\nexec 3>&1 4>&2\ntrap 'exec 2>&4 1>&3' 0 1 2 3\nexec 1>/tmp/log.out 2>&1\n\nsudo yum update\nsudo yum upgrade -y\n\nsudo amazon-linux-extras enable python3.8\nsudo yum install python3.8 -y\n\nsudo amazon-linux-extras install -y lamp-mariadb10.2-php7.2 php7.2\nsudo yum install -y httpd mariadb-server\nsudo usermod -a -G apache ec2-user\n\nsudo echo \"<!DOCTYPE html>\n<html>\n<head>\n<title>Page Title</title>\n    <style>\n        * {\n            margin: 0;\n            padding: 0;\n        }\n        .imgbox {\n            display: grid;\n            height: 100%;\n        }\n        .center-fit {\n            max-width: 100%;\n            max-height: 100vh;\n            margin: auto;\n        }\n    </style>\n</head>\n<body>\n<div class=\\\"imgbox\\\">\n        <img class=\\\"center-fit\\\"src=\\\"https://dorperbucket.s3.us-west-2.amazonaws.com/logo.png\\\" alt=\\\"Dorper\\\">\n</div>\n</body>\n</html>\" > /var/www/html/main.html\n\nsudo systemctl start httpd\nsudo systemctl enable httpd\n\nsudo pip3 install pyfiglet\nsudo pip3 install termcolor\n\nsudo wget https://dorperbucket.s3.us-west-2.amazonaws.com/draper.py -O /home/ec2-user/draper.py\n\n#Script is done flag\nsudo touch /tmp/end\n''']]
				lines = propertiesBlock.UserData['Fn::Base64'].split(/\\n/)
			} else {
				lines = propertiesBlock.UserData['Fn::Base64']['Fn::Join'][1]
			}
			// Then we will append to the list of items being joined
			def isCloudConfig = lines.find{ it instanceof String && it.contains('#cloud-config') } != null
			if(isCloudConfig) {
				log.warn("Existing CloudFormation templates contains #cloud-config block.. overwriting with Morpheus'")
				propertiesBlock.UserData = cloudInit.bytes.encodeBase64().toString()
			} else if(isLinux) {
				def isBash = lines.find{ it instanceof String && it.contains('!/bin/bash') } != null
				if(isBash) {
					// Add all the lines to the runCmd section
					def newLines = cloudInit.tokenize('\n').collect { "${it}\n".toString() }
					propertiesBlock.UserData['Fn::Base64'] = ['Fn::Join': ["", []]]
					propertiesBlock.UserData['Fn::Base64']['Fn::Join'][1] = newLines
					def EXECUTE_COMMAND_PREFIX = "- "
					def startOfCommand = true
					lines?.each { l ->
						if (l instanceof String) {
							if (!l.startsWith('#')) {
								propertiesBlock.UserData['Fn::Base64']['Fn::Join'][1] << (startOfCommand ? EXECUTE_COMMAND_PREFIX : '') + l.toString()
								startOfCommand = l.contains('\n')
							} else {
								def lineWithoutComment = l.substring(l.indexOf("\n") ? l.indexOf("\n") + 1 : 0)
								if(lineWithoutComment) {
									// If we detect more line breaks... we can't handle it.. suggest they put their multiline string into the array of Fn::Join
									if(lineWithoutComment.indexOf("\n") > 0) {
										log.warn("The existing CloudFormation template's UserData section contains a string with multiple line breaks. ${l} This is not supported. Split each line of the command into entries a Fn::Bash64 -> Fn::Join section")
									} else {
										propertiesBlock.UserData['Fn::Base64']['Fn::Join'][1] << (startOfCommand ? EXECUTE_COMMAND_PREFIX : '') + lineWithoutComment.toString()
										startOfCommand = lineWithoutComment.endsWith('\n')
									}
								}
							}
						} else {
							propertiesBlock.UserData['Fn::Base64']['Fn::Join'][1] << l // Probably a Map
						}
					}
				} else {
					propertiesBlock.UserData = cloudInit.bytes.encodeBase64().toString()
				}
			} else {
				// May contain either <script> (for bash scripts) or <powershell> (for powershell)
				// First line and last line enclose powershell, so find the powershell block
				def newLines = cloudInit.tokenize('\r\n').collect { "${it}\n" }
				def powerShellEnd = lines.findIndexOf { it instanceof String && it.startsWith('</powershell>') }
				propertiesBlock.UserData['Fn::Base64']['Fn::Join'][1] = []
				if(powerShellEnd == -1){
					// If no powershell block...
					// Add all the lines... then add all of ours
					lines?.each { l ->
						propertiesBlock.UserData['Fn::Base64']['Fn::Join'][1] << l
					}
					propertiesBlock.UserData['Fn::Base64']['Fn::Join'][1] << "\n"
					newLines?.each { l ->
						propertiesBlock.UserData['Fn::Base64']['Fn::Join'][1] << l
					}
				} else {
					// Add all the lines until right before the end of the powershell block
					lines?.eachWithIndex{ l, i ->
						if(i != powerShellEnd) {
							propertiesBlock.UserData['Fn::Base64']['Fn::Join'][1] << l
						} else {
							// We are at the end of the powershell block so add all of our lines
							newLines?.eachWithIndex { ourLine, j ->
								if(j != 0) {
									propertiesBlock.UserData['Fn::Base64']['Fn::Join'][1] << ourLine
								}
							}
						}
					}
				}
			}
		}
	}

	//App helpers

	/**
	 * Sets up the config for the App if the parameter value is missing from the App config but exists as a default
	 * value in the template
	 * @param app
	 * @param templateJson
	 */
	def configureDefaultTemplateParameters(App app, Map config, forValidation = false) {
		try {
			def templateParameters = getTemplateParameters(app.template, [:])?.data

			def appConfig = new groovy.json.JsonSlurper().parseText(app.getConfigProperty('config') ?: '{}')
			if (!appConfig.templateParameter) {
				appConfig.templateParameter = [:]
			}
			def parameters = appConfig.templateParameter

			def saveRequired = false
			templateParameters?.each { parameter ->
				log.debug "Working on ${parameter} "
				if (parameters[parameter.name] == null && parameter.defaultValue != null) {
					log.debug "no default value for ${parameter.name}.. setting to ${parameter.defaultValue}"
					parameters[parameter.name] = parameter.defaultValue
					saveRequired = true
				}
			}
			if (saveRequired) {
				log.debug "Saving app config"
				app.setConfigProperty('config', appConfig.encodeAsJSON().toString())
				if (!forValidation)
					app.save(flush: true)
			}
		} catch(e) {
			log.error "Error in configureDefaultTemplateParameters: ${e}", e
		}
	}

	ServiceResponse getTemplateParameters(AppTemplate appTemplate, Map opts) {
		def parameters = []
		def templateConfig = appTemplate.getConfigMap()
		def cloudFormationOpts = templateConfig.cloudFormation
		def configType = cloudFormationOpts.configType ?: 'json'
		def template
		try {
			if(configType == 'json') {
				def cloudFormationJson = appTemplate.configMap.cloudFormation.json
				template = new groovy.json.JsonSlurper().parseText(cloudFormationJson)
			} else if(configType == 'yaml') {
				def cloudFormationYaml = appTemplate.configMap.cloudFormation.yaml
				template = CloudFormationResourceMappingUtility.loadYamlOrJsonMap(cloudFormationYaml)
			} else if(configType == 'git') {
				def codeRepositoryId = cloudFormationOpts.git.repoId
				def codeRepository = morpheusContext.async.codeRepository.fetchCodeRepository(codeRepositoryId, cloudFormationOpts?.git?.branch)
				if (codeRepository) {
					def gitCloudFormationConfig = loadCloudFormationConfigFromRepo(codeRepository, cloudFormationOpts)
					if(!gitCloudFormationConfig.success) {
						throw new Exception('Error in loading CloudFormation config from git repository')
					}
					template = gitCloudFormationConfig?.template
				}
			}
		} catch(e) {
			log.error "Error in obtaining CloudFormation template: $e", e
		}
		def parametersJson = template?.Parameters
		parametersJson?.each { n, v ->
			def parameter = [
					name: n,
					displayName: n,
					required: true,
					options: [],
					description: v.Description,
					defaultValue: v.Default,
					minLength: v.MinLength,
					maxLength: v.MaxLength,
					minValue: v.MinValue,
					maxValue: v.MaxValue
			]
			switch(v.Type.toLowerCase()) {
				case 'Number':
					parameter.inputType = true
					break
				case 'String':
				default:
					if(v.AllowedValues) {
						parameter.selectType = true
						parameter.options = v.AllowedValues.collect {
							[name: it, value: it, selected: (parameter.defaultValue != null && parameter.defaultValue == it)]
						}
					} else {
						if(v.NoEcho?.toString()?.toLowerCase() == 'true') {
							parameter.passwordType = true
						} else {
							parameter.inputType = true
						}
					}

					break
			}
			parameters << parameter
		}

		ServiceResponse.success(parameters)
	}

	def loadCloudFormationConfigFromRepo(codeRepository, opts) {
		log.debug "loadCloudFormationConfigFromRepo: $codeRepository, $opts"
		def rtn = [ success: false, template:null, parameters: null, msg: '', isYaml: false ]
		def templateFileCount = 0
		def fileResults = loadCloudFormationFiles(codeRepository, opts)
		if(fileResults.success == true) {
			fileResults.files?.each { file ->
				try {
					if(file?.getName()?.toLowerCase().endsWith('.json')) {
						def json = new groovy.json.JsonSlurper().parseText(file.getText())
						if(json['AWSTemplateFormatVersion']) {
							rtn.template = json
							templateFileCount++
						}
					} else if(file?.getName()?.toLowerCase().endsWith('.yaml')) {
						def yaml = MorpheusUtils.loadYamlOrJsonMap(file.getText())
						if(yaml['AWSTemplateFormatVersion']) {
							rtn.template = yaml
							templateFileCount++
							rtn.isYaml = true
						}
					}
				} catch(e) {
					log.error('error parsing file: ' + e)
				}
			}
		}
		if(templateFileCount == 1) {
			rtn.success = true
		}
		if(templateFileCount > 1) {
			rtn.msg += 'Found more than 1 CloudFormation template file in directory and sub directories.'
		}
		return rtn
	}

	def loadCloudFormationFiles(CodeRepositoryResponse repo, Map opts = [:]) {
		def rtn = [success:false, rootPath:null, files:[]]
		try {
			if(repo?.root) {
				def workingPath = repo.root
				if(opts?.git?.path)
					workingPath = new File(workingPath, opts.git.path)
				rtn.rootPath = workingPath
				def repoFiles = workingPath.listFiles()
				repoFiles?.each { repoFile ->
					//add a match to the opts.path if present
					appendCloudFormationFiles(rtn.files, repoFile)
				}
				rtn.success = true
			}
		} catch(e) {
			log.error("load CloudFormation files error: ${e}", e)
		}
		return rtn
	}

	def appendCloudFormationFiles(List fileList, File targetFile) {
		if(targetFile.isDirectory()) {
			targetFile.listFiles().each { childFile ->
				appendCloudFormationFiles(fileList, childFile)
			}
		} else {
			def fileName = targetFile?.getName()?.toLowerCase()
			if(fileName.endsWith('.json')) {
				fileList << targetFile
			} else if(fileName.endsWith('.yaml')) {
				fileList << targetFile
			}
		}
	}

	private static parseImageId(resourceConfig, appConfig) {
		def imageId
		try {
			if (resourceConfig.image_id && resourceConfig.image_id instanceof String) {
				imageId = resourceConfig.image_id
			} else if((resourceConfig.image_id instanceof Map) && (resourceConfig.image_id['Ref'] instanceof String)) {
				def imageIdRef = resourceConfig.image_id['Ref']
				imageId = appConfig?.templateParameter[imageIdRef]
			}
		} catch(e) {
			log.error "Error parsing imageId: ${e}", e
		}
		return imageId
	}

	private updateServerForAMI(ComputeServer server, Cloud cloud, String imageId, String regionCode) {
		try {
			def opts = [cloud:cloud, amazonClient:plugin.getAmazonClient(cloud, false, regionCode),
						amazonSystemsManagementClient:plugin.getAmazonSystemsManagementClient(cloud),
						imageIds:imageId, allImages:true
			]
			def imageResults = AmazonComputeUtility.listImages(opts)
			if(!imageResults.success || imageResults.imageList.size() != 1) {
				throw new Exception("Unable to find image with id ${imageId}")
			}
			def imageDetails = imageResults.imageList.first()
			def platform = imageDetails.getPlatform() == 'windows' ? 'windows' : 'linux'
			def osType
			if(platform == 'windows')
				osType = new OsType(code: 'windows')
			else
				osType = new OsType(code: 'linux')
			server.osType = platform
			server.serverOs = osType
			morpheusContext.async.computeServer.save(server).blockingGet()
		} catch(e) {
			log.error "Error in updateServerForAMI: ${e}", e
			return false
		}
		return true
	}

	static generatePassword(length = 12) {
		def passwordCharSet = (('A'..'Z')+('a'..'z')+('0'..'9')+['!', '@', '#', '\$', '%', '^', '&', '*']).join()
		def random = new Random()
		return (1..length).collect{passwordCharSet[random.nextInt(passwordCharSet.length())]}.join()
	}

	static def getInstanceCreateUser(Instance instance) {
		def rtn
		def createUser = instance.getConfigProperty('createUser') != null ? instance.getConfigProperty('createUser') : instance.getConfigProperty('_createUser')
		if(createUser != null) {
			if(createUser == true || createUser == 'on' || createUser == 'true')
				rtn = instance.createdBy
		} else {
			rtn = instance.createdBy
		}
		log.debug("create user: ${rtn}")
		return rtn
	}


	static String convertResourcesToJSON(List resources) {
		resources.each { resource ->
			if (resource.resourceConfig) {
				def config = resource.resourceConfig.collectEntries { key, value ->
					if (value instanceof MorpheusModel) {
						[(key): (value.hasProperty('code') ? ["code": value.code] : ["id": value?.id])]
					} else {
						[(key): value]
					}
				}
				resource.resourceConfig = config
			}
		}
		return resources.encodeAsJSON().toString()
	}

	private getValueForProperty(propertyValue, parameters) {
		try {
			if (propertyValue instanceof String) {
				return propertyValue
			} else {
				// For now.. only allow Ref functions
				return parameters[propertyValue['Ref']]
			}
		} catch(e) {
			throw new Exception("Property value of ${propertyValue} not supported")
		}
	}

	ServiceResponse updateAppResource(Map resourceConfig, resourceData, physicalResourceId, resourceZone, regionCode) {
		log.debug "updateAppResource: $resourceConfig, $resourceData, $physicalResourceId"
		def rtn = [success: false, msg: '']
		try {
			if (resourceConfig?.morpheusType == 'instance') {

				// Go fetch the instance from Amazon and pass off the data to
				// AmazonComputeService to do the updates
				Instance instance = morpheusContext.async.instance.get(resourceData.instanceId.toLong()).blockingGet()
				Workload workload = morpheusContext.async.workload.get(resourceData.containerIds.first().toLong()).blockingGet()
				ComputeServer server = workload.server
				server.externalId = physicalResourceId
				morpheusContext.async.computeServer.save(server).blockingGet()
				Cloud cloud = server.cloud

				updateVirtualMachine(cloud, server, regionCode)


				instance.maxCores = server.maxCores
				instance.maxMemory = server.maxMemory
				instance.maxStorage = server.maxStorage
				instance.plan = server.plan
				instance.displayName = server.name
				morpheusContext.async.instance.save(instance).blockingGet()

				//get the windows administrator password
				if(server.osType == 'windows' && !resourceConfig.applyCloudInit && !resourceConfig.installAgent) {
					def checkPasswordOpts = [
							amazonClient: plugin.getAmazonClient(cloud, false, regionCode),
							server      : server,
							primaryKey  : morpheusContext.async.keyPair.get(resourceConfig.key_id).blockingGet()
					]
					def passwordResults = AmazonComputeUtility.checkPasswordReady(checkPasswordOpts)
					if(passwordResults.success == true) {
						server.sshUsername = 'Administrator'
						server.sshPassword = passwordResults.password
						morpheusContext.async.computeServer.save(server).blockingGet()
					}
				}
				rtn.success = true
			}
//				else if (resourceConfig?.morpheusType == 'computeZonePool') {
//					updateAppTemplateResource(App.get(resourceData.appId), [:], [id: resourceData.id, config: [morpheusType: resourceConfig?.morpheusType, externalId: physicalResourceId]], [:])
//					def computeZonePool = morpheusContext.async.cloud.pool.get(resourceData.id).blockingGet()
//					updateVPC(computeZonePool)
//					rtn.success = true
//				} else if (resourceConfig?.morpheusType == 'network') {
//					updateAppTemplateResource(App.get(resourceData.appId), [:], [id: resourceData.id, config: [morpheusType: resourceConfig?.morpheusType, externalId: physicalResourceId]], [:])
//					def network = Network.get(resourceData.id)
//					updateSubnet(network, network.zone)
//					rtn.success = true
//				} else if (resourceConfig?.morpheusType == 'instanceScale') {
//					updateAppTemplateResource(App.get(resourceData.appId), [:], [id: resourceData.id, config: [morpheusType: resourceConfig?.morpheusType, externalId: physicalResourceId]], [:])
//					rtn.success = true
//				} else if (resourceConfig?.morpheusType == 'securityGroupLocation') {
//					// For some reason, the cloud stack resources returns the GroupName rather than GroupId for the physicalResourceId
//					// Find the security group by name and then fetch it's groupId to use for the externalId
//					def securityGroup = alistSecurityGroups([account: resourceZone.account, zone: resourceZone], regionCode)?.securityGroups?.find { SecurityGroupInterface sg ->
//						sg.name == physicalResourceId
//					}
//					updateAppTemplateResource(App.get(resourceData.appId), [:], [id: resourceData.id, config: [morpheusType: resourceConfig?.morpheusType, externalId: securityGroup.id]], [:])
//					def securityGroupLocation = SecurityGroupLocation.get(resourceData.id)
//					updateSecurityGroupLocation(securityGroupLocation, ComputeZone.get(securityGroupLocation.refId))
//					rtn.success = true
//				} else {
//					rtn.success = true
//				}

		} catch(e) {
			rtn.msg = "Failed to updateAppResource: ${e}"
			log.error("Failed to updateAppResource: ${e}", e)
		}
		rtn.success ? ServiceResponse.success(rtn) : ServiceResponse.error(rtn.msg ?: "Failed to update resource")
	}

	ServiceResponse updateAppTemplateResource(App app, Map config, Map resource, Map masterConfig) {
		def rtn
		def rtnData = [:]
		try {
			if(resource.config?.morpheusType == 'zone') {
				if(resource.id) {
					//if its a vpc - set the id on the zone config
					//this will turn into a resource pool on a zone when refactor of amazon clouds is complete
					def vpcId = config.id
					if(vpcId) {
						def zoneMatch = morpheusContext.async.cloud.get(resource.id?.toLong()).blockingGet()
						if(zoneMatch) {
							zoneMatch.setConfigProperty('vpc', vpcId)
							if(zoneMatch.name.contains('${') && config['tags.Name']){
								zoneMatch.name = config['tags.Name']
							}
							morpheusContext.async.cloud.save(zoneMatch).blockingGet()
							rtnData.vpc = vpcId
							rtnData.zone = zoneMatch
							rtn = ServiceResponse.success(rtnData)
						} else {
							rtn = ServiceResponse.error('no resource match found to update')
						}
					} else {
						rtn = ServiceResponse.error('no uid found in config')
					}
				} else {
					rtn = ServiceResponse.error('no id found in config')
				}
			} else if(resource.config?.morpheusType == 'instance') {
				if(resource.id) {
					def instanceId = config.id
					//ip addresses
					def publicIp = config['public_ip']
					def privateIp = config['private_ip']
					def hostname = config['public_dns'] ?: config['private_dns']
					//find an elastic ip if no ip?
					if(publicIp == null) {
						masterConfig?.state['aws_eip']?.each { eipName, eipMap ->
							if(eipMap && eipMap['instance'] == instanceId) {
								//have a match - set public ip
								publicIp = eipMap['public_ip']
							}
						}
					}
					//unique id
					def uniqueId = instanceId
					if(uniqueId) {
						def serverList = []
						def serverIds = resource.data.serverIds.collect{ it.toLong() }
						if(resource.data?.serverIds?.size() > 0)
							serverList = ComputeServer.where { id in serverIds }
						def instanceMatch = Instance.get(resource.data.instanceId?.toLong())
						def serverMatch = serverList?.find{ it.uniqueId == uniqueId }
						if(serverMatch == null && publicIp != null)
							serverMatch = serverList?.find{ it.internalIp == publicIp || it.externalIp == publicIp }
						if(serverMatch == null && config.name != null)
							serverMatch = serverList?.find{ it.name == config.name }
						if(serverMatch == null)
							serverMatch = serverList?.find{ it.internalIp == null && it.externalIp == null && it.uniqueId == null }
						//if we have an instance
						def newName = config['name'] ?: resource.key
						if(instanceMatch) {
							def doSave = false
							if(newName && instanceMatch.displayName != newName) {
								instanceMatch.name = newName
								doSave = true
							}
							if(instanceMatch.displayName.contains('${') && config['tags.Name']){
								instanceMatch.displayName = config['tags.Name']
								instanceMatch.name = instanceMatch.displayName
								doSave = true
							}
							if(doSave == true) {
								morpheusContext.async.instance.save(instanceMatch).blockingGet()
							}
						}
						//if we have a server
						if(serverMatch) {
							serverMatch.externalId = instanceId
							serverMatch.uniqueId = uniqueId
							if(newName && serverMatch.name != newName)
								serverMatch.name = newName
							if(privateIp || publicIp)
								setComputeServerNetwork(serverMatch, privateIp, publicIp, hostname)
							log.info("updating app template server: ${serverMatch} - ${publicIp}")
							morpheusContext.async.computeServer.save(serverMatch).blockingGet()
							rtnData.updates = [osDevice:'/dev/xvda', dataDevice:'/dev/xvda', lvmEnabled:false, managed:true,
											   externalId:serverMatch.externalId]
							rtnData.server = serverMatch
							rtn = ServiceResponse.success(rtnData)
						} else {
							rtn = ServiceResponse.error('no resource match found to update')
						}
					} else {
						rtn = ServiceResponse.error('no uid found in config')
					}
				} else {
					rtn = ServiceResponse.error('no id found in config')
				}
			} else if(resource.config?.morpheusType == 'computeZonePool') {
				if(resource.id) {
					CloudPool cloudPool = morpheusContext.async.cloud.pool.get(resource.id?.toLong()).blockingGet()
					if(cloudPool) {
						cloudPool.externalId = resource.config.externalId ?: config.id
						cloudPool.code = "aws.vpc.${cloudPool.refId}.${cloudPool.externalId}"
						cloudPool.status = CloudPool.Status.available
						morpheusContext.async.cloud.pool.save(cloudPool).blockingGet()
						rtn = ServiceResponse.success(rtnData)
					} else {
						rtn = ServiceResponse.error('no resource match found to update')
					}
				} else {
					rtn = ServiceResponse.error('no id found in config')
				}
			} else if(resource.config?.morpheusType == 'network') {
				if(resource.id) {
					Network network = morpheusContext.async.network.get(resource.id?.toLong()).blockingGet()
					if(network) {
						network.externalId = resource.config.externalId ?: config.id
						network.uniqueId = network.externalId
						network.code = "amazon.ec2.subnet.${network.zone.id}.${network.externalId }"
						network.status = null
						morpheusContext.async.network.save(network).blockingGet()
						rtn = ServiceResponse.success(rtnData)
					} else {
						rtn = ServiceResponse.error('no resource match found to update')
					}
				} else {
					rtn = ServiceResponse.error('no id found in config')
				}
			} else if(resource.config?.morpheusType == 'instanceScale') {
				if(resource.id) {
					InstanceScale instanceScale = morpheusContext.async.instance.scale.get(resource.id?.toLong()).blockingGet()
					if(instanceScale) {
						instanceScale.externalId = resource.config.externalId ?: config.id
						instanceScale.status = InstanceScale.Status.available
						morpheusContext.async.instance.scale.save(instanceScale).blockingGet()
						rtn = ServiceResponse.success(rtnData)
					} else {
						rtn = ServiceResponse.error('no resource match found to update')
					}
				} else {
					rtn = ServiceResponse.error('no id found in config')
				}
			} else if(resource.config?.morpheusType == 'securityGroupLocation') {
				if(resource.id) {
					SecurityGroupLocation securityGroupLocation = morpheusContext.async.securityGroup.location.get(resource.id?.toLong()).blockingGet()
					if(securityGroupLocation) {
						securityGroupLocation.externalId = resource.config.externalId ?: config.id
						securityGroupLocation.status = SecurityGroupLocation.Status.available
						morpheusContext.async.securityGroup.location.save(securityGroupLocation).blockingGet()
						rtn = ServiceResponse.success(rtnData)
					} else {
						rtn = ServiceResponse.error('no resource match found to update')
					}
				} else {
					rtn = ServiceResponse.error('no id found in config')
				}
			} else {
				rtn = ServiceResponse.error('unknown resource type')
			}
		} catch(e) {
			log.error("error updating app template resource: ${e}", e)
			rtn = ServiceResponse.error("error updating app template resource: ${e}")
		}
		return rtn
	}

	def updateVirtualMachine(Cloud cloud, ComputeServer server, String regionCode = null) {
		// Update a single virtual machine
		def opts = [cloud: cloud]
		opts.amazonClient = plugin.getAmazonClient(cloud, false, regionCode)
		def serverResults = AmazonComputeUtility.listVpcServers(opts + [filterInstanceId: server.externalId, includeAllVPCs: true])
		def masterItem = serverResults.serverList?.size() == 1 ? serverResults.serverList[0] : null
		def usageLists = [restartUsageIds: [], stopUsageIds: [], startUsageIds: [], updatedSnapshotIds: [serverResults.snapshotList]]
		if(serverResults.success == true && masterItem) {
			CloudRegion region = morpheusContext.async.cloud.region.find(new DataQuery().withFilters([
					new DataFilter('regionCode', regionCode),
					new DataFilter('cloud', cloud)
			])).blockingGet()
			server.status = 'running'
			List updateList = [[masterItem: masterItem, existingItem: server]]
			new VirtualMachineSync(this.plugin, cloud).updateMatchedVirtualMachines(updateList, region, serverResults.volumeList, usageLists, 'full')
		}
	}

	def setComputeServerNetwork(server, privateIp, publicIp = null, hostname = null, networkPoolId = null) {
		def network
		if(privateIp) {
			privateIp = privateIp?.toString().contains("\n") ? privateIp.toString().replace("\n", "") : privateIp.toString()
			def newInterface = false
			server.internalIp = privateIp
			server.sshHost = privateIp
			network = server.interfaces?.find{it.ipAddress == privateIp}
			if(network == null) {
				network = server.interfaces?.find{ it.primaryInterface && it.ipAddress == null && it.publicIpAddress == null}
				if(network) {
					network.ipAddress = privateIp
				}
			}
			if(network == null) {
				def interfaceName = server.sourceImage?.interfaceName ?: 'eth0'
				network = new ComputeServerInterface(name:interfaceName, ipAddress:privateIp, primaryInterface:true,
						displayOrder:(server.interfaces?.size() ?: 0) + 1)
				newInterface = true
			}
			if(publicIp) {
				publicIp = publicIp?.toString().contains("\n") ? publicIp.toString().replace("\n", "") : publicIp.toString()
				network.publicIpAddress = publicIp
				server.externalIp = publicIp
			}
			if(networkPoolId) {
				network.pool = morpheusContext.async.network.pool.listById([networkPoolId.toLong()]).blockingFirst()
			}
			if(hostname) {
				server.hostname = hostname
			}
			if(newInterface == true) {
				morpheusContext.async.network.create(network).blockingGet()
				server.addToInterfaces(network)
			} else {
				morpheusContext.async.network.save(network).blockingGet()
			}
		}
		return network
	}
}