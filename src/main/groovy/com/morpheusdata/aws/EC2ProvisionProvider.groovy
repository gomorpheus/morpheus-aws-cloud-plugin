package com.morpheusdata.aws

import com.amazonaws.services.ec2.AmazonEC2
import com.bertramlabs.plugins.karman.CloudFile
import com.morpheusdata.aws.utils.AmazonComputeUtility
import com.morpheusdata.core.AbstractProvisionProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.util.ComputeUtility
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeCapacityInfo
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerInterface
import com.morpheusdata.model.ComputeServerInterfaceType
import com.morpheusdata.model.ComputeTypeLayout
import com.morpheusdata.model.ComputeZonePool
import com.morpheusdata.model.ComputeZoneRegion
import com.morpheusdata.model.Datastore
import com.morpheusdata.model.HostType
import com.morpheusdata.model.Icon
import com.morpheusdata.model.Instance
import com.morpheusdata.model.Network
import com.morpheusdata.model.NetworkProxy
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.OsType
import com.morpheusdata.model.PlatformType
import com.morpheusdata.model.ProcessEvent
import com.morpheusdata.model.ProxyConfiguration
import com.morpheusdata.model.ServicePlan
import com.morpheusdata.model.StorageVolume
import com.morpheusdata.model.StorageVolumeType
import com.morpheusdata.model.VirtualImage
import com.morpheusdata.model.VirtualImageLocation
import com.morpheusdata.model.Workload
import com.morpheusdata.model.provisioning.WorkloadRequest
import com.morpheusdata.request.ResizeRequest
import com.morpheusdata.request.UpdateModel
import com.morpheusdata.response.PrepareWorkloadResponse
import com.morpheusdata.response.ServiceResponse
import com.morpheusdata.response.WorkloadResponse
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j
import org.apache.http.client.utils.URIBuilder

@Slf4j
class EC2ProvisionProvider extends AbstractProvisionProvider {
	AWSPlugin plugin
	MorpheusContext morpheusContext

	EC2ProvisionProvider(AWSPlugin plugin, MorpheusContext morpheusContext) {
		this.plugin = plugin
		this.morpheusContext = morpheusContext
	}

	/**
	 * Provides a Collection of OptionType inputs that need to be made available to various provisioning Wizards
	 * @return Collection of OptionTypes
	 */
	@Override
	Collection<OptionType> getOptionTypes() {
		new ArrayList<OptionType>()
	}

	/**
	 * Provides a Collection of OptionType inputs for configuring node types
	 * @since 0.9.0
	 * @return Collection of OptionTypes
	 */
	@Override
	Collection<OptionType> getNodeOptionTypes() {
		new ArrayList<OptionType>()
	}

	/**
	 * Provides a Collection of ${@link ServicePlan} related to this ProvisioningProvider
	 * @return Collection of ServicePlan
	 */
	@Override
	Collection<ServicePlan> getServicePlans() {
		new ArrayList<ServicePlan>()
	}

	/**
	 * Provides a Collection of {@link ComputeServerInterfaceType} related to this ProvisioningProvider
	 * @return Collection of ComputeServerInterfaceType
	 */
	@Override
	Collection<ComputeServerInterfaceType> getComputeServerInterfaceTypes() {
		new ArrayList<ComputeServerInterfaceType>()
	}

	/**
	 * Determines if this provision type has datastores that can be selected or not.
	 * @return Boolean representation of whether or not this provision type has datastores
	 */
	@Override
	Boolean hasDatastores() {
		false
	}

	/**
	 * Determines if this provision type has networks that can be selected or not.
	 * @return Boolean representation of whether or not this provision type has networks
	 */
	@Override
	Boolean hasNetworks() {
		true
	}

	/**
	 * Determines if this provision type supports service plans that expose the tag match property.
	 * @return Boolean representation of whether or not service plans expose the tag match property.
	 */
	@Override
	Boolean hasPlanTagMatch() {
		false
	}

	/**
	 * Returns the maximum number of network interfaces that can be chosen when provisioning with this type
	 * @return maximum number of networks or 0,null if unlimited.
	 */
	@Override
	Integer getMaxNetworks() {
		null
	}

	/**
	 * Validates the provided provisioning options of a workload. A return of success = false will halt the
	 * creation and display errors
	 * @param opts options
	 * @return Response from API. Errors should be returned in the errors Map with the key being the field name and the error
	 * message as the value.
	 */
	@Override
	ServiceResponse validateWorkload(Map opts) {
		return null
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
		log.debug "validateInstance: ${instance} ${opts}"
		ServiceResponse.success()
	}

	/**
	 * Validate the provided provisioning options for a Docker host server.  A return of success = false will halt the
	 * creation and display errors
	 * @param server the ComputeServer to validate
	 * @param opts options
	 * @return Response from API
	 */
	@Override
	ServiceResponse validateDockerHost(ComputeServer server, Map opts) {
		log.debug "validateDockerHost: ${server} ${opts}"
		ServiceResponse.success()
	}

	/**
	 * This method is called before runWorkload and provides an opportunity to perform action or obtain configuration
	 * that will be needed in runWorkload. At the end of this method, if deploying a ComputeServer with a VirtualImage,
	 * the sourceImage on ComputeServer should be determined and saved.
	 * @param workload the Workload object we intend to provision along with some of the associated data needed to determine
	 *                 how best to provision the workload
	 * @param workloadRequest the RunWorkloadRequest object containing the various configurations that may be needed
	 *                        in running the Workload. This will be passed along into runWorkload
	 * @param opts additional configuration options that may have been passed during provisioning
	 * @return Response from API
	 */
	default ServiceResponse<PrepareWorkloadResponse> prepareWorkload(Workload workload, WorkloadRequest workloadRequest, Map opts) {
		ServiceResponse<PrepareWorkloadResponse> resp = new ServiceResponse<>()
		resp.data = new PrepareWorkloadResponse(workload: workload)

		ComputeServer server = workload.server
		//lets compute the resource pool we need to attach to the server and ComputeZoneRegion
		def vpcId = getResourceGroupId(workload.server.cloud.getConfigMap(), workload.getConfigMap())
		if(!vpcId && server.resourcePool) {
			vpcId = server.resourcePool.externalId
		}
		if(vpcId instanceof Map) {
			vpcId = vpcId.id
		} //handling legacy formats
		ComputeZonePool zonePool = morpheus.cloud.pool.listByCloudAndExternalIdIn(server.cloud.id,vpcId).blockingFirst()

		server.resourcePool = zonePool
		if (server.resourcePool.regionCode) {
			ComputeZoneRegion region = morpheus.cloud.region.findByCloudAndRegionCode(server.cloud.id,server.resourcePool.regionCode).blockingGet().get()
			server.volumes?.each { vol ->
				vol.regionCode = server.resourcePool.regionCode
			}
		}
		//build config
		AmazonEC2 amazonClient = AmazonComputeUtility.getAmazonClient(workload.server.cloud,false,workload.server.resourcePool.regionCode)
		//lets figure out what image we are deploying
		def imageType = workload.getConfigMap().imageType ?: 'default' //amazon generic instance type has a radio button for this
		def virtualImage = getWorkloadImage(amazonClient,server.resourcePool.regionCode,workload, opts)
		if(virtualImage) {
			if(virtualImage.imageType != 'ami' || imageType == 'local')
			{
				//we have to upload TODO: upload OVF Import from old importImage Method
			} else {
				//this ensures the image is set correctly for provisioning as it enters runWorkload
				workload.server.sourceImage = virtualImage
				VirtualImageLocation location = ensureVirtualImageLocation(amazonClient,server.resourcePool.regionCode,virtualImage,server.cloud)
				resp.data.setVirtualImageLocation(location)
			}
			return resp
		} else {
			return ServiceResponse.error("Virtual Image not found")
		}

	}




	/**
	 * This method is a key entry point in provisioning a workload. This could be a vm, a container, or something else.
	 * Information associated with the passed Workload object is used to kick off the workload provision request
	 * @param workload the Workload object we intend to provision along with some of the associated data needed to determine
	 *                 how best to provision the workload
	 * @param workloadRequest the RunWorkloadRequest object containing the various configurations that may be needed
	 *                        in running the Workload
	 * @param opts additional configuration options that may have been passed during provisioning
	 * @return Response from API
	 */
	@Override
	ServiceResponse<WorkloadResponse> runWorkload(Workload workload, WorkloadRequest workloadRequest, Map opts) {
		return null
	}

	/**
	 * Issues the remote calls necessary top stop a workload element from running.
	 * @param workload the Workload we want to shut down
	 * @return Response from API
	 */
	@Override
	ServiceResponse stopWorkload(Workload workload) {
		log.debug "stopWorkload: ${workload}"
		stopServer(workload.server)
	}

	/**
	 * Issues the remote calls necessary to start a workload element for running.
	 * @param workload the Workload we want to start up.
	 * @return Response from API
	 */
	@Override
	ServiceResponse startWorkload(Workload workload) {
		log.debug("stopWorkload: ${server}")
		startServer(workload.server)
	}

	/**
	 * Stop the server
	 * @param computeServer to stop
	 * @return Response from API
	 */
	@Override
	ServiceResponse stopServer(ComputeServer server) {
		log.debug("stopServer: ${server}")
		if(server?.externalId && (server.managed == true || server.computeServerType?.controlPower)) {
			def client = AmazonComputeUtility.getAmazonClient(server.cloud, false, server.resourcePool.regionCode)
			def stopResult = AmazonComputeUtility.stopServer([amazonClient: client, server: server])

			if (stopResult.success) {
				return ServiceResponse.success()
			} else {
				return ServiceResponse.error('Failed to stop vm')
			}
		} else {
			log.info("stopServer - ignoring request for unmanaged instance")
		}
		ServiceResponse.success()
	}

	/**
	 * Start the server
	 * @param server to start
	 * @return Response from API
	 */
	@Override
	ServiceResponse startServer(ComputeServer server) {
		log.debug("startServer: ${server}")
		if(server?.externalId && (server.managed == true || server.computeServerType?.controlPower)) {
			def client = AmazonComputeUtility.getAmazonClient(server.cloud, false, server.resourcePool.regionCode)
			def startResult = AmazonComputeUtility.startServer([amazonClient: client, server: server])

			if (startResult.success) {
				return ServiceResponse.success()
			} else {
				return ServiceResponse.error('Failed to start vm')
			}
		} else {
			log.info("startServer - ignoring request for unmanaged instance")
		}
		ServiceResponse.success()
	}

	/**
	 * Delete the server
	 * @param server to start
	 * @return Response from API
	 */
	ServiceResponse deleteServer(ComputeServer server) {
		log.debug("deleteServer: ${server}")
		if(server?.externalId && (server.managed == true || server.computeServerType?.controlPower)) {
			def client = AmazonComputeUtility.getAmazonClient(server.cloud, false, server.resourcePool.regionCode)
			def deleteResult = AmazonComputeUtility.deleteServer([amazonClient: client, server: server])

			if (deleteResult.success) {
				return ServiceResponse.success()
			} else {
				return ServiceResponse.error('Failed to remove vm')
			}
		} else {
			log.info("deleteServer - ignoring request for unmanaged instance")
		}
		ServiceResponse.success()
	}

	/**
	 * Issues the remote calls to restart a workload element. In some cases this is just a simple alias call to do a stop/start,
	 * however, in some cases cloud providers provide a direct restart call which may be preferred for speed.
	 * @param workload the Workload we want to restart.
	 * @return Response from API
	 */
	@Override
	ServiceResponse restartWorkload(Workload workload) {
		log.debug 'restartWorkload'
		ServiceResponse stopResult = stopWorkload(workload)
		stopResult.success ? startWorkload(workload) : stopResult
	}

	/**
	 * This is the key method called to destroy / remove a workload. This should make the remote calls necessary to remove any assets
	 * associated with the workload.
	 * @param workload to remove
	 * @param opts map of options
	 * @return Response from API
	 */
	@Override
	ServiceResponse removeWorkload(Workload workload, Map opts) {
		log.debug "removeWorkload: ${workload} ${opts}"
		deleteServer(workload.server)
	}

	/**
	 * Method called after a successful call to runWorkload to obtain the details of the ComputeServer. Implementations
	 * should not return until the server is successfully created in the underlying cloud or the server fails to
	 * create.
	 * @param server to check status
	 * @return Response from API. The publicIp and privateIp set on the WorkloadResponse will be utilized to update the ComputeServer
	 */
	@Override
	ServiceResponse<WorkloadResponse> getServerDetails(ComputeServer server) {
		WorkloadResponse rtn = new WorkloadResponse()
		def serverUuid = server.externalId
		if(server && server.uuid && server.resourcePool?.externalId) {
			def amazonClient = AmazonComputeUtility.getAmazonClient(server.cloud,false, server.resourcePool.regionCode)
			Map serverDetails = AmazonComputeUtility.checkServerReady([amazonClient:amazonClient, server:server])
			if(serverDetails.success && serverDetails.server) {
				rtn.externalId = serverUuid
				rtn.success = serverDetails.success
				rtn.publicIp = serverDetails.publicIpAddress
				rtn.privateIp = serverDetails.privateIpAddress
				rtn.hostname = serverDetails.tags?.find { it.key == 'Name' }?.value ?: serverDetails.instanceId
				return ServiceResponse.success(rtn)
				return ServiceResponse.success(rtn)
			} else {
				return ServiceResponse.error("Server not ready/does not exist")
			}
		} else {
			return ServiceResponse.error("Could not find server uuid")
		}
	}

	/**
	 * Request to scale the size of the Workload. Most likely, the implementation will follow that of resizeServer
	 * as the Workload usually references a ComputeServer. It is up to implementations to create the volumes, set the memory, etc
	 * on the underlying ComputeServer in the cloud environment. In addition, implementations of this method should
	 * add, remove, and update the StorageVolumes, StorageControllers, ComputeServerInterface in the cloud environment with the requested attributes
	 * and then save these attributes on the models in Morpheus. This requires adding, removing, and saving the various
	 * models to the ComputeServer using the appropriate contexts. The ServicePlan, memory, cores, coresPerSocket, maxStorage values
	 * defined on ResizeRequest will be set on the Workload and ComputeServer upon return of a successful ServiceResponse
	 * @param instance to resize
	 * @param workload to resize
	 * @param resizeRequest the resize requested parameters
	 * @param opts additional options
	 * @return Response from API
	 */
	@Override
	ServiceResponse resizeWorkload(Instance instance, Workload workload, ResizeRequest resizeRequest, Map opts) {
		return null
	}

	/**
	 * Request to scale the size of the ComputeServer. It is up to implementations to create the volumes, set the memory, etc
	 * on the underlying ComputeServer in the cloud environment. In addition, implementations of this method should
	 * add, remove, and update the StorageVolumes, StorageControllers, ComputeServerInterface in the cloud environment with the requested attributes
	 * and then save these attributes on the models in Morpheus. This requires adding, removing, and saving the various
	 * models to the ComputeServer using the appropriate contexts. The ServicePlan, memory, cores, coresPerSocket, maxStorage values
	 * defined on ResizeRequest will be set on the ComputeServer upon return of a successful ServiceResponse
	 * @param server to resize
	 * @param resizeRequest the resize requested parameters
	 * @param opts additional options
	 * @return Response from the API
	 */
	@Override
	ServiceResponse resizeServer(ComputeServer server, ResizeRequest resizeRequest, Map opts) {
		return null
	}

	/**
	 * Method called before runWorkload to allow implementers to create resources required before runWorkload is called
	 * @param workload that will be provisioned
	 * @param opts additional options
	 * @return Response from API
	 */
	@Override
	ServiceResponse createWorkloadResources(Workload workload, Map opts) {
		ServiceResponse.success()
	}

	/**
	 * Returns the host type that is to be provisioned
	 * @return HostType
	 */
	@Override
	HostType getHostType() {
		HostType.vm
	}

	/**
	 * Provides a Collection of {@link VirtualImage} related to this ProvisioningProvider. This provides a way to specify
	 * known VirtualImages in the Cloud environment prior to a typical 'refresh' on a Cloud. These are often used in
	 * predefined layouts. For example, when building up ComputeTypeLayouts
	 * @return Collection of {@link VirtualImage}
	 */
	@Override
	Collection<VirtualImage> getVirtualImages() {
		new ArrayList<VirtualImage>()
	}

	/**
	 * Provides a Collection of {@link ComputeTypeLayout} related to this ProvisioningProvider. These define the types
	 * of clusters that are exposed for this ProvisioningProvider. ComputeTypeLayouts have a collection of ComputeTypeSets,
	 * which reference a ContainerType. When returning this structure from implementations, it is often helpful to start
	 * with the ComputeTypeLayoutFactory to construct the default structure and modify fields as needed.
	 * @return Collection of ComputeTypeLayout
	 */
	@Override
	Collection<ComputeTypeLayout> getComputeTypeLayouts() {
		new ArrayList<ComputeTypeLayout>()
	}

	/**
	 * Returns the Morpheus Context for interacting with data stored in the Main Morpheus Application
	 *
	 * @return an implementation of the MorpheusContext for running Future based rxJava queries
	 */
	@Override
	MorpheusContext getMorpheus() {
		morpheusContext
	}

	/**
	 * A unique shortcode used for referencing the provided provider. Make sure this is going to be unique as any data
	 * that is seeded or generated related to this provider will reference it by this code.
	 * @return short code string that should be unique across all other plugin implementations.
	 */
	@Override
	String getCode() {
		'amazon-ec2-provision-provider'
	}

	/**
	 * Provides the provider name for reference when adding to the Morpheus Orchestrator
	 * NOTE: This may be useful to set as an i18n key for UI reference and localization support.
	 *
	 * @return either an English name of a Provider or an i18n based key that can be scanned for in a properties file.
	 */
	@Override
	String getName() {
		'Amazon EC2'
	}

	protected getWorkloadImage(AmazonEC2 amazonClient, String regionCode, Workload workload, Map opts = [:]) {
		VirtualImage rtn
		def containerConfig = workload.getConfigMap()
		def imageType = containerConfig.imageType ?: 'default'
		if(imageType == 'private' && containerConfig.imageId) {
			rtn = morpheusContext.virtualImage.get(containerConfig.imageId as Long).blockingGet()
		} else if(imageType == 'local' && (containerConfig.localImageId || containerConfig.template)) {
			Long localImageId = getImageId(containerConfig.localImageId) ?: getImageId(containerConfig.template)
			if(localImageId) {
				rtn = morpheusContext.virtualImage.get(localImageId).blockingGet()
			}
		} else if(imageType == 'public' && containerConfig.publicImageId) {

			def saveResults = saveAccountImage(amazonClient, workload.account, workload.server.cloud, regionCode, containerConfig.publicImageId)
			rtn = saveResults.image
		} else if(workload.workloadType.virtualImage) {
			rtn = workload.workloadType.virtualImage
		}
		return rtn
	}

	protected saveAccountImage(AmazonEC2 amazonClient, account, zone,String regionCode, publicImageId, sourceImage = null) {
		def rtn = [success:false]
		try {
			if(publicImageId?.startsWith('!')) {
				return rtn
			}
			VirtualImageLocation existing = morpheus.virtualImage.location.findVirtualImageLocationByExternalIdForCloudAndType(publicImageId,zone.id,regionCode,'ami',account.id).blockingGet()

			log.info("saveAccountImage existing: ${existing}")
			if(existing) {
				if(!existing.externalDiskId) {
					def imageResults = AmazonComputeUtility.loadImage([amazonClient:amazonClient, imageId:publicImageId])
					if(imageResults.success == true && imageResults.image)	{
						existing.externalDiskId = imageResults.image.blockDeviceMappings.find{ mapping -> mapping.deviceName == imageResults.image.rootDeviceName}?.ebs?.snapshotId
						morpheus.virtualImage.location.save([existing]).blockingGet()
						rtn.awsImage = imageResults.image
					}
				}
				rtn.image = existing.virtualImage
				rtn.imageLocation = existing
				rtn.imageId = rtn.image.id
				rtn.success = true
			} else {
				//find by image location
				def imageResults = AmazonComputeUtility.loadImage([amazonClient:amazonClient, imageId:publicImageId])
				//make sure its there
				if(imageResults.success == true && imageResults.image)	{
					def blockDeviceMap = imageResults.image.getBlockDeviceMappings()
					def blockDeviceConfig = []
					blockDeviceMap.each { blockDevice ->
						blockDeviceConfig << [deviceName:blockDevice.getDeviceName(), ebs:blockDevice.getEbs(), noDevice:blockDevice.getNoDevice(),
											  virtualName:blockDevice.getVirtualName()]
					}
					def tagsConfig = imageResults.image.getTags()?.collect{[key:it.getKey(), value:it.getValue()]} ?: []
					def productCodeConfig = imageResults.image.getProductCodes()?.collect{[id:it.getProductCodeId(), type:it.getProductCodeType()]} ?: []
					def imageConfig = [category:"amazon.ec2.image.${zone.id}", name:imageResults.image.getName(), installAgent:true,
									   code:"amazon.ec2.image.${zone.id}.${imageResults.image.getImageId()}", imageType:'ami', externalType:imageResults.image.getImageType(),
									   kernelId:imageResults.image.getKernelId(), architecture:imageResults.image.getArchitecture(),
									   description:imageResults.image.getDescription(), minDisk:10, minRam:512 * ComputeUtility.ONE_MEGABYTE, remotePath:imageResults.image.getImageLocation(),
									   hypervisor:imageResults.image.getHypervisor(), platform:(imageResults.image.getPlatform() == 'windows' ? 'windows' : 'linux'),
									   productCode:'', externalId:imageResults.image.getImageId(), ramdiskId:imageResults.image.getRamdiskId(), isCloudInit: (imageResults.image.getPlatform() == 'windows' && imageResults.image.getImageOwnerAlias() != 'amazon' ? false : true),
									   rootDeviceName:imageResults.image.getRootDeviceName(), rootDeviceType:imageResults.image.getRootDeviceType(),
									   enhancedNetwork:imageResults.image.getSriovNetSupport(), status:imageResults.image.getState(),
									   statusReason:imageResults.image.getStateReason(), virtualizationType:imageResults.image.getVirtualizationType(),
									   isPublic:imageResults.image.isPublic(), refType:'ComputeZone', refId:"${zone.id}", owner:account, userDefined:true,
									   sshUsername: sourceImage?.sshUsername, sshPassword: sourceImage?.sshPassword,
									   externalDiskId:imageResults.image.blockDeviceMappings.find{ mapping -> mapping.deviceName == imageResults.image.rootDeviceName}?.ebs?.snapshotId
					]
					log.info("Saving new image")
					def add = new VirtualImage(imageConfig)
					if(add.platform == 'windows')
						add.osType = new OsType(code:'windows')
					else
						add.osType = new OsType(code:'linux')

//					add.addToAccounts(account)
//					add.tags = tagsConfig.encodeAsJSON().toString()
//					add.blockDeviceConfig = blockDeviceConfig.encodeAsJSON().toString()
//					add.productCode = productCodeConfig.encodeAsJSON().toString()
					def locationConfig = [code:"amazon.ec2.image.${zone.id}.${imageResults.image.getImageId()}", externalId:imageResults.image.getImageId(),
										  externalDiskId:imageResults.image.blockDeviceMappings.find{ mapping -> mapping.deviceName == imageResults.image.rootDeviceName}?.ebs?.snapshotId,
										  refType:'ComputeZone', refId:zone.id, imageName:imageResults.image.getName(), imageRegion:getAmazonRegion(zone)]
					def addLocation = new VirtualImageLocation(locationConfig)
					add.imageLocations = [addLocation]
					VirtualImage imageResult = morpheus.virtualImage.create(add).blockingGet()
					rtn.awsImage = imageResults.image
					rtn.image = imageResult
					rtn.imageId = imageResult.id
					log.info("saveAccountImage result: ${add.code} - ${add.errors}")

					rtn.success = true
				}
			}
		} catch(e) {
			log.error("saveAccountImage error: ${e}", e)
		}
		return rtn
	}

	protected VirtualImageLocation ensureVirtualImageLocation(AmazonEC2 amazonClient, String region, VirtualImage virtualImage, Cloud cloud) {

		def rtn = virtualImage.locations?.find{it.refType == 'ComputeZone' && it.refId == cloud.id && it.imageRegion == region}
		if(!rtn) {
			rtn = virtualImage.locations?.find{it.refType == 'ComputeZone' && it.refId == cloud.id}
		}
		if(!rtn) {
			rtn = virtualImage.locations?.find{it.imageRegion == region}
		}
		if(!rtn) {
			if(virtualImage.isPublic) {
				//load image by name
				def publicImageResults = AmazonComputeUtility.loadImage([amazonClient:amazonClient, imageName:virtualImage.name, isPublic:true])
				if(publicImageResults.success && publicImageResults.image) {
					def diskId = publicImageResults.image.blockDeviceMappings.find{ mapping -> mapping.deviceName == publicImageResults.image.rootDeviceName}?.ebs?.snapshotId
					def newLocation = new VirtualImageLocation(virtualImage: virtualImage,imageName: virtualImage.name, externalId: publicImageResults.image.getImageId(), imageRegion: region, externalDiskId: diskId)
					newLocation = morpheus.virtualImage.location.create(newLocation).blockingGet()
					return newLocation
				}
			}
			if(!virtualImage.externalDiskId && virtualImage.externalId) {
				def imageResults = AmazonComputeUtility.loadImage([amazonClient:amazonClient, imageId:virtualImage.externalId])

			}
			rtn = new VirtualImageLocation([externalId:virtualImage.externalId, externalDiskId:virtualImage.externalDiskId])
		}
		return rtn
	}


	private Long getImageId(imageId) {
		Long rtn
		try {
			rtn = imageId?.toLong()
		} catch(e) {
			//nothing
		}
		return rtn
	}

	private static getResourceGroupId(zoneConfig, containerConfig) {
		def rtn = zoneConfig['vpc']
		if(!rtn)
			rtn = containerConfig['resourcePool']
		return rtn
	}
}