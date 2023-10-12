package com.morpheusdata.aws

import com.amazonaws.services.ec2.AmazonEC2
import com.amazonaws.services.ec2.AmazonEC2Client
import com.morpheusdata.PrepareHostResponse
import com.morpheusdata.aws.backup.AWSSnapshotBackupProvider
import com.morpheusdata.aws.utils.AmazonComputeUtility
import com.morpheusdata.core.AbstractProvisionProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.providers.HostProvisionProvider
import com.morpheusdata.core.providers.ProvisionProvider
import com.morpheusdata.core.providers.VmProvisionProvider
import com.morpheusdata.core.providers.WorkloadProvisionProvider
import com.morpheusdata.core.util.ComputeUtility
import com.morpheusdata.model.Account
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.CloudPool
import com.morpheusdata.model.CloudRegion
import com.morpheusdata.model.ComputeCapacityInfo
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerInterface
import com.morpheusdata.model.ComputeServerInterfaceType
import com.morpheusdata.model.ComputeTypeLayout
import com.morpheusdata.model.ComputeTypeSet
import com.morpheusdata.model.ContainerType
import com.morpheusdata.model.NetAddress
import com.morpheusdata.model.WorkloadType
import com.morpheusdata.model.HostType
import com.morpheusdata.model.ImageType
import com.morpheusdata.model.Instance
import com.morpheusdata.model.KeyPair
import com.morpheusdata.model.Network
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.OsType
import com.morpheusdata.model.ServicePlan
import com.morpheusdata.model.StorageVolume
import com.morpheusdata.model.StorageVolumeType
import com.morpheusdata.model.VirtualImage
import com.morpheusdata.model.VirtualImageLocation
import com.morpheusdata.model.Workload
import com.morpheusdata.model.provisioning.HostRequest
import com.morpheusdata.model.provisioning.NetworkConfiguration
import com.morpheusdata.model.provisioning.WorkloadRequest
import com.morpheusdata.request.ResizeRequest
import com.morpheusdata.response.PrepareWorkloadResponse
import com.morpheusdata.response.ServiceResponse
import com.morpheusdata.response.ProvisionResponse
import groovy.util.logging.Slf4j

@Slf4j
class EC2ProvisionProvider extends AbstractProvisionProvider implements VmProvisionProvider, WorkloadProvisionProvider.ResizeFacet, HostProvisionProvider.ResizeFacet, ProvisionProvider.BlockDeviceNameFacet {
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
		def options = []
		options << new OptionType([
				name : 'securityGroup',
				code : 'amazon-ec2-provision-security-group',
				category:'provisionType.amazon',
				fieldName : 'securityId',
				fieldContext : 'config',
				fieldLabel : 'Security Groups',
				required : false,
				inputType : OptionType.InputType.MULTI_SELECT,
				displayOrder : 100,
				optionSource: 'awsPluginEc2SecurityGroup'

		])
		options << new OptionType([
				name : 'publicIP',
				code : 'amazon-ec2-provision-public-id',
				category:'provisionType.amazon',
				fieldName : 'publicIpType',
				fieldContext : 'config',
				fieldLabel : 'Public IP',
				required : false,
				defaultValue: 'subnet',
				noBlank: true,
				inputType : OptionType.InputType.SELECT,
				displayOrder : 101,
				optionSource: 'awsPluginEc2PublicIpType'

		])

		options << new OptionType([
				name:'skip agent install',
		        code: 'provisionType.general.noAgent',
				category:'provisionType.amazon',
				fieldName: 'noAgent',
				fieldCode: 'gomorpheus.optiontype.SkipAgentInstall',
				fieldLabel: 'Skip Agent Install',
				fieldContext: 'config',
				fieldGroup: "Advanced Options",
				required: false,
				enabled: true,
				editable: false,
				global: false,
				displayOrder: 104,
				inputType: OptionType.InputType.CHECKBOX,
				helpBlock: 'Skipping Agent installation will result in a lack of logging and guest operating system statistics. Automation scripts may also be adversely affected.'
		])

		options << new OptionType(
			code:'provisionType.amazon.instanceProfile',
			inputType: OptionType.InputType.SELECT,
			name:'iam profile',
			category:'provisionType.amazon',
			fieldName:'instanceProfile',
			fieldCode: 'gomorpheus.optiontype.IamProfile',
			fieldLabel:'IAM Profile',
			fieldContext:'config',
			fieldGroup:'Advanced Options',
			required:false,
			enabled:true,
			optionSource:'amazonInstanceProfiles',
			editable:false,
			global:false,
			placeHolder:null,
			helpBlock:'',
			defaultValue:null,
			custom:false,
			displayOrder:106,
			fieldClass:null
		)

		options << new OptionType(
			code:'provisionType.amazon.kmsKeyId',
			inputType: OptionType.InputType.TEXT,
			name:'KMS Key ID',
			category:'provisionType.amazon',
			fieldName:'kmsKeyId',
			fieldCode: 'gomorpheus.optiontype.kmsKeyId',
			fieldLabel:'KMS Key ID',
			fieldContext:'config',
			fieldGroup:'Advanced Options',
			required:false,
			enabled:true,
			editable:false,
			global:false,
			placeHolder:null,
			helpBlock:'',
			defaultValue:null,
			custom:false,
			displayOrder:107,
			fieldClass:null
		)

		return options
	}

	/**
	 * Provides a Collection of OptionType inputs for configuring node types
	 * @since 0.9.0
	 * @return Collection of OptionTypes
	 */
	@Override
	Collection<OptionType> getNodeOptionTypes() {
		OptionType amiImage = new OptionType([
				name : 'image',
				code : 'amazon-ec2-node-image',
				fieldName : 'virtualImage.id',
				fieldContext : 'domain',
				fieldLabel : 'AMI Image',
				inputType : OptionType.InputType.TYPEAHEAD,
				displayOrder : 100,
				required : false,
				optionSource : 'amazonEc2NodeAmiImage'
		])
		OptionType logFolder = new OptionType([
				name : 'mountLogs',
				code : 'amazon-ec2-node-log-folder',
				fieldName : 'mountLogs',
				fieldContext : 'domain',
				fieldLabel : 'Log Folder',
				inputType : OptionType.InputType.TEXT,
				displayOrder : 101,
				required : false,
		])
		OptionType configFolder = new OptionType([
				name : 'mountConfig',
				code : 'amazon-ec2-node-config-folder',
				fieldName : 'mountConfig',
				fieldContext : 'domain',
				fieldLabel : 'Config Folder',
				inputType : OptionType.InputType.TEXT,
				displayOrder : 102,
				required : false,
		])
		OptionType deployFolder = new OptionType([
				name : 'mountData',
				code : 'amazon-ec2-node-deploy-folder',
				fieldName : 'mountData',
				fieldContext : 'domain',
				fieldLabel : 'Deploy Folder',
				inputType : OptionType.InputType.TEXT,
				displayOrder : 103,
				helpText: '(Optional) If using deployment services, this mount point will be replaced with the contents of said deployments.',
				required : false,
		])
		return [amiImage, logFolder, configFolder, deployFolder]
	}

	/**
	 * Provides a Collection of ${@link ServicePlan} related to this ProvisionProvider
	 * @return Collection of ServicePlan
	 */
	@Override
	Collection<ServicePlan> getServicePlans() {
		def servicePlans = []
		new ArrayList<ServicePlan>()
	}

	/**
	 * Provides a Collection of {@link ComputeServerInterfaceType} related to this ProvisionProvider
	 * @return Collection of ComputeServerInterfaceType
	 */
	@Override
	Collection<ComputeServerInterfaceType> getComputeServerInterfaceTypes() {
		new ArrayList<ComputeServerInterfaceType>()
	}

	/**
	 * Provides a Collection of {@link StorageVolumeType} related to this ProvisionProvider for the root volume
	 * @return Collection of StorageVolumeType
	 */
	@Override
	Collection<StorageVolumeType> getRootVolumeStorageTypes() {
		getStorageVolumeTypes().findAll { !['amazon-st1','amazon-sc1'].contains(it.code) }
	}

	/**
	 * Provides a Collection of {@link StorageVolumeType} related to this ProvisionProvider for adding data volumes
	 * @return Collection of StorageVolumeType
	 */
	@Override
	Collection<StorageVolumeType> getDataVolumeStorageTypes() {
		getStorageVolumeTypes()
	}

	//Helper method for provider storage types
	private getStorageVolumeTypes() {
		def volumeTypes = []

		volumeTypes << new StorageVolumeType([
			code:'amazon-gp2', displayName:'gp2', name:'gp2', 
			description:'AWS - gp2', volumeType:'volume', enabled:true, 
			customLabel:true, customSize:true, defaultType:true, autoDelete:true, 
			minStorage:(ComputeUtility.ONE_GIGABYTE), maxStorage:(16L * ComputeUtility.ONE_TERABYTE), 
			hasDatastore:false, allowSearch:true, volumeCategory:'volume',
			displayOrder: 0
		])

		volumeTypes << new StorageVolumeType([
			code:'amazon-gp3', displayName:'gp3', name:'gp3', 
			description:'AWS - gp3', volumeType:'volume', enabled:true, 
			customLabel:true, customSize:true, defaultType:true, autoDelete:true, 
			minStorage:(ComputeUtility.ONE_GIGABYTE), maxStorage:(16L * ComputeUtility.ONE_TERABYTE), 
			hasDatastore:false, allowSearch:true, volumeCategory:'volume',
			displayOrder: 1
		])

		volumeTypes << new StorageVolumeType([
			code:'amazon-io1', displayName:'io1', name:'io1', 
			description:'AWS - io1', volumeType:'volume', enabled:true, 
			customLabel:true, customSize:true, defaultType:false, 
			autoDelete:true, minStorage:(4L * ComputeUtility.ONE_GIGABYTE), maxStorage:(16L * ComputeUtility.ONE_TERABYTE), 
			configurableIOPS:true, minIOPS:100, maxIOPS:20000, hasDatastore:false, 
			allowSearch:true, volumeCategory:'volume', 
			displayOrder:2
		])

		volumeTypes << new StorageVolumeType([
			code:'amazon-st1', displayName:'st1', name:'st1', 
			description:'AWS - st1', volumeType:'volume', enabled:true, 
			customLabel:true, customSize:true, defaultType:false, autoDelete:true, 
			minStorage:(125L * ComputeUtility.ONE_GIGABYTE), maxStorage:(16L * ComputeUtility.ONE_TERABYTE), 
			hasDatastore:false, allowSearch:true, volumeCategory:'volume',
			displayOrder: 3
		])

		volumeTypes << new StorageVolumeType([
			code:'amazon-sc1', displayName:'sc1', name:'sc1', 
			description:'AWS - sc1', volumeType:'volume', enabled:true, 
			customLabel:true, customSize:true, defaultType:false, autoDelete:true, 
			minStorage:(125 * ComputeUtility.ONE_GIGABYTE), maxStorage:(16L * ComputeUtility.ONE_TERABYTE), 
			hasDatastore:false, allowSearch:true, volumeCategory:'volume',
			displayOrder: 4
		])

		volumeTypes << new StorageVolumeType([
			code:'amazon-standard', displayName:'standard', name:'standard', 
			description:'AWS - standard', volumeType:'volume', enabled:true, 
			customLabel:true, customSize:true, defaultType:false, autoDelete:true, 
			minStorage:(1L * ComputeUtility.ONE_GIGABYTE), maxStorage:(1L * ComputeUtility.ONE_TERABYTE),
			hasDatastore:false, allowSearch:true, volumeCategory:'volume',
			displayOrder: 5
		])

		volumeTypes
	}

	@Override
	Boolean lvmSupported() {
		return true
	}

	@Override
	String serverType() {
		return "ami"
	}

	@Override
	String getViewSet() {
		return "amazonCustom"
	}

	@Override
	Boolean multiTenant() {
		return false
	}

	@Override
	Boolean aclEnabled() {
		return false
	}

	@Override
	String getHostDiskMode() {
		return "lvm"
	}

	@Override
	Boolean hasSecurityGroups() {
		return true
	}

	@Override
	Boolean supportsAutoDatastore() {
		return false
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
		return 0
	}

	/**
	 * Determines if this provision type has resources pools that can be selected or not.
	 * @return Boolean representation of whether or not this provision type has resource pools
	 */
	@Override
	Boolean hasComputeZonePools() {
		true
	}

	/**
	 * Indicates if a ComputeZonePool is required during provisioning
	 * @return Boolean
	 */
	@Override
	Boolean computeZonePoolRequired() {
		return true
	}

	/**
	 * Determines if this provision type allows the rot volume to be renamed.
	 * @return Boolean representation of whether or not this provision type allows the rot volume to be renamed
	 */
	@Override
	Boolean canCustomizeRootVolume() {
		return true
	}

	/**
	 * Determines if this provision type allows the root volume to be resized.
	 * @return Boolean representation of whether or not this provision type allows the root volume to be resized
	 */
	@Override
	Boolean canResizeRootVolume() {
		return true
	}

	/**
	 * Indicates if volumes may be added during provisioning
	 * @return Boolean
	 */
	@Override
	Boolean canAddVolumes() {
		return true
	}

	/**
	 * Determines if this provision type allows the user to add data volumes.
	 * @return Boolean representation of whether or not this provision type allows the user to add data volumes
	 */
	@Override
	Boolean canCustomizeDataVolumes() {
		return true
	}

	/**
	 * Custom service plans can be created for this provider
	 * @return Boolean
	 */
	Boolean supportsCustomServicePlans() {
		return false;
	}


	/**
	 * For most provision types, a default instance type is created upon plugin registration.  Override this method if
	 * you do NOT want to create a default instance type for your provision provider
	 * @return defaults to true
	 */
	Boolean createDefaultInstanceType() {
		return false;
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
		log.debug("validateWorkload: ${opts}")
		ServiceResponse rtn = new ServiceResponse(true, null, [:], null)
		try {
			def cloud = morpheusContext.async.cloud.getCloudById(opts.cloud?.id ?: opts.zoneId).blockingGet()
			def validateTemplate = opts.template != null
			def validationResults = AmazonComputeUtility.validateServerConfig(morpheusContext, [amazonClient:plugin.getAmazonClient(cloud, false, opts.resourcePool?.regionCode), validateTemplate:validateTemplate] + opts)
			if(!validationResults.success) {
				validationResults.errors?.each { it ->
					rtn.addError(it.field, it.msg)
				}
			}

		} catch(e) {
			log.error("validateWorkload error: ${e}", e)
		}
		return rtn
	}


	/**
	 * Validate the provided provisioning options for a Docker host server.  A return of success = false will halt the
	 * creation and display errors
	 * @param server the ComputeServer to validate
	 * @param opts options
	 * @return Response from API
	 */
	@Override
	ServiceResponse validateHost(ComputeServer server, Map opts) {
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
	ServiceResponse<PrepareWorkloadResponse> prepareWorkload(Workload workload, WorkloadRequest workloadRequest, Map opts) {
		ServiceResponse<PrepareWorkloadResponse> resp = new ServiceResponse<>()
		resp.data = new PrepareWorkloadResponse(workload: workload, options: [sendIp: false])
		ComputeServer server = workload.server
		if (server.resourcePool.regionCode) {
			CloudRegion region = morpheus.cloud.region.findByCloudAndRegionCode(server.cloud.id,server.resourcePool.regionCode).blockingGet().get()
			server.volumes?.each { vol ->
				vol.regionCode = server.resourcePool.regionCode
			}
		}

		if(server.platform == "linux") {
			resp.data.disableCloudInit = false
			resp.data.disableAutoUpdates = true
		}

		//build config
		AmazonEC2 amazonClient = plugin.getAmazonClient(workload.server.cloud,false, workload.server.resourcePool.regionCode)
		//lets figure out what image we are deploying
		def imageType = workload.getConfigMap().imageType ?: 'default' //amazon generic instance type has a radio button for this
		def virtualImage = getWorkloadImage(amazonClient,server.resourcePool.regionCode,workload, opts)
		if(virtualImage) {
			if(virtualImage.imageType != ImageType.ami || imageType == 'local') {
				//we have to upload TODO: upload OVF Import from old importImage Method
			} else {
				//this ensures the image is set correctly for provisioning as it enters runWorkload
				workload.server.sourceImage = virtualImage
				VirtualImageLocation location = ensureVirtualImageLocation(amazonClient,server.resourcePool.regionCode,virtualImage,server.cloud)
				resp.data.setVirtualImageLocation(location)
			}
			resp.success = true
		} else {
			resp.success = false
			resp.msg = "Virtual Image not found"
		}

		// restore/clone from snapshot
		def backupSetId = opts.backupSetId
		def cloneContainerId = opts.cloneContainerId
		if(backupSetId && cloneContainerId) {
			Map rootSnapshot
			def snapshots = new AWSSnapshotBackupProvider(plugin, morpheus).getSnapshotsForBackupResult(backupSetId, cloneContainerId)
			log.debug("Snapshots: ${snapshots}")
			if(snapshots) {
				rootSnapshot = snapshots.find{it.diskType == "root"}
				log.debug("rootSnapshot: ${rootSnapshot}")
				if(rootSnapshot) {
					//this is a clone/restore so use the snapshot image
					assignSnapshotsToStorageVolumes(workload, snapshots)
				}
			}

			def snapshotOpts = [:]
			if(rootSnapshot) {
				log.info("Performing restore operation with : ${rootSnapshot.snapshotId}")
				//this is a clone/restore so register the image from the snapshot
				snapshotOpts = rootSnapshot.clone()
				snapshotOpts.amazonClient = amazonClient
				try {
					AmazonComputeUtility.waitForSnapshot([snapshotId: rootSnapshot.snapshotId, amazonClient: amazonClient])
				} catch (Exception ex) {
					log.error("Snapshot ${rootSnapshot.snapshotId} never completed", ex)
				}
				log.debug("rootSnapshot: ${rootSnapshot}")
				if(rootSnapshot.zoneId != null && server.cloud && rootSnapshot.usageAccountId != null && server.cloud.externalId != rootSnapshot.usageAccountId && server.cloud.regionCode == rootSnapshot.regionCode) {
					Cloud snapshotCloud = morpheus.services.cloud.get((Long) rootSnapshot.zoneId)
					if(snapshotCloud) {
						snapshotOpts.amazonClient = plugin.getAmazonClient(snapshotCloud,false, rootSnapshot.regionCode)
						snapshotOpts.shareUserId = server.cloud.externalId //accountID
					}
					//we can share this AMI in theory for access by the other account
				}

				def imageUploadResults = AmazonComputeUtility.insertSnapshotImage(snapshotOpts)
				log.debug("insertSnapshotImage results: ${imageUploadResults}")

				try {
					log.debug("imageUploadTask complete: ${imageUploadResults}")
					if(imageUploadResults.success == true && imageUploadResults.imageId) {
						//this is a clone/restore so use the snapshot image
						log.info("Creating server from snapshot ${rootSnapshot.snapshotId} image ${imageUploadResults.imageId}")
						opts.rootSnapshotId = rootSnapshot.snapshotId
						opts.snapshotImageRef = imageUploadResults.imageId
					} else {
						resp.success = false
						resp.msg = imageUploadResults.message
					}
				} catch(ie) {
					log.error("image acquire error: ${ie.message}",ie)
					resp.success = false
					resp.msg = 'failed to acquire additional virtual image information'
				}
			}
		}

		return resp
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
	ServiceResponse<ProvisionResponse> runWorkload(Workload workload, WorkloadRequest workloadRequest, Map opts) {
		log.debug "runWorkload: ${workload} ${workloadRequest} ${opts}"
		AmazonEC2 amazonClient
		ProvisionResponse provisionResponse = new ProvisionResponse(success: true)
		ComputeServer server = workload.server
		try {
			Cloud cloud = server.cloud
			VirtualImage virtualImage = server.sourceImage
			amazonClient = plugin.getAmazonClient(cloud,false, server.resourcePool.regionCode)
			def runConfig = buildWorkloadRunConfig(workload, workloadRequest, virtualImage, amazonClient, opts)
			runVirtualMachine(runConfig, provisionResponse, opts + [amazonClient: amazonClient])
			log.info("Checking Server Interfaces....")
			workload.server.interfaces?.each { netInt ->
				log.info("Net Interface: ${netInt.id} -> Network: ${netInt.network?.id}")
			}
			provisionResponse.noAgent = opts.noAgent ?: false
			return new ServiceResponse<ProvisionResponse>(success: true, data: provisionResponse)
		} catch (e) {
			log.error "runWorkload: ${e}", e
			provisionResponse.setError(e.message)
			return new ServiceResponse(success: false, msg: e.message, error: e.message, data: provisionResponse)
		}
	}

	@Override
	ServiceResponse finalizeWorkload(Workload workload) {
		log.debug "finalizeWorkload: ${workload?.id}"
		ComputeServer server = workload.server
		return finalizeServer(server)
	}

	@Override
	ServiceResponse<PrepareHostResponse> prepareHost(ComputeServer server, HostRequest hostRequest, Map opts) {
		log.debug "prepareHost: ${server} ${hostRequest} ${opts}"

		ServiceResponse<PrepareHostResponse> resp = new ServiceResponse<>()
		resp.data = new PrepareHostResponse(computeServer: server, disableCloudInit: false,disableAutoUpdates: true, options: [sendIp: false])

		try {
			def layout = server?.layout
			def typeSet = server.typeSet
			def config = server.getConfigMap()
			def imageType = config.templateTypeSelect ?: 'default'
			Cloud cloud = server.cloud
			def cloudPoolId = server.resourcePool?.id ?: getCloudPoolId(cloud?.getConfigMap(), server?.getConfigMap())
			CloudPool cloudPool
			if(cloudPoolId instanceof String) {
				cloudPool = morpheus.services.cloud.pool.find(new DataQuery().withFilter("cloud.id", cloud.id).withFilter("externalId", cloudPoolId))
			} else if(cloudPoolId instanceof Number) {
				cloudPool = morpheus.services.cloud.pool.get(cloudPoolId)
			}
			if(server.resourcePool == null && cloudPool) {
				server.resourcePool = cloudPool
			}
			AmazonEC2 amazonClient = plugin.getAmazonClient(cloud,false, server.resourcePool?.regionCode)

			VirtualImage virtualImage

			if(layout && typeSet) {
				Long computeTypeSetId = server.typeSet?.id
				if(computeTypeSetId) {
					ComputeTypeSet computeTypeSet = morpheus.services.computeTypeSet.get(computeTypeSetId)
					WorkloadType workloadType = computeTypeSet.getWorkloadType()

					if(workloadType) {
						Long workloadTypeId = workloadType.Id
						WorkloadType containerType = morpheus.services.containerType.get(workloadTypeId)
						Long virtualImageId = containerType.virtualImage.id
						virtualImage = morpheus.services.virtualImage.get(virtualImageId)   
						if(virtualImage) {
							ensureVirtualImageLocation(amazonClient, server.resourcePool?.regionCode, virtualImage, cloud)
						}
					}
				}
			} else if(imageType == 'custom') {
				if(config.publicImageId) {
					def saveResults = saveAccountImage(amazonClient, server.account, cloud, server.resourcePool?.regionCode, config.publicImageId, morpheusContext)
					virtualImage = saveResults.image
					if(virtualImage) {
						ensureVirtualImageLocation(amazonClient, server.resourcePool?.regionCode, virtualImage, cloud)
					}
				} else if(config.imageId) {
					Long imageId = config.imageId?.toLong()
					virtualImage = morpheus.services.virtualImage.get(imageId)
					if(virtualImage) {
						ensureVirtualImageLocation(amazonClient, server.resourcePool?.regionCode, virtualImage, cloud)
					}
				}
			} else {
				virtualImage = morpheus.services.virtualImage.find(new DataQuery().withFilter("code", "amazon.ec2.image.morpheus.ubuntu.20.04.4-v1.ubuntu.20.04.4.amd64"))
				if(virtualImage) {
					ensureVirtualImageLocation(amazonClient, server.resourcePool?.regionCode, virtualImage, cloud)
				}
			}
			if(!virtualImage) {
				resp.msg = "No virtual image selected"
			} else {
				server.sourceImage = virtualImage
				saveAndGet(server)
				resp.success = true
			}
		} catch(e) {
			resp.msg = "Error in prepareHost: ${e}"
			log.error("${resp.msg}, ${e}", e)

		}
		return resp

	}

	@Override
	//TODO - AC
	ServiceResponse<ProvisionResponse> runHost(ComputeServer server, HostRequest hostRequest, Map opts) {
		log.debug "runHost: ${server} ${hostRequest} ${opts}"
		AmazonEC2 amazonClient
		ProvisionResponse provisionResponse = new ProvisionResponse(success: true, installAgent: false)
		try {
			Cloud cloud = server.cloud
			VirtualImage virtualImage = server.sourceImage
			amazonClient = plugin.getAmazonClient(cloud,false, server.resourcePool.regionCode)

			def runConfig = buildHostRunConfig(server, hostRequest, virtualImage, amazonClient, opts)
			runVirtualMachine(runConfig, provisionResponse, opts + [amazonClient: amazonClient])

			if (provisionResponse.success != true) {
				return new ServiceResponse(success: false, msg: provisionResponse.message ?: 'vm config error', error: provisionResponse.message, data: provisionResponse)
			} else {
				return new ServiceResponse<ProvisionResponse>(success: true, data: provisionResponse)
			}

		} catch (e) {
			log.error "runWorkload: ${e}", e
			provisionResponse.setError(e.message)
			return new ServiceResponse(success: false, msg: e.message, error: e.message, data: provisionResponse)
		}
	}

	@Override
	ServiceResponse finalizeHost(ComputeServer server) {
		def rtn = [success: true, msg: null]
		log.debug("finalizeHost: ${server?.id}")
		return finalizeServer(server)
	}

	private finalizeServer(ComputeServer server) {
		def rtn = [success: true, msg: null]
		log.debug("finalizeServer: ${server?.id}")
		try {
			if(server && server.uuid && server.resourcePool?.externalId) {
				def amazonClient = plugin.getAmazonClient(server.cloud, false, server.resourcePool.regionCode)
				Map serverDetails = AmazonComputeUtility.checkServerReady([amazonClient: amazonClient, server: server])
				if (serverDetails.success && serverDetails.results) {
					def privateIp = serverDetails.results.getPrivateIpAddress()
					def publicIp = serverDetails.results.getPublicIpAddress()
					if (server.internalIp != privateIp) {
						server.internalIp = privateIp
						server.externalIp = publicIp
						morpheusContext.async.computeServer.save([server]).blockingGet()
					}
				}
			}
		} catch(e) {
			rtn.success = false
			rtn.msg = "Error in finalizing server: ${e.message}"
			log.error("Error in finalizeServer: ${e}", e)
		}
		return new ServiceResponse(rtn.success, rtn.msg, null, null)
	}

	protected buildHostRunConfig(ComputeServer server, HostRequest hostRequest, VirtualImage virtualImage, AmazonEC2 amazonClient, Map opts) {

		Cloud cloud = server.cloud
		StorageVolume rootVolume = server.volumes?.find{it.rootVolume == true}


		def maxMemory = server.maxMemory?.div(ComputeUtility.ONE_MEGABYTE)
		def maxStorage = rootVolume.getMaxStorage()

		def serverConfig = server.getConfigMap()

		def runConfig = [:] + opts + buildRunConfig(server, virtualImage, hostRequest.networkConfiguration, amazonClient, serverConfig, opts)

		runConfig += [
				name              : server.name,
				account 		  : server.account,
				osDiskSize		  : maxStorage.div(ComputeUtility.ONE_GIGABYTE),
				maxStorage        : maxStorage,
				maxMemory		  : maxMemory,
				applianceServerUrl: hostRequest.cloudConfigOpts?.applianceUrl,
				timezone          : (server.getConfigProperty('timezone') ?: cloud.timezone),
				proxySettings     : hostRequest.proxyConfiguration,
				noAgent           : (opts.config?.containsKey("noAgent") == true && opts.config.noAgent == true),
				installAgent      : (opts.config?.containsKey("noAgent") == false || (opts.config?.containsKey("noAgent") && opts.config.noAgent != true)),
				userConfig		  : hostRequest.usersConfiguration,
				cloudConfig		  : hostRequest.cloudConfigUser,
				networkConfig	  : hostRequest.networkConfiguration
		]

		return runConfig
	}

	protected buildWorkloadRunConfig(Workload workload, WorkloadRequest workloadRequest, VirtualImage virtualImage, AmazonEC2 amazonClient, Map opts) {
		log.debug("buildRunConfig: {}, {}, {}, {}", workload, workloadRequest, virtualImage, opts)
		Map workloadConfig = workload.getConfigMap()
		ComputeServer server = workload.server
		Cloud cloud = server.cloud
		StorageVolume rootVolume = server.volumes?.find{it.rootVolume == true}


		def maxMemory = server.maxMemory?.div(ComputeUtility.ONE_MEGABYTE)
		def maxStorage = rootVolume.getMaxStorage()

		def runConfig = [:] + opts + buildRunConfig(server, virtualImage, workloadRequest.networkConfiguration, amazonClient, workloadConfig, opts)

		runConfig += [
				name              : server.name,
				instanceId		  : workload.instance.id,
				containerId       : workload.id,
				account 		  : server.account,
				osDiskSize		  : maxStorage.div(ComputeUtility.ONE_GIGABYTE),
				maxStorage        : maxStorage,
				maxMemory		  : maxMemory,
				applianceServerUrl: workloadRequest.cloudConfigOpts?.applianceUrl,
				workloadConfig    : workload.getConfigMap(),
				timezone          : (server.getConfigProperty('timezone') ?: cloud.timezone),
				proxySettings     : workloadRequest.proxyConfiguration,
				noAgent           : (opts.config?.containsKey("noAgent") == true && opts.config.noAgent == true),
				installAgent      : (opts.config?.containsKey("noAgent") == false || (opts.config?.containsKey("noAgent") && opts.config.noAgent != true)),
				userConfig        : workloadRequest.usersConfiguration,
				cloudConfig	      : workloadRequest.cloudConfigUser,
				networkConfig	  : workloadRequest.networkConfiguration
		]

		return runConfig

	}

	protected buildRunConfig(ComputeServer server, VirtualImage virtualImage, NetworkConfiguration networkConfiguration, AmazonEC2 amazonClient, config, Map opts) {
		log.debug("buildRunConfig: {}, {}, {}, {}, {}", server, virtualImage, networkConfiguration, config, opts)
		Cloud cloud = server.cloud
		def network = networkConfiguration.primaryInterface?.network
		if(!network && server.interfaces) {
			network = server.interfaces.find {it.primaryInterface}?.network
		}
		def availabilityId = config.availabilityId ?: network?.availabilityZone ?: null
		def rootVolume = server.volumes?.find{it.rootVolume == true}
		def dataDisks = server?.volumes?.findAll{it.rootVolume == false}?.sort{it.id}
		def maxStorage
		if(rootVolume) {
			maxStorage = rootVolume.maxStorage
		} else {
			maxStorage = config.maxStorage ?: server.plan.maxStorage
		}

		def runConfig = [
				serverId: server.id,
				encryptEbs: config.encryptEbs,
				amazonClient: amazonClient,
				name: server.name,
				vpcRef: server.resourcePool?.externalId,
				securityRef: config.securityId,
				subnetRef: network?.externalId,
				flavorRef: server.plan.externalId,
				zoneRef: availabilityId,
				server: server,
				imageType: virtualImage.imageType,
				endpoint: AmazonComputeUtility.getAmazonEndpoint(cloud),
				serverOs: server.serverOs ?: virtualImage.osType,
				osType: (virtualImage.osType?.platform == 'windows' ? 'windows' : 'linux') ?: virtualImage.platform,
				platform: (virtualImage.osType?.platform == 'windows' ? 'windows' : 'linux') ?: virtualImage.platform,
				kmsKeyId: config.kmsKeyId,
				osDiskSize : maxStorage.div(ComputeUtility.ONE_GIGABYTE),
				maxStorage : maxStorage,
				osDiskType: rootVolume?.type?.name ?: 'gp2',
				iops: rootVolume?.maxIOPS,
				osDiskName:'/dev/sda1',
				dataDisks: dataDisks,
				rootVolume:rootVolume,
				//cachePath: virtualImageService.getLocalCachePath(),
				virtualImage: virtualImage,
				hostname: server.getExternalHostname(),
				hosts: server.getExternalHostname(),
				diskList:[],
				domainName: server.getExternalDomain(),
				securityGroups: config.securityGroups,
				serverInterfaces:server.interfaces,
				publicIpType: config.publicIpType ?: 'subnet',
				fqdn: server.getExternalHostname() + '.' + server.getExternalDomain(),
		]

		//TODO - tags
		//runConfig.tagList = buildMetadataTagList(container, [maxNameLength: 128, maxValueLength: 256])
		//TODO - licenses
		//runConfig.licenses = licenseService.applyLicense(vImage, 'ComputeServer', opts.server.id, opts.server.account)?.data?.licenses
		runConfig.virtualImageLocation = ensureVirtualImageLocation(amazonClient,server.resourcePool.regionCode,virtualImage,server.cloud)

		log.debug("Setting snapshot image refs opts.snapshotImageRef: ${opts.snapshotImageRef},  ${opts.rootSnapshotId}")
		if(opts.snapshotImageRef) {
			// restore from a snapshot
			runConfig.imageRef = opts.snapshotImageRef
			runConfig.osDiskSnapshot = opts.rootSnapshotId
		} else {
			// use selected provision image
			runConfig.imageRef = runConfig.virtualImageLocation.externalId
			runConfig.osDiskSnapshot = runConfig.virtualImageLocation.externalDiskId
		}

		return runConfig
	}

	private void runVirtualMachine(Map runConfig, ProvisionResponse provisionResponse, Map opts) {
		try {
			// don't think this used
			// runConfig.template = runConfig.imageId
			def runResults = insertVm(runConfig, provisionResponse, opts)
			if(provisionResponse.success) {
				finalizeVm(runConfig, provisionResponse, runResults)
			}
		} catch(e) {
			log.error("runVirtualMachine error:${e}", e)
			provisionResponse.setError('failed to upload image file')
		}
	}

	protected insertVm(Map runConfig, ProvisionResponse provisionResponse, Map opts) {
		log.debug("insertVm runConfig: {}", runConfig)
		def taskResults = [success:false]
		ComputeServer server = runConfig.server
		Account account = server.account
		opts.createUserList = runConfig.userConfig.createUsers

		//save server
		runConfig.server = saveAndGet(server)
		def imageResults = AmazonComputeUtility.loadImage([amazonClient:opts.amazonClient, imageId:runConfig.imageRef])

		//user key
		ensureAmazonKeyPair(runConfig, opts.amazonClient, account, server.cloud, runConfig.userConfig.primaryKey, morpheusContext)

		//root volume
		def blockDeviceMap = imageResults.image.getBlockDeviceMappings()
		def blockDeviceDisks = blockDeviceMap.findAll{it.getEbs() != null && it.getEbs().getSnapshotId() != null}
		if(blockDeviceDisks) {
			def rootDisk = blockDeviceDisks?.first()
			runConfig.osDiskName = rootDisk.deviceName
			println "setting osDiskName: ${runConfig.osDiskName}"
		}
		//data volumes
		if(runConfig.dataDisks)
			runConfig.diskList = buildDataDiskList(server, runConfig.dataDisks, imageResults)
		def createResults = AmazonComputeUtility.createServer(runConfig)
		log.debug("create server: ${createResults}")
		if(createResults.success == true && createResults.server) {
			server.externalId = createResults.externalId
			server.powerState = 'on'
			server.region = new CloudRegion(code: server.resourcePool.regionCode)
			provisionResponse.externalId = server.externalId
			server = saveAndGet(server)
			runConfig.server = server

			AmazonComputeUtility.waitForServerExists(runConfig)
			//wait for ready
			def statusResults = AmazonComputeUtility.checkServerReady(runConfig)
			if(statusResults.success == true) {
				//good to go
				def serverDetails = AmazonComputeUtility.getServerDetail(runConfig)
				if(serverDetails.success == true) {
					log.debug("server details: {}", serverDetails)
					//update volume info
					setRootVolumeInfo(runConfig.rootVolume, serverDetails.server)
					setVolumeInfo(runConfig.dataDisks, serverDetails.volumes)
					setNetworkInfo(runConfig.serverInterfaces, serverDetails.networks)
					//update network info
					def privateIp = serverDetails.server.getPrivateIpAddress()
					def publicIp = serverDetails.server.getPublicIpAddress()
					def serverConfigOpts = [:]
					if(opts.containerConfig?.publicIpType?.toString() == 'elasticIp' || server.getConfigMap()?.customOptions?.publicIpType?.toString() == 'elasticIp') {
						def lock
						try {
							lock = morpheusContext.acquireLock("container.amazon.allocateIp.${runConfig.zone.id}".toString(), [timeout: 660l * 1000l]).blockingGet()
							def freeIp = AmazonComputeUtility.getFreeEIP([zone: opts.zone, amazonClient:opts.amazonClient])
							def allocationId
							def eipPublicIp
							if(freeIp.success) {
								allocationId = freeIp.allocationId
								eipPublicIp = freeIp.publicIp
							} else {

								def createEIPResults = AmazonComputeUtility.createEIP([zone: opts.zone, amazonClient:opts.amazonClient])
								if(createEIPResults.success) {
									allocationId = createEIPResults.result.allocationId
									eipPublicIp = createEIPResults.result?.publicIp
								}
							}
							if(allocationId) {
								def associateResult = AmazonComputeUtility.associateEIP([allocationId: allocationId, zone: opts.zone, amazonClient:opts.amazonClient, externalId: createResults.externalId])
								if(eipPublicIp) {
									publicIp = eipPublicIp
									serverConfigOpts.eipPublicIp = eipPublicIp
									serverConfigOpts.eipAllocationId = allocationId
									serverConfigOpts.eipAssociationId = associateResult?.result?.associationId
								}
							}

						} catch(e) {
							log.error("execContainer error: ${e}", e)
						} finally {
							if(lock) {
								morpheusContext.releaseLock("container.amazon.allocateIp.${runConfig.zone.id}".toString(),[lock:lock]).blockingGet()
							}
						}

					}
					//update network info
					applyComputeServerNetwork(server, privateIp, publicIp, null, null, serverConfigOpts)

					//add extra nics
					if(runConfig.networkConfig?.extraInterfaces?.size() > 0) {
						runConfig.networkConfig.extraInterfaces?.eachWithIndex { extraInterface, index ->
							def networkConfig = [serverId:createResults.externalId, amazonClient:opts.amazonClient, securityGroups:runConfig.securityGroups,
												 subnetId:extraInterface.externalId, deviceIndex:(index + 1)]
							if (extraInterface.doStatic && extraInterface.ipAddress)
								networkConfig.ipAddress = extraInterface.ipAddress
							def networkResults = AmazonComputeUtility.addNetworkInterface(networkConfig)
							log.info("networkResults: ${networkResults}")
							if(networkResults.success == true && networkResults.networkInterface?.getNetworkInterfaceId()) {
								networkConfig.networkInterfaceId = networkResults.networkInterface?.getNetworkInterfaceId()
								def attachResults = AmazonComputeUtility.attachNetworkInterface(networkConfig)
								log.info("attachResults: ${attachResults}")
								if(attachResults.success == true) {
									def privateIps = networkResults.networkInterface.getPrivateIpAddresses()
									if(privateIps?.size() > 0) {
										def newPrivateIp = privateIps.first().getPrivateIpAddress()
										def newNetworkUpdates = [internalId:networkResults.networkInterface?.getNetworkInterfaceId(), externalId:attachResults.attachmentId,
																 uniqueId:"morpheus-nic-${runConfig.instanceId  ? runConfig.instanceId + "-" + runConfig.containerId : runConfig.serverId}-${index + 1}"]
										applyComputeServerNetwork(server, newPrivateIp, null, null, null, [:], index + 1, newNetworkUpdates)
									}
								} else {
									//lets just remove it then
									AmazonComputeUtility.deleteNetworkInterface(networkConfig)
								}
							}
						}
					}
					//get password
					if(runConfig.osType == 'windows' && runConfig.virtualImage.isCloudInit) {
						def passwordResults = AmazonComputeUtility.checkPasswordReady(runConfig)
						if(passwordResults.success == true) {
							log.debug("got win password")//: ${passwordResults.password}")
							if(opts.findAdminPassword == true) {
								taskResults.newPassword = passwordResults.password
							}
						}
					}
					taskResults.server = createResults.server
					taskResults.success = true
				} else {
					taskResults.message = 'Failed to get server status'
				}
			} else {
				taskResults.message = 'Failed to create server'
			}
		} else {
			taskResults.message = createResults.msg
		}
		return taskResults
	}

	def finalizeVm(Map runConfig, ProvisionResponse provisionResponse, Map runResults) {
		log.debug("runTask onComplete: provisionResponse: ${provisionResponse}")
		ComputeServer server = morpheusContext.async.computeServer.get(runConfig.serverId).blockingGet()
		try {
			if(provisionResponse.success == true) {
				server.sshHost = runResults.sshHost
				server.status = 'provisioned'
				server.statusDate = new Date()
				server.serverType = 'ami'
				server.osDevice = '/dev/xvda'
				server.lvmEnabled = false
				server.managed = true
				if(runResults.newPassword)
					server.sshPassword = runResults.newPassword
				server.capacityInfo = new ComputeCapacityInfo(maxCores:1, maxMemory:runConfig.maxMemory,
						maxStorage:runConfig.maxStorage)
				saveAndGet(server)
			}
		} catch(e) {
			log.error("finalizeVm error: ${e}", e)
			provisionResponse.setError('failed to run server: ' + e)
		}
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
		log.debug("startWorkload: ${workload}")
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
			def client = plugin.getAmazonClient(server.cloud, false, server.resourcePool?.regionCode)
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
			def client = plugin.getAmazonClient(server.cloud, false, server.resourcePool?.regionCode)
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
			def client = plugin.getAmazonClient(server.cloud, false, server.resourcePool.regionCode)
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
		log.debug("restartWorkload: ${workload}")
		def client = plugin.getAmazonClient(workload.server.cloud, false, workload.server.resourcePool.regionCode)
		AmazonComputeUtility.rebootServer([amazonClient: client, server: workload.server])
		def waitResults = AmazonComputeUtility.waitForServerStatus([amazonClient: client, server: workload.server], 16)
		if (waitResults.success) {
			return ServiceResponse.success()
		} else {
			return ServiceResponse.error('Failed to start vm')
		}
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
	 * @return Response from API. The publicIp and privateIp set on the ProvisionResponse will be utilized to update the ComputeServer
	 */
	@Override
	ServiceResponse<ProvisionResponse> getServerDetails(ComputeServer server) {
		ProvisionResponse rtn = new ProvisionResponse()
		def serverUuid = server.externalId
		if(server && server.uuid && server.resourcePool?.externalId) {
			def amazonClient = plugin.getAmazonClient(server.cloud,false, server.resourcePool.regionCode)
			Map serverDetails = AmazonComputeUtility.checkServerReady([amazonClient:amazonClient, server:server])
			if(serverDetails.success && serverDetails.results) {
				rtn.externalId = serverUuid
				rtn.success = serverDetails.success
				rtn.publicIp = serverDetails.results.getPublicIpAddress()
				rtn.privateIp = serverDetails.results.getPrivateIpAddress()
				rtn.hostname = serverDetails.results.getTags()?.find { it.key == 'Name' }?.value ?: serverDetails.instanceId
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
		def server = morpheusContext.async.computeServer.get(workload.server.id).blockingGet()
		if(server) {
			return internalResizeServer(server, resizeRequest, opts)
		} else {
			return ServiceResponse.error("No server provided")
		}
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
		return internalResizeServer(server, resizeRequest, opts)
	}


	private ServiceResponse internalResizeServer(ComputeServer server, ResizeRequest resizeRequest, Map opts) {
		def rtn = [success:false, supported:true]
		def amazonOpts = [server:server]
		Cloud cloud = server.cloud
		ServicePlan plan = resizeRequest.plan
		try {

			def encryptEbs = cloud.getConfigProperty('ebsEncryption') == 'on'

			amazonOpts.account = server.account
			amazonOpts.amazonClient = plugin.getAmazonClient(cloud,false, server.resourcePool.regionCode)
			def serverId = server.id

			def statusResults = AmazonComputeUtility.waitForServerStatus(amazonOpts, 80)
			if(statusResults.success == true) {

				//instance size
				if (plan?.id != server.plan?.id) {
					amazonOpts.flavorId = plan.externalId
					log.info("Resizing Plan")
					AmazonComputeUtility.resizeInstance(amazonOpts)
					server.plan = plan
					server.maxMemory = plan.maxMemory
					server.maxCores = plan.maxCores
					server.setConfigProperty('maxMemory', plan.maxMemory)
					server = saveAndGet(server)
				}

				//disk sizes

				def maxStorage = resizeRequest.maxStorage
				def newCounter = server.volumes?.size()
				def availabilityZone
				def allStorageVolumeTypes
				if (resizeRequest.volumesUpdate || resizeRequest.volumesAdd) {
					def serverDetails = AmazonComputeUtility.getServerDetail(amazonOpts)
					availabilityZone = serverDetails.server.getPlacement().getAvailabilityZone()
					allStorageVolumeTypes = morpheusContext.async.storageVolume.storageVolumeType.listAll().toMap { it.id }.blockingGet()
				}

				resizeRequest.volumesAdd?.each {newVolumeProps ->
					log.info("Adding New Volume")
					//new disk add it
					if (!newVolumeProps.maxStorage) {
						newVolumeProps.maxStorage = newVolumeProps.size ? (newVolumeProps.size.toDouble() * ComputeUtility.ONE_GIGABYTE).toLong() : 0
					}
					def volumeType = allStorageVolumeTypes[newVolumeProps.storageType?.toInteger()]
					def diskType = volumeType ? volumeType?.name : 'gp2'
					def addDiskResults = AmazonComputeUtility.addVolume([name: newVolumeProps.name, size: newVolumeProps.size, iops: newVolumeProps.maxIOPS ? newVolumeProps.maxIOPS.toInteger() : null,
																		 amazonClient: amazonOpts.amazonClient, availabilityId: availabilityZone, encryptEbs: encryptEbs, diskType: diskType, kmsKeyId: server.getConfigProperty('kmsKeyId')])
					if (!addDiskResults.success)
						throw new Exception("Error in creating new volume: ${addDiskResults}")
					def newVolumeId = addDiskResults.volume.volumeId
					def checkReadyResult = AmazonComputeUtility.checkVolumeReady([volumeId: newVolumeId, amazonClient: amazonOpts.amazonClient])
					if (!checkReadyResult.success)
						throw new Exception("Volume never became ready: ${checkReadyResult}")
					// Attach the new one
					def attachResults = AmazonComputeUtility.attachVolume([volumeId: newVolumeId, serverId: amazonOpts.server.externalId, amazonClient: amazonOpts.amazonClient])
					if (!attachResults.success)
						throw new Exception("Volume failed to attach: ${attachResults}")
					def waitAttachResults = AmazonComputeUtility.waitForVolumeState([volumeId: newVolumeId, requestedState: 'in-use', amazonClient: amazonOpts.amazonClient])
					if (!waitAttachResults.success)
						throw new Exception("Volume never attached: ${waitAttachResults}")

					def deviceName = waitAttachResults.results?.volume?.getAttachments()?.find { it.instanceId == amazonOpts.server.externalId }?.getDevice()
					def newVolume = new StorageVolume(
							refType: 'ComputeZone',
							refId: cloud.id,
							regionCode: server.region?.regionCode,
							account: server.account,
							maxStorage: newVolumeProps.maxStorage,
							maxIOPS: newVolumeProps.maxIops,
							type: volumeType,
							externalId: newVolumeId,
							deviceName: deviceName,
							deviceDisplayName: extractDiskDisplayName(deviceName),
							name: newVolumeProps.name,
							displayOrder: newCounter,
							status: 'provisioned',
							rootVolume: ['/dev/sda1','/dev/xvda','xvda','sda1','sda'].contains(deviceName)
					)
					log.info("Saving Volume")
					morpheusContext.async.storageVolume.create([newVolume], server).blockingGet()
					server = morpheusContext.async.computeServer.get(server.id).blockingGet()
					newCounter++
				}

				resizeRequest.volumesUpdate?.each { volumeUpdate ->
					log.info("resizing vm storage: count: ${volumeUpdate}")
					StorageVolume existing = volumeUpdate.existingModel
					Map updateProps = volumeUpdate.updateProps
					if (existing) {
						def iops = updateProps.maxIOPS ? updateProps.maxIOPS.toInteger() : null

						//existing disk - resize it
						if (updateProps.maxStorage > existing.maxStorage || (iops && allStorageVolumeTypes[updateProps.storageType]?.configurableIOPS && iops != existing.maxIOPS)) {
							def volumeId = existing.externalId
							def resizeResults = AmazonComputeUtility.resizeVolume([encryptEbs: encryptEbs, name: updateProps.name, volumeId: volumeId, size: updateProps.size, iops: iops, deleteOriginalVolumes: opts.deleteOriginalVolumes == true || opts.deleteOriginalVolumes == 'on'] + amazonOpts)
							if (resizeResults.success == true) {
								existing.maxIOPS = iops
								existing.externalId = resizeResults.newVolumeId
								existing.maxStorage = updateProps.maxStorage.toLong()
								morpheusContext.async.storageVolume.save([existing]).blockingGet()
							} else {
								rtn.setError("Failed to expand Disk: ${existing.name}")
							}
						}
					} else {
						log.info("Adding New Volume")
						//new disk add it
						if (!updateProps.maxStorage) {
							updateProps.maxStorage = updateProps.size ? (updateProps.size.toDouble() * ComputeUtility.ONE_GIGABYTE).toLong() : 0
						}
						def volumeType = allStorageVolumeTypes[updateProps.storageType?.toInteger()]
						def diskType = volumeType ? volumeType?.name : 'gp2'
						def addDiskResults = AmazonComputeUtility.addVolume([name: updateProps.name, size: updateProps.size, iops: updateProps.maxIOPS ? updateProps.maxIOPS.toInteger() : null,
																			 amazonClient: amazonOpts.amazonClient, availabilityId: availabilityZone, encryptEbs: encryptEbs, diskType: diskType, kmsKeyId: server.getConfigProperty('kmsKeyId')])
						if (!addDiskResults.success)
							throw new Exception("Error in creating new volume: ${addDiskResults}")
						def newVolumeId = addDiskResults.volume.volumeId
						def checkReadyResult = AmazonComputeUtility.checkVolumeReady([volumeId: newVolumeId, amazonClient: amazonOpts.amazonClient])
						if (!checkReadyResult.success)
							throw new Exception("Volume never became ready: ${checkReadyResult}")
						// Attach the new one
						def attachResults = AmazonComputeUtility.attachVolume([volumeId: newVolumeId, serverId: amazonOpts.server.externalId, amazonClient: amazonOpts.amazonClient])
						if (!attachResults.success)
							throw new Exception("Volume failed to attach: ${attachResults}")
						def waitAttachResults = AmazonComputeUtility.waitForVolumeState([volumeId: newVolumeId, requestedState: 'in-use', amazonClient: amazonOpts.amazonClient])
						if (!waitAttachResults.success)
							throw new Exception("Volume never attached: ${waitAttachResults}")

						def deviceName = waitAttachResults.results?.volume?.getAttachments()?.find { it.instanceId == amazonOpts.server.externalId }?.getDevice()
						def newVolume = new StorageVolume(
								refType: 'ComputeZone',
								refId: cloud.id,
								regionCode: server.region?.regionCode,
								account: server.account,
								maxStorage: updateProps.maxStorage,
								maxIOPS: updateProps.maxIops,
								type: volumeType,
								externalId: newVolumeId,
								deviceName: deviceName,
								deviceDisplayName: extractDiskDisplayName(deviceName),
								name: updateProps.name,
								displayOrder: newCounter,
								status: 'provisioned',
								rootVolume: ['/dev/sda1','/dev/xvda','xvda','sda1','sda'].contains(deviceName)
						)
						log.info("Saving Volume")
						morpheusContext.async.storageVolume.create([newVolume], server).blockingGet()
						server = morpheusContext.async.computeServer.get(server.id).blockingGet()
						newCounter++
					}

				}

				//delete any removed volumes
				resizeRequest.volumesDelete.each { volume ->
					log.info("Deleting volume : ${volume.externalId}")
					def volumeId = volume.externalId
					def detachResults = AmazonComputeUtility.detachVolume([volumeId: volumeId, instanceId: server.externalId, amazonClient: amazonOpts.amazonClient])
					if (detachResults.success == true) {
						AmazonComputeUtility.deleteVolume([volumeId: volumeId, amazonClient: amazonOpts.amazonClient])
						morpheusContext.async.storageVolume.remove([volume], server, true).blockingGet()
					}
				}

				//network adapters
				def securityGroups = server.getConfigProperty('securityGroups')
				//controllers
				resizeRequest?.interfacesAdd?.eachWithIndex { networkAdd, index ->

					log.info("adding network: ${networkAdd}")
					def newIndex = server.interfaces?.size()
					Network networkObj = morpheusContext.async.network.listById([networkAdd.network.id.toLong()]).firstOrError().blockingGet()
					def networkConfig = [serverId: server.externalId, amazonClient: amazonOpts.amazonClient, securityGroups: securityGroups,
										 subnetId: networkObj.externalId]
					def networkResults = AmazonComputeUtility.addNetworkInterface(networkConfig)
					log.info("network results: ${networkResults}")
					def nic = networkResults.networkInterface
					def platform = server.platform
					def nicName
					if(platform == 'windows') {
						nicName = (index == 0) ? 'Ethernet' : 'Ethernet ' + (index + 1)
					} else if(platform == 'linux') {
						nicName = "eth${index}"
					} else {
						nicName = "eth${index}"
					}
					if (networkResults.success == true && nic?.getNetworkInterfaceId()) {
						networkConfig.networkInterfaceId = nic?.getNetworkInterfaceId()
						def attachResults = AmazonComputeUtility.attachNetworkInterface(networkConfig)
						if (attachResults.success == true) {
							def newInterface = new ComputeServerInterface([
									externalId      : attachResults.attachmentId,
									internalId      : nic?.getNetworkInterfaceId(),
									uniqueId        : "morpheus-nic-${serverId}-${newIndex}",
									name            : nicName,
									ipAddress       : nic?.getPrivateIpAddress(),
									network         : networkObj,
									displayOrder    : newIndex,
									primaryInterface: networkAdd?.network?.isPrimary ? true : false
							])
							newInterface.addresses += new NetAddress(type: NetAddress.AddressType.IPV4, address: nic?.getPrivateIpAddress())

							newInterface = morpheusContext.async.computeServer.computeServerInterface.create(newInterface).blockingGet()
							server.interfaces += newInterface
							server = saveAndGet(server)
						}
					}

				}
				resizeRequest?.interfacesDelete?.eachWithIndex { networkDelete, index ->
					def deleteConfig = [serverId    : server.externalId, amazonClient: amazonOpts.amazonClient, networkInterfaceId: networkDelete.internalId,
										attachmentId: networkDelete.externalId]
					def detachResults = AmazonComputeUtility.detachNetworkInterface(deleteConfig)
					log.debug("detachResults: ${detachResults}")
					if (detachResults.success == true) {
						def deleteResults = AmazonComputeUtility.deleteNetworkInterface(deleteConfig)
						if (deleteResults.success == true) {
							morpheusContext.async.computeServer.computeServerInterface.remove([networkDelete], server).blockingGet()
							server = morpheusContext.async.computeServer.get(server.id).blockingGet()
						}
					}
				}

				rtn.success = true
			}
		} catch(ex) {
			log.error("Error resizing amazon instance to ${plan.name}", ex)
			rtn.success = false
			rtn.msg = "Error resizing amazon instance to ${plan.name} ${ex.getMessage()}"
			rtn.error= "Error resizing amazon instance to ${plan.name} ${ex.getMessage()}"
		}
		return new ServiceResponse(success: rtn.success, data: [supported: rtn.supported])

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
	 * Provides a Collection of {@link VirtualImage} related to this ProvisionProvider. This provides a way to specify
	 * known VirtualImages in the Cloud environment prior to a typical 'refresh' on a Cloud. These are often used in
	 * predefined layouts. For example, when building up ComputeTypeLayouts
	 * @return Collection of {@link VirtualImage}
	 */
	@Override
	Collection<VirtualImage> getVirtualImages() {
		new ArrayList<VirtualImage>()
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
			rtn = morpheusContext.async.virtualImage.get(containerConfig.imageId as Long).blockingGet()
		} else if(imageType == 'local' && (containerConfig.localImageId || containerConfig.template)) {
			Long localImageId = getImageId(containerConfig.localImageId) ?: getImageId(containerConfig.template)
			if(localImageId) {
				rtn = morpheusContext.async.virtualImage.get(localImageId).blockingGet()
			}
		} else if(imageType == 'public' && containerConfig.publicImageId) {

			def saveResults = saveAccountImage(amazonClient, workload.account, workload.server.cloud, regionCode, containerConfig.publicImageId)
			rtn = saveResults.image
		} else if(workload.workloadType.virtualImage) {
			rtn = workload.workloadType.virtualImage
		}
		return rtn
	}

	protected saveAccountImage(AmazonEC2 amazonClient, account, zone, String regionCode, publicImageId, sourceImage = null) {
	 return saveAccountImage(amazonClient, account, zone, regionCode, publicImageId, morpheusContext, sourceImage)
	}

	static saveAccountImage(AmazonEC2 amazonClient, account, zone, String regionCode, publicImageId, MorpheusContext morpheus, sourceImage = null) {
		def rtn = [success:false]
		try {
			if(publicImageId?.startsWith('!')) {
				return rtn
			}
			VirtualImageLocation existing = morpheus.async.virtualImage.location.findVirtualImageLocationByExternalIdForCloudAndType(publicImageId,zone.id,regionCode,'ami',account.id).blockingGet()?.orElse(null)
			
			log.info("saveAccountImage existing: ${existing}")
			if(existing) {
				if(!existing.externalDiskId) {
					def imageResults = AmazonComputeUtility.loadImage([amazonClient:amazonClient, imageId:publicImageId])
					if(imageResults.success == true && imageResults.image)	{
						existing.externalDiskId = imageResults.image.blockDeviceMappings.find{ mapping -> mapping.deviceName == imageResults.image.rootDeviceName}?.ebs?.snapshotId
						morpheus.async.virtualImage.location.save([existing]).blockingGet()
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
									   hypervisor:imageResults.image.getHypervisor(), platform:(imageResults.image.getPlatform() == 'windows' ? 'windows' : 'linux'),
									   productCode:'', externalId:imageResults.image.getImageId(), ramdiskId:imageResults.image.getRamdiskId(), isCloudInit: (imageResults.image.getPlatform() == 'windows' && imageResults.image.getImageOwnerAlias() != 'amazon' ? false : true),
									   rootDeviceName:imageResults.image.getRootDeviceName(), rootDeviceType:imageResults.image.getRootDeviceType(),
									   enhancedNetwork:imageResults.image.getSriovNetSupport(), status:imageResults.image.getState(),
									   statusReason:imageResults.image.getStateReason(), virtualizationType:imageResults.image.getVirtualizationType(),
									   isPublic:imageResults.image.isPublic(), refType:'ComputeZone', refId:"${zone.id}", owner:account, userDefined:true
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
										  refType:'ComputeZone', refId:zone.id, imageName:imageResults.image.getName(), imageRegion:regionCode]
					def addLocation = new VirtualImageLocation(locationConfig)
					add.imageLocations = [addLocation]
					VirtualImage imageResult = morpheus.async.virtualImage.create(add).blockingGet()
					rtn.awsImage = imageResults.image
					rtn.image = imageResult
					rtn.imageId = imageResult.id
					log.info("saveAccountImage result: ${add.code}")

					rtn.success = true
				}
			}
		} catch(e) {
			log.error("saveAccountImage error: ${e}", e)
		}
		return rtn
	}


	protected VirtualImageLocation ensureVirtualImageLocation(AmazonEC2 amazonClient, String region, VirtualImage virtualImage, Cloud cloud) {
		def rtn = virtualImage.imageLocations?.find{it.refType == 'ComputeZone' && it.refId == cloud.id && it.imageRegion == region}
		if(!rtn) {
			rtn = virtualImage.imageLocations?.find{it.refType == 'ComputeZone' && it.refId == cloud.id}
		}
		if(!rtn) {
			rtn = virtualImage.imageLocations?.find{it.imageRegion == region}
		}
		if(!rtn) {
			if(virtualImage.isPublic) {
				//load image by name
				def publicImageResults = AmazonComputeUtility.loadImage([amazonClient:amazonClient, imageName:virtualImage.name, isPublic:true])
				if(publicImageResults.success && publicImageResults.image) {
					def diskId = publicImageResults.image.blockDeviceMappings.find{ mapping -> mapping.deviceName == publicImageResults.image.rootDeviceName}?.ebs?.snapshotId
					def newLocation = new VirtualImageLocation(virtualImage: virtualImage,imageName: virtualImage.name, externalId: publicImageResults.image.getImageId(), imageRegion: region, externalDiskId: diskId)
					newLocation = morpheus.async.virtualImage.location.create(newLocation, cloud).blockingGet()
					return newLocation
				}
			}
			if(!virtualImage.externalDiskId && virtualImage.externalId) {
				def imageResults = AmazonComputeUtility.loadImage([amazonClient:amazonClient, imageId:virtualImage.externalId])
				if(imageResults.success == true && imageResults.image)	{
					virtualImage.externalDiskId = imageResults.image.blockDeviceMappings.find{ mapping -> mapping.deviceName == imageResults.image.rootDeviceName}?.ebs?.snapshotId
				}
			}
			rtn = new VirtualImageLocation([externalId:virtualImage.externalId, externalDiskId:virtualImage.externalDiskId])
		}
		return rtn
	}


	static protected ServiceResponse ensureAmazonKeyPair(runConfig, AmazonEC2Client amazonClient, Account account, Cloud cloud, KeyPair primaryKey, MorpheusContext morpheus) {
		ServiceResponse rtn = ServiceResponse.prepare(data: primaryKey)
		if(primaryKey) {
			def keyLocationId = 'amazon-' + cloud.id
			def accountKey = morpheus.async.keyPair.findOrGenerateByAccount(account.id).blockingGet()
			if(primaryKey.id == accountKey?.id) {
				log.debug('checking for keypair')
				//this is the account wide key
				runConfig.publicKeyName = cloud.getConfigProperty(keyLocationId)
				runConfig.primaryKey = primaryKey
				def keyResults = AmazonComputeUtility.uploadKeypair([key:primaryKey, account:account, zone:cloud,
																	 keyName:runConfig.publicKeyName, amazonClient:amazonClient])
				log.debug("key results : {}", keyResults)
				if(keyResults.success == true) {
					runConfig.publicKeyName = keyResults.keyName

					if(keyResults.uploaded == true) {
						morpheus.async.keyPair.addZoneKeyPairLocation(cloud.id, keyLocationId, keyResults.keyName)
					}

					rtn.data.keyName = keyResults.keyName
					rtn.success = true
				}
			} else {
				log.debug('checking for keypair2')
				//this is a user keypair
				runConfig.publicKeyName = primaryKey.getConfigProperty(keyLocationId)
				runConfig.primaryKey = primaryKey
				def keyResults = AmazonComputeUtility.uploadKeypair([key:primaryKey, account:account, zone:cloud,
																	 keyName:runConfig.publicKeyName, amazonClient:amazonClient])
				if(keyResults.success == true) {
					log.debug("key results 2: {}", keyResults)
					runConfig.publicKeyName = keyResults.keyName
					if(keyResults.uploaded == true) {
						morpheus.async.keyPair.addKeyPairLocation(primaryKey.id, keyLocationId, keyResults.keyName)
					}

					rtn.data.keyName = keyResults.keyName
					rtn.success = true
				}
			}
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

	private static getCloudPoolId(zoneConfig, containerConfig) {
		def rtn = zoneConfig['vpc']
		if(!rtn)
			rtn = containerConfig['resourcePool']
		return rtn
	}

	protected ComputeServer saveAndGet(ComputeServer server) {
		def saveSuccessful = morpheusContext.async.computeServer.save([server]).blockingGet()
		if(!saveSuccessful) {
			log.warn("Error saving server: ${server?.id}" )
		}
		return morpheusContext.async.computeServer.get(server.id).blockingGet()
	}

	def buildDataDiskList(server, dataDisks, imageResults) {
		def rtn = []
		if(dataDisks) {
			def blockDeviceMap = imageResults.image.getBlockDeviceMappings()
			def blockDeviceDisks = blockDeviceMap.findAll{it.getEbs() != null && it.getEbs().getSnapshotId() != null}
			def blockDeviceDataDiskCount = blockDeviceDisks?.size() - 1 //root volume already handled
			def volumeUpdates = []
			dataDisks?.eachWithIndex { dataVolume, index ->
				if(index >= blockDeviceDataDiskCount - 1) {
					def deviceName = AmazonComputeUtility.getFreeVolumeName(blockDeviceDisks, index)
					rtn << [diskType:dataVolume?.type?.name ?: 'gp2', diskSize:dataVolume.maxStorage.div(ComputeUtility.ONE_GIGABYTE),
							deviceName:deviceName.deviceName, iops: dataVolume.maxIOPS] //iops
					dataVolume.deviceName = deviceName.deviceName
					dataVolume.deviceDisplayName = extractDiskDisplayName(deviceName.deviceName)
					volumeUpdates << dataVolume

				}
			}
			morpheusContext.async.storageVolume.bulkSave(volumeUpdates).blockingGet()
		}
		return rtn
	}

	def extractDiskDisplayName(name) {
		def rtn = name
		if(rtn) {
			def lastSlash = rtn.lastIndexOf('/')
			if(lastSlash > -1)
				rtn = rtn.substring(lastSlash + 1)
		}
		return  changeDiskDisplayName(rtn)
	}

	private changeDiskDisplayName(name) {
		name = name?.replaceAll('sd', 'xvd')
		if(name?.endsWith('1'))
			name = name.substring(0, name.length() - 1)
		return name
	}

	def setNetworkInfo(serverInterfaces, externalNetworks, newInterface = null) {
		log.info("networks: ${externalNetworks}")
		try {
			if(externalNetworks?.size() > 0) {
				serverInterfaces?.eachWithIndex { networkInterface, index ->
					if(networkInterface.externalId) {
						//check for changes?
					} else {
						def matchNetwork = externalNetworks.find{ it.row == networkInterface.displayOrder }
						if(matchNetwork) {
							networkInterface.externalId = "${matchNetwork.externalId}"
							if(matchNetwork.macAddress && matchNetwork.macAddress != networkInterface.macAddress)
								networkInterface.macAddress = matchNetwork.macAddress
							if(networkInterface.type == null)
								networkInterface.type = new ComputeServerInterfaceType(code: 'standard')
							if(!networkInterface.name)
								networkInterface.name = matchNetwork.name
							networkInterface.description = matchNetwork.description
						}
					}
				}
				serverInterfaces?.each { netInt ->
					log.info("Net Interface: ${netInt.id} -> Network: ${netInt.network?.id}")
				}
				morpheusContext.async.computeServer.computeServerInterface.save(serverInterfaces).blockingGet()
			}
		} catch(e) {
			log.error("setNetworkInfo error: ${e}", e)
		}
	}


	def setRootVolumeInfo(StorageVolume rootVolume, awsInstance) {
		if(rootVolume && awsInstance) {
			def rootDeviceName = awsInstance?.getRootDeviceName()
			def awsRootDisk = awsInstance?.getBlockDeviceMappings()?.find { it.getDeviceName() == rootDeviceName }
			log.info("Assigning External ID to root Volume we hope ${awsRootDisk} - ${rootDeviceName} - ${rootVolume?.id}")
			if(awsRootDisk) {
				rootVolume.externalId = awsRootDisk.getEbs().getVolumeId()
				rootVolume.deviceName = rootDeviceName
				rootVolume.deviceDisplayName = extractDiskDisplayName(rootDeviceName)
			}
			morpheusContext.async.storageVolume.save([rootVolume]).blockingGet()
		}
	}

	def setVolumeInfo(serverVolumes, externalVolumes, doRoot = false) {
		log.info("external volumes: ${externalVolumes}")
		try {
			def maxCount = externalVolumes?.size()
			serverVolumes.sort{it.displayOrder}.eachWithIndex { volume, index ->
				if(index < maxCount && (volume.rootVolume != true || doRoot == true)) {
					if(volume.externalId) {
						log.debug("volume already assigned: ${volume.externalId}")
					} else {
						def volumeMatch = externalVolumes.find{it.deviceName == volume.deviceName}
						log.debug("looking for volume: ${volume.deviceName} found: ${volumeMatch}")
						if(volumeMatch) {
							volume.status = 'provisioned'
						    volume.externalId = volumeMatch.volumeId
						}
					}
				}
			}
			morpheusContext.async.storageVolume.save(serverVolumes).blockingGet()
		} catch(e) {
			log.error("setVolumeInfo error: ${e}", e)
		}
	}


	private applyComputeServerNetwork(server, privateIp, publicIp = null, hostname = null, networkPoolId = null, configOpts = [:], index = 0, networkOpts = [:]) {
		configOpts.each { k,v ->
			server.setConfigProperty(k, v)
		}
		ComputeServerInterface network
		if(privateIp) {
			privateIp = privateIp?.toString().contains("\n") ? privateIp.toString().replace("\n", "") : privateIp.toString()
			def newInterface = false
			server.internalIp = privateIp
			server.sshHost = privateIp
			log.debug("Setting private ip on server:${server.sshHost}")
			network = server.interfaces?.find{it.ipAddress == privateIp}

			if(network == null) {
				if(index == 0)
					network = server.interfaces?.find{it.primaryInterface == true}
				if(network == null)
					network = server.interfaces?.find{it.displayOrder == index}
				if(network == null)
					network = server.interfaces?.size() > index ? server.interfaces[index] : null
			}
			if(network == null) {
				def interfaceName = server.sourceImage?.interfaceName ?: 'eth0'
				network = new ComputeServerInterface(name:interfaceName, ipAddress:privateIp, primaryInterface:true,
						displayOrder:(server.interfaces?.size() ?: 0) + 1, externalId: networkOpts.externalId)
				network.addresses += new NetAddress(type: NetAddress.AddressType.IPV4, address: privateIp)
				newInterface = true
			} else {
				network.ipAddress = privateIp
			}
			if(publicIp) {
				publicIp = publicIp?.toString().contains("\n") ? publicIp.toString().replace("\n", "") : publicIp.toString()
				network.publicIpAddress = publicIp
				server.externalIp = publicIp
			}
			if(networkPoolId) {
				network.poolAssigned = true
				network.networkPool = NetworkPool.get(networkPoolId.toLong())
			}
			if(hostname) {
				server.hostname = hostname
			}

			if(networkOpts) {
				networkOpts.each { key, value ->
					network[key] = value
				}
			}

			if(newInterface == true)
				morpheusContext.async.computeServer.computeServerInterface.create([network], server).blockingGet()
			else
				morpheusContext.async.computeServer.computeServerInterface.save([network]).blockingGet()
		}
		saveAndGet(server)
		return network
	}

	protected assignSnapshotsToStorageVolumes(Workload workload, List<Map>snapshots){
		def rtn = [success:true, osDisk:null, dataDisks:[]]
		def rootVolume = getContainerRootDisk(workload)
		log.debug("rootVolume: ${rootVolume}")
		def dataDisks = getContainerDataDiskList(workload)
		log.debug("dataDisks: ${dataDisks}")
		def rootSnapshot = snapshots.find{it.diskType == "root"} ?: snapshots.first()
		rootVolume.sourceSnapshotId = rootSnapshot.snapshotId
		def saveResults = morpheus.async.storageVolume.save(rootVolume).blockingGet()
		log.debug("root volume saveResults: ${saveResults}")
		rtn.osDisk = rootVolume
		def dataSnapshots = snapshots.findAll{it.diskType == "data" && it != rootSnapshot}
		dataDisks.each { dataDisk ->
			def volumeSizeGb = dataDisk.maxStorage?.div(ComputeUtility.ONE_GIGABYTE)
			def dataSnapshot = dataSnapshots.find{it.volumeSize == volumeSizeGb}
			if(dataSnapshot){
				dataDisk.sourceSnapshotId = dataSnapshot.snapshotId
				dataSnapshots.remove(dataSnapshot)
				def dataDiskSaveResults = morpheus.async.storageVolume.save(dataDisk).blockingGet()
				log.debug("data volume saveResults: ${dataDiskSaveResults}")
				rtn.dataDisks << dataDisk
			}
		}

		return rtn
	}

	def getContainerRootDisk(container) {
		def rtn = container.server?.volumes?.find{it.rootVolume == true}
		return rtn
	}

	def getContainerDataDiskList(container) {
		def rtn = container.server?.volumes?.findAll{it.rootVolume == false}?.sort{it.id}
		return rtn
	}

	/**
	 * A unique shortcode used for referencing the provided provider provision type. Make sure this is going to be unique as any data
	 * that is seeded or generated related to this provider will reference it by this code.
	 * @return short code string that should be unique across all other plugin implementations.
	 */
	@Override
	String getProvisionTypeCode() {
		return "amazon"
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
		return "vm"
	}

	@Override
	String[] getDiskNameList() {
		return ['xvda', 'xvdb', 'xvdc', 'xvdd', 'xvde', 'xvdf', 'xvdg', 'xvdh', 'xvdi', 'xvdj', 'xvdk', 'xvdl'] as String[]
	}
}