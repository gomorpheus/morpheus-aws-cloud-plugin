package com.morpheusdata.aws

import com.amazonaws.services.ec2.AmazonEC2Client
import com.amazonaws.services.ec2.model.CreateRouteRequest
import com.morpheusdata.aws.utils.AmazonComputeUtility
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.providers.NetworkProvider
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.providers.CloudInitializationProvider
import com.morpheusdata.model.AccountIntegration
import com.morpheusdata.model.AccountIntegrationType
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.Network
import com.morpheusdata.model.NetworkRoute
import com.morpheusdata.model.NetworkRouter
import com.morpheusdata.model.NetworkRouterType
import com.morpheusdata.model.NetworkServer
import com.morpheusdata.model.NetworkServerType
import com.morpheusdata.model.NetworkSubnet
import com.morpheusdata.model.NetworkType
import com.morpheusdata.model.ComputeZonePool as CloudPool
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j

@Slf4j
class AWSNetworkProvider implements NetworkProvider, CloudInitializationProvider {

	Plugin plugin
	MorpheusContext morpheus

	final String code = 'amazon-network-server'
	final String name = 'Amazon'
	final String description = 'AWS EC2'

	AWSNetworkProvider(AWSPlugin plugin, MorpheusContext morpheusContext) {
		this.plugin = plugin
		this.morpheus = morpheusContext
	}

	@Override
	String getNetworkServerTypeCode() {
		return 'amazon'
	}

	/**
	 * The CloudProvider code that this NetworkProvider should be attached to.
	 * When this NetworkProvider is registered with Morpheus, all Clouds that match this code will have a
	 * NetworkServer of this type attached to them. Network actions will then be handled via this provider.
	 * @return String Code of the Cloud type
	 */
	@Override
	String getCloudProviderCode() {
		return 'amazon'
	}

	/**
	 * Provides a Collection of NetworkTypes that can be managed by this provider
	 * @return Collection of NetworkType
	 */
	@Override
	Collection<NetworkType> getNetworkTypes() {
		NetworkType amazonSubnet = new NetworkType([
				code              : 'amazonSubnet',
				cidrEditable      : false,
				dhcpServerEditable: false,
				dnsEditable       : false,
				gatewayEditable   : false,
				vlanIdEditable    : false,
				canAssignPool     : false,
				name              : 'Amazon Subnet'
		])

		[amazonSubnet]
	}

	/**
	 * Provides a Collection of Router Types that can be managed by this provider
	 * @return Collection of NetworkRouterType
	 */
	@Override
	Collection<NetworkRouterType> getRouterTypes() {
		return [
				new NetworkRouterType(code:'amazonInternetGateway', name:'Amazon Internet Gateway', creatable:true, description:'Amazon Internet Gateway',
					routerService:'amazonInternetGatewayService', enabled:true, hasNetworkServer:false, hasGateway:true, deletable: true,
					hasDnsClient:false, hasFirewall:false, hasFirewallGroups: false, hasNat:false, hasRouting:false, hasStaticRouting:false, hasBgp:false, hasOspf:false,
					hasMulticast:false, hasGre:false, hasBridging:false, hasLoadBalancing:false, hasDnsForwarding:false, hasDhcp:false, supportsEditRoute: false,
					hasDhcpRelay:false, hasSyslog:false, hasSslVpn:false, hasL2tVpn:false, hasIpsecVpn:false, hasCertificates:false, hasInterfaces: false,
					hasRouteRedistribution:false, supportsEditFirewallRule: false, hasHighAvailability:false),
				new NetworkRouterType(code:'amazonVpcRouter', name:'Amazon VPC Router', creatable:false, description:'Amazon VPC Router',
					routerService:'amazonNetworkService', enabled:true, hasNetworkServer:true, hasGateway:true, deletable: false,
					hasDnsClient:false, hasFirewall:false, hasFirewallGroups: false, hasNat:false, hasRouting:true, hasStaticRouting:false, hasBgp:false, hasOspf:false,
					hasMulticast:false, hasGre:false, hasBridging:false, hasLoadBalancing:false, hasDnsForwarding:false, hasDhcp:false, supportsEditRoute: true,
					hasDhcpRelay:false, hasSyslog:false, hasSslVpn:false, hasL2tVpn:false, hasIpsecVpn:false, hasCertificates:false, hasInterfaces: false,
					hasRouteRedistribution:false, supportsEditFirewallRule: false, hasHighAvailability:false)
		]

	}

	@Override
	ServiceResponse initializeProvider(Cloud cloud) {
		ServiceResponse rtn = ServiceResponse.prepare()
		try {
			NetworkServer integration = new NetworkServer(
				name: cloud.name,
				type: new NetworkServerType(code:"amazon")
			)
			morpheus.integration.registerCloudIntegration(cloud.id, integration)
			ServiceResponse.success = true
		} catch (Exception e) {
			rtn.success = false
			log.error("initializeProvider error: {}", e, e)
		}

		return rtn
	}

	/**
	 * Creates the Network submitted
	 * @param network Network information
	 * @param opts additional configuration options
	 * @return ServiceResponse
	 */
	@Override
	ServiceResponse createNetwork(Network network, Map opts) {
		def rtn = ServiceResponse.prepare()
		try {
			if(network.networkServer) {
				def cloud = network.cloud
				AmazonEC2Client amazonClient = AmazonComputeUtility.getAmazonClient(cloud, false, network.zonePool?.regionCode)
				def networkConfig = [:]
				networkConfig.name = network.name
				networkConfig.vpcId = network.zonePool?.externalId
				networkConfig.availabilityZone = network.availabilityZone
				networkConfig.active = network.active
				networkConfig.assignPublicIp = network.assignPublicIp
				networkConfig.type = network.type?.externalType
				networkConfig.cidr = network.cidr
				log.debug("sending network config: {}", networkConfig)
				def apiResults = AmazonComputeUtility.createSubnet(opts + [amazonClient: amazonClient, config: networkConfig])
				log.info("network apiResults: {}", apiResults)
				//create it
				if( apiResults?.success && apiResults?.error != true) {
					rtn.success = true
					network.externalId = apiResults.externalId
					network.regionCode = network.zonePool?.regionCode
				}
				rtn.data = network
				rtn.msg = apiResults.msg
				log.debug("results: {}", rtn.results)
			}
		} catch(e) {
			log.error("createNetwork error: ${e}", e)
		}
		return rtn
	}

	/**
	 * Updates the Network submitted
	 * @param network Network information
	 * @param opts additional configuration options
	 * @return ServiceResponse
	 */
	@Override
	ServiceResponse updateNetwork(Network network, Map opts) {
		return ServiceResponse.success()
	}

	/**
	 * Deletes the Network submitted
	 * @param network Network information
	 * @return ServiceResponse
	 */
	@Override
	ServiceResponse deleteNetwork(Network network, Map opts) {
		log.debug("delete network: {}", network.externalId)
		def rtn = ServiceResponse.prepare()
		//remove the network
		if(network.externalId) {
			AmazonEC2Client amazonClient = AmazonComputeUtility.getAmazonClient(network.cloud, false, network.zonePool?.regionCode)
			def deleteResults = AmazonComputeUtility.deleteSubnet([amazonClient: amazonClient, network: network])
			log.debug("deleteResults: {}", deleteResults)
			if(deleteResults.success == true) {
				rtn.success = true
			} else if(deleteResults.errorCode == 404) {
				//not found - success
				log.warn("not found")
				rtn.success = true
			} else {
				rtn.msg = deleteResults.msg
			}
		} else {
			rtn.success = true
		}
		return rtn
	}

	/**
	 * Creates the NetworkSubnet submitted
	 * @param subnet Network information
	 * @param network Network to create the NetworkSubnet on
	 * @param opts additional configuration options
	 * @return ServiceResponse
	 */
	@Override
	ServiceResponse createSubnet(NetworkSubnet subnet, Network network, Map opts) {
		return ServiceResponse.success()
	}

	/**
	 * Updates the NetworkSubnet submitted
	 * @param subnet NetworkSubnet information
	 * @param network Network that this NetworkSubnet is attached to
	 * @param opts additional configuration options
	 * @return ServiceResponse
	 */
	@Override
	ServiceResponse updateSubnet(NetworkSubnet subnet, Network network, Map opts) {
		return ServiceResponse.success()
	}

	/**
	 * Deletes the NetworkSubnet submitted
	 * @param subnet NetworkSubnet information
	 * @param network Network that this NetworkSubnet is attached to
	 * @return ServiceResponse
	 */
	@Override
	ServiceResponse deleteSubnet(NetworkSubnet subnet, Network network, Map opts) {
		return ServiceResponse.success()
	}


	/**
	 * Create the NetworkRouter submitted
	 * @param router NetworkRouter information
	 * @param opts additional configuration options
	 * @return ServiceResponse
	 */
	ServiceResponse createRouter(NetworkRouter router, Map opts) {
		log.debug("createRouter: ${router} ${opts}")
		def rtn = ServiceResponse.prepare()
		try {
			def name = router.name
			def cloud = router.cloud
			def vpcId
			CloudPool pool
			if(router.poolId) {
				pool = morpheus.cloud.pool.listById([router.poolId]).toList().blockingGet().getAt(0)
				vpcId = pool?.externalId
			}
			opts += [
				amazonClient: AmazonComputeUtility.getAmazonClient(cloud,false, pool.regionCode),
				name: name,
				vpcId: vpcId
			]

			def apiResults = AmazonComputeUtility.createRouter(opts)
			log.info("route apiResults: {}", apiResults)
			if(apiResults?.success && apiResults?.error != true) {
				router.externalId = apiResults.internetGatewayId
				rtn.success = true
				rtn.data = router
			} else {
				rtn.msg = apiResults.msg ?: 'error creating router'
			}
		} catch(e) {
			log.error("createRouter error: ${e}", e)
			rtn.msg = 'unknown error creating router'
		}
		return ServiceResponse.create(rtn)
	}

	/**
	 * Update the NetworkRouter submitted
	 * @param router NetworkRouter information
	 * @param opts additional configuration options
	 * @return ServiceResponse
	 */
	ServiceResponse updateRouter(NetworkRouter router, Map opts) {
		log.debug("updateRouter: ${router} ${opts}")
		def rtn = ServiceResponse.prepare()
		try {
			if(router.type.code == 'amazonVpcRouter') {
				rtn.success = true
			} else if(router.type.code == 'amazonInternetGateway') {
				def poolId = router.poolId ? router.poolId : (router.refType == 'ComputeZonePool' ? router.refId : null)
				String regionCode = router.regionCode
				CloudPool desiredAttachedPool
				if(poolId) {
					desiredAttachedPool = morpheus.cloud.pool.listById([router.poolId]).toList().blockingGet().getAt(0)
					regionCode = desiredAttachedPool?.regionCode
				}
				def name = router.name
				def zone = router.cloud
				def internetGatewayId = router.externalId
				opts += [
					amazonClient:AmazonComputeUtility.getAmazonClient(zone,false, regionCode),
					 name: name,
					 internetGatewayId: internetGatewayId
				]

				// See if attaching, detaching, or changing
				def listResults = AmazonComputeUtility.listInternetGateways(opts, [internetGatewayId: internetGatewayId])
				if(listResults.success && listResults.internetGateways?.size()) {
					def amazonInternetGateway = listResults.internetGateways.getAt(0)
					def currentAttachedVpcId = amazonInternetGateway.getAttachments().getAt(0)?.getVpcId()
					def desiredAttachedVpcId = desiredAttachedPool?.externalId

					if(currentAttachedVpcId != desiredAttachedVpcId) {
						if(currentAttachedVpcId) {
							AmazonComputeUtility.detachInternetGateway([vpcId: currentAttachedVpcId] + opts)
						}
						if(desiredAttachedVpcId) {
							def attachResults = AmazonComputeUtility.attachInternetGateway([vpcId: desiredAttachedVpcId] + opts)
							if(!attachResults.success) {
								rtn.msg = attachResults.msg
								return ServiceResponse.create(rtn)
							}
						}
					}

					def apiResults = AmazonComputeUtility.updateInternetGateway(opts)
					log.debug("route apiResults: {}", apiResults)
					if(apiResults?.success && apiResults?.error != true) {
						rtn.success = true
					} else {
						rtn.msg = apiResults.msg ?: 'error updating router'
					}
				} else {
					rtn.msg = "Unable to locate internet gateway ${internetGatewayId}"
				}
			} else {
				throw new Exception("Unknown router type ${router.type.code}")
			}
		} catch(e) {
			log.error("updateRouter error: ${e}", e)
			rtn.msg = 'unknown error creating router'
		}

		return rtn
	}

	/**
	 * Delete the NetworkRouter submitted
	 * @param router NetworkRouter information
	 * @return ServiceResponse
	 */
	ServiceResponse deleteRouter(NetworkRouter router, Map opts) {
		ServiceResponse rtn = ServiceResponse.prepare()
		try {
			if(router.type.code == 'amazonVpcRouter') {
				rtn.success = true
			} else if(router.type.code == 'amazonInternetGateway') {
				if(router.externalId) {
					Cloud cloud = router.cloud
					def poolId = router.poolId ? router.poolId : (router.refType == 'ComputeZonePool' ? router.refId : null)
					String regionCode = router.regionCode
					CloudPool attachedPool
					if(poolId) {
						attachedPool = morpheus.cloud.pool.listById([router.poolId]).toList().blockingGet().getAt(0)
						regionCode = attachedPool?.regionCode
					}
					opts += [
						amazonClient:AmazonComputeUtility.getAmazonClient(cloud,false, regionCode),
						internetGatewayId: router.externalId
					]

					def performDelete = true

					if(attachedPool && attachedPool.externalId) {
						// Must first detach from the VPC
						def detachResults = AmazonComputeUtility.detachInternetGateway([vpcId: attachedPool.externalId] + opts)

						log.info("detachResults: {}", detachResults)
						if(!detachResults.success) {
							if(detachResults.msg?.contains('InvalidInternetGatewayID')){
								performDelete = true
							} else {
								log.error("Error in detaching internet gateway: ${detachResults}")
								performDelete = false
							}
						}
					}

					if(performDelete) {
						def deleteResults = AmazonComputeUtility.deleteInternetGateway(opts)
						if(deleteResults.success || deleteResults.msg?.contains('InvalidInternetGatewayID')) {
							rtn.success = true
						} else {
							rtn.msg = deleteResults.msg ?: 'unknown error removing internet gateway'
						}
					}
				} else {
					rtn.success = true
				}
			} else {
				log.error "Unknown router type: ${router.type}"
			}
		} catch(e) {
			log.error("deleteRouter error: ${e}", e)
			rtn.msg = 'unknown error removing amazon internet gateway'
		}

		return rtn
	}

	/**
	 * Create the NetworkRoute submitted
	 * @param network Network information
	 * @param networkRoute NetworkRoute information
	 * @param opts additional configuration options
	 * @return ServiceResponse
	 */
	ServiceResponse createRouterRoute(NetworkRouter router, NetworkRoute route, Map opts) {
		log.debug "createRoute: ${router}, ${route}, ${opts}"
		def rtn = ServiceResponse.prepare([externalId:null])
		try {
			Cloud cloud = router.cloud
			route.destinationType = opts.route.destinationType

			def poolId = router.poolId ? router.poolId : (router.refType == 'ComputeZonePool' ? router.refId : null)
			String regionCode = router.regionCode
			CloudPool attachedPool
			if(poolId) {
				attachedPool = morpheus.cloud.pool.listById([router.poolId]).toList().blockingGet().getAt(0)
				regionCode = attachedPool?.regionCode
			}
			opts += [
				amazonClient:AmazonComputeUtility.getAmazonClient(cloud,false, regionCode),
				destinationCidrBlock: route.source, destinationType: route.destinationType, destination: route.destination, routeTableId: route.routeTable.externalId
			]

			def apiResults = AmazonComputeUtility.createRoute(opts)
			log.info("route apiResults: {}", apiResults)
			if(apiResults?.success && apiResults?.error != true) {
				rtn.success = true
				route.status = 'active'
				rtn.data.externalId = buildRouteExternalId(apiResults.routeRequest)
			} else {
				rtn.msg = apiResults.msg ?: 'error creating route'
			}
		} catch(e) {
			log.error("createRoute error: ${e}", e)
			rtn.msg = 'unknown error creating route'
		}

		return rtn
	};

	/**
	 * Delete the NetworkRoute submitted
	 * @param networkRoute NetworkRoute information
	 * @return ServiceResponse
	 */
	ServiceResponse deleteRouterRoute(NetworkRouter router, NetworkRoute route, Map opts) {
		log.debug "deleteRoute: ${router}, ${route}"
		def rtn = [success:false, data:[:], msg:null]
		try {
			Cloud cloud = router.cloud
			def poolId = router.poolId ? router.poolId : (router.refType == 'ComputeZonePool' ? router.refId : null)
			String regionCode = router.regionCode
			CloudPool attachedPool
			if(poolId) {
				attachedPool = morpheus.cloud.pool.listById([router.poolId]).toList().blockingGet().getAt(0)
				regionCode = attachedPool?.regionCode
			}
			opts += [
				amazonClient:AmazonComputeUtility.getAmazonClient(cloud,false, regionCode),
				routeTableId: route.routeTable.externalId
			]

			if(route.source?.indexOf(':') > -1) {
				opts.destinationIpv6CidrBlock = route.source
			} else {
				opts.destinationCidrBlock = route.source
			}

			def deleteResults = AmazonComputeUtility.deleteRoute(opts)

			log.info("deleteResults: {}", deleteResults)
			if(deleteResults.success == true) {
				rtn.success = true
			} else if(deleteResults.errorCode == 404) {
				//not found - success
				log.warn("not found")
				rtn.success = true
			} else {
				rtn.msg = deleteResults.msg
			}
		} catch(e) {
			log.error("deleteRoute error: ${e}", e)
			rtn.msg = 'unknown error deleting route'
		}
		return ServiceResponse.create(rtn)
	};

	private buildRouteExternalId(CreateRouteRequest amazonRoute) {
		def externalId = amazonRoute.getDestinationCidrBlock()
		if(amazonRoute.getEgressOnlyInternetGatewayId()){
			externalId += amazonRoute.getEgressOnlyInternetGatewayId()
		} else if(amazonRoute.getGatewayId()){
			externalId += amazonRoute.getGatewayId()
		} else if(amazonRoute.getInstanceId()) {
			externalId += amazonRoute.getInstanceId()
		} else if(amazonRoute.getLocalGatewayId()){
			externalId += amazonRoute.getLocalGatewayId()
		} else if(amazonRoute.getNatGatewayId()){
			externalId += amazonRoute.getNatGatewayId()
		} else if(amazonRoute.getNetworkInterfaceId()){
			externalId += amazonRoute.getNetworkInterfaceId()
		} else if(amazonRoute.getTransitGatewayId()){
			externalId += amazonRoute.getTransitGatewayId()
		} else if(amazonRoute.getVpcPeeringConnectionId()){
			externalId += amazonRoute.getVpcPeeringConnectionId()
		}
		externalId
	}

}
