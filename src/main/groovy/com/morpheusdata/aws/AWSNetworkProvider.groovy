package com.morpheusdata.aws

import com.amazonaws.services.ec2.AmazonEC2Client
import com.morpheusdata.aws.utils.AmazonComputeUtility
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.NetworkProvider
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
				AmazonEC2Client amazonClient = plugin.getAmazonClient(cloud, false, network.zonePool?.regionCode)
				def networkConfig = [:]
				networkConfig.name = network.name
				networkConfig.vpcId = network.zonePool?.externalId
				networkConfig.availabilityZone = network.availabilityZone
				networkConfig.active = network.active
				networkConfig.assignPublicIp = network.assignPublicIp
				networkConfig.type = network.type?.externalType
				networkConfig.cidr = network.cidr
				log.debug("sending network config: {}", networkConfig)
				def apiResults = AmazonComputeUtility.createSubnet(amazonClient, networkConfig, opts)
				log.info("network apiResults: {}", apiResults)
				//create it
				if( apiResults?.success && apiResults?.error != true) {
					rtn.success = true
					network.externalId = apiResults.externalId
					network.regionCode = network.zonePool?.regionCode
					morpheus.network.save(network).blockingGet()
				}
				rtn.data
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
	ServiceResponse deleteNetwork(Network network) {
		log.debug("delete network: {}", network.externalId)
		def rtn = ServiceResponse.prepare()
		//remove the network
		if(network.externalId) {
			AmazonEC2Client amazonClient = plugin.getAmazonClient(network.cloud, false, network.zonePool?.regionCode)
			def deleteResults = AmazonComputeUtility.deleteSubnet(amazonClient, network)
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
	ServiceResponse deleteSubnet(NetworkSubnet subnet, Network network) {
		return ServiceResponse.success()
	}


	/**
	 * Create the NetworkRouter submitted
	 * @param router NetworkRouter information
	 * @param opts additional configuration options
	 * @return ServiceResponse
	 */
	default ServiceResponse createRouter(NetworkRouter router, Map opts) {
		return ServiceResponse.success()
	}

	/**
	 * Update the NetworkRouter submitted
	 * @param router NetworkRouter information
	 * @param opts additional configuration options
	 * @return ServiceResponse
	 */
	default ServiceResponse updateRouter(NetworkRouter router, Map opts) {
		return ServiceResponse.success()
	}

	/**
	 * Delete the NetworkRouter submitted
	 * @param router NetworkRouter information
	 * @return ServiceResponse
	 */
	default ServiceResponse deleteRouter(NetworkRouter router) {
		return ServiceResponse.success()
	}

	/**
	 * Validate the submitted NetworkRoute information.
	 * If a {@link ServiceResponse} is not marked as successful then the validation results will be
	 * bubbled up to the user.
	 * @param network Network information
	 * @param networkRoute NetworkRoute information
	 * @param opts additional configuration options. Mode value will be 'update' for validations during an update vs
	 * creation
	 * @return ServiceResponse
	 */
	default ServiceResponse validateNetworkRoute(Network network, NetworkRoute networkRoute, Map opts) {
		return ServiceResponse.success()
	}

	/**
	 * Create the NetworkRoute submitted
	 * @param network Network information
	 * @param networkRoute NetworkRoute information
	 * @param opts additional configuration options
	 * @return ServiceResponse
	 */
	default ServiceResponse createNetworkRoute(Network network, NetworkRoute networkRoute, Map opts) { return ServiceResponse.success(); };

	/**
	 * Update the NetworkRoute submitted
	 * @param network Network information
	 * @param networkRoute NetworkRoute information
	 * @param opts additional configuration options
	 * @return ServiceResponse
	 */
	default ServiceResponse updateNetworkRoute(Network network, NetworkRoute networkRoute, Map opts) { return ServiceResponse.success(); };

	/**
	 * Delete the NetworkRoute submitted
	 * @param networkRoute NetworkRoute information
	 * @return ServiceResponse
	 */
	default ServiceResponse deleteNetworkRoute(NetworkRoute networkRoute) { return ServiceResponse.success(); };

}
