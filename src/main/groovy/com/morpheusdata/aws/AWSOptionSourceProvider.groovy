package com.morpheusdata.aws

import com.morpheusdata.aws.utils.AmazonComputeUtility
import com.morpheusdata.core.AbstractOptionSourceProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ReferenceData;
import com.morpheusdata.core.util.MorpheusUtils;
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
		return new ArrayList<String>(['awsPluginVpc', 'awsPluginRegions', 'amazonAvailabilityZones'])
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

		Cloud cloud = args.zoneId ? morpheusContext.cloud.getCloudById(args.zoneId.toLong()).blockingGet() : null
		if(!cloud) {
			cloud = new Cloud()
		}
		if(args.credential) {
			def credentialDataResponse = morpheusContext.accountCredential.loadCredentialConfig(args.credential, args.config).blockingGet()
			if(credentialDataResponse.success) {
				cloud.accountCredentialData = credentialDataResponse.data
				cloud.accountCredentialLoaded = true
			}
		}

		def config = [
			accessKey: args.accessKey ?: args.config?.accessKey ?: cloud.getConfigProperty('accessKey'),
			secretKey: args.secretKey ?: args.config?.secretKey ?: cloud.getConfigProperty('secretKey'),
			stsAssumeRole: (args.stsAssumeRole ?: args.config?.stsAssumeRole ?: cloud.getConfigProperty('stsAssumeRole')) in [true, 'true', 'on'],
			useHostCredentials: (args.useHostCredentials ?: cloud.getConfigProperty('useHostCredentials')) in [true, 'true', 'on'],
			endpoint: args.endpoint ?: args.config?.endpoint ?: cloud.getConfigProperty('endpoint')
		]
		if (config.secretKey == '*' * 12) {
			config.remove('secretKey')
		}
		cloud.setConfigMap(cloud.getConfigMap() + config)

		def proxy = args.apiProxy ? morpheusContext.network.networkProxy.getById(args.long('apiProxy')).blockingGet() : null
		cloud.apiProxy = proxy

		def rtn
		if(AmazonComputeUtility.testConnection(cloud).success) {
			def amazonClient = AmazonComputeUtility.getAmazonClient(cloud, true)
			def vpcResult = AmazonComputeUtility.listVpcs([amazonClient:amazonClient])
			if(vpcResult.success) {
				rtn = vpcResult.vpcList.collect {[name:"${it.vpcId} - ${it.tags?.find { tag -> tag.key == 'Name' }?.value ?: 'default'}", value:it.vpcId]}
			}
		}
		return rtn ?: []
	}

	def amazonAvailabilityZones(args) {
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
}
