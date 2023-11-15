package com.morpheusdata.aws.sync

import com.morpheusdata.aws.AWSPlugin
import com.morpheusdata.aws.utils.AmazonComputeUtility
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.model.Cloud
import groovy.util.logging.Slf4j

@Slf4j
class CloudSync {

	private Cloud cloud
	private MorpheusContext morpheusContext
	private AWSPlugin plugin

	CloudSync(AWSPlugin plugin, Cloud cloud) {
		this.plugin = plugin
		this.cloud = cloud
		this.morpheusContext = plugin.morpheusContext
	}

	def execute() {
		try {
			def securityClient = AmazonComputeUtility.getAmazonSecurityClient(cloud, false, null).amazonClient
			def identityResults = AmazonComputeUtility.getClientIdentity(securityClient, [:])
			def awsAccountId = identityResults.results?.account

			if(cloud.externalId != awsAccountId) {
				cloud.externalId = awsAccountId
				morpheusContext.services.cloud.save(cloud)
			}
		} catch(Exception ex) {
			log.error("CloudSync error: {}", ex, ex)
		}
	}
}
