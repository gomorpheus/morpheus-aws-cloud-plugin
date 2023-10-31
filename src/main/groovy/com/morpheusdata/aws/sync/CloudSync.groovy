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
			//upload missing key pairs
			ensureKeyPairs()
		} catch(Exception ex) {
			log.error("CloudSync error: {}", ex, ex)
		}
	}

	protected ensureKeyPairs() {
		def save
		def keyPair = morpheusContext.async.cloud.findOrGenerateKeyPair(cloud.account).blockingGet()

		morpheusContext.async.cloud.region.listIdentityProjections(cloud.id).blockingSubscribe { region ->
			def amazonClient = plugin.getAmazonClient(cloud, false, region.externalId)
			def keyLocationId = "amazon-${cloud.id}-${region.externalId}".toString()
			def keyResults = AmazonComputeUtility.uploadKeypair(
				[key: keyPair, account: cloud.account, zone: cloud, keyName: keyPair.getConfigProperty(keyLocationId), amazonClient:amazonClient]
			)
			if(keyResults.success) {
				if (keyResults.uploaded) {
					keyPair.setConfigProperty(keyLocationId, keyResults.keyName)
					save = true
				}
			} else {
				log.error "unable to upload keypair"
			}
		}

		if(save) {
			morpheusContext.async.keyPair.save([keyPair]).blockingGet()
		}
	}
}
