package com.morpheusdata.aws.sync

import com.amazonaws.services.ec2.model.Snapshot
import com.morpheusdata.aws.AWSPlugin
import com.morpheusdata.aws.utils.AmazonComputeUtility
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.ComputeUtility
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.CloudRegion
import com.morpheusdata.model.Snapshot as SnapshotModel
import com.morpheusdata.model.StorageVolume
import com.morpheusdata.model.StorageVolumeType
import com.morpheusdata.model.projection.CloudRegionIdentity
import com.morpheusdata.model.projection.SnapshotIdentityProjection
import com.morpheusdata.model.projection.StorageVolumeIdentityProjection
import groovy.util.logging.Slf4j
import io.reactivex.Observable

@Slf4j
class SnapshotSync {
	private Cloud cloud
	private MorpheusContext morpheusContext
	private AWSPlugin plugin
	private Map<String, StorageVolumeType> volumeTypes

	SnapshotSync(AWSPlugin plugin, Cloud cloud) {
		this.plugin = plugin
		this.cloud = cloud
		this.morpheusContext = plugin.morpheusContext
	}

	def execute() {
		try {
			def updatedSnapshotIds = []
			morpheusContext.async.cloud.region.listIdentityProjections(cloud.id).blockingSubscribe { region ->
				def volumes = morpheusContext.async.storageVolume.listIdentityProjections(cloud.id, region.externalId).toMap { it.externalId }.blockingGet()
				def amazonClient = plugin.getAmazonClient(cloud, false, region.externalId)
				def cloudItems = AmazonComputeUtility.listSnapshots([amazonClient: amazonClient, zone: cloud]).snapshotList
				Observable<SnapshotIdentityProjection> existingRecords = morpheusContext.async.snapshot.listIdentityProjections(cloud.id, region.externalId)
				SyncTask<SnapshotIdentityProjection, Snapshot, SnapshotModel> syncTask = new SyncTask<>(existingRecords, cloudItems)
				syncTask.addMatchFunction { SnapshotIdentityProjection existingItem, Snapshot cloudItem ->
					existingItem.externalId == cloudItem.snapshotId
				}.withLoadObjectDetailsFromFinder { List<SyncTask.UpdateItemDto<SnapshotIdentityProjection, SnapshotModel>> updateItems ->
					morpheusContext.async.snapshot.listById(updateItems.collect { it.existingItem.id } as List<Long>)
				}.onAdd { itemsToAdd ->
					updatedSnapshotIds += addMissingSnapshots(itemsToAdd, region, volumes)
				}.onUpdate { List<SyncTask.UpdateItem<SnapshotModel, Snapshot>> updateItems ->
					updatedSnapshotIds += updateMatchedSnapshots(updateItems, region)
				}.onDelete { removeItems ->
					removeMissingSnapshots(removeItems)
				}.observe().blockingSubscribe { completed ->
					if(updatedSnapshotIds) {
						morpheusContext.async.usage.restartSnapshotUsage(updatedSnapshotIds).blockingGet()
					}
				}
			}
		} catch(Exception ex) {
			log.error("SnapshotSync error: {}", ex, ex)
		}
	}

	private List<Long> addMissingSnapshots(Collection<Snapshot> addList, CloudRegionIdentity region, Map<String, StorageVolumeIdentityProjection> volumes) {
		log.debug "addMissingSnapshots: ${cloud} ${region.externalId} ${addList.size()}"
		def adds = []

		for(Snapshot cloudItem in addList) {
			StorageVolumeIdentityProjection volume = volumes[cloudItem.volumeId]
			if(volume) {
				adds << new SnapshotModel(
					account: cloud.account,
					cloud: cloud,
					name: cloudItem.snapshotId,
					externalId: cloudItem.snapshotId,
					snapshotCreated: cloudItem.startTime,
					region: new CloudRegion(id: region.id),
					volume: new StorageVolume(id: volume.id),
					volumeType: allVolumeTypes['s3Object'],
					maxStorage: (cloudItem.volumeSize ?: 0) * ComputeUtility.ONE_GIGABYTE
				)
			}
		}

		// Create em all!
		if(adds) {
			log.debug "About to create ${adds.size()} snapshots"
			return morpheusContext.async.snapshot.bulkCreate(adds).blockingGet().persistedItems?.collect{it.id} as List<Long>
		} else {
			return [] as List<Long>
		}
	}

	private List<Long> updateMatchedSnapshots(List<SyncTask.UpdateItem<SnapshotModel, Snapshot>> updateList, CloudRegionIdentity region) {
		log.debug "updateMatchedSnapshots: ${cloud} ${region.externalId} ${updateList.size()}"
		def saveList = []

		for(def updateItem in updateList) {
			def existingItem = updateItem.existingItem
			def cloudItem = updateItem.masterItem
			def save

			if(existingItem.maxStorage != (cloudItem.volumeSize ?: 0) * ComputeUtility.ONE_GIGABYTE) {
				existingItem.maxStorage = (cloudItem.volumeSize ?: 0) * ComputeUtility.ONE_GIGABYTE
				save = true
			}

			if(!existingItem.pricePlan) {
				save = true
			}

			if(save) {
				saveList << existingItem
			}
		}

		if(saveList) {
			log.debug "About to update ${saveList.size()} snapshots"
			morpheusContext.async.snapshot.bulkSave(saveList).blockingGet()
		}
		return saveList.collect{ it.id } as List<Long>
	}

	private removeMissingSnapshots(Collection<SnapshotIdentityProjection> removeList) {
		log.debug "removeMissingSnapshots: ${cloud} ${removeList.size()}"
		morpheusContext.async.snapshot.remove(removeList).blockingGet()
	}

	private getAllVolumeTypes() {
		volumeTypes ?: (volumeTypes = morpheusContext.async.storageVolume.storageVolumeType.listAll().toMap { it.code }.blockingGet())
	}
}
