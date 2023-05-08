package com.morpheusdata.aws

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.NetworkProvider
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.Network
import com.morpheusdata.model.NetworkRouterType
import com.morpheusdata.model.NetworkSubnet
import com.morpheusdata.model.NetworkType
import com.morpheusdata.response.ServiceResponse

class AWSNetworkProvider implements NetworkProvider {

	Plugin plugin
	MorpheusContext morpheus

	final String code = 'amazon-network-server'
	final String name = 'Amazon'
	final String description = 'AWS EC2'


	AWSNetworkProvider(AWSPlugin plugin, MorpheusContext morpheusContext) {
		this.plugin = plugin
		this.morpheus = morpheusContext
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
/**
	 * Validates the submitted network information.
	 * If a {@link ServiceResponse} is not marked as successful then the validation results will be
	 * bubbled up to the user.
	 * @param network Network information
	 * @param opts additional configuration options
	 * @return ServiceResponse
	 */
	@Override
	ServiceResponse validateNetwork(Network network, Map opts) {
		return null
	}

	/**
	 * Creates the Network submitted
	 * @param network Network information
	 * @param opts additional configuration options
	 * @return ServiceResponse
	 */
	@Override
	ServiceResponse createNetwork(Network network, Map opts) {
		return null
	}

	/**
	 * Updates the Network submitted
	 * @param network Network information
	 * @param opts additional configuration options
	 * @return ServiceResponse
	 */
	@Override
	ServiceResponse updateNetwork(Network network, Map opts) {
		return null
	}

	/**
	 * Deletes the Network submitted
	 * @param network Network information
	 * @return ServiceResponse
	 */
	@Override
	ServiceResponse deleteNetwork(Network network) {
		return null
	}

	/**
	 * Validates the submitted subnet information.
	 * If a {@link ServiceResponse} is not marked as successful then the validation results will be
	 * bubbled up to the user.
	 * @param subnet NetworkSubnet information
	 * @param network Network to create the NetworkSubnet on
	 * @param opts additional configuration options. Mode value will be 'update' for validations during an update vs
	 * creation
	 * @return ServiceResponse
	 */
	@Override
	ServiceResponse validateSubnet(NetworkSubnet subnet, Network network, Map opts) {
		return null
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
		return null
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
		return null
	}

	/**
	 * Deletes the NetworkSubnet submitted
	 * @param subnet NetworkSubnet information
	 * @param network Network that this NetworkSubnet is attached to
	 * @return ServiceResponse
	 */
	@Override
	ServiceResponse deleteSubnet(NetworkSubnet subnet, Network network) {
		return null
	}



}
