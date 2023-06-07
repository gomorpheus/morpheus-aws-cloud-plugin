package com.morpheusdata.aws

import com.morpheusdata.core.AbstractOptionSourceProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ReferenceData;
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
		return new ArrayList<String>(['awsPluginVpc', 'awsPluginRegions'])
	}

	def awsPluginRegions(args) {
		ReferenceData.findAllByCategory("amazon.ec2.region", [sort:'name']).collect { [value: it.value, name: it.name] }
	}

	def awsPluginVpc(args) {
		Cloud cloud = args.zoneId ? morpheusContext.cloud.getCloudById(args.zoneId.toLong()).blockingGet() : null
		def configMap = cloud.getConfigMap()
		authConfig.clientEmail = configMap.clientEmail
		authConfig.privateKey = configMap.privateKey
		authConfig.projectId = configMap.projectId

		def zone = args.zoneId ? zoneService.loadFullZone(args.zoneId.toLong()) : [:]

		if(params.credential) {
			zone.credentialData = credentialService.loadCredentialConfig(params.credential, params.config).data
			zone.credentialLoaded = true
		}

		def config = [
			accessKey: params.accessKey ?: zone.getConfigProperty('accessKey'),
			secretKey: params.secretKey ?: zone.getConfigProperty('secretKey'),
			stsAssumeRole: params.stsAssumeRole ?: zone.getConfigProperty('stsAssumeRole'),
			useHostCredentials: (params.useHostCredentials ?: zone.getConfigProperty('useHostCredentials')) in [true, 'true', 'on'],
			endpoint: params.endpoint ?: params.config?.endpoint ?: zone.getConfigProperty('endpoint')
		]
		if (config.secretKey == '*' * 12) {
			config.remove('secretKey')
		}
		zone.setConfigMap(zone.getConfigMap() + config)
		def proxy = params.apiProxy ? NetworkProxy.get(params.long('apiProxy')) : null
		zone.apiProxy = proxy

		def rtn
		if(amazonComputeService.testConnection(zone).success) {
			def vpcResult = amazonComputeService.listVpcs([zone: zone, fresh: true])
			if(vpcResult.success) {
				rtn = vpcResult.vpcList.collect {[name:"${it.vpcId} - ${it.tags?.find { tag -> tag.key == 'Name' }?.value ?: 'default'}", value:it.vpcId]}
			}
		}
		rtn ?: []
	}
}
