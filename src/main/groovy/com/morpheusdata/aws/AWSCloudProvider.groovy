package com.morpheusdata.aws

import com.morpheusdata.aws.sync.AlarmSync
import com.morpheusdata.aws.sync.AlbSync
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
import com.morpheusdata.aws.sync.RegionSync
import com.morpheusdata.aws.sync.ScaleGroupSync
import com.morpheusdata.aws.sync.SecurityGroupSync
import com.morpheusdata.aws.sync.SnapshotSync
import com.morpheusdata.aws.sync.SubnetSync
import com.morpheusdata.aws.sync.TransitGatewaySync
import com.morpheusdata.aws.sync.TransitGatewayVpcAttachmentSync
import com.morpheusdata.aws.sync.VPCRouterSync
import com.morpheusdata.aws.sync.VPCSync
import com.morpheusdata.aws.sync.VirtualMachineSync
import com.morpheusdata.aws.sync.VolumeSync
import com.morpheusdata.aws.sync.VpcPeeringConnectionSync
import com.morpheusdata.aws.utils.AmazonComputeUtility
import com.morpheusdata.core.backup.AbstractBackupProvider
import com.morpheusdata.core.CloudProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.ProvisioningProvider
import com.morpheusdata.core.util.ConnectionUtils
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerType
import com.morpheusdata.model.Icon
import com.morpheusdata.model.KeyPair
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
			name: 'Region',
			code: 'aws-plugin-endpoint',
			defaultValue: 'ec2.us-east-1.amazonaws.com',
			displayOrder: 0,
			fieldContext: 'config',
			fieldLabel: 'Region',
			fieldName: 'endpoint',
			inputType: OptionType.InputType.SELECT,
			optionSource: 'awsPluginRegions',
			required: true
		)
		OptionType credentials = new OptionType(
			code: 'aws-plugin-credential',
			inputType: OptionType.InputType.CREDENTIAL,
			name: 'Credentials',
			fieldContext: 'credential',
			fieldLabel: 'Credentials',
			fieldName: 'type',
			required: true,
			defaultValue: 'local',
			displayOrder: 10,
			optionSource: 'credentials',
			config: '{"credentialTypes":["access-key-secret"]}'
		)
		OptionType accessKey = new OptionType(
			name: 'Access Key',
			code: 'aws-plugin-access-key',
			displayOrder: 20,
			fieldContext: 'config',
			fieldLabel: 'Access Key',
			fieldName: 'accessKey',
			inputType: OptionType.InputType.TEXT,
			localCredential: true,
			required: true
		)
		OptionType secretKey = new OptionType(
			name: 'Secret Key',
			code: 'aws-plugin-secret-key',
			displayOrder: 30,
			fieldContext: 'config',
			fieldLabel: 'Secret Key',
			fieldName: 'secretKey',
			inputType: OptionType.InputType.PASSWORD,
			localCredential: true,
			required: true
		)
		OptionType useHostCreds = new OptionType(
			name: 'Use Host IAM Credentials',
			code: 'aws-plugin-use-host-creds',
			displayOrder: 40,
			fieldContext: 'config',
			fieldLabel: 'Use Host IAM Credentials',
			fieldName: 'useHostCredentials',
			inputType: OptionType.InputType.CHECKBOX,
			required: true
		)
		OptionType roleArn = new OptionType(
			name: 'Role ARN',
			code: 'aws-plugin-role-arn',
			displayOrder: 40,
			fieldContext: 'config',
			fieldLabel: 'Role ARN',
			fieldName: 'stsAssumeRole',
			inputType: OptionType.InputType.CHECKBOX,
		)
		OptionType importExisting = new OptionType(
			name: 'Import Existing',
			code: 'aws-plugin-import-existing',
			defaultValue: 'off',
			displayOrder: 50,
			fieldContext: 'config',
			fieldLabel: 'Import Existing Instances',
			fieldName: 'importExisting',
			helpBlock: 'Turn this feature on to import existing virtual machines from Amazon.',
			inputType: OptionType.InputType.CHECKBOX,
			required: true
		)
		OptionType isVpc = new OptionType(
			name: 'Use VPC Existing Instances',
			code: 'aws-plugin-is-vpc',
			displayOrder: 60,
			fieldContext: 'config',
			fieldLabel: 'Use VPC Existing Instances',
			fieldName: 'isVpc',
			inputType: OptionType.InputType.CHECKBOX,
			required: true
		)
		OptionType vpc = new OptionType(
			name: 'VPC',
			code: 'aws-plugin-vpc',
			displayOrder: 70,
			fieldContext: 'config',
			fieldLabel: 'VPC',
			fieldName: 'vpc',
			inputType: OptionType.InputType.SELECT,
			optionSource: 'awsPluginVpc',
			visibleOnCode: 'matchAny::config.isVpc:true,config.isVpc:on'
		)
		[apiUrl, credentials, accessKey, secretKey, useHostCreds, roleArn, importExisting, isVpc, vpc]
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

		[unmanaged, dockerType, vmType, windwsVmType] //TODO: More types for RDS and K8s
	}

	@Override
	Collection<ProvisioningProvider> getAvailableProvisioningProviders() {
		return plugin.getProvidersByType(ProvisioningProvider) as Collection<ProvisioningProvider>
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
	ProvisioningProvider getProvisioningProvider(String providerCode) {
		return getAvailableProvisioningProviders().find { it.code == providerCode }
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
				def config = validateCloudRequest.opts.config ?: [:]
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
/*
				if(config.costingReport) {
					def loadReportsResult = amazonCostingService.loadReportDefinitions(zone)

					if (loadReportsResult.success) {
						def costingReport
						if (config.costingReport == 'createReport') {
							config.costingReportError = null

							if (!config.costingReportName) {
								rtn.success = false
								rtn.errors.costingReportName = "Missing report name required to create a new report"
							} else if(!configMap.costingFolder) {
								rtn.success = false
								rtn.errors.costingFolder = "Missing folder name required to create a new report"
							} else {
								costingReport = loadReportsResult.reports?.find { it.reportName == configMap.costingReportName }

								if (!costingReport) {
									if (!configMap.costingBucket) {
										rtn.success = false
										rtn.errors.costingBucket = 'Choose a costing report bucket'
									} else {
										def costingBucket

										if (configMap.costingBucket == 'createBucket') {
											if (!configMap.costingBucketName) {
												rtn.success = false
												rtn.errors.costingBucketName = 'Enter costing report bucket name'
											}
										} else {
											costingBucket = StorageBucket.withCriteria(uniqueResult: true) {
												eq('bucketName', configMap.costingBucket)
												eq('account', zone.account)
												storageServer {
													eq('refType', 'ComputeZone')
													eq('refId', zone.id)
												}
											}
											if (!costingBucket) {
												rtn.success = false
												rtn.errors.costingBucket = "Costing report bucket ${configMap.costingBucket} not found"
											}
										}
									}
								}
							}
						} else {
							if (!(costingReport = loadReportsResult.reports?.find { it.reportName == configMap.costingReport })) {
								rtn.errors.costingReport = "Costing report ${configMap.costingReport} not found"
								rtn.success = false
							}
						}
						zone.setConfigMap(configMap)
					}
					else if(configMap.costingBucket) {
						def costingBucket = StorageBucket.withCriteria(uniqueResult: true) {
							eq('bucketName', configMap.costingBucket)
							eq('account', zone.account)
							storageServer {
								eq('refType', 'ComputeZone')
								eq('refId', zone.id)
							}
						}
						if (!costingBucket) {
							rtn.success = false
							rtn.errors.costingBucket = "Costing report bucket ${configMap.costingBucket} not found"
						}
						else {
							def bucketRegion = costingBucket.getConfigProperty('region')

							if(!bucketRegion) {
								def bucketLocationResult = AmazonComputeUtility.getBucketLocation(getAmazonS3Client(zone, null, false), costingBucket.bucketName)

								if(bucketLocationResult.success) {
									bucketRegion = bucketLocationResult.location
								}
								else {
									rtn.success = false
									rtn.errors.costingBucket = "Unable to get costing report bucket region"
								}
							}
							if(rtn.success) {
								configMap.costingRegion = bucketRegion
								zone.setConfigMap(configMap)
							}
						}
					}
				}
		*/
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
		ServiceResponse rtn = new ServiceResponse(success: false)
		log.info "Initializing Cloud: ${cloudInfo.code}"
		log.info "config: ${cloudInfo.configMap}"

		try {
			def networkProxy
			if(cloudInfo.apiProxy?.proxyPort) {
				networkProxy = new NetworkProxy(proxyHost: cloudInfo.apiProxy.proxyHost, proxyPort: cloudInfo.apiProxy.proxyPort)
			}

			def hostOnline = ConnectionUtils.testHostConnectivity(AmazonComputeUtility.getAmazonEndpoint(cloudInfo), 443, true, true, networkProxy)
			if(hostOnline) {
				def client = plugin.getAmazonClient(cloudInfo,true)
				def testResults = AmazonComputeUtility.testConnection(cloudInfo)
				if(testResults.success) {
					def keyResults = ensureAmazonKeyPair(cloudInfo, client)

					if(keyResults.success == true) {
						new RegionSync(this.plugin,cloudInfo).execute()
						new VPCSync(this.plugin,cloudInfo).execute()
						new VPCRouterSync(this.plugin,cloudInfo).execute()
						new KeyPairSync(this.plugin,cloudInfo).execute()
						new SubnetSync(this.plugin,cloudInfo).execute()
						new ImageSync(this.plugin,cloudInfo).execute()
						new SecurityGroupSync(this.plugin, cloudInfo).execute()
						new InstanceProfileSync(this.plugin,cloudInfo).execute()
						new IAMRoleSync(this.plugin,cloudInfo).execute()
						new InternetGatewaySync(this.plugin,cloudInfo).execute()
						//lb services
						new AlbSync(this.plugin,cloudInfo).execute()
						new ElbSync(this.plugin,cloudInfo).execute()
						//resources
						new EgressOnlyInternetGatewaySync(this.plugin,cloudInfo).execute()
						new NATGatewaySync(this.plugin,cloudInfo).execute()
						new TransitGatewaySync(this.plugin,cloudInfo).execute()
						new TransitGatewayVpcAttachmentSync(this.plugin,cloudInfo).execute()
						new NetworkInterfaceSync(this.plugin,cloudInfo).execute()
						new VpcPeeringConnectionSync(this.plugin,cloudInfo).execute()
						//rds services
						new DbSubnetGroupSync(this.plugin,cloudInfo).execute()
						new AlarmSync(this.plugin,cloudInfo).execute()
						//vms
						new VirtualMachineSync(this.plugin,cloudInfo).execute()
						//volumes
						new VolumeSync(this.plugin,cloudInfo).execute()
						new SnapshotSync(this.plugin,cloudInfo).execute()
						new ScaleGroupSync(this.plugin,cloudInfo).execute()
						rtn = ServiceResponse.success()

					} else {
						rtn = ServiceResponse.error('error uploading keypair')
					}
				} else {
					rtn = ServiceResponse.error(testResults.invalidLogin == true ? 'invalid credentials' : 'error connecting')
				}
			} else {
				rtn = ServiceResponse.error('AWS is not reachable', null, [status: Cloud.Status.offline])
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
		return new Icon(path:"amazon.svg", darkPath: "amazon-dark.svg")
	}

	@Override
	Icon getCircularIcon() {
		return new Icon(path:"amazon.svg", darkPath: "amazon-dark.svg")
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
		log.debug("Refreshing Cloud: ${cloud.code}")
		log.debug("config: ${cloud.configMap}")

		try {

			// initialize providers for this cloud
			plugin.getNetworkProvider().initializeCloud(cloud)
			plugin.getDnsProvider().initializeCloud(cloud)
			// plugin.getStorageProvider().initializeCloud(cloud)

			refreshDaily(cloud)
			refresh(cloud)
		} catch (e) {
			log.error("refresh cloud error: ${e}", e)
		}
		rtn
	}

	private ensureAmazonKeyPair(cloud, amazonClient = null) {
		amazonClient = amazonClient ?: plugin.getAmazonClient(cloud)
		def keyPair = morpheusContext.cloud.findOrGenerateKeyPair(cloud.account).blockingGet()
		def keyLocationId = "amazon-${cloud.id}".toString()
		def keyResults = AmazonComputeUtility.uploadKeypair(
			[key: keyPair, account: cloud.account, zone: cloud, keyName: keyPair.getConfigProperty(keyLocationId), amazonClient:amazonClient]
		)
		if(keyResults.success) {
			if(keyResults.uploaded) {
				keyPair.setConfigProperty(keyLocationId, keyResults.keyName)
				morpheusContext.cloud.updateKeyPair(keyPair, cloud)
			}
		}
		keyResults
	}

	@Override
	String getDefaultNetworkServerTypeCode() {
		return "amazon-network-server"
	}

}
