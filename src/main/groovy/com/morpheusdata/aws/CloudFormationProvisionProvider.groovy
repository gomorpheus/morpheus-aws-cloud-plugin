package com.morpheusdata.aws

import com.bertramlabs.plugins.karman.CloudFile
import com.morpheusdata.aws.utils.AmazonComputeUtility
import com.morpheusdata.aws.utils.CloudFormationResourceMappingUtility
import com.morpheusdata.core.AbstractProvisionProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.providers.CloudNativeProvisionProvider
import com.morpheusdata.core.util.ComputeUtility
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeCapacityInfo
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerInterface
import com.morpheusdata.model.ComputeServerInterfaceType
import com.morpheusdata.model.ComputeTypeLayout
import com.morpheusdata.model.Datastore
import com.morpheusdata.model.HostType
import com.morpheusdata.model.Icon
import com.morpheusdata.model.Instance
import com.morpheusdata.model.Network
import com.morpheusdata.model.NetworkProxy
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.PlatformType
import com.morpheusdata.model.ProcessEvent
import com.morpheusdata.model.ProxyConfiguration
import com.morpheusdata.model.ResourceSpec
import com.morpheusdata.model.ResourceSpecTemplate
import com.morpheusdata.model.ServicePlan
import com.morpheusdata.model.StorageVolume
import com.morpheusdata.model.StorageVolumeType
import com.morpheusdata.model.VirtualImage
import com.morpheusdata.model.VirtualImageLocation
import com.morpheusdata.model.Workload
import com.morpheusdata.model.WorkloadState
import com.morpheusdata.model.provisioning.InstanceRequest
import com.morpheusdata.model.provisioning.WorkloadRequest
import com.morpheusdata.request.ResizeRequest
import com.morpheusdata.request.UpdateModel
import com.morpheusdata.response.InstanceResponse
import com.morpheusdata.response.PrepareInstanceResponse
import com.morpheusdata.response.ServiceResponse
import com.morpheusdata.response.WorkloadResponse
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j
import org.apache.http.client.utils.URIBuilder

@Slf4j
class CloudFormationProvisionProvider extends AbstractProvisionProvider implements CloudNativeProvisionProvider {
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
	 * Determines if this provision type creates a {@link ComputeServer} for each instance
	 * @return Boolean representation of whether or not this provision type creates a {@link ComputeServer} for each instance
	 */
	@Override
	Boolean createServer() {
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
		println "\u001B[33mAC Log - CloudFormationProvisionProvider:validateInstance- ${instance} ${opts}\u001B[0m"
		return null
	}


	@Override
	ServiceResponse<PrepareInstanceResponse> prepareInstance(Instance instance, InstanceRequest instanceRequest, Map opts) {
		println "\u001B[33mAC Log - CloudFormationProvisionProvider:prepareInstance- ${instance} ${instanceRequest} ${opts}\u001B[0m"
		PrepareInstanceResponse prepareInstanceResponse = new PrepareInstanceResponse()
		def rtn = [success:false, data:[resources:[]]]

		try {
			//cloud formation instance
			def performingUpdate = (opts.performingUpdate == true)
			def instanceConfig = instance.getConfigMap()
			log.debug("instanceConfig: {}", instanceConfig)
			//template params
			def templateParams = instanceConfig.templateParameter
			def layoutSpecs = opts.layoutSpecs
			def scriptConfig = opts.scriptConfig

			def allSuccess = true
			layoutSpecs.each { spec ->
				def specTemplate = spec.specTemplate
				def specFileContent = spec.specContent
				ResourceSpec resourceSpec = instance.specs.find { it.template == specTemplate }

				//get the content for the spec template
				def specContent = performingUpdate && resourceSpec.isolated ? resourceSpec.templateContent : specFileContent
				//parse it

				println "\u001B[33mAC Log - CloudFormationProvisionProvider:prepareInstance specTemplate - ${specTemplate}\u001B[0m"
				def parseResults = CloudFormationResourceMappingUtility.parseResource(instance, specContent, scriptConfig as Map, opts, morpheusContext)
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
					println "\u001B[33mAC Log - CloudFormationProvisionProvider:prepareInstance- ${workloadState}\u001B[0m"
					if (!workloadState) {
						buildWorkloadStateForInstance(instance, resourceSpec, specContent)
					}

					def resourceList = []
					//grab the resources and map them
					parseResults.data.resources?.each { resource ->
						//[type:typeMatch.type, apiType:typeMatch.apiType, enabled:typeMatch.enabled, spec:processedSpec,
						//  specMap:resourceConfig, morpheusType:typeMatch.morpheusType, name:key]
						//map it
						def mapResults = CloudFormationResourceMappingUtility.mapResource(instance, resource, opts)
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
							def resourceResults = CloudFormationResourceMappingUtility.createResource(instance, row, opts)
							log.debug("resourceResults: {}", resourceResults)
							if (resourceResults.success == true && resourceResults.data) {
								def addRow = [resource: resourceResults.data.output, results: resourceResults.data]
								rtn.data.resources << addRow
								log.debug("new resource row: {}", addRow)
								//append them to the instance
								if (addRow.results.type == 'accountResource') {
									//add it to the instance
									instance.addToResources(addRow.resource)
									log.info("added resource to instance: {}", addRow.resource)
								}
								//see if there is an additional morpheus resource
								def morpheusResource = resourceResults.data.morpheusResource
								log.info("morpheus resource: {}", morpheusResource)
								if (morpheusResource) {
									if (morpheusResource.success == true && morpheusResource.data) {
										def morpheusRow = [resource: morpheusResource.data.output, results: morpheusResource.data]
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
			rtn.success = allSuccess
			//done
		} catch(e) {
			log.error("error preparing instance: ${e}", e)
			return new ServiceResponse(success: false, msg: e.message, error: e.message, data: prepareInstanceResponse)
		}

		return new ServiceResponse(success: rtn.success, msg: rtn.msg, data: prepareInstanceResponse)
	}

	@Override
	ServiceResponse<InstanceResponse> runInstance(Instance instance, InstanceRequest instanceRequest, Map opts) {
		println "\u001B[33mAC Log - CloudFormationProvisionProvider:runInstance- ${instance} ${instanceRequest} ${opts}\u001B[0m"
		return ServiceResponse.error()
	}


	@Override
	ServiceResponse destroyInstance(Instance instance, Map opts) {
		println "\u001B[33mAC Log - CloudFormationProvisionProvider:destroyInstance- ${instance} ${opts}\u001B[0m"
		def rtn = [success:true, removeServer:true]
		try {
			def cloud = instance.provisionZoneId ? morpheusContext.async.cloud.getCloudById(instance.provisionZoneId).blockingGet() : null
			if(cloud) {
				//delete the stack
				if(instance.specs?.size() > 0) {
					instance.specs?.each { spec ->
						def stackName = spec.resourceName
						if(stackName) {
							//def workloadState = WorkloadState.where { refType == 'Instance' && refId == instance.id && subRefType == 'ResourceSpec' && subRefId == spec.id }.get()
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

	@Override
	ServiceResponse<InstanceResponse> updateInstance(Instance instance, InstanceRequest instanceRequest, Map map) {
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
		'amazon-cloud-formation-provision-provider'
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


	private Instance saveAndGet(Instance instance) {
		def saveSuccessful = morpheusContext.async.instance.save(instance).blockingGet()
		if(!saveSuccessful) {
			log.warn("Error saving instance: ${instance?.id}" )
		}
		return morpheusContext.async.instance.get(instance.id).blockingGet()
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
		println "\u001B[33mAC Log - CloudFormationProvisionProvider:buildWorkloadStateForInstance- ${workloadState.dump()}\u001B[0m"
		return workloadState
	}


}