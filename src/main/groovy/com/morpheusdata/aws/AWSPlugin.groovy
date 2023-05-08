package com.morpheusdata.aws

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.AccountCredential
import com.morpheusdata.model.Cloud
import groovy.util.logging.Slf4j

@Slf4j
class AWSPlugin extends Plugin {

	private String cloudProviderCode

	@Override
	String getCode() {
		return 'morpheus-aws-plugin'
	}

	@Override
	void initialize() {
		this.setName('Amazon Web Services')
		// def nutanixProvision = new NutanixPrismProvisionProvider(this, this.morpheus)
		// def nutanixPrismCloud = new NutanixPrismCloudProvider(this, this.morpheus)
		// cloudProviderCode = nutanixPrismCloud.code
		// def nutanixPrismOptionSourceProvider = new NutanixPrismOptionSourceProvider(this, morpheus)

		// this.pluginProviders.put(nutanixProvision.code, nutanixProvision)
		// this.pluginProviders.put(nutanixPrismCloud.code, nutanixPrismCloud)
		// this.pluginProviders.put(nutanixPrismOptionSourceProvider.code, nutanixPrismOptionSourceProvider)
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

}
