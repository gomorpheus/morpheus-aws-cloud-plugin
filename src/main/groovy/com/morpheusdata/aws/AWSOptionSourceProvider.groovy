package com.morpheusdata.aws

import com.morpheusdata.aws.utils.AmazonComputeUtility
import com.morpheusdata.core.AbstractOptionSourceProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.AccountCredential
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.CloudRegion
import com.morpheusdata.model.ComputeZoneRegion
import com.morpheusdata.model.ImageType
import com.morpheusdata.model.NetworkRouteTable
import com.morpheusdata.core.util.MorpheusUtils
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
			'awsPluginVpc', 'awsPluginRegions', 'awsPluginAvailabilityZones', 'awsRouteTable', 'awsRouteDestinationType',
			'awsRouteDestination', 'awsPluginEc2SecurityGroup', 'awsPluginEc2PublicIpType', 'awsPluginInventoryLevels',
			'awsPluginStorageProvider', 'awsPluginEbsEncryption', 's3Regions', 'amazonEc2NodeAmiImage'
		])
	}

	def awsPluginRegions(args) {
		def rtn = []
		def refDataIds = morpheus.services.referenceData.list(new DataQuery().withFilter("category", "amazon.ec2.region")).collect { it.id }
		log.debug("refDataIds: ${refDataIds}")
		if(refDataIds.size() > 0) {
			rtn = morpheus.services.referenceData.listById(refDataIds).sort { it.name }.collect { [value: it.value, name: it.name] }
		}

		log.debug("results: ${rtn}")
		return rtn
	}

	def awsPluginVpc(args) {
		args = args instanceof Object[] ? args.getAt(0) : args

		Cloud cloud = args.zoneId ? morpheusContext.async.cloud.getCloudById(args.zoneId.toLong()).blockingGet() : null
		if(!cloud) {
			cloud = new Cloud()
		} else {
			//we need to load full creds
			AccountCredential credentials = morpheusContext.async.accountCredential.loadCredentials(cloud).blockingGet()
			cloud.accountCredentialData = credentials?.data
			cloud.accountCredentialLoaded = true
		}

		def config = [
			accessKey: args.accessKey ?: args.config?.accessKey ?: cloud.getConfigProperty('accessKey') ?: cloud.accountCredentialData?.username,
			secretKey: args.secretKey ?: args.config?.secretKey ?: cloud.getConfigProperty('secretKey') ?: cloud.accountCredentialData?.password,
			stsAssumeRole: (args.stsAssumeRole ?: args.config?.stsAssumeRole ?: cloud.getConfigProperty('stsAssumeRole')) in [true, 'true', 'on'],
			useHostCredentials: (args.useHostCredentials ?: cloud.getConfigProperty('useHostCredentials')) in [true, 'true', 'on'],
			endpoint: args.endpoint ?: args.config?.endpoint ?: cloud.getConfigProperty('endpoint')
		]
		if (config.secretKey == '*' * 12) {
			config.remove('secretKey')
		}
		cloud.setConfigMap(cloud.getConfigMap() + config)

		def proxy = args.apiProxy ? morpheusContext.async.network.networkProxy.getById(args.long('apiProxy')).blockingGet() : null
		cloud.apiProxy = proxy

		def rtn = [[name:'All', value:'']]
		if(AmazonComputeUtility.testConnection(cloud).success) {
			def amazonClient = plugin.getAmazonClient(cloud, true)
			def vpcResult = AmazonComputeUtility.listVpcs([amazonClient:amazonClient])
			if(vpcResult.success) {
				vpcResult.vpcList.each {
					rtn << [name:"${it.vpcId} - ${it.tags?.find { tag -> tag.key == 'Name' }?.value ?: 'default'}", value:it.vpcId]
				}
			}
		}
		rtn
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
		def zoneId = MorpheusUtils.getZoneId(args)
		def zonePoolId = MorpheusUtils.getResourcePoolId(args.network ?: [:])
		def tmpZone = zoneId ? morpheus.services.cloud.get(zoneId) : null
		def tmpZonePool = zonePoolId ? morpheus.services.cloud.pool.listById([zonePoolId])?.getAt(0) : null
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
		def rtn = []
		def routerId = MorpheusUtils.parseLongConfig(args.routerId)
		log.info("routerId: $routerId")
		if(routerId) {
			def router = morpheus.services.network.router.listById([routerId])?.getAt(0)
			log.info("router: $router")
			if(router && router.refType == 'ComputeZonePool') {
				def routeTableIds = morpheus.services.network.routeTable.listIdentityProjections(router.refId).collect { it.id }
				log.info("routeTableIds: $routeTableIds")
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
				def account = vpc?.owner
				def resourceType
				switch(destinationType) {
					case 'EGRESS_ONLY_INTERNET_GATEWAY':
						resourceType = 'aws.cloudFormation.ec2.egressOnlyInternetGateway'
						break
					case 'INTERNET_GATEWAY':
						List<Long> routerIds = morpheus.async.network.router.listIdentityProjections(vpc.cloud.id, 'amazonInternetGateway').collect { it.id }
						if(routerIds.size() > 0) {
							rtn =  morpheus.services.network.router.listById(routerIds)?.collect { [name: it.name, value: it.externalId ]}?.sort{it.name } ?: []
						}
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
					def accountResourceIds = morpheus.async.cloud.resource.listIdentityProjections(vpc.cloud.id, resourceType, null, account.id).toList().blockingGet().collect { it.id }
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
		def cloudId = getCloudId(args)
		if(cloudId) {
			Cloud tmpCloud = morpheusContext.async.cloud.getCloudById(cloudId).blockingGet()
			List zonePools
			if(args.config?.resourcePoolId) {
				def poolId = args.config.resourcePoolId
				if(poolId instanceof List) {
					poolId = poolId.first()
				}
				if(poolId instanceof String && poolId.startsWith('pool-')) {
					poolId = poolId.substring(5).toLong()
				}
				zonePools = morpheusContext.async.cloud.pool.listById([poolId]).toList().blockingGet()
			}
			def poolIds = zonePools?.collect { it.id }
			List options = morpheusContext.async.securityGroup.location.listIdentityProjections(tmpCloud.id, null, null).toList().blockingGet()
			List allLocs = morpheusContext.async.securityGroup.location.listByIds(options.collect {it?.id}).filter {poolIds.contains(it?.zonePool?.id)}.toList().blockingGet()
			def x =  allLocs.collect {[name: it.name, value: it.externalId]}.sort {it.name.toLowerCase()}
			return x
		} else {
			return []
		}
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


	private static getCloudId(args) {
		def cloudId = null
		if(args?.size() > 0) {
			def firstArg =  args.getAt(0)
			if(firstArg?.zoneId) {
				cloudId = firstArg.zoneId.toLong()
				return cloudId
			}
			if(firstArg?.domain?.zone?.id) {
				cloudId = firstArg.domain.zone.id.toLong()
				return cloudId
			}
		}
		return cloudId

	}
}
