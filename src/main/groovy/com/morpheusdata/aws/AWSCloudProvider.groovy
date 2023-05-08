package com.morpheusdata.aws

import com.morpheusdata.aws.sync.AlarmSync
import com.morpheusdata.aws.sync.AlbSync
import com.morpheusdata.aws.sync.DbSubnetGroupSync
import com.morpheusdata.aws.sync.EgressOnlyInternetGatewaySync
import com.morpheusdata.aws.sync.ElbSync
import com.morpheusdata.aws.sync.IAMRoleSync
import com.morpheusdata.aws.sync.InstanceProfileSync
import com.morpheusdata.aws.sync.InternetGatewaySync
import com.morpheusdata.aws.sync.NATGatewaySync
import com.morpheusdata.aws.sync.NetworkInterfaceSync
import com.morpheusdata.aws.sync.RegionSync
import com.morpheusdata.aws.sync.SubnetSync
import com.morpheusdata.aws.sync.TransitGatewaySync
import com.morpheusdata.aws.sync.TransitGatewayVpcAttachmentSync
import com.morpheusdata.aws.sync.VPCSync
import com.morpheusdata.aws.sync.VpcPeeringConnectionSync
import com.morpheusdata.aws.utils.AmazonComputeUtility
import com.morpheusdata.core.backup.AbstractBackupProvider
import com.morpheusdata.core.CloudProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.ProvisioningProvider
import com.morpheusdata.core.util.ConnectionUtils
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.model.AccountCredential
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerType
import com.morpheusdata.model.Icon
import com.morpheusdata.model.NetworkProxy
import com.morpheusdata.model.NetworkSubnetType
import com.morpheusdata.model.NetworkType
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.PlatformType
import com.morpheusdata.model.StorageControllerType
import com.morpheusdata.model.StorageVolumeType
import com.morpheusdata.request.ValidateCloudRequest
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j

@Slf4j
class AWSCloudProvider implements CloudProvider {

	AWSPlugin plugin
	MorpheusContext morpheusContext

	AWSCloudProvider(AWSPlugin plugin, MorpheusContext morpheusContext) {
		this.plugin = plugin
		this.morpheusContext = morpheusContext
	}

	@Override
	Collection<OptionType> getOptionTypes() {
		OptionType apiUrl = new OptionType(
				name: 'Api Url',
				code: 'nutanix-prism-api-url',
				fieldName: 'serviceUrl',
				displayOrder: 0,
				fieldLabel: 'Api Url',
				required: true,
				inputType: OptionType.InputType.TEXT,
				fieldContext: 'domain'
		)
		OptionType credentials = new OptionType(
				code: 'nutanix-prism-credential',
				inputType: OptionType.InputType.CREDENTIAL,
				name: 'Credentials',
				fieldName: 'type',
				fieldLabel: 'Credentials',
				fieldContext: 'credential',
				required: true,
				defaultValue: 'local',
				displayOrder: 10,
				optionSource: 'credentials',
				config: '{"credentialTypes":["username-password"]}'
		)
		OptionType username = new OptionType(
				name: 'Username',
				code: 'nutanix-prism-username',
				fieldName: 'serviceUsername',
				displayOrder: 20,
				fieldLabel: 'Username',
				required: true,
				inputType: OptionType.InputType.TEXT,
				fieldContext: 'domain',
				localCredential: true
		)
		OptionType password = new OptionType(
				name: 'Password',
				code: 'nutanix-prism-password',
				fieldName: 'servicePassword',
				displayOrder: 25,
				fieldLabel: 'Password',
				required: true,
				inputType: OptionType.InputType.PASSWORD,
				fieldContext: 'domain',
				localCredential: true
		)

		OptionType inventoryInstances = new OptionType(
				name: 'Inventory Existing Instances',
				code: 'nutanix-prism-import-existing',
				fieldName: 'importExisting',
				displayOrder: 90,
				fieldLabel: 'Inventory Existing Instances',
				required: false,
				inputType: OptionType.InputType.CHECKBOX,
				fieldContext: 'config'
		)

		OptionType enableVnc = new OptionType(
				name: 'Enable Hypervisor Console',
				code: 'nutanix-prism-enableVnc',
				fieldName: 'enableVnc',
				displayOrder: 91,
				fieldLabel: 'Enable Hypervisor Console',
				required: false,
				inputType: OptionType.InputType.CHECKBOX,
				fieldContext: 'config'
		)

		[apiUrl, credentials, username, password, inventoryInstances, enableVnc]
	}

	@Override
	Collection<ComputeServerType> getComputeServerTypes() {
		
		ComputeServerType unmanaged = new ComputeServerType()
		unmanaged.name = 'Amazon Instance'
		unmanaged.code = 'amazonUnmanaged'
		unmanaged.description = 'Amazon Instance'
		unmanaged.reconfigureSupported = true
		unmanaged.hasAutomation = false
		unmanaged.supportsConsoleKeymap = false
		unmanaged.platform = PlatformType.none
		unmanaged.managed = false
		unmanaged.provisionTypeCode = 'amazon'

		ComputeServerType dockerType = new ComputeServerType()
		dockerType.name = 'Amazon Docker Host'
		dockerType.code = 'amazonLinux'
		dockerType.description = 'Amazon Docker Host'
		dockerType.reconfigureSupported = true
		dockerType.hasAutomation = true
		dockerType.supportsConsoleKeymap = false
		dockerType.platform = PlatformType.linux
		dockerType.managed = true
		dockerType.provisionTypeCode = 'amazon'

		ComputeServerType vmType = new ComputeServerType()
		vmType.name = 'Amazon Instance'
		vmType.code = 'amazonVm'
		vmType.description = 'Amazon Instance'
		vmType.reconfigureSupported = true
		vmType.hasAutomation = true
		vmType.supportsConsoleKeymap = false
		vmType.platform = PlatformType.linux
		vmType.managed = true
		vmType.provisionTypeCode = 'amazon'

		ComputeServerType windwsVmType = new ComputeServerType()
		windwsVmType.name = 'Amazon Windows Instance'
		windwsVmType.code = 'amazonWindowsVm'
		windwsVmType.description = 'Amazon Windows Instance'
		windwsVmType.reconfigureSupported = true
		windwsVmType.hasAutomation = true
		windwsVmType.supportsConsoleKeymap = false
		windwsVmType.platform = PlatformType.windows
		windwsVmType.managed = true
		windwsVmType.provisionTypeCode = 'amazon'

		[unmanaged, dockerType,vmType,windwsVmType] //TODO: More types for RDS and K8s
	}

	@Override
	Collection<ProvisioningProvider> getAvailableProvisioningProviders() {
		return plugin.getProvidersByType(ProvisioningProvider) as Collection<ProvisioningProvider>
	}

	@Override
	Collection<AbstractBackupProvider> getAvailableBackupProviders() {
		return null
	}

	@Override
	ProvisioningProvider getProvisioningProvider(String providerCode) {
		return getAvailableProvisioningProviders().find { it.code == providerCode }
	}

	@Override
	Collection<NetworkType> getNetworkTypes() {
		//this is already handled in AWSNetworkProvider#getNetworkTypes()
		return null
	}

	@Override
	Collection<NetworkSubnetType> getSubnetTypes() {
		return null
	}

	@Override
	Collection<StorageVolumeType> getStorageVolumeTypes() {
		def volumeTypes = []
		volumeTypes << new StorageVolumeType([
				code: 'amazon-gp3',
				name: 'gp3',
				defaultType: true,
				displayOrder: 0
		])

		volumeTypes << new StorageVolumeType([
				code: 'amazon-gp2',
				name: 'gp2',
				displayOrder: 1
		])

		volumeTypes << new StorageVolumeType([
				code: 'amazon-io1',
				name: 'io1',
				configurableIOPS:true,
				minIOPS:100, 
				maxIOPS:20000,
				displayOrder: 2
		])

		volumeTypes << new StorageVolumeType([
				code: 'amazon-st1',
				name: 'st1',
				displayOrder: 3
		])

		volumeTypes << new StorageVolumeType([
				code: 'amazon-sc1',
				name: 'sc1',
				displayOrder: 4
		])

		volumeTypes << new StorageVolumeType([
				code: 'amazon-standard',
				name: 'standard'
		])

		volumeTypes
	}

	@Override
	Collection<StorageControllerType> getStorageControllerTypes() {
		return null
	}

	@Override
	ServiceResponse validate(Cloud cloudInfo, ValidateCloudRequest validateCloudRequest) {
		log.info("validate: {}", cloudInfo)
		try {
			if(cloudInfo) {
				def username
				def password
				if(validateCloudRequest.credentialType?.toString().isNumber()) {
					AccountCredential accountCredential = morpheus.accountCredential.get(validateCloudRequest.credentialType.toLong()).blockingGet()
					password = accountCredential.data.password
					username = accountCredential.data.username
				} else if(validateCloudRequest.credentialType == 'username-password') {
					password = validateCloudRequest.credentialPassword ?: cloudInfo.servicePassword
					username = validateCloudRequest.credentialUsername ?: cloudInfo.serviceUsername
				} else if(validateCloudRequest.credentialType == 'local') {
					if(validateCloudRequest.opts?.zone?.servicePassword && validateCloudRequest.opts?.zone?.servicePassword != '************') {
						password = validateCloudRequest.opts?.zone?.servicePassword
					} else {
						password = cloudInfo.servicePassword
					}
					username = validateCloudRequest.opts?.zone?.serviceUsername ?: cloudInfo.serviceUsername
				}

				if(username?.length() < 1) {
					return new ServiceResponse(success: false, msg: 'Enter a username')
				} else if(password?.length() < 1) {
					return new ServiceResponse(success: false, msg: 'Enter a password')
				} else if(cloudInfo.serviceUrl?.length() < 1) {
					return new ServiceResponse(success: false, msg: 'Enter an api url')
				} else {
					//test api call
					def apiUrl = plugin.getApiUrl(cloudInfo.serviceUrl)
					//get creds
					Map authConfig = [apiUrl: apiUrl, basePath: 'api/nutanix/v3', v2basePath: 'api/nutanix/v2.0', username: username, password: password]
					HttpApiClient apiClient = new HttpApiClient()
					def clusterList = NutanixPrismComputeUtility.listHostsV2(apiClient, authConfig)
					if(clusterList.success == true) {
						return ServiceResponse.success()
					} else {
						return new ServiceResponse(success: false, msg: 'Invalid credentials')
					}
				}
			} else {
				return new ServiceResponse(success: false, msg: 'No cloud found')
			}
		} catch(e) {
			log.error('Error validating cloud', e)
			return new ServiceResponse(success: false, msg: 'Error validating cloud')
		}
	}

	@Override
	ServiceResponse refresh(Cloud cloudInfo) {
		initializeCloud(cloudInfo)
	}

	@Override
	void refreshDaily(Cloud cloudInfo) {
		//nothing daily
	}

	@Override
	ServiceResponse deleteCloud(Cloud cloudInfo) {
		return new ServiceResponse(success: true)
	}

	@Override
	Boolean hasComputeZonePools() {
		return true
	}

	@Override
	Boolean hasNetworks() {
		return true
	}

	@Override
	Boolean hasFolders() {
		return false
	}

	@Override
	Boolean hasDatastores() {
		return false
	}

	@Override
	Boolean hasBareMetal() {
		return false
	}

	@Override
	MorpheusContext getMorpheus() {
		return this.morpheusContext
	}

	@Override
	Plugin getPlugin() {
		return this.plugin
	}

	@Override
	String getCode() {
		return 'amazon'
	}

	@Override
	Icon getIcon() {
		return new Icon(path:"nutanix-prism.svg", darkPath: "nutanix-prism-dark.svg")
	}

	@Override
	Icon getCircularIcon() {
		return new Icon(path:"nutanix-prism-plugin-circular.svg", darkPath: "nutanix-prism-plugin-circular-dark.svg")
	}

	@Override
	String getName() {
		return 'Amazon'
	}

	@Override
	String getDescription() {
		return 'AWS Cloud'
	}

	@Override
	ServiceResponse startServer(ComputeServer computeServer) {
		log.debug("startServer: ${computeServer}")
		def rtn = [success:false]
		try {
			return nutanixPrismProvisionProvider().startServer(computeServer)
		} catch(e) {
			rtn.msg = "Error starting server: ${e.message}"
			log.error("startServer error: ${e}", e)
		}
		return ServiceResponse.create(rtn)
	}

	@Override
	ServiceResponse stopServer(ComputeServer computeServer) {
		log.debug("stopServer: ${computeServer}")
		def rtn = [success:false]
		try {
			return nutanixPrismProvisionProvider().stopServer(computeServer)
		} catch(e) {
			rtn.msg = "Error stoping server: ${e.message}"
			log.error("stopServer error: ${e}", e)
		}
		return ServiceResponse.create(rtn)
	}

	@Override
	ServiceResponse deleteServer(ComputeServer computeServer) {
		log.debug("deleteServer: ${computeServer}")
		def rtn = [success:false]
		try {
			return nutanixPrismProvisionProvider().deleteServer(computeServer)
		} catch(e) {
			rtn.msg = "Error deleting server: ${e.message}"
			log.error("deleteServer error: ${e}", e)
		}
		return ServiceResponse.create(rtn)
	}

	@Override
	Boolean hasCloudInit() {
		return true
	}

	@Override
	Boolean supportsDistributedWorker() {
		return false
	}

	@Override
	ServiceResponse initializeCloud(Cloud cloud) {
		ServiceResponse rtn = new ServiceResponse(success: false)
		log.info "Initializing Cloud: ${cloud.code}"
		log.info "config: ${cloud.configMap}"

		try {


			def authConfig = plugin.getAuthConfig(cloud)
			def apiUrlObj = new URL(authConfig.apiUrl)
			def apiHost = apiUrlObj.getHost()
			def apiPort = apiUrlObj.getPort() > 0 ? apiUrlObj.getPort() : (apiUrlObj?.getProtocol()?.toLowerCase() == 'https' ? 443 : 80)
			def hostOnline = ConnectionUtils.testHostConnectivity(apiHost, apiPort, true, true, proxySettings)
			if(hostOnline) {
				def testResults = AmazonComputeUtility.testConnection(client, authConfig)
				if(testResults.success == true) {
					def doInventory = cloud.getConfigProperty('importExisting')
					Boolean createNew = false
					if(doInventory == 'on' || doInventory == 'true' || doInventory == true) {
						createNew = true
					}
					new RegionSync(this.plugin,cloud).execute()
					new VPCSync(this.plugin,cloud).execute()
					new SubnetSync(this.plugin,cloud).execute()
					new InstanceProfileSync(this.plugin,cloud).execute()
					new IAMRoleSync(this.plugin,cloud).execute()
					new InternetGatewaySync(this.plugin,cloud).execute()
					//lb services
					new AlbSync(this.plugin,cloud).execute()
					new ElbSync(this.plugin,cloud).execute()
					//resources
					new EgressOnlyInternetGatewaySync(this.plugin,cloud).execute()
					new NATGatewaySync(this.plugin,cloud).execute()
					new TransitGatewaySync(this.plugin,cloud).execute()
					new TransitGatewayVpcAttachmentSync(this.plugin,cloud).execute()
					new NetworkInterfaceSync(this.plugin,cloud).execute()
					new VpcPeeringConnectionSync(this.plugin,cloud).execute()
					//rds services
					new DbSubnetGroupSync(this.plugin,cloud).execute()
					new AlarmSync(this.plugin,cloud).execute()
					rtn = ServiceResponse.success()

				}
				else {
					rtn = ServiceResponse.error(testResults.invalidLogin == true ? 'invalid credentials' : 'error connecting')
				}
			} else {
				rtn = ServiceResponse.error('Nutanix Prism Central is not reachable', null, [status: Cloud.Status.offline])
			}
		} catch (e) {
			log.error("refresh cloud error: ${e}", e)
		}

		return rtn
	}



}
