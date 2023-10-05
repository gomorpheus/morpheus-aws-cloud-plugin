package com.morpheusdata.aws.sync

import com.amazonaws.services.ec2.model.KeyPairInfo
import com.morpheusdata.aws.AWSPlugin
import com.morpheusdata.aws.utils.AmazonComputeUtility
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.KeyPair
import com.morpheusdata.model.projection.CloudRegionIdentity
import com.morpheusdata.model.projection.InstanceScaleIdentityProjection
import com.morpheusdata.model.projection.KeyPairIdentityProjection
import groovy.util.logging.Slf4j
import io.reactivex.Observable

@Slf4j
class KeyPairSync {
	private Cloud cloud
	private MorpheusContext morpheusContext
	private AWSPlugin plugin
	private Map<String, InstanceScaleIdentityProjection> scaleTypes

	KeyPairSync(AWSPlugin plugin, Cloud cloud) {
		this.plugin = plugin
		this.cloud = cloud
		this.morpheusContext = plugin.morpheusContext
	}

	def execute() {
		try {
			morpheusContext.async.cloud.region.listIdentityProjections(cloud.id).blockingSubscribe { region ->
				def amazonClient = plugin.getAmazonClient(cloud, false, region.externalId)
				List<KeyPairInfo> cloudItems = AmazonComputeUtility.listKeypairs([amazonClient: amazonClient]).keyPairs
				Observable<KeyPairIdentityProjection> existingRecords = morpheusContext.async.keyPair.listIdentityProjections(cloud.id, region.externalId)
				SyncTask<KeyPairIdentityProjection, KeyPairInfo, KeyPair> syncTask = new SyncTask<>(existingRecords, cloudItems)
				syncTask.addMatchFunction { KeyPairIdentityProjection existingItem, KeyPairInfo cloudItem ->
					existingItem.externalId == cloudItem.keyPairId
				}.addMatchFunction { KeyPairIdentityProjection existingItem, KeyPairInfo cloudItem ->
					existingItem.externalId == cloudItem.keyName
				}.withLoadObjectDetailsFromFinder { List<SyncTask.UpdateItemDto<KeyPairIdentityProjection, KeyPair>> updateItems ->
					morpheusContext.async.keyPair.listById(updateItems.collect { it.existingItem.id } as List<Long>)
				}.onAdd { itemsToAdd ->
					addMissingKeyPairs(itemsToAdd, region)
				}.onUpdate { List<SyncTask.UpdateItem<KeyPair, KeyPairInfo>> updateItems ->
					updateMatchedKeyPairs(updateItems, region)
				}.onDelete { removeItems ->
					removeMissingKeyPairs(removeItems)
				}.start()
			}
		} catch(Exception ex) {
			log.error("KeyPairSync error: {}", ex, ex)
		}
	}

	private addMissingKeyPairs(Collection<KeyPairInfo> addList, CloudRegionIdentity region) {
		log.debug "addMissingKeyPairs: ${cloud} ${region.externalId} ${addList.size()}"
		List adds = []
		for (KeyPairInfo cloudItem in addList) {
			adds << new KeyPair(
				accountId: cloud.account.id,
				name: cloudItem.keyName,
				internalId: cloudItem.keyFingerprint,
				externalId: cloudItem.keyPairId,
				scope: 'account',
				refType: 'computeZone',
				refId: cloud.id,
				refName: cloud.name,
				publicFingerprint: cloudItem.keyFingerprint,
				regionCode: region.externalId
			)
		}

		log.debug "About to create ${adds.size()} keypairs"
		if(adds) {
			morpheusContext.async.keyPair.bulkCreate(adds).blockingGet()
		}
	}

	private updateMatchedKeyPairs(List<SyncTask.UpdateItem<KeyPair, KeyPairInfo>> updateList, CloudRegionIdentity region) {
		log.debug "updateMatchedKeyPairs: ${cloud} ${region.externalId} ${updateList.size()}"
		def saveList = []

		for(def updateItem in updateList) {
			def existingItem = updateItem.existingItem
			def cloudItem = updateItem.masterItem

			if(existingItem) {
				def save = false

				if(existingItem.name != cloudItem.keyName) {
					existingItem.name = cloudItem.keyName
					save = true
				}
				if(existingItem.externalId != cloudItem.keyPairId) {
					existingItem.externalId = cloudItem.keyPairId
					save = true
				}
				if(save) {
					saveList << save
				}
			}
		}

		if(saveList) {
			log.debug "About to update ${saveList.size()} keypairs"
			morpheusContext.async.keyPair.bulkSave(saveList).blockingGet()
		}
	}

	private removeMissingKeyPairs(List<KeyPairIdentityProjection> removeList) {
		log.debug "removeMissingKeyPairs: ${cloud} ${removeList.size()}"
		morpheusContext.async.keyPair.bulkRemove(removeList).blockingGet()
	}
}
