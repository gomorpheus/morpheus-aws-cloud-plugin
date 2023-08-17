package com.morpheusdata.aws

import com.morpheusdata.aws.utils.AmazonComputeUtility
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.AccountCredential
import com.morpheusdata.model.Cloud
import groovy.util.logging.Slf4j

@Slf4j
class AWSPlugin extends Plugin {

	private String cloudProviderCode
	private String networkProviderCode
	private String dnsProviderCode

	@Override
	String getCode() {
		return 'morpheus-aws-plugin'
	}

	@Override
	void initialize() {
		this.setName('Amazon Web Services')
		def provisionProvider = new EC2ProvisionProvider(this, this.morpheus)
		def cloudFormationProvisionProvider = new CloudFormationProvisionProvider(this, this.morpheus)
		def cloudProvider = new AWSCloudProvider(this, this.morpheus)
		def optionSourceProvider = new AWSOptionSourceProvider(this, this.morpheus)
		def networkProvider = new AWSNetworkProvider(this, this.morpheus)
		def dnsProvider = new Route53DnsProvider(this, this.morpheus)
		def scaleProvider = new AWSScaleProvider(this, this.morpheus)

		// load balancer providers
		def albProvider = new ALBLoadBalancerProvider(this, this.morpheus)
		def elbProvider = new ELBLoadBalancerProvider(this, this.morpheus)
		this.pluginProviders.put(albProvider.code, albProvider)
		this.pluginProviders.put(elbProvider.code, elbProvider)

		this.pluginProviders.put(provisionProvider.code, provisionProvider)
		this.pluginProviders.put(cloudFormationProvisionProvider.provisionTypeCode, cloudFormationProvisionProvider)
		this.pluginProviders.put(cloudProvider.code, cloudProvider)

		// option source providers
		def lbOptionSourceProvider = new LoadBalancerOptionSourceProvider(this, this.morpheus)
		this.pluginProviders.put(lbOptionSourceProvider.code, lbOptionSourceProvider)
		this.pluginProviders.put(optionSourceProvider.code, optionSourceProvider)

		this.pluginProviders.put(networkProvider.code, networkProvider)
		this.pluginProviders.put(dnsProvider.code, dnsProvider)
		this.pluginProviders.put(scaleProvider.code, scaleProvider)

		cloudProviderCode = cloudProvider.code
		networkProviderCode = networkProvider.code
		dnsProviderCode = dnsProvider.code
	}

	@Override
	void onDestroy() {

	}

	def MorpheusContext getMorpheusContext() {
		this.morpheus
	}
	
	def AWSCloudProvider getCloudProvider() {
		this.getProviderByCode(cloudProviderCode)
	}

	def AWSNetworkProvider getNetworkProvider() {
		this.getProviderByCode(networkProviderCode)
	}

	def Route53DnsProvider getDnsProvider() {
		this.getProviderByCode(dnsProviderCode)
	}

	def getAmazonClient(Cloud cloud, Boolean fresh = false, String region = null) {
		return AmazonComputeUtility.getAmazonClient(checkCloudCredentials(cloud), fresh, region)
	}

	def getAmazonElbClient(Cloud cloud, Boolean fresh = false, String region = null) {
		return AmazonComputeUtility.getAmazonElbClient(checkCloudCredentials(cloud), fresh, region)
	}

	def getAmazonAutoScaleClient(Cloud cloud, Boolean fresh = false, String region = null) {
		return AmazonComputeUtility.getAmazonAutoScalingClient(checkCloudCredentials(cloud), fresh, region)
	}

	def getAmazonCloudFormationClient(cloud, Boolean fresh = false, String region = null) {
		AmazonComputeUtility.getAmazonCloudFormationClient(checkCloudCredentials(cloud), fresh, region)
	}

	protected Cloud checkCloudCredentials(Cloud cloud) {
		if(!cloud.accountCredentialLoaded) {
			AccountCredential accountCredential
			try {
				accountCredential = this.morpheus.async.cloud.loadCredentials(cloud.id).blockingGet()
			} catch(e) {
				// If there is no credential on the cloud, then this will error
				// TODO: Change to using 'maybe' rather than 'blockingGet'?
			}
			cloud.accountCredentialLoaded = true
			cloud.accountCredentialData = accountCredential?.data
		}
		return cloud
	}
}
