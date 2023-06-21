package com.morpheusdata.aws.sync

import com.amazonaws.services.ec2.model.Instance
import com.amazonaws.services.ec2.model.Volume
import com.morpheus.util.ComputeUtility
import com.morpheusdata.aws.AWSPlugin
import com.morpheusdata.aws.utils.AmazonComputeUtility
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.*
import com.morpheusdata.model.projection.StorageVolumeIdentityProjection
import groovy.util.logging.Slf4j
import io.reactivex.Observable

@Slf4j
class VolumeSync {
	private Cloud cloud
	private MorpheusContext morpheusContext
	private AWSPlugin plugin
	private Map<String, StorageVolumeIdentityProjection> storageVolumeTypes

	VolumeSync(AWSPlugin plugin, Cloud cloud) {
		this.plugin = plugin
		this.cloud = cloud
		this.morpheusContext = plugin.morpheusContext
	}

	def execute() {
		morpheusContext.cloud.region.listIdentityProjections(cloud.id).blockingSubscribe { region ->
			def amazonClient = AmazonComputeUtility.getAmazonClient(cloud, false, region.externalId)
			def cloudItems = AmazonComputeUtility.listVolumes([amazonClient: amazonClient, zone: cloud]).volumeList
			Observable<StorageVolumeIdentityProjection> existingRecords =  morpheusContext.storageVolume.listIdentityProjections(cloud.id, region.externalId)
			SyncTask<StorageVolumeIdentityProjection, Volume, StorageVolume> syncTask = new SyncTask<>(existingRecords, cloudItems)
			syncTask.addMatchFunction { StorageVolumeIdentityProjection existingItem, Volume cloudItem ->
				existingItem.externalId == cloudItem.volumeId
			}.withLoadObjectDetailsFromFinder { List<SyncTask.UpdateItemDto<StorageVolumeIdentityProjection, StorageVolume>> updateItems ->
				morpheusContext.storageVolume.listById(updateItems.collect { it.existingItem.id } as List<Long>)
			}.onAdd { itemsToAdd ->
				addMissingStorageVolumes(itemsToAdd, region.externalId)
			}.onUpdate { List<SyncTask.UpdateItem<ComputeServer, Instance>> updateItems ->
				updateMatchedStorageVolumes(updateItems, region.externalId)
			}.onDelete { removeItems ->
				removeMissingStorageVolumes(removeItems)
			}.start()
		}
	}

	private addMissingStorageVolumes(Collection<StorageVolumeIdentityProjection> addList, String regionCode) {
		log.debug "addMissingStorageVolumes: ${cloud} ${regionCode} ${addList.size()}"
		def adds = []
		for(Volume cloudItem in addList) {
			def storageVolumeType = allStorageVolumeTypes["amazon-${cloudItem.volumeType}".toString()]
			if(storageVolumeType) {
				def newVolume = new StorageVolume([
					account: cloud.account, maxStorage: cloudItem.size * ComputeUtility.ONE_GIGABYTE,
					type: new StorageVolumeType(id: storageVolumeType.id), externalId: cloudItem.volumeId,
					name: cloudItem.tags?.find{it.key == 'Name'}?.value ?: cloudItem.volumeId,
					refType:' ComputeZone', refId: cloud.id, regionCode: regionCode, status: 'unattached'
				])

				if(cloudItem.attachments) {
					newVolume.deviceName = cloudItem.attachments.first().device
					newVolume.deviceDisplayName = AmazonComputeUtility.extractDiskDisplayName(newVolume.deviceName)
					newVolume.status = cloudItem.attachments.first().state
				}
				adds << newVolume
			}
		}

		if(adds) {
			morpheusContext.storageVolume.create(adds).blockingGet()
		}
	}

	private updateMatchedStorageVolumes(List<SyncTask.UpdateItem<StorageVolume, Volume>> updateList, String regionCode) {
		log.debug "updateMatchedStorageVolumes: ${cloud} ${regionCode} ${addList.size()}"
		def saveList = []

		for(def updateItem in updateList) {
			def existingItem = updateItem.existingItem
			def cloudItem = updateItem.masterItem
			def save = false
			def name = cloudItem.tags?.find {it.key == 'Name'}?.value ?: cloudItem.volumeId
			def status = 'unattached'
			def maxStorage = cloudItem.size * ComputeUtility.ONE_GIGABYTE

			if(existingItem.name != name && name != cloudItem.volumeId) {
				existingItem.name = name
				save = true
			}

			if(cloudItem.attachments) {
				def attachment = cloudItem.attachments.first()

				if(existingItem.deviceName != attachment.device) {
					existingItem.deviceName = attachment.device
					save = true
				}
				status = attachment.state
			}

			def deviceDisplayName = AmazonComputeUtility.extractDiskDisplayName(existingItem.deviceName)
			if(existingItem.deviceDisplayName != deviceDisplayName) {
				existingItem.deviceDisplayName = deviceDisplayName
				save = true
			}

			if(status != existingItem.status) {
				existingItem.status = status
				save = true
			}

			if(existingItem.regionCode != regionCode) {
				existingItem.regionCode = regionCode
				save = true
			}

			if(existingItem.maxStorage != maxStorage) {
				existingItem.maxStorage = maxStorage
				save = true
			}

			if(save) {
				saveList << existingItem
			}
		}

		if(saveList) {
			morpheusContext.storageVolume.save(saveList).blockingGet()
		}
	}

	private removeMissingStorageVolumes(Collection<StorageVolume> removeList) {
		log.debug "removeMissingStorageVolumes: ${cloud} ${removeList.size()}"
		morpheusContext.storageVolume.remove(removeList).blockingGet()
	}

	private Map<String, StorageVolumeType> getAllStorageVolumeTypes() {
		storageVolumeTypes ?: (storageVolumeTypes = morpheusContext.storageVolume.storageVolumeType.listAll().toMap {it.code}.blockingGet())
	}
}
