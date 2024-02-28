package com.morpheusdata.aws

import com.morpheusdata.aws.sync.AlarmSync
import com.morpheusdata.aws.sync.AlbSync
import com.morpheusdata.aws.sync.AvailabilityZoneSync
import com.morpheusdata.aws.sync.CloudSync
import com.morpheusdata.aws.sync.DbSubnetGroupSync
import com.morpheusdata.aws.sync.EgressOnlyInternetGatewaySync
import com.morpheusdata.aws.sync.ElbSync
import com.morpheusdata.aws.sync.IAMRoleSync
import com.morpheusdata.aws.sync.ImageSync
import com.morpheusdata.aws.sync.InstanceProfileSync
import com.morpheusdata.aws.sync.InternetGatewaySync
import com.morpheusdata.aws.sync.KeyPairSync
import com.morpheusdata.aws.sync.NATGatewaySync
import com.morpheusdata.aws.sync.NetworkInterfaceSync
import com.morpheusdata.aws.sync.PriceSync
import com.morpheusdata.aws.sync.RegionSync
import com.morpheusdata.aws.sync.RouteTableSync
import com.morpheusdata.aws.sync.ScaleGroupSync
import com.morpheusdata.aws.sync.ScaleGroupVirtualMachinesSync
import com.morpheusdata.aws.sync.SecurityGroupSync
import com.morpheusdata.aws.sync.ServicePlanSync
import com.morpheusdata.aws.sync.SnapshotSync
import com.morpheusdata.aws.sync.SubnetSync
import com.morpheusdata.aws.sync.TransitGatewaySync
import com.morpheusdata.aws.sync.TransitGatewayVpcAttachmentSync
import com.morpheusdata.aws.sync.VPCRouterSync
import com.morpheusdata.aws.sync.VPCSync
import com.morpheusdata.aws.sync.VirtualMachineSync
import com.morpheusdata.aws.sync.VolumeSync
import com.morpheusdata.aws.sync.VpcPeeringConnectionSync
import com.morpheusdata.aws.sync.VpnGatewaySync
import com.morpheusdata.aws.utils.AmazonComputeUtility
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.providers.IacResourceMappingProvider
import com.morpheusdata.core.util.ComputeUtility
import com.morpheusdata.core.backup.AbstractBackupProvider
import com.morpheusdata.core.providers.CloudProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.providers.CloudCostingProvider
import com.morpheusdata.core.providers.ProvisionProvider
import com.morpheusdata.core.util.ConnectionUtils
import com.morpheusdata.model.Account
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.CloudPool
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerType
import com.morpheusdata.model.Icon
import com.morpheusdata.model.NetworkProxy
import com.morpheusdata.model.NetworkRouter
import com.morpheusdata.model.NetworkRouterType
import com.morpheusdata.model.NetworkServer
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

	public static final String PROVIDER_CODE = 'amazon'

	AWSPlugin plugin
	MorpheusContext morpheusContext

	AWSCloudProvider(AWSPlugin plugin, MorpheusContext morpheusContext) {
		this.plugin = plugin
		this.morpheusContext = morpheusContext
	}

	@Override
	CloudCostingProvider getCloudCostingProvider() { return new AWSCloudCostingProvider(plugin,morpheusContext) };

	@Override
	Collection<OptionType> getOptionTypes() {
		def displayOrder = 0
		OptionType apiUrl = new OptionType(
			name: 'Region',
			code: 'aws-plugin-endpoint',
			displayOrder: displayOrder,
			fieldContext: 'config',
			fieldLabel: 'Region',
			fieldCode: 'gomorpheus.optiontype.Region',
			fieldName: 'endpoint',
			inputType: OptionType.InputType.SELECT,
			optionSource: 'awsPluginAllRegions',
			required: true
		)
		OptionType credentials = new OptionType(
			code: 'aws-plugin-credential',
			inputType: OptionType.InputType.CREDENTIAL,
			name: 'Credentials',
			fieldContext: 'credential',
			fieldLabel: 'Credentials',
			fieldCode:'gomorpheus.label.credentials',
			fieldName: 'type',
			required: true,
			defaultValue: 'local',
			displayOrder: displayOrder += 10,
			optionSource: 'credentials',
			config: '{"credentialTypes":["access-key-secret"]}'
		)
		OptionType accessKey = new OptionType(
			name: 'Access Key',
			code: 'aws-plugin-access-key',
			displayOrder: displayOrder += 10,
			fieldContext: 'config',
			fieldLabel: 'Access Key',
			fieldCode: 'gomorpheus.optiontype.AccessKey',
			fieldName: 'accessKey',
			inputType: OptionType.InputType.TEXT,
			localCredential: true,
			required: true
		)
		OptionType secretKey = new OptionType(
			name: 'Secret Key',
			code: 'aws-plugin-secret-key',
			displayOrder: displayOrder += 10,
			fieldContext: 'config',
			fieldLabel: 'Secret Key',
			fieldCode: 'gomorpheus.optiontype.SecretKey',
			fieldName: 'secretKey',
			inputType: OptionType.InputType.PASSWORD,
			localCredential: true,
			required: true
		)
		OptionType useHostCreds = new OptionType(
			name: 'Use Host IAM Credentials',
			code: 'aws-plugin-use-host-creds',
			displayOrder: displayOrder += 10,
			fieldContext: 'config',
			fieldLabel: 'Use Host IAM Credentials',
			fieldCode: 'gomorpheus.label.useHostCredentials',
			fieldName: 'useHostCredentials',
			inputType: OptionType.InputType.CHECKBOX,
			required: true
		)
		OptionType roleArn = new OptionType(
			name: 'Role ARN',
			code: 'aws-plugin-role-arn',
			displayOrder: displayOrder += 10,
			fieldContext: 'config',
			fieldLabel: 'Role ARN',
			fieldCode: 'gomorpheus.label.stsAssumeRole',
			fieldName: 'stsAssumeRole',
			inputType: OptionType.InputType.TEXT,
		)
		OptionType externalId = new OptionType(
			name: 'External ID',
			code: 'aws-plugin-external-id',
			displayOrder: displayOrder += 10,
			fieldContext: 'config',
			fieldLabel: 'External ID',
			fieldCode: 'gomorpheus.label.externalId',
			fieldName: 'stsExternalId',
			inputType: OptionType.InputType.TEXT,
		)
		OptionType inventoryLevel = new OptionType(
			name: 'Inventory',
			code: 'aws-plugin-inventory-level',
			displayOrder: displayOrder += 10,
			fieldContext: 'domain',
			fieldLabel: 'Inventory',
			fieldCode: 'gomorpheus.label.inventory',
			fieldName: 'inventoryLevel',
			inputType: OptionType.InputType.SELECT,
			optionSource:'awsPluginInventoryLevels',
			defaultValue: 'off'
		)
		OptionType vpc = new OptionType(
			name: 'VPC',
			code: 'aws-plugin-vpc',
			displayOrder: displayOrder += 10,
			fieldContext: 'config',
			fieldLabel: 'VPC',
			fieldCode: 'gomorpheus.optiontype.Vpc',
			fieldName: 'vpc',
			inputType: OptionType.InputType.SELECT,
			optionSource: 'awsPluginVpc',
			noBlank: true,
			dependsOnCode: 'config.endpoint, endpoint, config.accessKey, accessKey, config.secretKey, secretKey, credential.type, credential.username, credential.password'
		)
		OptionType imageTransferStore = new OptionType(
			name: 'Image Transfer Store',
			code: 'aws-plugin-image-xfer-store',
			displayOrder: displayOrder += 10,
			fieldContext: 'config',
			fieldLabel: 'Image Transfer Store',
			fieldCode: 'gomorpheus.label.imageTransferStore',
			fieldName: 'imageStoreId',
			fieldGroup: 'Advanced',
			inputType: OptionType.InputType.SELECT,
			optionSource: 'awsPluginStorageProvider'
		)
		OptionType ebsEncrytion = new OptionType(
			name: 'EBS Encryption',
			code: 'aws-plugin-ebs-encryption',
			displayOrder: displayOrder += 10,
			fieldContext: 'config',
			fieldLabel: 'EBS Encryption',
			fieldCode: 'gomorpheus.label.ebsEncryption',
			fieldName: 'ebsEncryption',
			fieldGroup: 'Advanced',
			inputType: OptionType.InputType.SELECT,
			optionSource: 'awsPluginEbsEncryption',
			noBlank:true
		)
		OptionType costingReport = new OptionType(
			name: 'Costing Report',
			code: 'aws-plugin-costing-report',
			displayOrder: displayOrder += 10,
			fieldContext: 'config',
			fieldLabel: 'Costing Report',
			fieldName: 'costingReport',
			fieldGroup: 'Advanced',
			inputType: OptionType.InputType.SELECT,
			optionSource: 'awsPluginCostingReports',
			visibleOnCode: 'config.vpc:^(?!\\-1)',
			dependsOnCode: 'config.endpoint, endpoint, config.accessKey, accessKey, config.secretKey, secretKey, credential, credential.type'
		)
		OptionType costingReportName = new OptionType(
			name: 'Costing Report Name',
			code: 'aws-plugin-costing-report-name',
			displayOrder: displayOrder += 10,
			fieldContext: 'config',
			fieldLabel: 'Costing Report Name',
			fieldName: 'costingReportName',
			fieldGroup: 'Advanced',
			inputType: OptionType.InputType.TEXT,
			visibleOnCode: 'config.costingReport:create-report',
			required: true
		)
		OptionType costingFolder = new OptionType(
			name: 'Costing Folder',
			code: 'aws-plugin-costing-folder',
			displayOrder: displayOrder += 10,
			fieldContext: 'config',
			fieldLabel: 'Costing Folder',
			fieldName: 'costingFolder',
			fieldGroup: 'Advanced',
			inputType: OptionType.InputType.TEXT,
			visibleOnCode: 'config.costingReport:create-report',
			required: true
		)
		OptionType costingBucket = new OptionType(
			name: 'Costing Bucket',
			code: 'aws-plugin-costing-bucket',
			displayOrder: displayOrder += 10,
			fieldContext: 'config',
			fieldLabel: 'Costing Bucket',
			fieldName: 'costingBucket',
			fieldGroup: 'Advanced',
			inputType: OptionType.InputType.SELECT,
			optionSource: 'awsPluginCostingBuckets',
			visibleOnCode: 'config.costingReport:create-report',
			dependsOnCode: 'config.endpoint, endpoint, config.accessKey, accessKey, config.secretKey, secretKey, credential, credential.type',
			required: true
		)
		OptionType costingBucketName = new OptionType(
			name: 'Costing Bucket Name',
			code: 'aws-plugin-costing-bucket-name',
			displayOrder: displayOrder += 10,
			fieldContext: 'config',
			fieldLabel: 'Costing Bucket Name',
			fieldName: 'costingBucketName',
			fieldGroup: 'Advanced',
			inputType: OptionType.InputType.TEXT,
			visibleOnCode: 'matchAll::config.costingReport:create-report,config.costingBucket:create-bucket',
			required: true
		)
		OptionType costingBucketRegion = new OptionType(
			name: 'Costing Bucket Region',
			code: 'aws-plugin-costing-bucket-region',
			displayOrder: displayOrder += 10,
			fieldContext: 'config',
			fieldLabel: 'Costing Bucket Region',
			fieldName: 'costingRegion',
			fieldGroup: 'Advanced',
			inputType: OptionType.InputType.SELECT,
			optionSource: 'awsPluginRegions',
			visibleOnCode: 'matchAll::config.costingReport:create-report,config.costingBucket:create-bucket'
		)
		OptionType costingKey = new OptionType(
			name: 'Costing Key',
			code: 'aws-plugin-costing-key',
			displayOrder: displayOrder += 10,
			fieldContext: 'config',
			fieldLabel: 'Costing Key',
			fieldCode: 'gomorpheus.amazon.cloud.costingKey',
			fieldName: 'costingAccessKey',
			fieldGroup: 'Advanced',
			inputType: OptionType.InputType.TEXT
		)
		OptionType costingSecret = new OptionType(
			name: 'Costing Secret',
			code: 'aws-plugin-costing-secret',
			displayOrder: displayOrder += 10,
			fieldContext: 'config',
			fieldLabel: 'Costing Secret',
			fieldCode: 'gomorpheus.amazon.cloud.costingSecret',
			fieldName: 'costingSecretKey',
			fieldGroup: 'Advanced',
			inputType: OptionType.InputType.PASSWORD
		)
		OptionType linkedAccount = new OptionType(
			name: 'Linked Account ID',
			code: 'aws-plugin-linked-account',
			displayOrder: displayOrder += 10,
			fieldContext: 'domain',
			fieldLabel: 'Linked Account ID',
			fieldCode: 'gomorpheus.label.linkedAccountId',
			fieldName: 'linkedAccountId',
			fieldGroup: 'Advanced',
			inputType: OptionType.InputType.TEXT
		)
		[
			apiUrl, credentials, accessKey, secretKey, useHostCreds, roleArn, externalId, inventoryLevel,
			vpc, imageTransferStore, ebsEncrytion, costingReport, costingReportName, costingFolder,
			costingBucket, costingBucketName, costingBucketRegion, costingKey, costingSecret, linkedAccount
		]
	}

	@Override
	Collection<ComputeServerType> getComputeServerTypes() {
		def options = []

		options << new OptionType([
				name : 'publicIP',
				code : 'amazon-ec2-provision-public-id',
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

		ComputeServerType unmanaged = new ComputeServerType()
		unmanaged.name = 'Amazon Instance'
		unmanaged.code = 'amazonUnmanaged'
		unmanaged.description = 'Amazon Instance'
		unmanaged.reconfigureSupported = false
		unmanaged.hasAutomation = false
		unmanaged.supportsConsoleKeymap = false
		unmanaged.platform = PlatformType.none
		unmanaged.managed = false
		unmanaged.provisionTypeCode = 'amazon'
		unmanaged.guestVm = true

		ComputeServerType unmanagedWindows = new ComputeServerType()
		unmanagedWindows.name = 'Amazon Windows Node'
		unmanagedWindows.code = 'amazonWindows'
		unmanagedWindows.description = 'Amazon Instance'
		unmanagedWindows.platform = PlatformType.windows
		unmanagedWindows.nodeType = 'morpheus-windows-node'
		unmanagedWindows.enabled = true
		unmanagedWindows.selectable = false
		unmanagedWindows.externalDelete = true
		unmanagedWindows.managed = false
		unmanagedWindows.controlPower = true
		unmanagedWindows.controlSuspend = false
		unmanagedWindows.displayOrder = 1
		unmanagedWindows.hasAutomation = true
		unmanagedWindows.reconfigureSupported = false
		unmanagedWindows.containerHypervisor = false
		unmanagedWindows.bareMetalHost = false
		unmanagedWindows.vmHypervisor = false
		unmanagedWindows.agentType = ComputeServerType.AgentType.node
		unmanagedWindows.guestVm = true
		unmanagedWindows.supportsConsoleKeymap = false
		unmanagedWindows.provisionTypeCode = 'amazon'
		unmanagedWindows.guestVm = true

		ComputeServerType dockerType = new ComputeServerType()
		dockerType.name = 'Amazon Docker Host'
		dockerType.code = 'amazonLinux'
		dockerType.description = 'Amazon Docker Host'
		dockerType.nodeType = 'morpheus-node'
		dockerType.reconfigureSupported = true
		dockerType.hasAutomation = true
		dockerType.supportsConsoleKeymap = false
		dockerType.platform = PlatformType.linux
		dockerType.managed = true
		dockerType.provisionTypeCode = 'amazon'
		dockerType.optionTypes = options
		dockerType.agentType = ComputeServerType.AgentType.node
		dockerType.containerHypervisor = true
		dockerType.containerEngine = ComputeServerType.ContainerEngine.docker
		dockerType.computeTypeCode = 'docker-host'

		ComputeServerType vmType = new ComputeServerType()
		vmType.name = 'Amazon Instance'
		vmType.code = 'amazonVm'
		vmType.nodeType = 'morpheus-vm-node'
		vmType.description = 'Amazon Instance'
		vmType.reconfigureSupported = true
		vmType.hasAutomation = true
		vmType.supportsConsoleKeymap = false
		vmType.platform = PlatformType.linux
		vmType.managed = true
		vmType.provisionTypeCode = 'amazon'
		vmType.guestVm = true

		ComputeServerType windwsVmType = new ComputeServerType()
		windwsVmType.name = 'Amazon Windows Instance'
		windwsVmType.code = 'amazonWindowsVm'
		windwsVmType.nodeType = 'morpheus-windows-vm-node'
		windwsVmType.description = 'Amazon Windows Instance'
		windwsVmType.reconfigureSupported = true
		windwsVmType.hasAutomation = true
		windwsVmType.supportsConsoleKeymap = false
		windwsVmType.platform = PlatformType.windows
		windwsVmType.managed = true
		windwsVmType.provisionTypeCode = 'amazon'
		windwsVmType.guestVm = true

		[unmanaged, unmanagedWindows, dockerType, vmType, windwsVmType] //TODO: More types for RDS and K8s
	}

	@Override
	Collection<ProvisionProvider> getAvailableProvisionProviders() {
		return plugin.getProvidersByType(ProvisionProvider) as Collection<com.morpheusdata.core.ProvisionProvider>
	}

	@Override
	String getDefaultProvisionTypeCode() {
		return 'amazon-ec2-provision-provider'
	}

	@Override
	Collection<AbstractBackupProvider> getAvailableBackupProviders() {
		return null
	}

	@Override
	ProvisionProvider getProvisionProvider(String providerCode) {
		return getAvailableProvisionProviders().find { it.code == providerCode }
	}

	@Override
	Collection<NetworkType> getNetworkTypes() {
		plugin.getNetworkProvider().getNetworkTypes() // so the zone types associate with the network types??
	}

	@Override
	Collection<NetworkSubnetType> getSubnetTypes() {
		// plugin.getNetworkProvider().getSubnetTypes() // so the zone types associate with the subnet types?? Network Provider doesn't have this method yet.
		return null
	}

	@Override
	Collection<StorageVolumeType> getStorageVolumeTypes() {
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
	Collection<StorageControllerType> getStorageControllerTypes() {
		return null
	}

	@Override
	ServiceResponse validate(Cloud cloudInfo, ValidateCloudRequest validateCloudRequest) {
		log.info("validate: {}", cloudInfo)
		try {
			if(cloudInfo) {
				def config = cloudInfo.getConfigMap()
				def useHostCredentials = config.useHostCredentials in [true, 'true', 'on']
				def username, password

				if(config.endpoint == 'global') {
					cloudInfo.regionCode = 'global'
					//no more verification necessary this is a cost aggregator cloud only, disable cloud
					return ServiceResponse.success()
				}

				if(!useHostCredentials) {
					if(validateCloudRequest.credentialType?.toString().isNumber() || validateCloudRequest.credentialType == 'access-key-secret') {
						username = validateCloudRequest.credentialUsername
						password = validateCloudRequest.credentialPassword

						if(!username) {
							return new ServiceResponse(success: false, msg: 'Enter an access key', errors: ['credential.username': 'Required field'])
						}
						if(!password) {
							return new ServiceResponse(success: false, msg: 'Enter a secret key', errors: ['credential.password': 'Required field'])
						}
					}
					if(validateCloudRequest.credentialType == 'local') {
						username = config.accessKey
						password = config.secretKey

						if(!username) {
							return new ServiceResponse(success: false, msg: 'Enter an access key', errors: ['accessKey': 'Required field'])
						}
						if(!password) {
							return new ServiceResponse(success: false, msg: 'Enter a secret key', errors: ['secretKey': 'Required field'])
						}
					}
				}

				//test creds
				cloudInfo.accountCredentialData = [username: username, password: password]
				def testResults = AmazonComputeUtility.testConnection(cloudInfo)
				if(!testResults.success) {
					if (testResults.invalidLogin) {
						return new ServiceResponse(success: false, msg: 'Invalid amazon credentials')
					} else {
						return new ServiceResponse(success: false, msg: 'Unknown error connecting to amazon')
					}
				}
				return ServiceResponse.success()
			} else {
				return new ServiceResponse(success: false, msg: 'No cloud found')
			}
		} catch(e) {
			log.error('Error validating cloud', e)
			return new ServiceResponse(success: false, msg: 'Error validating cloud')
		}
	}

	@Override
	void refreshDailyCloudType() {
		log.info("Refreshing Daily Cloud Type")
		new ServicePlanSync(this.plugin).execute()
		new PriceSync(this.plugin).execute()
	}

	@Override
	ServiceResponse refresh(Cloud cloudInfo) {
		ServiceResponse rtn = new ServiceResponse(success: false)

		try {
			def testResults = AmazonComputeUtility.testConnection(cloudInfo)
			if(testResults.success) {
				def now = new Date().time
				new CloudSync(this.plugin,cloudInfo).execute()
				log.info("${cloudInfo.name}: Cloud Synced in ${new Date().time - now}ms")
				now = new Date().time
				new RegionSync(this.plugin,cloudInfo).execute()
				log.info("${cloudInfo.name}: Region Synced in ${new Date().time - now}ms")
				now = new Date().time
				new KeyPairSync(this.plugin,cloudInfo).execute()
				log.info("${cloudInfo.name}: Keypair Synced in ${new Date().time - now}ms")
				now = new Date().time
				new AvailabilityZoneSync(this.plugin,cloudInfo).execute()
				log.info("${cloudInfo.name}: Availability Zone Synced in ${new Date().time - now}ms")
				now = new Date().time
				new VPCSync(this.plugin,cloudInfo).execute()
				log.info("${cloudInfo.name}: VPC Synced in ${new Date().time - now}ms")
				now = new Date().time
				new VPCRouterSync(this.plugin,cloudInfo).execute()
				log.info("${cloudInfo.name}: VPC Router Synced in ${new Date().time - now}ms")
				now = new Date().time
				new RouteTableSync(this.plugin,cloudInfo).execute()
				log.info("${cloudInfo.name}: Route Table Synced in ${new Date().time - now}ms")
				now = new Date().time
				new SubnetSync(this.plugin,cloudInfo).execute()
				log.info("${cloudInfo.name}: Subnet Synced in ${new Date().time - now}ms")
				now = new Date().time
				new ImageSync(this.plugin,cloudInfo).execute()
				log.info("${cloudInfo.name}: Image Synced in ${new Date().time - now}ms")
				now = new Date().time
				new SecurityGroupSync(this.plugin, cloudInfo).execute()
				log.info("${cloudInfo.name}: Security Group Synced in ${new Date().time - now}ms")
				now = new Date().time
				new InstanceProfileSync(this.plugin,cloudInfo).execute()
				log.info("${cloudInfo.name}: Instance Profile Synced in ${new Date().time - now}ms")
				now = new Date().time
				new IAMRoleSync(this.plugin,cloudInfo).execute()
				log.info("${cloudInfo.name}: IAMRole Synced in ${new Date().time - now}ms")
				now = new Date().time
				new VpnGatewaySync(this.plugin,cloudInfo).execute()
				log.info("${cloudInfo.name}: VPN Gateway Synced in ${new Date().time - now}ms")
				now = new Date().time
				new InternetGatewaySync(this.plugin,cloudInfo).execute()
				log.info("${cloudInfo.name}: Internet Gateway Synced in ${new Date().time - now}ms")
				now = new Date().time
				//lb services
				new AlbSync(this.plugin,cloudInfo).execute()
				log.info("${cloudInfo.name}: ALB Synced in ${new Date().time - now}ms")
				now = new Date().time
				new ElbSync(this.plugin,cloudInfo).execute()
				log.info("${cloudInfo.name}: ELB Synced in ${new Date().time - now}ms")
				now = new Date().time
				//resources
				new EgressOnlyInternetGatewaySync(this.plugin,cloudInfo).execute()
				log.info("${cloudInfo.name}: EgressOnlyInternetGateway Synced in ${new Date().time - now}ms")
				now = new Date().time
				new NATGatewaySync(this.plugin,cloudInfo).execute()
				log.info("${cloudInfo.name}: NAT Gateway Synced in ${new Date().time - now}ms")
				now = new Date().time
				new TransitGatewaySync(this.plugin,cloudInfo).execute()
				log.info("${cloudInfo.name}: Transit Gateway Synced in ${new Date().time - now}ms")
				now = new Date().time
				new TransitGatewayVpcAttachmentSync(this.plugin,cloudInfo).execute()
				log.info("${cloudInfo.name}: Transite Gateway Attachments Synced in ${new Date().time - now}ms")
				now = new Date().time
				new NetworkInterfaceSync(this.plugin,cloudInfo).execute()
				log.info("${cloudInfo.name}: NetworkInterface Synced in ${new Date().time - now}ms")
				now = new Date().time
				new VpcPeeringConnectionSync(this.plugin,cloudInfo).execute()
				log.info("${cloudInfo.name}: VPC Peering Connections Synced in ${new Date().time - now}ms")
				now = new Date().time
				//rds services
				new DbSubnetGroupSync(this.plugin,cloudInfo).execute()
				log.info("${cloudInfo.name}: DBSubnet Groups Synced in ${new Date().time - now}ms")
				now = new Date().time
				new AlarmSync(this.plugin,cloudInfo).execute()
				log.info("${cloudInfo.name}: Alarm Synced in ${new Date().time - now}ms")
				now = new Date().time

				//vms
				new VirtualMachineSync(this.plugin,cloudInfo).execute()
				log.info("${cloudInfo.name}: VirtualMachine Synced in ${new Date().time - now}ms")
				now = new Date().time
				//volumes
				new VolumeSync(this.plugin,cloudInfo).execute()
				log.info("${cloudInfo.name}: Volume Synced in ${new Date().time - now}ms")
				now = new Date().time
				new SnapshotSync(this.plugin,cloudInfo).execute()
				log.info("${cloudInfo.name}: Snapshot Synced in ${new Date().time - now}ms")
				now = new Date().time
				new ScaleGroupSync(this.plugin,cloudInfo).execute()
				log.info("${cloudInfo.name}: ScaleGroup Synced in ${new Date().time - now}ms")
				now = new Date().time
				new ScaleGroupVirtualMachinesSync(this.plugin,cloudInfo).execute()
				log.info("${cloudInfo.name}: ScaleGroup Virtual Machines Synced in ${new Date().time - now}ms")
				now = new Date().time
				rtn = ServiceResponse.success()
			} else {
				rtn = ServiceResponse.error(testResults.invalidLogin == true ? 'invalid credentials' : 'error connecting')
			}
		} catch (e) {
			log.error("refresh cloud error: ${e}", e)
		}
		rtn
	}

	@Override
	void refreshDaily(Cloud cloudInfo) {
		//nothing daily
	}

	@Override
	ServiceResponse deleteCloud(Cloud cloudInfo) {
		ServiceResponse rtn = new ServiceResponse(success: false)
		try {
			// cleanup all the providers associated with this cloud.
			// delete amazon network server, route53 dns integration, s3 storage server
			plugin.getNetworkProvider().deleteProvider(cloudInfo)
			plugin.getDnsProvider().deleteProvider(cloudInfo)
			plugin.getStorageProvider().deleteProvider(cloudInfo)
			
			// todo: anything else to delete? KeyPairs or ReferenceData maybe?
			rtn.success = true
		} catch (e) {
			log.error("delete cloud error: ${e}", e)
		}
		rtn
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
		return PROVIDER_CODE
	}

	@Override
	Icon getIcon() {
		return new Icon(path:"amazon.svg", darkPath: "amazon-dark.svg")
	}

	@Override
	Icon getCircularIcon() {
		return new Icon(path:"amazon-circular.svg", darkPath: "amazon-circular.svg")
	}

	@Override
	String getName() {
		return 'Amazon'
	}

	@Override
	String getDescription() {
		return 'Amazon Cloud'
	}

	@Override
	ServiceResponse startServer(ComputeServer computeServer) {
		log.debug("startServer: ${computeServer}")
		def rtn = [success:false]
		try {
			return ec2ProvisionProvider().startServer(computeServer)
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
			return ec2ProvisionProvider().stopServer(computeServer)
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
			return ec2ProvisionProvider().deleteServer(computeServer)
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
	Boolean canCreateCloudPools() {
		return true
	}

	@Override
	Boolean canDeleteCloudPools() {
		return true
	}

	@Override
	ServiceResponse<CloudPool> createCloudPool(Cloud cloud, CloudPool cloudPool) {
		def rtn = [success:false]
		try {
			if(!cloud.regionCode && !cloudPool.regionCode) {
				rtn.msg = "Region Must be specified in order to create a VPC"
			}
			else {
				def regionCode = AmazonComputeUtility.getAmazonEndpointRegion(cloud.regionCode ?: cloudPool.regionCode)
				def createOpts = [amazonClient: plugin.getAmazonClient(cloud, false, regionCode), name: cloudPool.name, cidr: cloudPool.getConfigProperty('cidrBlock'), tenancy: cloudPool.getConfigProperty('tenancy')]
				def createResults = AmazonComputeUtility.createVpc(createOpts)
				if(createResults.success) {
					cloudPool.regionCode = regionCode
					cloudPool.displayName = "${cloudPool.name} (${regionCode})"
					cloudPool.description = "${cloudPool.name} - ${createResults.externalId} - ${createResults.vpc.cidrBlock}"
					cloudPool.type = 'vpc'
					cloudPool.category = "aws.vpc.${cloud.id}"
					cloudPool.code = "aws.vpc.${cloud.id}.${createResults.externalId}"
					cloudPool.externalId = createResults.externalId
					cloudPool.internalId = createResults.externalId
					cloudPool = morpheusContext.services.cloud.pool.create(cloudPool)

					// Also.. create the network router
					morpheusContext.services.network.router.create(new NetworkRouter(
						owner        : new Account(id: cloud.owner.id),
						category     : "aws.router.${cloud.id}",
						code         : "aws.router.${cloud.id}.${cloudPool.id}",
						type         : new NetworkRouterType(code:'amazonVpcRouter'),
						name         : cloudPool.name,
						networkServer: new NetworkServer(id:cloud.networkServer.id),
						cloud        : new Cloud(id:cloud.id),
						refId        : cloudPool.id,
						poolId		 : cloudPool.id,
						regionCode   : cloudPool.regionCode,
						refType      : 'ComputeZonePool'
					))
					rtn.data = cloudPool
					rtn.success = true
				} else {
					rtn.msg = createResults.msg
				}
			}
		} catch(e) {
			log.error("create cloud pool error: ${e}", e)
		}
		ServiceResponse.create(rtn)
	}

	@Override
	ServiceResponse<CloudPool> removeCloudPool(Cloud cloud, CloudPool cloudPool) {
		def rtn = [success: false]
		try {
			def deleteOpts = [amazonClient: plugin.getAmazonClient(cloud,false, cloud.regionCode ?: cloudPool.regionCode), vpcId: cloudPool.externalId]
			def results = AmazonComputeUtility.deleteVpc(deleteOpts)

			if(results.success) {
				def securityGroups = morpheusContext.services.securityGroup.location.listIdentityProjections(new DataQuery().withFilter('zonePool.id', cloudPool.id))
				morpheusContext.services.securityGroup.location.bulkRemove(securityGroups)
				morpheusContext.services.securityGroup.bulkRemove(securityGroups.collect { it.securityGroup })
				rtn.data = cloudPool
				rtn.success = true
			} else {
				rtn.msg = results.msg
			}
		} catch(e){
			log.error "Error in delete vpc: ${e}", e
		}
		ServiceResponse.create(rtn)
	}

	@Override
	ServiceResponse initializeCloud(Cloud cloud) {
		ServiceResponse rtn = new ServiceResponse(success: false)
		log.debug("Refreshing Cloud: ${cloud.code}")
		log.debug("config: ${cloud.configMap}")

		try {

			// initialize providers for this cloud
			plugin.getNetworkProvider().initializeProvider(cloud)
			plugin.getDnsProvider().initializeProvider(cloud)
			plugin.getStorageProvider().initializeProvider(cloud)

			refreshDaily(cloud)
			refresh(cloud)
			rtn.success = true
		} catch (e) {
			log.error("refresh cloud error: ${e}", e)
		}
		rtn
	}

	@Override
	String getDefaultNetworkServerTypeCode() {
		return "amazon-network-server"
	}

	EC2ProvisionProvider ec2ProvisionProvider() {
		this.plugin.getProviderByCode('amazon-ec2-provision-provider')
	}

	IacResourceMappingProvider getIacResourceMappingProvider() {
		this.plugin.getProviderByCode('aws-iac-resource-mapping-provider') as AWSIacResourceMappingProvider
	}

}
