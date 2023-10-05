package com.morpheusdata.aws.sync

import com.amazonaws.services.ec2.model.Image
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataOrFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.util.ComputeUtility
import com.morpheusdata.aws.AWSPlugin
import com.morpheusdata.aws.utils.AmazonComputeUtility
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ImageType
import com.morpheusdata.model.MetadataTag
import com.morpheusdata.model.OsType
import com.morpheusdata.model.VirtualImage
import com.morpheusdata.model.VirtualImageLocation
import com.morpheusdata.model.projection.VirtualImageIdentityProjection
import com.morpheusdata.model.projection.VirtualImageLocationIdentityProjection
import groovy.json.JsonOutput
import groovy.util.logging.Slf4j
import io.reactivex.Observable

@Slf4j
class ImageSync {
	private Cloud cloud
	private MorpheusContext morpheusContext
	private AWSPlugin plugin
	private Map<String, VirtualImageIdentityProjection> virtualImages
	private Map<String, OsType> osTypes

	ImageSync(AWSPlugin plugin, Cloud cloud) {
		this.plugin = plugin
		this.cloud = cloud
		this.morpheusContext = plugin.morpheusContext
	}

	def execute() {
		try {
			morpheusContext.async.cloud.region.listIdentityProjectionsForRegionsWithCloudPools(cloud.id).blockingSubscribe { region ->
				def amazonClient = plugin.getAmazonClient(cloud, false, region.externalId)
				def cloudItems = AmazonComputeUtility.listImages([amazonClient: amazonClient, zone: cloud]).imageList
				Observable<VirtualImageLocationIdentityProjection> existingRecords = morpheusContext.async.virtualImage.location.listIdentityProjections(cloud.id, region.externalId)
				SyncTask<VirtualImageLocationIdentityProjection, Image, VirtualImageLocation> syncTask = new SyncTask<>(existingRecords, cloudItems)
				syncTask.addMatchFunction { VirtualImageLocationIdentityProjection existingItem, Image cloudItem ->
					existingItem.externalId == cloudItem.imageId
				}.withLoadObjectDetailsFromFinder { List<SyncTask.UpdateItemDto<VirtualImageLocationIdentityProjection, VirtualImageLocation>> updateItems ->
					morpheusContext.async.virtualImage.location.listById(updateItems.collect { it.existingItem.id } as List<Long>)
				}.onAdd { itemsToAdd ->
					addMissingVirtualImageLocations(itemsToAdd, region.externalId)
				}.onUpdate { List<SyncTask.UpdateItem<VirtualImageLocation, Image>> updateItems ->
					updateMatchedVirtualImageLocations(updateItems, region.externalId)
				}.onDelete { removeItems ->
					removeMissingVirtualImages(removeItems)
				}.start()
			}
		} catch(Exception ex) {
			log.error("ImageSync error: {}", ex, ex)
		}
	}

	private addMissingVirtualImageLocations(Collection<Image> addList, String regionCode) {
		// check for images w/o location, i.e. system and user defined
		log.debug "addMissingVirtualImages: ${cloud} ${regionCode} ${addList.size()}"

		List<String> names = addList.collect { it.name }
		Observable<VirtualImageIdentityProjection> existingRecords = morpheusContext.async.virtualImage.listIdentityProjections(new DataQuery().withFilters(
				new DataFilter<String>("imageType", "ami"),
				new DataFilter<Collection<String>>("name", "in", names),
				new DataOrFilter(
						new DataFilter<Boolean>("systemImage", true),
						new DataOrFilter(
								new DataFilter("owner", null),
								new DataFilter<Long>("owner.id", cloud.owner.id)
						)
				)
		))

		SyncTask<VirtualImageIdentityProjection, Image, VirtualImage> syncTask = new SyncTask<>(existingRecords, addList)
		syncTask.addMatchFunction { VirtualImageIdentityProjection existingItem, Image cloudItem ->
			existingItem.name == cloudItem.name
		}.withLoadObjectDetailsFromFinder { List<SyncTask.UpdateItemDto<VirtualImageIdentityProjection, VirtualImage>> updateItems ->
			morpheusContext.async.virtualImage.listById(updateItems.collect { it.existingItem.id } as List<Long>)
		}.onAdd { itemsToAdd ->
			addMissingVirtualImages(itemsToAdd, regionCode)
		}.onUpdate { List<SyncTask.UpdateItem<VirtualImage, Image>> updateItems ->
			updateMatchedVirtualImages(updateItems, regionCode)
		}.onDelete { removeItems ->
			//nothing to see here
		}.start()
	}

	private addMissingVirtualImages(Collection<Image> addList, String regionCode) {
		def adds = []
		def imageMap = [:]
		for(Image cloudItem in addList) {
			String name = cloudItem.name ?: cloudItem.imageId

			def blockDeviceMap = cloudItem.blockDeviceMappings
			def blockDeviceConfig = []
			blockDeviceMap.each { blockDevice ->
				blockDeviceConfig << [
					deviceName : blockDevice.getDeviceName(), ebs: blockDevice.getEbs(), noDevice: blockDevice.getNoDevice(),
					virtualName: blockDevice.getVirtualName()
				]
			}
			def productCodeConfig = cloudItem.productCodes?.collect { [id: it.productCodeId, type: it.productCodeType] } ?: []
			def imageConfig = [
					category          : "amazon.ec2.image.${cloud.id}", imageRegion: regionCode, name: cloudItem.name ?: cloudItem.imageId,
					code              : "amazon.ec2.image.${cloud.id}.${cloudItem.imageId}", imageType: 'ami', externalType: cloudItem.imageType,
					kernelId          : cloudItem.kernelId, architecture: cloudItem.architecture, description: cloudItem.description, minDisk: 10,
					minRam            : 512l * ComputeUtility.ONE_MEGABYTE, remotePath: cloudItem.imageLocation, hypervisor: cloudItem.hypervisor,
					platform          : (cloudItem.platform == 'windows' ? 'windows' : 'linux'),
					productCode       : JsonOutput.toJson(productCodeConfig), externalId: cloudItem.imageId,
					ramdiskId         : cloudItem.ramdiskId, isCloudInit: (cloudItem.platform == 'windows' ? false : true),
					rootDeviceName    : cloudItem.rootDeviceName, rootDeviceType: cloudItem.rootDeviceType, enhancedNetwork: cloudItem.sriovNetSupport,
					status            : cloudItem.state == 'available' ? 'Active' : cloudItem.state, statusReason: cloudItem.stateReason,
					virtualizationType: cloudItem.virtualizationType, isPublic: cloudItem.public, refType: 'ComputeZone', refId: "${cloud.id}",
					owner             : cloud.owner, account: cloud.owner
			]

			imageConfig.externalDiskId = blockDeviceMap.find { mapping -> mapping.deviceName == cloudItem.rootDeviceName }?.ebs?.snapshotId
			if (imageConfig.platform == 'windows') {
				imageConfig.osType = new OsType(code: 'windows')
			} else {
				imageConfig.osType = new OsType(code: 'linux')
			}
			def add = new VirtualImage(imageConfig)
			add.blockDeviceConfig = JsonOutput.toJson(blockDeviceConfig)

			def locationConfig = [code:"amazon.ec2.image.${cloud.id}.${cloudItem.getImageId()}", externalId:cloudItem.getImageId(),
								  refType:'ComputeZone', refId:cloud.id, imageName:cloudItem.getName(), imageRegion: regionCode, externalDiskId: imageConfig.externalDiskId]
			def addLocation = new VirtualImageLocation(locationConfig)
			add.imageLocations = [addLocation]

			adds << add

		}
		// Create em all!
		if(adds) {
			morpheusContext.async.virtualImage.create(adds, cloud).blockingGet()
		}
	}

	private updateMatchedVirtualImages(List<SyncTask.UpdateItem<VirtualImage, Image>> updateList, String regionCode) {
		def adds = []
		for(def updateItem in updateList) {
			def externalDiskId = updateItem.masterItem.blockDeviceMappings.find { mapping -> mapping.deviceName == updateItem.masterItem.rootDeviceName }?.ebs?.snapshotId

			def locationConfig = [virtualImage: updateItem.existingItem,code:"amazon.ec2.image.${cloud.id}.${updateItem.masterItem.getImageId()}", externalId:updateItem.masterItem.getImageId(),
								  refType:'ComputeZone', refId:cloud.id, imageName:updateItem.masterItem.getName(), imageRegion: regionCode, externalDiskId: externalDiskId]
			adds << new VirtualImageLocation(locationConfig)
		}
		if(adds) {
			morpheusContext.async.virtualImage.location.bulkCreate(adds).blockingGet()
		}
	}

	private updateMatchedVirtualImageLocations(List<SyncTask.UpdateItem<VirtualImageLocation, Image>> updateList, String regionCode) {
		log.debug "updateMatchedVirtualImages: ${cloud} ${regionCode} ${updateList.size()}"
		def saveLocationList = []
		def saveImageList = []
		def virtualImagesById = morpheusContext.async.virtualImage.listById(updateList.collect { it.existingItem.virtualImage.id }).toMap {it.id}.blockingGet()

		for(def updateItem in updateList) {
			def existingItem = updateItem.existingItem
			def virtualImage = virtualImagesById[existingItem.virtualImage.id]
			def cloudItem = updateItem.masterItem
			def save = false
			def saveImage = false
			def state = cloudItem.state == 'available' ? 'Active' : cloudItem.state
			def externalDiskId = cloudItem.blockDeviceMappings.find { mapping -> mapping.deviceName == cloudItem.rootDeviceName }?.ebs?.snapshotId

			if(existingItem.imageRegion != regionCode) {
				existingItem.imageRegion = regionCode
				save = true
			}
			def imageName = cloudItem.name ?: cloudItem.imageId
			if(existingItem.imageName != imageName) {
				existingItem.imageName = imageName

				if(virtualImage.imageLocations?.size() < 2) {
					virtualImage.name = imageName
					saveImage = true
				}
				save = true
			}
			if(existingItem.externalId != cloudItem.imageId) {
				existingItem.externalId = cloudItem.imageId
				save = true
			}
			if(virtualImage.status != state) {
				virtualImage.status = state
				saveImageList << virtualImage
			}
			if(existingItem.externalDiskId != externalDiskId) {
				existingItem.externalDiskId  = externalDiskId
				save = true
			}
			if(save) {
				saveLocationList << existingItem
			}

			def masterTags = cloudItem.tags?.sort { it.key }?.collect { [name: it.key, value: it.value] } ?: []
			if(virtualImage.metadata.sort { it.name }.collect {[name: it.name, value: it.value] }.encodeAsJSON().toString() != masterTags.encodeAsJSON().toString()) {
				virtualImage.metadata = masterTags.collect {
					new MetadataTag(name: it.name, value: it.value)
				}
				saveImage = true
			}

			if(saveImage) {
				saveImageList << virtualImage
			}
		}

		if(saveLocationList) {
			morpheusContext.async.virtualImage.location.save(saveLocationList, cloud).blockingGet()
		}
		if(saveImageList) {
			morpheusContext.async.virtualImage.save(saveImageList.unique(), cloud).blockingGet()
		}
	}

	private removeMissingVirtualImages(Collection<VirtualImageLocationIdentityProjection> removeList) {
		log.debug "removeMissingVirtualImages: ${cloud} ${removeList.size()}"
		morpheusContext.async.virtualImage.location.remove(removeList).blockingGet()
	}

}
