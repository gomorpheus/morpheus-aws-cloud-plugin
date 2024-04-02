package com.morpheusdata.aws

import com.morpheusdata.aws.utils.AmazonComputeUtility
import com.morpheusdata.core.AbstractOptionSourceProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataOrFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.AccountCredential
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.CloudRegion
import com.morpheusdata.model.ImageType
import com.morpheusdata.model.NetworkRouteTable
import com.morpheusdata.core.util.MorpheusUtils
import com.morpheusdata.model.Permission
import com.morpheusdata.model.SecurityGroup
import com.morpheusdata.model.SecurityGroupLocation
import com.morpheusdata.model.StorageServer
import groovy.util.logging.Slf4j

@Slf4j
class AWSOptionSourceProvider extends AbstractOptionSourceProvider {

	AWSPlugin plugin
	MorpheusContext morpheusContext

	AWSOptionSourceProvider(AWSPlugin plugin, MorpheusContext context) {
		this.plugin = plugin
		this.morpheusContext = context
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
		return 'aws-option-source'
	}

	@Override
	String getName() {
		return 'AWS Option Source'
	}

	@Override
	List<String> getMethodNames() {
		return new ArrayList<String>([
			'awsPluginVpc', 'awsPluginAllRegions', 'awsPluginRegions', 'awsPluginCloudRegions', 'awsPluginAvailabilityZones', 'awsRouteTable',
			'awsRouteDestinationType', 'awsRouteDestination', 'awsPluginEc2SecurityGroup', 'awsPluginEc2PublicIpType',
			'awsPluginInventoryLevels', 'awsPluginStorageProvider', 'awsPluginEbsEncryption', 'awsPluginCostingReports',
			'awsPluginCostingBuckets', 'awsPluginInventoryLevels', 's3Regions', 'amazonEc2NodeAmiImage', 'amazonInstanceProfiles', 'amazonS3Buckets'
		])
	}

	def awsPluginAllRegions(args) {
		[[name: morpheusContext.services.localization.get('gomorpheus.label.all'), value: '']] + awsPluginRegions(args)
	}

	def awsPluginRegions(args) {
		def rtn = []
		morpheusContext.services.referenceData.list(
			new DataQuery().withFilter('category', 'amazon.ec2.region').withSort("name")
		).each {
			rtn << [value: it.value, name: it.name]
		}

		return rtn
	}

	def awsPluginCloudRegions(args) {
		log.debug "awsPluginCloudRegions args: ${args}"
		def rtn = []
		Cloud cloud = loadCloud(args)
		if(cloud?.accountCredentialLoaded) {
			morpheusContext.async.cloud.region.listIdentityProjections(cloud.id).blockingSubscribe { region ->
				rtn << [value: region.externalId, name: region.externalId]
			}
		}

		return rtn
	}


	def awsPluginVpc(args) {
		Cloud cloud = loadCloud(args)
		def rtn
		if(cloud?.accountCredentialData && AmazonComputeUtility.testConnection(cloud).success) {
			def amazonClient = plugin.getAmazonClient(cloud, true)
			def vpcResult = AmazonComputeUtility.listVpcs([amazonClient:amazonClient])
			if(vpcResult.success && vpcResult.vpcList) {
				rtn = [[name:morpheusContext.services.localization.get('gomorpheus.label.all'), value:'']]
				vpcResult.vpcList.each {
					rtn << [name:"${it.vpcId} - ${it.tags?.find { tag -> tag.key == 'Name' }?.value ?: 'default'}", value:it.vpcId, isDefault: cloud.configMap.vpc == it.vpcId]
				}
			}
		}
		rtn ?: [[name: 'No VPCs found: verify credentials above.', value: '-1', isDefault: true]]
	}

	def awsPluginInventoryLevels(args) {
		[
			[name:'Off', value:'off'],
			[name:'Basic', value:'basic'],
			[name:'Full', value:'full']
		]
	}

	def awsPluginAvailabilityZones(args) {
		args = args instanceof Object[] ? args.getAt(0) : args
		def rtn = []
		def zonePoolId = MorpheusUtils.getResourcePoolId(args.network ?: [:])
		def tmpZonePool = zonePoolId ? morpheus.services.cloud.pool.listById([zonePoolId])?.getAt(0) : null
		def zoneId = tmpZonePool?.cloud?.id ?: MorpheusUtils.getZoneId(args)
		def tmpZone = zoneId ? morpheus.services.cloud.get(zoneId) : null
		if(tmpZone) {
			def results = []
			if(tmpZonePool && tmpZonePool.regionCode) {
				def categories = ["amazon.ec2.zone.${tmpZone.id}.${tmpZonePool.regionCode}", "amazon.ec2.zone.${tmpZone.id}"]
				results = morpheus.services.referenceData.list(new DataQuery(tmpZone.owner).withFilter("category", "in", categories))
			} else {
				results = morpheus.services.referenceData.list(new DataQuery(tmpZone.owner).withFilter("category", "=~", "amazon.ec2.zone.${tmpZone.id}"))
			}
			if(results.size() > 0) {
				rtn = results?.collect { [name:it.name, value:it.name] }
			}
		}

		return rtn
	}

	def awsRouteTable(args) {
		args = args instanceof Object[] ? args.getAt(0) : args
		log.info("awsRouteTable args: ${args}")
		log.debug("awsRouteTable args.parentId: ${args.parentId}")
		def rtn = []
		def routerId = MorpheusUtils.parseLongConfig(args.routerId ?: args.parentId)
		log.debug("routerId: $routerId")
		if(routerId) {
			def router = morpheus.services.network.router.listById([routerId])?.getAt(0)
			log.debug("router: $router")
			if(router && router.refType == 'ComputeZonePool') {
				def routeTableIds = morpheus.services.network.routeTable.listIdentityProjections(new DataQuery().withFilter("zonePool.id", router.refId)).collect { it.id }

				log.debug("routeTableIds: $routeTableIds")
				if(routeTableIds.size() > 0) {
					rtn = morpheus.services.network.routeTable.listById(routeTableIds).collect {
						[name: it.name, value: it.id]
					}
				}
			}
		}

		return rtn
	}

	def awsRouteDestinationType(args) {
		return [
			[name: 'Egress Only Internet Gateway', value: 'EGRESS_ONLY_INTERNET_GATEWAY'],
			[name: 'Instance', value: 'INSTANCE'],
			[name: 'Internet Gateway', value: 'INTERNET_GATEWAY'],
			[name: 'NAT Gateway', value: 'NAT_GATEWAY'],
			[name: 'Network Interface', value: 'NETWORK_INTERFACE'],
			[name: 'VPC Peering Connection', value: 'VPC_PEERING_CONNECTION'],
			[name: 'Transit Gateway', value: 'TRANSIT_GATEWAY'],
			[name: 'Virtual Private Gateway', value: 'GATEWAY']
		]
	}

	def awsRouteDestination(args) {
		args = args instanceof Object[] ? args.getAt(0) : args
		def rtn = []
		def destinationType = args.route?.destinationType
		def networkRouteTableId = MorpheusUtils.parseLongConfig(args.route?.routeTable)
		if(networkRouteTableId) {
			NetworkRouteTable networkRouteTable = morpheus.services.network.routeTable.listById([networkRouteTableId])?.getAt(0)
			if(networkRouteTable) {
				def vpc = networkRouteTable?.zonePool
				log.debug("VPC: ${vpc}")
				vpc = morpheus.services.cloud.pool.get(vpc.id)
				def account = vpc?.owner
				def resourceType
				switch(destinationType) {
					case 'EGRESS_ONLY_INTERNET_GATEWAY':
						resourceType = 'aws.cloudFormation.ec2.egressOnlyInternetGateway'
						break
					case 'INTERNET_GATEWAY':
						rtn = morpheus.services.network.router.list(
							new DataQuery().withFilter('poolId', vpc.id).withFilter('type.code', 'amazonInternetGateway')
						).collect { [name: it.name, value: it.externalId ]}?.sort{it.name } ?: []
						break
					case 'GATEWAY':
						resourceType = 'aws.cloudFormation.ec2.vpnGateway'
						break
					case 'INSTANCE':
						rtn = morpheus.services.computeServer.list(new DataQuery().withFilter("resourcePoolId", vpc.id))?.collect { [name: it.displayName ?: it.name, value: it.externalId ]}?.sort{it.name} ?: []
						break
					case 'NAT_GATEWAY':
						resourceType = 'aws.cloudFormation.ec2.natGateway'
						break
					case 'NETWORK_INTERFACE':
						resourceType = 'aws.cloudFormation.ec2.networkInterface'
						break
					case 'TRANSIT_GATEWAY':
						def accountResourceIds = morpheus.async.cloud.resource.listIdentityProjections(vpc.cloud.id, 'aws.cloudFormation.ec2.transitGatewayAttachment', null, account.id).toList().blockingGet().collect { it.id }
						if(accountResourceIds.size() > 0) {
							rtn = morpheus.async.cloud.resource.listById(accountResourceIds).filter {
								def payload = new groovy.json.JsonSlurper().parseText(it.rawData ?: '[]')
								return (payload.vpcId == vpc.externalId && payload.state == 'available')
							}.toList().blockingGet().collect {
								[name: it.displayName ?: it.name, value: it.externalId]
							}?.sort{it.name } ?: []
						}
						break
					case 'VPC_PEERING_CONNECTION':
						resourceType = 'aws.cloudFormation.ec2.vpcPeeringConnection'
						break
				}

				if(resourceType && rtn.size == 0) {
					log.debug("VPC Owner: ${account}, vpc cloud: ${vpc.cloud}")
					def accountResourceIds = morpheus.async.cloud.resource.listIdentityProjections(vpc.cloud.id, resourceType, null, account.id).toList().blockingGet().collect { it.id }
					log.debug("accountResourceIds: {}", accountResourceIds)
					if(accountResourceIds.size() > 0) {
						rtn = morpheus.services.cloud.resource.listById(accountResourceIds)?.collect {
							[name: it.displayName ?: it.name, value: it.externalId]
						}?.sort { it.name } ?: []
					}
				}

			}
		}

		return rtn
	}

	def awsPluginEc2SecurityGroup(args) {
		//AC - TODO - Overhaul security group location fetch to allow multiple zone pools and filter based on id rather than category?
		args = args instanceof Object[] ? args.getAt(0) : args
		def cloud = loadCloud(args)
		def rtn = []
		if(cloud) {
			def locationDataQuery = new DataQuery()
			if(args.config?.resourcePoolId) {
				def poolId = args.config.resourcePoolId
				if(poolId instanceof List) {
					poolId = poolId.first()
				}
				if(poolId instanceof String && poolId.startsWith('pool-')) {
					poolId = poolId.substring(5).toLong()
				}
				locationDataQuery.withFilter('zonePool.id', poolId)
			} else {
				locationDataQuery.withFilter('refType', 'ComputeZone').withFilter('refId', cloud.id)
			}

			List<SecurityGroupLocation> locations = morpheusContext.async.securityGroup.location.list(locationDataQuery).toList().blockingGet()
			Map<Long, SecurityGroup> groups = morpheusContext.async.securityGroup.listById(locations.collect { it.securityGroup.id }).toMap{ it.id }.blockingGet()
			List<Permission> accessibleGroupIds = morpheusContext.async.permission.listAccessibleResources(args.accountId, Permission.ResourceType.SecurityGroup, null, null).toList().blockingGet()

			// filter by security group parent visibility / ownership
			rtn = locations.findAll{
				SecurityGroup group = groups[it.securityGroup.id]
				group.id in accessibleGroupIds || group.visibility == 'public' || group.account.id == args.accountId || group.owner?.id == args.accountId
			}.collect{ [name: it.name, value: it.externalId] }.sort { it.name.toLowerCase() }
		}
		rtn
	}

	def awsPluginEc2PublicIpType(args) {
		return [
				[name:'Subnet Default', value:'subnet'],
				[name:'Assign EIP', value:'elasticIp']
		]
	}

	def awsPluginStorageProvider(args) {
		args = args instanceof Object[] ? args.getAt(0) : args
		def rtn = []
		Cloud cloud = args.zoneId ? morpheusContext.async.cloud.getCloudById(args.zoneId.toLong()).blockingGet() : null

		if(cloud) {
			morpheusContext.async.storageBucket.list(
				new DataQuery().withFilter('account.id', cloud.account.id).withFilter('providerType', 's3')
			).toList().blockingGet().sort { it.name }.each {
				rtn << [name: it.name, value: it.id]
			}
		}
		rtn
	}

	def awsPluginCostingBuckets(args) {
		args = args instanceof Object[] ? args.getAt(0) : args
		def rtn = [[name:'Create New',value:'create-bucket']]
		Cloud cloud = loadCloud(args)

		try {
			if(cloud?.accountCredentialData) {
				String regionCode = args.config?.costingRegion ?: AmazonComputeUtility.getAmazonEndpointRegion(args.config?.endpoint ?: cloud.regionCode)
				AmazonComputeUtility.listBuckets(AmazonComputeUtility.getAmazonS3Client(cloud, regionCode)).buckets?.sort { it.name }.each {
					rtn << [name: it.name, value: it.name]
				}
			}
		} catch(e) {
		}
		rtn
	}

	def awsPluginEbsEncryption(args) {
		[
			[name:'Off',value:'off'],
			[name:'On', value:'on']
		]
	}

	def amazonEc2NodeAmiImage(args) {
		def rtn = []
		args = args instanceof Object[] ? args.getAt(0) : args
		def images = morpheusContext.async.virtualImage.listIdentityProjections(args?.accountId?.toLong(), ImageType.ami).toList().blockingGet()
		if(images) {
			if(args.phrase) {
				rtn = images.findAll{it.name.toLowerCase().contains(args.phrase.toLowerCase())}.collect { img -> [name: img.name, value: img.id] }.sort { it.name }
			} else {
				rtn = images.collect { img -> [name: img.name, value: img.id] }.sort { it.name }
			}
		}
		return rtn
	}

	def s3Regions(args) {
		args = args instanceof Object[] ? args.getAt(0) : args
		// this filters AWS regions by aws partition based on the storage server
		// make the storage server region the default value
		def records = morpheus.services.referenceData.list(new DataQuery(sort:"name").withFilter("category", "amazon.ec2.region"))
		// remove the global cost aggregator region
		records = records.findAll {it.keyValue != 'global'}
		String amazonEndpoint
		String defaultRegion
		if(args.storageProvider?.storageServer?.isLong()) {
			StorageServer storageServer = morpheus.services.storageServer.get(args.storageProvider.storageServer.toLong())
			if(storageServer.refType == 'ComputeZone') {
				// Select us-east-1 by default, or else the first available region
				Cloud cloud = morpheus.services.cloud.get(storageServer.refId.toLong())
				def cloudRegions = morpheus.services.cloud.region.list(new DataQuery(sort:"name", order: DataQuery.SortOrder.asc).withFilter("cloud", cloud))
				CloudRegion cloudRegion = cloudRegions?.find { it.regionCode == 'us-east-1' } ?: cloudRegions?.getAt(0)
				if(cloudRegion) {
					amazonEndpoint = cloudRegion.internalId
				} else {
					// no regions? use regionCode
					if(cloud.regionCode)
						amazonEndpoint = cloud.regionCode
					else
						amazonEndpoint = "s3.us-east-1.amazonaws.com"

				}
			} else if(storageServer.serviceUrl && storageServer.serviceUrl.contains('amazonaws.com')) {
				amazonEndpoint = storageServer.serviceUrl
			}
		}
		if(amazonEndpoint) {
			defaultRegion = AmazonComputeUtility.getAmazonEndpointRegion(amazonEndpoint)
			if(amazonEndpoint.endsWith(".cn")) {
				// China
				records = records.findAll { it.keyValue?.startsWith('cn-') }
			} else if(amazonEndpoint.contains("gov-")) {
				// US Gov
				records = records.findAll { it.keyValue?.contains('gov-') }
			} else {
				// Default is everywhere else
				records = records.findAll { !it.keyValue?.startsWith('cn-') && !it.keyValue?.contains('gov-') }
			}
		} else {
			// if no storage server is passed, all regions are returned
			// records = []
		}
		def rtn = records.collect { [value: it.keyValue, name: it.name, isDefault: (it.keyValue == defaultRegion)]}
		def select = [name:morpheus.services.localization.get("gomorpheus.label.select"), value: '']
		return [select] + rtn
	}

	def amazonS3Buckets(args) {
		def cloud = loadCloud(args)
		return morpheus.services.storageBucket.list(new DataQuery().withFilters([
			new DataFilter('account', cloud.account),
			new DataFilter('providerType', 's3'),
			new DataFilter('storageServer.refType', 'ComputeZone'),
			new DataFilter('storageServer.refId', cloud.id)
		])).collect{[name: it.name, value: it.id]}.sort {it.name}
	}

	def awsPluginCostingReports(args) {
		def rtn = [[name:'Create New',value:'create-report']]
		Cloud cloud = loadCloud(args)

		if(cloud?.accountCredentialData) {
			plugin.cloudProvider.cloudCostingProvider.loadAwsReportDefinitions(cloud).reports?.each { report ->
				rtn << [name: report.reportName, value: report.reportName]
			}
		}
		rtn
	}

	def amazonInstanceProfiles(args) {
		args = args instanceof Object[] ? args.getAt(0) : args
		def tmpZone = morpheus.services.cloud.get(args.zoneId?.toLong())
		def poolId = args?.poolId ?: args?.server?.poolId
		def regionCode = null
		if(tmpZone && poolId) {
			def pool = morpheus.services.cloud.pool.get(poolId, tmpZone.account.id, args.siteId?.toLong(), tmpZone?.id)
			regionCode = pool.regionCode
		}
		def rtn = null
		if(tmpZone?.cloudType?.code == 'amazon') {
			def refDataResults = morpheus.services.referenceData.search(
				new DataQuery().withFilters(
					new DataOrFilter(
						new DataFilter('account.id', tmpZone.account.id),
						new DataFilter('account.id', tmpZone.owner.id)
					),
					new DataOrFilter(
						new DataFilter('category', "amazon.ec2.profiles.${tmpZone.id}.${regionCode}"),
						new DataFilter('category', "amazon.ec2.profiles.${tmpZone.id}")
					)
				).withSort('name')
			)

			if(refDataResults.success) {
				rtn = refDataResults.items?.collect { row -> [name:row.name, value:row.keyValue] }
			}
		}

		return rtn
	}

	private static getCloudId(args) {
		args = args instanceof Object[] ? args[0] : args
		def cloudId = args.cloudId ?: args.zoneId ?: args.cloudFormation?.zoneId// ?: args.domain?.id
		cloudId ? cloudId.toLong() : null
	}

	private Cloud loadCloud(args) {
		args = args instanceof Object[] ? args.getAt(0) : args
		Long cloudId = getCloudId(args)
		Cloud rtn = cloudId ? morpheusContext.async.cloud.getCloudById(cloudId).blockingGet() : null
		if(!rtn) {
			rtn = new Cloud()
		}

		// load existing credentials when not passed in
		if(args.credential == null && !(args.accessKey ?: args.config?.accessKey)) {
			// check for passed in credentials
			if(!rtn.accountCredentialLoaded) {
				AccountCredential credentials = morpheusContext.services.accountCredential.loadCredentials(rtn)
				rtn.accountCredentialData = credentials?.data
			}
		} else {
			def config = [
				accessKey: args.accessKey ?: args.config?.accessKey,
				secretKey: args.secretKey ?: args.config?.secretKey,
				stsAssumeRole: (args.stsAssumeRole ?: args.config?.stsAssumeRole) in [true, 'true', 'on'],
				useHostCredentials: args.useHostCredentials in [true, 'true', 'on'],
				endpoint: args.endpoint ?: args.config?.endpoint
			]
			if (config.secretKey == '*' * 12) {
				config.remove('secretKey')
			}
			rtn.setConfigMap(rtn.getConfigMap() + config)
			rtn.accountCredentialData = morpheusContext.services.accountCredential.loadCredentialConfig(args.credential, config).data
		}
		rtn.accountCredentialLoaded = true

		def proxy = args.apiProxy ? morpheusContext.async.network.networkProxy.getById(args.long('apiProxy')).blockingGet() : null
		rtn.apiProxy = proxy

		return rtn
	}
}
