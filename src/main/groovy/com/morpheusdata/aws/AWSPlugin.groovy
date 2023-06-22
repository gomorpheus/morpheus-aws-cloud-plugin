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
		def cloudProvider = new AWSCloudProvider(this, this.morpheus)
		def optionSourceProvider = new AWSOptionSourceProvider(this, this.morpheus)
		def networkProvider = new AWSNetworkProvider(this, this.morpheus)
		def dnsProvider = new Route53DnsProvider(this, this.morpheus)
		this.pluginProviders.put(provisionProvider.code, provisionProvider)
		this.pluginProviders.put(cloudProvider.code, cloudProvider)
		this.pluginProviders.put(optionSourceProvider.code, optionSourceProvider)
		this.pluginProviders.put(networkProvider.code, networkProvider)
		this.pluginProviders.put(dnsProvider.code, dnsProvider)

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

	def AWSNetworkProvider getDnsProvider() {
		this.getProviderByCode(dnsProviderCode)
	}

	def getAmazonClient(Cloud cloud, Boolean fresh = false, String region=null) {
		if(!cloud.accountCredentialLoaded) {
			AccountCredential accountCredential
			try {
				accountCredential = this.morpheus.cloud.loadCredentials(cloud.id).blockingGet()
			} catch(e) {
				// If there is no credential on the cloud, then this will error
				// TODO: Change to using 'maybe' rather than 'blockingGet'?
			}
			cloud.accountCredentialLoaded = true
			cloud.accountCredentialData = accountCredential?.data
		}
		return AmazonComputeUtility.getAmazonClient(cloud, fresh, region)
	}
}
