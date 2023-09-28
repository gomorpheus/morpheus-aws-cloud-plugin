package com.morpheusdata.aws

import com.morpheusdata.aws.utils.AmazonComputeUtility
import com.morpheusdata.core.AbstractOptionSourceProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.AccountCredential
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.NetworkRouteTable
import com.morpheusdata.core.util.MorpheusUtils
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
			'awsPluginStorageProvider', 'awsPluginEbsEncryption'
		])
	}

	def awsPluginRegions(args) {
		def rtn = []
		def refDataIds = morpheus.referenceData.listByCategory("amazon.ec2.region").toList().blockingGet().collect { it.id }
		log.debug("refDataIds: ${refdataIds}")
		if(refDataIds.size() > 0) {
			rtn = morpheus.referenceData.listById(refDataIds).toList().blockingGet().sort { it.name }.collect { [value: it.value, name: it.name] }
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
		def tmpZone = zoneId ? morpheus.cloud.getCloudById(zoneId).blockingGet() : null
		def tmpZonePool = zonePoolId ? morpheus.cloud.pool.listById([zonePoolId]).toList().blockingGet()?.getAt(0) : null
		if(tmpZone) {
			def results = []
			if(tmpZonePool && tmpZonePool.regionCode) {
				def categories = ["amazon.ec2.zone.${tmpZone.id}.${tmpZonePool.regionCode}", "amazon.ec2.zone.${tmpZone.id}"]
				results = morpheus.referenceData.listByAccountIdAndCategories(tmpZone.owner.id, categories).toList().blockingGet()
			} else {
				results = morpheus.referenceData.listByAccountIdAndCategoryMatch(tmpZone.owner.id, "amazon.ec2.zone.${tmpZone.id}").toList().blockingGet()
			}
			if(results.size() > 0) {
				rtn = results?.collect { [name:it.name, value:it.name] }
			}
		}

		return rtn
	}

	def awsRouteTable(args) {\
		args = args instanceof Object[] ? args.getAt(0) : args
		log.info("awsRouteTable args: ${args}")
		def rtn = []
		def routerId = MorpheusUtils.parseLongConfig(args.routerId)
		log.info("routerId: $routerId")
		if(routerId) {
			def router = morpheus.network.router.listById([routerId]).toList().blockingGet()?.getAt(0)
			log.info("router: $router")
			if(router && router.refType == 'ComputeZonePool') {
				def routeTableIds = morpheus.network.routeTable.listIdentityProjections(router.refId).toList().blockingGet().collect { it.id }
				log.info("routeTableIds: $routeTableIds")
				if(routeTableIds.size() > 0) {
					rtn = morpheus.network.routeTable.listById(routeTableIds).toList().blockingGet().collect {
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
			NetworkRouteTable networkRouteTable = morpheus.network.routeTable.listById([networkRouteTableId]).toList().blockingGet()?.getAt(0)
			if(networkRouteTable) {
				def vpc = networkRouteTable?.zonePool
				def account = vpc?.owner
				def resourceType
				switch(destinationType) {
					case 'EGRESS_ONLY_INTERNET_GATEWAY':
						resourceType = 'aws.cloudFormation.ec2.egressOnlyInternetGateway'
						break
					case 'INTERNET_GATEWAY':
						List<Long> routerIds = morpheus.network.router.listIdentityProjections(vpc.cloud.id, 'amazonInternetGateway').toList().blockingGet().collect { it.id }
						if(routerIds.size() > 0) {
							rtn =  morpheus.network.router.listById(routerIds).toList().blockingGet()?.collect { [name: it.name, value: it.externalId ]}?.sort{it.name } ?: []
						}
						break
					case 'GATEWAY':
						resourceType = 'aws.cloudFormation.ec2.vpnGateway'
						break
					case 'INSTANCE':
						rtn = morpheus.computeServer.listByResourcePoolId(vpc.id).toList().blockingGet()?.collect { [name: it.displayName ?: it.name, value: it.externalId ]}?.sort{it.name} ?: []
						break
					case 'NAT_GATEWAY':
						resourceType = 'aws.cloudFormation.ec2.natGateway'
						break
					case 'NETWORK_INTERFACE':
						resourceType = 'aws.cloudFormation.ec2.networkInterface'
						break
					case 'TRANSIT_GATEWAY':
						def accountResourceIds = morpheus.cloud.resource.listIdentityProjections(vpc.cloud.id, 'aws.cloudFormation.ec2.transitGatewayAttachment', null, account.id).toList().blockingGet().collect { it.id }
						if(accountResourceIds.size() > 0) {
							rtn = morpheus.cloud.resource.listById(accountResourceIds).filter {
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
					def accountResourceIds = morpheus.cloud.resource.listIdentityProjections(vpc.cloud.id, resourceType, null, account.id).toList().blockingGet().collect { it.id }
					if(accountResourceIds.size() > 0) {
						rtn = morpheus.cloud.resource.listById(accountResourceIds).toList().blockingGet()?.collect {
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
